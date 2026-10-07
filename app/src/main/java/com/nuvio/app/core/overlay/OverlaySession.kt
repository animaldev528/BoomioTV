package com.nuvio.app.core.overlay

import android.content.Context
import android.util.Log
import com.nuvio.app.features.boomio.BoomioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "OverlaySession"

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
        val endpoint = found?.endpoint?.takeIf { it.isUsable }

        if (endpoint == null) {
            // ⚠️ Deliberately does nothing, including when a device is up — see the object's
            // doc. The one case that *is* a teardown is the ladder having nothing to offer at
            // all on a cold start, and there `configuredFor` is already null.
            return false
        }

        val authority = endpoint.authority
        if (authority == configuredFor) return false

        val serverKey = endpoint.serverPublicKeyBase64 ?: return false
        val device = tunnel() ?: return false

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
                Log.d(TAG, "Overlay tunnel up against $authority")
                true
            }
            is TunnelState.Failed -> {
                // Left null on purpose: a failure must not be remembered as "configured for
                // this endpoint", or the next identical `Found` would be suppressed and the
                // retry the ladder's cadence offers would never happen.
                configuredFor = null
                Log.w(TAG, "Overlay tunnel refused $authority: ${result.reason}")
                true
            }
            TunnelState.Down -> {
                configuredFor = null
                true
            }
        }
    }
}
