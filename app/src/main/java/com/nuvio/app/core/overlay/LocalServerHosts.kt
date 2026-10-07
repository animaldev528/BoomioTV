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
 * domain as the server**. A multi-label public suffix (`co.uk`) would over-match; the
 * pin is pin-first, so the cost of an over-match is one failed connect, not a break.
 *
 * [OverlayPinRegistry] reuses this at *lookup* time, not only to filter addon hosts.
 * That is what covers the media-plane hosts (`bss-dav`, `bss-tor`, `nzbdav`, …), which
 * appear in no configuration and in no manifest — they arrive inside the stream URLs
 * bsf returns, so no enumeration performed earlier in the session can contain them.
 */
internal fun serverDomainSuffixes(serverHosts: Set<String>): Set<String> = serverHosts
    .mapNotNull { host ->
        val labels = host.split('.').filter { it.isNotBlank() }
        if (labels.size < 2) null else labels.takeLast(2).joinToString(".")
    }
    .toSet()

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
