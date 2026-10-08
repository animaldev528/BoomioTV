package com.nuvio.app.core.mtls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registration wire contract, against the literal bodies `bsc/routes/overlay.js` sends.
 *
 * These are written from the route's own source rather than from a captured exchange, because the
 * thing worth pinning is the *classification* — which replies are worth retrying and which are
 * permanent — not the JSON.
 *
 * ⚠️ The single most valuable assertion in this file is
 * [the request carries the certificate and nothing else]. If the client ever sends its name, the
 * server will ignore it (it derives the expected CN from the session) and the registration will
 * still succeed — so nothing on the device would ever look wrong, and the client would be quietly
 * wrong about who decided the name.
 */
class MtlsRegistrationProtocolTest {

    // ── success ──────────────────────────────────────────────────────────────

    @Test
    fun `a registered reply is read as success, with the name and fingerprint`() {
        val ack = decodeCertRegistration(
            200,
            """{"status":"registered","name":"device-kyle-s24-3f9a1b2c",""" +
                """"fingerprint_sha256":"aabbcc","not_before":"2026-10-08T00:00:00.000Z",""" +
                """"not_after":"2036-10-05T00:00:00.000Z"}""",
        )

        assertEquals(
            CertRegistrationAck.Registered(
                name = "device-kyle-s24-3f9a1b2c",
                fingerprintSha256 = "aabbcc",
            ),
            ack,
        )
    }

    @Test
    fun `the dates in a registered reply are ignored, not parsed`() {
        // Nothing consults not_before/not_after — the edge's verifier compares raw DER and never
        // looks at validity. A reply carrying dates this client cannot parse must still register.
        val ack = decodeCertRegistration(
            201,
            """{"status":"registered","name":"device-x","not_before":"whenever","not_after":"never"}""",
        )

        assertEquals(CertRegistrationAck.Registered(name = "device-x", fingerprintSha256 = null), ack)
    }

    @Test
    fun `unknown fields in a registered reply are tolerated`() {
        // The route is free to grow fields; a client that refused on the first new key would stop
        // registering on a server upgrade and look exactly like a network failure.
        val ack = decodeCertRegistration(
            200,
            """{"status":"registered","name":"device-x","serial":"1234","revoked_at":null}""",
        )

        assertTrue(ack is CertRegistrationAck.Registered)
    }

    // ── the 2xx that is not success ──────────────────────────────────────────

    @Test
    fun `a 2xx with the wrong status is deferred, not believed`() {
        // ⚠️ The whole reason this is not `status in 200..299 -> Registered`: believing a reply we
        // could not interpret would record a registration that may not exist, and every later
        // handshake would fail with nothing in the client's log to connect it to.
        assertEquals(
            CertRegistrationAck.Deferred("the server's registration reply was not understood"),
            decodeCertRegistration(200, """{"status":"pending"}"""),
        )
    }

    @Test
    fun `a 2xx with no body at all is deferred`() {
        assertEquals(
            CertRegistrationAck.Deferred("the server's registration reply was not understood"),
            decodeCertRegistration(200, ""),
        )
    }

    @Test
    fun `a 2xx that is not JSON is deferred`() {
        assertEquals(
            CertRegistrationAck.Deferred("the server's registration reply was not understood"),
            decodeCertRegistration(200, "<html>502 Bad Gateway</html>"),
        )
    }

    // ── the refusals ─────────────────────────────────────────────────────────

    @Test
    fun `a validator refusal carries the server's message and its code`() {
        val ack = decodeCertRegistration(
            400,
            """{"error":"certificate CN must be \"device-kyle-s24-3f9a1b2c\"","code":"cn_mismatch"}""",
        )

        assertEquals(
            CertRegistrationAck.Refused(
                reason = """certificate CN must be "device-kyle-s24-3f9a1b2c"""",
                code = "cn_mismatch",
            ),
            ack,
        )
    }

    @Test
    fun `a 400 with no code is still a refusal`() {
        // `{error:'device_id missing from session'}` — a 400 from the route rather than from the
        // validator, so there is no code. It is still permanent: no request from this device will
        // produce a different answer, so retrying would only spend the rate-limit budget.
        val ack = decodeCertRegistration(400, """{"error":"device_id missing from session"}""")

        assertEquals(
            CertRegistrationAck.Refused(reason = "device_id missing from session", code = null),
            ack,
        )
    }

    @Test
    fun `a 404 is a refusal, because the route is wrong rather than the moment`() {
        // The client is pointed at a base URL that does not serve this route. Re-sending the
        // identical request to the identical path cannot fix that.
        val ack = decodeCertRegistration(404, "")

        assertTrue(ack is CertRegistrationAck.Refused, "got $ack")
        assertNull((ack as CertRegistrationAck.Refused).code)
    }

    @Test
    fun `a 403 is revocation, which is terminal and distinct from a refusal`() {
        val ack = decodeCertRegistration(403, """{"error":"device is revoked"}""")

        assertEquals(CertRegistrationAck.Revoked("device is revoked"), ack)
    }

    @Test
    fun `a 401 is the session, not the certificate`() {
        // Kept apart from `Refused` because the caller's response differs: a dead session may be
        // replaced by a later run, so this must not be reported as "your certificate is wrong" —
        // and it must not trigger the re-mint path, which would burn a key over an auth problem.
        assertEquals(
            CertRegistrationAck.Unauthenticated("unauthorized"),
            decodeCertRegistration(401, """{"error":"unauthorized"}"""),
        )
    }

    // ── the retryable ones ───────────────────────────────────────────────────

    @Test
    fun `a 429 is deferred, with the server's own words`() {
        assertEquals(
            CertRegistrationAck.Deferred("Certificate registration rate limited."),
            decodeCertRegistration(429, """{"error":"Certificate registration rate limited."}"""),
        )
    }

    @Test
    fun `a 500 is deferred`() {
        assertEquals(
            CertRegistrationAck.Deferred("could not record certificate"),
            decodeCertRegistration(500, """{"error":"could not record certificate"}"""),
        )
    }

    @Test
    fun `a 5xx with an unreadable body still names the status`() {
        // The fallback has to carry the status, because "the server could not record the
        // certificate" with no code is indistinguishable from a dozen other paths in a log.
        val ack = decodeCertRegistration(503, "<html>upstream connect error</html>")

        assertEquals(
            CertRegistrationAck.Deferred("the server could not record the certificate (503)"),
            ack,
        )
    }

    @Test
    fun `a non-2xx with no body at all is deferred rather than a refusal`() {
        // 400 with an empty body is the one shape where the status alone has to decide, and the
        // safe reading is "the moment was wrong" — a refusal recorded wrongly would stop the
        // device from ever registering, while a deferral recorded wrongly costs one retry.
        val ack = decodeCertRegistration(400, "")

        assertEquals(
            CertRegistrationAck.Refused("the server refused the certificate", null),
            ack,
        )
    }

    // ── the request ──────────────────────────────────────────────────────────

    @Test
    fun `the request carries the certificate and nothing else`() {
        // ⚠️ Pins the one field. `peerNameFor(session.device_id)` decides the expected CN on the
        // server, so a name here would be a value the server is right to ignore — and its presence
        // would suggest to the next reader that the client has a say in its own name.
        val body = mtlsRegistrationJson.encodeToString(CertRegistrationRequestDto("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"))

        assertEquals("""{"cert":"-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"}""", body)
    }

    @Test
    fun `the request dto has exactly one property`() {
        // A structural guard beside the string above: the string pins today's shape, this pins the
        // shape against a field being *added* (which the string would catch) or one being renamed
        // (which it would also catch) — and, more to the point, documents that one is all there is.
        //
        // ⚠️ Static fields are filtered, not just synthetic ones. The compiler adds two to a
        // `@Serializable` data class that are neither: `Companion` (for the generated serializer)
        // and `$stable` (the Compose stability marker, an artifact of this module compiling with
        // the Compose compiler). Neither is part of the wire shape, which is what this test is
        // about.
        val properties = CertRegistrationRequestDto::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }

        assertEquals(listOf("cert"), properties)
    }
}
