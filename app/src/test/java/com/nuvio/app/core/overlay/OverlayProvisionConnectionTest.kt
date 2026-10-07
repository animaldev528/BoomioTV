package com.nuvio.app.core.overlay

import android.app.Application
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The provisioning channel, against a real TCP server on loopback.
 *
 * **What is real and what is faked.** The sockets, the framing, the handshake sequencing and
 * every failure path are the production ones. The *cryptography* is faked, and deliberately:
 * the real thing is X25519 + HKDF + AES-GCM inside the `overlaywg` AAR, which cannot load on
 * the host JVM, and duplicating it in Kotlin to test it here would be a second implementation
 * of the one thing that must never have one. It is proven where it lives — by
 * `overlaywg/provision_test.go`, whose `TestProvisionSessionMatchesServerVectors` opens a frame
 * the real Node server sealed and seals a byte-identical one back.
 *
 * So this file answers the questions the Go test cannot: does the client frame correctly, does
 * it read a coalesced reply, does it send the nonce it derived from, and does it **stop** when
 * the proof does not authenticate. [FakeProvisionCrypto] stands in for the AEAD with an
 * order-checking prefix, which is enough to make every one of those observable.
 *
 * Robolectric rather than a plain host test for one reason: [OverlayProvisionConnection] logs,
 * and `android.util.Log` throws when unmocked. The sockets are real regardless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayProvisionConnectionTest {

    private val servers = mutableListOf<FakeProvisionServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    // ── the happy path ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the handshake completes and the server's proof is validated`() = runBlocking {
        val server = startServer()
        newConnection(server).use { connection ->
            assertEquals(PROVISION_PROTOCOL_VERSION, connection.proof.protocolVersion)
            assertEquals(PROVISION_SERVER_NAME, connection.proof.server)
        }
        server.await()
        assertNull(server.failure)
    }

    @Test
    fun `the hello carries the device's public key and a fresh nonce, in that order`() = runBlocking {
        val server = startServer()
        newConnection(server).use { }

        val hello = server.awaitHello()
        assertEquals(65, hello.size, "HELLO is opcode + 32-byte key + 32-byte nonce")
        assertEquals(PROVISION_OP_HELLO, hello[0])
        assertContentEquals(
            decodeWireGuardKey(DEVICE_PUBLIC_KEY),
            hello.copyOfRange(1, 33),
            "the server must receive the device's real public key — it is what enrollment records",
        )
        assertTrue(
            hello.copyOfRange(33, 65).any { it != 0.toByte() },
            "the client nonce must not be all zeroes",
        )
    }

    /**
     * ⚠️ The salt is `client_nonce ‖ server_nonce`, and the ordering is a genuine interop
     * hazard: both sides derive it without transmitting it, so getting the order backwards
     * produces two correct implementations that cannot talk to each other. The derivation
     * itself is pinned by the Go interop test; what is pinned here is that the connection
     * hands the client's own nonce and the server's echoed one to the crypto in that order.
     */
    @Test
    fun `the crypto is given the client nonce and the server's echoed nonce, client first`() = runBlocking {
        val server = startServer()
        val crypto = RecordingProvisionCrypto()
        newConnection(server, crypto = crypto).use { }

        val args = assertNotNull(crypto.recorded)
        assertEquals(DEVICE_PRIVATE_KEY, args.devicePrivateKeyB64)
        assertEquals(SERVER_PUBLIC_KEY, args.serverPublicKeyB64)
        assertContentEquals(
            server.awaitHello().copyOfRange(33, 65),
            args.clientNonceB64.fromBase64(),
            "the client nonce handed to the key schedule must be the one sent in HELLO",
        )
        assertContentEquals(
            server.serverNonce,
            args.serverNonceB64.fromBase64(),
            "the server nonce must be the one the server actually sent",
        )
    }

    @Test
    fun `a pair request returns the user code the owner approves`() = runBlocking {
        val server = startServer { request ->
            assertEquals("pair.request", request.string("t"))
            assertEquals("device-123", request.string("device_id"))
            """{"t":"pair.code","device_code":"dc-1","user_code":"ABC123",
                "verification_uri":"https://bsm.example/tv?code=ABC123",
                "expires_in":300,"interval":5}"""
        }

        newConnection(server).use { connection ->
            val reply = assertIs<ProvisionMessage.PairCode>(
                connection.pairRequest(deviceId = "device-123", platform = "android", name = null)
            )
            assertEquals("dc-1", reply.deviceCode)
            assertEquals("ABC123", reply.userCode)
            assertEquals("https://bsm.example/tv?code=ABC123", reply.verificationUri)
            assertEquals(300L, reply.expiresInSeconds)
            assertEquals(5L, reply.intervalSeconds)
        }
        server.await()
    }

    @Test
    fun `a full flow runs four round trips on one connection`() = runBlocking {
        // The real sequence: ask for a code, poll until approved, enroll, poll for the address.
        val server = startServer { request ->
            when (request.string("t")) {
                "pair.request" -> """{"t":"pair.code","device_code":"dc-1","user_code":"ABC123",
                    "verification_uri":"https://bsm.example/tv","expires_in":300,"interval":5}"""
                "pair.poll" -> """{"t":"pair.ok","status":"ok","token":"bs_ses_1",
                    "session_token":"bs_ses_1","id":"u-1","username":"kyle","email":null,
                    "display_name":"Kyle"}"""
                "enroll" -> """{"t":"enroll.pending","name":"phone","poll_after_ms":3000}"""
                "enroll.status" -> """{"t":"enroll.ready","name":"phone","address":"10.77.0.7",
                    "server_pubkey":"$SERVER_PUBLIC_KEY","endpoint":"192.168.68.65:51820",
                    "lan_endpoint":"boomio.local:51820","wan_endpoint":"boomio.duckdns.org:51820",
                    "overlay_cidr":"10.77.0.0/24","mtu":1420}"""
                else -> error("unexpected request ${request.string("t")}")
            }
        }

        newConnection(server).use { connection ->
            assertIs<ProvisionMessage.PairCode>(
                connection.pairRequest("device-123", "android", "Kyle's phone")
            )
            val ok = assertIs<ProvisionMessage.PairOk>(connection.pairPoll("dc-1"))
            assertEquals("bs_ses_1", ok.sessionToken)

            assertIs<ProvisionMessage.EnrollPending>(connection.enroll("bs_ses_1"))

            val ready = assertIs<ProvisionMessage.EnrollReady>(
                connection.enrollStatus("bs_ses_1")
            )
            assertEquals("10.77.0.7", ready.assignment.address)
            assertEquals("10.77.0.7/32", ready.assignment.localCidr)
            assertEquals("192.168.68.65:51820", ready.assignment.endpoint)
            assertEquals(1420, ready.assignment.mtu)
        }
        server.await()
    }

    @Test
    fun `the assignment carries both discovery endpoints through`() = runBlocking {
        // These are what the names tier prefers over the single endpoint once the device is
        // off the home LAN, so dropping them here would silently cost a working roam path.
        val server = startServer { enrollReadyReply() }
        newConnection(server).use { connection ->
            val ready = assertIs<ProvisionMessage.EnrollReady>(connection.enroll("bs_ses_1"))
            assertEquals("boomio.local:51820", ready.lanEndpoint)
            assertEquals("boomio.duckdns.org:51820", ready.wanEndpoint)
        }
        server.await()
    }

    // ── the trust anchor ────────────────────────────────────────────────────────────────────

    /**
     * ⚠️ The test the whole design rests on. A server that does not hold the provisioning key
     * behind `ppk` cannot seal a proof this client can open, and the client must abandon the
     * connection rather than send anything to it.
     */
    @Test
    fun `a server that cannot seal the proof is refused before anything secret is sent`() = runBlocking {
        val server = startServer(cryptoTag = "impostor")

        val failure = assertFailsWith<OverlayProvisionException.HandshakeFailed> {
            newConnection(server).use { }
        }
        assertTrue(
            failure.message.orEmpty().contains("did not authenticate"),
            "the failure must say the proof did not authenticate, not something generic",
        )

        server.await()
        assertEquals(
            0,
            server.requestsReceived,
            "nothing may be sent to a peer whose proof did not open",
        )
    }

    @Test
    fun `a server that names itself something else is refused`() = runBlocking {
        val server = startServer(serverName = "not-boomio")
        assertFailsWith<OverlayProvisionException.HandshakeFailed> { newConnection(server).use { } }
        server.await()
    }

    @Test
    fun `a server speaking a different protocol version is refused`() = runBlocking {
        val server = startServer(protocolVersion = 99)
        assertFailsWith<OverlayProvisionException.HandshakeFailed> { newConnection(server).use { } }
        server.await()
    }

    @Test
    fun `a proof that opens but is not a hello is refused`() = runBlocking {
        val server = startServer(proofBody = """{"t":"pair.ok","token":"x"}""")
        assertFailsWith<OverlayProvisionException.HandshakeFailed> { newConnection(server).use { } }
        server.await()
    }

    // ── the server's refusals ───────────────────────────────────────────────────────────────

    /**
     * `DENIED` is the `prov` kill switch. It arrives in cleartext and cannot be mistaken for
     * anything else — which is why the reason must be surfaced rather than swallowed.
     */
    @Test
    fun `a denied handshake surfaces the operator's reason`() = runBlocking {
        val server = startServer(denyReason = "provisioning is closed")

        val failure = assertFailsWith<OverlayProvisionException.ServerDenied> {
            newConnection(server).use { }
        }
        assertEquals("provisioning is closed", failure.reason)
        server.await()
    }

    @Test
    fun `an error message becomes a ServerError carrying the server's own code`() = runBlocking {
        val server = startServer { """{"t":"error","code":"unauthorized","message":"valid session_token required"}""" }

        newConnection(server).use { connection ->
            val failure = assertFailsWith<OverlayProvisionException.ServerError> {
                connection.enroll("expired")
            }
            assertEquals("unauthorized", failure.code)
            assertEquals("valid session_token required", failure.message)
        }
        server.await()
    }

    @Test
    fun `an unknown message type is a protocol fault, not a silent no-op`() = runBlocking {
        // The failure mode this prevents is the expensive one: a server-side rename that the
        // client ignores produces a flow that simply never advances and says nothing.
        val server = startServer { """{"t":"pair.maybe"}""" }

        newConnection(server).use { connection ->
            assertFailsWith<OverlayProvisionException.ProtocolFault> {
                connection.pairPoll("dc-1")
            }
        }
        server.await()
    }

    // ── framing ─────────────────────────────────────────────────────────────────────────────

    /**
     * ⚠️ **TCP has no message boundaries.** A server is free to write `HELLO_ACK` and the first
     * reply in one segment, and a client that reads one frame per `read()` and discards the
     * rest will hang waiting for a reply it already received — with the server sitting idle,
     * waiting for a request that will never come. The client reads a *stream*, not messages.
     */
    @Test
    fun `a reply coalesced with the handshake is not lost`() = runBlocking {
        // See the server's coalescing branch: the second frame is unsolicited, because no
        // conforming server can follow HELLO_ACK on the wire. The byte pattern is the test.
        val server = startServer(coalesceAckAndFirstReply = true) { enrollReadyReply() }

        newConnection(server).use { connection ->
            val ready = assertIs<ProvisionMessage.EnrollReady>(connection.enroll("bs_ses_1"))
            assertEquals("10.77.0.7", ready.assignment.address)
        }
        server.await()
    }

    @Test
    fun `a frame length beyond the cap is refused before it is allocated`() = runBlocking {
        val server = startServer(rawFirstReply = oversizedFrameHeader())
        assertFailsWith<OverlayProvisionException.ProtocolFault> { newConnection(server).use { } }
        server.await()
    }

    @Test
    fun `a closed connection during the handshake is a transport failure`() = runBlocking {
        val server = startServer(closeAfterHello = true)
        assertFailsWith<OverlayProvisionException.TransportFailed> { newConnection(server).use { } }
        server.await()
    }

    @Test
    fun `a server that closes mid-flow is a transport failure, not a protocol fault`() = runBlocking {
        // The distinction matters to the caller: a dropped connection is worth retrying, and
        // a protocol fault is not.
        val server = startServer(closeInsteadOfReplying = true)
        newConnection(server).use { connection ->
            assertFailsWith<OverlayProvisionException.TransportFailed> {
                connection.pairPoll("dc-1")
            }
        }
        server.await()
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private suspend fun newConnection(
        server: FakeProvisionServer,
        crypto: OverlayProvisionCrypto = FakeProvisionCrypto(EXPECTED_CRYPTO_TAG),
    ): OverlayProvisionConnection = OverlayProvisionConnection.open(
        host = server.host,
        port = server.port,
        devicePrivateKeyBase64 = DEVICE_PRIVATE_KEY,
        devicePublicKeyBase64 = DEVICE_PUBLIC_KEY,
        serverPublicKeyBase64 = SERVER_PUBLIC_KEY,
        crypto = crypto,
    )

    private fun startServer(
        cryptoTag: String = "right",
        serverName: String = PROVISION_SERVER_NAME,
        protocolVersion: Int = PROVISION_PROTOCOL_VERSION,
        proofBody: String? = null,
        denyReason: String? = null,
        coalesceAckAndFirstReply: Boolean = false,
        rawFirstReply: ByteArray? = null,
        closeAfterHello: Boolean = false,
        closeInsteadOfReplying: Boolean = false,
        respond: (JsonObject) -> String = { enrollReadyReply() },
    ): FakeProvisionServer = FakeProvisionServer(
        cryptoTag = cryptoTag,
        serverName = serverName,
        protocolVersion = protocolVersion,
        proofBody = proofBody,
        denyReason = denyReason,
        coalesceAckAndFirstReply = coalesceAckAndFirstReply,
        rawFirstReply = rawFirstReply,
        closeAfterHello = closeAfterHello,
        closeInsteadOfReplying = closeInsteadOfReplying,
        respond = respond,
    ).also { servers += it; it.start() }

    private fun enrollReadyReply(): String =
        """{"t":"enroll.ready","name":"phone","address":"10.77.0.7",
            "server_pubkey":"$SERVER_PUBLIC_KEY","endpoint":"192.168.68.65:51820",
            "lan_endpoint":"boomio.local:51820","wan_endpoint":"boomio.duckdns.org:51820",
            "overlay_cidr":"10.77.0.0/24","mtu":1420}"""

    /** A well-formed frame header claiming more than [PROVISION_MAX_FRAME] bytes. */
    private fun oversizedFrameHeader(): ByteArray = byteArrayOf(0x00, 0x00, 0x20, 0x00)

    private companion object {
        /** A real WireGuard-shaped keypair. The values are public test material, not secrets. */
        const val DEVICE_PRIVATE_KEY = "AAIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eH2A="
        const val DEVICE_PUBLIC_KEY = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw="
        const val SERVER_PUBLIC_KEY = "YFpyXSpK3+6xop4X7dYhwbdZPujNvESsbEq24vgF0jw="

        /**
         * What the *client* expects the peer to hold. It stands in for the `ppk` read out of the
         * discovery record, so it is fixed for the life of a test and never follows the server's
         * tag — which is the whole point. A client handed the server's own tag would agree with
         * an impostor, and the test that exists to catch exactly that would pass.
         */
        const val EXPECTED_CRYPTO_TAG = "right"
    }
}

// ── the fake server ─────────────────────────────────────────────────────────────────────────

/**
 * A real `ServerSocket` on loopback that speaks the provisioning protocol.
 *
 * It uses the production [ProvisionFrameReader] for its framing, because that is the one part
 * of the server side worth sharing — a fake with its own private framing could agree with a
 * buggy client about a wrong format.
 */
internal class FakeProvisionServer(
    val cryptoTag: String,
    private val serverName: String,
    private val protocolVersion: Int,
    private val proofBody: String?,
    private val denyReason: String?,
    private val coalesceAckAndFirstReply: Boolean,
    private val rawFirstReply: ByteArray?,
    private val closeAfterHello: Boolean,
    private val closeInsteadOfReplying: Boolean,
    private val respond: (JsonObject) -> String,
) {

    val serverNonce: ByteArray = ByteArray(PROVISION_NONCE_BYTES) { (it + 1).toByte() }

    /**
     * A stand-in request, for the one server mode that answers before being asked.
     *
     * [respond] switches on the request's `t`, and in that mode no request exists to hand it —
     * the client has not sent one yet. Any `t` the responder knows will do; the assertion is
     * about *which frame* comes back, not about what was asked.
     */
    private val syntheticRequest: JsonObject = buildJsonObject { put("t", JsonPrimitive("enroll")) }

    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val finished = CountDownLatch(1)
    private val failures = AtomicReference<Throwable?>(null)
    private val hellos = AtomicReference<ByteArray?>(null)
    private val requestCount = AtomicInteger(0)

    val host: String get() = "127.0.0.1"
    val port: Int get() = socket.localPort
    val failure: Throwable? get() = failures.get()
    val requestsReceived: Int get() = requestCount.get()

    fun start() {
        thread(isDaemon = true, name = "fake-provision-server") {
            try {
                socket.accept().use { connection -> serve(connection) }
            } catch (t: Throwable) {
                failures.set(t)
            } finally {
                runCatching { socket.close() }
                finished.countDown()
            }
        }
    }

    fun stop() {
        runCatching { socket.close() }
        finished.await(2, TimeUnit.SECONDS)
    }

    /** Blocks until the server thread has finished, then fails the test if it threw. */
    fun await() {
        assertTrue(finished.await(5, TimeUnit.SECONDS), "the fake server never finished")
        failures.get()?.let { throw AssertionError("the fake server failed", it) }
    }

    fun awaitHello(): ByteArray = assertNotNull(
        hellos.get(), "the server never received a HELLO"
    )

    private fun serve(connection: Socket) {
        connection.soTimeout = 5_000
        val input = connection.getInputStream()
        val output = connection.getOutputStream()
        val reader = ProvisionFrameReader()

        val hello = nextFrame(input, reader)
        hellos.set(hello)

        denyReason?.let {
            output.write(
                encodeProvisionFrame(byteArrayOf(PROVISION_OP_DENIED) + it.toByteArray())
            )
            output.flush()
            return
        }

        rawFirstReply?.let {
            output.write(it)
            output.flush()
            return
        }

        if (closeAfterHello) return

        val sealer = FakeSealer(cryptoTag)
        val proof = proofBody
            ?: """{"t":"hello","v":$protocolVersion,"server":"$serverName"}"""
        val ack = byteArrayOf(PROVISION_OP_HELLO_ACK) +
            serverNonce +
            sealer.seal(proof.toByteArray())

        if (coalesceAckAndFirstReply) {
            // One write, two frames — the segment a one-frame-per-read client loses.
            //
            // ⚠️ The reply here is *unsolicited*, which no conforming server sends: this client
            // reads HELLO_ACK before it writes anything, so on a real connection nothing can
            // follow the ack yet. That is deliberate. What is under test is the byte pattern —
            // two frames arriving in one read — because the failure it guards against is silent:
            // the reader's second frame is dropped on the floor and the client then blocks
            // forever on a reply the server has already sent.
            val reply = encodeProvisionFrame(
                byteArrayOf(PROVISION_OP_MSG) +
                    sealer.seal(respond(syntheticRequest).toByteArray())
            )
            output.write(encodeProvisionFrame(ack) + reply)
            output.flush()
            // Stay readable so the client's own request lands somewhere rather than on a socket
            // the server has already closed, and stop when the client is done.
            while (true) {
                try {
                    nextFrame(input, reader)
                } catch (_: Exception) {
                    return
                }
                requestCount.incrementAndGet()
            }
        }

        output.write(encodeProvisionFrame(ack))
        output.flush()

        while (true) {
            val payload = try {
                nextFrame(input, reader)
            } catch (_: Exception) {
                return
            }
            requestCount.incrementAndGet()
            if (closeInsteadOfReplying) return
            val opened = sealer.open(payload.copyOfRange(1, payload.size))
            val reply = respond(parse(opened))
            output.write(
                encodeProvisionFrame(byteArrayOf(PROVISION_OP_MSG) + sealer.seal(reply.toByteArray()))
            )
            output.flush()
        }
    }

    private fun parse(bytes: ByteArray): JsonObject =
        overlayProvisionJson.decodeFromString(bytes.toString(Charsets.UTF_8))

    private fun nextFrame(input: InputStream, reader: ProvisionFrameReader): ByteArray {
        while (true) {
            val chunk = ByteArray(4096)
            val read = input.read(chunk)
            if (read < 0) throw IllegalStateException("client closed")
            reader.feed(chunk.copyOf(read)).firstOrNull()?.let { return it }
        }
    }
}

/** Where a [JsonObject]'s string field went, for assertions that read well. */
internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.content

// ── the fakes ───────────────────────────────────────────────────────────────────────────────

/**
 * The AEAD's stand-in: an order-checking prefix instead of a tag.
 *
 * It is not pretending to be cryptography. What it does is make the *ordering* visible — the
 * counter is in the prefix, so a frame sealed at the wrong counter or opened out of sequence
 * fails exactly as a real one would — and make [cryptoTag] decide whether two sides agree,
 * which is what lets a test stand up a server that cannot authenticate.
 */
internal class FakeProvisionCrypto(private val tag: String) : OverlayProvisionCrypto {
    override fun session(
        devicePrivateKeyB64: String,
        serverPublicKeyB64: String,
        clientNonceB64: String,
        serverNonceB64: String,
    ): OverlayProvisionSealer = FakeSealer(tag)
}

internal class FakeSealer(private val tag: String) : OverlayProvisionSealer {

    private var send = 0
    private var recv = 0

    override fun seal(plaintext: ByteArray): ByteArray =
        prefix(send++) + plaintext

    override fun open(sealed: ByteArray): ByteArray {
        val expected = prefix(recv)
        // ⚠️ A mismatch is thrown as HandshakeFailed, which is what the real sealer does with a
        // failed AEAD tag. It must not be an `assertContentEquals`: that throws AssertionError,
        // which is an `Error` and not an `Exception`, so it would sail straight past the
        // connection's classification and reach the caller as neither an authentication failure
        // nor a transport one — a mistake this fake made once, and the test below caught.
        if (sealed.size < expected.size ||
            !sealed.copyOfRange(0, expected.size).contentEquals(expected)
        ) {
            throw OverlayProvisionException.HandshakeFailed(
                "the frame did not authenticate under the expected key material"
            )
        }
        // The counter is deliberately NOT advanced on failure, matching the Go session: a
        // single bad frame must not desynchronise the rest of the connection.
        recv++
        return sealed.copyOfRange(expected.size, sealed.size)
    }

    override val sendCounter: Int get() = send
    override val recvCounter: Int get() = recv

    private fun prefix(counter: Int): ByteArray = "$tag|$counter|".toByteArray()
}

/** Records what the connection asked the key schedule for, so the ordering can be asserted. */
internal class RecordingProvisionCrypto : OverlayProvisionCrypto {

    data class Args(
        val devicePrivateKeyB64: String,
        val serverPublicKeyB64: String,
        val clientNonceB64: String,
        val serverNonceB64: String,
    )

    @Volatile
    var recorded: Args? = null

    override fun session(
        devicePrivateKeyB64: String,
        serverPublicKeyB64: String,
        clientNonceB64: String,
        serverNonceB64: String,
    ): OverlayProvisionSealer {
        recorded = Args(devicePrivateKeyB64, serverPublicKeyB64, clientNonceB64, serverNonceB64)
        return FakeSealer("right")
    }
}

// ── test-local helpers ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalEncodingApi::class)
private fun String.fromBase64(): ByteArray = Base64.Default.decode(this)
