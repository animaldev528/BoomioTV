package com.nuvio.app.core.overlay

/**
 * The provisioning handshake's cryptography, as Kotlin sees it.
 *
 * **Why this is a seam and not a call.** The real implementation is four lines of
 * delegation to the `overlaywg` AAR — but that AAR is a JNI library that loads a `.so` and
 * needs a device, so anything calling it directly cannot be tested on the host JVM at all.
 * With the seam, the whole protocol client — the framing, the handshake sequencing, the
 * counter discipline, every failure path — is exercised on the host against a fake, and
 * what is left untested is delegation.
 *
 * **Why the crypto lives in Go rather than here.** The client needs X25519 and
 * HKDF-SHA256 before it can do anything, and this module has neither: there is no
 * BouncyCastle and no Tink on the classpath, and JCA's `XDH` only exists from API 33 while
 * `minSdk` is 24. The `overlaywg` package already vendors `golang.org/x/crypto` and already
 * calls curve25519 to mint the device's WireGuard keypair, so the primitives cost nothing
 * there — and the alternative, hand-rolling X25519 in Kotlin, is the one thing that must
 * never be written.
 *
 * ⚠️ **The seam is narrow on purpose.** It exposes sealing and opening, never a raw key and
 * never a shared secret. Go keeps the AEAD keys inside the session object, so a Kotlin bug
 * cannot leak them into a log or into `BoomioConfig` — the same reason
 * [OverlayWgBinding.redactKeys] exists on the tunnel side.
 */
internal interface OverlayProvisionCrypto {

    /**
     * Completes the key agreement and returns a sealer for the rest of the connection.
     *
     * [devicePrivateKeyB64] is this device's **WireGuard** private key — the same one the
     * tunnel uses, which is the point: the device has to hold a keypair to enroll at all,
     * so the handshake reuses material that already exists rather than inventing a second
     * identity. [serverPublicKeyB64] is the `ppk` read out of the DuckDNS tuple, and
     * [clientNonceB64]/[serverNonceB64] are the two 32-byte nonces, client first.
     *
     * Throws [OverlayProvisionException.HandshakeFailed] if the material is malformed or if
     * the agreement produces a degenerate value — a low-order server key forces a constant
     * shared secret, and refusing it is the entire purpose of the check.
     */
    fun session(
        devicePrivateKeyB64: String,
        serverPublicKeyB64: String,
        clientNonceB64: String,
        serverNonceB64: String,
    ): OverlayProvisionSealer
}

/**
 * One direction-pair of AEAD keys and the two counters that go with them.
 *
 * The counters are the replay defence: they are never transmitted, each side simply expects
 * the next number, and both directions start at **0 for their own counter**. `HELLO_ACK` is
 * the server's 0 and the device's first sealed message is its own 0.
 *
 * ⚠️ Session state is mutable and a sealer is **not** safe to use from two threads. One
 * connection, one sealer, one calling thread.
 */
internal interface OverlayProvisionSealer {

    /** Encrypts one message and advances the send counter. */
    fun seal(plaintext: ByteArray): ByteArray

    /**
     * Decrypts one message and advances the receive counter.
     *
     * Throws [OverlayProvisionException.HandshakeFailed] if the frame does not
     * authenticate. **That failure is terminal, not retryable** — it means the peer does not
     * hold the shared secret, so the caller must tear the connection down rather than
     * answer.
     */
    fun open(sealed: ByteArray): ByteArray

    /** How many messages have been sealed. For tests and ordering assertions. */
    val sendCounter: Int

    /** How many messages have been opened. */
    val recvCounter: Int
}

/**
 * The real sealer: a handle to the Go session, with the keys living on the Go side.
 *
 * There is no `close()` because gomobile's generated proxy does not expose one — the refnum
 * is released when this object is collected, the same as [OverlayWgBinding.generateKeypair]
 *'s result. A session is created once per connection and lives exactly as long as it, so
 * there is nothing to pool and nothing to leak in practice.
 */
private class GomobileProvisionSealer(
    private val delegate: overlaywg.ProvisionSession,
) : OverlayProvisionSealer {

    override fun seal(plaintext: ByteArray): ByteArray = delegate.seal(plaintext)

    override fun open(sealed: ByteArray): ByteArray = try {
        delegate.open(sealed)
    } catch (e: Exception) {
        // Go reports a failed tag as an error, which gobind turns into an exception. It is
        // translated rather than propagated so callers never have to treat a JNI exception
        // as a normal control-flow signal — and so the message says what it means.
        throw OverlayProvisionException.HandshakeFailed(
            "the reply did not authenticate — this is not the server the advertised key belongs to",
            e,
        )
    }

    override val sendCounter: Int get() = delegate.sendCounter().toInt()

    override val recvCounter: Int get() = delegate.recvCounter().toInt()
}

internal object GomobileProvisionCrypto : OverlayProvisionCrypto {

    override fun session(
        devicePrivateKeyB64: String,
        serverPublicKeyB64: String,
        clientNonceB64: String,
        serverNonceB64: String,
    ): OverlayProvisionSealer = try {
        GomobileProvisionSealer(
            overlaywg.Overlaywg.newProvisionSession(
                devicePrivateKeyB64,
                serverPublicKeyB64,
                clientNonceB64,
                serverNonceB64,
            )
        )
    } catch (e: Exception) {
        throw OverlayProvisionException.HandshakeFailed(e.message ?: "key agreement failed", e)
    }
}
