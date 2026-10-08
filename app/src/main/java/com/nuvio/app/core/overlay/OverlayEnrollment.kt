package com.nuvio.app.core.overlay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.app.core.mtls.MtlsRegistration
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.companionRestBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "OverlayEnrollment"

/**
 * The address the **server assigned this device**, and everything needed to reach it.
 *
 * ⚠️ [address] is the client's *own* address inside the overlay, and it is the field that
 * makes enrollment worth having. [BoomioConfig.overlayLocalCidr]'s shipped default is
 * `10.77.0.2/32`, which is right for the first client on an overlay and **wrong for every
 * one after it** — the tunnel comes up, completes a handshake, and then silently drops every
 * return packet, because the server has no route to an address it never agreed to. That
 * failure looks exactly like a broken tunnel and is not one.
 */
internal data class OverlayAssignment(
    val address: String,
    val serverPublicKeyBase64: String,
    val endpoint: String,
    val overlayCidr: String,
    val mtu: Int,
    /**
     * The name the **server** gave this device, and the `CN` its client certificate must carry.
     *
     * It rides along with the address because they are assigned together and are wrong together:
     * re-enrolling is what renames a device, so a cached address and a cached name are either both
     * current or both stale. That is also why [OverlayEnrollment.writeConfig] applies it in the same
     * breath as the address rather than on a schedule of its own.
     *
     * ⚠️ Carried as one value with [address] on purpose, and that is a choice about failure. The
     * edge's mTLS allow-list is keyed on this string, so anything that applies one without the other
     * leaves a device holding a tunnel it may use and a certificate it may not.
     *
     * Defaulted to blank so this stays a source-compatible addition — but note the default is only
     * ever reached by a *constructed* assignment, never by one decoded from the server, which
     * always fills it (`decodeEnrollStatus`). A blank value here means "this server did not name the
     * device", which is the pre-mTLS case and gates certificate registration off.
     */
    val deviceName: String = "",
) {
    /** The `Address =` line's form. */
    val localCidr: String get() = "$address/32"

    /**
     * The **server's** own address on the overlay — the third overlay address, and the one
     * nothing carried until enrollment did. See [overlayServerAddressOf].
     *
     * Derived rather than decoded, so it recomputes per read and stays out of the data class's
     * equality: the declared fields are what the server assigned, and this is a reading of them.
     */
    val serverAddress: String get() = overlayServerAddressOf(overlayCidr)
}

/**
 * The server's address inside [overlayCidr] — the network's first host.
 *
 * ⚠️ **A convention, and its other half is the allocator.** `next_peer_address()` in
 * `overlay/overlay-server-setup.sh` hands out `10.77.0.${n}` starting at **n = 2**, so the
 * first host of the overlay is never assigned to a peer — it is the server, which is why
 * `10.77.0.1` is where the tunnel's DNS responder and its 443 both live. Deriving it is what
 * lets one shipped build work against any deployment: `BOOMIO_OVERLAY_ADDR` was build-time
 * with no fallback, and an app that must be *compiled* knowing where the server sits on a
 * private network cannot be shipped to anyone.
 *
 * ⚠️ **Blank for anything unparseable, and blank leaves the overlay resolver off** — the
 * fail-closed direction. A wrong address here would point every boomio FQDN at a host that
 * never answers, which is indistinguishable from a broken tunnel and much harder to find.
 */
internal fun overlayServerAddressOf(overlayCidr: String): String {
    val prefix = overlayCidr.substringAfter('/', "").trim().toIntOrNull() ?: return ""
    // A /31 and a /32 have no first *host* to speak of, and a /0 has no network boundary.
    if (prefix !in 1..30) return ""

    val octets = overlayCidr.substringBefore('/').trim().split('.')
    if (octets.size != 4) return ""
    val values = octets.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return "" }
    val network = values.fold(0L) { acc, value -> (acc shl 8) or value.toLong() }

    // Masked, so a caller handed a host address rather than the network still gets the same
    // answer: `10.77.0.7/24` and `10.77.0.0/24` both name 10.77.0.1.
    val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val firstHost = ((network and mask) + 1) and 0xFFFFFFFFL
    return listOf(24, 16, 8, 0).joinToString(".") { shift ->
        ((firstHost shr shift) and 0xFF).toString()
    }
}

/** An assignment together with the device key it was issued for. */
internal data class CachedAssignment(
    val assignment: OverlayAssignment,
    val issuedForPublicKeyBase64: String,
)

internal sealed interface OverlayEnrollmentState {
    /** No companion session, so there is nothing to enroll *as*. Not an error. */
    data object Unavailable : OverlayEnrollmentState
    data object Idle : OverlayEnrollmentState
    data object Enrolling : OverlayEnrollmentState

    /**
     * [fromCache] means the server was never contacted — the value is last run's, applied so
     * the tunnel can come up before the network is proven.
     */
    data class Ready(val assignment: OverlayAssignment, val fromCache: Boolean) : OverlayEnrollmentState
    data class Failed(val reason: String) : OverlayEnrollmentState
}

// ── the wire ────────────────────────────────────────────────────────────────────────────────

internal sealed interface EnrollAck {
    data class Pending(val pollAfterMs: Long) : EnrollAck

    /** The server refused the request itself — a bad key, a dead session. Not retryable. */
    data class Rejected(val reason: String) : EnrollAck
}

internal sealed interface EnrollPoll {
    data object Pending : EnrollPoll
    data class Ready(val assignment: OverlayAssignment) : EnrollPoll
    data class Failed(val reason: String) : EnrollPoll
}

/**
 * The two calls `bsc` answers. An interface rather than a concrete client so the policy below
 * can be tested without a server — the same split [OverlayWgTunnel] uses for its binding.
 */
internal interface OverlayEnrollmentApi {
    suspend fun enroll(publicKeyBase64: String): EnrollAck
    suspend fun poll(): EnrollPoll
}

// ── the policy ──────────────────────────────────────────────────────────────────────────────

/**
 * Decides what an enrollment attempt does, with no Android and no network of its own.
 *
 * Kept separate from [OverlayEnrollment] for the reason the rest of this package is split that
 * way: the interesting behaviour is *when* it talks to the server and *whether* it trusts what
 * it already has, and neither question needs a device to answer.
 */
internal class OverlayEnroller(
    private val api: () -> OverlayEnrollmentApi?,
    private val devicePublicKey: () -> String?,
    private val cached: () -> CachedAssignment?,
    private val remember: (CachedAssignment) -> Unit,
    private val apply: suspend (OverlayAssignment) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxPolls: Int = MAX_POLLS,
) {

    /**
     * [useCache] is the difference between "come up now" and "find out whether anything
     * changed". A cached assignment issued for *this* device key is still valid — the address
     * is allocated per public key, and the key has not changed — so returning it costs no
     * network and works with no connectivity at all. It can, however, be stale: the server's
     * endpoint or public key may have rotated since, which is why startup applies the cache
     * and then refreshes rather than choosing one.
     *
     * ⚠️ **The cache is keyed on the device's public key, not on time.** A regenerated keypair
     * means the old assignment is not merely stale but *wrong* — the server is holding a peer
     * for a key this device no longer has — so it must be discarded rather than applied.
     */
    suspend fun enroll(useCache: Boolean = true): OverlayEnrollmentState {
        val client = api() ?: return OverlayEnrollmentState.Unavailable
        val publicKey = devicePublicKey()
            ?: return OverlayEnrollmentState.Failed("This device has no overlay key pair yet.")

        if (useCache) {
            val held = cached()
            if (held != null && held.issuedForPublicKeyBase64 == publicKey) {
                apply(held.assignment)
                return OverlayEnrollmentState.Ready(held.assignment, fromCache = true)
            }
        }

        return when (val ack = client.enroll(publicKey)) {
            is EnrollAck.Rejected -> OverlayEnrollmentState.Failed(ack.reason)
            is EnrollAck.Pending -> awaitAssignment(client, publicKey, ack.pollAfterMs)
        }
    }

    private suspend fun awaitAssignment(
        client: OverlayEnrollmentApi,
        publicKey: String,
        firstDelayMs: Long,
    ): OverlayEnrollmentState {
        // The server allocates on a host timer it does not control the latency of, so this is
        // a poll rather than a long-held request: the request that records the enrollment
        // returns immediately, and the address exists only once the adapter has run.
        repeat(maxPolls) { attempt ->
            sleep(if (attempt == 0) firstDelayMs else POLL_INTERVAL_MS)
            when (val poll = client.poll()) {
                is EnrollPoll.Ready -> {
                    remember(CachedAssignment(poll.assignment, publicKey))
                    apply(poll.assignment)
                    return OverlayEnrollmentState.Ready(poll.assignment, fromCache = false)
                }
                is EnrollPoll.Failed -> return OverlayEnrollmentState.Failed(poll.reason)
                EnrollPoll.Pending -> Unit
            }
        }
        // Deliberately not a `Failed` that stops retrying: nothing is wrong, the adapter is
        // simply slower than this window. The next launch asks again, and the recorded request
        // is still queued on the host.
        return OverlayEnrollmentState.Failed("The server has not assigned an address yet.")
    }

    private companion object {
        /** ~36s at the server's 3s hint. The adapter ticks every 5s. */
        const val MAX_POLLS = 12
        const val POLL_INTERVAL_MS = 3_000L
    }
}

// ── the real transport ──────────────────────────────────────────────────────────────────────

/**
 * ⚠️ **Deliberately NOT [com.nuvio.app.features.boomio.createBoomioHttpClient].** That client
 * carries `withOverlayProxy()` and the overlay DNS hook, which is right for every other call
 * this app makes and fatal for this one: **enrollment is what creates the tunnel**, so a client
 * that routes through the tunnel, or that resolves names to an overlay address which does not
 * exist yet, can never complete the call that would bring it up. This one dials the public
 * edge directly, on the platform's own resolver — the same plane the companion pairing call
 * uses before anything is linked.
 */
private fun enrollmentHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
    }
    expectSuccess = false
}

internal class BscOverlayEnrollmentApi(
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient = enrollmentHttpClient(),
) : OverlayEnrollmentApi {

    override suspend fun enroll(publicKeyBase64: String): EnrollAck {
        val response = runCatching {
            http.post("$baseUrl/api/overlay/enroll") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(overlayEnrollJson.encodeToString(EnrollRequestDto(publicKeyBase64)))
            }
        }.getOrElse { return EnrollAck.Rejected("Could not reach the server: ${it.message}") }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return EnrollAck.Rejected(describeEnrollFailure(body, response.status.value))
        }
        return decodeEnrollAck(body)
    }

    override suspend fun poll(): EnrollPoll {
        val response = runCatching {
            http.get("$baseUrl/api/overlay/enroll/status") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }.getOrElse { return EnrollPoll.Failed("Could not reach the server: ${it.message}") }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return EnrollPoll.Failed(describeEnrollFailure(body, response.status.value))
        }
        return decodeEnrollStatus(body)
    }
}

// ── the wire, decoded ───────────────────────────────────────────────────────────────────────
//
// Split out of the client above for one reason: every field name here is a contract with
// `bsc/routes/overlay.js`, and a rename on that side would otherwise show up only as a device
// that silently never enrols — the worst possible symptom, because it is indistinguishable
// from a server that is merely slow. As free functions they can be tested against literal
// JSON, which is what pins the contract.

internal val overlayEnrollJson = Json { ignoreUnknownKeys = true }

internal const val OVERLAY_ENROLL_DEFAULT_POLL_MS = 3_000L
internal const val OVERLAY_ENROLL_DEFAULT_CIDR = "10.77.0.0/24"
internal const val OVERLAY_ENROLL_DEFAULT_MTU = 1420

@Serializable
internal data class EnrollRequestDto(val pubkey: String)

@Serializable
internal data class EnrollAckDto(
    val status: String? = null,
    val name: String? = null,
    @SerialName("poll_after_ms") val pollAfterMs: Long? = null,
)

@Serializable
internal data class EnrollStatusDto(
    val status: String,
    val name: String? = null,
    val address: String? = null,
    @SerialName("server_pubkey") val serverPubkey: String? = null,
    val endpoint: String? = null,
    @SerialName("overlay_cidr") val overlayCidr: String? = null,
    val mtu: Int? = null,
    val reason: String? = null,
)

@Serializable
internal data class EnrollErrorDto(val error: String? = null)

/**
 * The `202` from `POST /api/overlay/enroll`. A 202 is only ever `pending` — the route's
 * rejections all arrive as non-2xx and are handled by [describeEnrollFailure] before this is
 * reached — so the only thing to read here is the server's poll hint.
 */
internal fun decodeEnrollAck(body: String): EnrollAck {
    val dto = runCatching { overlayEnrollJson.decodeFromString<EnrollAckDto>(body) }.getOrNull()
        ?: return EnrollAck.Rejected("The server's enrollment reply was not understood.")
    return EnrollAck.Pending(pollAfterMs = dto.pollAfterMs ?: OVERLAY_ENROLL_DEFAULT_POLL_MS)
}

/** The `200` from `GET /api/overlay/enroll/status`. */
internal fun decodeEnrollStatus(body: String): EnrollPoll {
    val dto = runCatching { overlayEnrollJson.decodeFromString<EnrollStatusDto>(body) }.getOrNull()
        ?: return EnrollPoll.Failed("The server's status reply was not understood.")

    return when (dto.status) {
        "ready" -> {
            val address = dto.address
            val serverKey = dto.serverPubkey
            val endpoint = dto.endpoint
            if (address.isNullOrBlank() || serverKey.isNullOrBlank() || endpoint.isNullOrBlank()) {
                // ⚠️ A `ready` with a hole in it is NOT usable, and this is the one case worth
                // being strict about: applying it would point the tunnel at an endpoint with
                // no server key to authenticate, or hand the tunnel an address the server
                // never allocated — and the second failure mode is the silent-drops one.
                EnrollPoll.Failed("The server's assignment is incomplete.")
            } else {
                EnrollPoll.Ready(
                    OverlayAssignment(
                        address = address,
                        serverPublicKeyBase64 = serverKey,
                        endpoint = endpoint,
                        overlayCidr = dto.overlayCidr ?: OVERLAY_ENROLL_DEFAULT_CIDR,
                        mtu = dto.mtu ?: OVERLAY_ENROLL_DEFAULT_MTU,
                        // Carried through rather than dropped. The route has always sent this —
                        // `EnrollAckDto` and `EnrollStatusDto` both declared it — but nothing
                        // downstream read it, so it was parsed and discarded here. It is the CN
                        // the certificate route will demand, so it has to survive the boundary.
                        // Blank (an older server, or one that does not derive names) is tolerated
                        // and registers nothing: see `planCertificate`.
                        deviceName = dto.name.orEmpty(),
                    ),
                )
            }
        }
        "failed" -> EnrollPoll.Failed(dto.reason ?: "The server could not enroll this device.")
        else -> EnrollPoll.Pending
    }
}

/** Pulls the human-readable half out of an error body, falling back to the status code. */
internal fun describeEnrollFailure(body: String, status: Int): String {
    val detail = runCatching { overlayEnrollJson.decodeFromString<EnrollErrorDto>(body).error }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
    return when {
        detail != null -> detail
        status == 401 -> "This device is not paired with the server."
        else -> "The server refused the request ($status)."
    }
}

// ── the singleton ───────────────────────────────────────────────────────────────────────────

/**
 * The app's self-enrollment with the overlay server.
 *
 * Replaces the last hand-held step in the overlay: until this exists, a client's overlay
 * address and its enrolment with the server are both **build-time constants**
 * (`BOOMIO_OVERLAY_LOCAL_CIDR`, `_ENDPOINT`, `_PUBKEY`), which is not a property any shipped
 * app can have — the second client on an overlay needs a *different* address from the first,
 * and no build can know which one it is.
 *
 * On startup this applies the last assignment from disk (so the tunnel works before, or
 * without, a network) and then refreshes it in the background.
 */
internal object OverlayEnrollment {

    private const val PREFS = "boomio_overlay_enrollment"

    private const val KEY_ADDRESS = "address"
    private const val KEY_SERVER_PUBKEY = "server_pubkey"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_OVERLAY_CIDR = "overlay_cidr"
    private const val KEY_MTU = "mtu"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_ISSUED_FOR = "issued_for_public_key"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<OverlayEnrollmentState>(OverlayEnrollmentState.Idle)
    val state: StateFlow<OverlayEnrollmentState> = _state.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var enroller: OverlayEnroller? = null

    @Volatile
    private var started = false

    /**
     * ⚠️ **Plain `SharedPreferences`, and unlike the keypair beside it that is not a
     * compromise.** Everything stored here is public by construction: an address inside
     * `10.77.0.0/24`, the server's public key (already published by mDNS and by the DuckDNS TXT
     * record), and an endpoint. There is no secret to protect, so there is nothing an
     * AndroidKeyStore would be buying. The keypair keeps its own file and its own reasoning.
     */
    fun initialize(context: Context) {
        if (started) return
        started = true

        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store

        // Applied synchronously and before any network call, so a cold start off-network still
        // has a working tunnel from last run rather than waiting on a request that cannot land.
        read(store)?.let { cached ->
            val key = devicePublicKey()
            if (key != null && cached.issuedForPublicKeyBase64 == key) {
                writeConfig(cached.assignment)
                _state.value = OverlayEnrollmentState.Ready(cached.assignment, fromCache = true)
            }
        }

        enroller = OverlayEnroller(
            api = ::apiOrNull,
            devicePublicKey = ::devicePublicKey,
            cached = { read(store) },
            remember = { write(store, it) },
            apply = { assignment ->
                writeConfig(assignment)
                // Tells the ladder to re-run now rather than at its next cadence, which is what
                // makes a first enrollment bring the tunnel up in seconds instead of minutes.
                OverlayEndpointDiscovery.offerManual(
                    rawAuthority = assignment.endpoint,
                    serverPublicKeyBase64 = assignment.serverPublicKeyBase64,
                )
            },
        )

        // ⚠️ **Bound to the session rather than run once, and that is the whole point.** An
        // enrollment call needs a `bs_ses_` token, and the install this path exists for has
        // none yet — the device is being onboarded *because* it has nothing. A single
        // `refresh()` here would run before pairing completes and then never again, leaving a
        // device that had just linked holding no assignment until it was restarted. It only
        // ever worked when a token had survived from a previous run into
        // `BoomioSessionRepository.initialize`, which is precisely the case that does not exist
        // on a fresh install. Collecting instead makes the same call happen whenever a token
        // arrives: from the store at start-up, or minutes later when the device-code poll lands.
        //
        // Keyed on the *token* so a session object changing for some unrelated reason does not
        // re-enroll, while a genuine re-link does — `unlink()` clears the token and the next
        // poll sets a different one, which is a change like any other.
        scope.launch {
            BoomioSessionRepository.session
                .map { session -> session?.token?.takeIf { it.isNotBlank() } }
                .distinctUntilChanged()
                .collect { token ->
                    if (token == null) return@collect
                    refresh()
                    // ⚠️ The policy is refreshed on the same trigger, and here rather than only on
                    // app-foreground so a freshly enrolled device has a policy *before* it has a
                    // tunnel — the moment the server's answer matters most. Best effort: a failure
                    // leaves the cached policy in force (see SecurityPolicyRefresh).
                    SecurityPolicyRefresh.refresh()
                }
        }
    }

    /** Asks the server for this device's address, using the cache only if [useCache]. */
    suspend fun enroll(useCache: Boolean = true): OverlayEnrollmentState {
        val runner = enroller ?: return OverlayEnrollmentState.Unavailable
        _state.value = OverlayEnrollmentState.Enrolling
        val result = runner.enroll(useCache)
        _state.value = result
        return result
    }

    /** The startup path: never trusts the cache, so a rotated server key is picked up. */
    suspend fun refresh(): OverlayEnrollmentState {
        val result = enroll(useCache = false)
        if (result is OverlayEnrollmentState.Failed) {
            // The cache, if there is one, is already applied and still working — a failed
            // refresh must not be reported as a device that has no address.
            val held = prefs?.let { read(it) }
            if (held != null && held.issuedForPublicKeyBase64 == devicePublicKey()) {
                Log.d(TAG, "Refresh failed (${result.reason}); continuing on the saved assignment")
                _state.value = OverlayEnrollmentState.Ready(held.assignment, fromCache = true)
                return _state.value
            }
        }
        return result
    }

    private fun apiOrNull(): OverlayEnrollmentApi? {
        val token = BoomioSessionRepository.bearerToken()?.takeIf { it.isNotBlank() } ?: return null

        // ⚠️ **The transport that linked the device is the transport that enrolls it.** The
        // default below dials `companionRestBaseUrl`, which off-LAN resolves to the box's *LAN*
        // address — so a device that reached its session over the provisioning channel because
        // that address is unreachable would, on the very next call, reach for it again and come
        // away with a session and no tunnel. `OverlayProvisioning` answers null wherever the
        // channel is not the right transport (the ingress is off, or the device is on the home
        // L2 where the base URL works), and that null *is* the fallback.
        //
        // ⚠️ **The plain-HTTPS bootstrap arm sits around that, and it is the owner's *"freeze the
        // enrolment if it is not possible or desired"* requirement.** "Not desired" is the
        // client-side switch, which skips the channel entirely; "not possible" is the channel
        // answering null, which is already the fall-through below. When the fallback is switched
        // off *and* the channel does not apply, this returns null — the pre-existing behaviour.
        // See `BootstrapFallback` for why the base URL is the public edge without a pin.
        if (!BootstrapFallback.plainHttpsPreferred) {
            OverlayProvisioning.enrollmentApi(token)?.let { return it }
        }
        if (!BootstrapFallback.shouldUsePlainHttps(overlayAvailable = false)) return null

        val base = BoomioConfig.companionRestBaseUrl.takeIf { it.isNotBlank() } ?: return null
        return BscOverlayEnrollmentApi(baseUrl = base, token = token)
    }

    private fun devicePublicKey(): String? =
        runCatching { OverlayWgTunnelController.instance?.publicKeyBase64() }.getOrNull()

    /**
     * Points the app at the assigned server. Synchronous on purpose: these are read live by the
     * ladder ([OverlayEndpointDiscovery.rung3Manual]), by [OverlaySessionDriver] and by the DNS
     * seam, so writing them is what "the assignment took effect" means.
     *
     * The certificate registration at the end belongs here for the same reason. The device's name
     * arrives *with* its address, so "the assignment took effect" and "this device now has a name to
     * be certified under" are the same event — and this is the only place that can fire for both
     * paths, the cached one at startup and the fresh one from the server.
     */
    private fun writeConfig(assignment: OverlayAssignment) {
        BoomioConfig.overlayLocalCidr = assignment.localCidr
        BoomioConfig.overlayEndpoint = assignment.endpoint
        BoomioConfig.overlayServerPubKey = assignment.serverPublicKeyBase64

        // ⚠️ The one field here that used to have a build-time source *only*, and the reason
        // this area was a gap: with `BOOMIO_OVERLAY_ADDR` unset the DNS seam was dead and the
        // app resolved every boomio FQDN publicly, so a shipped build could not work against a
        // deployment it had not been compiled for. The address arrives with the assignment now.
        //
        // Blank means the CIDR did not parse, and a blank must NOT clobber a build-time value:
        // an operator who set `BOOMIO_OVERLAY_ADDR` gets to keep it, and the seam stays off
        // only when there is genuinely nothing to aim it at.
        assignment.serverAddress.takeIf { it.isNotBlank() }?.let {
            BoomioConfig.overlayServerAddress = it
        }

        // ⚠️ **The device's own name, and it is the one field here the app cannot derive.** Every
        // other line above is about reaching the server; this one is what the server calls *us*,
        // and it is the CN the edge's mTLS allow-list is keyed on. `bsc` computes it from the
        // device id, so a build has no way to know it in advance — and a name this app invented
        // for itself would be a name the allow-list has never heard of.
        //
        // ⚠️ Blank must NOT clobber a learned value, and the condition is not an optimisation. A
        // cache written before this field existed reads back blank, and assigning that would
        // *erase* a name learned earlier in the same process — the enrolled-then-refresh-failed
        // path re-applies the cache, so a blank could land on top of a good value and silently
        // disable registration. Same reason as the address above: a reply that omits the name is
        // not an instruction to forget the one we have.
        if (assignment.deviceName.isNotBlank()) {
            BoomioConfig.overlayDeviceName = assignment.deviceName
        }

        // ⚠️ **The assignment taking effect has to be announced, not merely written.** The two
        // components that gate on the address check it at their own start-up, and start-up is
        // long over by the time this runs: `MainActivity` initializes `OverlaySession` and
        // `OverlayRelay` *before* this object, deliberately, because enrollment needs the
        // keypair the first of them creates. On a fresh install the address is blank at that
        // moment, so without these two calls a device that had just enrolled would hold a
        // perfectly good assignment and still not tunnel, not resolve over the overlay, and not
        // reach the relay until the app was restarted. A no-op once the address is known, which
        // is every run after the first.
        OverlaySession.onServerAddressLearned()
        OverlayRelay.onServerAddressLearned()

        // ⚠️ **The second half of that same event, and the two triggers are deliberately both
        // kept.** This one fires on every `writeConfig` — including the cached assignment applied
        // at startup — while `MtlsRegistration`'s own collector fires when the enrollment state
        // *emits* a `Ready`. Neither subsumes the other: the collector cannot see a state value
        // that was already `Ready` before it subscribed, and this call cannot see a refresh that
        // changed nothing. Firing twice is free — `planCertificate` answers `AlreadyRegistered`
        // and `register` returns without touching the network.
        //
        // No-op until `MtlsRegistration.initialize` has run, and free afterwards.
        MtlsRegistration.requestRegistration()
    }

    private fun read(store: SharedPreferences): CachedAssignment? {
        val address = store.getString(KEY_ADDRESS, null) ?: return null
        val serverKey = store.getString(KEY_SERVER_PUBKEY, null) ?: return null
        val endpoint = store.getString(KEY_ENDPOINT, null) ?: return null
        val issuedFor = store.getString(KEY_ISSUED_FOR, null) ?: return null
        return CachedAssignment(
            OverlayAssignment(
                address = address,
                serverPublicKeyBase64 = serverKey,
                endpoint = endpoint,
                overlayCidr = store.getString(KEY_OVERLAY_CIDR, null) ?: "10.77.0.0/24",
                mtu = store.getInt(KEY_MTU, 1420),
                // Absent on a record written before this field existed, and that reads as
                // "unknown" rather than as an error: the next `refresh()` fills it in. Reading it
                // back matters for the cold start — without it a device that enrolled last week
                // and has no network this morning would know its address and not its own name,
                // and the mTLS half would sit at `Unavailable` until something else woke it.
                deviceName = store.getString(KEY_DEVICE_NAME, null).orEmpty(),
            ),
            issuedForPublicKeyBase64 = issuedFor,
        )
    }

    private fun write(store: SharedPreferences, cached: CachedAssignment) {
        store.edit()
            .putString(KEY_ADDRESS, cached.assignment.address)
            .putString(KEY_SERVER_PUBKEY, cached.assignment.serverPublicKeyBase64)
            .putString(KEY_ENDPOINT, cached.assignment.endpoint)
            .putString(KEY_OVERLAY_CIDR, cached.assignment.overlayCidr)
            .putInt(KEY_MTU, cached.assignment.mtu)
            .putString(KEY_ISSUED_FOR, cached.issuedForPublicKeyBase64)
            .putString(KEY_DEVICE_NAME, cached.assignment.deviceName)
            .apply()
    }
}
