package com.nuvio.app.core.overlay

import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The pre-tunnel provisioning protocol's wire format: framing, opcodes, and the message
 * shapes.
 *
 * ⚠️ **Nothing here may import `android.*`.** These are the parts where a mistake is
 * *silent* — a length read with the wrong endianness, an opcode compared as a signed byte,
 * a field renamed on one side only — and they are tested in `androidHostTest` without
 * Robolectric, which is only possible while this file stays pure JVM. The socket and the
 * logging live in [OverlayProvisionConnection].
 *
 * The other half of the contract is `bsc/lib/overlay-provision-channel.js`. Where a field
 * name looks oddly spelled (`user_code`, `poll_after_ms`) it is because the server spells
 * it that way, and the names are a wire format rather than a style choice.
 */

// ── framing ─────────────────────────────────────────────────────────────────────────────────

/**
 * A frame is `[4-byte big-endian length][payload]`, and **the length covers the opcode** —
 * the payload includes its own leading type byte.
 *
 * 4 KiB bounds what one connection can make either side buffer. The largest real message is
 * an enrollment reply, well under 1 KiB.
 */
internal const val PROVISION_MAX_FRAME = 4096

/** The server's own loop guard. A whole flow needs four messages. */
internal const val PROVISION_MAX_MESSAGES = 200

internal const val PROVISION_KEY_BYTES = 32
internal const val PROVISION_NONCE_BYTES = 32
internal const val PROVISION_TAG_BYTES = 16

internal const val PROVISION_OP_HELLO: Byte = 0x01
internal const val PROVISION_OP_HELLO_ACK: Byte = 0x02
internal const val PROVISION_OP_DENIED: Byte = 0x03
internal const val PROVISION_OP_MSG: Byte = 0x04

/** The `v` in the server's proof, and the only version this client speaks. */
internal const val PROVISION_PROTOCOL_VERSION = 1

/** The `server` field in the proof. Checked, because it is free to check. */
internal const val PROVISION_SERVER_NAME = "boomio"

/** Prefixes [payload] with its big-endian length. */
internal fun encodeProvisionFrame(payload: ByteArray): ByteArray {
    require(payload.isNotEmpty()) { "a zero-length frame is a protocol fault, not a message" }
    val out = ByteArray(4 + payload.size)
    out[0] = (payload.size ushr 24).toByte()
    out[1] = (payload.size ushr 16).toByte()
    out[2] = (payload.size ushr 8).toByte()
    out[3] = payload.size.toByte()
    payload.copyInto(out, 4)
    return out
}

/**
 * Accumulates whatever a socket hands over and yields whole frames.
 *
 * TCP gives no message boundaries, so one `read` can carry three frames or half of one. This
 * is deliberately a *buffer*, not a "read until I have a frame" loop — a loop would block on
 * a partial frame while the rest of the frames already in hand went unprocessed.
 *
 * Throws [OverlayProvisionException.ProtocolFault] on a length that cannot be a frame. The
 * server does the same on its side, and for the same reason: refusing before allocating
 * means a peer cannot ask for a large buffer by sending a length and nothing else.
 */
internal class ProvisionFrameReader {

    private var buffer = ByteArray(0)

    /** Feeds [chunk] and returns every frame it completed. */
    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isNotEmpty()) {
            buffer = if (buffer.isEmpty()) chunk else buffer + chunk
        }
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= 4) {
            val length = readUInt32BigEndian(buffer, offset)
            if (length == 0L || length > PROVISION_MAX_FRAME) {
                throw OverlayProvisionException.ProtocolFault("bad frame length $length")
            }
            // Narrowed only after the range check above, which is what makes it safe: an
            // unreduced length is exactly the value that would arrive here negative.
            val payloadLength = length.toInt()
            if (buffer.size - offset < 4 + payloadLength) break
            frames += buffer.copyOfRange(offset + 4, offset + 4 + payloadLength)
            offset += 4 + payloadLength
        }
        if (offset > 0) buffer = buffer.copyOfRange(offset, buffer.size)
        return frames
    }

    /** How many bytes of a frame are held but incomplete. Diagnostics only. */
    val pendingBytes: Int get() = buffer.size
}

/**
 * Reads the length prefix as an unsigned 32-bit big-endian value.
 *
 * ⚠️ **Widened through `Long`, not `Int`.** A length of `0xFFFFFFFF` read into an `Int` is
 * `-1`, which passes a `> MAX_FRAME` check and then asks for a negative-sized array. The
 * server's counterpart reads it unsigned because Node has no choice; this is the Kotlin
 * spelling of the same thing.
 */
internal fun readUInt32BigEndian(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 4) {
        value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
    }
    return value
}

/** A fresh 32-byte client nonce: the client's half of the key schedule's salt. */
internal fun newClientNonce(): ByteArray {
    val bytes = ByteArray(PROVISION_NONCE_BYTES)
    SecureRandom().nextBytes(bytes)
    return bytes
}

@OptIn(ExperimentalEncodingApi::class)
internal fun ByteArray.toProvisionBase64(): String = Base64.Default.encode(this)

// ── failures ────────────────────────────────────────────────────────────────────────────────

/**
 * Everything this channel can go wrong with, split by **what the caller should do about it**
 * rather than by where it was thrown.
 *
 * That split is the point. A device that cannot decrypt the server's proof and a device whose
 * Wi-Fi dropped both look like "enrollment failed" in a log, and they are opposite situations:
 * one must be reported to the owner as a possible impostor, the other is worth retrying in
 * ten seconds. A caller that cannot tell them apart will get one of those two wrong.
 */
internal sealed class OverlayProvisionException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * The server closed the door before any key existed, carrying a reason in cleartext.
     *
     * ⚠️ **Not an error the device caused.** This is the `prov` kill switch, and the reason
     * string is fixed operator-facing text — never anything derived from this device. It
     * means provisioning is deliberately off, so retrying is pointless until an operator
     * turns it back on.
     */
    class ServerDenied(val reason: String) :
        OverlayProvisionException("The server refused provisioning: $reason")

    /**
     * The bytes are not this protocol — a bad frame length, an unexpected opcode, a reply
     * that is not JSON. Retrying the same way will produce the same result.
     */
    class ProtocolFault(message: String) : OverlayProvisionException(message)

    /**
     * ⚠️ **The trust anchor failed.** The server's reply did not authenticate under the key
     * derived from the `ppk` the discovery record advertised, which means the peer does not
     * hold the provisioning key that `ppk` names.
     *
     * **Terminal, and it must not be retried and must not be answered.** Whatever is on the
     * other end of this socket is not the box whose key was published, and the whole reason
     * this handshake exists is to find that out *before* a session token crosses.
     */
    class HandshakeFailed(message: String, cause: Throwable? = null) :
        OverlayProvisionException(message, cause)

    /** The socket failed. Ordinary, and worth retrying. */
    class TransportFailed(message: String, cause: Throwable? = null) :
        OverlayProvisionException(message, cause)

    /** The server understood the request and answered `{t:'error'}`. [code] is its own. */
    class ServerError(val code: String, message: String) :
        OverlayProvisionException(message)
}

// ── the messages ────────────────────────────────────────────────────────────────────────────

/** What the server can say, once the channel is sealed. */
internal sealed interface ProvisionMessage {

    /** The server's proof, sealed under the pre-tunnel key. Its decodability *is* the auth. */
    data class HelloProof(val protocolVersion: Int, val server: String) : ProvisionMessage

    data class PairCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    ) : ProvisionMessage

    data object PairPending : ProvisionMessage
    data object PairExpired : ProvisionMessage

    data class PairOk(
        val token: String,
        val sessionToken: String,
        val displayName: String?,
    ) : ProvisionMessage

    data class EnrollPending(
        val name: String?,
        val reason: String?,
        val pollAfterMs: Long,
    ) : ProvisionMessage

    /**
     * An assignment, in the shape the rest of the app already consumes.
     *
     * [lanEndpoint] and [wanEndpoint] are the two discovery-name endpoints — the same pair the
     * DuckDNS tuple publishes — and they are carried through rather than dropped because they
     * are what the names tier prefers over the single [OverlayAssignment.endpoint] once the
     * device is off the home LAN. Null here means the server did not offer one, not that it is
     * empty; the ladder's existing fallback covers that.
     */
    data class EnrollReady(
        val name: String?,
        val assignment: OverlayAssignment,
        val lanEndpoint: String?,
        val wanEndpoint: String?,
    ) : ProvisionMessage

    /**
     * The edge's allow-list now holds this device's certificate — `docs/mtls-plan.md` §13.2.
     *
     * The dates are informational and nothing consults them: the edge's verifier compares raw DER
     * and never checks validity (§11.2), so refusing an expired certificate here would invent a rule
     * the edge does not enforce.
     */
    data class CertRegistered(
        val name: String?,
        val fingerprintSha256: String?,
        val notBefore: String?,
        val notAfter: String?,
    ) : ProvisionMessage

    /**
     * The registration was understood and refused. [code] is the machine-readable half and is what a
     * log reader greps — `cn_mismatch`, `eku`, `revoked`, `write_failed`, …
     *
     * ⚠️ **Deliberately not [Error].** [OverlayProvisionConnection.request] raises
     * [OverlayProvisionException.ServerError] for `{t:'error'}`, which would flatten every refusal
     * into one exception — and the distinction that matters here is not success/failure but
     * **whether re-sending the identical bytes can ever help**. `write_failed` can; `cn_mismatch`
     * and `revoked` cannot. A bare error would lose exactly that.
     */
    data class CertRefused(val code: String, val message: String) : ProvisionMessage

    /**
     * The security policy the server currently records — a reply to `policy.get`.
     *
     * ⚠️ **Named `Policy` and not `SecurityPolicy` deliberately.** The payload type is the top-level
     * [SecurityPolicy], and a nested member of the same name would shadow it inside this sealed
     * interface, so the field here would silently refer to the wrong type.
     */
    data class Policy(val policy: SecurityPolicy) : ProvisionMessage

    data class Error(val code: String, val message: String) : ProvisionMessage
}

/**
 * The message DTO.
 *
 * ⚠️ **One flat DTO with a `t` discriminator, rather than a polymorphic hierarchy**, and the
 * reason is the server's own: it deliberately spells these fields identically to the HTTPS
 * routes (`GET /api/v1/auth/device/poll`), so that a device which reaches its first session
 * over this channel and every later one over HTTPS can share one parser. A `@Serializable`
 * sealed hierarchy would key off the same `t` and require `classDiscriminator` to be set to
 * `"t"` on both sides of a contract this client does not own — and getting that wrong fails
 * as a decode error on a field the server never sees. A flat DTO plus an explicit `when` is
 * the same parsing with nothing hidden, and it matches how [decodeEnrollStatus] already reads
 * the HTTPS route's reply.
 */
@Serializable
internal data class ProvisionMessageDto(
    val t: String,
    // the proof
    val v: Int? = null,
    val server: String? = null,
    // pair.code
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Long? = null,
    // pair.ok — names mirror the HTTPS poll route on purpose
    val status: String? = null,
    val token: String? = null,
    @SerialName("session_token") val sessionToken: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    // enroll.pending / enroll.ready
    val name: String? = null,
    @SerialName("poll_after_ms") val pollAfterMs: Long? = null,
    val reason: String? = null,
    val address: String? = null,
    @SerialName("server_pubkey") val serverPubkey: String? = null,
    val endpoint: String? = null,
    @SerialName("lan_endpoint") val lanEndpoint: String? = null,
    @SerialName("wan_endpoint") val wanEndpoint: String? = null,
    @SerialName("overlay_cidr") val overlayCidr: String? = null,
    val mtu: Int? = null,
    // cert.registered — the same three spellings `POST /api/overlay/cert` returns, so a device that
    // reached its certificate over this channel and one that re-registered over HTTPS parse the
    // same reply body.
    @SerialName("fingerprint_sha256") val fingerprintSha256: String? = null,
    @SerialName("not_before") val notBefore: String? = null,
    @SerialName("not_after") val notAfter: String? = null,
    // policy — the four booleans as one nested object, exactly the shape bsm stores under
    // `security_policy` in system_kv (routes/security.js), so the two halves cannot drift.
    @SerialName("security_policy") val securityPolicy: JsonObject? = null,
    // error / cert.refused
    val code: String? = null,
    val message: String? = null,
)

/**
 * `explicitNulls = false` so a request built from nullable fields omits them rather than
 * sending `"name": null`. The server treats an absent key and a null one the same way, so
 * this is about the wire being readable rather than about behaviour.
 */
internal val overlayProvisionJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** The server's poll hint, when it does not send one. Matches [OVERLAY_ENROLL_DEFAULT_POLL_MS]. */
internal const val PROVISION_DEFAULT_POLL_MS = 3_000L

/**
 * Decodes a sealed plaintext into a message.
 *
 * Throws [OverlayProvisionException.ProtocolFault] for anything that is not a message this
 * client understands — including a `t` it has never heard of. Being strict here is what makes
 * a server-side rename show up as a clear fault rather than as a flow that quietly never
 * advances, which is the failure this whole area is most prone to.
 */
internal fun decodeProvisionMessage(plaintext: ByteArray): ProvisionMessage {
    val text = plaintext.toString(Charsets.UTF_8)
    val dto = try {
        overlayProvisionJson.decodeFromString<ProvisionMessageDto>(text)
    } catch (e: Exception) {
        throw OverlayProvisionException.ProtocolFault("the server's message was not understood: ${e.message}")
    }

    return when (dto.t) {
        "hello" -> ProvisionMessage.HelloProof(
            protocolVersion = dto.v ?: 0,
            server = dto.server.orEmpty(),
        )

        "pair.code" -> ProvisionMessage.PairCode(
            deviceCode = dto.deviceCode.orEmpty(),
            userCode = dto.userCode.orEmpty(),
            verificationUri = dto.verificationUri.orEmpty(),
            expiresInSeconds = dto.expiresIn ?: 0L,
            intervalSeconds = dto.interval ?: 5L,
        )

        "pair.pending" -> ProvisionMessage.PairPending

        // ⚠️ `pair.expired` carries no fields at all — the server sends `{t:'pair.expired'}`.
        // It means the device code is gone from Redis: either the user never approved it, or
        // it was approved and the session has already been collected by an earlier poll. The
        // second case is why this must NOT be reported as "you did not approve it".
        "pair.expired" -> ProvisionMessage.PairExpired

        "pair.ok" -> ProvisionMessage.PairOk(
            token = dto.token.orEmpty(),
            sessionToken = dto.sessionToken ?: dto.token.orEmpty(),
            displayName = dto.displayName,
        )

        "enroll.pending" -> ProvisionMessage.EnrollPending(
            name = dto.name,
            reason = dto.reason,
            pollAfterMs = dto.pollAfterMs ?: PROVISION_DEFAULT_POLL_MS,
        )

        "enroll.ready" -> ProvisionMessage.EnrollReady(
            name = dto.name,
            assignment = assignmentOf(dto),
            lanEndpoint = dto.lanEndpoint,
            wanEndpoint = dto.wanEndpoint,
        )

        "cert.registered" -> ProvisionMessage.CertRegistered(
            name = dto.name,
            fingerprintSha256 = dto.fingerprintSha256,
            notBefore = dto.notBefore,
            notAfter = dto.notAfter,
        )

        // ⚠️ Answered as a MESSAGE, not as the `error` that [OverlayProvisionConnection.request]
        // turns into an exception. See [ProvisionMessage.CertRefused] for why the code has to
        // survive to the caller.
        "cert.refused" -> ProvisionMessage.CertRefused(
            code = dto.code.orEmpty(),
            message = dto.message ?: "The server refused the certificate.",
        )

        // ⚠️ A `policy` message with no parseable policy is a ProtocolFault rather than a null: the
        // server answered the question, so silently leaving the caller on the cached policy would
        // make a server-side regression look like a network miss.
        "policy" -> ProvisionMessage.Policy(
            securityPolicyFrom(dto.securityPolicy)
                ?: throw OverlayProvisionException.ProtocolFault(
                    "the server's policy message carried no policy"
                ),
        )

        // The message is `ProvisionMessage.Error`; the *exception* raised for it by the
        // connection is `OverlayProvisionException.ServerError`. Two names for two things.
        "error" -> ProvisionMessage.Error(
            code = dto.code.orEmpty(),
            message = dto.message ?: "The server refused the request.",
        )

        else -> throw OverlayProvisionException.ProtocolFault("unknown message type '${dto.t}'")
    }
}

/**
 * The assignment inside an `enroll.ready`.
 *
 * ⚠️ **A `ready` with a hole in it is refused, not defaulted.** These three are what points
 * the tunnel at a server and gives it an identity there; supplying a placeholder for a
 * missing one produces a tunnel that comes up and silently drops every return packet,
 * because the server has no peer for an address it never allocated. That failure is
 * indistinguishable from a broken tunnel and is far more expensive to diagnose than a
 * refusal here.
 */
private fun assignmentOf(dto: ProvisionMessageDto): OverlayAssignment {
    val address = dto.address
    val serverKey = dto.serverPubkey
    val endpoint = dto.endpoint
    if (address.isNullOrBlank() || serverKey.isNullOrBlank() || endpoint.isNullOrBlank()) {
        throw OverlayProvisionException.ProtocolFault("the server's assignment is incomplete")
    }
    return OverlayAssignment(
        address = address,
        serverPublicKeyBase64 = serverKey,
        endpoint = endpoint,
        overlayCidr = dto.overlayCidr ?: OVERLAY_ENROLL_DEFAULT_CIDR,
        mtu = dto.mtu ?: OVERLAY_ENROLL_DEFAULT_MTU,
        // ⚠️ **Not part of "does this assignment work", and carried anyway.** A name the server
        // omitted is a name we do not have, not a reason to refuse an otherwise complete
        // assignment — the tunnel is usable without it and the mTLS half reads blank as
        // "nothing to mint against". Deliberately outside the blank-check above for exactly
        // that reason: refusing here would cost a device its tunnel over a field it does not
        // need in order to route packets.
        deviceName = dto.name.orEmpty(),
    )
}
