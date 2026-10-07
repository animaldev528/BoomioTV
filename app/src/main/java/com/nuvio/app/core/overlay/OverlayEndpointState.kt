package com.nuvio.app.core.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which rung of the discovery ladder produced an endpoint.
 *
 * ⚠️ **The declaration order is the rung order**, and it is the order the ladder tries them
 * in. Unlike [LocalServerSource], nothing here ranks concurrent answers — the ladder stops at
 * the first rung that works, so at most one of these is ever in flight — but the order is
 * still the specification, and [OverlayEndpointStatus.Found] reports which rung won so a
 * support log can say *how* the app found the server rather than only that it did.
 */
enum class OverlayEndpointSource {
    /** Rung 1 — the mDNS advert `_boomio-overlay._udp`, on the server's own network. */
    MDNS,

    /** Rung 2 — the `boomio-local` DNS record, when mDNS is blocked or unavailable. */
    LOCAL_DNS,

    /**
     * Rung 3 — a person typed it: the in-session prompt, or a value already persisted in
     * settings. The two are the same rung because they are the same fact — a human supplied
     * this endpoint — and splitting them would only add a state with no distinct behaviour.
     */
    MANUAL,
}

/**
 * A WireGuard endpoint the userspace tunnel can be brought up against.
 *
 * This is the **whole tuple**, not just an address: [host] and [port] say where the UDP goes,
 * and [serverPublicKeyBase64] is what makes a handshake possible at all. An `A` record alone
 * solves half the problem — see architecture §4.4.
 *
 * [serverPublicKeyBase64] is nullable because rung 3 has to be able to represent "the user
 * typed an address and did not know the key". A null key is not usable for a handshake, and
 * [OverlayEndpoint.isUsable] says so; the ladder treats such an endpoint as a *candidate*
 * that must be completed, never as a working answer.
 *
 * ⚠️ **[host] is a literal address, never a name.** The endpoint string is handed to
 * `OverlayWgTunnel.up` and from there to `IpcSet`'s `endpoint=` **verbatim**, so a name here
 * would be resolved by wireguard-go's own resolver inside a gomobile AAR — where Go's resolver
 * looks for an `/etc/resolv.conf` that Android does not have. `OverlayEndpointDiscovery`
 * resolves before it publishes, for exactly that reason, and it means the host the tunnel
 * dials is the same one whose reachability was just tested.
 */
data class OverlayEndpoint(
    val host: String,
    val port: Int,
    val serverPublicKeyBase64: String?,
    val source: OverlayEndpointSource,
) {
    /** `host:port`, the form `BoomioConfig.overlayEndpoint` and the WG binding both take. */
    val authority: String get() = "$host:$port"

    /**
     * True when this endpoint could actually bring a tunnel up.
     *
     * A reachable host with no server public key is a dead end that looks like progress, which
     * is exactly the shape of bug this property exists to make impossible to write by accident.
     */
    val isUsable: Boolean
        get() = host.isNotBlank() && port in 1..65535 && !serverPublicKeyBase64.isNullOrBlank()
}

/**
 * What the discovery ladder has to say.
 *
 * [NeedsManual] is a first-class state rather than an [Unavailable] with a particular message,
 * because it is the only one with an **action** attached: the ladder has exhausted its
 * automatic rungs and is waiting for a person. Architecture §10.7 makes that a requirement, not
 * a nicety — with no LAN fallback, a failure to establish the tunnel fails visibly, and this is
 * the state that says what the user can do about it.
 */
sealed interface OverlayEndpointStatus {
    /** Nothing has run yet, or the seam is switched off. Silent in the UI. */
    data object Idle : OverlayEndpointStatus

    /** A rung is being tried. */
    data object Searching : OverlayEndpointStatus

    /** An endpoint was found **and passed the reachability gate**. */
    data class Found(val endpoint: OverlayEndpoint) : OverlayEndpointStatus

    /**
     * Every automatic rung missed. [reason] is shown to the user, so it must read as an
     * explanation and should name the rungs that were tried.
     */
    data class NeedsManual(val reason: String) : OverlayEndpointStatus

    /** The seam cannot run here at all — e.g. discovery was never initialised. */
    data class Unavailable(val reason: String) : OverlayEndpointStatus
}

/**
 * The single source of truth for [OverlayEndpointStatus].
 *
 * Shaped like [LocalServerState] on purpose, and for the same reason: the writer is Android
 * platform code and the reader is common UI, so an `expect`/`actual` pair would demand a stub
 * in every one of `AppFeaturePolicy`'s five actuals to serve one platform.
 *
 * ⚠️ **One value, not one per source** — the opposite of [LocalServerState], and the difference
 * is the point. Those two sources run *concurrently* and both can hold a pin, so they need
 * separate slots. These are *rungs of one ladder*, tried in sequence, stopping at the first
 * success: a later rung never has an opinion while an earlier one is holding, so a second slot
 * could only ever be empty.
 *
 * ### This object carries a message *back* as well, and that is deliberate
 *
 * [status] goes platform → UI. [submitManual] goes UI → platform. Both are the same seam seen
 * from its two ends, and they belong on one object because they are one conversation: the UI
 * renders [OverlayEndpointStatus.NeedsManual], whose entire content is *an action*, and the
 * control that performs that action has to reach rung 3 of the same ladder. A second object
 * holding only the uplink would be a second name for the same boundary.
 *
 * ⚠️ **The uplink is a registered handler, not an `expect`/`actual` pair**, for the reason the
 * class docblock above already gives for the downlink: the ladder is Android code
 * ([OverlayEndpointDiscovery]) and an `expect` would demand a stub in each of
 * `AppFeaturePolicy`'s five actuals. So commonMain declares the shape and androidMain supplies
 * the body — exactly how [LocalServerState.update] works, one direction over.
 */
object OverlayEndpointState {
    private val mutable = MutableStateFlow<OverlayEndpointStatus>(OverlayEndpointStatus.Idle)

    val status: StateFlow<OverlayEndpointStatus> = mutable.asStateFlow()

    /**
     * What a typed address does, or null before platform code has registered one.
     *
     * Null is a real state and not an error: the UI is composed on platforms and in tests where
     * the ladder does not exist, and [submitManual] handles that by doing nothing rather than by
     * throwing at some later frame.
     *
     * ⚠️ **Written once, during initialisation, before any UI can compose** — `initialize` runs
     * in `MainActivity.onCreate`, ahead of the first composition. It is a plain `var` rather than
     * a flow because a *later* registration has no meaning: there is exactly one ladder per
     * process, and it exists for the whole of it.
     */
    var manualSubmit: (suspend (rawAuthority: String) -> Unit)? = null

    /** Called by platform code. Not for UI. */
    fun update(value: OverlayEndpointStatus) {
        mutable.value = value
    }

    /**
     * Offers a user-typed endpoint to the ladder. Called by UI.
     *
     * The parameter is the **address only**. The server's public key is the one field a person
     * cannot reasonably produce — it is a base64 X25519 key, published by the mDNS advert and the
     * DuckDNS TXT record — so it is not asked for here. Rung 3 already reads a key that this
     * process holds ([com.nuvio.app.features.boomio.BoomioConfig.overlayServerPubKey], written by
     * enrollment on a device that has one), and [OverlayEndpointStatus.NeedsManual]'s reason
     * names the key explicitly when even that is missing. Asking for a key in a text field would
     * be a field nobody could fill in correctly.
     *
     * Nothing is returned because the answer arrives the way every other answer does: as
     * [status]. The ladder republishes — `Found` if the address worked, `NeedsManual` with a
     * fresh reason if it did not — and the caller is already collecting that.
     */
    suspend fun submitManual(rawAuthority: String) {
        manualSubmit?.invoke(rawAuthority)
    }

    /** Tests only — production code always moves to another state. */
    fun reset() {
        mutable.value = OverlayEndpointStatus.Idle
    }
}
