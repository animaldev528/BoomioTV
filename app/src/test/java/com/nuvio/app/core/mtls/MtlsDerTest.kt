package com.nuvio.app.core.mtls

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The DER writer's primitives, tested against values that are known independently of it.
 *
 * ⚠️ **Why this file carries its weight.** A hand-rolled encoder has no compiler and no type
 * system standing between it and a certificate that parses in nothing — and the failure is silent
 * until a device cannot reach the server. Two of the primitives below are the ones a first pass
 * gets wrong:
 *
 * - **Length.** Every realistic certificate is longer than 127 bytes, so the short form is right
 *   for `SEQUENCE { OID }` and wrong for the thing that matters. The 127/128 boundary and the
 *   255/256 boundary are asserted directly, because that is where an off-by-one hides.
 * - **Base64 padding.** This package hand-rolls base64 (the header of `MtlsDer.base64` says why),
 *   and the two- and one-byte remainders are exactly the cases a hand-rolled encoder gets wrong.
 *
 * No Robolectric: nothing in `MtlsDer` reaches an Android class, and `java.util.Base64` is used
 * *here only* — as an independent reference the test JVM has and the app deliberately does not
 * depend on.
 */
class MtlsDerTest {

    // -----------------------------------------------------------------------------------------
    // Length encoding — the 127/128 boundary is the whole point
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a short body uses the one byte length form`() {
        val out = MtlsDer.tlv(0x04, ByteArray(127))
        assertEquals(0x04.toByte(), out[0])
        assertEquals(127.toByte(), out[1])
        assertEquals(2 + 127, out.size)
    }

    @Test
    fun `128 bytes crosses into the long form with a minimal byte count`() {
        // ⚠️ The naive implementation writes `81 80` as `82 00 80`, which is not minimal DER and
        // is rejected by strict parsers. The exact length bytes are the assertion.
        val out = MtlsDer.tlv(0x04, ByteArray(128))
        assertEquals(0x04.toByte(), out[0])
        assertEquals(0x81.toByte(), out[1])
        assertEquals(0x80.toByte(), out[2])
        assertEquals(3 + 128, out.size)
    }

    @Test
    fun `256 bytes uses two length bytes`() {
        val out = MtlsDer.tlv(0x04, ByteArray(256))
        assertEquals(0x82.toByte(), out[1])
        assertEquals(0x01.toByte(), out[2])
        assertEquals(0x00.toByte(), out[3])
        assertEquals(4 + 256, out.size)
    }

    @Test
    fun `65536 bytes uses three length bytes`() {
        val out = MtlsDer.tlv(0x04, ByteArray(65536))
        assertEquals(0x83.toByte(), out[1])
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x00), out.copyOfRange(2, 5))
    }

    @Test
    fun `an empty body is a zero length`() {
        assertContentEquals(byteArrayOf(0x30, 0x00), MtlsDer.tlv(0x30, ByteArray(0)))
    }

    // -----------------------------------------------------------------------------------------
    // OIDs — known-good encodings, written out as bytes
    // -----------------------------------------------------------------------------------------

    @Test
    fun `commonName encodes to its published bytes`() {
        // 2.5.4.3 -> 40*2+5 = 0x55, then 0x04, 0x03
        assertContentEquals(
            byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03),
            MtlsDer.oid("2.5.4.3"),
        )
    }

    @Test
    fun `sha256WithRSAEncryption encodes to its published bytes`() {
        // The 113549 arc is the one that exercises multi-byte base-128 (86 F7 0D).
        assertContentEquals(
            byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B),
            MtlsDer.oid("1.2.840.113549.1.1.11"),
        )
    }

    @Test
    fun `clientAuth encodes to its published bytes`() {
        assertContentEquals(
            byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x02),
            MtlsDer.oid("1.3.6.1.5.5.7.3.2"),
        )
    }

    @Test
    fun `an OID with a bad first arc is refused rather than silently re-encoded`() {
        // ⚠️ The first two arcs are packed as `40*a + b`, so a bad `a` does not fail — it produces
        // a *different, valid* OID. Refusing is the only way this is not a silent wrong answer.
        assertFailsWith<IllegalArgumentException> { MtlsDer.oid("3.1.1") }
        assertFailsWith<IllegalArgumentException> { MtlsDer.oid("1.40.1") }
        assertFailsWith<IllegalArgumentException> { MtlsDer.oid("1") }
        assertFailsWith<IllegalArgumentException> { MtlsDer.oid("1.x.1") }
    }

    // -----------------------------------------------------------------------------------------
    // INTEGER — DER is signed, so the top bit is not free
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a positive integer with the top bit set gains a leading zero`() {
        assertContentEquals(
            byteArrayOf(0x02, 0x02, 0x00, 0x80.toByte()),
            MtlsDer.integer(byteArrayOf(0x80.toByte())),
        )
    }

    @Test
    fun `a positive integer without the top bit set is not padded`() {
        assertContentEquals(byteArrayOf(0x02, 0x01, 0x7F), MtlsDer.integer(byteArrayOf(0x7F)))
    }

    @Test
    fun `the version integer is v3`() {
        assertContentEquals(byteArrayOf(0x02, 0x01, 0x02), MtlsDer.integer(2))
    }

    @Test
    fun `an integer with an empty magnitude is refused`() {
        assertFailsWith<IllegalArgumentException> { MtlsDer.integer(ByteArray(0)) }
    }

    // -----------------------------------------------------------------------------------------
    // BIT STRING — the unused-bit count is load-bearing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a bit string of whole bytes declares zero unused bits`() {
        assertContentEquals(
            byteArrayOf(0x03, 0x03, 0x00, 0xAB.toByte(), 0xCD.toByte()),
            MtlsDer.bitString(byteArrayOf(0xAB.toByte(), 0xCD.toByte())),
        )
    }

    @Test
    fun `keyUsage digitalSignature is one byte with seven unused bits`() {
        // ⚠️ Declaring 0 unused bits here is the natural mistake: it claims eight significant bits
        // and a strict parser rejects the extension.
        assertContentEquals(
            byteArrayOf(0x03, 0x02, 0x07, 0x80.toByte()),
            MtlsDer.bitString(byteArrayOf(0x80.toByte()), unusedBits = 7),
        )
    }

    @Test
    fun `an unused bit count over seven is refused`() {
        assertFailsWith<IllegalArgumentException> { MtlsDer.bitString(byteArrayOf(0x00), unusedBits = 8) }
    }

    @Test
    fun `declaring bits unused that are actually set is refused`() {
        // 0x81 with seven unused bits claims the low bit is padding — but it is set.
        assertFailsWith<IllegalArgumentException> { MtlsDer.bitString(byteArrayOf(0x81.toByte()), unusedBits = 7) }
    }

    // -----------------------------------------------------------------------------------------
    // Time — the tag is chosen by the year, per RFC 5280
    // -----------------------------------------------------------------------------------------

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    @Test
    fun `a modern date is a UTCTime with seconds and a literal Z`() {
        val out = MtlsDer.time(utc(2026, 10, 8, 12, 34, 56))
        assertEquals(0x17.toByte(), out[0])
        assertEquals(13 + 2, out.size)
        assertEquals("261008123456Z", String(out.copyOfRange(2, out.size), Charsets.US_ASCII))
    }

    @Test
    fun `a year at the top of the UTCTime range is still a UTCTime`() {
        val out = MtlsDer.time(utc(2049, 12, 31, 23, 59, 59))
        assertEquals(0x17.toByte(), out[0])
        assertEquals("491231235959Z", String(out.copyOfRange(2, out.size), Charsets.US_ASCII))
    }

    @Test
    fun `a year past the UTCTime range becomes a GeneralizedTime`() {
        // RFC 5280 §4.1.2.5 makes this mandatory, and a certificate with a ten-year validity minted
        // in 2045 is exactly how a device would reach it.
        val out = MtlsDer.time(utc(2050, 1, 1, 0, 0, 0))
        assertEquals(0x18.toByte(), out[0])
        assertEquals("20500101000000Z", String(out.copyOfRange(2, out.size), Charsets.US_ASCII))
    }

    @Test
    fun `a year before 1950 becomes a GeneralizedTime`() {
        val out = MtlsDer.time(utc(1949, 6, 1, 0, 0, 0))
        assertEquals(0x18.toByte(), out[0])
        assertEquals("19490601000000Z", String(out.copyOfRange(2, out.size), Charsets.US_ASCII))
    }

    @Test
    fun `the time digits are ASCII whatever the default locale`() {
        // ⚠️ This is the regression test for a real defect: `String.format` without an explicit
        // locale uses the default, and a locale with non-ASCII digits formats these as bytes no
        // parser accepts. The assertion is on the bytes, not on the string, because that is what
        // goes into the certificate.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val out = MtlsDer.time(utc(2026, 10, 8, 12, 34, 56))
            assertEquals("261008123456Z", String(out.copyOfRange(2, out.size), Charsets.US_ASCII))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // -----------------------------------------------------------------------------------------
    // base64 — checked against the JDK implementation, over every remainder case
    // -----------------------------------------------------------------------------------------

    @Test
    fun `base64 matches the platform encoder at every length from 0 to 200`() {
        // Every `length % 3` case is covered by construction, which is what the hand-rolled
        // encoder's two padding branches need.
        val random = java.util.Random(20261008L)
        for (length in 0..200) {
            val bytes = ByteArray(length).also { random.nextBytes(it) }
            assertEquals(
                java.util.Base64.getEncoder().encodeToString(bytes),
                MtlsDer.base64(bytes),
                "base64 differs at length $length",
            )
        }
    }

    @Test
    fun `base64 pads the two-remainder case`() {
        assertEquals("TWFu", MtlsDer.base64("Man".toByteArray()))
        assertEquals("TWE=", MtlsDer.base64("Ma".toByteArray()))
        assertEquals("TQ==", MtlsDer.base64("M".toByteArray()))
        assertEquals("", MtlsDer.base64(ByteArray(0)))
    }

    // -----------------------------------------------------------------------------------------
    // Structure helpers
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a single valued RDN nests set inside sequence`() {
        val out = MtlsDer.rdn("2.5.4.3", MtlsDer.utf8("x"))
        assertEquals(0x30.toByte(), out[0]) // SEQUENCE
        assertEquals(0x31.toByte(), out[2]) // SET
        assertEquals(0x30.toByte(), out[4]) // AttributeTypeAndValue
    }

    @Test
    fun `an explicit context tag is constructed and tagged`() {
        val out = MtlsDer.contextExplicit(3, MtlsDer.sequence())
        assertContentEquals(byteArrayOf(0xA3.toByte(), 0x02, 0x30, 0x00), out)
    }

    @Test
    fun `a boolean true is all ones`() {
        // DER requires 0xFF, not 0x01 — a non-canonical BOOLEAN is a strict-mode parse error.
        assertContentEquals(byteArrayOf(0x01, 0x01, 0xFF.toByte()), MtlsDer.boolean(true))
    }

    @Test
    fun `concat preserves order and size`() {
        val joined = MtlsDer.concat(arrayOf(byteArrayOf(1, 2), byteArrayOf(3), ByteArray(0), byteArrayOf(4, 5)))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), joined)
        assertTrue(joined.isNotEmpty())
    }
}
