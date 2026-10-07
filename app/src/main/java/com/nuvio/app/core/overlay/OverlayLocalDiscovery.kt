package com.nuvio.app.core.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.addons.AddonRepository
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "OverlayLocalDiscovery"

/**
 * Finds a boomio server on the local network and pins its address.
 *
 * This is the client half of the overlay's Tier 1, and it is deliberately
 * **tunnel-free**: Android permits one active `VpnService` and that slot belongs to
 * whichever VPN the user chose, so the app cannot have one. It browses mDNS instead
 * and repoints the public FQDN's *resolution* — never a URL, because Caddy serves
 * certificates per named site block and a bare address fails TLS.
 *
 * The tier earns its place on a LAN where system DNS does not point at the server:
 * the server moved, the router changed, a `hosts` entry was lost. At home, where
 * dnsmasq already resolves, this is a no-op by design.
 *
 * [OverlayTunnel] is the sibling that covers every network this one cannot reach, and
 * the two publish into the same registry under different [LocalServerSource]s. They are
 * independent on purpose: neither can be blamed for the other's failure, and on the
 * reference deployment they are mutually exclusive in practice, because the tunnel's
 * endpoint is the public WAN address and hairpin NAT is off.
 *
 * A missed path degrades to the public edge rather than breaking, which is why
 * [LocalServerStatus.Unavailable] never raises and why the pin is additive.
 */
internal object OverlayLocalDiscovery {

    /** Must match the A1 publisher exactly (`avahi-publish-service`), or nothing is seen. */
    private const val SERVICE_TYPE = "_boomio-overlay._udp"

    /**
     * The edge port, not the advertised SRV port.
     *
     * The advert is **tunnel-shaped** — it publishes SRV port 51820 and TXT
     * `addr=10.77.0.1`/`pubkey=…`, all WireGuard-tier semantics. Tier 1 uses exactly
     * one field: the SRV target's A record. The port comes from the URL, which is 443.
     */
    private const val EDGE_PORT = 443

    /**
     * The **maximum** a browse may take — not the time it always takes. The browse is cut
     * short as soon as a candidate answers the liveness gate (see [collectCandidates]), so
     * this bounds a *silent* network rather than taxing a responsive one.
     *
     * ⚠️ **A bound on the `pin`, not on the browse.** It is the default for [refresh]'s
     * `windowMs`, and the endpoint ladder passes a shorter one — see that parameter for why
     * the two callers want different numbers from the same browse.
     */
    private const val BROWSE_WINDOW_MS = 6_000L
    private const val RESOLVE_TIMEOUT_MS = 3_000L
    private const val LIVENESS_TIMEOUT_MS = 700

    /**
     * How long a browse result is trusted. Re-browsing on every foreground would be
     * pointless traffic on a network that has not changed; the network callback below
     * is what catches the case that actually matters.
     */
    private const val RESULT_TTL_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val browseMutex = Mutex()

    private var appContext: Context? = null

    /** Elapsed-realtime of the last browse *attempt*; a clock change cannot confuse it. */
    @Volatile
    private var lastBrowsedAtMs = 0L

    private var lifecycleStarted = false

    /**
     * The address the last successful browse pinned, or null when nothing is pinned.
     *
     * A flow rather than a plain field so the pin can be **re-applied** to a widened host
     * set without re-browsing, in *either* order. The host set is not fixed at browse
     * time: the addon catalogue, which is where most of the app's server hosts come from,
     * loads asynchronously and can land before or after the pin — see
     * [observeAddonChanges].
     */
    private val pinnedAddress = MutableStateFlow<InetAddress?>(null)

    /**
     * The last advert this browse **verified**, tuple and all, for the endpoint ladder.
     *
     * The browse has always resolved the whole advert and then thrown away everything but the
     * address; [parseMdnsAdvertTxt] now reads the `pubkey`/`port` it was already receiving.
     * Publishing it here is what lets rung 1 be free: `OverlayEndpointDiscovery` reads this
     * instead of opening a *second* NSD browse on the same service, which would double the
     * multicast traffic and the six-second window for one answer nobody needed twice.
     *
     * Cleared with the pin, and for the same reason: an advert from the network the phone has
     * just left is worse than none. `@Volatile` rather than a flow — one writer, and the reader
     * is a coroutine that has already awaited the browse.
     */
    @Volatile
    private var lastVerifiedAdvert: OverlayMdnsAdvert? = null

    /** The most recent advert a completed browse verified, or null when nothing is known. */
    fun lastVerifiedAdvert(): OverlayMdnsAdvert? = lastVerifiedAdvert

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Idempotent: onCreate can run again after a configuration-forced restart, and
        // a second set of observers would double every browse.
        if (lifecycleStarted) return
        lifecycleStarted = true
        observeForeground()
        observeNetworkChanges()
        observeServerChanges()
        observeAddonChanges()
    }

    /** Browses on foreground, at most once per [RESULT_TTL_MS]. */
    private fun observeForeground() {
        scope.launch {
            AppForegroundMonitor.events()
                // `events()` is a `callbackFlow` whose block calls
                // `ProcessLifecycleOwner.lifecycle.addObserver`, and Android requires
                // that on the main thread. Collecting it straight from this object's
                // IO scope throws `IllegalStateException: Method addObserver must be
                // called on the main thread` on the *first* collection — which, because
                // `initialize` runs in `MainActivity.onCreate`, kills the app on launch.
                //
                // `flowOn` moves only the flow's upstream. The collection body below
                // stays on IO, so browsing still never runs on the UI thread.
                .flowOn(Dispatchers.Main.immediate)
                .collect { visibility ->
                    if (visibility != AppVisibility.Foreground) return@collect
                    if (SystemClock.elapsedRealtime() - lastBrowsedAtMs < RESULT_TTL_MS) return@collect
                    refresh()
                }
        }
    }

    /**
     * Drops the pin the moment the default network changes.
     *
     * This is the main stale-pin defence: a LAN address is meaningless on a different
     * network, and keeping it would point the app at a host that is not there. Waiting
     * for the next browse to notice would instead fail the *first* request after every
     * network change.
     */
    private fun observeNetworkChanges() {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onDefaultNetworkChanged()
            override fun onLost(network: Network) = onDefaultNetworkChanged()
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Could not observe network changes", it) }
    }

    private fun onDefaultNetworkChanged() {
        clear()
        lastBrowsedAtMs = 0L
        refreshAsync()
    }

    /**
     * Re-derives the host set when the configured server changes.
     *
     * The pin is host-scoped, so switching servers changes what may be pinned at all —
     * and leaving the old pin in place would apply it to the new server's host.
     */
    private fun observeServerChanges() {
        scope.launch {
            ServerConfigurationRepository.active
                .map { it.backendUrl }
                .distinctUntilChanged()
                // The value already in the flow at assembly is not a change.
                .drop(1)
                .collect {
                    clear()
                    lastBrowsedAtMs = 0L
                    refresh()
                }
        }
    }

    /**
     * Re-applies the pin when the *host set* widens under a pin that is already in place.
     *
     * ⚠️ **This is the difference between "auth works" and "the app works".** The addon
     * catalogue is where most of the app's server hosts live (`derivePinnableAddonHosts`),
     * and it is loaded asynchronously — so deriving the host set only inside [refresh]
     * pins the server hosts and leaves the whole catalogue plane pointing at the public
     * edge for the rest of the session.
     *
     * ⚠️ **The two may arrive in either order, and the first version of this assumed the
     * wrong one.** It collected the host set and read `pinnedAddress` *inside* the
     * collector, dropping every emission that arrived while the pin was still null — and
     * because the mapped set is `distinctUntilChanged`, a dropped emission was never
     * revisited. Measured on device 2026-10-05, the addons beat the pin:
     *
     * ```
     * 11:28:35.428  AddonRepository: initialize() — local addon count: 21
     * 11:28:39.728  OverlayPinRegistry: Pinned [bsc, bss-iptv, nuvioserver]
     * ```
     *
     * The host set widened four seconds *before* the pin existed, so the widened set was
     * discarded and the app pinned three hosts for the whole session. [combine] makes the
     * order irrelevant: whichever changes last produces the pair, so a pin that lands
     * after the addons still gets the full set.
     *
     * Deliberately does **not** re-browse: the discovered address has not changed, only
     * the list of hosts that should use it. Re-browsing here would cost a 6 s NSD window
     * on every profile switch for no new information.
     */
    private fun observeAddonChanges() {
        scope.launch {
            combine(
                AddonRepository.uiState.map { localServerHosts() }.distinctUntilChanged(),
                pinnedAddress,
            ) { hosts, address -> hosts to address }
                .collect { (hosts, address) ->
                    if (hosts.isNotEmpty() && address != null) {
                        OverlayPinRegistry.pin(LocalServerSource.LAN, hosts, address)
                    }
                }
        }
    }

    /**
     * Fire-and-forget [refresh] for callers that are not themselves coroutines —
     * lifecycle hooks and configuration changes. Never cancelled, so a browse that is
     * in flight when the app backgrounds still publishes its result.
     */
    fun refreshAsync() {
        scope.launch { refresh() }
    }

    /** Drops the pin without browsing. Used on network change and on server switch. */
    fun clear() {
        pinnedAddress.value = null
        // The advert goes with the pin. The endpoint ladder reads this, and an advert resolved
        // on the network the phone has just left names a host that is not on this one.
        lastVerifiedAdvert = null
        OverlayPinRegistry.clear(LocalServerSource.LAN)
        LocalServerState.update(LocalServerSource.LAN, LocalServerStatus.Idle)
    }

    /**
     * Browses and republishes [LocalServerState]. Safe to call repeatedly; concurrent
     * calls serialise rather than racing the NSD listener.
     *
     * ⚠️ **[windowMs] is a parameter because the two callers want different numbers from the
     * same browse, and the difference is latency on the cold path.** This object's own
     * foreground trigger is the *pin*: it has five minutes of TTL behind it and no user
     * waiting on the answer, so it takes the full [BROWSE_WINDOW_MS] and would rather see a
     * second server on the LAN than finish early. The endpoint ladder is the opposite — it is
     * walked in front of a first-run screen, and a Wi-Fi network with no boomio advert on it
     * costs the whole window in pure dead latency before the ladder can say anything. It
     * passes a shorter one.
     *
     * Still a **maximum** either way: a candidate that answers the liveness gate pins
     * immediately (see [collectCandidates]) and the window only bounds a silent network.
     */
    suspend fun refresh(windowMs: Long = BROWSE_WINDOW_MS) = browseMutex.withLock {
        // Stamped on entry, not on success: a browse that finds nothing is still a
        // browse, and re-running it on every foreground would be pure traffic.
        lastBrowsedAtMs = SystemClock.elapsedRealtime()

        val context = appContext
        if (context == null) {
            publish(LocalServerStatus.Unavailable("Local discovery is not initialized"))
            return@withLock
        }

        if (!isOnLocalNetwork(context)) {
            // Cellular cannot see mDNS: it is link-local with TTL 1.
            publish(LocalServerStatus.Unavailable("Not on Wi-Fi or Ethernet"))
            return@withLock
        }

        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            publish(LocalServerStatus.Unavailable("This device has no service discovery"))
            return@withLock
        }

        val hosts = localServerHosts()
        if (hosts.isEmpty()) {
            publish(LocalServerStatus.Unavailable("No boomio server is configured"))
            return@withLock
        }

        LocalServerState.update(LocalServerSource.LAN, LocalServerStatus.Searching)

        val multicastLock = acquireMulticastLock(context)
        // Written only by the single browse worker, read after it has been joined, so no
        // lock is needed. Every entry is a *distinct* address -- the worker dedupes.
        val verified = mutableListOf<Candidate>()
        val candidates = try {
            collectCandidates(nsd, windowMs) { candidate ->
                verified += candidate
                if (verified.size == 1) {
                    // ⚠️ **The cold-launch win, and it is worth seconds.** This pin used to
                    // wait for the whole browse window, so it landed at t≈6 s even though
                    // mDNS resolved the server in ~180 ms. Every request the app fires at
                    // process start had by then already gone to the WAN address and hung
                    // for its full 10 s timeout -- and nothing re-drove it, because the
                    // failure is a request timeout, not a retryable route failure. Measured
                    // on device 2026-10-05: launch to usable was **62 s**.
                    pinCandidate(localServerHosts().ifEmpty { hosts }, candidate)
                } else {
                    // Two servers on one LAN is a real situation with no honest tiebreak:
                    // the adverts carry no identity to choose between them. Picking
                    // arbitrarily would be worse than not choosing, so the pin placed just
                    // above is revoked and the app falls back to the public edge.
                    publish(LocalServerStatus.Unavailable("More than one server on this network"))
                }
            }
        } catch (cancellation: CancellationException) {
            // Never swallowed: continuing to publish after cancellation would leave a
            // pin behind that the caller believes it cancelled.
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Browse failed", error)
            emptyList()
        } finally {
            releaseMulticastLock(multicastLock)
        }

        Log.d(TAG, "Browse: ${candidates.size} candidate(s), ${verified.size} reachable on $EDGE_PORT")

        // Already decided, from this same information, while the browse was still running.
        // Re-deciding here could only undo that -- and would undo it *later*, after the app
        // had begun using the pin.
        if (verified.isNotEmpty()) return@withLock

        // Nothing answered the liveness gate. The probe is a *preference*, not a veto: a
        // probe that fails must not throw away a discovery the browse actually made. The
        // pin is pin-first, so a candidate that turns out to be dead costs one failed
        // connect and the app falls back to the public edge, whereas *discarding* the
        // candidate means the tier does nothing at all. Observed on device 2026-10-05 --
        // the probe timed out on a LAN the app's own client was reaching in 2.6 ms, and
        // the resulting empty pin silently disabled discovery everywhere.
        val fallback = candidates.map { it.address }.distinctBy { it.hostAddress }
        when {
            fallback.isEmpty() ->
                publish(LocalServerStatus.Unavailable("No server found on this network"))

            // The same ambiguity, reached from the probe-less path.
            fallback.size > 1 ->
                publish(LocalServerStatus.Unavailable("More than one server on this network"))

            else -> {
                val target = fallback.single()
                Log.w(TAG, "Nothing passed the $EDGE_PORT liveness gate; pinning anyway: $target")
                pinCandidate(localServerHosts().ifEmpty { hosts }, candidates.first { it.address == target })
            }
        }
    }

    /**
     * Publishes [candidate] as the pinned server.
     *
     * ⚠️ The host set is **re-derived** here, never reused from the top of [refresh]: the
     * addon catalogue — where most server hosts live — loads asynchronously and may or may
     * not have landed by now. Pinning the stale set is what left 3 hosts pinned on
     * 2026-10-05 while the addons were already in state. A widening *after* this call is
     * covered by [observeAddonChanges], which re-pins when the host set changes.
     */
    private fun pinCandidate(hosts: Set<String>, candidate: Candidate) {
        pinnedAddress.value = candidate.address
        // Published for the endpoint ladder (architecture §4.4 rung 1) — the tuple this browse
        // has always received and never read. Set here rather than in the browse so it is only
        // ever an advert that was actually *used*, and so the failure path that clears the pin
        // clears this with it.
        lastVerifiedAdvert = OverlayMdnsAdvert(
            address = candidate.address,
            serverPublicKeyBase64 = candidate.serverPublicKey,
            port = candidate.wgPort,
            serviceName = candidate.serviceName,
        )
        OverlayPinRegistry.pin(LocalServerSource.LAN, hosts, candidate.address)
        LocalServerState.update(
            LocalServerSource.LAN,
            LocalServerStatus.Found(
                address = candidate.address.hostAddress.orEmpty(),
                source = LocalServerSource.LAN,
                serviceName = candidate.serviceName,
                hostName = candidate.hostName,
                version = candidate.version,
            ),
        )
    }

    private fun publish(status: LocalServerStatus) {
        // Any status that is not Found means there is nothing to pin. Clearing here is
        // the main stale-pin defence: an address from another network is worse than none.
        if (status !is LocalServerStatus.Found) {
            // Dropped together with the registry, or `observeAddonChanges` would re-pin
            // an address the user has just invalidated.
            pinnedAddress.value = null
            OverlayPinRegistry.clear(LocalServerSource.LAN)
        }
        LocalServerState.update(LocalServerSource.LAN, status)
    }

    private data class Candidate(
        val address: InetAddress,
        val serviceName: String?,
        val hostName: String?,
        val version: String?,
        /**
         * The advert's `pubkey` and `port` — the WireGuard half of the tuple.
         *
         * Null when the advert did not carry them, which is a real case worth representing:
         * a server older than the tunnel publishes `addr`/`v` and nothing else, and the ladder
         * must see an unusable candidate rather than a fabricated key.
         */
        val serverPublicKey: String? = null,
        val wgPort: Int? = null,
    )

    /**
     * Browses for up to [windowMs] and returns everything that resolved.
     *
     * `resolveService` handles one service at a time and throws if re-entered, so
     * found services are queued to a single worker rather than resolved inline.
     * `onServiceFound`/`onServiceLost` flapping about once a second is *normal* with
     * Avahi, so nothing here treats a single event as authoritative — a late re-find
     * simply overwrites the candidate.
     *
     * ⚠️ **[onVerified] is what makes the window a maximum instead of an obligatory
     * wait.** The liveness probe used to run *after* the window closed, so the caller
     * could not pin until the window had elapsed even when mDNS had answered in
     * ~180 ms. Probing here lets the caller pin on the first server that answers, which
     * is the difference between a 62 s cold launch and a ~2 s one (measured 2026-10-05).
     * Only a *distinct* address is reported, so Avahi re-announcing the same server about
     * once a second cannot read as a second server.
     *
     * ⚠️ **The window still runs to its full length after a candidate is verified**, because
     * the caller may be waiting on the *list* and not just the first entry — [refresh]'s
     * ambiguity check ("More than one server on this network") is exactly that. Who waits how
     * long is therefore the caller's choice, which is what [windowMs] is for.
     */
    private suspend fun collectCandidates(
        nsd: NsdManager,
        windowMs: Long,
        onVerified: (Candidate) -> Unit,
    ): List<Candidate> = coroutineScope {
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val found = LinkedHashMap<String, Candidate>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to start: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to stop: $errorCode")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "onServiceFound: '${serviceInfo.serviceName}'")
                queue.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Deliberately inert. A lost event is *not* authority to drop a candidate:
                // Avahi flaps these about once a second, and a real departure is caught by
                // the liveness gate below, which is the test that cannot be fooled.
            }
        }

        var started = false
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            started = true

            val worker = launch(Dispatchers.IO) {
                for (info in queue) {
                    val candidate = resolve(nsd, info) ?: continue
                    // `put` returns the previous value: a re-found service is the *same*
                    // server, so only a genuinely new address may reach the caller.
                    val isNewAddress =
                        found.put(candidate.address.hostAddress.orEmpty(), candidate) == null
                    if (isNewAddress && isReachable(candidate.address)) onVerified(candidate)
                }
            }

            delay(windowMs)
            queue.close()
            worker.join()
        } finally {
            // Calling stopServiceDiscovery before onDiscoveryStarted throws, hence `started`.
            if (started) runCatching { nsd.stopServiceDiscovery(listener) }
                .onFailure { Log.w(TAG, "stopServiceDiscovery failed", it) }
        }

        found.values.toList()
    }

    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): Candidate? {
        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.d(TAG, "onResolveFailed: '${serviceInfo.serviceName}' code=$errorCode")
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (continuation.isActive) {
                                continuation.resume(candidateOf(serviceInfo))
                            }
                        }
                    },
                )
            }
        }
        // Distinguishes "the resolver never came back" from "it came back with nothing
        // usable" -- both used to arrive here as a bare null, which is why a browse that
        // saw a perfectly good advert could report finding nothing with no trace at all.
        if (resolved == null) Log.d(TAG, "Resolve gave no candidate for '${info.serviceName}'")
        return resolved
    }

    private fun candidateOf(info: NsdServiceInfo): Candidate? {
        val addresses = addressesOf(info)
        Log.d(TAG, "Resolved '${info.serviceName}': ${addresses.map { it.hostAddress }}")
        val address = addresses.firstOrNull { it is Inet4Address && it.isUsableLanAddress() }
        if (address == null) {
            Log.d(TAG, "No usable IPv4 address for '${info.serviceName}'")
            return null
        }
        val tuple = parseMdnsAdvertTxt(attributesOf(info))
        if (tuple.serverPublicKeyBase64 == null) {
            // Not fatal to the *pin* — the address is what Tier 1 pins, and it is valid
            // regardless. It is fatal to the *ladder* rung, which needs the whole tuple, so it
            // is worth a line: from the server side, an advert missing its key and an advert
            // nothing ever looked at are indistinguishable.
            Log.d(TAG, "Advert '${info.serviceName}' carries no usable pubkey; rung 1 cannot use it")
        }
        return Candidate(
            address = address,
            serviceName = info.serviceName,
            hostName = runCatching { info.host?.hostName }.getOrNull(),
            version = attributeVersion(info),
            serverPublicKey = validServerKeyOrNull(tuple.serverPublicKeyBase64),
            wgPort = tuple.port,
        )
    }

    /** `attributes` throws on some platform builds when the record was never resolved. */
    private fun attributesOf(info: NsdServiceInfo): Map<String, ByteArray>? =
        runCatching { info.attributes }.getOrNull()

    /**
     * `getHost()` returns a single [InetAddress] and is the only form before API 34;
     * `getHostAddresses()` was added in 34. Read the older one first, then widen.
     */
    private fun addressesOf(info: NsdServiceInfo): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        runCatching { info.host }.getOrNull()?.let { addresses += it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { info.hostAddresses }.getOrNull()?.let { addresses += it }
        }
        return addresses
    }

    private fun attributeVersion(info: NsdServiceInfo): String? = runCatching {
        info.attributes?.get("v")?.toString(Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun InetAddress.isUsableLanAddress(): Boolean =
        !isLoopbackAddress && !isMulticastAddress && !isAnyLocalAddress && !isLinkLocalAddress

    /** Blocking; the caller runs it on an IO dispatcher. */
    private fun isReachable(address: InetAddress): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, EDGE_PORT), LIVENESS_TIMEOUT_MS)
                true
            }
        }.onFailure {
            // This used to be swallowed, which is why a gate that failed on *every* run
            // was invisible: the browse reported "found nothing" and nothing said why.
            Log.w(
                TAG,
                "Liveness probe to ${address.hostAddress}:$EDGE_PORT failed after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms",
                it,
            )
        }.getOrDefault(false)
    }

    private fun isOnLocalNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * The single most-recommended fix for Avahi services not being found. Held for one
     * browse and released in a `finally` — never long-lived, which is why reception is
     * foreground-scoped.
     */
    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        val permission = context.checkSelfPermission(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
        // A denial degrades to "browse without the lock", never a crash.
        if (permission != PackageManager.PERMISSION_GRANTED) return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        return runCatching {
            wifi.createMulticastLock("boomio-overlay-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        if (lock == null) return
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { Log.w(TAG, "MulticastLock release failed", it) }
    }
}
