package com.nuvio.app.core.overlay

import android.content.Context
import android.util.Log
import com.nuvio.app.features.boomio.BoomioPairingResult
import com.nuvio.app.features.boomio.BoomioPairingTransport
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.core.overlay.OverlayEndpointDiscovery.PROV_RECORD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "OverlayProvisioning"

/** The server's own poll hint is 3 s; this is the fallback for a reply that omits one. */
private const val DEFAULT_POLL_MS = PROVISION_DEFAULT_POLL_MS

/**
 * 60 attempts, matching `BoomioSessionRepository`'s own bound.
 *
 * The server's pair TTL is 300 s and its poll TTL 360 s, so a code stays pollable for longer than
 * this; the bound is the device's patience, not the server's.
 */
private const val MAX_POLLS = 60

/**
 * Where a device with **no session and no address** reaches the server: the provisioning
 * channel, wired into the two places that need it.
 *
 * ### The two halves, and why they are one object
 *
 * The channel carries four operations, and this app splits them across two seams that already
 * existed:
 *
 * | Operation | Reaches the app through |
 * |---|---|
 * | `pair.request` / `pair.poll` | [BoomioSessionRepository.pairingTransport] — the link flow |
 * | `enroll` / `enroll.status` | [OverlayEnrollment]'s api — the assignment |
 *
 * ⚠️ **They are one object because the second half depends on what the first one learns.** The
 * session token authorises enrollment, so it cannot run until pairing has; and
 * [linkedOverChannel] is how the enrollment half knows *which* transport to use, which is a fact
 * only the pairing half can observe. Split into two objects, a third would have to own that one
 * flag and both halves would reach for it.
 *
 * ### Why the enrollment half has to follow the pairing half at all
 *
 * `POST /api/overlay/enroll` is dialled at `BoomioConfig.companionRestBaseUrl`, which off-LAN
 * resolves to the box's **LAN** address — so a device that needed the channel to pair would
 * reach for the very address it just proved it cannot dial, and would come away with a session
 * and no tunnel. Whichever transport linked the device is therefore the transport that enrolls
 * it, and that is all [linkedOverChannel] records.
 *
 * ⚠️ **It is a per-process flag and that is enough, because the assignment is cached on disk.**
 * A later launch holding the same token — off-LAN, no link in this process — resolves the base
 * URL to the LAN address and fails, but [OverlayEnrollment.initialize] has already applied the
 * saved assignment *before* any network call, and `refresh()` deliberately keeps it when a
 * refresh fails. So the device holds a working tunnel and only the refresh misses. Persisting
 * the flag would buy a cheaper refresh, not a working one.
 *
 * See `docs/vpn-overlay-provisioning-ingress.md` §6 for the protocol and §7 for the two trust
 * checks this half performs.
 */
internal object OverlayProvisioning {

    /**
     * The discovery record's budget.
     *
     * Rung 2's number, for rung 2's reason: this lookup sits in front of a screen a person is
     * waiting on, and a miss has to be cheap. The record it wants is the one
     * `OverlayEndpointDiscovery` rung 2 already reads, so it is one name and one `TXT` query
     * rather than a walk.
     */
    private const val RESOLVE_BUDGET_MS = 1_500L

    /**
     * How long the **pairing** connect may take — deliberately not
     * `OverlayProvisionConnection`'s ten seconds.
     *
     * ⚠️ **A candidate that is not on this network is the case this bounds, and it has two
     * shapes.** Off the property the record's `lan=` is a private address: the kernel usually
     * refuses it outright, but on some networks it is instead routed to a default gateway that
     * silently drops it, and a refused connect is the only fast one. At home the reverse can bite —
     * the public `wan=` is unreachable because hairpin is off on this network, and a connect from
     * inside the LAN to the public address times out rather than being refused. Ten seconds of a
     * first-run screen before falling back to the transport that works is a regression against the
     * flow this one sits beside; three seconds is a connect that has plainly failed.
     *
     * Enrollment keeps the default, because it dials an address the device has already proven
     * reachable — a long deadline costs nothing there and a short one would fail a slow network.
     */
    internal const val PAIR_CONNECT_BUDGET_MS = 3_000

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    /**
     * True once a link has been established over this channel *in this process*.
     *
     * ⚠️ **Written by the pairing half and read by the enrollment half**, and the direction
     * matters: it says "the transport that worked here is the channel", which is what makes
     * enrollment follow it instead of reaching for a base URL this network cannot resolve.
     */
    @Volatile
    private var linkedOverChannel = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Idempotent, like every other overlay singleton: onCreate can run again after a
        // configuration-forced restart, and a second registration would replace an identical one.
        if (started) return
        started = true
        BoomioSessionRepository.pairingTransport = ChannelPairingTransport()
    }

    // ── the enrollment half's seam ──────────────────────────────────────────────────────────

    /**
     * An enrollment api that speaks over the channel, or **null when the channel is not the
     * transport this network needs**.
     *
     * Returning null is the signal `OverlayEnrollment.apiOrNull()` acts on by falling back to
     * `BscOverlayEnrollmentApi`, so the choice between the two transports is made in one place
     * and the caller needs no branch of its own.
     */
    internal fun enrollmentApi(token: String): OverlayEnrollmentApi? =
        if (linkedOverChannel) ChannelEnrollmentApi(token) else null

    // ── the target, from the discovery record ───────────────────────────────────────────────

    internal fun markLinked() {
        linkedOverChannel = true
    }

    /**
     * Whether the channel is the transport this network needs — the read side of [markLinked].
     *
     * ⚠️ **Read by the mTLS half through `channelRegistrationApiOrNull`**, and the direction is the
     * same one [enrollmentApi] follows: the fact is written here (only the pairing half can observe
     * it) and every *decision* built on it lives with its own caller. This object deliberately
     * returns a fact rather than an api, because `MtlsRegistrationApi` lives in `core.mtls` and
     * constructing one here would make `core.overlay` depend on `core.mtls` — the reverse of the
     * edge that already exists (`MtlsHandshakeWatch` reads `localServerHosts` from this package).
     */
    internal fun isLinkedOverChannel(): Boolean = linkedOverChannel

    /**
     * ⚠️ `internal` rather than `private` so the transports below can take it as a **default
     * argument** — production passes nothing, and a host test passes a target aimed at a loopback
     * socket. Kotlin's `private` binds to the declaration's own body, so a `private` member here
     * would not be reachable from a class in the same file.
     */
    internal suspend fun target(): ProvisionTarget? {
        val context = appContext ?: return null

        // ⚠️ **The walk runs at home too, and home is the case it was built for.** It used to be
        // skipped whenever a verified mDNS advert was present, on the premise that at home "the
        // HTTPS path that always worked" would carry the link. Measured 10-07, it does not.
        // `BoomioSessionRepository` deliberately builds its **own plain client** — pairing mints
        // the token that enrollment presents, so it cannot ride a seam enrollment creates — and a
        // plain client resolves `bsc.tracemonkey.org` through **system DNS**. This Wi-Fi hands out
        // `1.1.1.1` and `8.8.8.8` over DHCP, so the name resolved to the **public**
        // `153.68.210.49`, hairpin is off, and pairing died on a 10 s connect timeout:
        // `BoomioLinkFailure.Start`, the "Couldn't start connecting" the owner saw — with the box
        // answering fine on `192.168.68.65:443` *and* `:51820` two hops away. The pin covers every
        // other client on the LAN; that one call cannot use it by design, which is exactly the
        // hole this channel exists to fill.
        //
        // So being at home is not a reason to skip the walk. `lan=` is a **private literal
        // published in a public TXT**, so it is readable from anywhere — including through
        // `1.1.1.1` — and the gate below accepts it exactly when the box really is on this
        // segment. Nothing on this path needs system DNS at all.
        //
        // ⚠️ **One record, two addresses, and off-LAN the second is the one that routes.** The v2
        // record names `lan=` and `wan=` itself, so a single lookup of [PROV_RECORD] supplies the
        // whole walk. That is why a rejection below means "try the next address" and never "give
        // up", and why only a set that all fails ends the search — the LAN literal is readable
        // everywhere but routable in one place, and stopping at it would hand the dialler an
        // address nothing off-LAN can reach, which is precisely the failure this channel exists to
        // remove.
        //
        // ⚠️ **"Resolves" is not the test — "answers" is.** The record is public, so it resolves
        // from *any* network; only the socket probe distinguishes the segment the box is on from
        // every other one. Without it the walk would stop on its first candidate everywhere. The
        // dialler gets exactly one attempt at whatever this returns (`ChannelPairingTransport`
        // dials the target it is handed, once, and falls back to HTTPS rather than retrying), so
        // the probe is the only thing standing between a roam and a spent attempt.

        val resolved = OverlayDnsClient.resolve(context, PROV_RECORD, RESOLVE_BUDGET_MS) ?: run {
            Log.i(TAG, "'$PROV_RECORD' did not resolve")
            return null
        }
        val tuple = resolved.tuple

        // ⚠️ Only an explicit `prov=1` opens the door — the same fail-closed reading the server
        // applies to its own flag, so a silent record and a closed server agree.
        if (!tuple.offersProvisioning) {
            Log.i(TAG, "'$PROV_RECORD' does not offer provisioning (`prov` is not 1)")
            return null
        }
        val ppk = tuple.provisioningPublicKeyBase64
        if (ppk == null) {
            Log.w(TAG, "'$PROV_RECORD' offers provisioning but publishes no usable ppk; declining")
            return null
        }

        // ⚠️ **The addresses come from the tuple, not from the record's own `A`.** That is the v2
        // contract: the two literals are named explicitly so no client has to resolve them, and the
        // per-plane `lanpport=`/`wanpport=` travel with them so neither plane's port is inferred
        // from a global default. The record's `A` is kept as a *fallback only*, for a publication
        // whose tuple named no plane — one attempt is still better than none.
        val planes = tuple.targets.inOrder()
        val candidates: List<Pair<String, Int>> = if (planes.isNotEmpty()) {
            planes.map { it.host to it.provisioningPort }
        } else {
            listOfNotNull(resolved.address.hostAddress?.let { host ->
                host to (tuple.provisioningPort ?: OverlayAdvertTuple.DEFAULT_PORT)
            })
        }
        if (candidates.isEmpty()) {
            Log.w(TAG, "'$PROV_RECORD' published no usable address to dial")
            return null
        }

        for ((host, port) in candidates) {
            // ⚠️ **The gate, and it is the load-bearing line in this walk.** A private address on a
            // foreign network would otherwise win the walk on every network — handing the dialler an
            // address it can never reach, spending the channel's single attempt on it, and leaving
            // the public address permanently untried. Testing that the socket actually opens is what
            // turns "first address named" into "first address that answers", which is the only
            // reading that survives a roam.
            //
            // The cost is bounded and paid only where a candidate cannot work: a private address off
            // the LAN is refused (or times out) rather than hanging, and on the LAN the connect
            // either completes in single-digit milliseconds or the host is not there.
            //
            // Pinned to IO the way the ladder pins its own gate: this is a blocking `Socket.connect`,
            // and although `startLink` reaches here on `Dispatchers.Default` rather than the main
            // thread, `target()` is a seam a caller could reach from anywhere. Inheriting whatever
            // dispatcher that turned out to be would make a frozen UI latent rather than impossible.
            val answered = withContext(Dispatchers.IO) { isReachable(host, port) }
            if (!answered) {
                Log.i(TAG, "$host:$port did not answer; trying the next published address")
                continue
            }
            Log.i(TAG, "Provisioning target from '$PROV_RECORD': $host:$port")
            return ProvisionTarget(host, port, ppk, tuple.serverPublicKeyBase64)
        }

        Log.i(TAG, "No address published by '$PROV_RECORD' answered; pairing falls back to HTTPS")
        return null
    }

    /** `internal` for the same default-argument reason as [target]. */
    internal fun deviceKeypair(): OverlayWgKeypair? =
        runCatching { OverlayWgTunnelController.instance?.keypair() }.getOrNull()
}

/**
 * Where to dial, and the two keys the answer is checked with.
 *
 * ⚠️ **Everything here comes from the discovery record, and the `ppk` is the reason it must.**
 * The address is spoofable, so the channel's whole trust anchor is that a reply which *decrypts*
 * proves the peer holds the private half of the published provisioning key (§7 check 1). A `ppk`
 * is therefore not optional: a record that says `prov=1` without one is a record this client
 * cannot verify, and it declines rather than dialling something it cannot authenticate.
 *
 * [tunnelKeyBase64] is `pk` and is **not** used to dial. It is kept for §7 check 2, which is the
 * check `ppk` cannot make: the handshake proves *who is speaking*, never *what they sent*.
 */
internal data class ProvisionTarget(
    val host: String,
    val port: Int,
    val provisioningKeyBase64: String,
    val tunnelKeyBase64: String?,
)

/**
 * `pair.request` → `pair.poll`, over one sealed connection.
 *
 * ⚠️ **Every failure before a code exists is [BoomioPairingResult.Unavailable], and that is the
 * load-bearing choice in this file.** Nothing has been issued at that point, no human is looking
 * at anything, and the HTTPS flow is the path that always worked — so a server not offering
 * provisioning, an address that does not answer, and a record mid-rollout all mean the same
 * thing: *use the other transport*. Reporting any of them as a failure would turn an optional
 * new path into a regression for every existing install, which is the one thing this wiring must
 * not do.
 *
 * ⚠️ **Every failure after it is [BoomioPairingResult.Failed], also deliberately.** A code has
 * been displayed and the owner may be typing it into bsm right now. Quietly starting the HTTPS
 * exchange behind them would mint a *second* code, and the first approval would land on a
 * request nothing is polling — so the transport that got that far owns the outcome, whatever it
 * turns out to be.
 *
 * The three dependencies are seams, in the shape the rest of this package uses: production
 * passes the defaults, and a host test passes a target pointing at a real loopback socket with
 * the fake AEAD, so the framing, the sequencing and every classification above run for real.
 */
internal class ChannelPairingTransport(
    private val target: suspend () -> ProvisionTarget? = OverlayProvisioning::target,
    private val keypair: () -> OverlayWgKeypair? = OverlayProvisioning::deviceKeypair,
    private val crypto: OverlayProvisionCrypto = GomobileProvisionCrypto,
) : BoomioPairingTransport {

    override suspend fun pair(
        deviceId: String,
        platform: String?,
        name: String?,
        onCode: (userCode: String, verificationUri: String?) -> Unit,
    ): BoomioPairingResult {
        val target = target() ?: return BoomioPairingResult.Unavailable
        val keypair = keypair() ?: return BoomioPairingResult.Unavailable

        val started = try {
            begin(target, keypair, deviceId, platform, name)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Provisioning channel unavailable: ${error.message}")
            null
        } ?: return BoomioPairingResult.Unavailable

        val (connection, code) = started
        try {
            // The code exists from here on, so nothing below may fall through to the other
            // transport — see the class comment.
            onCode(code.userCode, code.verificationUri.takeIf { it.isNotBlank() })
            return awaitApproval(connection, code)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Provisioning poll failed: ${error.message}")
            return BoomioPairingResult.Failed(
                error.message ?: "The provisioning channel dropped before the code was approved.",
            )
        } finally {
            connection.close()
        }
    }

    /**
     * Opens the channel and asks for a code, or null for **any** pre-code disappointment.
     *
     * The connection is closed here on the way out when anything throws, because the caller only
     * takes ownership of a pair that was handed back whole.
     */
    private suspend fun begin(
        target: ProvisionTarget,
        keypair: OverlayWgKeypair,
        deviceId: String,
        platform: String?,
        name: String?,
    ): Pair<OverlayProvisionConnection, ProvisionMessage.PairCode>? {
        var opened: OverlayProvisionConnection? = null
        return try {
            val connection = OverlayProvisionConnection.open(
                host = target.host,
                port = target.port,
                devicePrivateKeyBase64 = keypair.privateKeyBase64,
                devicePublicKeyBase64 = keypair.publicKeyBase64,
                serverPublicKeyBase64 = target.provisioningKeyBase64,
                crypto = crypto,
                connectTimeoutMs = OverlayProvisioning.PAIR_CONNECT_BUDGET_MS,
            )
            opened = connection
            val reply = connection.pairRequest(deviceId, platform, name)
            val code = reply as? ProvisionMessage.PairCode
                ?: throw OverlayProvisionException.ProtocolFault(
                    "pair.request answered '${reply::class.simpleName}'",
                )
            connection to code
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // ⚠️ Logged rather than reported, and the distinction is worth keeping in the log:
            // a `ServerDenied` is the operator's switch, a `HandshakeFailed` is a peer that does
            // not hold `ppk`, and a `TransportFailed` is usually this network having no route to
            // its own WAN address. All three are "not this transport, today" to the flow above —
            // but they are not the same fact, and only this line tells them apart.
            Log.i(TAG, "Not provisioning over the channel: ${error.message}")
            runCatching { opened?.close() }
            null
        }
    }

    private suspend fun awaitApproval(
        connection: OverlayProvisionConnection,
        code: ProvisionMessage.PairCode,
    ): BoomioPairingResult {
        // Clamped the way the HTTPS flow clamps the same hint, and for the same reason: a server
        // asking for a one-second interval would have this device polling it twice as often as
        // it means to, and one asking for a minute would spend the code's whole life asleep.
        val intervalMs = (code.intervalSeconds.takeIf { it > 0 } ?: (DEFAULT_POLL_MS / 1_000))
            .coerceIn(2L, 10L) * 1_000L

        repeat(MAX_POLLS) { attempt ->
            if (attempt > 0) delay(intervalMs)
            when (val polled = connection.pairPoll(code.deviceCode)) {
                is ProvisionMessage.PairOk -> {
                    val token = polled.sessionToken.takeIf { it.isNotBlank() }
                        ?: return BoomioPairingResult.Failed(
                            "The server approved the code but sent no session.",
                        )
                    OverlayProvisioning.markLinked()
                    // ⚠️ `userId` is deliberately null even though the reply also carries
                    // `token`. Those two names hold the same value in the server's `pair.ok` (it
                    // mirrors the HTTPS poll route, whose `token` *is* the session), and the one
                    // field that is genuinely a user identifier in the HTTPS flow — `id` — has no
                    // counterpart on this channel. A value here would be an account id invented
                    // out of a session token.
                    return BoomioPairingResult.Linked(
                        token = token,
                        userId = null,
                        displayName = polled.displayName,
                    )
                }

                // ⚠️ Not "you did not approve it". The server sends this when the code is gone
                // from Redis, which also covers a code that *was* approved and whose session an
                // earlier poll already collected.
                ProvisionMessage.PairExpired -> return BoomioPairingResult.Failed(
                    "The code expired before it was approved. Start again for a new one.",
                )

                ProvisionMessage.PairPending -> Unit

                else -> return BoomioPairingResult.Failed(
                    "The server answered the poll with '${polled::class.simpleName}'.",
                )
            }
        }
        return BoomioPairingResult.Failed("The code was not approved in time.")
    }
}

/**
 * Enrollment over the channel: **one connection per call**.
 *
 * ⚠️ **A fresh connection per call, rather than one held for the whole enrollment.** The server
 * reads the device public key off *that connection's* HELLO, so a connection is a small piece of
 * state that has to be right rather than a session worth keeping — and the poll loop is a
 * sequence of three-second gaps, where a socket the server has since idled out (its own timeout
 * is 30 s) would fail a call that a fresh one answers. Reconnecting costs a TCP connect and an
 * X25519 exchange on a link the device has already proven.
 *
 * ⚠️ **The target is resolved once per instance, not once per call**, because the loop makes up
 * to twelve calls and a DNS lookup in front of each would cost more than the calls do. One
 * instance covers one `OverlayEnroller.enroll` — one whole cycle.
 */
internal class ChannelEnrollmentApi(
    private val token: String,
    private val target: suspend () -> ProvisionTarget? = OverlayProvisioning::target,
    private val keypair: () -> OverlayWgKeypair? = OverlayProvisioning::deviceKeypair,
    private val crypto: OverlayProvisionCrypto = GomobileProvisionCrypto,
) : OverlayEnrollmentApi {

    @Volatile
    private var resolved: ProvisionTarget? = null

    override suspend fun enroll(publicKeyBase64: String): EnrollAck {
        val target = targetOrNull()
            ?: return EnrollAck.Rejected(UNREACHABLE)
        val keypair = keypair()
            ?: return EnrollAck.Rejected(NO_KEYPAIR)

        return when (val call = withConnection(target, keypair) { connection ->
            when (val reply = connection.enroll(token)) {
                is ProvisionMessage.EnrollPending -> EnrollAck.Pending(reply.pollAfterMs)

                // An assignment straight out of `enroll` is not what the contract says, but it is
                // a *better* answer than a pending one rather than a wrong one — so it is read as
                // "the answer is already there" and collected by the first poll. `Rejected` is
                // terminal, and a terminal state must not be built out of a reply the server was
                // free to improve on.
                is ProvisionMessage.EnrollReady -> EnrollAck.Pending(PROVISION_DEFAULT_POLL_MS)

                // ⚠️ `publicKeyBase64` is deliberately not sent: the server reads the key off the
                // HELLO that opened this connection, which is the same key by construction — both
                // come from the one `OverlayWgTunnelController.instance`.
                else -> EnrollAck.Rejected(
                    "The server answered the enrollment with '${reply::class.simpleName}'.",
                )
            }
        }) {
            is ChannelCall.Ok -> call.value
            is ChannelCall.Refused -> EnrollAck.Rejected(call.message)
            ChannelCall.Unreachable -> EnrollAck.Rejected(UNREACHABLE)
        }
    }

    override suspend fun poll(): EnrollPoll {
        val target = targetOrNull() ?: return EnrollPoll.Failed(UNREACHABLE)
        val keypair = keypair() ?: return EnrollPoll.Failed(NO_KEYPAIR)

        return when (val call = withConnection(target, keypair) { connection ->
            when (val reply = connection.enrollStatus(token)) {
                is ProvisionMessage.EnrollReady ->
                    checkAssignmentServerKey(target.tunnelKeyBase64, reply.assignment)

                is ProvisionMessage.EnrollPending -> EnrollPoll.Pending
                else -> EnrollPoll.Failed(
                    "The server answered the status with '${reply::class.simpleName}'.",
                )
            }
        }) {
            is ChannelCall.Ok -> call.value
            is ChannelCall.Refused -> EnrollPoll.Failed(call.message)
            ChannelCall.Unreachable -> EnrollPoll.Failed(UNREACHABLE)
        }
    }

    private suspend fun targetOrNull(): ProvisionTarget? =
        resolved ?: target()?.also { resolved = it }

    /**
     * One call, one connection, and **three outcomes the caller must be able to tell apart**.
     *
     * ⚠️ **A refusal and a dropped socket are not the same fact, and collapsing them is the
     * mistake this type exists to prevent.** `enroll` reaches the server, the server reads the
     * session token and answers `{t:'error', code:'unauthorized'}` — that arrives here as an
     * exception, exactly like a `ConnectException` does, so a `T?` return would report "could not
     * reach the server" for a server that answered promptly and said no. In a first-run flow that
     * an operator debugs from a screenshot, that is the sentence that costs an hour.
     */
    private suspend fun <T : Any> withConnection(
        target: ProvisionTarget,
        keypair: OverlayWgKeypair,
        block: suspend (OverlayProvisionConnection) -> T,
    ): ChannelCall<T> {
        var opened: OverlayProvisionConnection? = null
        return try {
            val connection = OverlayProvisionConnection.open(
                host = target.host,
                port = target.port,
                devicePrivateKeyBase64 = keypair.privateKeyBase64,
                devicePublicKeyBase64 = keypair.publicKeyBase64,
                serverPublicKeyBase64 = target.provisioningKeyBase64,
                crypto = crypto,
            )
            opened = connection
            ChannelCall.Ok(block(connection))
        } catch (error: CancellationException) {
            throw error
        } catch (error: OverlayProvisionException.ServerError) {
            // The server's own words, which name the actual reason — a dead session, a device it
            // has never heard of, a key it will not accept.
            Log.i(TAG, "The server refused the enrollment call: ${error.code}")
            ChannelCall.Refused(error.message ?: error.code)
        } catch (error: Throwable) {
            Log.w(TAG, "Provisioning enrollment call did not complete: ${error.message}")
            ChannelCall.Unreachable
        } finally {
            runCatching { opened?.close() }
        }
    }

    private companion object {
        const val UNREACHABLE = "Could not reach the server over the provisioning channel."
        const val NO_KEYPAIR = "This device has no overlay key pair yet."
    }
}

/**
 * What one call over the channel produced.
 *
 * See [ChannelEnrollmentApi.withConnection] for why [Refused] is not folded into [Unreachable].
 */
private sealed interface ChannelCall<out T> {
    data class Ok<T>(val value: T) : ChannelCall<T>

    /** The server answered, and its answer was no. [message] is the server's own. */
    data class Refused(val message: String) : ChannelCall<Nothing>

    /** No answer at all: no target, no route, a socket that dropped mid-call. */
    data object Unreachable : ChannelCall<Nothing>
}

/**
 * §7's **check 2** — the one thing the sealed handshake cannot do for us.
 *
 * ⚠️ The channel proves *who is speaking*; it says nothing about what they sent. A peer that
 * genuinely holds `ppk` could still hand back a config pointing at somebody else's endpoint with
 * somebody else's server key, and the tunnel would be a man-in-the-middle for as long as it
 * lived. `prov_priv` and the overlay's own private key are deliberately different keys (§6.4),
 * so holding the first must not be allowed to hand out the second.
 *
 * ⚠️ **A record with no `pk` cannot be checked and is not failed for it.** The comparison needs a
 * published value to compare against, and inventing a failure where there is nothing to disagree
 * with would strand a device on a record that is merely old. It is logged instead, because a
 * record that publishes `ppk` and not `pk` is a publisher bug rather than a device's problem.
 */
internal fun checkAssignmentServerKey(
    expectedTunnelKeyBase64: String?,
    assignment: OverlayAssignment,
): EnrollPoll {
    if (expectedTunnelKeyBase64 == null) {
        Log.w(TAG, "The record publishes no pk; the assignment's server key cannot be checked")
        return EnrollPoll.Ready(assignment)
    }
    if (!expectedTunnelKeyBase64.equals(assignment.serverPublicKeyBase64, ignoreCase = true)) {
        Log.e(TAG, "The assignment's server key is not the one the discovery record publishes")
        return EnrollPoll.Failed(
            "The server handed back a tunnel key that is not the one the discovery record publishes.",
        )
    }
    return EnrollPoll.Ready(assignment)
}
