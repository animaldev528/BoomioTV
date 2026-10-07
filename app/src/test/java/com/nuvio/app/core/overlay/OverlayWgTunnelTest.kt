package com.nuvio.app.core.overlay

import android.app.Application
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tunnel lifecycle, against a fake binding.
 *
 * **What this test is for.** U2 shipped the transport with no driver, so nothing had ever
 * asked the binding to generate a keypair, bring a device up, or report its state. This
 * covers the logic that now does: that a keypair is generated exactly once and then reused,
 * that the persisted one wins over generating a fresh one, that the server's base64 key is
 * converted to the hex `IpcSet` parses, and that every failure path lands in
 * [TunnelState.Failed] rather than leaving the state ambiguous.
 *
 * ⚠️ **The conversion is the assertion worth reading.** `wg set` and both publication
 * channels speak base64; wireguard-go's `IpcSet` speaks hex. That mismatch is silent when it
 * is wrong — a tunnel that never handshakes and logs nothing about why — so the test pins it
 * against a real published key rather than round-tripping a value the test made up itself.
 *
 * Robolectric because `android.util.Base64` and `android.util.Log` both need it. Nothing here
 * needs a device: the fake *is* the tunnel, and the real one is a JNI library that cannot
 * load on the host at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayWgTunnelTest {

    private val binding = RecordingBinding()

    @Test
    fun `generates a keypair once and reuses it`() {
        val saved = mutableListOf<OverlayWgKeypair>()
        val tunnel = OverlayWgTunnel(binding, loadKeypair = { null }, saveKeypair = { saved += it })

        val first = tunnel.keypair()
        val second = tunnel.keypair()

        assertEquals(1, binding.generateCalls, "a second call must not mint a new key")
        assertEquals(1, saved.size, "the generated pair must be persisted exactly once")
        assertEquals(first, second)
    }

    @Test
    fun `a persisted keypair is adopted rather than regenerated`() {
        val stored = OverlayWgKeypair("stored-priv-b64", "stored-pub-b64", "stored-priv-hex", "stored-pub-hex")
        val tunnel = OverlayWgTunnel(binding, loadKeypair = { stored }, saveKeypair = { error("must not save") })

        assertEquals("stored-pub-b64", tunnel.publicKeyBase64())
        assertEquals(0, binding.generateCalls, "a stored identity must survive a fresh process")
    }

    @Test
    fun `up converts the server base64 key to the hex IpcSet takes`() {
        val tunnel = tunnel()

        val state = tunnel.up(
            endpoint = "192.168.68.65:51820",
            serverPublicKeyBase64 = PUBLISHED_SERVER_KEY_B64,
            localCidr = "10.77.0.9/32",
        )

        assertIs<TunnelState.Up>(state)
        assertEquals(PUBLISHED_SERVER_KEY_HEX, binding.lastUp?.peerPublicKeyHex)
        assertEquals("client-priv-hex", binding.lastUp?.privateKeyHex)
        assertEquals("192.168.68.65:51820", binding.lastUp?.endpoint)
        assertEquals("10.77.0.9/32", binding.lastUp?.localCidr)
    }

    @Test
    fun `up applies the transport defaults the overlay actually needs`() {
        tunnel().up(
            endpoint = "192.168.68.65:51820",
            serverPublicKeyBase64 = PUBLISHED_SERVER_KEY_B64,
            localCidr = "10.77.0.9/32",
        )

        val args = requireNotNull(binding.lastUp)
        assertEquals("10.77.0.0/24", args.allowedIps)
        // Not optional: `dial` takes the hostname from the CONNECT line, so names must
        // resolve inside the tunnel or the relay can only reach literal addresses.
        assertEquals("10.77.0.1", args.dns)
        assertEquals(1420, args.mtu)
        assertEquals(25, args.keepalive)
    }

    @Test
    fun `a blank endpoint fails without touching the device`() {
        val state = tunnel().up(
            endpoint = "   ",
            serverPublicKeyBase64 = PUBLISHED_SERVER_KEY_B64,
            localCidr = "10.77.0.9/32",
        )

        assertIs<TunnelState.Failed>(state)
        assertEquals(0, binding.upCalls, "a config that cannot work must not be handed to Go")
    }

    @Test
    fun `an unparseable server key fails rather than reaching the device`() {
        val state = tunnel().up(
            endpoint = "192.168.68.65:51820",
            // `!` is not in the base64 alphabet. This is the shape of a paste that picked up
            // a stray character, and it must not become a tunnel that silently never dials.
            serverPublicKeyBase64 = "not!valid!base64!",
            localCidr = "10.77.0.9/32",
        )

        assertIs<TunnelState.Failed>(state)
        assertEquals(0, binding.upCalls)
    }

    /**
     * ⚠️ **The test that caught the real bug.** `android.util.Base64` does not throw on
     * characters outside its alphabet — it *skips* them — so `"not!valid!base64!"` decodes to
     * a short byte array rather than raising, and the first version of this code happily
     * handed it to Go as the peer key. The only symptom would have been a tunnel that never
     * handshook and logged nothing about why.
     *
     * Valid base64, right alphabet, **wrong length** — so this fails on the length check
     * rather than on the decode, which is the path the lenient decoder actually takes.
     */
    @Test
    fun `a valid base64 key that is not 32 bytes fails rather than becoming a peer`() {
        val state = tunnel().up(
            endpoint = "192.168.68.65:51820",
            // Sixteen bytes of perfectly valid base64. A truncated paste looks exactly like
            // this, and it is indistinguishable from a real key without the length check.
            serverPublicKeyBase64 = "AAAAAAAAAAAAAAAAAAAAAA==",
            localCidr = "10.77.0.9/32",
        )

        val failed = assertIs<TunnelState.Failed>(state)
        assertEquals(0, binding.upCalls, "a key that cannot be one must not reach Go")
        assertContains(failed.reason, "32-byte")
    }

    @Test
    fun `a binding that rejects the config lands in Failed, not a half-up state`() {
        binding.failUpWith = IllegalStateException("IpcSet rejected the peer")
        val tunnel = tunnel()

        val state = tunnel.up(
            endpoint = "192.168.68.65:51820",
            serverPublicKeyBase64 = PUBLISHED_SERVER_KEY_B64,
            localCidr = "10.77.0.9/32",
        )

        assertIs<TunnelState.Failed>(state)
        assertContains(state.reason, "IpcSet rejected the peer")
        assertIs<TunnelState.Failed>(tunnel.state.value)
    }

    /**
     * ⚠️ **The test the first on-device run made necessary.** `IpcGet` prints the private key in
     * full hex, and `status()` is the string the diagnostics row renders — the probe wrote one
     * straight into a report file and the log, which is how this was noticed.
     *
     * The fake returns a realistic `IpcGet` dump, keys and all, and the assertion is that the
     * counters come through while the key material does not. Testing only that a key is absent
     * would pass on a status that had been emptied out entirely, so both halves are pinned.
     */
    @Test
    fun `status strips key material but keeps the counters worth reading`() {
        binding.statusReply = """
            private_key=1111111111111111111111111111111111111111111111111111111111111111
            listen_port=38978
            public_key=2222222222222222222222222222222222222222222222222222222222222222
            preshared_key=0000000000000000000000000000000000000000000000000000000000000000
            endpoint=192.168.68.65:51820
            last_handshake_time_sec=1770000000
            tx_bytes=444
            rx_bytes=0
        """.trimIndent()

        val status = tunnel().status()

        assertFalse(status.contains("1111111111111111111111111111111111111111111111111111111111111111"), "the private key must never leave `status()`")
        assertFalse(status.contains("0000000000000000000000000000000000000000000000000000000000000000"), "the preshared key line must go too")

        // The parts that are actually worth reading have to survive, or the redaction has
        // simply broken the diagnostic it exists to serve.
        assertContains(status, "tx_bytes=444")
        assertContains(status, "rx_bytes=0")
        assertContains(status, "endpoint=192.168.68.65:51820")
        assertContains(status, "listen_port=38978")
    }

    /** The same string reaches callers through `up()`, which captures it for the state. */
    @Test
    fun `the status captured by up is redacted too`() {
        binding.statusReply = """
            private_key=3333333333333333333333333333333333333333333333333333333333333333
            tx_bytes=148
        """.trimIndent()

        val state = tunnel().up(
            endpoint = "192.168.68.65:51820",
            serverPublicKeyBase64 = PUBLISHED_SERVER_KEY_B64,
            localCidr = "10.77.0.9/32",
        )

        val up = assertIs<TunnelState.Up>(state)
        assertFalse(up.status.contains("3333333333333333333333333333333333333333333333333333333333333333"))
        assertContains(up.status, "tx_bytes=148")
    }

    @Test
    fun `status reports the failure instead of throwing`() {
        binding.failStatusWith = IllegalStateException("device is gone")

        val status = tunnel().status()

        assertContains(status, "unavailable")
        assertContains(status, "device is gone")
    }

    @Test
    fun `down returns to Down even when teardown throws`() {
        val tunnel = tunnel()
        tunnel.up(
            endpoint = "192.168.68.65:51820",
            serverPublicKeyBase64 = PUBLISHED_SERVER_KEY_B64,
            localCidr = "10.77.0.9/32",
        )
        assertIs<TunnelState.Up>(tunnel.state.value)

        binding.failDownWith = IllegalStateException("already gone")
        tunnel.down()

        // The caller asked for a state, and "down" is that state. A failure here means it is
        // down for a different reason, which the next `up()` is what surfaces.
        assertIs<TunnelState.Down>(tunnel.state.value)
    }

    @Test
    fun `a fresh tunnel starts Down and has asked the binding for nothing`() {
        val tunnel = tunnel()

        assertIs<TunnelState.Down>(tunnel.state.value)
        assertNull(binding.lastUp)
        assertEquals(0, binding.downCalls)
    }

    private fun tunnel() = OverlayWgTunnel(
        binding = binding,
        loadKeypair = { null },
        saveKeypair = { },
    )

    private companion object {
        /**
         * The live server's public key as both publication channels carry it. A real value
         * rather than a fixture, so the expected hex below is a fact about the actual
         * encoding and not a restatement of the conversion being tested.
         */
        const val PUBLISHED_SERVER_KEY_B64 = "kqZZdZcb8cGF9AhUTJw7RWuM/98WZs9NJOGlBKWtiU0="

        /** `base64 -d … | xxd -p` of the same key. */
        const val PUBLISHED_SERVER_KEY_HEX =
            "92a65975971bf1c185f408544c9c3b456b8cffdf1666cf4d24e1a504a5ad894d"
    }
}

/** What [OverlayWgBinding.up] was asked for, kept so a test can name it. */
private data class UpArgs(
    val privateKeyHex: String,
    val peerPublicKeyHex: String,
    val endpoint: String,
    val localCidr: String,
    val allowedIps: String,
    val dns: String,
    val mtu: Int,
    val keepalive: Int,
)

/**
 * A binding that records instead of tunnelling.
 *
 * Separate from the dialler test's fake because it records a different thing: that one cares
 * about buffer identity and short writes on the copy path, this one about the arguments of a
 * lifecycle call that happens once.
 */
private class RecordingBinding : OverlayWgBinding {

    var generateCalls = 0
    var upCalls = 0
    var downCalls = 0
    var lastUp: UpArgs? = null

    var failUpWith: Throwable? = null
    var failDownWith: Throwable? = null
    var failStatusWith: Throwable? = null

    /** What the binding reports; a realistic `IpcGet` dump where a test needs one. */
    var statusReply: String = "handshake=1.2s rx=0 tx=0"

    override fun up(
        privateKeyHex: String,
        peerPublicKeyHex: String,
        endpoint: String,
        localCidr: String,
        allowedIps: String,
        dns: String,
        mtu: Int,
        keepalive: Int,
    ) {
        upCalls++
        lastUp = UpArgs(privateKeyHex, peerPublicKeyHex, endpoint, localCidr, allowedIps, dns, mtu, keepalive)
        failUpWith?.let { throw it }
    }

    override fun down() {
        downCalls++
        failDownWith?.let { throw it }
    }

    override fun status(): String {
        failStatusWith?.let { throw it }
        return statusReply
    }

    override fun generateKeypair(): OverlayWgKeypair {
        generateCalls++
        return OverlayWgKeypair(
            privateKeyBase64 = "client-priv-b64",
            publicKeyBase64 = "client-pub-b64",
            privateKeyHex = "client-priv-hex",
            publicKeyHex = "client-pub-hex",
        )
    }

    override fun dial(host: String, port: Int): Long = error("not used by the lifecycle tests")

    override fun read(id: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        error("not used by the lifecycle tests")

    override fun write(id: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        error("not used by the lifecycle tests")

    override fun close(id: Long) = error("not used by the lifecycle tests")

    override fun lastError(): String = ""
}
