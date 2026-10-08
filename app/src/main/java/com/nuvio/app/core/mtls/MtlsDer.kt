package com.nuvio.app.core.mtls

/**
 * Just enough DER to emit one X.509 certificate — and deliberately nothing more.
 *
 * ── Why this file exists instead of a library ────────────────────────────────
 * `docs/mtls-plan.md` P2.2 asks for a self-signed client certificate with "no CA, no CSR, no
 * jSCEP, no BouncyCastle". The same sentence then names `X509v3CertificateBuilder`, which *is*
 * BouncyCastle (`org.bouncycastle.cert.X509v3CertificateBuilder`) — so the sentence contradicts
 * itself, and the prohibition is the half that wins: BouncyCastle is a large dependency to add for
 * one certificate, it has bitten this project's owner before, and it is not on the classpath today
 * (measured — no `bcprov`/`bcpkix` in the version catalog or any build script).
 *
 * What is left is the encoding itself, which is small because the shape is fixed: one certificate,
 * one key type (RSA), one signature algorithm, three extensions. That is this file.
 *
 * ── What this is not ─────────────────────────────────────────────────────────
 * It is **not** a general ASN.1 library. It has no parser, no SET ordering rules, and no support
 * for tags above 30. It writes the bytes it is handed. The oracle is not a diff against a
 * reference encoder — it is `CertificateFactory.getInstance("X.509")` in the host test, which is a
 * real parser that rejects malformed DER, plus `X509Certificate.verify()`, which fails unless the
 * signature covers the exact TBS bytes this file produced.
 *
 * ⚠️ **Length encoding is the trap in a hand-rolled DER writer.** An RSA-2048 SPKI plus the
 * extensions pushes `TBSCertificate` past 127 bytes, so the *short form* length (a single byte,
 * `< 0x80`) is not enough and the long form is required — but the short form is what a first
 * implementation writes, and it produces a certificate that looks right and parses in nothing.
 * `derLength` below is exercised at both sides of that boundary by `MtlsDerTest`.
 */
internal object MtlsDer {

    /** Universal, constructed, SEQUENCE (0x10 | 0x20). */
    private const val TAG_SEQUENCE = 0x30

    /** Universal, constructed, SET (0x11 | 0x20). */
    private const val TAG_SET = 0x31

    /** Universal, primitive, INTEGER. */
    private const val TAG_INTEGER = 0x02

    /** Universal, primitive, BIT STRING. */
    private const val TAG_BIT_STRING = 0x03

    /** Universal, primitive, OCTET STRING. */
    private const val TAG_OCTET_STRING = 0x04

    /** Universal, primitive, NULL. */
    private const val TAG_NULL = 0x05

    /** Universal, primitive, OBJECT IDENTIFIER. */
    private const val TAG_OID = 0x06

    /** Universal, primitive, UTF8String. */
    private const val TAG_UTF8_STRING = 0x0C

    /** Universal, primitive, BOOLEAN. */
    private const val TAG_BOOLEAN = 0x01

    /** Universal, primitive, UTCTime. */
    private const val TAG_UTC_TIME = 0x17

    /** Universal, primitive, GeneralizedTime. */
    private const val TAG_GENERALIZED_TIME = 0x18

    /**
     * A tag-length-value triple.
     *
     * `tag` is the **complete** identifier byte, including the constructed bit — this writer has no
     * tag-class arithmetic because every tag it needs is known at the call site.
     */
    fun tlv(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArray(1 + 5 + body.size)
        out[0] = tag.toByte()
        val lengthBytes = writeLength(out, 1, body.size)
        body.copyInto(out, 1 + lengthBytes)
        return out.copyOf(1 + lengthBytes + body.size)
    }

    /**
     * Write a DER length at `offset`, returning how many bytes it took.
     *
     * Short form is one byte and covers `0..127`; long form is `0x80 | n` followed by `n`
     * big-endian bytes. `n` is minimal: a length of 256 is `82 01 00`, never `83 00 01 00`.
     */
    private fun writeLength(out: ByteArray, offset: Int, length: Int): Int {
        require(length >= 0) { "DER length cannot be negative: $length" }
        if (length < 0x80) {
            out[offset] = length.toByte()
            return 1
        }
        // Minimal big-endian byte count for `length`.
        var n = 0
        var v = length
        while (v > 0) {
            n++
            v = v ushr 8
        }
        out[offset] = (0x80 or n).toByte()
        for (i in 0 until n) {
            out[offset + 1 + i] = (length ushr (8 * (n - 1 - i))).toByte()
        }
        return 1 + n
    }

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(TAG_SEQUENCE, concat(parts))

    fun setOf(vararg parts: ByteArray): ByteArray = tlv(TAG_SET, concat(parts))

    fun concat(parts: Array<out ByteArray>): ByteArray {
        var size = 0
        for (p in parts) size += p.size
        val out = ByteArray(size)
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    fun nullValue(): ByteArray = byteArrayOf(TAG_NULL.toByte(), 0x00)

    fun boolean(value: Boolean): ByteArray = byteArrayOf(TAG_BOOLEAN.toByte(), 0x01, if (value) 0xFF.toByte() else 0x00)

    /**
     * An INTEGER from caller-supplied **magnitude** bytes.
     *
     * A leading `0x00` is inserted when the top bit is set, because DER INTEGER is signed and
     * `0x80…` would otherwise decode as a negative number — which for a serial number is the kind
     * of defect that shows up only in whichever tool happens to print it.
     *
     * ⚠️ The caller is responsible for having stripped leading zeros; this does not, because
     * silently re-encoding here would hide a caller that handed over a non-minimal value.
     */
    fun integer(magnitude: ByteArray): ByteArray {
        require(magnitude.isNotEmpty()) { "DER INTEGER cannot be empty" }
        val body = if (magnitude[0].toInt() and 0x80 != 0) {
            byteArrayOf(0x00) + magnitude
        } else {
            magnitude
        }
        return tlv(TAG_INTEGER, body)
    }

    /** A small non-negative INTEGER, for the few places a literal is clearer than bytes. */
    fun integer(value: Int): ByteArray {
        require(value >= 0) { "DER INTEGER here is non-negative only: $value" }
        var n = 1
        var v = value
        while (v > 0x7F) {
            n++
            v = v ushr 8
        }
        val body = ByteArray(n)
        for (i in 0 until n) {
            body[n - 1 - i] = (value ushr (8 * i)).toByte()
        }
        // Top bit set means the value would read as negative; one more byte of zero fixes it.
        return if (body[0].toInt() and 0x80 != 0) tlv(TAG_INTEGER, byteArrayOf(0x00) + body) else tlv(TAG_INTEGER, body)
    }

    /** A BIT STRING whose bits are all whole bytes — `unusedBits` is always 0 here. */
    fun bitString(bytes: ByteArray): ByteArray = bitString(bytes, unusedBits = 0)

    /**
     * A BIT STRING with a stated number of unused trailing bits.
     *
     * DER requires the count to be minimal: `keyUsage` with only `digitalSignature` (bit 0) set is
     * one byte, `0x80`, with **seven** unused bits. Saying `0` there is the natural mistake and
     * makes the extension claim eight significant bits, which a strict parser rejects.
     */
    fun bitString(bytes: ByteArray, unusedBits: Int): ByteArray {
        require(unusedBits in 0..7) { "unusedBits must be 0..7, was $unusedBits" }
        require(bytes.isNotEmpty()) { "a DER BIT STRING needs at least one content byte" }
        if (unusedBits > 0) {
            val last = bytes[bytes.size - 1].toInt() and 0xFF
            require(last and ((1 shl unusedBits) - 1) == 0) {
                "the $unusedBits bits declared unused must be zero in the final byte"
            }
        }
        return tlv(TAG_BIT_STRING, byteArrayOf(unusedBits.toByte()) + bytes)
    }

    fun octetString(bytes: ByteArray): ByteArray = tlv(TAG_OCTET_STRING, bytes)

    fun utf8(value: String): ByteArray = tlv(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))

    /**
     * An OBJECT IDENTIFIER from its dotted form.
     *
     * The first two arcs are packed into one byte as `40 * a + b` (X.690 §8.19); every arc after
     * that is base-128 with the continuation bit set on all but the last byte. The `require` on the
     * first two arcs is not decoration — the packing silently produces a different OID rather than
     * failing, so a typo like `1.3.6.1.5.5.7.3.2` written as `1.40.…` would be un-caught.
     */
    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map {
            it.toIntOrNull() ?: throw IllegalArgumentException("OID arc is not a number: \"$it\" in \"$dotted\"")
        }
        require(arcs.size >= 2) { "an OID needs at least two arcs: \"$dotted\"" }
        require(arcs[0] in 0..2) { "first OID arc must be 0..2, was ${arcs[0]} in \"$dotted\"" }
        require(arcs[1] in 0..39) { "second OID arc must be 0..39, was ${arcs[1]} in \"$dotted\"" }

        val body = ArrayList<Byte>(arcs.size)
        appendBase128(body, arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            require(arcs[i] >= 0) { "OID arcs are non-negative, was ${arcs[i]} in \"$dotted\"" }
            appendBase128(body, arcs[i])
        }
        return tlv(TAG_OID, body.toByteArray())
    }

    private fun appendBase128(out: MutableList<Byte>, value: Int) {
        var v = value
        val chunk = ArrayList<Byte>(5)
        do {
            chunk.add((v and 0x7F).toByte())
            v = v ushr 7
        } while (v > 0)
        // Emitted most-significant first, continuation bit on everything but the last.
        for (i in chunk.indices.reversed()) {
            val last = i == 0
            out.add(if (last) chunk[i] else (chunk[i].toInt() or 0x80).toByte())
        }
    }

    /**
     * A `[n] EXPLICIT` wrapper — context-class, constructed, with the inner element's full DER as
     * the content. Used for the version field and for the extensions block.
     */
    fun contextExplicit(tagNumber: Int, inner: ByteArray): ByteArray {
        require(tagNumber in 0..30) { "context tag out of the range this writer supports: $tagNumber" }
        return tlv(0xA0 or tagNumber, inner)
    }

    /**
     * A `Time` choice. RFC 5280 §4.1.2.5 requires UTCTime for years 1950–2049 and GeneralizedTime
     * outside that, and picky parsers enforce it — so the choice is made here rather than by the
     * caller.
     *
     * UTCTime is `YYMMDDHHMMSSZ`: seconds and a literal Z are mandatory (the optional-minutes and
     * offset forms are legal ASN.1 and rejected by RFC 5280). The two-digit year is `year - 2000`
     * for 2000–2049 and `year - 1900` below, which is why the branch decides both the tag and the
     * year format together.
     */
    fun time(epochMillis: Long): ByteArray {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMillis
        val year = cal.get(java.util.Calendar.YEAR)
        // ⚠️ Locale.US is load-bearing, not tidiness. `String.format` uses the default locale, and
        // a locale with non-ASCII digits (Arabic-Indic, Devanagari, …) formats `36` as something
        // that is not the byte 0x33 0x36 — producing a DER time that no parser accepts, on exactly
        // the devices least likely to be in the test matrix.
        val body = buildString {
            if (year in 1950..2049) {
                append("%02d".format(java.util.Locale.US, year % 100))
            } else {
                append("%04d".format(java.util.Locale.US, year))
            }
            append("%02d".format(java.util.Locale.US, cal.get(java.util.Calendar.MONTH) + 1))
            append("%02d".format(java.util.Locale.US, cal.get(java.util.Calendar.DAY_OF_MONTH)))
            append("%02d".format(java.util.Locale.US, cal.get(java.util.Calendar.HOUR_OF_DAY)))
            append("%02d".format(java.util.Locale.US, cal.get(java.util.Calendar.MINUTE)))
            append("%02d".format(java.util.Locale.US, cal.get(java.util.Calendar.SECOND)))
            append('Z')
        }
        val tag = if (year in 1950..2049) TAG_UTC_TIME else TAG_GENERALIZED_TIME
        return tlv(tag, body.toByteArray(Charsets.US_ASCII))
    }

    /** `Name` for a single-valued RDN: `SEQUENCE { SET { SEQUENCE { OID, value } } }`. */
    fun rdn(attributeOid: String, value: ByteArray): ByteArray =
        sequence(setOf(sequence(oid(attributeOid), value)))

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Standard base64 with `=` padding, unwrapped.
     *
     * ⚠️ **Hand-rolled to keep this package free of Android.** The two obvious alternatives are
     * both wrong here: `java.util.Base64` is **API 26** and `minSdk` is 24, so on a 7.0/7.1 device
     * it is a `NoClassDefFoundError` at the moment a certificate is minted — invisible in every
     * host test, because the host JVM has it natively. `android.util.Base64` works everywhere but
     * is an Android class, which would drag Robolectric into a test suite that is otherwise plain
     * JVM by design (see `MtlsDer`'s header for why that matters).
     *
     * The oracle is not a reference implementation read by eye: `MtlsDerTest` compares this against
     * `java.util.Base64` — which the *test* JVM has, even though the app cannot rely on it — over
     * every length from 0 to 200 bytes, so all three padding cases are covered by construction.
     */
    fun base64(bytes: ByteArray): String {
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(B64_ALPHABET[(n ushr 18) and 0x3F])
            out.append(B64_ALPHABET[(n ushr 12) and 0x3F])
            out.append(B64_ALPHABET[(n ushr 6) and 0x3F])
            out.append(B64_ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(B64_ALPHABET[(n ushr 18) and 0x3F])
                out.append(B64_ALPHABET[(n ushr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(B64_ALPHABET[(n ushr 18) and 0x3F])
                out.append(B64_ALPHABET[(n ushr 12) and 0x3F])
                out.append(B64_ALPHABET[(n ushr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }
}
