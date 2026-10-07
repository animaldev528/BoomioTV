package com.nuvio.app.core.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OverlayEgress"

/**
 * The egress question's budget, handed to [fetchEgressIp] — which is a real bound, and this is the
 * number it is actually bounded by.
 *
 * ⚠️ **Bounded because it sits on the discovery path**, in front of the names tier's walk. The walk
 * only ever runs after every rung has already missed the gate, so this is the tail of an
 * already-slow path; it buys a correct answer with a bounded delay and must never be allowed to
 * grow into an unbounded one. 1500 ms is roughly five times a healthy round trip to any of the
 * services below, which leaves room for one retry and no room for a hang.
 *
 * ⚠️ **The bound is the budget *plus one attempt*, not the budget exactly** — see [fetchEgressIp]
 * for why a coroutine timeout cannot tighten that. [egressMatchesPublishedWan] splits this figure
 * between its own two waits, so the whole check costs this plus its name resolution.
 */
internal const val EGRESS_BUDGET_MS = 1_500L

/**
 * The most one echo service may spend, and the cap on a single attempt — a shorter remaining budget
 * shortens it further. See [fetchEgressIp] for why the caller's budget, not this, is the bound.
 *
 * ⚠️ A `Long`, not an `Int`, despite always being a small integer: it is combined with [budgetMs]'s
 * arithmetic, and `minOf(Long, Int)` has no applicable overload — Kotlin resolves it to the generic
 * `minOf<T : Comparable<T>>` and infers `Comparable<*>`, which then will not assign to a `Long`.
 */
private const val EGRESS_ATTEMPT_MS = 700L

/**
 * The services asked "what is my public address?", in the order they are asked.
 *
 * ⚠️ **A list rather than one service, and every entry returns a bare address as `text/plain`.**
 * One hard-coded host makes this feature's correctness depend on a stranger's uptime, and a JSON
 * endpoint would mean parsing a body to find a field whose shape can change. A bare address needs
 * the smallest possible trust: if what comes back is not an address, it is not an answer.
 */
internal val EGRESS_ECHO_URLS = listOf(
    "https://ifconfig.me/ip",
    "https://icanhazip.com",
    "https://api.ipify.org",
)

/** An IPv4 literal: four dotted decimal octets. Deliberately not a range check; see [literalOrNull]. */
private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

/** An IPv6 literal is anything hex-and-colons that actually contains a colon. */
private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:]+$")

/**
 * A literal address, or null — **and never a resolution.**
 *
 * ⚠️ **This is the difference between a parser and a resolver, and it is a security boundary.**
 * [InetAddress.getByName] happily accepts a *hostname* and will consult DNS for it, so feeding it an
 * unvalidated body from a third-party service would let that service aim the resolver at a name of
 * its choosing. The regexes above admit only literals, and the colon requirement keeps a bare hex
 * string from being read as a hostname.
 */
internal fun literalOrNull(text: String): InetAddress? {
    val looksLikeV4 = IPV4_LITERAL.matches(text)
    val looksLikeV6 = IPV6_LITERAL.matches(text) && text.contains(':')
    if (!looksLikeV4 && !looksLikeV6) return null
    return runCatching { InetAddress.getByName(text) }.getOrNull()
}

/**
 * The address in an echo service's response body, or null if the body is not one.
 *
 * ⚠️ **Sniff the body, never the status line and never the content-type.** A captive portal, a CDN
 * error page and a rate-limit notice all answer `200 OK`, and the overlay has already been burned
 * once by an API that labelled its own JSON `text/html` (`bsf-loc-en-easynews-endpoint`). "It
 * answered" is not "it answered with an address"; only the literal check can tell them apart, and a
 * body that fails it must read as *no answer at all* rather than as a wrong one.
 */
internal fun parseEgressIp(body: String?): String? {
    val text = body?.trim().orEmpty()
    // 45 is the longest a literal can be (a full IPv6 with an embedded IPv4), so anything longer is
    // a page, not an address.
    if (text.isEmpty() || text.length > 45) return null
    return literalOrNull(text)?.hostAddress
}

/**
 * Whether two address literals are the same address — or null when they cannot be compared.
 *
 * ⚠️ **A family mismatch is `null`, not `false`.** An IPv4-only DuckDNS `A` record against a device
 * whose provider hands it IPv6 can never be equal, and reporting that as "not at home" would send a
 * phone that is sitting on the sofa to the WAN address — which does not hairpin (architecture §7)
 * and would fail. "I cannot compare these" and "these differ" are different answers and only one of
 * them is safe to act on.
 */
internal fun sameFamilyAddress(left: String?, right: String?): Boolean? {
    val a = left?.let { literalOrNull(it) } ?: return null
    val b = right?.let { literalOrNull(it) } ?: return null
    if (a.address.size != b.address.size) return null
    return a.hostAddress.equals(b.hostAddress, ignoreCase = true)
}

/**
 * Whether [candidate] sits inside the subnet [link]/[prefixLength] describes.
 *
 * ⚠️ **This is the offline half of "am I at home", and it is the half that cannot be faked.** The
 * LAN record is a *public* DNS name holding a **private** address (measured: `boomio-lan.duckdns.org`
 * answers `192.168.68.65` from the house resolver and from `1.1.1.1` alike), so it resolves
 * everywhere and is only *routable* in one place. An address that falls inside a subnet this device
 * is genuinely attached to is therefore evidence of home that no third party is involved in.
 *
 * Bit-by-bit rather than by string prefix, because the house is not a `/24`: the deployment
 * advertises `192.168.68.0/22`, so `192.168.68.65` and `192.168.69.5` are the same network and a
 * string comparison would disagree.
 */
internal fun isOnLink(link: InetAddress, prefixLength: Int, candidate: InetAddress): Boolean {
    val a = link.address
    val b = candidate.address
    if (a.size != b.size) return false
    val whole = (prefixLength / 8).coerceIn(0, a.size)
    for (i in 0 until whole) if (a[i] != b[i]) return false
    if (whole >= a.size) return true
    val bits = prefixLength % 8
    if (bits == 0) return true
    val mask = (0xFF shl (8 - bits)) and 0xFF
    return (a[whole].toInt() and mask) == (b[whole].toInt() and mask)
}

/**
 * Every address this device currently holds on its default network, with its prefix length.
 *
 * Empty means "nothing to compare against" — see [OverlayEndpointDiscovery.preferLanFor], which
 * reads that as *unknown* rather than as *not home*.
 */
internal fun localLinkAddresses(context: Context): List<Pair<InetAddress, Int>> {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return emptyList()
    val properties = manager.activeNetwork?.let { manager.getLinkProperties(it) } ?: return emptyList()
    return properties.linkAddresses.mapNotNull { link ->
        val address = link.address ?: return@mapNotNull null
        address to link.prefixLength
    }
}

/**
 * This device's public address as the internet sees it, or null if no service could say.
 *
 * ⚠️ **Direct, and it has to be.** Opening the connection with `Proxy.NO_PROXY` is doing real work
 * here, not documenting a default: the overlay's whole egress story is a loopback CONNECT relay
 * (`OverlayProxy`), and the app has already been bitten by one engine that quietly kept its own
 * `HttpClient` and rode past the tunnel (`SupabaseModule`). The failure is *silent and inverted* —
 * a request that goes through the tunnel leaves at the house, so the address that comes back is the
 * house's WAN address, and the check would answer "you are at home" from a phone on a hotspot. That
 * is this file's bug, reintroduced by a one-line omission.
 *
 * Nothing global needs unwinding for this to be true: the overlay installs no default
 * `ProxySelector`, no default `SSLSocketFactory` and no default `HostnameVerifier` (audited), so a
 * plain `HttpURLConnection` with an explicit `NO_PROXY` reaches the public internet directly.
 */
private fun getDirect(url: String, timeoutMs: Long): String? {
    // `URI(...).toURL()` rather than `URL(...)`: the `URL(String)` constructor is deprecated from
    // Java 20 on, and this file should not be the one that turns a warning into a build failure.
    //
    // ⚠️ **`openConnection(Proxy.NO_PROXY)` is the whole mechanism, and it is not interchangeable
    // with setting a proxy afterwards.** `URLConnection` has no public `setProxy` at all — the proxy
    // is bound at open time — and this overload is the one path that never consults
    // `ProxySelector.getDefault()`. `followRedirect` reuses this connection object rather than
    // opening a fresh one, so the bypass survives a redirect too.
    val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
    // One figure for both, so a single attempt can never cost more than the caller granted it.
    val timeout = timeoutMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    connection.connectTimeout = timeout
    connection.readTimeout = timeout
    connection.instanceFollowRedirects = true
    return try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) null
        else parseEgressIp(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        runCatching { connection.disconnect() }
    }
}

/**
 * The first echo service to return something that parses as an address, or null. See [getDirect].
 *
 * ⚠️ **The loop is deadline-driven, because a coroutine timeout cannot bound it.** Every attempt is
 * a *blocking* `HttpURLConnection` call, and `withTimeoutOrNull` around a blocking call returns only
 * once that call has already finished — so wrapping this loop in one would leave the budget
 * advisory: three services, each free to spend [EGRESS_ATTEMPT_MS] on connect and again on read,
 * would overrun [budgetMs] three times over while the timeout sat there waiting. The remaining
 * budget is therefore measured *before* each attempt and handed to that attempt as its own timeout.
 *
 * The honest guarantee is `budgetMs` **plus at most one attempt**, and the last attempt is the one
 * that can overrun it: a service entered at the deadline still gets its full timeout. That is the
 * number [EGRESS_BUDGET_MS] documents, rather than the tighter one this code cannot actually keep.
 */
internal suspend fun fetchEgressIp(budgetMs: Long): String? =
    withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + budgetMs * 1_000_000L
        for (url in EGRESS_ECHO_URLS) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) {
                Log.d(TAG, "Out of egress budget; not trying '$url'")
                break
            }
            val ip = runCatching { getDirect(url, minOf(remainingMs, EGRESS_ATTEMPT_MS)) }
                .onFailure { Log.d(TAG, "'$url' did not answer: ${it.message}") }
                .getOrNull()
            if (ip != null) {
                Log.i(TAG, "Public address as seen from here: $ip (via $url)")
                return@withContext ip
            }
        }
        Log.w(TAG, "No echo service answered within ${budgetMs}ms (${EGRESS_ECHO_URLS.size} tried)")
        null
    }

/**
 * Whether this device's public address is the one [wanName] publishes — or null when no verdict can
 * be reached. **Never throws, and never exceeds [budgetMs] by more than one echo attempt** (see
 * [fetchEgressIp], which is where that overrun lives; the figure here is split between the two
 * waits this check makes rather than spent twice).
 *
 * ⚠️ **A WAN name that resolves to a private address yields `null`, not `false`.** The whole value
 * of this test is that it compares two *public* addresses; if the published name answers with
 * something private, the deployment is misconfigured or the name has been repointed, and the honest
 * answer is "I cannot tell" so the caller falls through to the on-link test rather than acting on a
 * comparison that could not have been meaningful.
 */
internal suspend fun egressMatchesPublishedWan(wanName: String, budgetMs: Long): Boolean? {
    // ⚠️ Split rather than spent twice: resolving the published name and asking the internet are
    // two waits, and handing each the *whole* budget would make this check cost twice what its
    // caller was told it costs. Resolution gets a third and answers in tens of milliseconds when it
    // answers at all, so the probe keeps the bulk.
    val resolveBudget = budgetMs / 3
    val published = resolveHost(wanName, resolveBudget) ?: return null
    if (published.isSiteLocalAddress || published.isLoopbackAddress || published.isLinkLocalAddress) {
        Log.d(TAG, "'$wanName' resolved to a non-public address (${published.hostAddress}); no verdict")
        return null
    }
    val egress = fetchEgressIp(budgetMs - resolveBudget) ?: return null
    return sameFamilyAddress(published.hostAddress, egress)
}
