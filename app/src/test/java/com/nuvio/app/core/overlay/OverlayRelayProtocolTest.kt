package com.nuvio.app.core.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The relay's parsing, authentication and routing decisions, with no device and no socket.
 *
 * ⚠️ **These are the decisions where a mistake is silent.** A bad parse reports 400 to
 * something that was fine; a bad auth check either locks every client out of playback or
 * hands any co-resident app a general-purpose proxy; a bad route quietly sends boomio
 * traffic around the tunnel it was built for. None of them produce a crash, and none of
 * them are visible in a playback test — so they are tested here, exhaustively.
 *
 * Deliberately **not** a Robolectric test: `OverlayRelayProtocol.kt` imports nothing from
 * `android.*`, and keeping it that way is what makes this file cheap enough to run on
 * every change. Adding one `Log.d` there would cost all of it an SDK-pinned runner.
 *
 * The Base64 literals below were produced by an independent encoder, not by
 * [decodeBase64]. An oracle written with the code under test agrees with it by
 * construction and proves nothing.
 */
class OverlayRelayProtocolTest {

    private val secret = "s3cr3t"

    // ---------------------------------------------------------------- request parsing

    @Test
    fun `a connect is parsed with its authority and headers`() {
        val head = assertNotNull(
            parseHttpHead(
                "CONNECT bss-tor.tracemonkey.org:443 HTTP/1.1\r\n" +
                    "Host: bss-tor.tracemonkey.org:443\r\n" +
                    "Proxy-Authorization: Basic Ym9vbWlvOnMzY3IzdA==\r\n",
            ),
        )

        assertEquals("CONNECT", head.method)
        assertEquals("bss-tor.tracemonkey.org:443", head.target)
        assertEquals("Basic Ym9vbWlvOnMzY3IzdA==", head.headers["proxy-authorization"])
        assertEquals("bss-tor.tracemonkey.org:443", head.headers["host"])
    }

    @Test
    fun `header names are lowercased and the method is uppercased`() {
        // ⚠️ Both matter. The auth header is looked up by its lowercase name, so a client
        // sending `PROXY-AUTHORIZATION` — which is legal HTTP — would otherwise be
        // rejected with a 407 it could never satisfy. The method is compared against the
        // literal `CONNECT`, and a lowercase `connect` is equally legal.
        val head = parseHttpHead("connect example.com:443 HTTP/1.1\r\nPROXY-AUTHORIZATION: Bearer x\r\n")

        assertEquals("CONNECT", head?.method)
        assertEquals("Bearer x", head?.headers?.get("proxy-authorization"))
    }

    @Test
    fun `surrounding whitespace in a header value is trimmed`() {
        val head = parseHttpHead("CONNECT a:1 HTTP/1.1\r\nHost:   a:1   \r\n")

        assertEquals("a:1", head?.headers?.get("host"))
    }

    @Test
    fun `an empty header value survives as empty`() {
        // Real clients send `Proxy-Authorization:` with nothing after it. That must reach
        // the auth check as an empty string and fail there, rather than being dropped and
        // read as "no header" — the two are the same outcome here, but only one of them
        // keeps the parse honest.
        val head = parseHttpHead("CONNECT a:1 HTTP/1.1\r\nProxy-Authorization:\r\n")

        assertEquals("", head?.headers?.get("proxy-authorization"))
    }

    @Test
    fun `a malformed request head is refused`() {
        assertNull(parseHttpHead(""))
        assertNull(parseHttpHead("\r\n"))
        assertNull(parseHttpHead("CONNECT a:1\r\n"))                       // no version
        assertNull(parseHttpHead("CONNECT a:1 HTTP/1.1 extra\r\n"))        // four fields
        assertNull(parseHttpHead("CONNECT a:1 NOTHTTP/1.1\r\n"))           // not HTTP
        assertNull(parseHttpHead("CONNECT a:1 HTTP/1.1\r\nnocolon\r\n"))   // header w/o colon
        assertNull(parseHttpHead("CONNECT a:1 HTTP/1.1\r\n: empty-name\r\n"))
    }

    @Test
    fun `a repeated header keeps the last value`() {
        // Pinned as a decision rather than left to chance: `Proxy-Authorization` sent
        // twice is ambiguous, and "last wins" is what every HTTP library does. Whatever it
        // keeps, the value must still be one this relay can authenticate — a smuggled
        // first header cannot ride along in the map.
        val head = parseHttpHead(
            "CONNECT a:1 HTTP/1.1\r\nProxy-Authorization: Bearer wrong\r\n" +
                "Proxy-Authorization: Bearer " + secret + "\r\n",
        )

        assertEquals("Bearer $secret", head?.headers?.get("proxy-authorization"))
        assertTrue(isProxyAuthorized(head?.headers?.get("proxy-authorization"), secret))
    }

    // --------------------------------------------------------------------- authorities

    @Test
    fun `a host and port authority is split`() {
        val target = connectTargetOf("bss-tor.tracemonkey.org:443")

        assertEquals("bss-tor.tracemonkey.org", target?.host)
        assertEquals(443, target?.port)
    }

    @Test
    fun `a bracketed ipv6 authority is split`() {
        // The bracketed form is what both FFmpeg and OkHttp send, and it is the only
        // unambiguous one — `::1:443` could be read either way.
        val target = connectTargetOf("[2001:db8::1]:8443")

        assertEquals("2001:db8::1", target?.host)
        assertEquals(8443, target?.port)
    }

    @Test
    fun `an unbracketed ipv6 authority is refused rather than guessed`() {
        assertNull(connectTargetOf("2001:db8::1:443"))
        assertNull(connectTargetOf("::1"))
    }

    @Test
    fun `a port is required and must be in range`() {
        assertNull(connectTargetOf("example.com"))       // no port
        assertNull(connectTargetOf("example.com:"))      // empty port
        assertNull(connectTargetOf("example.com:0"))     // not a destination
        assertNull(connectTargetOf("example.com:65536")) // out of range, not wrapped
        assertNull(connectTargetOf("example.com:-1"))
        assertNull(connectTargetOf("example.com:https"))
        assertNull(connectTargetOf(""))
        assertNull(connectTargetOf("   "))
    }

    @Test
    fun `a host carrying anything but hostname characters is refused`() {
        // ⚠️ This value comes off an unauthenticated request line and is handed straight
        // to a socket. Every one of these is a shape that has no business being there.
        assertNull(connectTargetOf("/etc/passwd:443"))
        assertNull(connectTargetOf("example.com/evil:443"))
        assertNull(connectTargetOf("exa mple.com:443"))
        assertNull(connectTargetOf("example.com\r\nX: 1:443"))
        assertNull(connectTargetOf(":443"))              // empty host
    }

    @Test
    fun `surrounding whitespace around an authority is tolerated`() {
        assertEquals(443, connectTargetOf("  example.com:443  ")?.port)
    }

    // ----------------------------------------------------------------------- the secret

    @Test
    fun `a fresh secret is 192 bits of hex`() {
        val first = newRelaySecret()
        val second = newRelaySecret()

        assertEquals(48, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        // Distinct, so a broken RNG that returned a constant would be caught here rather
        // than by a co-resident app guessing the value.
        assertFalse(first == second)
    }

    @Test
    fun `basic credentials are accepted when the password matches`() {
        // `Ym9vbWlvOnMzY3IzdA==` is base64("boomio:s3cr3t").
        assertTrue(isProxyAuthorized("Basic Ym9vbWlvOnMzY3IzdA==", secret))
    }

    @Test
    fun `the basic user part is ignored`() {
        // ⚠️ Load-bearing, not sloppiness. libmpv can only be handed a proxy as a URL
        // (`http://user:pass@host:port`), so the user part is whatever the app chose to
        // write there and carries no meaning. Requiring a particular one would break
        // playback the moment the URL's user was changed.
        assertTrue(isProxyAuthorized("Basic dXNlcjpzM2NyM3Q=", secret))    // user:s3cr3t
        assertTrue(isProxyAuthorized("Basic bm9ib2R5OnMzY3IzdA==", secret)) // nobody:s3cr3t
        assertTrue(isProxyAuthorized("Basic OnMzY3IzdA==", secret))         // :s3cr3t
    }

    @Test
    fun `a basic password containing a colon is compared whole`() {
        // ⚠️ Splitting on the *last* colon would compare `ss` here and reject a correct
        // credential. The first colon is the user/password separator; everything after it
        // is the password.
        assertTrue(isProxyAuthorized("Basic dXNlcjpwYTpzcw==", "pa:ss"))
        assertFalse(isProxyAuthorized("Basic dXNlcjpwYTpzcw==", "ss"))
    }

    @Test
    fun `wrong basic credentials are refused`() {
        assertFalse(isProxyAuthorized("Basic dXNlcjp3cm9uZw==", secret))        // user:wrong
        assertFalse(isProxyAuthorized("Basic dXNlcjpzM2NyM3Qh", secret))        // user:s3cr3t!
        assertFalse(isProxyAuthorized("Basic czNjcjN0", secret))                // bare s3cr3t, no colon
        assertFalse(isProxyAuthorized("Basic " + "A".repeat(24), secret))
    }

    @Test
    fun `padding in a basic credential is optional`() {
        // `dXNlcjpzM2NyM3Q` is the unpadded form of `dXNlcjpzM2NyM3Q=`. Both are legal,
        // and clients differ on whether they pad.
        assertTrue(isProxyAuthorized("Basic dXNlcjpzM2NyM3Q=", secret))
        assertTrue(isProxyAuthorized("Basic dXNlcjpzM2NyM3Q", secret))
    }

    @Test
    fun `a bearer token is accepted only when it matches exactly`() {
        assertTrue(isProxyAuthorized("Bearer $secret", secret))
        assertTrue(isProxyAuthorized("Bearer  $secret  ", secret))  // surrounding space trimmed
        assertFalse(isProxyAuthorized("Bearer wrong", secret))
        assertFalse(isProxyAuthorized("Bearer $secret$secret", secret))
    }

    @Test
    fun `the auth scheme is matched case-insensitively`() {
        assertTrue(isProxyAuthorized("basic Ym9vbWlvOnMzY3IzdA==", secret))
        assertTrue(isProxyAuthorized("BASIC Ym9vbWlvOnMzY3IzdA==", secret))
        assertTrue(isProxyAuthorized("bearer $secret", secret))
    }

    @Test
    fun `a missing, empty or unparseable credential is refused`() {
        assertFalse(isProxyAuthorized(null, secret))
        assertFalse(isProxyAuthorized("", secret))
        assertFalse(isProxyAuthorized("Basic", secret))       // scheme with no credential
        assertFalse(isProxyAuthorized("Basic ", secret))
        assertFalse(isProxyAuthorized("Nonsense xyz", secret))
        assertFalse(isProxyAuthorized(secret, secret))         // bare token, no scheme
        // A character outside the standard alphabet is a parse failure, not a mismatch
        // that happens to be unequal.
        assertFalse(isProxyAuthorized("Basic dXNlcjpzM2NyM3Q*", secret))
    }

    @Test
    fun `an empty configured secret authorises nobody`() {
        // The guard that keeps a bug in secret generation from producing a relay that
        // accepts `Basic <anything-before-the-colon>`.
        assertFalse(isProxyAuthorized("Basic OnMzY3IzdA==", ""))
        assertFalse(isProxyAuthorized("Bearer ", ""))
    }

    // ------------------------------------------------------------------ base64 decoding

    @Test
    fun `base64 decodes to the expected bytes`() {
        assertEquals("boomio:s3cr3t", decodeBase64("Ym9vbWlvOnMzY3IzdA==")?.asText())
        assertEquals("user:s3cr3t", decodeBase64("dXNlcjpzM2NyM3Q=")?.asText())
        assertEquals("a", decodeBase64("YQ==")?.asText())
        assertEquals("ab", decodeBase64("YWI=")?.asText())
        assertEquals("abc", decodeBase64("YWJj")?.asText())
        assertEquals("", decodeBase64("")?.asText())
    }

    @Test
    fun `unpadded base64 decodes to the same bytes`() {
        assertEquals("a", decodeBase64("YQ")?.asText())
        assertEquals("ab", decodeBase64("YWI")?.asText())
    }

    @Test
    fun `an invalid base64 character makes the whole decode null`() {
        // Not a partial decode: a truncated result would compare unequal and look like a
        // wrong password rather than a malformed header.
        assertNull(decodeBase64("YWJj!"))
        assertNull(decodeBase64("YWJj*"))
    }

    @Test
    fun `base64 line wrapping is tolerated`() {
        // Some clients wrap long credentials. The decoder skips the wrapping, which is
        // what lets a future longer secret stay compatible with them. Whitespace inside a
        // credential can only ever cause a *rejection* — the decoded bytes still have to
        // equal the secret exactly — so tolerating it is safe as well as compatible.
        assertEquals("user:s3cr3t", decodeBase64("dXNlcjpz\r\nM2NyM3Q=")?.asText())
        assertEquals("abc", decodeBase64("YW Jj")?.asText())
    }

    // ------------------------------------------------------------------------- routing

    @Test
    fun `an allow-set host is carried and anything else goes direct`() {
        // ⚠️ DIRECT is a route, not a refusal. A third-party addon is still dialled — over
        // the ordinary network — and turning this into a gate would break every addon the
        // user installed.
        val allow = setOf("bss-tor.tracemonkey.org", "bss-dav.tracemonkey.org")

        assertEquals(RelayRoute.CARRIED, relayRouteFor("bss-tor.tracemonkey.org", allow))
        assertEquals(RelayRoute.DIRECT, relayRouteFor("catalog.nuvio.tv", allow))
        assertEquals(RelayRoute.DIRECT, relayRouteFor("", allow))
        assertEquals(RelayRoute.DIRECT, relayRouteFor("bss-tor.tracemonkey.org", emptySet()))
    }

    @Test
    fun `routing ignores host case and a trailing root label`() {
        // The fully-qualified spelling (`…org.`) is legal in an authority and means the
        // same host. Missing this would route a boomio host direct, silently — a mismatch
        // is not an error, it is just a different route.
        val allow = setOf("bss-tor.tracemonkey.org")

        assertEquals(RelayRoute.CARRIED, relayRouteFor("BSS-TOR.TRACEMONKEY.ORG", allow))
        assertEquals(RelayRoute.CARRIED, relayRouteFor("bss-tor.tracemonkey.org.", allow))
    }

    @Test
    fun `loopback routes direct even when the allow set claims it`() {
        // ⚠️ The P2P engine serves its stream from an in-process loopback server, and
        // libmpv's `http-proxy` is a blanket property rather than a per-URL one — so that
        // request arrives here too. Carrying it through the tunnel would send the peer's
        // own local stream out over WireGuard and back.
        val allow = setOf("127.0.0.1", "localhost")

        assertEquals(RelayRoute.DIRECT, relayRouteFor("127.0.0.1", allow))
        assertEquals(RelayRoute.DIRECT, relayRouteFor("localhost", allow))
        assertEquals(RelayRoute.DIRECT, relayRouteFor("127.0.0.2", allow))
    }

    @Test
    fun `loopback is recognised across the whole block`() {
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("127.0.0.2"))
        assertTrue(isLoopbackHost("127.255.255.254"))
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(isLoopbackHost("LOCALHOST"))
        assertTrue(isLoopbackHost("::1"))
        assertTrue(isLoopbackHost("[::1]"))
    }

    @Test
    fun `a near miss is not loopback`() {
        // ⚠️ `128.0.0.1` shares every character of its prefix with `127.0.0.1` up to the
        // octet that matters. These are the shapes a guard written as `startsWith("127")`
        // would wave through.
        assertFalse(isLoopbackHost("128.0.0.1"))
        assertFalse(isLoopbackHost("127.0.0"))
        assertFalse(isLoopbackHost("127.0.0.1.1"))
        assertFalse(isLoopbackHost("127.0.0.256"))
        assertFalse(isLoopbackHost("127.0.0.x"))
        assertFalse(isLoopbackHost("1270.0.0.1"))
        assertFalse(isLoopbackHost("10.77.0.1"))
        assertFalse(isLoopbackHost(""))
    }

    @Test
    fun `a connect to the relay's own port is a self dial`() {
        assertEquals(true, isSelfDial("127.0.0.1", 41234, 41234))
        assertEquals(true, isSelfDial("localhost", 41234, 41234))
        // Same address, different port: an ordinary loopback service, not us.
        assertEquals(false, isSelfDial("127.0.0.1", 8080, 41234))
        // Our port, someone else's machine.
        assertEquals(false, isSelfDial("10.77.0.1", 41234, 41234))
    }

    @Test
    fun `host normalization strips brackets, case and the root label`() {
        assertEquals("::1", normalizeHost("[::1]"))
        assertEquals("example.com", normalizeHost("  ExAmPlE.CoM  "))
        assertEquals("example.com", normalizeHost("example.com."))
        // A bare loopback literal must survive: stripping a trailing dot from `127.0.0.1`
        // would leave `127.0.0.1` intact, but the guard has to be a suffix check rather
        // than a truncation, and this is what pins that.
        assertEquals("127.0.0.1", normalizeHost("127.0.0.1"))
    }

    // -------------------------------------------------------------------- the comparison

    @Test
    fun `constant time comparison agrees with equality`() {
        assertTrue(constantTimeEquals("s3cr3t", "s3cr3t"))
        assertTrue(constantTimeEquals("", ""))
        assertFalse(constantTimeEquals("s3cr3t", "s3cr34"))
        assertFalse(constantTimeEquals("s3cr3t", "s3cr3t "))
        assertFalse(constantTimeEquals("s3cr3t", "s3cr3"))
        assertFalse(constantTimeEquals("s3cr3", "s3cr3t"))
        assertFalse(constantTimeEquals("", "s3cr3t"))
    }

    @Test
    fun `a shared prefix is not a match`() {
        // The property that matters: length is folded into the same accumulator as
        // content, so a 47-character prefix of a 48-character secret is not "close".
        val half = "0123456789abcdef0123456789abcdef0123456789abcdef"
        assertFalse(constantTimeEquals(half.take(47), half))
        assertTrue(constantTimeEquals(half, half))
    }
}

private fun ByteArray.asText(): String = toString(Charsets.ISO_8859_1)
