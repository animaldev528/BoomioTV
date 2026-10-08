package com.nuvio.app.core.mtls

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.util.Calendar
import java.util.TimeZone
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The minted certificate, read back by a real X.509 parser.
 *
 * ⚠️ **This is the whole reason `MtlsDer` is safe to hand-roll.** There is no reference encoder to
 * diff against, and no compiler checking ASN.1 — so the oracle is
 * `CertificateFactory.getInstance("X.509")`, which rejects malformed DER outright, plus
 * `X509Certificate.verify(cert.publicKey)`, which fails unless the signature covers *exactly* the
 * TBS bytes this package produced. A field written in the wrong order, a length off by one, or a
 * `SET` that is not a `SET` all die here rather than in a TLS handshake on a TV.
 *
 * The assertions are the contract `bsc/lib/overlay-cert.js` will re-derive server-side — CN, RSA
 * ≥ 2048, `clientAuth` EKU — plus the two extensions that exist only so the certificate does not
 * read as a CA to anything that looks at it.
 *
 * No Robolectric: `KeyPairGenerator`, `Signature` and `CertificateFactory` are all plain JVM, and
 * this package touches no Android class (that is deliberate — the keypair, which *does*, is P2.1's
 * file, not this one).
 */
class MtlsCertificateTest {

    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** Second-aligned, so the DER's second-granularity times round-trip exactly. */
    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    private val now = utc(2026, 10, 8, 12, 0, 0)

    /** A fixed serial, not `randomSerial()`: the determinism assertions need a fixed input. */
    private val serial = ByteArray(16) { (it + 1).toByte() }

    private fun mint(
        commonName: String = "pixel-7-pro-430f9ca3",
        at: Long = now,
        serialBytes: ByteArray = serial,
    ): ClientCertificate = MtlsCertificate.selfSign(commonName, keyPair, at, serialBytes)

    private fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    // -----------------------------------------------------------------------------------------
    // It is a certificate, and it is self-signed
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the bytes parse as an X509 certificate`() {
        val cert = parse(mint().der)

        assertEquals(3, cert.version)
        assertEquals("1.2.840.113549.1.1.11", cert.sigAlgOID)
    }

    @Test
    fun `the signature verifies against the certificate's own public key`() {
        // ⚠️ The load-bearing assertion of the entire file. It fails unless the signature was
        // computed over the exact TBS bytes that were emitted — so it catches a mis-ordered field,
        // a wrong length, or an algorithm identifier that does not match the signature, all of
        // which parse fine on their own.
        val cert = parse(mint().der)
        cert.verify(cert.publicKey)
    }

    @Test
    fun `issuer and subject are identical, because nothing else signed it`() {
        val cert = parse(mint().der)

        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertContentEquals(
            cert.subjectX500Principal.encoded,
            cert.issuerX500Principal.encoded,
        )
    }

    @Test
    fun `the public key is the one it was minted from`() {
        val cert = parse(mint().der)

        assertEquals(keyPair.public, cert.publicKey)
        assertEquals(2048, (cert.publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength())
    }

    // -----------------------------------------------------------------------------------------
    // The three things bsc's validator will re-derive
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the subject common name is exactly the server-assigned device name`() {
        // ⚠️ Not `Build.MODEL`. bsc derives the expected value from the session's device_id and
        // rejects a mismatch, so a certificate naming the hardware registers nothing at all.
        val name = "shield-android-tv-9a1b2c3d"
        val cert = parse(mint(commonName = name).der)

        assertEquals("CN=$name", cert.subjectX500Principal.getName(X500Principal.RFC2253))
    }

    @Test
    fun `extendedKeyUsage carries clientAuth and nothing else`() {
        val cert = parse(mint().der)

        assertEquals(listOf(MtlsCertificate.CLIENT_AUTH_EKU_OID), cert.extendedKeyUsage)
    }

    @Test
    fun `a common name holding a comma stays one attribute`() {
        // ⚠️ The UTF8String is written as-is, so a CN containing a DN separator would be a way to
        // smuggle a second attribute into the Name — and bsc compares the *parsed* CN, so the two
        // would disagree about what the subject is. RFC 2253 escaping is the proof that the parser
        // saw one attribute: a genuine second one would produce `+` or `,` separators instead.
        val cert = parse(mint(commonName = "tv,upstairs").der)

        assertEquals("CN=tv\\,upstairs", cert.subjectX500Principal.getName(X500Principal.RFC2253))
    }

    // -----------------------------------------------------------------------------------------
    // The two extensions that exist to stop it reading as a CA
    // -----------------------------------------------------------------------------------------

    @Test
    fun `basicConstraints says this is not a CA`() {
        // ⚠️ Measured on the server side: a self-signed certificate carrying only extendedKeyUsage
        // makes OpenSSL's legacy fallback report `ca === true`. bsc does not consult `ca`, but an
        // explicit CA:FALSE is what makes the file an operator inspects behave.
        val cert = parse(mint().der)

        assertEquals(-1, cert.basicConstraints)
    }

    @Test
    fun `keyUsage is digitalSignature alone`() {
        val cert = parse(mint().der)
        val usage = cert.keyUsage

        assertEquals(9, usage.size)
        assertTrue(usage[0], "digitalSignature (bit 0) must be set")
        assertTrue(usage.drop(1).none { it }, "no other key usage bit may be set")
    }

    // -----------------------------------------------------------------------------------------
    // Validity and serial
    // -----------------------------------------------------------------------------------------

    @Test
    fun `validity is backdated by the clock skew and runs for ten years`() {
        val cert = parse(mint().der)

        assertEquals(now - MtlsCertificate.CLOCK_SKEW, cert.notBefore.time)
        assertEquals(now + MtlsCertificate.VALIDITY, cert.notAfter.time)
        // ⚠️ The edge never consults this — the plan's whole revocation model rests on that, and
        // `overlay-cert.js` documents the omission deliberately. The assertion is only that the
        // certificate would still be sane to a tool that did look.
        assertTrue(cert.notBefore.before(cert.notAfter))
    }

    @Test
    fun `the serial round-trips`() {
        val cert = parse(mint().der)

        assertEquals(BigInteger(1, serial), cert.serialNumber)
    }

    @Test
    fun `a fresh serial is positive, sixteen bytes, and never zero`() {
        // RFC 5280 §4.1.2.2 forbids serial zero, and a leading high bit would make it negative.
        repeat(64) {
            val bytes = MtlsCertificate.randomSerial()
            assertEquals(16, bytes.size)
            assertTrue(bytes[0].toInt() and 0x80 == 0, "serial must encode as positive")
            assertTrue(bytes.any { b -> b.toInt() != 0 }, "serial must not be zero")
        }
    }

    @Test
    fun `two fresh serials differ`() {
        assertFalse(MtlsCertificate.randomSerial().contentEquals(MtlsCertificate.randomSerial()))
    }

    // -----------------------------------------------------------------------------------------
    // Byte stability — this is what makes re-registration idempotent
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the same inputs produce byte-identical DER`() {
        // ⚠️ The edge compares raw DER with `x509.Certificate.Equal`. Two mints of the "same"
        // certificate that differ by a byte would put two files in the allow-list directory for one
        // device — and the caller's guard against that (re-using the stored certificate rather than
        // re-minting) is only sound because this holds.
        assertContentEquals(mint().der, mint().der)
    }

    @Test
    fun `a different serial produces different DER`() {
        val other = ByteArray(16) { (it + 1).toByte() }.also { it[15] = 0x7F }
        assertFalse(mint().der.contentEquals(mint(serialBytes = other).der))
        assertNotEquals(mint(), mint(serialBytes = other))
    }

    @Test
    fun `a different common name produces different DER`() {
        assertFalse(mint(commonName = "a").der.contentEquals(mint(commonName = "b").der))
    }

    // -----------------------------------------------------------------------------------------
    // PEM — byte-for-byte what bsc re-serialises on the server
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the PEM wraps at sixty-four characters`() {
        val pem = mint().pem
        val lines = pem.trimEnd('\n').split('\n')

        assertEquals("-----BEGIN CERTIFICATE-----", lines.first())
        assertEquals("-----END CERTIFICATE-----", lines.last())
        assertTrue(pem.endsWith("\n"), "bsc's toPem ends with a newline; a mismatch shifts the bytes")
        lines.subList(1, lines.size - 1).forEachIndexed { index, line ->
            val isLast = index == lines.size - 3
            if (!isLast) assertEquals(64, line.length, "line ${index + 1} is not 64 characters")
            assertTrue(line.length <= 64)
        }
    }

    @Test
    fun `the PEM parses back to the same certificate bytes`() {
        val minted = mint()
        val fromPem = parse(minted.pem.toByteArray(Charsets.US_ASCII))

        assertContentEquals(minted.der, fromPem.encoded)
    }

    @Test
    fun `the fingerprint is lowercase sha256 hex, and matches the DER`() {
        val minted = mint()
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(minted.der)
            .joinToString("") { "%02x".format(java.util.Locale.US, it) }

        assertEquals(64, minted.fingerprintSha256.length)
        assertEquals(minted.fingerprintSha256.lowercase(), minted.fingerprintSha256)
        assertEquals(expected, minted.fingerprintSha256)
    }

    // -----------------------------------------------------------------------------------------
    // Refusals — a bad input must not become a certificate that registers nothing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a non-RSA key is refused rather than mis-encoded`() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        val failure = assertFailsWith<IllegalArgumentException> {
            MtlsCertificate.selfSign("device", ec, now, serial)
        }
        assertTrue(failure.message!!.contains("RSA"), "the message must say what is wrong: ${failure.message}")
    }

    @Test
    fun `a blank common name is refused`() {
        assertFailsWith<IllegalArgumentException> { mint(commonName = "") }
        assertFailsWith<IllegalArgumentException> { mint(commonName = "   ") }
    }

    @Test
    fun `an implausibly long common name is refused`() {
        assertFailsWith<IllegalArgumentException> { mint(commonName = "d".repeat(65)) }
        // The boundary itself is allowed.
        assertEquals("d".repeat(64), mint(commonName = "d".repeat(64)).commonName)
    }

    @Test
    fun `an empty serial is refused`() {
        assertFailsWith<IllegalArgumentException> { mint(serialBytes = ByteArray(0)) }
    }

    // -----------------------------------------------------------------------------------------
    // Nothing private leaks
    // -----------------------------------------------------------------------------------------

    @Test
    fun `no private key material appears in the DER`() {
        // ⚠️ The certificate is public and gets uploaded, so a stray private exponent here would be
        // a real disclosure — and a hand-rolled encoder is exactly where a fat-fingered argument
        // would put one. A 256-byte needle in an ~800-byte document cannot match by accident.
        val exponent = (keyPair.private as RSAPrivateCrtKey).privateExponent.toByteArray()
        val der = mint().der

        assertFalse(
            der.asList().windowed(exponent.size).any { it == exponent.asList() },
            "the private exponent appears in the certificate",
        )
    }

    @Test
    fun `toString does not print the certificate`() {
        val text = mint().toString()

        assertFalse(text.contains("BEGIN CERTIFICATE"))
        assertTrue(text.contains("pixel-7-pro-430f9ca3"))
    }
}
