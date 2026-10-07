package com.nuvio.app.core.overlay

import android.app.Application
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.nuvio.app.features.boomio.BoomioPairingResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two transports the provisioning channel is wired into, against a real TCP server.
 *
 * **What these tests are for.** `OverlayProvisionConnectionTest` proves the *protocol* — the
 * framing, the handshake, the trust anchor. This file proves the two **policies** built on top of
 * it, and every one of them is a decision that a passing protocol test cannot see:
 *
 * 1. **Pre-code failures fall through, post-code failures do not.** A server that is not offering
 *    provisioning must leave the link to the HTTPS transport; a server that already handed out a
 *    code must not, because a second exchange would mint a second code the first approver never
 *    sees. Both are `BoomioPairingResult` — and the difference between [Unavailable] and [Failed]
 *    is the difference between an optional new path and a regression.
 * 2. **§7 check 2.** The sealed handshake proves *who is speaking*, never *what they sent*, so an
 *    assignment whose `server_pubkey` is not the `pk` the discovery record publishes is refused
 *    even though the peer authenticated perfectly.
 * 3. **A refusal is not a dropped wire.** `enroll` reaches the server, the server answers
 *    `{t:'error'}` — that must surface as the server's own reason, not as "could not reach".
 *
 * The sockets, the framing and the sequencing are production code. The AEAD is
 * [FakeProvisionCrypto]'s order-checking prefix, for the reason the sibling file documents: the
 * real one is X25519 + AES-GCM inside the `overlaywg` AAR, which cannot load on a host JVM, and
 * re-implementing it here would create the second implementation that must never exist.
 *
 * Robolectric because both classes log through `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayProvisioningTest {

    private val servers = mutableListOf<ScriptedProvisionServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    // ── §7 check 2, in isolation ────────────────────────────────────────────────────────────

    @Test
    fun `an assignment whose server key is the record's pk is accepted`() {
        val assignment = assignmentOf(serverKey = TEST_TUNNEL_KEY)

        val poll = checkAssignmentServerKey(TEST_TUNNEL_KEY, assignment)

        val ready = assertIs<EnrollPoll.Ready>(poll)
        assertEquals("10.77.0.9", ready.assignment.address)
    }

    /**
     * ⚠️ **The check this whole path exists for.** The peer decrypted our request perfectly — it
     * really does hold `ppk` — and then handed back a config for a *different* server. Without
     * this refusal the tunnel comes up and silently routes through whoever answered.
     */
    @Test
    fun `an assignment whose server key is not the record's pk is refused`() {
        val poll = checkAssignmentServerKey(TEST_PPK, assignmentOf(serverKey = TEST_TUNNEL_KEY))

        val failed = assertIs<EnrollPoll.Failed>(poll)
        assertTrue(
            "discovery record" in failed.reason,
            "the refusal must name what disagreed, was: ${failed.reason}",
        )
    }

    /**
     * A record that predates the `pk` field has nothing to compare against, and inventing a
     * failure there would strand a device on an old publisher. Logged, not failed.
     */
    @Test
    fun `a record that publishes no pk cannot be checked and is not failed for it`() {
        val poll = checkAssignmentServerKey(null, assignmentOf(serverKey = TEST_TUNNEL_KEY))

        assertIs<EnrollPoll.Ready>(poll)
    }

    // ── pairing over the channel ────────────────────────────────────────────────────────────

    @Test
    fun `pair publishes the code, polls, and links on approval`() = runBlocking {
        val server = startServer { request ->
            when (request.string("t")) {
                "pair.request" -> pairCodeReply()
                "pair.poll" -> """{"t":"pair.ok","status":"approved","token":"bs_ses_1",
                    "session_token":"bs_ses_1","display_name":"Kyle"}""".compact()
                else -> error("unexpected request ${request.string("t")}")
            }
        }

        val codes = mutableListOf<Pair<String, String?>>()
        val result = transport(server).pair("device-1", "android", "Kyle's phone") { user, uri ->
            codes += user to uri
        }

        val linked = assertIs<BoomioPairingResult.Linked>(result)
        assertEquals("bs_ses_1", linked.token)
        assertEquals("Kyle", linked.displayName)
        // ⚠️ Null on purpose. `pair.ok` carries `token` and `session_token` holding the same
        // session value, and the HTTPS flow's `id` — the one real user identifier — has no
        // counterpart here. A value in this field would be an account id invented from a token.
        assertNull(linked.userId, "the channel has no user id to report, and must not fake one")

        // The explicit type argument is load-bearing: the collected pairs are `String?`-valued
        // (the server may omit `verification_uri`), and an inferred `List<Pair<String, String>>`
        // would not unify with what the callback collects.
        assertEquals(
            listOf<Pair<String, String?>>("WXYZ-1234" to "https://bsm.tracemonkey.org/pair"),
            codes,
        )

        server.throwIfFailed()
        val requests = server.requests
        assertEquals("pair.request", requests[0].string("t"))
        assertEquals("device-1", requests[0].string("device_id"))
        assertEquals("pair.poll", requests[1].string("t"))
        assertEquals("dc-1", requests[1].string("device_code"))
    }

    @Test
    fun `pair reports Unavailable when no target resolves`(): Unit = runBlocking {
        val result = ChannelPairingTransport(
            target = { null },
            keypair = { deviceKeypair() },
            crypto = FakeProvisionCrypto(EXPECTED_CRYPTO_TAG),
        ).pair("device-1", null, null) { _, _ -> error("a code must not be published") }

        assertIs<BoomioPairingResult.Unavailable>(result)
    }

    /**
     * ⚠️ The enrollment half dials the key the *pairing* half proved, so a device with no keypair
     * cannot pair at all — and that is a "use the other transport", not a failure: the HTTPS flow
     * does not need the overlay key.
     */
    @Test
    fun `pair reports Unavailable when the device has no overlay keypair`() = runBlocking {
        val server = startServer { pairCodeReply() }

        val result = ChannelPairingTransport(
            target = { targetFor(server) },
            keypair = { null },
            crypto = FakeProvisionCrypto(EXPECTED_CRYPTO_TAG),
        ).pair("device-1", null, null) { _, _ -> error("a code must not be published") }

        assertIs<BoomioPairingResult.Unavailable>(result)
        assertEquals(0, server.connectionsServed, "no keypair means nothing was dialled")
    }

    /**
     * The `prov` kill switch, and the case that has to stay a fall-through: the operator has not
     * turned provisioning on, so the link must proceed on the transport that always worked.
     */
    @Test
    fun `pair reports Unavailable when the server refuses at the handshake`() = runBlocking {
        val server = startServer(denyReason = "provisioning is not enabled") { pairCodeReply() }
        val codes = mutableListOf<String>()

        val result = transport(server).pair("device-1", null, null) { user, _ -> codes += user }

        assertIs<BoomioPairingResult.Unavailable>(result)
        assertTrue(codes.isEmpty(), "a refused handshake must not surface a code")
        server.throwIfFailed()
    }

    /**
     * ⚠️ **The load-bearing rule of the whole wiring, and the reason this test exists.**
     *
     * A code has been displayed; the owner may be typing it into bsm right now. If a failure here
     * were [BoomioPairingResult.Unavailable] the caller would quietly start the HTTPS exchange,
     * mint a *second* code, and the approval of the first would land on a request nothing is
     * polling — a flow that hangs for the person while both halves report success.
     *
     * The failure is injected as a server-side `error` reply rather than a dropped socket, but the
     * two take the identical path: both throw out of `pairPoll` inside the post-code `try`.
     */
    @Test
    fun `a failure after the code is reported, never retried on the other transport`() = runBlocking {
        val server = startServer { request ->
            when (request.string("t")) {
                "pair.request" -> pairCodeReply()
                else -> """{"t":"error","code":"internal","message":"the pairing store is unavailable"}"""
            }
        }
        val codes = mutableListOf<String>()

        val result = transport(server).pair("device-1", null, null) { user, _ -> codes += user }

        assertEquals(listOf("WXYZ-1234"), codes, "the code was published before the failure")
        val failed = assertIs<BoomioPairingResult.Failed>(result)
        assertTrue(
            "pairing store" in failed.message,
            "the server's own reason must survive to the caller, was: ${failed.message}",
        )
        server.throwIfFailed()
    }

    @Test
    fun `an expired code is reported as expired rather than as a timeout`() = runBlocking {
        val server = startServer { request ->
            when (request.string("t")) {
                "pair.request" -> pairCodeReply()
                else -> """{"t":"pair.expired"}"""
            }
        }
        val codes = mutableListOf<String>()

        val result = transport(server).pair("device-1", null, null) { user, _ -> codes += user }

        val failed = assertIs<BoomioPairingResult.Failed>(result)
        assertTrue("expired" in failed.message, "was: ${failed.message}")
        assertEquals(1, codes.size)
        server.throwIfFailed()
    }

    // ── enrollment over the channel ─────────────────────────────────────────────────────────

    @Test
    fun `enroll reports Pending with the server's own poll hint`() = runBlocking {
        val server = startServer { PENDING_REPLY }

        val ack = enrollmentApi(server).enroll(TEST_DEVICE_PUBLIC_KEY)

        assertEquals(1500L, assertIs<EnrollAck.Pending>(ack).pollAfterMs)
        server.throwIfFailed()
    }

    /**
     * A refused enrollment is the server's answer, not a dead wire — and the two must not be
     * reported with the same sentence.
     */
    @Test
    fun `a refused enroll carries the server's reason, not a network error`() = runBlocking {
        val server = startServer {
            """{"t":"error","code":"unauthorized","message":"This session is no longer valid."}"""
        }

        val ack = enrollmentApi(server).enroll(TEST_DEVICE_PUBLIC_KEY)

        assertEquals(
            "This session is no longer valid.",
            assertIs<EnrollAck.Rejected>(ack).reason,
        )
        server.throwIfFailed()
    }

    /**
     * ⚠️ **Enrollment follows the transport that paired it.** The api opens a fresh connection
     * per call, because the server reads the device key off *that* connection's HELLO — so the
     * assertion that two calls made two connections is not incidental, it is the design.
     */
    @Test
    fun `poll returns the assignment when its key is the record's pk`() = runBlocking {
        val server = startServer(respond = enrollThenReady())
        val api = enrollmentApi(server)

        assertEquals(1500L, assertIs<EnrollAck.Pending>(api.enroll(TEST_DEVICE_PUBLIC_KEY)).pollAfterMs)
        val ready = assertIs<EnrollPoll.Ready>(api.poll())

        assertEquals("10.77.0.9", ready.assignment.address)
        assertEquals(2, server.connectionsServed, "one connection per call, by construction")
        server.throwIfFailed()
    }

    /** §7 check 2, at the seam where the wiring could actually get it wrong. */
    @Test
    fun `poll refuses an assignment whose key the record does not publish`() = runBlocking {
        val server = startServer(respond = enrollThenReady())
        val api = enrollmentApi(server, tunnelKeyBase64 = TEST_PPK)

        assertIs<EnrollAck.Pending>(api.enroll(TEST_DEVICE_PUBLIC_KEY))

        val failed = assertIs<EnrollPoll.Failed>(api.poll())
        assertTrue("discovery record" in failed.reason, "was: ${failed.reason}")
        server.throwIfFailed()
    }

    /**
     * ⚠️ **A record that carries `ppk` and no `pk` is a publisher bug, and the device is not the
     * one that should suffer for it.** Nothing is compared and the assignment is taken — the
     * alternative, failing, would turn a stale publisher into a device that can never enroll.
     */
    @Test
    fun `poll accepts an uncheckable assignment when the record publishes no pk`() = runBlocking {
        val server = startServer(respond = enrollThenReady())
        val api = enrollmentApi(server, tunnelKeyBase64 = null)

        assertIs<EnrollAck.Pending>(api.enroll(TEST_DEVICE_PUBLIC_KEY))

        assertIs<EnrollPoll.Ready>(api.poll())
        server.throwIfFailed()
    }

    /**
     * ⚠️ **An `enroll.ready` answer to the `enroll` call is not an error.** The contract says that
     * reply is `enroll.pending`, but a server that already holds the assignment can answer with it
     * — and "the answer is already there, collect it on the first poll" is a *better* answer than
     * pending, not a wrong one. What must not happen is the reverse reading: a terminal `Rejected`
     * invented from a reply the server was free to improve on would strand a device one poll away
     * from being enrolled, with nothing to retry.
     *
     * The poll interval is the client's own default here, because the reply carried no hint to
     * use — which is exactly what the assertion pins.
     */
    @Test
    fun `an assignment straight out of enroll is collected on the first poll, not rejected`() =
        runBlocking {
            val server = startServer { enrollReadyReply(serverKey = TEST_TUNNEL_KEY) }
            val api = enrollmentApi(server)

            val ack = assertIs<EnrollAck.Pending>(api.enroll(TEST_DEVICE_PUBLIC_KEY))
            assertEquals(PROVISION_DEFAULT_POLL_MS, ack.pollAfterMs)

            assertIs<EnrollPoll.Ready>(api.poll())
            server.throwIfFailed()
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private fun transport(server: ScriptedProvisionServer) = ChannelPairingTransport(
        target = { targetFor(server) },
        keypair = { deviceKeypair() },
        crypto = FakeProvisionCrypto(EXPECTED_CRYPTO_TAG),
    )

    private fun enrollmentApi(
        server: ScriptedProvisionServer,
        tunnelKeyBase64: String? = TEST_TUNNEL_KEY,
    ) = ChannelEnrollmentApi(
        token = "bs_ses_1",
        target = { targetFor(server, tunnelKeyBase64) },
        keypair = { deviceKeypair() },
        crypto = FakeProvisionCrypto(EXPECTED_CRYPTO_TAG),
    )

    /**
     * ⚠️ The dialling key and the checked key are different fields on purpose, and the tests above
     * would pass vacuously if they were not: `ppk` is what the handshake proves the peer holds,
     * and `pk` is what an *assignment* must agree with. A target built with one value for both
     * could not tell "check 2 passed" from "check 2 never ran".
     */
    private fun targetFor(
        server: ScriptedProvisionServer,
        tunnelKeyBase64: String? = TEST_TUNNEL_KEY,
    ) = ProvisionTarget(
        host = server.host,
        port = server.port,
        provisioningKeyBase64 = TEST_PPK,
        tunnelKeyBase64 = tunnelKeyBase64,
    )

    private fun deviceKeypair() = OverlayWgKeypair(
        privateKeyBase64 = TEST_DEVICE_PRIVATE_KEY,
        publicKeyBase64 = TEST_DEVICE_PUBLIC_KEY,
        privateKeyHex = "00",
        publicKeyHex = "00",
    )

    private fun assignmentOf(serverKey: String) = OverlayAssignment(
        address = "10.77.0.9",
        serverPublicKeyBase64 = serverKey,
        endpoint = "192.168.68.65:51820",
        overlayCidr = "10.77.0.0/24",
        mtu = 1420,
    )

    private fun pairCodeReply() = """{"t":"pair.code","device_code":"dc-1","user_code":"WXYZ-1234",
        "verification_uri":"https://bsm.tracemonkey.org/pair","expires_in":300,"interval":2}"""
        .compact()

    private fun enrollReadyReply(serverKey: String) = """{"t":"enroll.ready","name":"phone",
        "address":"10.77.0.9","server_pubkey":"$serverKey","endpoint":"192.168.68.65:51820",
        "overlay_cidr":"10.77.0.0/24","mtu":1420}""".compact()

    /**
     * ⚠️ **The responder has to discriminate, and a constant one silently does not.**
     *
     * `enrollReadyReply` answers *every* request with `enroll.ready`, including the `enroll` call
     * — and the client reads that reply as "collect the assignment on the first poll", carrying the
     * server's own poll hint nowhere. That is deliberate behaviour with a test of its own below,
     * but a test that meant to assert the server's `poll_after_ms` would then be asserting the
     * client's default instead, and pass or fail for the wrong reason.
     *
     * These tests are about the *poll*, so the enroll call gets a real `enroll.pending` reply and
     * only the status call gets the assignment.
     */
    private fun enrollThenReady(serverKey: String = TEST_TUNNEL_KEY): (JsonObject) -> String =
        { request -> if (request.string("t") == "enroll") PENDING_REPLY else enrollReadyReply(serverKey) }

    private fun startServer(
        denyReason: String? = null,
        respond: (JsonObject) -> String,
    ): ScriptedProvisionServer =
        ScriptedProvisionServer(cryptoTag = EXPECTED_CRYPTO_TAG, denyReason = denyReason, respond = respond)
            .also {
                servers += it
                it.start()
            }

    private companion object {
        /**
         * A real WireGuard-shaped keypair, so `decodeWireGuardKey` accepts it. Public test
         * material, not secrets — the same pair the sibling connection tests use, redeclared
         * because that file keeps its copy in a `private companion object`.
         */
        /** The server's own poll hint, deliberately not the client's 3000 ms default. */
        const val PENDING_REPLY = """{"t":"enroll.pending","poll_after_ms":1500}"""

        const val TEST_DEVICE_PRIVATE_KEY = "AAIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eH2A="
        const val TEST_DEVICE_PUBLIC_KEY = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw="

        /** The provisioning key — `ppk`, what the sealed handshake proves the peer holds. */
        const val TEST_PPK = "YFpyXSpK3+6xop4X7dYhwbdZPujNvESsbEq24vgF0jw="

        /**
         * `pk`, the tunnel key a discovery record publishes and an assignment must match.
         *
         * ⚠️ **Deliberately not one of the key literals above.** Check 2 compares two strings and
         * never decodes them, so this only has to be *different* — and reusing a real key here
         * would make it possible to write a target that passes for the wrong reason. It is
         * recognisable on sight as a placeholder so that no reader mistakes it for key material.
         */
        val TEST_TUNNEL_KEY: String = "A".repeat(43) + "="

        const val EXPECTED_CRYPTO_TAG = "right"
    }
}

/** Collapses the multi-line JSON literals above into one string the parser can read. */
private fun String.compact(): String = trimIndent().replace("\n", "").trim()

// ── the fake server ─────────────────────────────────────────────────────────────────────────

/**
 * A real `ServerSocket` on loopback that speaks the provisioning protocol, **accepting any number
 * of connections**.
 *
 * The sibling connection tests use a single-accept fake, which is enough there because one test
 * makes one connection. It is not enough here: `ChannelEnrollmentApi` deliberately opens a *fresh*
 * connection per call, so the enroll → poll cycle is two connections and a one-shot server would
 * leave the second call dialling a closed port — which would look like a network failure and pass
 * the wrong assertion.
 *
 * It shares [ProvisionFrameReader] and [FakeSealer] with the production framing rather than
 * re-implementing either: a fake with its own framing could agree with a buggy client about a
 * wrong format.
 */
internal class ScriptedProvisionServer(
    private val cryptoTag: String,
    private val denyReason: String? = null,
    private val respond: (JsonObject) -> String,
) {

    private val serverNonce: ByteArray = ByteArray(PROVISION_NONCE_BYTES) { (it + 7).toByte() }
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val finished = CountDownLatch(1)
    private val failures = AtomicReference<Throwable?>(null)
    private val accepted = AtomicInteger(0)

    /** Every request that arrived, in order, across every connection. */
    val requests = CopyOnWriteArrayList<JsonObject>()

    val host: String get() = "127.0.0.1"
    val port: Int get() = socket.localPort
    val connectionsServed: Int get() = accepted.get()

    fun start() {
        thread(isDaemon = true, name = "scripted-provision-server") {
            try {
                while (true) {
                    socket.accept().use { serve(it) }
                }
            } catch (t: Throwable) {
                // `stop()` closes the listener out from under `accept`; that is not a failure.
                if (!socket.isClosed) failures.set(t)
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

    /** Fails the test if the server thread threw — checked after the client has been asserted on. */
    fun throwIfFailed() {
        failures.get()?.let { throw AssertionError("the fake server failed", it) }
    }

    private fun serve(connection: Socket) {
        accepted.incrementAndGet()
        connection.soTimeout = 5_000
        val input = connection.getInputStream()
        val output = connection.getOutputStream()
        val reader = ProvisionFrameReader()

        nextFrame(input, reader)

        denyReason?.let {
            output.write(encodeProvisionFrame(byteArrayOf(PROVISION_OP_DENIED) + it.toByteArray()))
            output.flush()
            return
        }

        val sealer = FakeSealer(cryptoTag)
        val proof = """{"t":"hello","v":$PROVISION_PROTOCOL_VERSION,"server":"$PROVISION_SERVER_NAME"}"""
        output.write(
            encodeProvisionFrame(
                byteArrayOf(PROVISION_OP_HELLO_ACK) + serverNonce + sealer.seal(proof.toByteArray())
            )
        )
        output.flush()

        // ⚠️ A loop rather than one request: pairing makes two calls on one connection
        // (`pair.request` then `pair.poll`) while enrollment makes one call per connection. Both
        // shapes have to work, and the loop is what lets one fake serve them.
        while (true) {
            val payload = try {
                nextFrame(input, reader)
            } catch (_: Exception) {
                return
            }
            val request = parse(sealer.open(payload.copyOfRange(1, payload.size)))
            requests += request
            output.write(
                encodeProvisionFrame(
                    byteArrayOf(PROVISION_OP_MSG) + sealer.seal(respond(request).toByteArray())
                )
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
