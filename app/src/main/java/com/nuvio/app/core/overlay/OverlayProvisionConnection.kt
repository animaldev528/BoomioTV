package com.nuvio.app.core.overlay

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val TAG = "OverlayProvision"

/**
 * One provisioning connection: the handshake, and then sealed request/response.
 *
 * **What this is for.** A freshly-installed app off the home LAN has no session and no
 * address to dial — and cannot bring the tunnel up, because WireGuard has no "unknown peer"
 * mode: the server must already hold this device's public key. This channel is that first
 * contact, on the same port number as the tunnel but the other protocol (TCP here, UDP
 * there), and it is what turns "a device that has never reached this server" into "a device
 * holding a session and a tunnel config".
 *
 * ⚠️ **There is no TLS, and that is a decision rather than an omission.** The certificate
 * would have to be for the name whose DuckDNS TXT record *is* the discovery tuple, and
 * DuckDNS gives each name exactly one TXT slot — so a DNS-01 challenge and the tuple would
 * fight over the same record. The proof is X25519 instead: the device derives a shared secret
 * against the server's published provisioning key, and **a reply it can decrypt is a reply
 * from the box that key belongs to**. See [OverlayProvisionCrypto] and the design doc.
 *
 * ⚠️ The device is *not* authenticated by its key, and must not be. It is authorised by the
 * human-approved user code, which is what makes a credential-less first contact possible.
 *
 * ## Order of operations, which is the security property
 *
 * 1. Send `HELLO` — the device's **public** key and a fresh nonce. Nothing secret, nothing
 *    identifying; the server learns only that some device wants to talk.
 * 2. Read `HELLO_ACK` or `DENIED`. The server's nonce arrives in the clear (it is not a
 *    secret — it is half the salt), together with a sealed proof.
 * 3. **Derive and open the proof before sending anything else.** A proof that does not open
 *    means the peer is not the box `ppk` names, and nothing further may be sent to it. This
 *    is the branch that matters, and it is why the failure is a distinct exception rather
 *    than a generic error.
 */
internal class OverlayProvisionConnection private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    /**
     * ⚠️ **Inherited from the handshake, not created here.** The handshake's read can complete
     * more than one frame, and anything past `HELLO_ACK` belongs to this connection — a fresh
     * reader here would drop it and then block on a reply the server had already sent. The
     * reader and the queue are passed through rather than re-made.
     */
    private val reader: ProvisionFrameReader,
    private val buffered: ArrayDeque<ByteArray>,
    private val sealer: OverlayProvisionSealer,
    /** The server's proof, already opened and validated. See [open]. */
    val proof: ProvisionMessage.HelloProof,
) : Closeable {

    private var sentMessages = 0

    /**
     * Sends one sealed message and returns the server's reply.
     *
     * Throws [OverlayProvisionException.ServerError] when the reply is `{t:'error'}`, so a
     * caller cannot accidentally treat a refusal as a result — every operation here has a
     * happy path that is not an error object, and the two are easy to conflate when the error
     * arrives as an ordinary message.
     */
    suspend fun request(message: JsonObject): ProvisionMessage = withContext(Dispatchers.IO) {
        if (++sentMessages > PROVISION_MAX_MESSAGES) {
            throw OverlayProvisionException.ProtocolFault("too many messages on one connection")
        }

        val sealed = sealer.seal(message.toString().toByteArray(Charsets.UTF_8))
        writeFrame(byteArrayOf(PROVISION_OP_MSG) + sealed)

        when (val reply = readSealedMessage()) {
            is ProvisionMessage.Error -> throw OverlayProvisionException.ServerError(
                code = reply.code,
                message = reply.message,
            )
            else -> reply
        }
    }

    /** `pair.request` — asks for a user code for the owner to approve at [ProvisionMessage.PairCode]. */
    suspend fun pairRequest(deviceId: String, platform: String?, name: String?): ProvisionMessage =
        request(
            buildJsonObject {
                put("t", JsonPrimitive("pair.request"))
                put("device_id", JsonPrimitive(deviceId))
                if (platform != null) put("platform", JsonPrimitive(platform))
                if (name != null) put("name", JsonPrimitive(name))
            }
        )

    /** `pair.poll` — has the owner approved yet? */
    suspend fun pairPoll(deviceCode: String): ProvisionMessage =
        request(
            buildJsonObject {
                put("t", JsonPrimitive("pair.poll"))
                put("device_code", JsonPrimitive(deviceCode))
            }
        )

    /** `enroll` — records this device's public key against the session's identity. */
    suspend fun enroll(sessionToken: String): ProvisionMessage =
        request(
            buildJsonObject {
                put("t", JsonPrimitive("enroll"))
                put("session_token", JsonPrimitive(sessionToken))
            }
        )

    /** `enroll.status` — has the host adapter allocated the address yet? */
    suspend fun enrollStatus(sessionToken: String): ProvisionMessage =
        request(
            buildJsonObject {
                put("t", JsonPrimitive("enroll.status"))
                put("session_token", JsonPrimitive(sessionToken))
            }
        )

    /**
     * `cert.register` — files this device's client certificate in the edge's allow-list.
     *
     * ⚠️ **No device id and no name.** The server derives the CN it expects from the *verified
     * session* (`peerNameFor(session.device_id)`), so a name sent from here would be a value the
     * server is right to ignore — the same rule the HTTPS route follows. The session token is the
     * whole identity, exactly as it is for [enroll].
     *
     * ⚠️ **The certificate must be the same name the session maps to**, and `name` in an
     * `enroll.ready` is that name: a certificate minted for any other CN is refused with
     * `cn_mismatch` rather than quietly accepted, which is what binds a self-signed certificate to
     * an identity the server computed rather than one the device asserted.
     */
    suspend fun certRegister(sessionToken: String, certPem: String): ProvisionMessage =
        request(
            buildJsonObject {
                put("t", JsonPrimitive("cert.register"))
                put("session_token", JsonPrimitive(sessionToken))
                put("cert", JsonPrimitive(certPem))
            }
        )

    /**
     * `policy.get` — the security policy the server currently records.
     *
     * ⚠️ **The `t` is a guess; the committed server half serves this over HTTP only.** See
     * `ChannelSecurityPolicyApi` for why the channel is nonetheless the client's only authenticated
     * pull path. A server that does not understand this answers `{t:'error'}` (raised as
     * [OverlayProvisionException.ServerError]) or faults on the unknown type — both of which the
     * caller treats as "no answer", leaving the cached policy in force.
     */
    suspend fun securityPolicy(sessionToken: String): ProvisionMessage =
        request(
            buildJsonObject {
                put("t", JsonPrimitive("policy.get"))
                put("session_token", JsonPrimitive(sessionToken))
            }
        )

    override fun close() {
        // Best-effort throughout: closing happens on both the success and every failure
        // path, and a throw here would replace whatever the caller was actually reporting.
        runCatching { socket.close() }
    }

    // ── the wire ────────────────────────────────────────────────────────────────────────────

    private fun writeFrame(payload: ByteArray) {
        try {
            output.write(encodeProvisionFrame(payload))
            output.flush()
        } catch (e: Exception) {
            throw OverlayProvisionException.TransportFailed("could not send: ${e.message}", e)
        }
    }

    /**
     * The next whole frame, reading from the socket only when the buffer is empty.
     *
     * ⚠️ **Not "read until a frame arrives".** One `read` can complete several frames, and
     * looping until a frame is available would discard the ones already in hand — for this
     * protocol that means losing the reply and then blocking on a server that is waiting for
     * the next request.
     */
    private fun nextFrame(): ByteArray {
        while (true) {
            buffered.removeFirstOrNull()?.let { return it }
            val chunk = ByteArray(4096)
            val read = try {
                input.read(chunk)
            } catch (e: Exception) {
                throw OverlayProvisionException.TransportFailed("could not read: ${e.message}", e)
            }
            if (read < 0) {
                throw OverlayProvisionException.TransportFailed("the server closed the connection")
            }
            buffered.addAll(reader.feed(chunk.copyOf(read)))
        }
    }

    /** Reads one frame and opens its sealed payload. */
    private fun readSealedMessage(): ProvisionMessage {
        val payload = nextFrame()
        if (payload.isEmpty() || payload[0] != PROVISION_OP_MSG) {
            throw OverlayProvisionException.ProtocolFault(
                "expected a sealed message, got opcode 0x${(payload.firstOrNull() ?: 0).toString(16)}"
            )
        }
        if (payload.size < 1 + PROVISION_TAG_BYTES) {
            throw OverlayProvisionException.ProtocolFault("sealed message is too short to hold a tag")
        }
        val plaintext = sealer.open(payload.copyOfRange(1, payload.size))
        return decodeProvisionMessage(plaintext)
    }

    companion object {

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        /**
         * Dials [host]:[port], performs the handshake, and returns a connection that has
         * already proven the server's identity.
         *
         * [devicePrivateKeyBase64] is this device's WireGuard private key and
         * [serverPublicKeyBase64] is the `ppk` from the discovery record. Both are needed
         * before a byte is sent, so both are validated up front — a malformed `ppk` should
         * fail as a clear configuration error, not as an unopenable handshake that looks
         * like an impostor.
         *
         * [socketFactory] is a seam for tests only; production connects with a plain socket.
         *
         * ⚠️ **[connectTimeoutMs] exists because the right deadline for a connect depends on who
         * is dialling, and this client has two callers with opposite answers.** The enrollment
         * calls dial an address the device has already proven reachable, so [CONNECT_TIMEOUT_MS]
         * is a backstop. The *pairing* call dials the address the discovery record publishes —
         * which is the server's WAN address, and which on the home LAN does not answer at all
         * (hairpin is off) — so it wants a deadline short enough that the fallback to the
         * transport that *does* work at home is not a visible stall. Baking one number in would
         * force one of the two to be wrong.
         */
        suspend fun open(
            host: String,
            port: Int,
            devicePrivateKeyBase64: String,
            devicePublicKeyBase64: String,
            serverPublicKeyBase64: String,
            crypto: OverlayProvisionCrypto = GomobileProvisionCrypto,
            socketFactory: (String, Int) -> Socket = { h, p -> Socket() },
            connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        ): OverlayProvisionConnection = withContext(Dispatchers.IO) {
            val socket = try {
                socketFactory(host, port).apply {
                    // The server's own handshake timer is 10s, so matching it means a dead
                    // peer surfaces as our timeout with our message rather than as its
                    // abrupt close.
                    connect(InetSocketAddress(host, port), connectTimeoutMs)
                    soTimeout = READ_TIMEOUT_MS
                    // ⚠️ Frames here are tiny and strictly alternating. Without this, Nagle
                    // holds a request behind the previous segment's ACK and every round trip
                    // in the flow pays for it — the pairing poll loop most of all.
                    tcpNoDelay = true
                }
            } catch (e: Exception) {
                throw OverlayProvisionException.TransportFailed("could not reach $host:$port: ${e.message}", e)
            }

            try {
                handshake(socket, devicePrivateKeyBase64, devicePublicKeyBase64, serverPublicKeyBase64, crypto)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }

        private fun handshake(
            socket: Socket,
            devicePrivateKeyBase64: String,
            devicePublicKeyBase64: String,
            serverPublicKeyBase64: String,
            crypto: OverlayProvisionCrypto,
        ): OverlayProvisionConnection {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val devicePublicKey = decodeWireGuardKey(devicePublicKeyBase64)
                ?: throw OverlayProvisionException.ProtocolFault(
                    "this device's overlay public key is not a valid key"
                )
            val clientNonce = newClientNonce()

            val hello = byteArrayOf(PROVISION_OP_HELLO) + devicePublicKey + clientNonce
            try {
                output.write(encodeProvisionFrame(hello))
                output.flush()
            } catch (e: Exception) {
                throw OverlayProvisionException.TransportFailed("could not send HELLO: ${e.message}", e)
            }

            // ── the reply, and the one branch that matters ──────────────────────────────
            val reader = ProvisionFrameReader()
            val buffered = ArrayDeque<ByteArray>()
            val reply = firstFrameFrom(input, reader, buffered)

            when (reply.firstOrNull()) {
                PROVISION_OP_DENIED -> {
                    // Cleartext, necessarily: there is no key yet, which is exactly why this
                    // frame carries a fixed operator-facing reason and nothing about this
                    // device. Retrying cannot help — provisioning is switched off.
                    val reason = reply.copyOfRange(1, reply.size).toString(Charsets.UTF_8)
                    Log.i(TAG, "provisioning refused: $reason")
                    throw OverlayProvisionException.ServerDenied(reason)
                }

                PROVISION_OP_HELLO_ACK -> Unit

                else -> throw OverlayProvisionException.ProtocolFault(
                    "expected HELLO_ACK, got opcode 0x${(reply.firstOrNull() ?: 0).toString(16)}"
                )
            }

            if (reply.size < 1 + PROVISION_NONCE_BYTES + PROVISION_TAG_BYTES) {
                throw OverlayProvisionException.ProtocolFault("HELLO_ACK is too short")
            }
            val serverNonce = reply.copyOfRange(1, 1 + PROVISION_NONCE_BYTES)
            val sealedProof = reply.copyOfRange(1 + PROVISION_NONCE_BYTES, reply.size)

            val sealer = crypto.session(
                devicePrivateKeyB64 = devicePrivateKeyBase64,
                serverPublicKeyB64 = serverPublicKeyBase64,
                clientNonceB64 = clientNonce.toProvisionBase64(),
                serverNonceB64 = serverNonce.toProvisionBase64(),
            )

            // ⚠️ **This is the trust decision.** If the open fails, the peer did not hold the
            // provisioning key behind `ppk`, and the connection is abandoned without another
            // byte — no session token, no device id, nothing. Everything above this line is
            // public; everything below it is secret.
            //
            // The two steps are classified apart because they mean different things: an
            // unopenable proof is *"this is not the server"*, while a proof that opens and is
            // then not decodable is *"this is the server, and one of us is broken"*. Both stop
            // the connection, but only the first is an impostor.
            val plaintext = try {
                sealer.open(sealedProof)
            } catch (e: OverlayProvisionException.HandshakeFailed) {
                throw e
            } catch (e: Exception) {
                // A crypto implementation is not obliged to know our exception types, so an
                // unopenable frame is translated here rather than being allowed to surface as
                // whatever the JNI layer happened to throw.
                throw OverlayProvisionException.HandshakeFailed(
                    "the server's proof did not authenticate — this is not the server the advertised key belongs to",
                    e,
                )
            }

            val proof = try {
                decodeProvisionMessage(plaintext)
            } catch (e: OverlayProvisionException.ProtocolFault) {
                throw OverlayProvisionException.HandshakeFailed(
                    "the server's proof was not a hello: ${e.message}", e
                )
            }

            if (proof !is ProvisionMessage.HelloProof) {
                throw OverlayProvisionException.HandshakeFailed(
                    "the server's proof was not a hello"
                )
            }
            if (proof.server != PROVISION_SERVER_NAME) {
                // Free to check and cheap to be wrong about: the name is in the proof
                // precisely so a client can bind the key it verified to the service it meant
                // to reach.
                throw OverlayProvisionException.HandshakeFailed(
                    "the server identified itself as '${proof.server}'"
                )
            }
            if (proof.protocolVersion != PROVISION_PROTOCOL_VERSION) {
                throw OverlayProvisionException.HandshakeFailed(
                    "the server speaks protocol v${proof.protocolVersion}, this build speaks v$PROVISION_PROTOCOL_VERSION"
                )
            }

            Log.i(TAG, "provisioning handshake complete with $serverPublicKeyBase64")
            return OverlayProvisionConnection(
                socket = socket,
                input = input,
                output = output,
                reader = reader,
                buffered = buffered,
                sealer = sealer,
                proof = proof,
            )
        }

        /**
         * The first frame on a fresh connection, before a [OverlayProvisionConnection] exists.
         *
         * Everything the read completed goes into [buffered] and the head is taken from there,
         * so frames that arrived alongside `HELLO_ACK` survive into the connection instead of
         * being read and thrown away.
         */
        private fun firstFrameFrom(
            input: InputStream,
            reader: ProvisionFrameReader,
            buffered: ArrayDeque<ByteArray>,
        ): ByteArray {
            while (true) {
                buffered.removeFirstOrNull()?.let { return it }
                val chunk = ByteArray(4096)
                val read = try {
                    input.read(chunk)
                } catch (e: Exception) {
                    throw OverlayProvisionException.TransportFailed("could not read the handshake: ${e.message}", e)
                }
                if (read < 0) {
                    throw OverlayProvisionException.TransportFailed(
                        "the server closed the connection during the handshake"
                    )
                }
                buffered.addAll(reader.feed(chunk.copyOf(read)))
            }
        }
    }
}
