package com.nuvio.app.core.overlay

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.isPublicServerHost
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.boomio.BoomioConfig

/**
 * The registry host of [url], lowercased, or null when it is blank or unparseable.
 *
 * ⚠️ The `://` check is load-bearing, not cosmetic. Ktor's `URLBuilder` defaults
 * `host` to `"localhost"`, so `Url("")` and `Url("nonsense")` both parse
 * **successfully** into a URL that claims to be localhost. Without the check this
 * helper hands out `"localhost"` for every blank config value — which is how a blank
 * URL would have matched a blank fallback and made two servers look like one.
 */
internal fun hostOf(url: String): String? {
    if (!url.contains("://")) return null
    return runCatching { io.ktor.http.Url(url).host.lowercase() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

/**
 * The subset of [urls] that may be pinned to a discovered LAN address.
 *
 * A host qualifies only when [isPublicServerHost] accepts it, which already rejects
 * `.local` and RFC1918 — so a discovered `.local` name can never itself become a pin
 * target, and the pin can never point the app at an address it already uses.
 */
internal fun derivePinnableHosts(urls: Iterable<String>): Set<String> = urls.asSequence()
    .filter { it.isNotBlank() }
    .filter { isPublicServerHost(it) }
    .mapNotNull { hostOf(it) }
    .toSet()

/**
 * The configured URLs that could name the user's own server.
 *
 * ⚠️ **[ServerConfiguration.fallbackBackendUrl] is deliberately absent.** It is the
 * Supabase *cloud* fallback, not a boomio Caddy host; pinning it to a LAN address
 * would break the very failover it exists for. A unit test asserts its absence, so
 * adding it here is caught rather than shipped.
 *
 * [BoomioConfig.boomioBaseUrl] and [BoomioConfig.bsmBaseUrl] are blank in this
 * repository — a host application assigns them — so they are read, never assumed.
 */
internal fun localServerHostCandidates(): List<String> = listOf(
    ServerConfigurationRepository.active.value.backendUrl,
    BoomioConfig.companionBaseUrl,
    BoomioConfig.iptvBaseUrl,
    BoomioConfig.boomioBaseUrl,
    BoomioConfig.bsmBaseUrl,
)

/**
 * The registrable-domain suffixes of [serverHosts] — the last two labels of each.
 *
 * A heuristic, and a deliberately conservative one: it is anchored on hosts the user's
 * own configuration already named, so it can only widen the pin to hosts on the **same
 * domain as the server**.
 *
 * ⚠️ **`labels.takeLast(2)` alone is not enough, and the two cases are not symmetric.**
 * For `bsc.tracemonkey.org` those labels are a domain the owner controls, so widening is
 * exactly what reaches `tmdb.tracemonkey.org` and the rest of the media plane — the point
 * of the function. For `boomio-tls.duckdns.org` they are **`duckdns.org`**, which the owner
 * does not control and which anyone can sign up under: the suffix would then match *every*
 * `*.duckdns.org` host this app contacts and resolve it to the tunnel address instead of
 * its real one. That is not "one failed connect" — it is every such request in the session
 * answered by the wrong server. [SHARED_SUFFIXES] is what separates the two cases, and the
 * fallback for a match is the **host itself**, so the device's own name stays pinned.
 *
 * ⚠️ **A short list rather than the Public Suffix List, deliberately.** The full PSL is
 * ~240 KB with its own update cadence, and this deployment has exactly one shared suffix in
 * it. Adding one is a one-line change, and the bias is chosen so that being *wrong* is
 * cheap: a name listed here that did not need to be is pinned by exact host instead of by
 * domain, which loses coverage rather than gaining a wrong one.
 *
 * [OverlayPinRegistry] reuses this at *lookup* time, not only to filter addon hosts.
 * That is what covers the media-plane hosts (`bss-dav`, `bss-tor`, `nzbdav`, …), which
 * appear in no configuration and in no manifest — they arrive inside the stream URLs
 * bsf returns, so no enumeration performed earlier in the session can contain them.
 */
internal fun serverDomainSuffixes(serverHosts: Set<String>): Set<String> = serverHosts
    .mapNotNull { host ->
        val labels = host.split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return@mapNotNull null
        val lastTwo = labels.takeLast(2).joinToString(".")
        // Not a name the owner registered: pin this host, never its shared parent.
        if (lastTwo in SHARED_SUFFIXES) host else lastTwo
    }
    .toSet()

/**
 * The two-label suffixes that are **shared** — the registrable name is the whole host, so the
 * label above them belongs to whoever signed up, not to the user.
 *
 * `duckdns.org` is the measured entry: `boomio-tls.duckdns.org` is a companion host in the
 * reference deployment, and `docs/mtls-plan.md` §13 records the over-match it would otherwise
 * cause. The rest are the same *shape* — free dynamic-DNS names and shared app hosting, where
 * `anything.<suffix>` is a stranger — included because the cost of a missing entry is the
 * over-match above, while the cost of a spare one is only a narrower pin.
 *
 * Only ever compared against the last **two** labels, so a longer suffix (the PSL's
 * `foo.bar.ck` style) cannot appear here: it would be dead weight that never matches.
 */
private val SHARED_SUFFIXES = setOf(
    // Dynamic DNS: `boomio-tls.duckdns.org` is the case this exists for.
    "duckdns.org",
    "ddns.net",
    "dynu.net",
    "no-ip.org",
    "hopto.org",
    "myftp.org",
    "sytes.net",
    "zapto.org",
    "afraid.org",
    // Shared app hosting and tunnels, where `foo.<suffix>` is somebody else's deployment.
    "github.io",
    "pages.dev",
    "vercel.app",
    "netlify.app",
    "herokuapp.com",
    "trycloudflare.com",
    "ngrok.io",
    "ngrok-free.app",
)

/**
 * The addon manifest hosts served by the *same server* as [serverHosts].
 *
 * ⚠️ **This is where most of the app's server hosts actually live, and leaving them out
 * breaks the home screen.** The catalogue is assembled from addons, and on the reference
 * deployment 19 of 21 sit on the server's own domain — 17 row addons on
 * `tmdb.tracemonkey.org`, plus `bsf.` and `usn.`. A pin set derived only from the server
 * configuration therefore covers auth and the companion plane and leaves the entire
 * catalogue plane resolving to the public edge.
 *
 * Observed on device 2026-10-05, with the phone's DNS set to a public resolver: the
 * pinned Supabase call answered (`PGRST202` from PostgREST) while every addon request
 * went to `153.68.210.49` — the WAN address, unreachable from the LAN because hairpin
 * NAT is off — and timed out. To the user that is "no posters and no playback".
 *
 * Filtered to the server's own domain so a third-party addon (a public Stremio addon,
 * `catalog.nuvio.tv`) keeps resolving publicly. Those are not the user's server, and
 * repointing them at a LAN address would be simply wrong.
 */
internal fun derivePinnableAddonHosts(
    serverHosts: Set<String>,
    addonUrls: Iterable<String>,
): Set<String> {
    if (serverHosts.isEmpty()) return emptySet()
    val suffixes = serverDomainSuffixes(serverHosts)
    return derivePinnableHosts(addonUrls)
        .filter { host -> host in serverHosts || suffixes.any { host.endsWith(".$it") } }
        .toSet()
}

/** The manifest URLs of the addons installed for the active profile. */
private fun addonManifestUrls(): List<String> =
    AddonRepository.uiState.value.addons.map { it.manifestUrl }

/**
 * The hosts the overlay may repoint at a LAN address. Derived at runtime, never hardcoded.
 *
 * ⚠️ **Read live, never cached.** The addon catalogue arrives asynchronously, seconds
 * after the first browse, so this same call returns a *larger* set later in the session.
 * Callers must ask again when the addon set changes rather than pinning once at browse
 * time — see `OverlayLocalDiscovery.observeAddonChanges`.
 */
internal fun localServerHosts(): Set<String> {
    val serverHosts = derivePinnableHosts(localServerHostCandidates())
    return serverHosts + derivePinnableAddonHosts(serverHosts, addonManifestUrls())
}
