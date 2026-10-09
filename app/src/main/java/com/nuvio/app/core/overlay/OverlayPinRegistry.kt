package com.nuvio.app.core.overlay

import android.util.Log
import java.net.InetAddress

/**
 * The address a public FQDN is pinned to, and which source put it there.
 *
 * The pin is **address-only**. It never rewrites a URL: Caddy serves certificates per
 * named site block, so `https://192.168.68.65/` and `https://beamstream.local/` both
 * fail TLS. Only the *resolution* of a name changes, which is why this is a DNS seam
 * and not a base-URL swap. The same holds for the overlay address: `https://10.77.0.1/`
 * carries no name, so Caddy has no site block to answer it — the name has to survive
 * and only its address may change.
 *
 * ⚠️ **A pin covers a domain, not a list of hosts.** The server's edge is ~18 site
 * blocks — `bsc`, `bsf`, `tmdb`, `usn`, `bss-iptv`, `bss-dav`, `bss-tor`, `bss-local`,
 * `bss-en`, `nzbdav`, `easynews`, `aiot`, `hydra`, `lists`, `dmm`, `bsm`, `grafana` —
 * and the app learns most of them **only at runtime**, from the stream URLs bsf hands
 * back. A pin set built by enumerating configuration and addon manifests is therefore
 * always one plane short, and it was short twice before this: once missing Supabase,
 * once missing the whole addon plane.
 *
 * That is not a hypothetical. Measured on device 2026-10-05, with the app otherwise
 * fully working, the player sat on
 *
 *     SocketTimeoutException: failed to connect to
 *         bss-dav.tracemonkey.org/153.68.210.49 (port 443) after 15000ms
 *
 * `153.68.210.49` is the public WAN address, which hairpin NAT cannot reach from the
 * LAN, so every attempt burned the full 15 s and the stream buffered forever. The
 * catalogue worked throughout, because the hosts *it* uses are config- and
 * manifest-derived and were pinned. To the user that reads as "posters and search
 * work, nothing plays".
 *
 * So [lookup] matches a host **on the pinned domain** as well as an exact host. The
 * suffix is derived from the pinned hosts, which are themselves already filtered to
 * the server's own registrable domain (see `derivePinnableAddonHosts`), so a
 * third-party host — `image.tmdb.org`, `catalog.nuvio.tv`, `opensubtitles-v3.strem.io`,
 * the Supabase cloud — can never match and keeps resolving publicly.
 *
 * ⚠️ **One pin per source, and the sources are ranked.** The three sources pin the same host set
 * to different addresses — the overlay address, the server's address on its own network, and its
 * public address — and they are driven by independent triggers that know nothing about each
 * other. A single slot would let the last writer win, so a tunnel probe landing a second after a
 * browse would silently replace a working LAN pin. Sources are held separately and consulted in
 * [LocalServerSource] order instead.
 *
 * ⚠️ **Ranking decides the order; [ownTunnelCarriesTraffic] decides whether the two direct arms
 * of it are consulted at all.** Both are load-bearing and neither replaces the other.
 *
 * ⚠️ **A reader gets the whole chain, not just the winner** — see [lookupChain]. The two direct
 * arms are the same server seen from two places, and the client has no way to know which place
 * it is in, so it is handed both and lets the connect decide.
 *
 * Held as one immutable map behind `@Volatile` and replaced copy-on-write rather than
 * guarded by a lock: a `lookup` runs on a network thread for *every* connect and must
 * never block, and a reader needs one consistent snapshot rather than several fields
 * read one at a time. The address is [InetAddress] rather than a string so a link-local
 * scope id survives.
 */
internal object OverlayPinRegistry {

    private const val TAG = "OverlayPinRegistry"

    private class Pin(
        val hosts: Set<String>,
        val suffixes: Set<String>,
        val address: InetAddress,
    )

    @Volatile
    private var pins: Map<LocalServerSource, Pin> = emptyMap()

    /**
     * Whether the app's **own** userspace tunnel is currently carrying boomio traffic.
     *
     * ⚠️ **This exists because the LAN pin has to be *kept* but not *followed* at home, and
     * those are two different things.** Path B (see `OverlayTunnel`'s two-paths doc) installs
     * no kernel route and therefore pins nothing — which left the LAN pin, placed a moment
     * earlier by the mDNS browse, as the only pin in the registry and so the route every
     * client took. The tunnel was up and carrying nothing; the relay was idle. Measured on
     * device 2026-10-07: the home screen read "Local server detected — 192.168.68.65" with a
     * healthy overlay in place.
     *
     * Skipping `LAN` at lookup time rather than declining to place the pin is deliberate:
     * holding it costs nothing, the browse's work is not thrown away when the tunnel drops,
     * and no window opens in which a *removed* pin would have to be rebuilt. A lookup runs on
     * a network thread per connect, so this reads the tunnel's own `StateFlow` live and
     * follows the transition in both directions with no bookkeeping and no ordering hazard.
     *
     * ⚠️ **Both halves are required, and the relay half is the one that is easy to omit.** The
     * tunnel and the relay come up on independent lifecycles, so `TunnelState.Up` alone is
     * reachable while `OverlayRelay` never bound — a failed bind, or a `start` that has not run
     * yet. In that state no client has a proxy URL (`RelayProxySelector` answers `DIRECT`), so
     * suppressing the LAN pin would send every request to the public WAN address with no
     * tunnel behind it, and hairpin is off: the app would be *less* able to reach the server
     * than before this change. Requiring the relay means the pin is dropped only once something
     * is actually carrying the traffic. `RelayState.Up` is published in the same synchronized
     * block that assigns the handle, so it is exact rather than approximate, and reading it
     * costs no allocation on a path that runs once per connect.
     *
     * A function rather than a direct reference so the ranking is testable without a running
     * Go device. Production never reassigns it, which is also why the composition above carries
     * no test of its own: the two halves have no shared seam to stub.
     */
    @Volatile
    internal var ownTunnelCarriesTraffic: () -> Boolean = {
        OverlayRelay.state.value is RelayState.Up &&
            OverlayWgTunnelController.instance?.state?.value is TunnelState.Up
    }

    /** The pinned address for [host], or null when no source covers [host]. */
    fun lookup(host: String?): InetAddress? = lookupChain(host).firstOrNull()

    /**
     * Every pinned address for [host], in **rank order** — the highest-ranked source first.
     *
     * ⚠️ **A chain, not a winner, because the two direct sources are alternatives rather than
     * overrides.** The LAN and WAN arms name the *same server* at two addresses, each reachable
     * from a different place, and nothing in the client knows which one it is standing on.
     * Picking one would be committing to a guess; returning both lets the dialler try the LAN
     * literal, be refused immediately on a foreign network, and fall through to the WAN one.
     * This is the owner's "tries the lan ip first, then the wan", and it costs nothing at home
     * because a refused connect is `ECONNREFUSED` or `EHOSTUNREACH` rather than a timeout.
     *
     * ⚠️ **The fall-through to public DNS is not here; it is in the caller.** [IPv4FirstDns]
     * appends the delegate resolver's own answers after this list, so the order a socket actually
     * tries is TUNNEL, LAN, WAN, then whatever DNS said — the pin first, the public edge last.
     */
    fun lookupChain(host: String?): List<InetAddress> {
        if (host.isNullOrBlank()) return emptyList()
        val current = pins
        if (current.isEmpty()) return emptyList()
        val answers = mutableListOf<InetAddress>()
        // Walks the sources in rank order, so the highest-ranked pin answers first.
        for (source in LocalServerSource.entries) {
            if (isSuppressed(source)) continue
            val pin = current[source] ?: continue
            // The domain match is what covers the hosts the app only ever learns at runtime.
            if (host in pin.hosts || pin.suffixes.any { suffix -> host.endsWith(".$suffix") }) {
                answers += pin.address
            }
        }
        return answers
    }

    /**
     * Whether [source]'s arm must stand down and let a higher-ranked one answer.
     *
     * ⚠️ **Both direct arms are suppressed, by the same two conditions, and the symmetry is the
     * point.** The tunnel-carrying check (see [ownTunnelCarriesTraffic]) and the policy's
     * per-plane toggle both say the same thing about a *direct* route — do not take one — and
     * neither of them cares whether that route crosses the LAN or the WAN. Suppressing only the
     * LAN arm would leave a `directWanPlayback=false` policy silently routing every request
     * straight out to the public address, which is precisely the bypass the toggle exists to
     * close.
     *
     * The TUNNEL arm is never suppressed: it names the overlay address and is not a direct
     * route at all. Read live, per lookup, like everything else here — so both transitions
     * follow in either direction with no bookkeeping.
     *
     * ⚠️ **`directWanPlayback` defaults to `false`, so by default the WAN pin never answers.**
     * That is the toggle working as designed — it is a permission about a *direct WAN route*, and a
     * WAN pin is exactly that. It does mean the off-LAN, tunnel-down client falls to system DNS
     * (and, on this deployment, to the service name's own `A` record) rather than to the `wan=`
     * literal the discovery record published, until an operator sets `directWanPlayback: true` in
     * bsm. The server's own default was written as "a WAN caller gets `404` at today's edge, so
     * deny it"; the v2 origin is served to the WAN with no source-IP gate, so that premise no longer
     * holds for this name and the toggle is worth revisiting. Deliberately NOT worked around here:
     * the client may not quietly promote a direct route the server's policy forbids.
     */
    private fun isSuppressed(source: LocalServerSource): Boolean = when (source) {
        LocalServerSource.TUNNEL -> false
        LocalServerSource.LAN -> ownTunnelCarriesTraffic() || !mayDialDirectly(DirectPlane.LAN)
        LocalServerSource.WAN -> ownTunnelCarriesTraffic() || !mayDialDirectly(DirectPlane.WAN)
    }

    /**
     * True when [url]'s host is currently pinned.
     *
     * This is the gate that decides whether a connection may use a pin at all. A pin is
     * only ever safe to follow under real certificate validation, so callers that cannot
     * validate must not consult it — see the two clients in `PlayerPlaybackNetworking`.
     * It is the gate for *every* source, which keeps that protection in one place rather
     * than one per tier.
     */
    fun isPinnedHost(url: String): Boolean = lookup(hostOf(url)) != null

    /** Pins [hosts], and every other host on their domain, to [address] for [source]. */
    fun pin(source: LocalServerSource, hosts: Collection<String>, address: InetAddress) {
        if (hosts.isEmpty()) return
        val hostSet = hosts.toSet()
        val suffixSet = serverDomainSuffixes(hostSet)
        pins = pins.toMutableMap().apply { this[source] = Pin(hostSet, suffixSet, address) }
        // The host set is the single most useful line when discovery "works" but the app
        // still fails: a pin landing on a host nothing requests looks identical, from the
        // server side, to discovery never having run. Observed 2026-10-05.
        Log.d(TAG, "[$source] pinned ${hostSet.sorted()} + *.$suffixSet -> ${address.hostAddress}")
    }

    /** Drops [source]'s pin, leaving every other source's in place. */
    fun clear(source: LocalServerSource) {
        if (source !in pins) return
        pins = pins.toMutableMap().apply { remove(source) }
        Log.d(TAG, "[$source] pin cleared")
    }

    /** Drops every pin. Tests only — production code clears one source at a time. */
    fun clearAll() {
        pins = emptyMap()
    }
}
