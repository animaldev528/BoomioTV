package com.nuvio.app.features.boomio

import android.content.Context
import com.nuvio.tv.core.auth.currentDeviceClientMetadata
import com.nuvio.tv.core.sync.SyncClientIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * TV compat layer — **not** part of the overlay port.
 *
 * ⚠️ **This is a deliberate re-declaration, not a port.** Mobile's `BoomioSessionRepository` is a
 * substantial class wired to Ktor, the device-code flow, `bs_ses_` storage and self-approval;
 * transplanting it wholesale is exactly what would make this port un-inert. The overlay uses
 * only three members of it, so only those three exist here, and the host device's own session is
 * what feeds them.
 *
 * The overlay needs the session for one thing: **enrollment (B3)**. `OverlayEnrollment` collects
 * [BoomioSessionRepository.session] and calls [BoomioSessionRepository.bearerToken] to authenticate
 * the enrollment request. It collects rather than reads once on purpose — a fresh install has no
 * token at start-up, so binding to the flow is what lets enrollment fire minutes later, when
 * pairing finally lands one.
 *
 * ⚠️ **The link flow is this object's own, and it is what makes the port live.** [startLink]
 * drives [pairingTransport] — the provisioning channel — and on success [adoptSession] persists
 * the token and publishes it, which is what lets `OverlayEnrollment` fire. Before this existed
 * nothing in the app ever called [publish], so `session` stayed null for the process's whole life
 * and enrollment could not run at all: the engine was complete and had no ignition.
 *
 * The ordinary HTTPS device-code flow is **not** re-declared here. Where the channel answers
 * [BoomioPairingResult.Unavailable] — no endpoint, the ingress switched off — this reports that
 * plainly rather than inventing a second transport. Mobile falls through to HTTPS on that answer
 * because mobile has one; this app does not, and swallowing it would leave the press doing
 * nothing with no message, which is the exact failure this change removes.
 */

/**
 * A linked companion session — **narrowed to the one field the overlay reads.**
 *
 * Mobile's version also carries `deviceId`, `userId` and `displayName`. None is referenced by any
 * of the 25 transplanted files, so none is re-declared: the overlay's only use is
 * `session?.token?.takeIf { it.isNotBlank() }`.
 */
internal data class BoomioSession(
    /** The `bs_ses_*` session, presented as `Authorization: Bearer <token>`. */
    val token: String,
)

/**
 * Obtains a session without going through the app's own HTTPS pairing flow.
 *
 * The overlay's implementation of this is `ChannelPairingTransport`, which runs the exchange over
 * the pre-tunnel provisioning channel; the host installs it into
 * [BoomioSessionRepository.pairingTransport].
 *
 * ⚠️ The distinction that matters to the overlay is between [BoomioPairingResult.Unavailable] and
 * [BoomioPairingResult.Failed], and it is load-bearing rather than cosmetic — see that type.
 */
internal interface BoomioPairingTransport {
    /**
     * Runs the exchange and returns once a session token exists.
     *
     * [onCode] is called as soon as a user code exists and a human has to approve it, so the
     * screen can render the code *while* the transport keeps polling. A transport that links
     * without ever needing a human simply never calls it.
     */
    suspend fun pair(
        deviceId: String,
        platform: String?,
        name: String?,
        onCode: (userCode: String, verificationUri: String?) -> Unit,
    ): BoomioPairingResult
}

/**
 * The outcome of a pairing attempt.
 *
 * ⚠️ **[Unavailable] and [Failed] must not be collapsed**, and the overlay is written around the
 * difference. `Unavailable` means *this transport cannot be used right now* — no endpoint, no
 * published key, the port refused, the server's switch off — and the caller must fall through to
 * its ordinary transport rather than report an error. Reporting it as a failure would turn an
 * optional path into a regression for every deployment that has not enabled the ingress.
 * `Failed` means the exchange *did* run and went wrong: a code was issued and a human may be
 * looking at it, so starting a second exchange would mint a code the first approver never sees.
 */
internal sealed interface BoomioPairingResult {
    /** The token is live. The caller owns persisting it and publishing the session. */
    data class Linked(
        val token: String,
        val userId: String?,
        val displayName: String?,
    ) : BoomioPairingResult

    /** Not usable right now. **Fall through to the other transport; do not report this.** */
    data object Unavailable : BoomioPairingResult

    /** The transport worked and the exchange failed. **Not** retried on another transport. */
    data class Failed(val message: String) : BoomioPairingResult
}

/**
 * What the link flow is doing, for the settings row to render.
 *
 * ⚠️ **Mobile's `Linking` state is deliberately not declared.** It means "self-approve took, so
 * the poll is expected to finish with no human involved", and there is no self-approve on a TV —
 * so this flow goes straight from [Starting] to [AwaitingApproval]. A state that can never be
 * entered is a branch no test can reach; if it is ever needed, the exhaustive `when`s below will
 * point at every site that has to change.
 */
internal sealed interface BoomioLinkState {
    data object Idle : BoomioLinkState
    data object Starting : BoomioLinkState

    /**
     * A device code is live and waiting for a **human** to approve it at [verificationUri].
     *
     * ⚠️ **Published from the transport's `onCode` callback, not after it returns.** The exchange
     * keeps polling underneath, so this is a *running* state even though everything it carries is
     * already known. Treating it as terminal — returning here, or letting the caller cancel —
     * would strand the flow at the exact moment it became useful. This is also why the UI must not
     * offer a "try again" on it: a second exchange would mint a second code the first approver
     * never sees.
     */
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String?,
    ) : BoomioLinkState

    data class Failed(val reason: BoomioLinkFailure) : BoomioLinkState
}

/**
 * Why a link attempt failed.
 *
 * ⚠️ **[Unreachable] is not [Start].** They are the same word to a log line and different words
 * to a person: `Start` means the exchange ran and went wrong, `Unreachable` means no provisioning
 * endpoint answered at all — off-LAN with no forward, or the ingress switched off. The second is
 * the expected answer on a deployment that never enabled the ingress, so it needs to say what to
 * check rather than repeating a generic failure at someone who cannot act on it.
 */
internal enum class BoomioLinkFailure {
    /** The exchange ran and went wrong. */
    Start,

    /** Nothing answered on the provisioning port. */
    Unreachable,

    /** This build registered no provisioning channel at all. */
    Unsupported,
}

internal object BoomioSessionRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var appContext: Context? = null

    private var linkJob: Job? = null

    private val _session = MutableStateFlow<BoomioSession?>(null)

    /**
     * The linked session, or null when the device has none.
     *
     * ⚠️ Collected, never read once. Enrollment binds to this flow so that a token arriving
     * *later* — from the pairing exchange, after start-up — still triggers enrollment.
     */
    val session: StateFlow<BoomioSession?> = _session.asStateFlow()

    private val _linkState = MutableStateFlow<BoomioLinkState>(BoomioLinkState.Idle)

    /** What the link flow is doing. */
    val linkState: StateFlow<BoomioLinkState> = _linkState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** The last exchange's own message, when [BoomioLinkState.Failed] alone is not specific enough. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * The transport used to pair when the ordinary HTTPS flow cannot reach the server.
     *
     * Set by the overlay's provisioning wiring (`OverlayProvisioning`); null means the overlay
     * has no pairing path of its own and the host's normal flow is the only one.
     */
    var pairingTransport: BoomioPairingTransport? = null

    /** The session token, or null when there is no session. */
    fun bearerToken(): String? = _session.value?.token

    /** Records the linked session. Called by the host's pairing/enrollment path. */
    fun publish(next: BoomioSession?) {
        _session.value = next
    }

    /**
     * Hydrates a persisted token and keeps the context the link flow needs.
     *
     * ⚠️ **A persisted token is the whole of "this TV is already linked".** Without this the
     * session would be per-process, so every reboot would un-enroll the TV — and enrollment, which
     * fires off this flow, would never run on a cold start at all.
     *
     * Idempotent, like every other overlay singleton: `onCreate` can run again after a
     * configuration-forced restart, and a second call must not clobber a session linked since.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        BoomioSessionStorage.initialize(context)
        if (_session.value != null) return
        val token = BoomioSessionStorage.loadSessionToken() ?: return
        _session.value = BoomioSession(token = token)
    }

    /** True while an exchange is in flight. */
    val linking: Boolean
        get() = when (_linkState.value) {
            is BoomioLinkState.Starting,
            is BoomioLinkState.AwaitingApproval,
            -> true

            else -> false
        }

    /**
     * Starts the link flow: the provisioning channel, and only that.
     *
     * ⚠️ **A press while an exchange is already in flight is a no-op, not a second exchange.**
     * A second `pair.request` would mint a code the first approver never sees.
     */
    fun startLink() {
        if (linking) return
        linkJob?.cancel()
        _error.value = null
        linkJob = scope.launch {
            _linkState.value = BoomioLinkState.Starting
            try {
                linkOverProvisioningChannel()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _error.value = error.message
                _linkState.value = BoomioLinkState.Failed(BoomioLinkFailure.Start)
            }
        }
    }

    /**
     * Runs the exchange over the provisioning channel.
     *
     * ⚠️ **Nothing here marks the link as "over the channel".** `ChannelPairingTransport` calls
     * `OverlayProvisioning.markLinked()` itself on success, and that flag is what makes
     * `OverlayEnrollment` follow the channel for the enrollment that comes next. Setting it here
     * too would be a second source of truth for one fact — and the wrong one to own, since only
     * the transport knows which exchange actually succeeded.
     */
    private suspend fun linkOverProvisioningChannel() {
        val transport = pairingTransport
        val context = appContext
        if (transport == null || context == null) {
            _linkState.value = BoomioLinkState.Failed(BoomioLinkFailure.Unsupported)
            return
        }

        val metadata = currentDeviceClientMetadata(context)
        val result = try {
            transport.pair(
                deviceId = SyncClientIdentity(context).currentClientId(),
                platform = metadata.platform,
                name = metadata.deviceName,
            ) { userCode, verificationUri ->
                // The code is live and a human has to approve it. Published while the exchange is
                // still running, so the screen shows the code instead of a spinner.
                _linkState.value = BoomioLinkState.AwaitingApproval(userCode, verificationUri)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _error.value = error.message
            _linkState.value = BoomioLinkState.Failed(BoomioLinkFailure.Start)
            return
        }

        when (result) {
            is BoomioPairingResult.Linked -> adoptSession(result.token)

            is BoomioPairingResult.Unavailable ->
                _linkState.value = BoomioLinkState.Failed(BoomioLinkFailure.Unreachable)

            is BoomioPairingResult.Failed -> {
                _error.value = result.message
                _linkState.value = BoomioLinkState.Failed(BoomioLinkFailure.Start)
            }
        }
    }

    /**
     * The one place a link becomes a session.
     *
     * ⚠️ **Persist first, then publish.** A crash between the two leaves a token a cold start
     * recovers; the other order leaves a session that vanishes on the next launch and a server
     * that already believes this TV is linked — a TV that looks paired and behaves unpaired.
     */
    private fun adoptSession(token: String) {
        BoomioSessionStorage.saveSessionToken(token)
        _session.value = BoomioSession(token = token)
        _linkState.value = BoomioLinkState.Idle
    }

    /**
     * Cancels an in-flight link.
     *
     * A code already issued is deliberately **not** revoked: the server holds it until it expires,
     * and the exchange this cancels is the only thing that could still have redeemed it.
     */
    fun cancelLink() {
        linkJob?.cancel()
        linkJob = null
        _linkState.value = BoomioLinkState.Idle
    }

    /**
     * Drops the local session.
     *
     * Local only — there is no unpair call, because the enrollment peer on the server is keyed by
     * this device's WireGuard public key and a TV that re-links keeps the same key. Clearing the
     * token is enough to stop enrollment authenticating, which is the whole effect wanted here.
     */
    fun unlink() {
        linkJob?.cancel()
        linkJob = null
        BoomioSessionStorage.clearSessionToken()
        _session.value = null
        _linkState.value = BoomioLinkState.Idle
    }
}
