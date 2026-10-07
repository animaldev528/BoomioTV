package com.nuvio.app.core.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.boomio.BoomioConfig
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "OverlayTunnel"

/**
 * Pins the app's public FQDNs to the server's **overlay** address while the tunnel is up.
 *
 * This is the Tier 2 half of the overlay, and the second source feeding
 * [OverlayPinRegistry]. Tier 1 ([OverlayLocalDiscovery]) needs the phone to be on the
 * server's own network; this one is for everywhere else — the phone on cellular, the TV
 * at a friend's house — where the server is reached through WireGuard at `10.77.0.1`.
 *
 * **What it changes, and what it deliberately does not.** The server keeps emitting
 * `https://bss-tor.tracemonkey.org/…` forever (see §6.2 option D). Nothing here rewrites
 * a URL; it changes what that name *resolves to*. That is not a shortcut but a
 * requirement: Caddy holds certificates **per named site block**, so an overlay address
 * used as a URL has no site block to match it. Reaching `10.77.0.1:443` with the name
 * `bss-tor.tracemonkey.org` serves correctly — verified live against the server with
 * `curl --resolve` and `openssl -servername`, both returning a valid Let's Encrypt chain
 * — and reaching it with the bare address does not, at any point in the stack.
 *
 * ⚠️ **There is no `VpnService` here, and that is the point.** This object does not
 * create or own a tunnel; it observes one. Android grants exactly one active
 * `VpnService`, and coexistence with a VPN the user already runs (NordVPN) is a hard
 * requirement, so the app cannot take that slot. When no tunnel exists, this reports
 * [LocalServerStatus.Idle] and the app falls back to the public edge, which still works.
 *
 * ---
 *
 * ## The two overlay paths, and why only one of them pins
 *
 * ⚠️ **This is the thing to understand before changing anything below.** Since U3 there are
 * two ways the overlay can be up, and they are reached by *mechanisms that are not
 * interchangeable* — so this object treats them differently on purpose.
 *
 * **A. A platform tunnel — a real `VpnService`** (the user's WireGuard app, some future
 * enrollment). The kernel has a route to `10.77.0.0/24`, so the app's own resolver can be
 * pointed there: Dns over the pin, then every engine connects by itself. **This path pins.**
 * Identified by a VPN network whose link address lies on the overlay subnet, because
 * "a VPN is up" is not enough — NordVPN also satisfies that and routes nothing we want.
 *
 * **B. The app's own userspace tunnel** ([OverlayWgTunnel]) — what this app ships. netstack
 * runs *in process* and **installs no kernel route at all**, so:
 *   - a plain `java.net.Socket` to `10.77.0.1` reaches nothing, and the probe has to be
 *     dialled *through* the binding instead ([probeThroughTunnel]);
 *   - ⚠️ **pinning DNS to the overlay address is not merely useless here, it is harmful.**
 *     It would rewrite every boomio FQDN to an address the kernel cannot reach, and the
 *     relay — which is the only route — dials by *name*, so it would never even be consulted.
 *     The pin would take a working app and blackhole it.
 * So **this path never pins.** It reports the status and leaves DNS alone; traffic reaches
 * the server through [OverlayRelay], which resolves names inside the tunnel at
 * `10.77.0.1` and is itself already wired to prefer the tunnel.
 *
 * **The gate is the probe, not the interface.** For path A, the identifying signal is the
 * VPN link's address on the overlay subnet and the confirming signal is a TCP connect. For
 * path B there is no interface to identify, so the gate is [TunnelState.Up] — our own device
 * having been configured against an endpoint the ladder *already* vetted.
 *
 * The two are not mutually exclusive in principle and path B wins when both hold, because
 * the relay is the route this app actually uses.
 */
internal object OverlayTunnel {

    /**
     * The edge port, not the WireGuard port.
     *
     * The tunnel listens on udp/51820, but nothing here speaks to it. What is being
     * checked is whether the *server's Caddy* is reachable through the tunnel, which is
     * where every request the app makes actually goes.
     */
    private const val EDGE_PORT = 443
    private const val PROBE_TIMEOUT_MS = 900

    /**
     * A WireGuard handshake completes about a second after the interface comes up, and
     * the network callback fires at the interface, not at the handshake. Without the
     * retry the first, natural probe lands in that gap, reports the tunnel unreachable,
     * and — because the next trigger is the foreground TTL — leaves the app on the
     * public edge for up to [RESULT_TTL_MS] afterwards.
     */
    private const val PROBE_ATTEMPTS = 3
    private const val PROBE_RETRY_DELAY_MS = 1_000L

    /**
     * Fewer attempts than [PROBE_ATTEMPTS], because the retry budget is spent differently.
     *
     * Path A's probe fails in [PROBE_TIMEOUT_MS], so three attempts cost three seconds. Path
     * B's dial is bounded by the *Go* side's 20 s timeout ([OverlayWgBinding.dial]), so the
     * same count could hold this object's probe mutex for a minute. The handshake gap the
     * retry exists to cover is about a second wide, so a second attempt a second later lands
     * past it; the rest of the convergence is [observeForeground]'s cadence.
     */
    private const val OWN_TUNNEL_PROBE_ATTEMPTS = 2

    /** See [OverlayLocalDiscovery.RESULT_TTL_MS]: the same foreground cadence. */
    private const val RESULT_TTL_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeMutex = Mutex()

    private var appContext: Context? = null

    @Volatile
    private var lastProbedAtMs = 0L

    private var lifecycleStarted = false

    /**
     * The address the last successful probe pinned, or null when nothing is pinned.
     *
     * A flow rather than a field for the same reason [OverlayLocalDiscovery] uses one:
     * the host set is not fixed at probe time. The addon catalogue, where most of the
     * app's server hosts live, loads asynchronously and can land before or after the pin,
     * so the pin has to be re-applied when the set widens — in either order.
     */
    private val pinnedAddress = MutableStateFlow<InetAddress?>(null)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Idempotent: onCreate can run again after a configuration-forced restart, and a
        // second set of observers would double every probe.
        if (lifecycleStarted) return
        lifecycleStarted = true
        observeVpnNetworks()
        observeForeground()
        observeServerChanges()
        observeAddonChanges()
    }

    /**
     * Re-probes whenever a VPN network appears, disappears, or changes.
     *
     * A `NetworkRequest` on `TRANSPORT_VPN` rather than the *default* network callback:
     * a split-tunnel VPN that carries only the overlay subnet never becomes the default
     * network, so the default callback would never fire for the case this exists to
     * catch. This one fires on the interface itself.
     *
     * `onLinkPropertiesChanged` is included because that is when the tunnel's address
     * and routes are actually installed — `onAvailable` can precede them, and probing
     * before the route exists is a probe that fails for a reason that is about to stop
     * being true.
     */
    private fun observeVpnNetworks() {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onTunnelEvent()
            override fun onLost(network: Network) = onTunnelEvent()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                onTunnelEvent()
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "Could not observe VPN networks", it) }
    }

    /**
     * Re-decides rather than clearing immediately.
     *
     * The LAN tier clears its pin the moment the network changes, because a LAN address
     * is meaningless on a different network. An overlay address is not: it is the same
     * address on every network, and it stays valid across a Wi-Fi-to-cellular handover —
     * which is exactly the case the tunnel exists for. Clearing on every link-properties
     * event would drop a working pin during ordinary roaming, so the probe decides
     * instead. A tunnel that is genuinely gone fails the probe, and the pin clears then.
     */
    private fun onTunnelEvent() {
        lastProbedAtMs = 0L
        refreshAsync()
    }

    /** Re-probes on foreground, at most once per [RESULT_TTL_MS]. */
    private fun observeForeground() {
        scope.launch {
            AppForegroundMonitor.events()
                // `events()` is a `callbackFlow` whose block calls
                // `ProcessLifecycleOwner.lifecycle.addObserver`, and Android requires
                // that on the main thread — collecting it straight from this object's IO
                // scope throws on the *first* collection, which would kill the app on
                // launch. `flowOn` moves only the upstream; the body below stays on IO.
                .flowOn(Dispatchers.Main.immediate)
                .collect { visibility ->
                    if (visibility != AppVisibility.Foreground) return@collect
                    if (SystemClock.elapsedRealtime() - lastProbedAtMs < RESULT_TTL_MS) return@collect
                    refresh()
                }
        }
    }

    /**
     * Re-derives the host set when the configured server changes.
     *
     * The pin is host-scoped, so switching servers changes what may be pinned at all —
     * and the overlay address belongs to the server that was configured, not to whoever
     * is configured now.
     */
    private fun observeServerChanges() {
        scope.launch {
            ServerConfigurationRepository.active
                .map { it.backendUrl }
                .distinctUntilChanged()
                // The value already in the flow at assembly is not a change.
                .drop(1)
                .collect {
                    lastProbedAtMs = 0L
                    refresh()
                }
        }
    }

    /**
     * Re-applies the pin when the *host set* widens under a pin already in place.
     *
     * The same race [OverlayLocalDiscovery] documents, and it is worth restating because
     * it is invisible from this side: the addon catalogue is where most of the app's
     * server hosts live, it loads seconds after launch, and it can beat the pin. A
     * collector that read `pinnedAddress` *inside* itself would drop that emission
     * permanently — the mapped host set is `distinctUntilChanged`, so a dropped value is
     * never revisited. [combine] makes the order irrelevant; whichever changes last
     * produces the pair.
     *
     * Deliberately does **not** re-probe: the tunnel address has not changed, only the
     * list of hosts that should use it.
     */
    private fun observeAddonChanges() {
        scope.launch {
            combine(
                AddonRepository.uiState.map { localServerHosts() }.distinctUntilChanged(),
                pinnedAddress,
            ) { hosts, address -> hosts to address }
                .collect { (hosts, address) ->
                    if (hosts.isNotEmpty() && address != null) {
                        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, hosts, address)
                    }
                }
        }
    }

    /** Fire-and-forget [refresh] for callers that are not themselves coroutines. */
    fun refreshAsync() {
        scope.launch { refresh() }
    }

    /** Drops the tunnel pin without probing. */
    private fun clear() {
        pinnedAddress.value = null
        OverlayPinRegistry.clear(LocalServerSource.TUNNEL)
    }

    /**
     * Decides what the tunnel can offer right now, and publishes it.
     *
     * Safe to call repeatedly; concurrent calls serialise rather than racing the probe.
     */
    suspend fun refresh() = probeMutex.withLock {
        // Stamped on entry, not on success: a probe that finds nothing is still a probe.
        lastProbedAtMs = SystemClock.elapsedRealtime()

        val context = appContext
        if (context == null) {
            publish(LocalServerStatus.Idle)
            return@withLock
        }

        val server = parseOverlayAddress(BoomioConfig.overlayServerAddress)
        if (server == null) {
            // Not configured: the tier is simply not in use, which is not a failure and
            // must stay silent. This is the blank-inert pattern the rest of BoomioConfig
            // follows, and it is what keeps the feature off for anyone not running an
            // overlay.
            publish(LocalServerStatus.Idle)
            return@withLock
        }

        // ---- Path B: the app's own userspace tunnel (see the object doc) ----------------
        //
        // Checked BEFORE the platform gate, and with no `ConnectivityManager` involved,
        // because a userspace tunnel has no interface to enumerate — path A's gate is not
        // merely unhelpful here, it can never be satisfied, which is what made this tier
        // report `Idle` forever once U3 started bringing the tunnel up for real.
        val ownTunnel = OverlayWgTunnelController.instance
            ?.takeIf { it.state.value is TunnelState.Up }
        if (ownTunnel != null) {
            // ⚠️ Cleared unconditionally, before the probe has an answer. A pin left by a
            // previous path-A session names an address the kernel cannot reach under
            // netstack, and leaving it in place for even one probe's duration is a window
            // where every engine is pointed at a black hole.
            if (probeThroughTunnel(ownTunnel, server)) {
                publishUnpinned(
                    LocalServerStatus.Found(
                        address = server.hostAddress.orEmpty(),
                        source = LocalServerSource.TUNNEL,
                    )
                )
                Log.d(TAG, "Own overlay tunnel up; ${server.hostAddress} answers through it (no DNS pin)")
            } else {
                // A real fault: the device is up and configured, and the server did not
                // answer. Reported rather than swallowed, per §10.7 — the user has no LAN
                // fallback to fall back to, so silence would be the wrong answer.
                logUnansweredTunnel()
                publishUnpinned(
                    LocalServerStatus.Unavailable("The overlay tunnel is up but the server did not answer")
                )
            }
            return@withLock
        }

        // ---- Path A: a platform VPN carrying the overlay subnet ------------------------
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null || !hasOverlayTunnel(manager, server)) {
            // No VPN carrying the overlay subnet. Either the user runs no tunnel at all,
            // or they run a different VPN — NordVPN on the same phone, which satisfies
            // "a VPN is up" and routes nothing this app wants. Both are ordinary states,
            // not errors, so neither is reported as one.
            publish(LocalServerStatus.Idle)
            return@withLock
        }

        val hosts = localServerHosts()
        if (hosts.isEmpty()) {
            publish(LocalServerStatus.Unavailable("No boomio server is configured"))
            return@withLock
        }

        if (probeWithRetry(server)) {
            pinnedAddress.value = server
            OverlayPinRegistry.pin(LocalServerSource.TUNNEL, hosts, server)
            LocalServerState.update(
                LocalServerSource.TUNNEL,
                LocalServerStatus.Found(address = server.hostAddress.orEmpty(), source = LocalServerSource.TUNNEL),
            )
            Log.d(TAG, "Overlay tunnel up; pinned $hosts -> ${server.hostAddress}")
        } else {
            // The tunnel is up and the server did not answer. That is a real fault —
            // the handshake succeeded but nothing is being served — so it is reported
            // rather than swallowed.
            publish(LocalServerStatus.Unavailable("The overlay tunnel is up but the server did not answer"))
        }
    }

    private fun publish(status: LocalServerStatus) {
        // Any status that is not Found means there is nothing to pin. Without this a
        // failed probe would leave the previous pin in place, and the app would keep
        // resolving to an address the probe has just said is dead.
        if (status !is LocalServerStatus.Found) clear()
        LocalServerState.update(LocalServerSource.TUNNEL, status)
    }

    /**
     * Publishes a status for path B, which **never pins** — see the object doc.
     *
     * Deliberately separate from [publish] rather than a flag on it: [publish]'s contract is
     * "a `Found` here means the address in it is pinned", and the whole point of path B is
     * that `Found` and "pinned" come apart. A boolean parameter would let the two be
     * conflated again at the next call site.
     */
    private fun publishUnpinned(status: LocalServerStatus) {
        clear()
        LocalServerState.update(LocalServerSource.TUNNEL, status)
    }

    /**
     * Probes the overlay address **through the userspace tunnel** rather than over a socket.
     *
     * ⚠️ **A plain `Socket` cannot be substituted here, and that is the whole reason this
     * exists.** netstack installs no kernel route, so `Socket().connect(10.77.0.1:443)` gets
     * `ENETUNREACH` on a tunnel that is working perfectly — the probe would report every
     * healthy tunnel as dead, and the fix would look like it belonged in the tunnel.
     *
     * A successful `dial` **is** the reachability signal: gVisor's TCP connect does not
     * return until the handshake completes, so a handle means the server's Caddy answered.
     * Nothing is written and the connection is closed immediately — this asks whether the
     * edge is there, not what it says.
     */
    private suspend fun probeThroughTunnel(tunnel: OverlayWgTunnel, address: InetAddress): Boolean {
        val host = address.hostAddress ?: return false
        repeat(OWN_TUNNEL_PROBE_ATTEMPTS) { attempt ->
            val startedAt = SystemClock.elapsedRealtime()
            val reached = runCatching {
                val handle = tunnel.binding.dial(host, EDGE_PORT)
                // Closing an unknown handle is not an error, so this is safe even if a
                // future binding returns a sentinel instead of throwing.
                tunnel.binding.close(handle)
                true
            }.onFailure {
                Log.w(
                    TAG,
                    "Own-tunnel probe to $host:$EDGE_PORT failed after " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms",
                    it,
                )
            }.getOrDefault(false)

            if (reached) return true
            if (attempt < OWN_TUNNEL_PROBE_ATTEMPTS - 1) delay(PROBE_RETRY_DELAY_MS)
        }
        return false
    }

    /**
     * Says **which identity the app just presented**, when its own tunnel came up and nothing
     * answered through it.
     *
     * ⚠️ **This exists because the failure it describes is otherwise completely silent, and
     * that silence is what made it expensive.** A userspace WireGuard whose public key the
     * server does not hold comes up *perfectly* — `Up()` returns, the device exists, the
     * status tier says `Found` — and then the server drops every handshake initiation
     * without a word, because a responder that does not recognise a peer deliberately says
     * nothing at all. From inside the app that is indistinguishable from a server that is
     * down, and on 2026-10-07 it took a packet capture to tell the two apart: the phone's
     * initiations were on the wire every five seconds and the server answered none of them,
     * while handshaking happily with a different peer at the same moment.
     *
     * The identity is not a secret — it is exactly the value the operator pastes into
     * `wg set` — and the remedy is one line, so the app can simply say both rather than
     * leaving the next person to reach for tcpdump. [OverlayWgTunnel.status] is redacted at
     * its source, and nothing here touches the private half of the keypair.
     */
    private fun logUnansweredTunnel() {
        val device = OverlayWgTunnelController.instance ?: return
        // `storedPublicKeyBase64`, never `publicKeyBase64`: the latter *mints and persists* a
        // keypair when storage is incomplete, so a diagnostic could rotate the very identity
        // it is reporting on. That is precisely the shape of the incident this explains.
        val key = runCatching { device.storedPublicKeyBase64() }.getOrNull()
        val cidr = BoomioConfig.overlayLocalCidr
        val identity = if (key != null) "$key holding $cidr" else "(no keypair stored)"
        val remedy = if (key != null) {
            " Register it with: overlay-server-setup.sh add-peer-pubkey <name> $key"
        } else {
            ""
        }
        Log.w(
            TAG,
            "Own tunnel is up but nothing answered through it. If the server's handshake " +
                "time for this peer is zero, the server does not hold this key and is " +
                "dropping the initiations silently. Identity presented: $identity.$remedy",
        )
        Log.w(TAG, "Tunnel status: ${device.status()}")
    }

    private suspend fun probeWithRetry(address: InetAddress): Boolean {
        repeat(PROBE_ATTEMPTS) { attempt ->
            if (isReachable(address)) return true
            if (attempt < PROBE_ATTEMPTS - 1) delay(PROBE_RETRY_DELAY_MS)
        }
        return false
    }

    /** Blocking; the caller runs it on an IO dispatcher. */
    private fun isReachable(address: InetAddress): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, EDGE_PORT), PROBE_TIMEOUT_MS)
                true
            }
        }.onFailure {
            Log.w(
                TAG,
                "Overlay probe to ${address.hostAddress}:$EDGE_PORT failed after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms",
                it,
            )
        }.getOrDefault(false)
    }

    /**
     * True when some up VPN network is carrying the overlay subnet.
     *
     * The subnet is taken from [server]'s first three octets — the POC's `10.77.0.0/24`,
     * in which the server is `10.77.0.1` and the phone `10.77.0.2`. That is a property of
     * this deployment, not a general truth, and the failure it can produce is bounded and
     * safe: a deployment on a different overlay subnet is not recognised, so the tier
     * reports [LocalServerStatus.Idle] and the app uses the public edge, which works. A
     * wrong answer here is never a wrong pin.
     */
    private fun hasOverlayTunnel(manager: ConnectivityManager, server: InetAddress): Boolean =
        runCatching {
            manager.allNetworks.any { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
                val link = manager.getLinkProperties(network) ?: return@any false
                link.linkAddresses.map { it.address }.sharesOverlaySubnetWith(server)
            }
        }.onFailure { Log.w(TAG, "Could not enumerate networks", it) }.getOrDefault(false)
}

/**
 * The configured overlay address, or null when it is blank or not a literal IPv4 address.
 *
 * ⚠️ **Literal-only on purpose.** `InetAddress.getByName` resolves a *name* through the
 * system resolver, which is a blocking DNS call — and this value is read on every probe,
 * including from the foreground observer. It is also the wrong failure: a hostname here
 * would put the resolution back in the system's hands, which is the one thing this whole
 * seam exists to take away. A non-literal is treated as "not configured".
 */
internal fun parseOverlayAddress(raw: String): InetAddress? {
    val text = raw.trim()
    val parts = text.split('.')
    if (parts.size != 4) return null
    val octets = ByteArray(4)
    for (index in 0 until 4) {
        val value = parts[index].toIntOrNull() ?: return null
        if (value !in 0..255) return null
        // A leading zero is not a valid dotted-quad octet, and `toIntOrNull` would
        // accept `010` as ten — which is a different address than it looks like.
        if (parts[index].length > 1 && parts[index].startsWith("0")) return null
        octets[index] = value.toByte()
    }
    return runCatching { InetAddress.getByAddress(octets) }.getOrNull()
        ?.takeIf { it is Inet4Address }
}

/**
 * True when one of [this] lies on the same /24 as [server].
 *
 * A VPN link address is the discriminator between the overlay tunnel and any other VPN
 * the user might be running: the overlay puts the client at `10.77.0.2`, so a tunnel
 * carrying it is ours, while NordVPN's interface is somewhere else entirely.
 */
internal fun List<InetAddress>.sharesOverlaySubnetWith(server: InetAddress): Boolean {
    val target = server.address
    if (target.size != 4) return false
    return any { address ->
        val candidate = address.address
        candidate.size == 4 &&
            candidate[0] == target[0] &&
            candidate[1] == target[1] &&
            candidate[2] == target[2]
    }
}
