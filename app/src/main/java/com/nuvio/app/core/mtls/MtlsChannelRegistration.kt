package com.nuvio.app.core.mtls

import android.util.Log
import com.nuvio.app.core.overlay.GomobileProvisionCrypto
import com.nuvio.app.core.overlay.OverlayProvisionConnection
import com.nuvio.app.core.overlay.OverlayProvisionCrypto
import com.nuvio.app.core.overlay.OverlayProvisionException
import com.nuvio.app.core.overlay.OverlayProvisioning
import com.nuvio.app.core.overlay.OverlayWgKeypair
import com.nuvio.app.core.overlay.ProvisionMessage
import com.nuvio.app.core.overlay.ProvisionTarget
import kotlinx.coroutines.CancellationException

/**
 * Certificate registration over the provisioning channel — `docs/mtls-plan.md` §13.2, the client half.
 *
 * ── Why this exists at all ────────────────────────────────────────────────
 * A device with no certificate **cannot complete a TLS handshake at a site that demands one**, so
 * `POST /api/overlay/cert` is unreachable for exactly the device that needs it: the bootstrap loop.
 * The channel is the one connection a device has *before* it has a tunnel or a certificate — the
 * same one that issued its session and its WireGuard config — so registering here closes the loop
 * without leaving an unenforced HTTPS surface open for anyone else to find.
 *
 * ⚠️ **This trades at the trust level enrollment already uses, and that is not a free win.** The
 * channel is TLS-free; it authenticates by WireGuard public key plus pairing code, not by
 * certificate. A peer that can register a certificate here could already enroll a tunnel here. What
 * it buys is that no *additional* hole exists, not that the hole is smaller.
 *
 * ── One connection per call, and why ──────────────────────────────────────
 * The same rule [com.nuvio.app.core.overlay.ChannelEnrollmentApi] follows, for the same reason: the
 * server reads the device's public key off *that connection's* HELLO, so a connection is a piece of
 * state that has to be right rather than a session worth keeping. There is no poll loop here at all
 * — registration is one request and one answer, because unlike enrollment there is nothing to wait
 * for on the host side.
 *
 * ── Why a second implementation of [MtlsRegistrationApi] rather than a branch inside the first ──
 * [MtlsRegistrationApi] is the seam [MtlsRegistrar] already plans, mints and retries against, and
 * every one of those decisions — including the whole of P2.6's "never silently generate a new key" —
 * is transport-independent. A branch inside [BscMtlsRegistrationApi] would put a transport choice
 * inside the HTTP client; a second implementation puts it one level up, where the two can be chosen
 * between by the same "null is the fallback" rule [com.nuvio.app.core.overlay.OverlayEnrollment]'s
 * api factory already uses.
 *
 * ── The reply table, and why it is not the HTTPS one ──────────────────────
 * The question every mapping below answers is the same one the HTTPS half's table answers —
 * **could re-sending these identical bytes ever help?** — but the channel's reply space is not the
 * HTTPS route's, so the answers are reached differently:
 *
 * | channel says | ack | why |
 * |---|---|---|
 * | `cert.registered` | `Registered` | the file exists; live at the edge within one TTL |
 * | `cert.refused` `write_failed` | `Deferred` | ⚠️ the one refusal worth retrying — the bytes were fine and the disk was not |
 * | `cert.refused` `revoked` | `Revoked` | an operator removed this device; terminal |
 * | `cert.refused` anything else | `Refused` | `cn_mismatch`, `eku`, … — the bytes are wrong |
 * | `error` `unauthorized` | `Unauthenticated` | the session is gone |
 * | no target / no keypair / socket died | `Deferred` | the tunnel may simply not be up yet |
 * | `HandshakeFailed` | `Refused("handshake_failed")` | ⚠️ see below |
 * | `ServerDenied` / `ProtocolFault` | `Refused(code)` | terminal until an operator acts |
 *
 * ⚠️ **The last two rows are the ones that needed a decision.** [OverlayProvisionException] calls
 * `HandshakeFailed` terminal — *"must not be retried and must not be answered"*, because whatever is
 * on the other end does not hold the published `ppk` — and it calls `ServerDenied` the operator's own
 * switch, which retrying cannot move. But [MtlsRegistrationApi] has no transport-terminal outcome;
 * its five cases were designed for an HTTP status space. Mapping either onto `Deferred` would put
 * them through [MtlsRegistrar]'s five-attempt retry loop, which is precisely what `HandshakeFailed`
 * forbids. So both are surfaced as `Refused` with a code naming the real cause: the *action* the
 * registrar takes is the correct one (stop), and the code keeps the two distinguishable in a log
 * from a genuine validator refusal of the certificate bytes.
 */
internal class ChannelMtlsRegistrationApi(
    private val token: String,
    private val target: suspend () -> ProvisionTarget? = OverlayProvisioning::target,
    private val keypair: () -> OverlayWgKeypair? = OverlayProvisioning::deviceKeypair,
    private val crypto: OverlayProvisionCrypto = GomobileProvisionCrypto,
) : MtlsRegistrationApi {

    /**
     * The target, resolved once per instance.
     *
     * The registrar retries up to five times, and a DNS walk plus a reachability probe in front of
     * each attempt would cost more than the attempts do. One instance covers one
     * [MtlsRegistrar.register] call, so this is one walk per registration rather than one per retry.
     */
    @Volatile
    private var resolved: ProvisionTarget? = null

    override suspend fun register(pem: String): CertRegistrationAck {
        val target = resolved ?: target()?.also { resolved = it }
            ?: return CertRegistrationAck.Deferred(NO_TARGET)
        val keypair = keypair() ?: return CertRegistrationAck.Deferred(NO_KEYPAIR)

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
            classify(connection.certRegister(token, pem))
        } catch (error: CancellationException) {
            throw error
        } catch (error: OverlayProvisionException.HandshakeFailed) {
            // Terminal, and it must not be answered — see the file header.
            Log.e(TAG, "Not registering: the provisioning peer is not the published key", error)
            CertRegistrationAck.Refused(
                reason = error.message ?: "the provisioning peer did not authenticate",
                code = CODE_HANDSHAKE_FAILED,
            )
        } catch (error: OverlayProvisionException.ServerDenied) {
            Log.i(TAG, "Not registering: provisioning is switched off")
            CertRegistrationAck.Refused(
                reason = error.message ?: "the server refused provisioning",
                code = CODE_PROVISIONING_CLOSED,
            )
        } catch (error: OverlayProvisionException.ProtocolFault) {
            // A peer that speaks a different protocol will speak it again on the next attempt.
            Log.w(TAG, "Not registering: protocol fault — ${error.message}")
            CertRegistrationAck.Refused(
                reason = error.message ?: "the provisioning channel answered something else",
                code = CODE_PROTOCOL_FAULT,
            )
        } catch (error: OverlayProvisionException.ServerError) {
            // The server's own words. Only `unauthorized` is its own bucket: the rest are answers
            // about this device's state that a later run may well get a different answer to.
            if (error.code == ERROR_UNAUTHORIZED) {
                CertRegistrationAck.Unauthenticated(error.message ?: "the server session is not valid")
            } else {
                CertRegistrationAck.Deferred(error.message ?: error.code)
            }
        } catch (error: Throwable) {
            // A connect timeout while the tunnel comes up is the expected first failure, so this is
            // `Deferred` and not an error: the registrar's retry window exists for exactly this.
            Log.w(TAG, "Provisioning registration call did not complete: ${error.message}")
            CertRegistrationAck.Deferred(UNREACHABLE)
        } finally {
            runCatching { opened?.close() }
        }
    }

    /**
     * Turns the sealed reply into the registrar's vocabulary.
     *
     * ⚠️ Split out so the mapping is readable as the table it is. A `when` inlined into the `try`
     * above would sit among five `catch` clauses, and the one arm worth noticing — `write_failed` is
     * `Deferred` rather than `Refused` — would be the hardest line in the method to find.
     */
    private fun classify(reply: ProvisionMessage): CertRegistrationAck = when (reply) {
        is ProvisionMessage.CertRegistered -> CertRegistrationAck.Registered(
            name = reply.name,
            fingerprintSha256 = reply.fingerprintSha256,
        )

        is ProvisionMessage.CertRefused -> when (reply.code) {
            CODE_REVOKED -> CertRegistrationAck.Revoked(reply.message)
            // ⚠️ The ONE refusal that is worth retrying, and the server says so itself: it accepted
            // the bytes and failed to write them. Treating it as terminal would leave a device that
            // hit a full disk permanently without a certificate, and the fix is a retry.
            CODE_WRITE_FAILED -> CertRegistrationAck.Deferred(reply.message)
            else -> CertRegistrationAck.Refused(reason = reply.message, code = reply.code)
        }

        // The server is not obliged to answer a `cert.register` with anything else, and a reply we
        // cannot read is not a registration. `Deferred` rather than `Refused` because the cause is
        // our own misreading of the wire, which the next attempt may not repeat.
        else -> CertRegistrationAck.Deferred(
            "the server answered the registration with '${reply::class.simpleName}'",
        )
    }

    private companion object {
        const val TAG = "BoomioMtls"

        const val NO_TARGET = "This network has no reachable provisioning address."
        const val NO_KEYPAIR = "This device has no overlay key pair yet."
        const val UNREACHABLE = "Could not reach the server over the provisioning channel."

        /** The server's `error` code for a session that is not (or no longer) valid. */
        const val ERROR_UNAUTHORIZED = "unauthorized"

        // The server's own codes, spelled as `lib/overlay-provision-channel.js` spells them.
        const val CODE_REVOKED = "revoked"
        const val CODE_WRITE_FAILED = "write_failed"

        // This client's own codes for the terminal states the channel reports as exceptions. They
        // cannot collide with the server's: none of these is a `cert.refused` code.
        const val CODE_HANDSHAKE_FAILED = "handshake_failed"
        const val CODE_PROVISIONING_CLOSED = "provisioning_closed"
        const val CODE_PROTOCOL_FAULT = "protocol_fault"
    }
}

/**
 * The registration api for this process, or **`null` when the channel is not the transport this
 * network needs** — the network reaching for the edge's HTTPS route instead.
 *
 * ⚠️ Returning null is the signal `MtlsRegistration.apiOrNull()` acts on by falling back to
 * [BscMtlsRegistrationApi], so the choice between the two transports is made in one place and the
 * caller needs no branch of its own. That is the same contract, written the same way, as
 * `OverlayProvisioning.enrollmentApi(token)` — and it is deliberately the *same* flag, because the
 * reason for choosing the channel is a property of the network rather than of the operation. A
 * device that needed the channel to obtain its session is a device whose `companionRestBaseUrl`
 * resolves to an address it cannot dial, and that is equally true of the enrollment call and this
 * one.
 *
 * ⚠️ This lives in `core.mtls` rather than in `OverlayProvisioning` so the dependency stays
 * one-way: `MtlsHandshakeWatch` already reads `localServerHosts` from `core.overlay`, and having
 * `core.overlay` construct an `MtlsRegistrationApi` would make the two packages mutually dependent.
 */
internal fun channelRegistrationApiOrNull(token: String): MtlsRegistrationApi? =
    if (OverlayProvisioning.isLinkedOverChannel()) ChannelMtlsRegistrationApi(token) else null
