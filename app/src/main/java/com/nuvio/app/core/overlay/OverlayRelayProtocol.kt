package com.nuvio.app.core.overlay

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * The pure half of the relay: request parsing, proxy authentication, the route decision,
 * and the per-process secret.
 *
 * ⚠️ **Nothing here may import `android.*`.** These are the decisions where a mistake is
 * *silent* — a bad parse, a weak comparison, a host routed the wrong way — and they are
 * tested in `androidHostTest` without Robolectric, which is only possible while this file
 * stays pure JVM. A single `android.util.Log` in here costs every test in the file a
 * Robolectric runner, and the SDK pin that comes with it.
 *
 * `java.util.Base64` is also avoided deliberately: this module's `minSdk` is **24**, and
 * that class arrived in API 26. The decoder below is the standard alphabet, which is what
 * a `Proxy-Authorization: Basic` payload always uses — the secret itself never needs
 * decoding, only comparison.
 */

/** The header a client must present to use the relay. Lowercased, as headers are stored. */
internal const val PROXY_AUTH_HEADER = "proxy-authorization"

/** The reply that opens a tunnel. */
internal val ESTABLISHED_RESPONSE: ByteArray =
    "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

/**
 * Where a dial should go.
 *
 * ⚠️ **[DIRECT] is not a refusal.** The allow-set decides *routing*, not *reachability*
 * (decided 2026-10-05): a host outside it is still dialled, just not carried through the
 * tunnel. Turning this into a gate would break every third-party addon.
 */
internal enum class RelayRoute { CARRIED, DIRECT }

/** A parsed `CONNECT` authority. */
internal data class ConnectTarget(val host: String, val port: Int)

/** A parsed request line plus its headers, header names lowercased. */
internal data class HttpHead(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
)

/**
 * Parses a request head (the text before the terminating blank line), or null when it is
 * not a well-formed HTTP request.
 *
 * Strict on purpose. The relay is reachable by any app on the device that guesses the
 * port, so a lenient parser is an attack surface rather than a convenience.
 */
internal fun parseHttpHead(raw: String): HttpHead? {
    val lines = raw.split("\r\n")
    val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val parts = requestLine.split(' ').filter { it.isNotEmpty() }
    if (parts.size != 3) return null
    val (method, target, version) = parts
    if (!version.startsWith("HTTP/")) return null

    val headers = LinkedHashMap<String, String>()
    for (line in lines.drop(1)) {
        if (line.isEmpty()) continue
        val separator = line.indexOf(':')
        // A header line with no colon is malformed. Skipping it would be more forgiving
        // and strictly worse: `Proxy-Authorization` is read out of this map.
        if (separator <= 0) return null
        val name = line.substring(0, separator).trim().lowercase()
        if (name.isEmpty()) return null
        headers[name] = line.substring(separator + 1).trim()
    }
    return HttpHead(method.uppercase(), target, headers)
}

/**
 * The `host:port` of a CONNECT authority, or null when it is not one.
 *
 * ⚠️ **The host charset is validated, not just split.** This value comes from an
 * unauthenticated request line, and it is handed to a socket. Restricting it to the
 * characters a hostname or literal address can actually contain means a crafted authority
 * cannot smuggle anything into the dialler.
 *
 * IPv6 must be bracketed (`[::1]:443`), which is what FFmpeg and OkHttp both send — an
 * unbracketed IPv6 authority is ambiguous with the port separator and is refused rather
 * than guessed at.
 */
internal fun connectTargetOf(authority: String): ConnectTarget? {
    val text = authority.trim()
    if (text.isEmpty()) return null

    val host: String
    val portText: String
    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close <= 1) return null
        host = text.substring(1, close)
        val rest = text.substring(close + 1)
        if (!rest.startsWith(":")) return null
        portText = rest.substring(1)
    } else {
        val colon = text.lastIndexOf(':')
        if (colon <= 0) return null
        host = text.substring(0, colon)
        // ⚠️ An unbracketed IPv6 literal reaches here as `2001:db8::1:443`, and splitting
        // at the last colon would read the host as `2001:db8::1` — but `::1` alone would
        // read as host `:` port 1, which is not an address at all. Refusing the whole
        // shape is the only reading that is right for both, and it is the reading RFC 3986
        // requires: a colon inside an authority without brackets is simply not valid.
        if (host.contains(':')) return null
        portText = text.substring(colon + 1)
    }

    if (host.isEmpty() || !HOST_CHARS.matches(host)) return null
    val port = portText.toIntOrNull() ?: return null
    // Port 0 is not a destination, and out-of-range is a parse failure rather than a
    // wrap-around. The relay never dials either.
    if (port !in 1..65535) return null
    return ConnectTarget(host, port)
}

/** Hostnames and literal addresses only: no slashes, spaces, or control characters. */
private val HOST_CHARS = Regex("^[A-Za-z0-9._:-]+$")

/**
 * True when [headerValue] carries the relay's per-process [secret].
 *
 * ⚠️ **This is the relay's only defence, not defence in depth.** Because the allow-set is
 * a routing decision rather than a gate, the relay will forward to *any* host — so without
 * this check any co-resident app on the device has a free, general-purpose proxy. A
 * co-resident app is the normal case on a phone, not a hypothetical.
 *
 * Two forms are accepted, and both exist for a reason:
 *  * `Basic <base64(user:secret)>` — what FFmpeg produces from a
 *    `http://user:secret@127.0.0.1:port` proxy URL, which is the *only* way libmpv can be
 *    told about a proxy. The user part is ignored; the password must match.
 *  * `Bearer <secret>` — what the app's own Kotlin clients send, via an OkHttp
 *    `proxyAuthenticator`.
 */
internal fun isProxyAuthorized(headerValue: String?, secret: String): Boolean {
    if (headerValue.isNullOrEmpty() || secret.isEmpty()) return false
    val separator = headerValue.indexOf(' ')
    if (separator <= 0) return false
    val scheme = headerValue.substring(0, separator)
    val credentials = headerValue.substring(separator + 1).trim()
    if (credentials.isEmpty()) return false

    return when {
        scheme.equals("Basic", ignoreCase = true) -> {
            val decoded = decodeBase64(credentials)?.toString(Charsets.ISO_8859_1) ?: return false
            // Split on the FIRST colon: a user part may not contain one, but a password
            // could, and truncating at the last colon would then compare the wrong slice.
            val colon = decoded.indexOf(':')
            // ⚠️ A credential with **no colon at all is refused**, not compared whole.
            // Accepting `base64(secret)` as a second spelling of `base64(user:secret)`
            // would widen the set of strings that authenticate for no benefit — the caller
            // still has to know the secret either way — and the documented form is the one
            // libmpv actually produces from the proxy URL, so the lenient branch would
            // never fire in practice and would never be exercised.
            if (colon < 0) false
            else constantTimeEquals(decoded.substring(colon + 1), secret)
        }

        scheme.equals("Bearer", ignoreCase = true) -> constantTimeEquals(credentials, secret)
        else -> false
    }
}

/**
 * Compares without an early exit.
 *
 * Over localhost this is close to theatre — an attacker who can time a loopback comparison
 * can already read the process. It is here because the cost is a few microseconds on a
 * path that runs once per connection, and because the alternative is a habit that is wrong
 * the moment this code is copied somewhere less local.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    val x = a.toByteArray(Charsets.ISO_8859_1)
    val y = b.toByteArray(Charsets.ISO_8859_1)
    var difference = x.size xor y.size
    for (index in 0 until minOf(x.size, y.size)) {
        difference = difference or (x[index].toInt() xor y[index].toInt())
    }
    return difference == 0
}

/**
 * Decodes standard-alphabet Base64, or null on any character outside it.
 *
 * Trailing bits are discarded, which is correct here: the only caller compares the decoded
 * text against a secret, so a padded and an unpadded encoding of the same bytes must both
 * be accepted, and they are.
 */
internal fun decodeBase64(text: String): ByteArray? {
    val out = ByteArrayOutputStream(text.length * 3 / 4 + 3)
    var buffer = 0
    var bits = 0
    for (character in text) {
        if (character == '=') break
        if (character == '\n' || character == '\r' || character == ' ') continue
        val value = when (character) {
            in 'A'..'Z' -> character - 'A'
            in 'a'..'z' -> character - 'a' + 26
            in '0'..'9' -> character - '0' + 52
            '+' -> 62
            '/' -> 63
            else -> return null
        }
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xFF)
        }
    }
    return out.toByteArray()
}

/**
 * Lowercases, strips IPv6 brackets, and drops the root label, so every spelling of one
 * host compares equal.
 *
 * The trailing dot is the fully-qualified form (`bss-tor.tracemonkey.org.`) and is legal
 * in an authority. Without stripping it, an allow-set match fails for a client that sends
 * one — and the failure is silent, because a mismatch simply routes direct.
 */
internal fun normalizeHost(host: String): String =
    host.trim().lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")

/**
 * True when [host] is a loopback name or address.
 *
 * ⚠️ **Wider than the two private `isLoopbackHost` helpers already in the playback code.**
 * Those match the exact string `127.0.0.1` because that is all a playback URL ever names.
 * This one has to cover the whole `127.0.0.0/8` block: it is guarding "the relay must never
 * dial itself", and `127.0.0.2` reaching the same loopback interface is exactly the kind of
 * near-miss that turns a guard into a formality.
 */
internal fun isLoopbackHost(host: String): Boolean {
    val normalized = normalizeHost(host)
    if (normalized == "localhost" || normalized == "::1") return true
    if (normalized == "::ffff:127.0.0.1") return true
    if (!normalized.startsWith("127.")) return false
    val parts = normalized.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.all { it.isDigit() } && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

/**
 * Routes a dial.
 *
 * Loopback goes [RelayRoute.DIRECT] rather than being refused, and that is load-bearing:
 * the P2P engine serves its stream from an in-process loopback server, and libmpv — whose
 * `http-proxy` is a blanket property, not a per-URL one — sends *that* request to the
 * relay too. Refusing loopback would break P2P playback the moment the proxy is set.
 */
internal fun relayRouteFor(host: String, allowSet: Set<String>): RelayRoute {
    val normalized = normalizeHost(host)
    if (isLoopbackHost(normalized)) return RelayRoute.DIRECT
    return if (normalized in allowSet) RelayRoute.CARRIED else RelayRoute.DIRECT
}

/**
 * True when a CONNECT is aimed at the relay itself.
 *
 * A CONNECT to our own port would be accepted, dialled direct back into us, and accepted
 * again — recursing until the process runs out of sockets. Cheap to check, and the failure
 * it prevents is a self-inflicted denial of service.
 */
internal fun isSelfDial(host: String, port: Int, selfPort: Int): Boolean =
    isLoopbackHost(host) && port == selfPort

/**
 * A fresh per-process secret, as lowercase hex.
 *
 * Hex rather than Base64 so it can be pasted into an `http://user:secret@host:port` URL —
 * which is the only shape libmpv accepts — with nothing to escape and no padding to strip.
 * 24 bytes is 192 bits, far past guessable for a value that lives as long as the process.
 */
internal fun newRelaySecret(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
}

private const val HEX = "0123456789abcdef"
