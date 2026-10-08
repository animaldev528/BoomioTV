package com.nuvio.app.core.overlay

import android.content.Context
import android.util.Log
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.companionRestBaseUrl
import com.nuvio.app.features.boomio.createBoomioHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

private const val TAG = "SecurityPolicy"

/**
 * Where a policy comes from, as a seam. **One method, and null means "no answer"** — never
 * "the empty policy", because the caller must be able to tell a server that said nothing from a
 * server that said "these are the defaults" (the first leaves the cache in force, the second
 * replaces it).
 *
 * A `fun interface` so a test can supply a lambda, and so the transport choice stays out of the
 * consumer — exactly the shape `MtlsRegistrationApi` and `OverlayEnrollmentApi` already use for the
 * two other server-pushed facts this app holds.
 */
internal fun interface SecurityPolicyApi {
    suspend fun fetch(): SecurityPolicy?
}

/**
 * A policy together with the freshness the server labelled it with.
 *
 * `stale = true` means bsc served its last known good value because it could not re-read bsm. **It is
 * still an answer** — the values are the ones an admin last saved — so it is applied exactly like a
 * fresh one; the flag exists so the client can *tell the two apart*, which it could not before.
 * `ageMs` is how old that cached value is, when the server reports it.
 */
internal data class SecurityPolicyAnswer(
    val policy: SecurityPolicy,
    val stale: Boolean,
    val ageMs: Long?,
)

/**
 * A policy api that can also say how fresh the answer it just handed back was.
 *
 * ⚠️ **Only the HTTPS transport can implement this.** `GET /api/overlay/policy` carries `stale` and
 * `age_ms`; the channel's `policy` message carries the four booleans alone. [ChannelSecurityPolicyApi]
 * deliberately does **not** implement this rather than inventing `stale = false` for a reply that
 * never mentioned freshness.
 */
internal interface SecurityPolicyFreshness {
    /** The freshness of the policy the last non-null [SecurityPolicyApi.fetch] returned, or null. */
    val lastAnswer: SecurityPolicyAnswer?
}

/**
 * The pull transport that **cannot be the primary, and is now the fallback**: the pre-tunnel
 * provisioning channel, the same connection that already carries enrollment and certificate
 * registration.
 *
 * ── Why this used to be the only path, and why it is not ────────────────────
 * bsm's `GET /api/internal/security-policy` is authenticated by `X-Internal-Key`, which is a
 * **server-to-server** secret (it is what bsc presents; the `companion-pair-blocked-by-internal-key`
 * note records `POST /auth/pair` failing without it). The mobile client holds no such key — verified
 * in this tree, there is no `X-Internal-Key`, no `BuildConfig` field for it, and
 * `BoomioConfig.companionRestBaseUrl` addresses bsc, never bsm. That reasoning was correct about
 * **bsm** and missed **bsc**: `GET /api/overlay/policy` on bsc is authenticated by the very
 * `bs_ses_*` session this client already holds, so the ordinary HTTPS read is reachable and is now
 * the primary ([BscSecurityPolicyApi], ordered ahead of this one by [FallbackSecurityPolicyApi]).
 *
 * ── Why it is kept at all ───────────────────────────────────────────────────
 * ⚠️ **A device on the home L2 can hold a session it cannot use over HTTPS.** The public FQDN is on
 * the far side of the WAN and hairpin NAT is OFF on this network (task #135), so a device at home
 * cannot reach the edge by name — the exact hole this channel was built to fill. The channel is
 * therefore the second arm of the chain: reached only when the HTTPS pull produced no answer.
 *
 * ⚠️ **`{t:"policy.get"}` is still a guess.** The server half exposes the policy over HTTPS
 * ([BscSecurityPolicyApi]) and no channel handler exists yet; this arm is written to be *correct when
 * it is absent*: an unknown message type, a refusal, or a dead socket all surface as `null` (or a
 * thrown exception the caller swallows), and the cached policy stays in force. It never falls back to
 * the defaults on a failed pull.
 */
internal class ChannelSecurityPolicyApi(
    private val token: String,
    private val target: suspend () -> ProvisionTarget? = OverlayProvisioning::target,
    private val keypair: () -> OverlayWgKeypair? = OverlayProvisioning::deviceKeypair,
    private val crypto: OverlayProvisionCrypto = GomobileProvisionCrypto,
) : SecurityPolicyApi {

    override suspend fun fetch(): SecurityPolicy? {
        val target = target() ?: return null
        val keypair = keypair() ?: return null

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
            when (val reply = connection.securityPolicy(token)) {
                is ProvisionMessage.Policy -> reply.policy
                else -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Ordinary while the tunnel comes up, and a channel that does not answer `policy.get`
            // yet is the expected case. Neither is a reason to move off the cached policy.
            Log.w(TAG, "Security policy pull did not complete: ${error.message}")
            null
        } finally {
            runCatching { opened?.close() }
        }
    }
}

/**
 * One HTTP client for the process, and the reason is the same [com.nuvio.app.core.mtls.MtlsRegistration]
 * records for its own: [SecurityPolicyRefresh] constructs [BscSecurityPolicyApi] on every pull, so a
 * client per construction would leak an engine each time. `by lazy` also defers the keystore/overlay
 * wiring in `createBoomioHttpClient()` until the first pull actually needs it.
 */
private val securityPolicyHttpClient: HttpClient by lazy { createBoomioHttpClient() }

/**
 * The **primary** pull transport: `GET /api/overlay/policy` on bsc, over the session this client
 * already holds.
 *
 * ── Everything it reuses ────────────────────────────────────────────────────
 * It is deliberately the sibling of [com.nuvio.app.core.mtls.BscMtlsRegistrationApi]
 * (`POST /api/overlay/cert`) and [BscOverlayEnrollmentApi] (`POST /api/overlay/enroll`), and reuses
 * rather than reinvents each of the three things those do:
 *
 * * **the base URL** — `BoomioConfig.companionRestBaseUrl`, which is bsc, the same host those two
 *   dial (the policy route is mounted at `/api` on the same router, `routes/overlay.js`);
 * * **the bearer** — the same `bs_ses_*` session token `BoomioSessionRepository` hands those two;
 * * **the client** — `createBoomioHttpClient()`, *not* a forked one. That client already carries
 *   `withOverlayProxy()` and `withClientCertificate()`, so a device that has a certificate presents
 *   it here with no code in this file — matching the sibling's client choice is what keeps a second,
 *   subtly-different TLS seam from existing.
 *
 * ⚠️ **This is the transport the channel could never be, because it needs no tunnel.** The route is
 * an ordinary HTTPS call over the public edge, so a device with a session but no working channel — or
 * one whose channel is simply the wrong transport — reaches it directly. The channel remains the
 * fallback for the reverse case (see [ChannelSecurityPolicyApi]).
 */
internal class BscSecurityPolicyApi(
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient = securityPolicyHttpClient,
) : SecurityPolicyApi, SecurityPolicyFreshness {

    @Volatile
    override var lastAnswer: SecurityPolicyAnswer? = null
        private set

    override suspend fun fetch(): SecurityPolicy? {
        // ⚠️ **A transport error is "no answer", never "the defaults".** The caller keeps whatever
        // policy it already holds; only a real answer may replace it.
        val response = runCatching {
            http.get("$baseUrl/api/overlay/policy") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }.getOrElse {
            Log.w(TAG, "Security policy HTTPS pull did not complete: ${it.message}")
            return null
        }

        val answer = decodeSecurityPolicyReply(response.status.value, response.bodyAsText()) ?: return null
        lastAnswer = answer
        if (answer.stale) {
            // The server answered from its cache after a failed re-read: still an answer, but it says
            // bsm was unreachable. Worth a line, because it is the state a loosening is *waiting* to
            // be replaced in and the state a tightening has not yet reached.
            Log.i(TAG, "Security policy served stale (age_ms=${answer.ageMs}); applying it anyway")
        }
        return answer.policy
    }
}

/**
 * The transport chain: **HTTPS first, the channel second** — the ordering the factory builds.
 *
 * ── Why this exists at all ──────────────────────────────────────────────────
 * The two transports cover opposite networks. [BscSecurityPolicyApi] is the ordinary, direct read and
 * works wherever the session's base URL is reachable; [ChannelSecurityPolicyApi] is for the device
 * that holds a session but cannot complete that HTTPS call — the on-LAN case where the public FQDN is
 * unreachable (hairpin NAT is off; task #135). Neither is right everywhere, so both are kept and the
 * chain picks whichever answers.
 *
 * ⚠️ **A chain is not a retry loop and must not become one.** [fetch] answers with the first arm that
 * produces a policy and `null` only when *both* have nothing. On `null` it neither invents a policy
 * nor reaches for the compiled defaults: [SecurityPolicyRefresh] leaves the cached policy in force,
 * which is the whole point of the null contract. A chain that fell back to the defaults on a failed
 * pull would be a security regression — it could silently *loosen* a policy the server had tightened.
 */
internal class FallbackSecurityPolicyApi(
    private val primary: SecurityPolicyApi,
    private val fallback: SecurityPolicyApi,
) : SecurityPolicyApi, SecurityPolicyFreshness {

    @Volatile
    override var lastAnswer: SecurityPolicyAnswer? = null
        private set

    override suspend fun fetch(): SecurityPolicy? {
        val fromHttps = primary.fetch()
        if (fromHttps != null) {
            // Prefer the arm's own freshness when it has one; a plain api that produced a policy
            // answered the question but said nothing about staleness, so the default is "not stale".
            lastAnswer = (primary as? SecurityPolicyFreshness)?.lastAnswer
                ?: SecurityPolicyAnswer(fromHttps, stale = false, ageMs = null)
            return fromHttps
        }

        // ⚠️ Only reached when the HTTPS arm yielded null — an unreachable edge, a non-200, or a body
        // that was not four booleans. Anything but null would have returned above.
        val fromChannel = fallback.fetch() ?: return null
        // The channel labels no freshness and its reply is a live read of the record, so it is not
        // called stale; `ageMs` is null because the reply carries no age. Labelling a channel answer
        // "fresh" is an absence of information, not a claim — see [SecurityPolicyFreshness].
        lastAnswer = SecurityPolicyAnswer(fromChannel, stale = false, ageMs = null)
        return fromChannel
    }
}

/**
 * Orders the two transports into the api the refresh pulls from, or null when there is none.
 *
 * Pure and takes the arms already built, so the *ordering* — HTTPS preferred, the channel only when
 * HTTPS is absent or answers null — can be pinned by a test with no session, no base URL and no
 * socket. [SecurityPolicyRefresh.defaultApi] is the only production caller and is responsible for
 * building the two arms; this function is responsible only for what happens when both exist, one
 * exists, or neither does.
 */
internal fun securityPolicyApiChain(
    https: SecurityPolicyApi?,
    channel: SecurityPolicyApi?,
): SecurityPolicyApi? = when {
    https != null && channel != null -> FallbackSecurityPolicyApi(primary = https, fallback = channel)
    else -> https ?: channel
}

// ── the wire ────────────────────────────────────────────────────────────────────────────────

private val policyReplyJson = Json { ignoreUnknownKeys = true }

/**
 * The `200` body of `GET /api/overlay/policy`, decoded — **or null for anything that is not one**.
 *
 * ⚠️ **Strict on the policy, and that is deliberate.** The server validates its own record before it
 * answers (`lib/security-policy.js`), so a `200` whose `policy` is not exactly four booleans is
 * anomalous — a mangled body, a hand-edit, a version skew — and the client reads it as *no answer*.
 * See [strictSecurityPolicyFrom] for why that is the opposite of the cache's lenient merge.
 *
 * Every non-`200` status maps to null, and the reason is that the caller's rule is identical for all
 * of them: `401` (no or invalid bearer), `429` (this device's poll budget), `503` (bsc has nothing
 * cached and bsm is unreachable) and everything else all mean "keep the policy you already hold", so
 * there is nothing to distinguish.
 *
 * `stale`/`age_ms` are read leniently: they are the server's freshness labels, not part of the
 * policy, so a reply that omits them still answers the question it was asked.
 */
internal fun decodeSecurityPolicyReply(status: Int, body: String): SecurityPolicyAnswer? {
    if (status != 200) return null

    val root = runCatching { policyReplyJson.parseToJsonElement(body) }.getOrNull() as? JsonObject
        ?: return null
    val policy = strictSecurityPolicyFrom(root["policy"] as? JsonObject ?: return null) ?: return null

    return SecurityPolicyAnswer(
        policy = policy,
        stale = (root["stale"] as? JsonPrimitive)?.booleanOrNull ?: false,
        ageMs = (root["age_ms"] as? JsonPrimitive)?.longOrNull,
    )
}

/**
 * Fetches the policy, caches it, and applies it — on enrollment and on app-foreground.
 *
 * ── The design question this answers ────────────────────────────────────────
 * There was no periodic server-config refresh: re-enrollment fires only on a session-token change, so
 * a policy pushed by bsm would never reach a running client. The owner's asymmetry rule ("always be
 * able to go DOWN") makes that a correctness bug, not a nicety: a *loosened* policy must reach an
 * already-deployed client without a wipe.
 *
 * **Transport chosen: (b) — a lightweight pull, refreshed on app-foreground and at enrollment.**
 * (a) alone (the policy riding the enrollment reply) is insufficient by construction: it covers only
 * fresh installs and re-enrollments, which is exactly the population that does not need loosening.
 * A pull is required, and it is served by a chain: the authenticated HTTPS route first
 * ([BscSecurityPolicyApi]), the provisioning channel second ([ChannelSecurityPolicyApi]). The
 * enrollment trigger is kept because it is free — the same session-token arrival that re-enrolls also
 * refreshes the policy, so a freshly enrolled device has a policy before it has a tunnel.
 *
 * ⚠️ **Every failure leaves the cached policy in force.** No branch here applies the defaults on an
 * error; the defaults are applied only at [initialize] when there is nothing cached, and by
 * [SecurityPolicyState.apply] when the server actually answered.
 */
internal object SecurityPolicyRefresh {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    /** The api factory. Swapped by tests; production reads the session and the provisioning flag. */
    @Volatile
    internal var apiFactory: () -> SecurityPolicyApi? = ::defaultApi

    /** The foreground throttle. A person switching apps must not cost a handshake each time. */
    @Volatile
    internal var minIntervalMs: Long = 5 * 60 * 1000L

    /**
     * Whether the policy in force came from the server labelled `stale`; false when the last answer
     * was fresh or the transport did not carry a label.
     *
     * ⚠️ **This is new information, and it is the reason it is kept rather than dropped.** The
     * channel's `policy` message could never say it; `GET /api/overlay/policy` does. It is the
     * difference between "bsm said this just now" and "bsc's cache said this after bsm went quiet",
     * and a caller that wanted to warn, or to re-poll sooner, has nowhere else to read it.
     */
    @Volatile
    internal var lastAnswerStale: Boolean = false

    private val busy = AtomicBoolean(false)
    private val lastAttemptAt = AtomicLong(0L)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // The cache is applied before any network call, so a cold start off-network already routes
        // by the last policy it was told — the same order OverlayEnrollment applies its assignment.
        SecurityPolicyStore.load(context)?.let { SecurityPolicyState.apply(it) }
    }

    /**
     * Pulls the policy once. Returns true when a policy was fetched and applied (whether or not it
     * changed), false when nothing could be applied — in which case the cache is untouched.
     */
    suspend fun refresh(): Boolean {
        val api = apiFactory() ?: return false
        if (!busy.compareAndSet(false, true)) return false
        try {
            val policy = try {
                api.fetch()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Security policy refresh failed: ${error.message}")
                return false
            } ?: return false

            SecurityPolicyState.apply(policy)
            appContext?.let { SecurityPolicyStore.save(it, policy) }
            // ⚠️ `stale` is surfaced rather than discarded. It is the server's own label for "this is
            // my last known good value", and it is the one thing that tells the client whether the
            // policy it just applied is a fresh admin edit or a cache served after bsm went quiet.
            // A stale answer is applied exactly like a fresh one — the label is information, not a
            // reason to refuse.
            val stale = (api as? SecurityPolicyFreshness)?.lastAnswer?.stale == true
            lastAnswerStale = stale
            Log.i(TAG, "Security policy applied: $policy (stale=$stale)")
            return true
        } finally {
            busy.set(false)
            lastAttemptAt.set(nowMs())
        }
    }

    /**
     * The app-foreground trigger: refresh, unless we tried recently.
     *
     * ⚠️ **Fire-and-forget and never on a caller's critical path.** This runs from `onStart`, where a
     * blocking handshake would show up as a frozen first frame; the result is published by
     * `SecurityPolicyState`, which the routing seams read live, so nothing has to wait for it.
     */
    fun onAppForegrounded() {
        if (nowMs() - lastAttemptAt.get() < minIntervalMs) return
        scope.launch { refresh() }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    /**
     * The production api: **the HTTPS route first, the channel second** — see [securityPolicyApiChain]
     * — and **null when neither can be built** (no session token).
     *
     * Null is not an error; it is why a device with no session does not pay for a pull it has no
     * credential to complete, and it is the same "null is the fallback" rule
     * `OverlayProvisioning.enrollmentApi` follows.
     */
    private fun defaultApi(): SecurityPolicyApi? {
        val token = BoomioSessionRepository.bearerToken()?.takeIf { it.isNotBlank() } ?: return null

        // **HTTPS first.** `GET /api/overlay/policy` on bsc is authenticated by the same `bs_ses_*`
        // bearer this client already sends to `/api/overlay/enroll` and `/api/overlay/cert`, over the
        // same shared client (whose mTLS certificate `MtlsSsl` attaches, not this file). It needs no
        // tunnel, so it is the ordinary direct read and the one to prefer.
        val https = BoomioConfig.companionRestBaseUrl
            .takeIf { it.isNotBlank() }
            ?.let { base -> BscSecurityPolicyApi(baseUrl = base, token = token) }

        // **The channel second**, and only when it is this network's transport. A device on the home
        // L2 holds a session it cannot use over HTTPS (the public FQDN is unreachable; hairpin NAT is
        // off), and this is the arm that still answers there. This is the same check
        // `OverlayProvisioning.enrollmentApi` and `channelRegistrationApiOrNull` apply, for the same
        // reason: whether the channel is the right transport is a property of the network, not of the
        // operation.
        val channel = if (OverlayProvisioning.isLinkedOverChannel()) ChannelSecurityPolicyApi(token) else null

        return securityPolicyApiChain(https, channel)
    }
}
