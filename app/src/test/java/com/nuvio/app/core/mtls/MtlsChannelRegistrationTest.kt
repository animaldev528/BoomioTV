package com.nuvio.app.core.mtls

import android.app.Application
import com.nuvio.app.core.overlay.FakeProvisionCrypto
import com.nuvio.app.core.overlay.OverlayWgKeypair
import com.nuvio.app.core.overlay.ProvisionTarget
import com.nuvio.app.core.overlay.ScriptedProvisionServer
import com.nuvio.app.core.overlay.string
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `cert.register` over the provisioning channel — the client half of `docs/mtls-plan.md` §13.2.
 *
 * **What these tests are for.** `MtlsRegistrarTest` proves the *plan* and `MtlsRegistrationProtocolTest`
 * proves the *HTTPS* reply mapping. Neither can see this file's subject, which is the mapping from the
 * **channel's** reply space onto the registrar's vocabulary — and that mapping is where the decision
 * lives: the question every arm answers is *could re-sending these identical bytes ever help?*
 *
 * The three arms worth naming:
 *
 * 1. **`write_failed` is `Deferred`, not `Refused`.** The bytes were accepted and the disk was not.
 *    Mapping it terminal would leave a device that hit a full disk permanently without a certificate,
 *    when the fix is a retry.
 * 2. **`revoked` is its own bucket.** An operator removed this device; re-sending earns the same
 *    answer forever, and `MtlsHandshakeWatch` reads `Revoked` to stop asking.
 * 3. **A refused handshake is `Refused`, not `Deferred`.** `OverlayProvisionException.HandshakeFailed`
 *    is documented as terminal — *"must not be retried and must not be answered"* — so putting it
 *    through the registrar's five-attempt loop would be the one outcome the protocol forbids.
 *
 * The sockets, the framing, the handshake and the sequencing are production code, driven against a
 * real loopback `ServerSocket`. The AEAD is [FakeProvisionCrypto]'s order-checking prefix, because the
 * real one is X25519 + AES-GCM inside the `overlaywg` AAR and re-implementing it here would create the
 * second implementation that must never exist.
 *
 * Robolectric because the class logs through `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MtlsChannelRegistrationTest {

    private val servers = mutableListOf<ScriptedProvisionServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    // ── the happy path, and what actually goes on the wire ──────────────────────────────────

    @Test
    fun `a registered reply carries the server's name and fingerprint`() = runBlocking {
        val server = startServer {
            """{"t":"cert.registered","name":"$EXPECTED_NAME",
               "fingerprint_sha256":"$EXPECTED_FINGERPRINT",
               "not_before":"2026-10-01T00:00:00Z","not_after":"2027-10-01T00:00:00Z"}""".compact()
        }

        val ack = api(server).register(CERT_PEM)

        val registered = assertIs<CertRegistrationAck.Registered>(ack)
        assertEquals(EXPECTED_NAME, registered.name)
        assertEquals(EXPECTED_FINGERPRINT, registered.fingerprintSha256)
    }

    /**
     * ⚠️ **The request carries the token and the certificate and nothing else.**
     *
     * The server derives the CN it will accept from the *verified session*
     * (`peerNameFor(session.device_id)`), exactly as the HTTPS route does. A `device_id` or a `name`
     * sent from here would be a value the server is right to ignore — so the test pins that this
     * client does not send one, which is what keeps the binding "the server decided who you are"
     * rather than "you said who you are".
     */
    @Test
    fun `the request carries no device id and no name`() = runBlocking {
        val server = startServer { """{"t":"cert.registered","name":"$EXPECTED_NAME"}""" }

        api(server).register(CERT_PEM)

        val request = server.requests.single()
        assertEquals("cert.register", request.string("t"))
        assertEquals(TOKEN, request.string("session_token"))
        assertEquals(CERT_PEM, request.string("cert"))
        assertNull(request["device_id"], "the server must derive the device from the session, not be told")
        assertNull(request["name"], "the CN is the server's to compute; sending one invites cn_mismatch")
        server.throwIfFailed()
    }

    /** One request, one answer, one connection — unlike enrollment there is nothing to poll for. */
    @Test
    fun `registration makes exactly one call on one connection`() = runBlocking {
        val server = startServer { """{"t":"cert.registered","name":"$EXPECTED_NAME"}""" }

        api(server).register(CERT_PEM)

        assertEquals(1, server.requests.size)
        assertEquals(1, server.connectionsServed)
    }

    // ── the refusals, which are the reason this mapping exists ──────────────────────────────

    /**
     * ⚠️ **The one refusal that is worth retrying**, and the arm most likely to be "simplified" into
     * `Refused` by someone reading the block as a list of failures. The bytes were fine; the write
     * was not.
     */
    @Test
    fun `write_failed is deferred rather than refused`() = runBlocking {
        val server = startServer {
            """{"t":"cert.refused","code":"write_failed","message":"could not record certificate"}"""
        }

        val ack = api(server).register(CERT_PEM)

        // Asserted *through* the reason rather than only on the class, so the arm also pins that the
        // server's own words are what come back. A generic "registration failed" would defer just as
        // correctly and would leave an operator reading the log with nothing to act on.
        assertEquals(
            "could not record certificate",
            assertIs<CertRegistrationAck.Deferred>(ack).reason,
        )
    }

    /** Terminal. Re-sending earns the same answer forever, and the watch stops asking on `Revoked`. */
    @Test
    fun `a revoked device is terminal`() = runBlocking {
        val server = startServer {
            """{"t":"cert.refused","code":"revoked","message":"device is revoked"}"""
        }

        val ack = api(server).register(CERT_PEM)

        val revoked = assertIs<CertRegistrationAck.Revoked>(ack)
        assertTrue("revoked" in revoked.reason, "the server's own words must survive: ${revoked.reason}")
    }

    /** A validator refusal is about the bytes, so the machine-readable code has to reach the caller. */
    @Test
    fun `a validator refusal keeps its code`() = runBlocking {
        val server = startServer {
            // ⚠️ `\"` and not `\\"`: in a Kotlin raw string an escape is *not* processed, so `\\"`
            // would put two backslashes and then a real quote into the JSON — closing the string
            // early and turning this fixture into a decode failure, which reads as a protocol fault
            // rather than as the validator refusal the test is about.
            """{"t":"cert.refused","code":"cn_mismatch","message":"certificate CN must be \"$EXPECTED_NAME\""}"""
        }

        val ack = api(server).register(CERT_PEM)

        val refused = assertIs<CertRegistrationAck.Refused>(ack)
        assertEquals("cn_mismatch", refused.code)
    }

    /**
     * A `cert.refused` must NOT arrive as the exception the connection raises for `{t:'error'}`.
     *
     * ⚠️ **This is the assertion that pins the protocol's shape.** `OverlayProvisionConnection.request`
     * turns `{t:'error'}` into `OverlayProvisionException.ServerError`, and if the server ever answered
     * a refusal that way every code above would collapse into one bucket — losing exactly the
     * distinction ("could re-sending help?") the mapping is built on. The test proves the branch by
     * showing a `cert.refused` reaches `classify` as a *message*.
     */
    @Test
    fun `a refusal arrives as a message rather than as an error`() = runBlocking {
        val server = startServer {
            """{"t":"cert.refused","code":"eku","message":"certificate is not valid for clientAuth"}"""
        }

        val ack = api(server).register(CERT_PEM)

        assertEquals("eku", assertIs<CertRegistrationAck.Refused>(ack).code)
    }

    // ── the session's own state, which is a different axis ──────────────────────────────────

    @Test
    fun `an unauthorized session is its own outcome`() = runBlocking {
        val server = startServer {
            """{"t":"error","code":"unauthorized","message":"valid session_token required"}"""
        }

        val ack = api(server).register(CERT_PEM)

        // The reason has to survive, because this is the arm that means "re-pair", not "retry" — and
        // the session's own words are what tells a reader which of the two they are looking at.
        assertEquals(
            "valid session_token required",
            assertIs<CertRegistrationAck.Unauthenticated>(ack).reason,
        )
    }

    /**
     * Any *other* server error is deferred rather than terminal: the server understood the request
     * and answered about this device's state, which a later run may get a different answer to.
     */
    @Test
    fun `a server error that is not unauthorized is deferred`() = runBlocking {
        val server = startServer {
            """{"t":"error","code":"internal","message":"something went wrong"}"""
        }

        val ack = api(server).register(CERT_PEM)

        // The server's message, not its code: `internal` is what the machine reads and this is what
        // the person reading the log gets. Losing it would make this arm indistinguishable from the
        // socket-died one below.
        assertEquals(
            "something went wrong",
            assertIs<CertRegistrationAck.Deferred>(ack).reason,
        )
    }

    // ── the two terminal transport states, and why they are not deferred ────────────────────

    /**
     * ⚠️ **The trust anchor failed, and retrying is exactly what must not happen.**
     *
     * A peer that does not hold the published `ppk` will not hold it on the fourth attempt either, and
     * `OverlayProvisionException.HandshakeFailed` is documented as terminal for that reason. The
     * registrar has no transport-terminal bucket — its five cases were designed for an HTTP status
     * space — so the refusal is surfaced as `Refused` with its own code: the *action* is right (stop)
     * and the code keeps it distinguishable from a validator refusal of the certificate bytes.
     */
    @Test
    fun `a refused handshake is terminal and names itself`() = runBlocking {
        val server = ScriptedProvisionServer(
            cryptoTag = "wrong",
            respond = { """{"t":"cert.registered"}""" },
        ).also { servers += it; it.start() }

        val ack = api(server, cryptoTag = "right").register(CERT_PEM)

        val refused = assertIs<CertRegistrationAck.Refused>(ack)
        assertEquals("handshake_failed", refused.code)
    }

    /** The operator's kill switch. Retrying cannot move it, so it must not be retried. */
    @Test
    fun `a denied provisioning server is terminal`() = runBlocking {
        val server = ScriptedProvisionServer(
            cryptoTag = CRYPTO_TAG,
            denyReason = "provisioning is off",
            respond = { """{"t":"cert.registered"}""" },
        ).also { servers += it; it.start() }

        val ack = api(server).register(CERT_PEM)

        assertEquals("provisioning_closed", assertIs<CertRegistrationAck.Refused>(ack).code)
    }

    // ── the answers that are not answers ────────────────────────────────────────────────────

    /** No address means the tunnel may simply not be up; the registrar's retry window covers it. */
    @Test
    fun `no target is deferred and opens no socket`() = runBlocking {
        val ack = ChannelMtlsRegistrationApi(TOKEN, target = { null }).register(CERT_PEM)

        assertEquals("This network has no reachable provisioning address.", assertIs<CertRegistrationAck.Deferred>(ack).reason)
    }

    @Test
    fun `no keypair is deferred`() = runBlocking {
        val ack = api(startServer { """{"t":"cert.registered"}""" }, keypair = null).register(CERT_PEM)

        // Named, because this one is *ours*: no keypair means no overlay, which is a different
        // situation from no target below and is fixed by a different action (enroll, not wait).
        assertEquals(
            "This device has no overlay key pair yet.",
            assertIs<CertRegistrationAck.Deferred>(ack).reason,
        )
    }

    /**
     * A readable reply that is simply not about the certificate is not a registration.
     *
     * `pair.pending` is a real [ProvisionMessage] this client decodes perfectly well — it is just not
     * an answer to `cert.register`. `Deferred` rather than `Refused`, because the cause is on *our*
     * side of the wire and the next attempt may not repeat it.
     */
    @Test
    fun `a reply that is not about the certificate is deferred rather than refused`() = runBlocking {
        val server = startServer { """{"t":"pair.pending"}""" }

        val ack = api(server).register(CERT_PEM)

        // ⚠️ The reason has to name what actually arrived. This is the one arm debugged from a log
        // rather than from the server, and "the registration failed" would leave nothing to go on.
        assertTrue(
            "PairPending" in assertIs<CertRegistrationAck.Deferred>(ack).reason,
            "the reason must name the message that arrived",
        )
    }

    /**
     * ⚠️ **The *undecodable* reply, which is a different outcome from the test above and a harsher
     * one — and the two are easy to conflate.**
     *
     * An `enroll.ready` carrying no assignment is not a message this client can read: `assignmentOf`
     * refuses the hole rather than defaulting an address and endpoint, so the reply is thrown out
     * before it ever reaches `classify` and surfaces as `ProtocolFault`. That maps to
     * `Refused("protocol_fault")` — **terminal**, unlike the deferred case above — because a peer
     * that speaks a different protocol will speak it again on the next attempt.
     */
    @Test
    fun `an undecodable reply is refused as a protocol fault`() = runBlocking {
        val server = startServer { """{"t":"enroll.ready","name":"$EXPECTED_NAME"}""" }

        val ack = api(server).register(CERT_PEM)

        assertEquals("protocol_fault", assertIs<CertRegistrationAck.Refused>(ack).code)
    }

    // ── the fallback, which is what keeps the HTTPS route reachable ─────────────────────────

    /**
     * ⚠️ **`null` is the fallback, and it is the whole reason the two transports can coexist.**
     *
     * `MtlsRegistration.apiOrNull()` reads this and falls through to `BscMtlsRegistrationApi` on a
     * null, so a device on the home L2 — where `companionRestBaseUrl` works — reaches the route and
     * never touches the channel. With no process initialized there is no link, so this is the
     * not-linked answer by construction rather than by arrangement.
     */
    @Test
    fun `a device that did not link over the channel falls back to the route`() {
        assertNull(channelRegistrationApiOrNull(TOKEN))
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────────

    private fun startServer(respond: (JsonObject) -> String): ScriptedProvisionServer =
        ScriptedProvisionServer(cryptoTag = CRYPTO_TAG, respond = respond).also {
            servers += it
            it.start()
        }

    private fun api(
        server: ScriptedProvisionServer,
        cryptoTag: String = CRYPTO_TAG,
        keypair: OverlayWgKeypair? = DEVICE_KEYPAIR,
    ): ChannelMtlsRegistrationApi = ChannelMtlsRegistrationApi(
        token = TOKEN,
        target = {
            ProvisionTarget(
                host = server.host,
                port = server.port,
                provisioningKeyBase64 = TEST_PPK,
                tunnelKeyBase64 = TEST_TUNNEL_KEY,
            )
        },
        keypair = { keypair },
        crypto = FakeProvisionCrypto(cryptoTag),
    )

    private companion object {
        const val TOKEN = "bs_ses_mtls_test"

        /** `peerNameFor("pixel-7-pro")` on the server side — the CN the route and the channel both expect. */
        const val EXPECTED_NAME = "device-pixel-7-pro-430f9ca3"
        /**
         * The *shape* of a SHA-256 fingerprint and nothing more. A `val` rather than a `const`
         * because `repeat` is not a compile-time constant, and spelling out sixty-four literal `a`s
         * would only invite a miscount.
         */
        val EXPECTED_FINGERPRINT = "a".repeat(64)

        const val CRYPTO_TAG = "right"

        /**
         * A real WireGuard-shaped keypair and the provisioning key, so `decodeWireGuardKey` accepts
         * them. Public test material, not secrets — the same literals the provisioning tests use,
         * redeclared because that file keeps its copy in a `private companion object`.
         */
        val DEVICE_KEYPAIR = OverlayWgKeypair(
            privateKeyBase64 = "AAIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eH2A=",
            publicKeyBase64 = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw=",
            privateKeyHex = "",
            publicKeyHex = "",
        )

        const val TEST_PPK = "YFpyXSpK3+6xop4X7dYhwbdZPujNvESsbEq24vgF0jw="
        val TEST_TUNNEL_KEY: String = "A".repeat(43) + "="

        /**
         * A stand-in for a PEM. Nothing on this path parses it — the *server* does, and this fake
         * server does not — so a literal here is honest about what is being tested: the framing, the
         * sequencing and the reply mapping, none of which read the bytes.
         */
        const val CERT_PEM = "-----BEGIN CERTIFICATE-----\nMIIBkTCB+w==\n-----END CERTIFICATE-----\n"
    }
}

/** Collapses a multi-line JSON literal into one string the parser can read. */
private fun String.compact(): String = trimIndent().replace("\n", "").trim()
