package com.nuvio.app.core.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Where the address a public FQDN is currently resolving to came from.
 *
 * ⚠️ **The declaration order is the priority order, and two consumers depend on it.**
 * [OverlayPinRegistry] walks the sources in this order at *lookup* time, and
 * [effectiveStatus] walks them in this order at *display* time. Declaring a new source
 * therefore places it: put it where it belongs in the ranking, not at the end.
 *
 * ⚠️ **`TUNNEL` outranks `LAN`, and that order was reversed on 2026-10-07 — do not
 * "restore" it.** The tunnel was once the fallback, on the reasoning that a LAN pin can only
 * exist on the server's own network and is therefore the most local path available. That is
 * sound about *distance* and wrong about *intent*: the app already has a tunnel up on the
 * home network too, so preferring the LAN pin means the shipped transport goes unexercised
 * exactly where it is cheapest to exercise, and every tunnel defect waits until the owner
 * leaves the house to show itself. The owner's call was that one path everywhere is worth
 * more than a local hop. Both tiers are pin-*first*, so ranking them wrongly costs one
 * failed connect, never a broken app.
 *
 * ⚠️ **The LAN tier still runs at home, and that is not vestigial.** Its remaining job is to
 * supply the tunnel's **endpoint** — [OverlayLocalDiscovery.pinCandidate] still publishes
 * `pinnedAddress` and the verified advert, which is what `OverlaySession` dials. What changes
 * is only that its *pin* stops being followed while our own tunnel carries traffic; see
 * [OverlayPinRegistry.ownTunnelCarriesTraffic], which is where that is enforced and the only
 * reason a live LAN pin existing alongside a live tunnel is not a bug.
 */
enum class LocalServerSource {
    /** The server's overlay address, reached through the WireGuard tunnel. */
    TUNNEL,

    /** The server's own address, found by mDNS on the network the phone is on. */
    LAN,
}

/**
 * Whether a boomio server has been found, and how.
 *
 * Two tiers report into this, and they answer different questions. Tier 1 is the LAN:
 * on a network where system DNS does not point at the server — it moved, the router
 * changed — the app finds it by mDNS and pins the address, while every URL keeps naming
 * the same public FQDN. It is deliberately tunnel-free, because Android allows a single
 * active `VpnService` and that slot belongs to whatever VPN the user chose. Tier 2 is
 * the overlay: the phone is on a foreign network and reaches the server through the
 * tunnel by exactly the same public names.
 *
 * [Unavailable] is a first-class state rather than an absence of [Found]: "discovery
 * never ran" and "discovery ran and found nothing" are different failures, and a UI
 * that renders both as blank cannot tell the user which one to fix.
 */
sealed interface LocalServerStatus {
    /** Nothing to report from this source yet. The initial state, and the state after a clear. */
    data object Idle : LocalServerStatus

    /** A search is in flight. */
    data object Searching : LocalServerStatus

    /**
     * A server was found **and is reachable over the overlay**, at [address].
     *
     * ⚠️ **"Found" does not always mean "pinned", and the difference is not cosmetic.** The
     * LAN tier and the platform-VPN half of the tunnel tier reach the server by repointing
     * DNS, so for those two [address] is literally what every boomio FQDN now resolves to.
     * The app's **own userspace tunnel** reaches the server through the relay instead and
     * installs no kernel route, so it reports `Found` while **pinning nothing** — a pin there
     * would name an address the kernel cannot reach. Read this as "the app is off the public
     * edge, via [address] somewhere in the path", not as a statement about DNS.
     * See `OverlayTunnel`'s two-paths doc.
     *
     * [serviceName], [hostName] and [version] come from an mDNS advert and are null on
     * both tunnel paths, which have no advert to read them from.
     */
    data class Found(
        val address: String,
        val source: LocalServerSource,
        val serviceName: String? = null,
        val hostName: String? = null,
        val version: String? = null,
    ) : LocalServerStatus

    /**
     * This source cannot be used. [reason] is shown to the user, so it must read as an
     * explanation rather than a code — e.g. "not on Wi-Fi".
     */
    data class Unavailable(val reason: String) : LocalServerStatus
}

/**
 * The single source of truth for [LocalServerStatus], for all sources at once.
 *
 * A concrete `object` in commonMain rather than `expect`/`actual`: `AppFeaturePolicy`
 * carries five actuals (androidFull, androidPlaystore, iosFull, iosAppStore, desktop),
 * so an `expect object` for an Android-only feature would demand a stub in every one
 * for no benefit. This is the same shape [com.nuvio.app.core.network.ServerConfigurationRepository]
 * already uses — commonMain owns the flow and reads it; androidMain writes it.
 *
 * ⚠️ **Held per source, not as one value.** The two tiers run independently and on
 * different triggers, so a single slot would have them overwrite each other: the tunnel
 * reporting `Idle` on a network change would erase a perfectly good LAN pin's status,
 * and the UI would flap between them. Each source owns its slot; [status] is derived.
 */
object LocalServerState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val perSource = MutableStateFlow<Map<LocalServerSource, LocalServerStatus>>(emptyMap())

    /** The most specific thing any source currently has to say. */
    val status: StateFlow<LocalServerStatus> = perSource
        .map { it.effectiveStatus() }
        .stateIn(scope, SharingStarted.Eagerly, LocalServerStatus.Idle)

    /** Called by platform code. Not for UI. */
    fun update(source: LocalServerSource, value: LocalServerStatus) {
        // Atomic on the map, so two sources reporting at once cannot lose an entry —
        // which a read-modify-write on a plain `var` map would.
        perSource.update { it + (source to value) }
    }
}

/**
 * What the user should be told, given everything every source has reported.
 *
 * Pure and internal so it can be tested without a dispatcher: the flow that calls it is
 * deliberately asynchronous, and a test of the *ranking* should not have to wait on it.
 *
 * A [LocalServerStatus.Found] outranks everything, because "the app is going somewhere
 * other than the public edge" is the fact worth surfacing — and it is what makes the
 * tunnel's status visible to a user whose LAN browse is reporting "not on Wi-Fi", which
 * is true and irrelevant at the same time. Failing that, the first source with anything
 * to say wins, so a real diagnostic is not hidden behind an `Idle`.
 */
internal fun Map<LocalServerSource, LocalServerStatus>.effectiveStatus(): LocalServerStatus {
    val ordered = LocalServerSource.entries.mapNotNull { this[it] }
    return ordered.firstOrNull { it is LocalServerStatus.Found }
        ?: ordered.firstOrNull { it !is LocalServerStatus.Idle }
        ?: LocalServerStatus.Idle
}
