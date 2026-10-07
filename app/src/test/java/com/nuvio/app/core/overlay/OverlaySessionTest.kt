package com.nuvio.app.core.overlay

import android.app.Application
import android.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The policy that turns a discovered endpoint into a running tunnel.
 *
 * **What is actually at stake here.** [OverlaySessionDriver] is the only thing that decides
 * *when* the device is brought up, re-pointed or left alone, and every one of those decisions
 * is invisible when it is wrong: a tunnel that is reconfigured on every ladder emission still
 * works, just with a handshake gap every few minutes, and a tunnel that is torn down by a
 * transient discovery miss still works until the moment it doesn't. So the assertions below
 * are mostly about *how often* and *whether at all*, not about the resulting state.
 *
 * ⚠️ **A real 32-byte key is used, not a placeholder.** `OverlayWgTunnel.up` rejects anything
 * that does not decode to exactly 32 bytes — deliberately, because `android.util.Base64` skips
 * out-of-alphabet characters and would otherwise turn a paste error into a garbage peer key.
 * A fake like `"server-pub"` would fail that check and the tests would be exercising the
 * rejection path while looking like they exercised the happy one.
 *
 * Robolectric because `android.util.Base64` and `android.util.Log` both need it; no device,
 * because the fake binding *is* the tunnel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlaySessionTest {

    private val binding = SessionBinding()

    /**
     * ⚠️ **One device, handed to the driver as a supplier that returns the same instance.**
     *
     * This is not tidiness — a `tunnel()` that built a fresh [OverlayWgTunnel] per call makes
     * the *moved endpoint* case pass while testing nothing: the driver would ask for a device,
     * get a brand-new one sitting in [TunnelState.Down], and skip the teardown that the old
     * device needed. That is the exact shape of bug this file exists to catch, so the fake has
     * to match production, where the supplier is `{ OverlayWgTunnelController.instance }` and
     * a device lives for the process.
     */
    private val device: OverlayWgTunnel by lazy {
        OverlayWgTunnel(binding, loadKeypair = { STORED_KEYPAIR }, saveKeypair = { error("must not save") })
    }

    private fun driverOn(tunnelProvider: () -> OverlayWgTunnel? = { device }) =
        OverlaySessionDriver(tunnel = tunnelProvider, localCidr = { "10.77.0.2/32" })

    private fun foundEndpoint(
        host: String = "192.168.68.65",
        port: Int = 51820,
        key: String? = SERVER_KEY_B64,
    ) = OverlayEndpointStatus.Found(
        OverlayEndpoint(host = host, port = port, serverPublicKeyBase64 = key, source = OverlayEndpointSource.MDNS),
    )

    @Test
    fun `a found endpoint brings the device up against that authority`() {
        val driver = driverOn()

        assertTrue(driver.apply(foundEndpoint()))

        assertEquals("192.168.68.65:51820", binding.lastUp?.endpoint)
        assertEquals(1, binding.upCalls)
        assertTrue(driver.isUp)
    }

    @Test
    fun `the device's own address is passed through, not assumed`() {
        // ⚠️ The one field whose default is right for the first client and wrong for the
        // second. If this were hard-coded the second client's tunnel would come up, complete
        // a handshake, and silently drop every return packet — the server has no route to an
        // address it never enrolled.
        val driver = OverlaySessionDriver(tunnel = { device }, localCidr = { "10.77.0.7/32" })

        driver.apply(foundEndpoint())

        assertEquals("10.77.0.7/32", binding.lastUp?.localCidr)
    }

    @Test
    fun `the server's key is converted to the hex IpcSet parses`() {
        driverOn().apply(foundEndpoint())

        // base64 in, hex out — the two publication channels and wireguard-go disagree, and
        // the conversion happens inside `up` rather than at either end.
        assertEquals(hexOfTestKey(), binding.lastUp?.peerPublicKeyHex)
    }

    @Test
    fun `re-applying the same endpoint is a no-op`() {
        // The ladder re-emits `Found` on a foreground cadence with the same value. Taking the
        // device down and up each time would be a handshake gap every five minutes for no
        // gain, and `distinctUntilChanged` upstream does not cover it — the *endpoint* is
        // equal but it arrives as a fresh `Found` object each walk.
        val driver = driverOn()

        driver.apply(foundEndpoint())
        val changed = driver.apply(foundEndpoint())

        assertFalse(changed)
        assertEquals(1, binding.upCalls)
        assertEquals(0, binding.downCalls)
    }

    @Test
    fun `a moved endpoint takes the old device down before bringing the new one up`() {
        val driver = driverOn()

        driver.apply(foundEndpoint(host = "192.168.68.65"))
        val changed = driver.apply(foundEndpoint(host = "153.68.210.49"))

        assertTrue(changed)
        assertEquals(
            listOf("up", "down", "up"),
            binding.calls,
            "the old device is pointed at the wrong server, and `up` on a live device is not " +
                "documented to reconfigure in place — so the teardown has to come first",
        )
        assertEquals("153.68.210.49:51820", binding.lastUp?.endpoint)
    }

    @Test
    fun `a status that is not Found does not tear a running tunnel down`() {
        // ⚠️ **The invariant this class exists to protect.** The obvious shape — `Found`
        // means up, anything else means down — kills a tunnel that is up and carrying traffic
        // the first time a timeboxed DNS miss or an empty mDNS browse comes back. The ladder
        // was restructured to avoid exactly that; this is the second half of it.
        val driver = driverOn()

        driver.apply(foundEndpoint())
        val changed = driver.apply(null)

        assertFalse(changed)
        assertEquals(0, binding.downCalls)
        assertTrue(driver.isUp)
    }

    @Test
    fun `a status that is not Found is inert on a cold start too`() {
        val driver = driverOn()

        assertFalse(driver.apply(null))

        assertEquals(0, binding.upCalls)
        assertFalse(driver.isUp)
    }

    @Test
    fun `a refused bring-up is not remembered, so the ladder's next walk retries`() {
        // ⚠️ If a failure left `configuredFor` set, the next identical `Found` would be
        // suppressed as "no change" — and on a cold start that is the *only* value the
        // ladder ever produces, so the tunnel would never be retried for the process's life.
        binding.failUpWith = IllegalStateException("the config was rejected")
        val driver = driverOn()

        assertTrue(driver.apply(foundEndpoint()), "a failure is still a state change worth acting on")
        assertFalse(driver.isUp)

        binding.failUpWith = null
        assertTrue(driver.apply(foundEndpoint()), "the same endpoint must be retried, not suppressed")
        assertEquals(2, binding.upCalls)
        assertTrue(driver.isUp)
    }

    @Test
    fun `an endpoint with no server key never reaches the binding`() {
        // Rung 3 can represent "the user typed an address and did not know the key". That is
        // a candidate to be completed, not an answer — and a device brought up without a peer
        // key would come up, never handshake, and report nothing useful about why.
        val driver = driverOn()

        assertFalse(driver.apply(foundEndpoint(key = null)))

        assertEquals(0, binding.upCalls)
        assertFalse(driver.isUp)
    }

    @Test
    fun `a missing tunnel object is a no-op rather than a crash`() {
        // `OverlayWgTunnelController.initialize` has not run yet on some paths — and a driver
        // that threw here would take down whichever collector called it.
        val driver = driverOn(tunnelProvider = { null })

        assertFalse(driver.apply(foundEndpoint()))

        assertFalse(driver.isUp)
    }

    @Test
    fun `a refused config lands in Failed rather than a half-up state`() {
        binding.failUpWith = IllegalStateException("TUN could not be built")

        val state = device.up(
            endpoint = "192.168.68.65:51820",
            serverPublicKeyBase64 = SERVER_KEY_B64,
            localCidr = "10.77.0.2/32",
        )

        assertIs<TunnelState.Failed>(state)
    }
}

/** One lifecycle call's arguments, recorded so the assertions can be about what crossed. */
private data class SessionUpArgs(
    val endpoint: String,
    val peerPublicKeyHex: String,
    val localCidr: String,
)

/**
 * A binding that serves the lifecycle only.
 *
 * The data path is deliberately `error(...)`: these tests are about *when* the device is
 * brought up, and a fake that quietly accepted a `dial` would let a policy bug look like a
 * passing test.
 */
private class SessionBinding : OverlayWgBinding {

    var upCalls = 0
    var downCalls = 0
    var lastUp: SessionUpArgs? = null
    var failUpWith: Throwable? = null

    /**
     * The lifecycle calls in order.
     *
     * `upCalls`/`downCalls` can only ever say *how many*; a re-point that brought the new
     * device up before taking the old one down would have the same counts and two live
     * devices. The order is the assertion, so the fake records it.
     */
    val calls = mutableListOf<String>()

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
        calls += "up"
        lastUp = SessionUpArgs(endpoint, peerPublicKeyHex, localCidr)
        failUpWith?.let { throw it }
    }

    override fun down() {
        downCalls++
        calls += "down"
    }

    override fun status(): String = "handshake=1.2s rx=0 tx=0"

    override fun generateKeypair(): OverlayWgKeypair = error("a stored pair is supplied by the test")

    override fun dial(host: String, port: Int): Long = error("not used by the lifecycle tests")

    override fun read(id: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        error("not used by the lifecycle tests")

    override fun write(id: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        error("not used by the lifecycle tests")

    override fun close(id: Long) = error("not used by the lifecycle tests")

    override fun lastError(): String = ""
}

private val STORED_KEYPAIR =
    OverlayWgKeypair("stored-priv-b64", "stored-pub-b64", "stored-priv-hex", "stored-pub-hex")

/** 32 bytes, so it survives `up`'s length check — see this file's header. */
private val TEST_KEY_BYTES = ByteArray(32) { it.toByte() }

private val SERVER_KEY_B64: String = Base64.encodeToString(TEST_KEY_BYTES, Base64.NO_WRAP)

private fun hexOfTestKey(): String =
    TEST_KEY_BYTES.joinToString("") { "%02x".format(it) }
