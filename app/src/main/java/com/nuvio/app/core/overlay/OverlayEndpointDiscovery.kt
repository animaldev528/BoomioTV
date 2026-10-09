package com.nuvio.app.core.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.boomio.BOOMIO_SERVICE_HOST
import com.nuvio.app.features.boomio.BoomioConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "OverlayEndpointDiscovery"

/**
 * How long the reachability gate waits for a TCP connect, in milliseconds.
 *
 * ⚠️ **File-level, not a member of the object below**, because `isReachable` is a free function
 * at the bottom of this file — the gate has to be callable from `resolve()` without dragging the
 * object's state along, and a constant declared inside the object is not visible from there.
 *
 * 900 ms is deliberately shorter than rung 2's whole-record budget: the gate may run once per
 * candidate, so its cost multiplies where the resolver's does not. On a LAN the connect either
 * completes in single-digit milliseconds or the host is not there — see `OverlayLocalDiscovery`,
 * where a 2.6 ms LAN round trip was once mistaken for a dead server by a probe that waited too
 * briefly to be meaningful and too long to be free.
 */
private const val GATE_TIMEOUT_MS = 900

/**
 * The endpoint ladder: mDNS → [PROV_RECORD] → a person.
 *
 * ### Why this is not a convenience
 *
 * Architecture §10.7 chose **"no LAN fallback"**: the tunnel is the only route to the server, on
 * the LAN as much as off it, so when it cannot be brought up the app has nowhere else to go. The
 * ladder is therefore not an optimisation that makes the app nicer when discovery works — it is
 * the only thing between a missing mDNS advert and a dead app at home, which is why the failure
 * state ([OverlayEndpointStatus.NeedsManual]) carries an action rather than a message.
 *
 * ### The rungs, and the rule that binds them
 *
 * | # | Rung | Yields |
 * |---|---|---|
 * | 1 | the mDNS advert `_boomio-overlay._udp` | TXT `pubkey`/`port` + the SRV target's `A` |
 * | 2 | the [PROV_RECORD] record | an `A` + the same TXT tuple |
 * | 3 | a person | whatever they typed, plus the server's public key |
 *
 * ⚠️ **"Working" means it passed the gate, not that it resolved.** A rung that returns a record
 * the client cannot reach is *worse* than one that returns nothing, because the ladder would
 * stop on it: on the LAN the boomio FQDNs resolve publicly to the WAN address and hairpin is
 * off, so an endpoint that looks fine and routes nowhere is the exact failure §7 describes. Each
 * candidate is therefore TCP-probed before it is accepted, and the ladder keeps walking rungs
 * until one answers.
 *
 * ⚠️ **The gate is reachability, and the terminal verdict is still the handshake.** A TCP
 * connect to the edge proves the host is up and its Caddy is listening; it does **not** prove
 * the UDP port is forwarded or that the server holds this peer's key. Nothing here can prove
 * that — a WireGuard handshake is the only oracle, and it happens after this object is done. So
 * the gate is documented as a *preference*, it never rejects a candidate outright (it only
 * decides the order), and the failure it can miss is a tunnel that comes up against a dead
 * forward. See `OverlayWgTunnel.up` and `wg show boomio-overlay` for the real check.
 *
 * ### What it deliberately does not do
 *
 * It does not bring the tunnel up, and it does not touch the relay. U3 owns the wiring; this
 * object's whole contract is to publish **one endpoint** and say where it came from.
 */
internal object OverlayEndpointDiscovery {

    /**
     * The **one name** — the discovery record, and the service FQDN every URL, SNI and certificate
     * check stays on. It is [BOOMIO_SERVICE_HOST] and nothing else, declared there so this ladder
     * and the client's URL floor cannot drift apart.
     *
     * ⚠️ **One name, because the split it replaced bought nothing.** `boomio-prov` (carrying the
     * tuple) and `boomio.duckdns.org` (the origin) were two DuckDNS names spending two `TXT` slots
     * on one job, and a deployment that renamed one and not the other failed rung 2 for a reason
     * that had nothing to do with DNS. DuckDNS gives a name exactly one `TXT` slot, so the publisher
     * borrows it for about a minute at each certificate renewal; the read-before-write arbitration
     * in `overlay/overlay-duckdns.py` is what makes the sharing safe.
     *
     * ⚠️ **It carries the whole tuple, which is what makes "no LAN DNS" survivable.** The `TXT`
     * record holds the v2 tuple: `svc` — the service FQDN, which equals this name — plus the `lan`
     * and `wan` **literals**, their per-plane ports, and the two keys. A client on a stranger's
     * network therefore learns where the server is, where it is at home, and who it is, from one
     * public lookup with no resolver of the deployment's own involved.
     *
     * ⚠️ **The `A` record is the server's private address, so the client must not resolve this
     * name.** It is written explicitly as the LAN literal so a browser on the LAN can reach the
     * management surface by a name a certificate can be issued for. Off the property that address is
     * undialable, and the record's `wan` literal is what the client dials instead — with SNI and the
     * certificate check still naming `svc`. Resolving the name here would answer with the one
     * address that cannot work, which is also why it is never baked as a tunnel *endpoint*: it is an
     * origin and a lookup, never a dial target off-LAN.
     *
     * ⚠️ **This is a bootstrap constant, and it has to be compiled in.** A client that has never
     * reached the server has never read a publication to learn a name from, so there is nothing to
     * discover it *from*; it is also what a person types by hand in the absence of anything else,
     * which is why rung 3 accepts it too.
     *
     * ⚠️ **`boomio-local.tracemonkey.org` and `boomio-lan.duckdns.org` used to sit in this ladder
     * and are retired.** `boomio-local` was never published in any form — measured 2026-10-07 as
     * NXDOMAIN from the house dnsmasq, the router and `1.1.1.1` alike — and `boomio-lan` existed
     * only to hold the private address this name now holds itself. `boomio-tls.duckdns.org` is
     * *not* part of that cut: it is still the mTLS plane.
     */
    internal const val PROV_RECORD = BOOMIO_SERVICE_HOST

    /** The WireGuard port, when nothing published one. */
    private const val DEFAULT_WG_PORT = OverlayAdvertTuple.DEFAULT_PORT

    /**
     * Rung 1's budget, in and out — how long the ladder waits on the mDNS advert.
     *
     * ⚠️ **This is §3's cap, and it is here rather than around the race because a race is
     * bounded by its slowest member.** With rung 2 running beside it ([raceRungs]) the walk costs
     * the longer of the two rungs rather than their sum.
     *
     * ⚠️ **Two seconds was measured to be too short, and the "~180 ms" this comment used to
     * claim was never true of a roaming client.** On 2026-10-07, on the reference LAN, with the
     * screen held awake and the advert demonstrably in the platform's own NSD cache (it appeared
     * in full, `ip: [192.168.68.65] port: 51820`, in `dumpsys servicediscovery`), two consecutive
     * cold launches both ended in `Browse: 0 candidate(s), 0 reachable on 443` — while the phone
     * was roaming between mesh BSSIDs, which invalidates the mDNS cache and makes the app's own
     * query the one that has to be answered. `MdnsDiscoveryManager` sends that query once and then
     * schedules the next **20 s** later, so a lost answer inside the window is not merely delayed,
     * it is the whole browse.
     *
     * Six seconds — the same window `OverlayLocalDiscovery` uses for the pin. That number is not a
     * measured latency either; it is the one window this LAN has actually been observed to
     * succeed with. §3's "~2–3 s" no longer holds on a cold path: the cost is now paid in full
     * only when nothing answers, and a cold path that finds nothing gets no tunnel at all, so the
     * wait is buying the feature rather than delaying it. A walk that *does* answer still returns
     * on the first advert.
     */
    internal const val RUNG1_BUDGET_MS = 6_000L

    /**
     * Rung 2's whole budget, in and out.
     *
     * ⚠️ **This exists because rung 1 already taught the lesson.** The mDNS window used to be an
     * obligatory wait and cost every cold launch six seconds (`1aa62092`); a DNS timeout on the
     * same critical path would put it straight back. A miss has to be cheap, so the whole rung —
     * address, tuple, and CNAME follow — is bounded here and gives up quietly.
     */
    private const val RUNG2_BUDGET_MS = 1_200L

    /**
     * Rung 3's resolution budget, for a person who typed a hostname rather than an address.
     *
     * Longer than rung 2's because it is not on the cold path in the same way — by the time
     * anyone is typing, the app is already running and the wait is a deliberate one they are
     * watching.
     */
    private const val MANUAL_RESOLVE_BUDGET_MS = 1_500L

    /**
     * The literal tier, in and out — the last thing tried before the ladder asks for an address.
     *
     * ⚠️ **Cheaper than the tier it replaces, because there is no DNS left in it.** The published
     * addresses are literals, so this walk is at most two blocking gate probes ([GATE_TIMEOUT_MS]
     * each) and no resolution at all. The ceiling is kept anyway because this runs on the path
     * where someone is already waiting for an answer, and unlike a rung it has nothing to do
     * afterwards but tell them to type an address — so it is what stops that message arriving
     * seconds late.
     */
    private const val TARGET_TIER_BUDGET_MS = 2_500L

    /**
     * The reachability gate's port.
     *
     * The **edge**, not the WireGuard port: what is being asked is "is the server there", and a
     * dead UDP forward is invisible to TCP. The same port and the same reasoning as
     * `OverlayLocalDiscovery`'s liveness gate.
     */
    private const val EDGE_PORT = 443

    /**
     * The same foreground cadence the two pin sources use.
     *
     * ⚠️ **Never armed by entry into [`resolve`].** Stamping it on the way in meant a walk that
     * found *nothing* — the one outcome that actually needs a retry — bought itself five minutes
     * of silence: `observeForeground` would not re-walk, and the only escape left was a network
     * change. The field note this file already carried ("discovery goes silently dead on the LAN")
     * is that bug; on 2026-10-07 it was reproduced as `Browse: 0 candidate(s)` followed by no
     * further browse for the life of the process.
     *
     * It is now armed in two places, both deliberate: [accept], on an answer worth keeping, and
     * [scheduleMissRetry]'s `finally`, once a miss has spent its prompt retries — so a network
     * with no server on it settles back to this cadence instead of browsing on every foreground.
     */
    private const val RESULT_TTL_MS = 5 * 60 * 1000L

    /** How long to wait after a walk that found nothing before walking again. */
    private const val MISS_RETRY_MS = 2_000L

    /**
     * How many miss retries one miss may chain, total.
     *
     * Bounded on purpose: a network with no boomio server on it must not browse forever. Three
     * tries plus the original walk covers the roam case — the mesh here reassociates on the order
     * of a minute, and each retry is a fresh browse against a freshly populated cache.
     */
    private const val MISS_RETRY_ATTEMPTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ladderMutex = Mutex()

    private var appContext: Context? = null
    private var lifecycleStarted = false

    /**
     * When the last **successful** walk finished, for [observeForeground]'s cadence.
     *
     * `internal` rather than private so the host test can pin the thing that actually broke: that
     * a walk which produced no answer leaves this at zero. Same reason `wireManualEntry` is
     * `internal` — a field with a load-bearing invariant and no pure way to observe it otherwise.
     */
    @Volatile
    internal var lastResolvedAtMs = 0L

    /**
     * A miss retry is already scheduled, so further misses must not chain another.
     *
     * ⚠️ Without this, every `onNetworkChanged` during a roam would start its own retry chain and
     * a wandering phone would browse continuously. One chain at a time, and the chain is what
     * gets the bounded attempt count.
     */
    @Volatile
    private var missRetryPending = false

    /** The endpoint last accepted, so a re-run that finds nothing does not erase a good answer. */
    @Volatile
    private var current: OverlayEndpoint? = null

    /**
     * The dial targets the last publication carried, or null when none ever did.
     *
     * ⚠️ **Process-scoped, like everything else in this ladder — a known limit, not a decision.**
     * `BoomioConfig.overlayEndpoint`, which rung 3 reads, is runtime state too, so an address does
     * not survive a process death either. The consequence is specific: the targets rescue a roam
     * that happens *while the app is alive*, which is the case they were added for, but a cold
     * launch on a foreign network still lands on [OverlayEndpointStatus.NeedsManual].
     *
     * Persisting them is the obvious next increment — unlike the address, the two literals are
     * stable server identity rather than a property of the network — and it is deliberately not
     * done here, where it would be the only persisted thing in the discovery subsystem.
     */
    @Volatile
    private var discoveryTargets: OverlayDiscoveryTargets? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // ⚠️ Called **above** the once-per-process guard, and that ordering is deliberate. The
        // guard makes everything after it a no-op on a second call, which is right for observers
        // — but the UI's uplink is not an observer. Assigning a captured lambda twice costs
        // nothing; failing to assign it once leaves a ladder with no way to feed it rung 3, which
        // is the dead end the field exists to close.
        wireManualEntry()
        if (lifecycleStarted) return
        lifecycleStarted = true
        observeForeground()
        observeNetworkChanges()
    }

    /**
     * Points the UI's "use this address" control at this ladder's rung 3.
     *
     * Split out from [initialize] so it can be tested without dragging a `Context`, a lifecycle
     * observer and a `ConnectivityManager` into a test that is only about one assignment — the
     * same division the pure half of the test file already draws for the parsers.
     *
     * ⚠️ **The key is deliberately not taken from the UI.** [offerManual] falls back to the
     * server public key already held in `BoomioConfig`, which enrollment wrote on any device that
     * has one; a person cannot read a base64 X25519 key off anything they own. When even that is
     * missing, `offerManual` says so in the status the row renders, so the gap is reported rather
     * than guessed at.
     */
    internal fun wireManualEntry() {
        OverlayEndpointState.manualSubmit = { raw -> offerManual(raw, null) }
    }

    /** Re-runs the ladder on foreground, at most once per [RESULT_TTL_MS]. */
    private fun observeForeground() {
        scope.launch {
            AppForegroundMonitor.events()
                // Same reason as the two pin sources: `events()` installs a lifecycle observer
                // and Android requires that on the main thread. `flowOn` moves only the
                // upstream, so the ladder still runs on IO.
                .flowOn(Dispatchers.Main.immediate)
                .collect { visibility ->
                    if (visibility != AppVisibility.Foreground) return@collect
                    if (SystemClock.elapsedRealtime() - lastResolvedAtMs < RESULT_TTL_MS) return@collect
                    resolve()
                }
        }
    }

    /**
     * Re-runs the ladder the moment the network changes.
     *
     * ⚠️ **Without the TTL reset this would be a no-op in the case that matters.** The ladder has
     * a five-minute foreground cadence, and the interesting transition — home Wi-Fi to cellular —
     * happens well inside it, so the discovery that answers "which endpoint" would not re-run
     * until long after the answer changed. The endpoint itself is also network-dependent in a way
     * the overlay address is not: rung 1 yields the *LAN* address, which is meaningless on the
     * next network.
     */
    private fun observeNetworkChanges() {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()
            override fun onLost(network: Network) = onNetworkChanged()
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Could not observe network changes", it) }
    }

    private fun onNetworkChanged() {
        lastResolvedAtMs = 0L
        // The *accepted* endpoint is deliberately kept: it is re-tested by the next run, and
        // dropping it here would leave the app with no endpoint for the length of a ladder walk.
        // A tunnel that no longer works fails visibly, which §10.7 chose over a silent fallback.
        refreshAsync()
    }

    /** Fire-and-forget [resolve] for lifecycle hooks. */
    fun refreshAsync() {
        scope.launch { resolve() }
    }

    /** The endpoint currently in force, or null when the ladder has not produced one. */
    fun current(): OverlayEndpoint? = current

    /**
     * Walks the rungs and publishes the first endpoint that passes the gate.
     *
     * Safe to call repeatedly; concurrent calls serialise rather than racing each other into
     * three simultaneous mDNS browses.
     */
    suspend fun resolve(): OverlayEndpointStatus = ladderMutex.withLock {
        // ⚠️ **`lastResolvedAtMs` is deliberately NOT stamped here.** It used to be, on entry,
        // before the outcome was known — so the one result that needs a retry (nothing found)
        // was the one result that bought five minutes of silence. It is stamped in [accept],
        // where there is an answer to protect. A miss now schedules its own bounded retry.
        val context = appContext
        if (context == null) {
            return@withLock publish(
                OverlayEndpointStatus.Unavailable("Endpoint discovery is not initialized"),
            )
        }

        OverlayEndpointState.update(OverlayEndpointStatus.Searching)

        // ⚠️ **The two automatic rungs run *concurrently*, and that is §3's race.** Walked in
        // sequence the ladder pays the sum of the rungs before it can say anything — and the
        // first of them is a six-second mDNS window on any Wi-Fi network without an advert on
        // it, which is the "pure dead latency on every first run" §3 names. Raced, the walk
        // costs its slowest rung instead: ≤ [RUNG1_BUDGET_MS], whatever rung 2 is doing.
        //
        // ⚠️ **Raced to *collect*, not raced to the first answer, and there are two reasons.**
        // The first is the one §4.4 already gave: a ladder that stopped at the first rung that
        // merely *answered* would stop on rung 1's advert without ever learning that the address
        // it names is unreachable — the normal way for things to go wrong on the LAN, since the
        // boomio FQDNs resolve publicly to a WAN address hairpin cannot reach (§7). The gate
        // decides what *works*, so it has to see everything that answered.
        //
        // The second is a bug this file has already been bitten by. The other tempting shape —
        // take whichever rung answers first and abandon the loser — would cancel a browse
        // mid-flight, and that browse owns a multicast lock, the LAN pin and `LocalServerState`.
        // A cancelled `OverlayLocalDiscovery.refresh` leaves `LocalServerStatus` on `Searching`,
        // no pin at all, and a five-minute TTL stamped over the top so nothing retries: discovery
        // goes silently dead on the LAN, which is the failure recorded twice in
        // `OverlayLocalDiscovery`. ⚠️ **This ladder used to reproduce it from the other end**, by
        // stamping that same TTL on entry rather than on success — so a walk that found nothing
        // was silenced for five minutes. The stamp now lives in [accept] and a miss retries; the
        // TTL is only ever armed over an answer worth keeping.
        val candidates = raceRungs(
            { rung1Mdns() },
            { rung2DiscoveryRecord(context) },
        ).toMutableList()
        rung3Manual(context)?.let(candidates::add)

        // ⚠️ **The gate is a blocking connect, so it is pinned to IO explicitly.** `resolve()` is
        // reachable from `offerManual`, which a settings screen calls from the main thread — and
        // an un-dispatched `Socket.connect` there is a `NetworkOnMainThreadException` at best and
        // a frozen UI at worst. Inheriting the caller's dispatcher would make that latent.
        val working = withContext(Dispatchers.IO) {
            candidates.firstOrNull { isReachable(it.host, EDGE_PORT) }
        }
        working?.let { return@withLock accept(it) }

        // ⚠️ **The published addresses are tried here — after the gate, before giving up — and not
        // as a fourth rung.** A rung answers "where is the server"; this answers "the answer I had
        // has gone stale", which is a state only the gate can detect. Putting it here is what lets
        // the rungs above stay cheap (§4.4) while still covering the one transition none of them
        // survives: the phone leaving the network whose advert it learned the server on.
        //
        // It is deliberately *not* guarded on `candidates.isEmpty()`. A remembered literal can be
        // the only thing that works when the address rung 3 is still holding has gone dead — and a
        // literal that is already known costs nothing to try when there was nothing else to try.
        targetsFallback(current ?: candidates.firstOrNull())?.let { return@withLock accept(it) }

        if (candidates.isEmpty()) {
            // A walk that found nothing is the one that must not be sticky. Two seconds ago the
            // phone may have been mid-roam with its mDNS cache invalidated; two seconds from now
            // the cache is repopulated and the same browse answers immediately. Retry before
            // telling anyone to type an address by hand.
            //
            // ⚠️ **It is armed here, after the gate and after the published names, and that
            // placement is the point.** This block used to sit *before* both — so it retried walks
            // the gate was about to resolve anyway. A retry is only owed once everything that
            // could have answered has been asked, which is exactly here.
            scheduleMissRetry()
            return@withLock publish(
                OverlayEndpointStatus.NeedsManual(
                    "No boomio server was found — tried mDNS, $PROV_RECORD and any saved " +
                        "address. Enter the server's address to continue.",
                ),
            )
        }

        // ⚠️ **Nothing answered the gate, so the first candidate is taken anyway** — and this is
        // the one place the ladder deliberately overrules its own probe. It is the same call
        // `OverlayLocalDiscovery` makes, and it learned it the hard way: a liveness probe once
        // timed out on a LAN the app's own client was reaching in 2.6 ms, and because the
        // candidate was *discarded* rather than merely deprioritised, discovery went silently
        // dead everywhere. The gate's job is to choose between candidates; it has no business
        // throwing the last one away.
        val fallback = candidates.first()
        Log.w(TAG, "No candidate answered the gate on $EDGE_PORT; taking ${fallback.authority} anyway")
        accept(fallback)
    }

    // ---------------------------------------------------------------------------------------
    // Rungs
    // ---------------------------------------------------------------------------------------

    /**
     * Rung 1 — the mDNS advert, borrowed from the browse that already ran.
     *
     * ⚠️ **It *drives* the browse rather than reading a cache.** `OverlayLocalDiscovery.refresh()`
     * is mutex-guarded and idempotent, and calling it here is what makes rung 1 honest: the
     * ladder never stops on an advert that the current network has since invalidated, and it
     * never opens a *second* browse on the same service, which would double both the multicast
     * traffic and the six-second window for one answer.
     *
     * On a network that is not Wi-Fi or Ethernet the browse returns immediately ("Not on Wi-Fi or
     * Ethernet") and the advert is cleared, so this costs nothing off-LAN — which is the case
     * where rung 2 is the one that matters.
     */
    private suspend fun rung1Mdns(): OverlayEndpoint? {
        // [RUNG1_BUDGET_MS], passed *in* rather than the wait being timed out around it: a
        // timeout here would cancel the browse, and the browse is the only thing that will ever
        // place the LAN pin. The constant happens to equal the browse's own default window, but
        // it is still passed explicitly — the two answer to different owners, and the ladder's
        // bound should not silently follow the pin's if the pin's ever moves.
        OverlayLocalDiscovery.refresh(windowMs = RUNG1_BUDGET_MS)
        // ⚠️ **Read from the browse directly, because [lastVerifiedAdvert] is not enough
        // here.** That advert is only published when the browse resolved a *usable* address, so a
        // platform whose NSD answers with a link-local IPv6 and no `A` produces none at all -- and
        // that is precisely the device this tier has to rescue. The targets survived the browse;
        // they are read here.
        OverlayLocalDiscovery.advertTargets()?.let { rememberTargets(it) }
        val advert = OverlayLocalDiscovery.lastVerifiedAdvert() ?: return null
        // Recorded before the key is judged. The targets are a fact about the *server*, published
        // by the same advert, and an advert whose key is unusable is still a server whose
        // addresses are worth knowing -- the ladder may yet have to find it on another network.
        rememberTargets(advert.tuple.targets)
        val key = validServerKeyOrNull(advert.tuple.serverPublicKeyBase64)
        if (key == null) {
            Log.d(TAG, "Rung 1: advert has no usable pubkey; falling through")
            return null
        }
        return endpointOf(
            address = advert.address,
            port = advert.tuple.port ?: DEFAULT_WG_PORT,
            key = key,
            source = OverlayEndpointSource.MDNS,
        )
    }

    /**
     * Rung 2 — the discovery record, [PROV_RECORD].
     *
     * ⚠️ **A record with no `TXT` is not an endpoint, and is not treated as one.** An address
     * without the server's public key cannot produce a handshake, so accepting it would stop the
     * ladder one rung early on something that cannot work — the precise failure §4.4 warns about
     * ("the third record needs the tuple, not just the address"). Falling through to rung 3 is
     * both safer and more honest: rung 3 carries a key by construction.
     *
     * ⚠️ **This is the rung that matters off the property, and it is why the record exists.** Its
     * `A` follows the server's *public* address, so away from home it answers with something
     * dialable — which the service name deliberately does not, since that one points at the
     * private address so a LAN browser can reach management. On the LAN the `A` resolves to the
     * public address and the gate rejects it, which is correct and costs one probe: rung 1 has
     * usually already answered there, and the targets this record carries give the literal tier
     * the private address to fall back to.
     */
    private suspend fun rung2DiscoveryRecord(context: Context): OverlayEndpoint? {
        val resolved = OverlayDnsClient.resolve(context, PROV_RECORD, RUNG2_BUDGET_MS) ?: return null
        // Same reasoning as rung 1: recorded whether or not this record's key is usable, because
        // the targets are a fact about the server rather than about this particular answer.
        rememberTargets(resolved.tuple.targets)
        val key = validServerKeyOrNull(resolved.tuple.serverPublicKeyBase64)
        if (key == null) {
            Log.d(TAG, "Rung 2: '$PROV_RECORD' resolved but published no usable key")
            return null
        }
        return endpointOf(
            address = resolved.address,
            port = resolved.tuple.port ?: DEFAULT_WG_PORT,
            key = key,
            source = OverlayEndpointSource.LOCAL_DNS,
        )
    }

    /**
     * Rung 3 — a person.
     *
     * One rung, two sources, because they are one fact: `BoomioConfig.overlayEndpoint` is where a
     * typed value is *persisted*, and [offerManual] is how it gets there. Splitting "typed just
     * now" from "typed last week" would produce two states with identical behaviour.
     *
     * ⚠️ **The host is resolved to a literal here, before it is published.** The value goes to
     * `OverlayWgTunnel.up`, which hands it to `IpcSet`'s `endpoint=` verbatim — and whether
     * wireguard-go's own resolver works inside a gomobile AAR on Android (Go's resolver looks for
     * an `/etc/resolv.conf` that does not exist there) is unverified. Resolving on this side
     * removes the question rather than betting on the answer, and it means the endpoint the
     * tunnel sees is the same one the gate just proved reachable.
     *
     * ⚠️ **A typed *name* is read for its `TXT`, not merely resolved, and that is what makes the
     * typed discovery name enough on its own.** The case this rung exists for is a device that has
     * never read a publication — a first-ever launch away from home, or a manual entry standing in
     * for everything else — so the record is precisely what is missing. Resolving the name alone
     * would supply the tunnel's endpoint and nothing else: the service name would still resolve
     * publicly to the server's **private** address, so with the tunnel not yet up every HTTP
     * request would fail, and with it up the pins would be the only thing missing from an
     * otherwise working client. Reading the record here lands the targets through the same funnel
     * rungs 1 and 2 use ([rememberTargets]), so where the server is and who it is arrive together
     * however the client found out.
     *
     * ⚠️ **The record's own `port=` wins over the parsed default, deliberately, so this rung
     * agrees with rung 2.** Rung 3 exists to reach the same place as the automatic rungs; a typed
     * name carries no port of its own in the common case, and taking the record's here is what
     * keeps the endpoint this rung hands the tunnel identical to the one rung 2 would have.
     */
    private suspend fun rung3Manual(context: Context): OverlayEndpoint? {
        val raw = BoomioConfig.overlayEndpoint.trim()
        if (raw.isEmpty()) return null
        val key = validServerKeyOrNull(BoomioConfig.overlayServerPubKey)
        if (key == null) {
            Log.d(TAG, "Rung 3: an endpoint is configured but no usable server key is")
            return null
        }
        val authority = parseEndpointAuthority(raw, DEFAULT_WG_PORT) ?: return null
        val (typedHost, typedPort) = authority

        // A *literal* never comes through here: it is dialled as written, which is the whole point
        // of the no-LAN-DNS case, and asking a resolver about it would be the one DNS call this
        // rung must not make. Only a name is worth a lookup, and only a name can carry a record.
        if (literalOrNull(typedHost) == null) {
            OverlayDnsClient.resolve(context, typedHost, MANUAL_RESOLVE_BUDGET_MS)?.let { resolved ->
                rememberTargets(resolved.tuple.targets)
                val literal = resolved.address.hostAddress ?: return@let
                return OverlayEndpoint(
                    host = literal,
                    port = resolved.tuple.port ?: typedPort,
                    serverPublicKeyBase64 = key,
                    source = OverlayEndpointSource.MANUAL,
                )
            }
        }

        val address = resolveHost(typedHost, MANUAL_RESOLVE_BUDGET_MS) ?: return null
        return OverlayEndpoint(
            host = address.hostAddress ?: return null,
            port = typedPort,
            serverPublicKeyBase64 = key,
            source = OverlayEndpointSource.MANUAL,
        )
    }

    // ---------------------------------------------------------------------------------------
    // The published addresses -- the fallback that survives leaving the network
    // ---------------------------------------------------------------------------------------

    /**
     * Remembers the dial targets a publication carried, if it carried any.
     *
     * ⚠️ **The set is replaced wholesale; a partial publication is not merged.** Both channels
     * publish the whole tuple from one server at one instant, so a record carrying only `lan=` is
     * the server's *current* truth — a `wan=` that has been withdrawn — rather than a half-heard
     * message. Merging field by field would keep a withdrawn address alive for the life of the
     * process, which is the stale-discovery failure this whole subsystem exists to prevent.
     *
     * Values arrive already validated: both parsers run them through [validDottedQuadOrNull], so a
     * blank, malformed or *v1-style hostname* field is `null` by the time it reaches here — which
     * is what makes a record from the previous era read as "no addresses published" rather than as
     * two names to go and resolve.
     *
     * ⚠️ **This is also where the service pin is placed, and this is the only place it can be.**
     * The tuple arrives on *both* channels — mDNS from the browse, the TXT from a public lookup —
     * and this method is the one funnel they share. See [applyServicePins] for what it does with
     * them and why the pin is load-bearing rather than a nicety.
     */
    private fun rememberTargets(targets: OverlayDiscoveryTargets) {
        if (targets.isEmpty) return
        if (targets == discoveryTargets) return
        Log.i(TAG, "Discovery targets learned: svc=${targets.svcName ?: "-"} " +
            targets.inOrder().joinToString { "${it.host}:${it.tunnelPort}" })
        discoveryTargets = targets
        applyServicePins(targets)
    }

    /**
     * Points the **service name** at the address this record says serves it from here.
     *
     * ⚠️ **The `wan` pin is what makes the whole design work off the property, and without it the
     * app simply cannot reach the HTTP plane away from home.** The service name's public `A` record
     * deliberately holds the server's **private** address — that is the owner's choice, and it is
     * what lets a LAN browser reach the management surface under a name a certificate can be issued
     * for. The cost is that off-LAN the name resolves to something undialable, and nothing in DNS
     * will correct it. The record's `wan` literal is the correction, and a pin is the only
     * mechanism that can apply one: a certificate is issued per *name*, so the address may change
     * and the name must not.
     *
     * ⚠️ **The `lan` literal is pinned too, and it is not redundant.** At home the public `A`
     * usually already answers with the same private address, so the pin changes nothing there —
     * but "usually" is doing work: the publisher rewrites that record every five minutes, and a
     * client mid-roam can hold a resolver answer from before a change. Pinning both literals means
     * the two arms the record publishes are exactly the two the dialler tries, with nothing left to
     * DNS in between. Both are suppressed together while the tunnel carries traffic — see
     * [OverlayPinRegistry.isSuppressed].
     *
     * ⚠️ **Replacing the pin on every publication is the point, not a leak.** A record with a new
     * `wan` means the public address moved; the old pin is then a stale address, and the whole
     * reason the publisher runs on a timer is to have the client notice. Same-source replacement
     * keeps exactly one `LAN` and one `WAN` pin, and [OverlayPinRegistry.pin] is copy-on-write so a
     * concurrent lookup sees one snapshot or the other, never a half-updated pair.
     */
    private fun applyServicePins(targets: OverlayDiscoveryTargets) {
        // The host set is re-derived rather than passed in: the addon catalogue — where most of the
        // app's server hosts live — loads asynchronously, so this call returns a *larger* set later
        // in the session. The service name is added explicitly because it is the one host the
        // record itself names, and at a cold start it is the only one that matters.
        val svc = targets.svcName
        if (svc != null) BoomioConfig.serviceOrigin = "https://$svc"
        val hosts = localServerHosts() + listOfNotNull(svc)
        if (hosts.isEmpty()) return
        for (plane in targets.inOrder()) {
            // [literalOrNull] rather than `InetAddress.getByName` directly: same-package helper, and
            // it is regex-gated so a malformed field can never become a resolver call on the
            // discovery path. The parser already validated these, so this is belt and braces.
            val address = literalOrNull(plane.host) ?: continue
            OverlayPinRegistry.pin(
                source = if (plane.isLan) LocalServerSource.LAN else LocalServerSource.WAN,
                hosts = hosts,
                address = address,
            )
        }
    }

    /**
     * The last resort before the ladder gives up: dial the addresses the server published.
     *
     * ⚠️ **This runs only when every rung has missed the gate**, and that placement is the whole
     * design. Reading the record eagerly would multiply the cold path's cost by the number of
     * misses and it is not needed on the path where a rung already answered. Here it costs nothing
     * when the ladder worked, and buys the one case the rungs cannot cover: the phone has left the
     * network whose advert taught it where the server was.
     *
     * ⚠️ **No DNS happens in this tier, and that is the point of the v2 record.** `lan=`/`wan=` are
     * addresses now, so the walk is over literals: nothing resolves, nothing can be captured by a
     * poisoned resolver, and the "no LAN DNS" constraint is satisfied by construction rather than
     * by a fallback. The names this replaced needed one resolve each and could not work at all on a
     * network without a resolver.
     *
     * ⚠️ **An address supplies only the host; the key and the port are reused from [seed].** That
     * is not a shortcut but the correct reading — the tuple published beside `lan=`/`wan=` belongs
     * to the same server. If that server's key had changed, the handshake would fail and the next
     * publication would correct it, which is the honest outcome; inventing a key here would hide
     * it. ⚠️ **The seed's port wins over the plane's `lanport=`/`wanport=` deliberately**: this
     * tier hands back an [OverlayEndpoint], which is a *tunnel* endpoint, and the per-plane ports
     * are the same number reached by a different route. Reading the plane's instead would make the
     * tier's answer disagree with every other rung's for no benefit.
     *
     * ⚠️ **A null [seed] makes this a no-op, deliberately.** With no endpoint anywhere there is no
     * key to dial with, so there is nothing to try — and a first-ever launch away from home has no
     * publication to climb *from* either. That launch belongs to rung 3, and
     * [OverlayEndpointStatus.NeedsManual] is the right answer to it.
     *
     * ⚠️ **The seed is `current ?: candidates.firstOrNull()`, and the candidate half is a bug
     * fix.** It used to be `current` alone, which is process-scoped and therefore null until
     * something has been accepted — so on a **cold launch** the tier could not run at all. That is
     * the walk it is most needed on: rung 3 is holding an address learned on some earlier network,
     * the gate is about to reject it, and the publication is the only thing that can still find the
     * server. Seeding from the candidate costs nothing (it is the same server's key and port) and
     * closes that window.
     *
     * The gate still decides whenever it can: a plane that answers on [EDGE_PORT] wins outright,
     * whatever its position. What it cannot decide is the case this tier now exists to survive —
     * with the WAN forward closed, which is the in-app tunnel's whole point since it needs nothing
     * but UDP 51820, *no* plane answers, and a tier that insisted would come back empty at exactly
     * the moment it is the only thing left to try. So when nothing proves itself the **first plane
     * in order** is taken, which is the LAN literal — the owner's stated order ("tries the lan ip
     * first, then the wan"), and a private address on a foreign network is refused immediately
     * rather than after a timeout, so taking it there costs one fast failure and no delay.
     *
     * The handshake remains the only real verdict, as the class doc says; a plane that turns out to
     * be wrong fails visibly and is corrected by the next walk, which is the trade §10.7 chose over
     * a silent fallback (see `onNetworkChanged`). The cost is the one ordering can never remove:
     * the gate proves the *address* is plausible, never that the forward is open.
     */
    private suspend fun targetsFallback(seed: OverlayEndpoint?): OverlayEndpoint? {
        val targets = discoveryTargets ?: return null
        val known = seed ?: return null
        val key = validServerKeyOrNull(known.serverPublicKeyBase64) ?: return null
        // Already in dial order — LAN then WAN. See [OverlayDiscoveryTargets.inOrder].
        val attempt = targets.inOrder()
        if (attempt.isEmpty()) return null

        Log.i(
            TAG,
            "Every rung missed the gate; trying published addresses: " +
                attempt.joinToString { "${it.host}:${it.tunnelPort}" },
        )

        val startedAt = SystemClock.elapsedRealtime()

        // ⚠️ **The fallback is the *first* plane, not the last that answered.** The order is the
        // owner's — LAN then WAN — and letting a later plane overwrite an earlier one would swap the
        // LAN literal (correct at home, refused instantly away from home) for the public address
        // whenever the WAN forward happened to be open, which is the opposite of the stated order.
        // First-wins is also what makes it race-free: it is set on the earliest iteration, so a walk
        // cut short by [TARGET_TIER_BUDGET_MS] still leaves a usable endpoint behind rather than an
        // empty tier.
        var unproven: OverlayEndpoint? = null

        val proven = withContext(Dispatchers.IO) {
            withTimeoutOrNull(TARGET_TIER_BUDGET_MS) {
                for (plane in attempt) {
                    // A validated dotted quad, so this constructs an InetAddress and never resolves.
                    val address = literalOrNull(plane.host) ?: continue
                    val candidate = endpointOf(
                        address = address,
                        port = known.port,
                        key = key,
                        source = OverlayEndpointSource.DISCOVERY_RECORD,
                    ) ?: continue
                    if (isReachable(candidate.host, EDGE_PORT)) return@withTimeoutOrNull candidate
                    if (unproven == null) unproven = candidate
                }
                null
            }
        }

        // Kept loud, and split by which thing failed: "nothing was published" and "addresses were
        // published but nothing answered" are different faults, and the tier is quiet enough that
        // this line is the only place either one shows up.
        if (proven == null) {
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val tried = attempt.joinToString { "${it.host}:${it.tunnelPort}" }
            if (unproven == null) {
                Log.w(TAG, "No usable published address ($tried) after ${elapsedMs}ms")
            } else {
                Log.w(
                    TAG,
                    "No published address answered the gate on $EDGE_PORT ($tried) after " +
                        "${elapsedMs}ms; taking ${unproven!!.authority} anyway",
                )
            }
        }
        return proven ?: unproven
    }

    // ---------------------------------------------------------------------------------------
    // The gate, and what happens to a winner
    // ---------------------------------------------------------------------------------------

    /**
     * Builds a candidate from an address the rung already resolved.
     *
     * Deliberately **does not gate** — the gate is [resolve]'s, applied across all candidates at
     * once so it can choose between them. A rung that gated its own candidate could only ever
     * discard it, which is the mistake the browse already made once.
     *
     * ⚠️ **No resolution happens here**: rungs 1 and 2 hold an [InetAddress] already, and
     * re-resolving a literal on the cold path would be a syscall for nothing.
     */
    private fun endpointOf(
        address: InetAddress,
        port: Int,
        key: String,
        source: OverlayEndpointSource,
    ): OverlayEndpoint? = OverlayEndpoint(
        host = address.hostAddress ?: return null,
        port = port,
        serverPublicKeyBase64 = key,
        source = source,
    )

    private fun accept(endpoint: OverlayEndpoint): OverlayEndpointStatus {
        current = endpoint
        // ⚠️ **Written through to `BoomioConfig`, which is the documented end state** — "in the
        // end state this is *learned*, not configured". The probe and U3's bring-up both read
        // these fields, so a ladder that only published a flow would leave them on the
        // build-time value and the tunnel would dial the wrong address while the status said
        // otherwise. `BoomioConfig` is runtime state, not persisted, so nothing here survives a
        // process death — which is right: the network may have changed by then.
        BoomioConfig.overlayEndpoint = endpoint.authority
        endpoint.serverPublicKeyBase64?.let { BoomioConfig.overlayServerPubKey = it }

        Log.i(TAG, "Endpoint accepted from ${endpoint.source}: ${endpoint.authority}")

        // ⚠️ **The TTL is armed here, on success only.** This is the stamp that used to sit at the
        // top of [resolve]: moving it here is what makes a miss retryable while a *hit* still
        // costs nothing until the TTL expires or the network changes.
        //
        // ⚠️ This deliberately does **not** clear `missRetryPending`. A retry chain notices the
        // success for itself (`current != null`) and exits through its own `finally`; clearing the
        // latch from here as well would open a window in which a concurrent miss starts a second
        // chain beside the first.
        lastResolvedAtMs = SystemClock.elapsedRealtime()
        return publish(OverlayEndpointStatus.Found(endpoint))
    }

    /**
     * Walks the ladder again after a bounded delay, because a walk that found nothing is the one
     * outcome that must not be sticky.
     *
     * ⚠️ **Guarded by [missRetryPending], and the guard is not tidiness.** `onNetworkChanged`
     * fires on every reassociation, so a phone wandering a mesh would otherwise start a fresh
     * three-attempt chain per roam and browse continuously — turning a discovery fix into a
     * battery bug. One chain at a time; the chain is what carries the attempt count.
     *
     * This is the other end of the failure [resolve] already names — "discovery goes silently dead
     * on the LAN" — reached from the opposite direction: not a cancelled browse, but a browse that
     * ran to completion, found nothing, and was then never allowed to run again.
     */
    private fun scheduleMissRetry() {
        if (missRetryPending) return
        missRetryPending = true
        scope.launch {
            try {
                var left = MISS_RETRY_ATTEMPTS
                while (left > 0) {
                    delay(MISS_RETRY_MS)
                    // A later walk may have succeeded while this one waited, in which case there
                    // is nothing left to chase. `current` is the right test rather than the TTL:
                    // `onNetworkChanged` zeroes the clock, so the clock says nothing about
                    // whether an endpoint is held.
                    if (current != null) return@launch
                    val status = resolve()
                    // ⚠️ `resolve()` calls this function again on a miss. The latch is what makes
                    // that call a no-op, so the *loop* owns the attempt count and the recursion
                    // cannot fork into three chains. Without it a phone on a mesh would browse
                    // continuously, one chain per roam.
                    if (status is OverlayEndpointStatus.Found) return@launch
                    left--
                }
            } finally {
                missRetryPending = false
                // ⚠️ **The prompt retries are spent, so put the foreground gate back to sleep.**
                // Without this, leaving the clock at zero means *every* foreground event pays a
                // full browse — and on a Wi-Fi network with no boomio server on it, that is a
                // six-second multicast browse each time the app is raised, forever. The chain
                // above is what a miss gets instead of silence; the ordinary TTL is what a
                // serverless network gets instead of a hot loop. `onNetworkChanged` still zeroes
                // the clock, so the roam case — the one this whole function exists for — retries
                // immediately, which is the difference that matters.
                if (current == null) lastResolvedAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun publish(status: OverlayEndpointStatus): OverlayEndpointStatus {
        OverlayEndpointState.update(status)
        return status
    }

    // ---------------------------------------------------------------------------------------
    // Rung 3's input
    // ---------------------------------------------------------------------------------------

    /**
     * Records a user-supplied endpoint and re-runs the ladder.
     *
     * Returns the resulting status so the caller can render it directly: a typed address that
     * the **gate** cannot reach still becomes `Found`, because preference-not-veto applies to a
     * human's answer too — they may know something the probe cannot see (a server behind a
     * forward that is briefly down, a name that only resolves on their network). Only a value
     * that is not an endpoint at all is rejected, and it returns `NeedsManual` with the reason
     * rather than throwing.
     */
    suspend fun offerManual(rawAuthority: String, serverPublicKeyBase64: String?): OverlayEndpointStatus {
        val authority = parseEndpointAuthority(rawAuthority, DEFAULT_WG_PORT)
        if (authority == null) {
            return publish(
                OverlayEndpointStatus.NeedsManual(
                    "\"${rawAuthority.trim()}\" is not an address — expected a host, or host:port.",
                ),
            )
        }
        val key = validServerKeyOrNull(serverPublicKeyBase64)
            ?: validServerKeyOrNull(BoomioConfig.overlayServerPubKey)
        if (key == null) {
            // The server's key is public and is published by both channels; it is the one field
            // a user cannot invent, so its absence is named rather than guessed at.
            return publish(
                OverlayEndpointStatus.NeedsManual(
                    "The server's public key is needed as well — it is published by the server's " +
                        "mDNS advert and its DuckDNS TXT record.",
                ),
            )
        }
        BoomioConfig.overlayEndpoint = rawAuthority.trim()
        BoomioConfig.overlayServerPubKey = key
        return resolve()
    }
}

/**
 * Runs every rung at once and returns the answers **in rung order**, nulls dropped.
 *
 * §3 asks the ladder to "race mDNS against name resolution and take the first answer". This is
 * the race; the *taking* is [OverlayEndpointDiscovery.resolve]'s gate, and it is deliberately not
 * done here — see the comment at the call site for why taking the first answer as it arrives
 * would be wrong.
 *
 * **Concurrency here is worth its weight in exactly one place, and it is not throughput.** Each
 * rung carries its own budget ([OverlayEndpointDiscovery]'s `RUNG1_BUDGET_MS` and
 * `RUNG2_BUDGET_MS`), so walked in sequence the ladder costs the *sum* of them — on a Wi-Fi
 * network with no boomio advert on it, six‑plus seconds of the first-run screen saying nothing.
 * Raced, it costs the *maximum*.
 *
 * ⚠️ **Awaiting in rung order, not in completion order**, so the list the gate sees is the same
 * list a sequential walk produced and the gate's preference between rungs is unchanged. A rung
 * that is slower therefore delays the list, never the ordering — which is the whole of the
 * difference between this and the loop it replaces.
 *
 * ⚠️ **Nothing is cancelled when a rung returns null.** A rung returning null is an answer
 * ("nothing here"), not a failure, and the other rung is still the one that matters off-LAN.
 */
internal suspend fun <T : Any> raceRungs(
    vararg rungs: suspend () -> T?,
): List<T> = coroutineScope {
    rungs.map { rung -> async { rung() } }.awaitAll().filterNotNull()
}

/**
 * `host:port` → its parts, or null if [raw] is not an address at all.
 *
 * Deliberately forgiving about the shapes a person actually pastes — a scheme, a trailing slash,
 * a path, brackets round an IPv6 literal — and unforgiving about anything that is not an
 * address. A default is supplied for the port because "the server's address" is how the question
 * will be asked, and the WireGuard port is a constant of this deployment.
 *
 * ⚠️ **Two or more colons and no brackets is read as an address, not as `host:port`.** `::1:51820`
 * is a valid IPv6 address *and* a plausible IPv4-style address-plus-port, and splitting on that
 * colon would send the tunnel to a host the user never named. Taking it whole and leaving the
 * port at the default is the reading that cannot be wrong about *which host*; the port was always
 * going to be the default anyway, since this deployment has one.
 *
 * **A saved value the ladder itself wrote reads back as rung 3.** [accept] publishes a discovered
 * endpoint into `BoomioConfig`, which is what rung 3 reads, so the next run can present last
 * run's answer as a manual one. That is accurate in the only sense that matters — it *is* the
 * configured address now — and it is what makes the rung useful when discovery is transiently
 * down.
 */
internal fun parseEndpointAuthority(raw: String, defaultPort: Int): Pair<String, Int>? {
    var text = raw.trim()
    if (text.isEmpty()) return null

    // A pasted URL is the common case, and the scheme is noise here.
    val scheme = text.indexOf("://")
    if (scheme > 0) text = text.substring(scheme + 3)
    text = text.substringBefore('/').trim()
    if (text.isEmpty()) return null

    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close <= 1) return null
        val host = text.substring(1, close)
        val rest = text.substring(close + 1)
        val port = if (rest.startsWith(":")) rest.substring(1).toPortOrNull() ?: return null else defaultPort
        return host to port
    }

    val colons = text.count { it == ':' }
    return when (colons) {
        0 -> text to defaultPort
        1 -> {
            val host = text.substringBefore(':')
            val port = text.substringAfter(':').toPortOrNull() ?: return null
            if (host.isEmpty()) null else host to port
        }
        // Two or more colons and no brackets: an unbracketed IPv6 literal. Port stays default.
        else -> text to defaultPort
    }
}

/**
 * A literal address for [host], or null.
 *
 * Blocks; callers run it off the main thread and inside a timeout. A value that is already a
 * literal returns without a resolver round trip — which is the case for everything rungs 1 and 2
 * produce, so this is only ever really exercised by a person typing a name.
 */
internal suspend fun resolveHost(host: String, budgetMs: Long): InetAddress? =
    withTimeoutOrNull(budgetMs) {
        withContext(Dispatchers.IO) {
            runCatching { InetAddress.getByName(host) }
                .onFailure { Log.d(TAG, "'$host' did not resolve: ${it.message}") }
                .getOrNull()
        }
    }

/**
 * True when a TCP connect to [host]:[port] completes inside [GATE_TIMEOUT_MS].
 *
 * `internal` rather than `private` so `OverlayProvisioning` can apply the *same* gate while it
 * walks the published names. That walk needs it for a reason the ladder does not have to face: a
 * provisioning dialler gets exactly one attempt at whichever address it picks, so "the name
 * resolved" is not good enough — the address has to answer. See [OverlayProvisioning.target].
 */
internal fun isReachable(host: String, port: Int): Boolean {
    val startedAt = SystemClock.elapsedRealtime()
    return runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), GATE_TIMEOUT_MS)
            true
        }
    }.onFailure {
        Log.w(
            TAG,
            "Gate probe to $host:$port failed after ${SystemClock.elapsedRealtime() - startedAt}ms",
            it,
        )
    }.getOrDefault(false)
}
