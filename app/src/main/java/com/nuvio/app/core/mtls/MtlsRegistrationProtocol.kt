package com.nuvio.app.core.mtls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire contract with `bsc`'s `POST /api/overlay/cert` — and, more usefully, what each reply
 * means for whether trying again could possibly help.
 *
 * This is `docs/mtls-plan.md` P2.3's transport half. It is split from the client that uses it for
 * the reason the rest of this package is split the same way: every field name here is a contract
 * with the route, and a rename on that side would otherwise surface only as a device that silently
 * never gets a certificate — a symptom indistinguishable from a server that is merely slow. As free
 * functions these can be tested against literal JSON, which is what pins the contract.
 *
 * ── What the request is, and what it deliberately is not ─────────────────────
 * One field. ⚠️ **The client does not send its name, and must not.** The route derives the CN it
 * expects from the *verified session* — `peerNameFor(session.device_id)` — and hands that to the
 * validator, so nothing in the body can influence it. A client that also sent a name would be
 * sending a value the server is right to ignore, and a client that sent one *instead* of using it
 * as the CN would be registering nothing.
 *
 * ── Why the reply needs five cases and not two ───────────────────────────────
 * The distinction that matters is not success/failure but **whether re-sending the identical bytes
 * can ever work**, because the caller's response to each is different:
 *
 * | reply | meaning | what the caller does |
 * |---|---|---|
 * | `Registered` | the file exists; live at the edge within one TTL | nothing further, ever |
 * | `Refused` | these bytes are wrong (`cn_mismatch`, `eku`, …) | stop; re-sending cannot help |
 * | `Revoked` | an operator removed this device | stop; and do not rebuild clients for it |
 * | `Unauthenticated` | the session is gone | stop *now*; a later run with a session may work |
 * | `Deferred` | no answer, a 5xx, a 429 | retry — the tunnel may simply not be up yet |
 *
 * ⚠️ Collapsing `Refused` into `Deferred` is the tempting simplification and it is the expensive
 * one: a certificate with the wrong CN fails identically on every attempt, so a client that treats
 * it as transient spends the device's whole rate-limit budget (10/min, per device) rediscovering
 * the same permanent fact.
 */

internal val mtlsRegistrationJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class CertRegistrationRequestDto(val cert: String)

/** The `200` from a successful registration. The dates are informational — nothing consults them. */
@Serializable
internal data class CertRegistrationReplyDto(
    val status: String? = null,
    val name: String? = null,
    @SerialName("fingerprint_sha256") val fingerprintSha256: String? = null,
    @SerialName("not_before") val notBefore: String? = null,
    @SerialName("not_after") val notAfter: String? = null,
)

/** Every non-2xx from the route. `code` is present only on the validator's refusals. */
@Serializable
internal data class CertRegistrationErrorDto(val error: String? = null, val code: String? = null)

internal sealed interface CertRegistrationAck {
    data class Registered(val name: String?, val fingerprintSha256: String?) : CertRegistrationAck

    /**
     * The validator refused these bytes. [code] is the machine-readable half and is what a log
     * reader greps for — `cn_mismatch` means this device's CN is not the name the server assigned
     * it, `eku` means the certificate is missing `clientAuth`, and so on.
     */
    data class Refused(val reason: String, val code: String?) : CertRegistrationAck

    /** Terminal: an operator revoked this device, so no certificate will be accepted for it. */
    data class Revoked(val reason: String) : CertRegistrationAck

    /** The session is not (or no longer) valid. A later run with a session may succeed. */
    data class Unauthenticated(val reason: String) : CertRegistrationAck

    /** No answer, a 5xx, or a 429. The same request may well succeed shortly. */
    data class Deferred(val reason: String) : CertRegistrationAck
}

/**
 * Classify the route's reply.
 *
 * The status code carries the meaning and the body carries the detail, so this reads both and
 * prefers the server's own words when it sent any — the route's messages are written for the
 * client's author ("certificate CN must be \"device-…\""), which is more useful in a log than
 * anything this side could invent.
 */
internal fun decodeCertRegistration(status: Int, body: String): CertRegistrationAck {
    val error = runCatching { mtlsRegistrationJson.decodeFromString<CertRegistrationErrorDto>(body) }
        .getOrNull()

    if (status in 200..299) {
        val dto = runCatching { mtlsRegistrationJson.decodeFromString<CertRegistrationReplyDto>(body) }
            .getOrNull()
        // A 2xx that is not the documented shape is NOT treated as success: believing a reply we
        // could not parse would record a registration that may not exist, and the next handshake
        // would fail with nothing in the log to explain it.
        return if (dto?.status == "registered") {
            CertRegistrationAck.Registered(name = dto.name, fingerprintSha256 = dto.fingerprintSha256)
        } else {
            CertRegistrationAck.Deferred("the server's registration reply was not understood")
        }
    }

    return when (status) {
        401 -> CertRegistrationAck.Unauthenticated(error?.error ?: "the server session is not valid")
        403 -> CertRegistrationAck.Revoked(error?.error ?: "this device is revoked")
        // Its own bucket, 10 per device per minute, so a retry storm here is self-inflicted.
        429 -> CertRegistrationAck.Deferred(error?.error ?: "the server is rate limiting registration")
        // 400 is the validator's refusal, and 404/405/413 would mean the client is pointed at the
        // wrong route — also not fixable by re-sending.
        in 400..499 -> CertRegistrationAck.Refused(
            reason = error?.error ?: "the server refused the certificate",
            code = error?.code,
        )
        else -> CertRegistrationAck.Deferred(
            error?.error ?: "the server could not record the certificate ($status)",
        )
    }
}
