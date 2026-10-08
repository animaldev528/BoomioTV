package com.nuvio.app.core.mtls

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The device's mTLS identity: one non-exportable RSA-2048 key in the `AndroidKeyStore`, and — once
 * registration succeeds — the certificate that key minted, bound to it.
 *
 * This is `docs/mtls-plan.md` P2.1. The key never leaves the keystore, not even to this process:
 * the private half is only ever handed to a `Signature` or to Conscrypt, which is what makes the
 * whole no-CA design safe. If the key could be exported, a certificate in an allow-list would be a
 * bearer token; because it cannot, possession of the *hardware* is the credential.
 *
 * ── The one thing this file is careful about ─────────────────────────────────
 * ⚠️ **The certificate is bound into the keystore by [installCertificate], and that is the point.**
 * §14.2's checklist asks for `setKeyEntry(alias, priv, null, arrayOf(cert))`, and the payoff is not
 * tidiness: with both halves under one alias, the TLS attach becomes
 * `KeyManagerFactory.getInstance("X509").init(keyStore, null)` — the platform picks the alias and
 * builds the `X509KeyManager`. Without it, P2.4 has to hand-write an `X509KeyManager` that returns
 * the right pair for the right server, and that hand-written class is exactly where a mTLS client
 * silently sends *no* certificate. The keystore is the source of truth, so there is nothing to get
 * out of sync.
 *
 * ── ⚠️ A deliberate deviation from §14.2's checklist, and why ────────────────
 * The checklist says "Key generated once; **delete any old entry first**". Taken literally that
 * means `generate()` deletes before it creates — and that is unsafe here, because generation is the
 * one step that can fail for a reason outside this app's control (a device with no StrongBox and an
 * OEM keystore that rejects the spec). Deleting first would destroy a working key on the way to an
 * attempt that might not produce a replacement, which turns "StrongBox was unavailable" into "this
 * device now has no identity and its registered certificate is orphaned".
 *
 * So deletion is a separate, explicit act ([delete]) and [loadOrCreate] never generates over a live
 * key. The checklist's actual intent — never *inherit* a stale entry when minting a fresh one — is
 * still met, because AndroidKeyStore's `KeyPairGenerator` is atomic: it either installs a complete
 * keypair under the alias or throws, so a half-made entry is not a state that exists.
 *
 * ⚠️ **Nothing here is unit-tested, and it cannot be.** `AndroidKeyStore` and
 * `KeyGenParameterSpec` do not exist off a device, and Robolectric does not emulate a real keystore
 * provider. The compile is the only automated gate; the rest is the device test in the plan's P2
 * verify line. The one part with actual logic — the attempt order — lives in [MtlsStrongBox] so
 * that it, at least, is asserted in the host suite.
 */
internal object MtlsIdentity {

    /**
     * One alias, versioned.
     *
     * Versioned because the alias is the *only* thing that ties a keystore entry to this feature:
     * if a future change needs a different key type or a shorter-lived scheme, a new alias leaves
     * the old entry alone and revocable rather than silently reinterpreting it. The allow-list is
     * keyed by certificate bytes, so an orphaned alias costs a revocation, not a mystery.
     */
    const val KEY_ALIAS: String = "boomio.mtls.client.v1"

    private const val TAG = "BoomioMtls"
    private const val PROVIDER = "AndroidKeyStore"

    /** Whether this device already holds a client key. */
    fun hasKey(): Boolean = keyStore().containsAlias(KEY_ALIAS)

    /**
     * The identity to use: the existing key if there is one, otherwise a newly generated one.
     *
     * ⚠️ **Callers must prefer this over [generate].** Re-generating replaces the key, which
     * invalidates the certificate already registered in the edge's allow-list — the device would
     * then hold a certificate that signs with the wrong key and a registration that names the wrong
     * bytes, and P2.6's whole rule ("re-register the existing certificate, do not mint a new key")
     * exists to stop exactly that.
     */
    fun loadOrCreate(): KeyPair = existing() ?: generate()

    /** The existing keypair, or null. Never generates. */
    fun existing(): KeyPair? {
        val keyStore = keyStore()
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
        if (privateKey == null && publicKey == null) return null
        // ⚠️ Half an entry is not a state AndroidKeyStore produces, so reaching here means something
        // outside this app touched the alias. Refusing beats returning a pair with a null half.
        check(privateKey != null && publicKey != null) {
            "keystore alias '$KEY_ALIAS' is in a partial state (key=${privateKey != null}, cert=${publicKey != null})"
        }
        return KeyPair(publicKey, privateKey)
    }

    /**
     * Generate a fresh RSA-2048 key, StrongBox if the device has it and the TEE otherwise.
     *
     * ⚠️ **Destructive to the current identity** — see [loadOrCreate]. Only call this when there is
     * no key, or when the caller has deliberately decided to re-key (which also means re-registering
     * and revoking the old certificate).
     *
     * The spec is §14.1 verbatim, and its header says every line is load-bearing. The three entries
     * that are load-bearing *for TLS specifically* are `DIGEST_NONE`, `ENCRYPTION_PADDING_NONE` and
     * `SIGNATURE_PADDING_RSA_PSS` plus `setRandomizedEncryptionRequired(false)` — Conscrypt performs
     * raw RSA during the handshake and the TEE refuses it without them. The lessons doc's summary is
     * the right warning to keep in view: *"Every assumption you make about how TLS uses your key is
     * wrong."*
     */
    fun generate(): KeyPair {
        var failure: Exception? = null
        for (strongBox in MtlsStrongBox.attempts(Build.VERSION.SDK_INT)) {
            try {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER)
                generator.initialize(spec(KEY_ALIAS, strongBox))
                val keyPair = generator.generateKeyPair()
                Log.i(TAG, "Generated RSA-2048 client key in ${if (strongBox) "StrongBox" else "TEE"}")
                return keyPair
            } catch (e: Exception) {
                // ⚠️ Deliberately broad, and the retry is what makes that safe. The failure a
                // StrongBox request produces is not one type across API levels and vendors —
                // `StrongBoxUnavailableException` on 28+, a plain `InvalidAlgorithmParameterException`
                // ("StrongBox not supported") on some OEM keystores, wrapped in `ProviderException`
                // on others. Classifying it would mean guessing, and a wrong "that wasn't a
                // StrongBox problem, rethrow" is worse than a retry: if the cause is real, the TEE
                // attempt below fails the same way and *that* is what propagates.
                failure = e
                Log.w(
                    TAG,
                    "Client key generation failed with strongBox=$strongBox" +
                        if (strongBox) "; falling back to the TEE" else "; giving up",
                    e,
                )
            }
        }
        throw IllegalStateException("Could not generate an mTLS client key on this device", failure)
    }

    /**
     * Bind a minted certificate to the key so the platform's own `X509KeyManager` can find it.
     *
     * The certificate is parsed back from DER rather than being constructed here: `setKeyEntry`
     * wants a `java.security.cert.Certificate`, and `MtlsCertificate` deliberately produces bytes
     * (the edge compares raw DER, so the bytes are the artifact). Re-parsing is how the bytes that
     * get registered and the bytes that get stored are guaranteed to be the same ones.
     *
     * AndroidKeyStore verifies that the certificate's public key is the entry's public key, so a
     * mismatch is refused here rather than becoming a handshake that fails somewhere else.
     */
    fun installCertificate(keyPair: KeyPair, certificate: ClientCertificate) {
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate.der))
        keyStore().setKeyEntry(KEY_ALIAS, keyPair.private, null, arrayOf(parsed))
    }

    /**
     * The certificate currently bound to the alias.
     *
     * ⚠️ Before [installCertificate] runs, this returns AndroidKeyStore's own placeholder
     * self-signed certificate (`CN=Unverified…`), not the registered one — the keystore creates a
     * stub for every generated keypair. A caller deciding "is this device registered?" must not use
     * this as the test; that answer lives in [ClientCertificate]'s stored PEM.
     */
    fun certificate(): X509Certificate? = keyStore().getCertificate(KEY_ALIAS) as? X509Certificate

    /** Remove the identity. The registered certificate must be revoked server-side separately. */
    fun delete() {
        keyStore().deleteEntry(KEY_ALIAS)
        Log.i(TAG, "Deleted the mTLS client key")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /**
     * §14.1's spec, with `setIsStrongBoxBacked` applied only where the API exists.
     *
     * The guard is not defensive style: `setIsStrongBoxBacked` is API 28 and `minSdk` is 24, so an
     * unguarded call is a `NoSuchMethodError` on a 7.x device — at the moment a user enrols, which
     * is the worst possible time. [MtlsStrongBox.attempts] never asks for StrongBox below 28, so
     * the flag would be `false` anyway; the guard is there because the *method* is absent.
     */
    private fun spec(alias: String, strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT,
        )
            .setKeySize(2048)
            .setDigests(
                KeyProperties.DIGEST_SHA256, // standard TLS
                KeyProperties.DIGEST_SHA512, // future-proofing
                KeyProperties.DIGEST_NONE, // raw RSA for Conscrypt
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, // TLS 1.2 CertificateVerify
                KeyProperties.SIGNATURE_PADDING_RSA_PSS, // TLS 1.3 CertificateVerify
            )
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1,
                KeyProperties.ENCRYPTION_PADDING_NONE, // Conscrypt raw RSA handshake
            )
            .setRandomizedEncryptionRequired(false) // required alongside PADDING_NONE

        if (Build.VERSION.SDK_INT >= MtlsStrongBox.MIN_SDK) {
            builder.setIsStrongBoxBacked(strongBox)
        }
        return builder.build()
    }
}
