package com.nuvio.app.features.boomio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * ⚠️ **Nothing calls [BoomioSessionRepository.publish] yet, and that is deliberate** — an inert
 * port adds no call sites. Until the host publishes, [session] is null, `bearerToken()` returns
 * null, and the overlay's enrollment finds no credential and stops — which is inert and correct,
 * since nothing starts collecting while `BOOMIO_OVERLAY_ADDR` is blank.
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

internal object BoomioSessionRepository {

    private val _session = MutableStateFlow<BoomioSession?>(null)

    /**
     * The linked session, or null when the device has none.
     *
     * ⚠️ Collected, never read once. Enrollment binds to this flow so that a token arriving
     * *later* — from the device-code poll, minutes after start-up — still triggers enrollment.
     */
    val session: StateFlow<BoomioSession?> = _session.asStateFlow()

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
}
