package com.nuvio.app.core.mtls

import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * Mint the self-signed client certificate the edge will compare against its allow-list.
 *
 * This is `docs/mtls-plan.md` P2.2. It is the whole of the client's certificate logic, and it is
 * small on purpose — the design it serves has **no CA**, so there is nothing here to sign with, no
 * chain to build, and no CSR to send. A device signs its own certificate with its own key, hands
 * the certificate to the server over a session it is already authenticated on, and the server puts
 * it in a directory. Trust comes from being *listed*, never from a signature.
 *
 * ── What the certificate must satisfy, and where that is decided ─────────────
 * Two independent consumers, with different strictness:
 *
 * 1. **`bsc/lib/overlay-cert.js`** — the registration endpoint's validator. It requires exactly
 *    three things of the bytes this file produces: a single CN equal to the name the server
 *    assigned the device, an RSA key of at least 2048 bits, and an `extendedKeyUsage` containing
 *    `clientAuth`. It deliberately does **not** check expiry, and must not (see that file's header).
 * 2. **`tls.client_auth.verifier.leafdir`** at the edge — compares the raw DER of `rawCerts[0]`
 *    against each file in the allow-list directory, using `x509.Certificate.Equal`. It parses
 *    nothing. It cares only that the bytes are byte-stable, which is why none of this is allowed to
 *    depend on wall-clock time or on a random nonce beyond the serial.
 *
 * ── Two things this deliberately gets right that a first pass would not ───────
 * **`basicConstraints` and `keyUsage` are both emitted.** Not because either consumer reads them
 * (neither does) but because of a measured trap on the server side: a self-signed certificate
 * carrying *only* `extendedKeyUsage` makes Node report `cert.ca === true`, via OpenSSL's legacy
 * "no basicConstraints and no keyUsage ⇒ CA" fallback. bsc does not check `ca` and says so in its
 * header — but emitting an explicit `CA:FALSE` means the certificate does not read as a CA to
 * anything else that looks at it, which is a better artifact to have written into an allow-list
 * directory an operator may `openssl x509 -text`.
 *
 * **The signature is `sha256WithRSA` (PKCS#1 v1.5), not PSS.** The TLS 1.3 handshake *does* use
 * RSA-PSS — which is why §14.1's `KeyGenParameterSpec` allows it — but that is `CertificateVerify`,
 * a different signature over different bytes. The certificate's own `signatureAlgorithm` uses the
 * PKCS#1 v1.5 form, which every TLS stack accepts for a certificate and which is what the
 * `DIGEST_SHA256` + `SIGNATURE_PADDING_RSA_PKCS1` half of the spec exists to permit.
 *
 * ⚠️ **Nothing here reads the clock for anything but `Validity`.** A certificate that varied
 * between two runs on the same device would put two different files in the allow-list for one
 * device, and the edge compares bytes — so re-registration is only idempotent because the caller
 * re-uses the stored certificate rather than re-minting (`MtlsIdentity`). Re-minting is a
 * deliberate act, not a retry.
 */
internal object MtlsCertificate {

    /** `id-kp-clientAuth`. The one EKU this certificate carries. */
    const val CLIENT_AUTH_EKU_OID: String = "1.3.6.1.5.5.7.3.2"

    /** `id-at-commonName`. The server binds the certificate's CN to the device it assigned. */
    const val COMMON_NAME_OID: String = "2.5.4.3"

    private const val RDN_RSA_ENCRYPTION = "1.2.840.113549.1.1.1"
    private const val ALG_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val EXT_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val EXT_KEY_USAGE = "2.5.29.15"
    private const val EXT_EXTENDED_KEY_USAGE = "2.5.29.37"

    /**
     * Ten years. The edge never consults validity, so this is not a security parameter — it is
     * hygiene, so that a tool an operator points at the allow-list has something sensible to print.
     * The plan calls it "long validity" and this matches the ten-year certificates the edge's own
     * handshake test mints.
     */
    val VALIDITY: Long = TimeUnit.DAYS.toMillis(3650)

    /**
     * Backdate `notBefore` by a day.
     *
     * A device whose clock is behind — a fresh boot before NTP lands, a TV that has been unplugged
     * — would otherwise mint a certificate that is not yet valid. That is invisible today, because
     * nothing checks validity, but it is the kind of latent wrongness that turns into a mystery the
     * day someone adds a check. A day is generous and costs nothing.
     */
    val CLOCK_SKEW: Long = TimeUnit.DAYS.toMillis(1)

    /**
     * Mint a self-signed certificate for `keyPair`, valid from `now - CLOCK_SKEW`.
     *
     * @param commonName the **server-assigned** device name from the enroll reply — never
     *   `Build.MODEL`. bsc derives the expected value with `peerNameFor(session.device_id)` and
     *   rejects a mismatch, so a name taken from the device would register nothing.
     * @param nowMillis the clock. Injected so a test is not a function of the wall clock.
     * @return the DER bytes, and the same bytes as PEM for the wire.
     */
    fun selfSign(
        commonName: String,
        keyPair: KeyPair,
        nowMillis: Long,
        serial: ByteArray = randomSerial(),
    ): ClientCertificate {
        require(commonName.isNotBlank()) { "a client certificate needs a CN; the server assigns it" }
        // The wire's own validator caps the PEM at 8 KiB. A CN long enough to approach that is not
        // a name the server would assign, and silently minting an unregisterable certificate is a
        // worse outcome than refusing here.
        require(commonName.length <= 64) { "CN is implausibly long for a device name: ${commonName.length} chars" }
        require(serial.isNotEmpty()) { "a certificate needs a serial number" }

        val publicKey = keyPair.public as? RSAPublicKey
            ?: throw IllegalArgumentException(
                "only RSA keys are supported (P2.1 generates RSA-2048); got ${keyPair.public.algorithm}",
            )

        val notBefore = nowMillis - CLOCK_SKEW
        val notAfter = nowMillis + VALIDITY

        // Self-signed: issuer and subject are the same Name, and it is one attribute wide.
        val name = MtlsDer.rdn(COMMON_NAME_OID, MtlsDer.utf8(commonName))
        val algorithm = sha256WithRsa()

        val tbs = MtlsDer.sequence(
            // [0] EXPLICIT version, v3 (2). v3 is required for extensions to be legal at all.
            MtlsDer.contextExplicit(0, MtlsDer.integer(2)),
            MtlsDer.integer(serial),
            algorithm,
            name, // issuer
            MtlsDer.sequence(MtlsDer.time(notBefore), MtlsDer.time(notAfter)),
            name, // subject
            subjectPublicKeyInfo(publicKey),
            MtlsDer.contextExplicit(3, extensions()),
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }

        return ClientCertificate(
            commonName = commonName,
            der = MtlsDer.sequence(tbs, algorithm, MtlsDer.bitString(signature)),
            notBeforeMillis = notBefore,
            notAfterMillis = notAfter,
        )
    }

    private fun sha256WithRsa(): ByteArray =
        MtlsDer.sequence(MtlsDer.oid(ALG_SHA256_WITH_RSA), MtlsDer.nullValue())

    /**
     * `SubjectPublicKeyInfo`, built from the modulus and exponent rather than from
     * `PublicKey.encoded`.
     *
     * ⚠️ `getEncoded()` on a **public** key is specified to be the X.509 SubjectPublicKeyInfo, and
     * on the JVM it is. On Android it is returned by the key-store provider rather than by the JCA
     * spec — and this certificate's bytes must be identical on every device and every API level,
     * because the edge compares them. Deriving the SPKI from the two numbers the RSA key is
     * actually made of removes the provider from the trust path entirely.
     */
    private fun subjectPublicKeyInfo(publicKey: RSAPublicKey): ByteArray =
        MtlsDer.sequence(
            MtlsDer.sequence(MtlsDer.oid(RDN_RSA_ENCRYPTION), MtlsDer.nullValue()),
            // RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }
            MtlsDer.bitString(
                MtlsDer.sequence(
                    MtlsDer.integer(publicKey.modulus.toByteArray()),
                    MtlsDer.integer(publicKey.publicExponent.toByteArray()),
                ),
            ),
        )

    /**
     * The three extensions, in the order a reader expects them.
     *
     * `basicConstraints` and `keyUsage` are marked **critical** — they are constraints, and a
     * consumer that does not understand them must refuse the certificate rather than guess.
     * `extendedKeyUsage` is left non-critical, which is the near-universal convention and what
     * OpenSSL emits.
     */
    private fun extensions(): ByteArray =
        MtlsDer.sequence(
            extension(
                oid = EXT_BASIC_CONSTRAINTS,
                critical = true,
                // BasicConstraints ::= SEQUENCE { cA BOOLEAN DEFAULT FALSE, pathLen OPTIONAL }
                // An empty SEQUENCE is the canonical encoding of cA = FALSE: the value equals its
                // DEFAULT, and DER requires a DEFAULT value to be omitted rather than written out.
                value = MtlsDer.sequence(),
            ),
            extension(
                oid = EXT_KEY_USAGE,
                critical = true,
                // digitalSignature is bit 0, so a single byte with the high bit set and seven
                // unused bits. This is what makes the key usable for a TLS handshake signature and
                // for nothing else.
                value = MtlsDer.bitString(byteArrayOf(0x80.toByte()), unusedBits = 7),
            ),
            extension(
                oid = EXT_EXTENDED_KEY_USAGE,
                critical = false,
                value = MtlsDer.sequence(MtlsDer.oid(CLIENT_AUTH_EKU_OID)),
            ),
        )

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) {
            MtlsDer.sequence(MtlsDer.oid(oid), MtlsDer.boolean(true), MtlsDer.octetString(value))
        } else {
            // The FALSE is the DEFAULT, so DER omits it — and a parser handed an explicit
            // `BOOLEAN FALSE` in a strict-DER reader is entitled to complain.
            MtlsDer.sequence(MtlsDer.oid(oid), MtlsDer.octetString(value))
        }

    /**
     * A 16-byte positive serial.
     *
     * The first byte is forced into `0100_0000`–`0111_1111`, which does two things at once: the
     * high bit stays clear so the INTEGER is unambiguously positive with no leading `0x00` pad
     * needed, and the second-highest bit stays set so the value can never encode short. A serial of
     * zero is also illegal per RFC 5280 §4.1.2.2, and this cannot produce one.
     */
    fun randomSerial(random: SecureRandom = SecureRandom()): ByteArray {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes[0] = ((bytes[0].toInt() and 0x7F) or 0x40).toByte()
        return bytes
    }

    /**
     * Base64 with 64-character lines and a trailing newline — byte-for-byte what
     * `bsc/lib/overlay-cert.js`'s `toPem` produces.
     *
     * That matters more than formatting taste: the server re-serialises the certificate from the
     * parsed DER before storing it, so if the client's PEM matched a different layout the bytes on
     * disk would differ from the bytes the client believes it registered. They match, so the
     * fingerprint the server returns is the fingerprint of the file the client sent.
     */
    fun toPem(der: ByteArray): String {
        val body = MtlsDer.base64(der).chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }
}

/**
 * A minted client certificate. Holds no private material — the key stays in the AndroidKeyStore
 * and is never an argument to anything in this package.
 */
internal class ClientCertificate(
    val commonName: String,
    val der: ByteArray,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
) {
    val pem: String get() = MtlsCertificate.toPem(der)

    /** SHA-256 over the DER, lowercase hex — the same value bsc returns as `fingerprint_sha256`. */
    val fingerprintSha256: String
        get() = java.security.MessageDigest.getInstance("SHA-256")
            .digest(der)
            .joinToString("") { "%02x".format(java.util.Locale.US, it) }

    // Kotlin's data-class equals would compare the ByteArray by identity, which makes two
    // certificates with identical DER compare unequal — precisely the confusion this type exists to
    // avoid. Written out instead.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ClientCertificate &&
            commonName == other.commonName &&
            der.contentEquals(other.der) &&
            notBeforeMillis == other.notBeforeMillis &&
            notAfterMillis == other.notAfterMillis)

    override fun hashCode(): Int {
        var result = commonName.hashCode()
        result = 31 * result + der.contentHashCode()
        result = 31 * result + notBeforeMillis.hashCode()
        result = 31 * result + notAfterMillis.hashCode()
        return result
    }

    /** Never prints the PEM; a certificate is public but a log line is not the place for it. */
    override fun toString(): String =
        "ClientCertificate(cn=$commonName, sha256=${fingerprintSha256.take(16)}…, der=${der.size}B)"
}
