package com.nuvio.app.core.overlay

import android.content.Context
import android.util.Log
import com.nuvio.app.features.boomio.BoomioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "OverlaySession"

/** How long to let a drop settle before rebuilding; see the collector in [OverlaySession.initialize]. */
private const val REBUILD_DELAY_MS = 1_500L

/**
 * Turns a discovered endpoint into a running tunnel.
 *
 * **The gap this closes.** [OverlayEndpointDiscovery] finds *where* the server is, and
 * [OverlayWgTunnel] knows *how* to bring a device up, and until this existed nothing connected
 * the two: the ladder published an endpoint that no one consumed, and the tunnel had no
 * caller outside the debug probe. U2's AAR was, in the app, still a library nobody loaded.
 *
 * **It owns the decision, not the mechanism.** Bring-up and tear-down live in
 * [OverlayWgTunnel]; the relay's routing lives in [OverlayRelay]. All this does is watch the
 * ladder and say "the endpoint changed, so the device should change with it" — which is why it
 * is small enough to be worth reading in one sitting, and why it can be tested against a fake
 * binding with no device involved.
 *
 * ⚠️ **A failed re-resolve does not tear the tunnel down.** The obvious shape — `Found` means
 * up, anything else means down — reintroduces the bug the ladder itself was restructured to
 * avoid. The ladder re-resolves on a foreground cadence, so a single `NeedsManual` (one
 * timeboxed DNS miss, one browse that found nothing this time) would kill a tunnel that is up
 * and carrying traffic, and the app would drop to the public edge for no reason. A status that
 * is not `Found` is evidence about *discovery*, not about a tunnel that is already running; the
 * tunnel's own liveness is [OverlayTunnel]'s probe. So: `Found` (re)configures, and nothing
 * else touches it.
 */
internal object OverlaySession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var driver: OverlaySessionDriver? = null

    /**
     * The ladder collector, held so [stop] can actually stop it.
     *
     * ⚠️ Without this, `stop` would null the driver and leave its collector running against a
     * driver nothing references any more. That was harmless while the gate could only ever open
     * once; now that [onServerAddressLearned] can reopen it, a stop-then-start would leave two
     * collectors both driving the tunnel.
     */
    @Volatile
    private var driverJob: Job? = null

    @Volatile
    private var lifecycleStarted = false

    private val _connectionState =
        MutableStateFlow<OverlayTunnelUiState>(OverlayTunnelUiState.Down)

    /**
     * The tunnel as the Settings screen shows it: the device's state projected through the
     * user's "keep it down" choice. One collector, started in [initialize]; see
     * [OverlayTunnelUiState.of] for why this is a projection and not a second state machine.
     */
    val connectionState: StateFlow<OverlayTunnelUiState> = _connectionState.asStateFlow()

    /**
     * Starts the session for this process, once.
     *
     * ⚠️ **The keypair is created here unconditionally, and that is load-bearing.** Creating a
     * keypair is not starting a tunnel: it is the device acquiring an *identity*, which commits
     * the app to nothing and is what [OverlayEnrollment] enrols *as*. Under the previous order
     * the address gate sat above this line, so on an install built without
     * `BOOMIO_OVERLAY_ADDR` the controller was never created at all, `devicePublicKey()`
     * answered null, and the first enrollment could not happen — the device had no key to ask
     * with, which is the one case the whole provisioning ingress exists for. The gate below is
     * about the *ladder*, not the keypair, and it stays exactly where it was.
     */
    fun initialize(context: Context) {
        if (lifecycleStarted) return
        lifecycleStarted = true

        OverlayWgTunnelController.initialize(context)

        // The one place the two facts meet. `initialize` has just created the device, so a
        // null here would mean the controller refused to start at all — and then there is no
        // tunnel state to project, only `Down`.
        OverlayWgTunnelController.instance?.let { device ->
            scope.launch {
                combine(device.state, OverlayWgTunnelController.manuallyDisconnected) { state, manual ->
                    OverlayTunnelUiState.of(state, manual)
                }.collect { _connectionState.value = it }
            }

            // **A tunnel that goes down on its own is brought back here.** Neither existing
            // trigger covers it: the ladder is `distinctUntilChanged`, so the `Found` that
            // would rebuild the device is not re-delivered after a bounce, and
            // [OverlayTunnel]'s triggers only re-*probe* — they never call `up()`. Without
            // this, a tunnel that drops (link loss, a lapsed handshake, a VPN toggled under
            // the app, the relay's own watchdog) stays down until the user finds Reconnect,
            // and the fail-open relay then serves media straight from the LAN — which the
            // server refuses under `requireTunnelForMedia`, as a 403 the user sees as
            // "playback is permanently broken".
            //
            // Only a transition OUT of `Up` is news: the StateFlow's first emission is the
            // current state, so `wasUp` is seeded from it and a cold start is not a "drop".
            scope.launch {
                var wasUp = device.state.value is TunnelState.Up
                device.state.collect { state ->
                    val up = state is TunnelState.Up
                    val dropped = wasUp && !up
                    wasUp = up
                    if (!dropped) return@collect
                    // The user's own Disconnect is a drop too, and must stay down.
                    if (OverlayWgTunnelController.manuallyDisconnected.value) return@collect
                    // A beat, not immediately: `apply` takes the device down before it brings
                    // it back, so rebuilding into a teardown already in flight would fight it.
                    delay(REBUILD_DELAY_MS)
                    if (OverlayWgTunnelController.manuallyDisconnected.value) return@collect
                    val held = OverlayEndpointState.status.value as? OverlayEndpointStatus.Found
                    Log.d(TAG, "Overlay tunnel dropped; rebuilding against ${held?.endpoint?.authority}")
                    if (held != null && driver?.apply(held) == true) OverlayTunnel.refreshAsync()
                }
            }
        }

        startDriverIfConfigured()
    }

    /**
     * Starts the ladder-driven bring-up, once there is an address to aim it at.
     *
     * ⚠️ **Gated on [BoomioConfig.overlayServerAddress], the same single switch the relay
     * uses**, so one value turns the whole overlay subsystem on or off rather than each piece
     * inventing its own condition. The gate is deliberately *not* "did the ladder find
     * something": rung 2 resolves a **public** name, so on an install that runs no overlay
     * the ladder can still find a real endpoint and this would build a tunnel nobody enrolled
     * — a device that comes up, never handshakes, and reports nothing useful.
     *
     * ⚠️ **Re-checked on every call rather than latched with [initialize]**, because the
     * address is *learned* now: on a fresh install it does not exist yet when this object
     * initializes, so a latch here would leave the subsystem off for the entire process on
     * exactly the build it exists to serve. [onServerAddressLearned] is what re-opens it.
     */
    @Synchronized
    private fun startDriverIfConfigured() {
        if (driver != null) return
        if (BoomioConfig.overlayServerAddress.isBlank()) return

        val created = OverlaySessionDriver(
            tunnel = { OverlayWgTunnelController.instance },
            localCidr = { BoomioConfig.overlayLocalCidr },
            // Read at apply time, never captured: the flag is the user's, it outlives this
            // driver, and Disconnect can be pressed long after the collector was launched.
            manuallyDisconnected = { OverlayWgTunnelController.manuallyDisconnected.value },
        )
        driver = created

        driverJob = scope.launch {
            OverlayEndpointState.status
                .map { it as? OverlayEndpointStatus.Found }
                .distinctUntilChanged()
                .collect { found ->
                    if (!created.apply(found)) return@collect
                    // The device's state just changed, and [OverlayTunnel]'s own triggers are
                    // foreground, network and server-config — **none of which fire when the
                    // ladder converges**, which is exactly when this happens. Without this the
                    // status tier would sit on a stale answer for up to its five-minute TTL,
                    // showing a connected user "the tunnel is down" or nothing at all.
                    //
                    // Told, rather than watched: [OverlayTunnel] could collect the tunnel's
                    // state itself, but that needs a non-null controller at *its* init time
                    // and so would silently depend on the order MainActivity happens to call
                    // these two in. Announcing the change here has no such ordering.
                    OverlayTunnel.refreshAsync()
                }
        }
    }

    /**
     * Announces that [BoomioConfig.overlayServerAddress] has just been written, possibly for
     * the first time on this install.
     *
     * Told, rather than watched, for the same reason [OverlayTunnel] is told when the ladder
     * converges: the address is a plain `var` with no flow behind it, so watching would mean
     * polling, and the writer is one call away. [OverlayEnrollment] is its only writer, and it
     * calls this from the one place it writes.
     */
    internal fun onServerAddressLearned() {
        startDriverIfConfigured()
        // ⚠️ [startDriverIfConfigured] alone is a no-op here in every case this is called for:
        // enrollment is the only writer, and by the time it writes, the driver already exists
        // (the address gate sits on a baked non-blank default). So the thing that was learned
        // would reach a config `var` that nothing re-reads. Re-applying the held endpoint is
        // what rebuilds the device onto the assigned /32.
        val held = OverlayEndpointState.status.value as? OverlayEndpointStatus.Found
        if (held != null) driver?.apply(held)
    }

    /**
     * The user's Disconnect control: tear the tunnel down and keep it down.
     *
     * ⚠️ **The flag is set FIRST, before anything is launched.** Every `apply` that starts
     * after this line returns immediately, including one the endpoint ladder is about to
     * deliver; setting it inside the coroutine would leave a window in which a `Found` could
     * bring the device straight back up. The teardown itself runs on [scope] because a
     * bring-up in flight holds the driver's monitor for its handshake wait and the Settings
     * screen must not block behind that.
     *
     * Discovery and enrollment keep running: the address can still be learned while the
     * tunnel is down, so Reconnect is instant and a newly assigned address is not lost.
     */
    @Synchronized
    fun disconnect() {
        OverlayWgTunnelController.setManuallyDisconnected(true)
        scope.launch {
            val active = driver
            if (active != null) active.disconnect() else OverlayWgTunnelController.instance?.down()
            OverlayTunnel.refreshAsync()
        }
    }

    /**
     * The user's Reconnect control.
     *
     * ⚠️ **The held endpoint is re-applied explicitly.** The ladder collector is
     * `distinctUntilChanged`, and the endpoint almost certainly has not changed since before
     * the disconnect — so clearing the flag alone would leave the device down until some
     * *new* endpoint appeared. Re-applying the held `Found` is what rebuilds it.
     */
    @Synchronized
    fun reconnect() {
        OverlayWgTunnelController.setManuallyDisconnected(false)
        startDriverIfConfigured()
        val held = OverlayEndpointState.status.value as? OverlayEndpointStatus.Found
        scope.launch {
            if (held != null) driver?.apply(held)
            // A re-resolve may have been due while the tunnel was down; walk the same seams
            // the Settings screen's "Check again" row walks. Each is fire-and-forget and
            // takes its own mutex.
            OverlayLocalDiscovery.refreshAsync()
            OverlayEndpointDiscovery.refreshAsync()
            OverlayTunnel.refreshAsync()
        }
    }

    /** Tears the device down and stops reacting to the ladder. Tests and the debug probe. */
    @Synchronized
    fun stop() {
        driverJob?.cancel()
        driverJob = null
        driver = null
        OverlayWgTunnelController.instance?.down()
    }

    /**
     * The tunnel as an [OverlayDialer], or null when it is not up.
     *
     * ⚠️ **Read live, per dial, not captured at start-up.** [OverlayRelay] binds its diallers
     * once for the process — `start` is idempotent and returns the running handle — so a
     * dialler handed over at start-up would freeze whatever the tunnel's state happened to be
     * at that instant, which is `Down`, because the relay starts before the ladder has
     * finished its first walk. Reading the state at dial time is what lets the relay come up
     * *before* the tunnel and still carry traffic once it does.
     */
    fun dialerOrNull(): OverlayDialer? =
        OverlayWgTunnelController.instance
            ?.takeIf { it.state.value is TunnelState.Up }
            ?.let { OverlayWgDialer(it.binding) }
}

/**
 * The bring-up policy, separated from the singleton so it can be tested.
 *
 * Split the same way [OverlayWgTunnel] is split from [OverlayWgTunnelController], and for the
 * same reason: the real device is a JNI library that cannot load on the host JVM, so the part
 * that decides *when* to call `up()` has to be reachable without one.
 */
internal class OverlaySessionDriver(
    private val tunnel: () -> OverlayWgTunnel?,
    private val localCidr: () -> String,
    /**
     * True while the user has asked for the tunnel to stay down.
     *
     * A supplier rather than a value because the preference outlives any one `apply`, and
     * a captured boolean would let a long-lived collector act on a stale answer.
     */
    private val manuallyDisconnected: () -> Boolean = { false },
) {

    /**
     * The endpoint the device is currently configured for, or null when it is not up.
     *
     * Tracked separately from [OverlayWgTunnel.state] because it answers a different question.
     * That one says whether a device exists; this says **which server it is pointed at**, which
     * is what makes re-applying the same `Found` a no-op and a *different* `Found` a
     * reconfigure.
     */
    @Volatile
    private var configuredFor: String? = null

    /**
     * The local address the device was last brought up with, or null when it is not up.
     *
     * ⚠️ **Part of the reconfigure decision, not a memo of [configuredFor].** The address is
     * *learned* — enrollment assigns it — and it is written into [BoomioConfig] at a moment when
     * the ladder may already have brought the device up on the baked default. The server
     * registers this peer's key against the **assigned** /32, so a device left up on the old one
     * completes its handshake and then has every inner packet dropped by cryptokey routing:
     * connected, and carrying nothing. Deciding on the authority alone calls that state
     * "already configured" and never rebuilds it.
     */
    @Volatile
    private var configuredLocalCidr: String? = null

    /** True when this driver has a device up. Read by tests; production reads the tunnel. */
    val isUp: Boolean get() = configuredFor != null

    /**
     * Reacts to the ladder. Returns true when the device's configuration changed.
     *
     * [found] is null for every status that is not [OverlayEndpointStatus.Found] — the caller
     * maps before it gets here, so this function cannot accidentally act on `Searching` as
     * though it were news.
     */
    @Synchronized
    fun apply(found: OverlayEndpointStatus.Found?): Boolean {
        // `Found` is discovery news, not consent to bring the device back up. Re-checked
        // inside the monitor so a disconnect that raced the ladder cannot be undone by it.
        if (manuallyDisconnected()) return false

        val endpoint = found?.endpoint?.takeIf { it.isUsable }

        if (endpoint == null) {
            // ⚠️ Deliberately does nothing, including when a device is up — see the object's
            // doc. The one case that *is* a teardown is the ladder having nothing to offer at
            // all on a cold start, and there `configuredFor` is already null.
            return false
        }

        val authority = endpoint.authority
        val cidr = localCidr()
        val device = tunnel() ?: return false
        if (authority == configuredFor && cidr == configuredLocalCidr) {
            // `configuredFor` records what this driver ASKED for, not what is running. A
            // device that went down on its own — link loss, a lapsed handshake, the app
            // resumed — leaves that memory untouched, and then this same endpoint would be
            // suppressed for the life of the process: the ladder is `distinctUntilChanged`,
            // so nothing re-delivers it. A stale memory is a rebuild, not a no-op.
            if (device.state.value is TunnelState.Up) return false
            Log.d(TAG, "Overlay tunnel is no longer up for $authority; rebuilding")
        }
        if (authority == configuredFor) {
            // Same server, different local address: this device has been assigned its /32 since
            // the device came up, and it is still running on the old one. Rebuilding is the
            // entire point — see [configuredLocalCidr].
            Log.d(TAG, "Overlay local address changed to $cidr; rebuilding the device")
        }

        val serverKey = endpoint.serverPublicKeyBase64 ?: return false

        // An endpoint that moved means the old device is pointed at the wrong server. `up()`
        // on a live device is not documented to reconfigure in place, so it is taken down
        // first — the same order the probe uses.
        if (device.state.value is TunnelState.Up) device.down()

        return when (val result = device.up(
            endpoint = authority,
            serverPublicKeyBase64 = serverKey,
            localCidr = localCidr(),
        )) {
            is TunnelState.Up -> {
                configuredFor = authority
                configuredLocalCidr = cidr
                Log.d(TAG, "Overlay tunnel up against $authority as $cidr")
                true
            }
            is TunnelState.Failed -> {
                // Left null on purpose: a failure must not be remembered as "configured for
                // this endpoint", or the next identical `Found` would be suppressed and the
                // retry the ladder's cadence offers would never happen.
                configuredFor = null
                configuredLocalCidr = null
                Log.w(TAG, "Overlay tunnel refused $authority: ${result.reason}")
                true
            }
            TunnelState.Down -> {
                configuredFor = null
                configuredLocalCidr = null
                true
            }
        }
    }

    /**
     * Tears the device down **and forgets what it was configured for**.
     *
     * ⚠️ **Clearing [configuredFor] is the whole of Reconnect working at all.** [apply] treats
     * the same endpoint as "already configured" — correct for an unchanged `Found`, fatal
     * here — so a disconnect that only called `down()` would make every later `Found` a no-op
     * and Reconnect a dead button until the endpoint happened to change. Done under the same
     * monitor as [apply], so a bring-up in flight completes first and is then torn down.
     */
    @Synchronized
    fun disconnect() {
        configuredFor = null
        configuredLocalCidr = null
        // `down()` is safe when already down and normalises the state either way.
        tunnel()?.down()
    }
}
