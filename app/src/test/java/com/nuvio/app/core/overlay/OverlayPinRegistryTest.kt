package com.nuvio.app.core.overlay

import android.app.Application
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The registry's contract is that **a pin covers the server's domain**, not a list of
 * hosts. That is what [isPinnedHost] decides, and it is the gate that selects the
 * validating playback client — so getting it wrong is what made every bsf link buffer.
 *
 * ⚠️ Robolectric is required: `pin()` calls `android.util.Log`, which is unmocked in a
 * plain JVM host test. The SDK is pinned because the module compiles against SDK 37,
 * which Robolectric does not map. Same treatment as `LocalServerHostsTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayPinRegistryTest {

    private val pinned = InetAddress.getByName("192.168.68.65")
    private val overlay = InetAddress.getByName("10.77.0.1")

    @AfterTest
    fun tearDown() {
        OverlayPinRegistry.clearAll()
    }

    @Test
    fun `nothing is pinned before discovery runs`() {
        assertNull(OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://bsc.tracemonkey.org"))
    }

    @Test
    fun `a stream url on the server's domain selects the pinned client`() {
        // `bss-dav` and `bss-tor` are in no configuration and in no addon manifest --
        // they arrive inside bsf's stream URLs. When this gate said false for them, the
        // player used the trust-all client, resolved to the WAN address and sat on
        // `failed to connect to bss-dav.tracemonkey.org/153.68.210.49 after 15000ms`.
        OverlayPinRegistry.pin(
            LocalServerSource.LAN,
            listOf("bsc.tracemonkey.org", "bsf.tracemonkey.org"),
            pinned,
        )

        assertTrue(
            OverlayPinRegistry.isPinnedHost(
                "https://bss-dav.tracemonkey.org/library/movie/tt0137523/stream.mkv",
            ),
        )
        assertTrue(OverlayPinRegistry.isPinnedHost("https://bss-tor.tracemonkey.org/resolve/x"))
        assertTrue(OverlayPinRegistry.isPinnedHost("https://nzbdav.tracemonkey.org/content/x.mkv"))
    }

    @Test
    fun `a third-party url never selects the pinned client`() {
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        assertFalse(OverlayPinRegistry.isPinnedHost("https://image.tmdb.org/t/p/w500/a.jpg"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://catalog.nuvio.tv/manifest.json"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://opensubtitles-v3.strem.io/m.json"))
        // Ends with the string `tracemonkey.org` but is a different domain.
        assertFalse(OverlayPinRegistry.isPinnedHost("https://eviltracemonkey.org/x"))
    }

    @Test
    fun `a blank or unparseable url is not pinned`() {
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        assertFalse(OverlayPinRegistry.isPinnedHost(""))
        assertFalse(OverlayPinRegistry.isPinnedHost("nonsense"))
        assertNull(OverlayPinRegistry.lookup(null))
    }

    @Test
    fun `clear removes the domain match as well as the exact host`() {
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)
        OverlayPinRegistry.clear(LocalServerSource.LAN)

        assertNull(OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        assertNull(OverlayPinRegistry.lookup("bss-dav.tracemonkey.org"))
    }

    @Test
    fun `an empty host set never clears a good pin`() {
        // `OverlayLocalDiscovery.refresh` falls back to the browsed set when the live
        // derivation comes back empty; a pin must not evaporate just because a caller
        // had nothing to say.
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)
        OverlayPinRegistry.pin(LocalServerSource.LAN, emptyList(), pinned)

        assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
    }

    @Test
    fun `the two sources hold their own pins and the tunnel is ranked first`() {
        // The tiers are driven by independent triggers that know nothing about each
        // other. With one slot, a tunnel probe landing a second after a browse would
        // replace a working LAN pin with the overlay address — silently, and for the
        // rest of the session.
        //
        // ⚠️ The tunnel wins the rank. It used to lose it, on "the most local path
        // available"; the owner reversed that on 2026-10-07 so the shipped transport is
        // the one used everywhere, home included.
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        assertEquals(overlay, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
    }

    @Test
    fun `the LAN pin is kept but not followed while our own tunnel carries traffic`() {
        // ⚠️ The regression this guards is silent and looks like success: path B pins
        // nothing, so without this the LAN pin is the only route in the registry, every
        // client goes direct to 192.168.68.65, and a healthy tunnel carries nothing at all.
        val was = OverlayPinRegistry.ownTunnelCarriesTraffic
        try {
            OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

            // Tunnel down: the LAN pin is the route, as it always was.
            OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
            assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))

            // Tunnel up: the pin is still there, and deliberately not followed — the relay
            // resolves the name inside the tunnel instead.
            OverlayPinRegistry.ownTunnelCarriesTraffic = { true }
            assertNull(OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
            assertNull(OverlayPinRegistry.lookup("bss-dav.tracemonkey.org"))
            assertFalse(OverlayPinRegistry.isPinnedHost("https://bss-dav.tracemonkey.org/x.mkv"))

            // And the transition back is instant, with no re-browse: the pin never left.
            OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
            assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        } finally {
            OverlayPinRegistry.ownTunnelCarriesTraffic = was
        }
    }

    @Test
    fun `a tunnel pin still answers while our own tunnel carries traffic`() {
        // Only the LAN arm is suppressed. A platform-VPN pin names the overlay address the
        // kernel can reach, so it is exactly the right answer in this state and must survive.
        val was = OverlayPinRegistry.ownTunnelCarriesTraffic
        try {
            OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)
            OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)
            OverlayPinRegistry.ownTunnelCarriesTraffic = { true }

            assertEquals(overlay, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        } finally {
            OverlayPinRegistry.ownTunnelCarriesTraffic = was
        }
    }

    @Test
    fun `clearing one source does not disturb the other`() {
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        OverlayPinRegistry.clear(LocalServerSource.LAN)

        assertEquals(overlay, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        assertEquals(overlay, OverlayPinRegistry.lookup("bss-dav.tracemonkey.org"))
    }

    @Test
    fun `a tunnel pin alone covers the domain exactly as a LAN pin does`() {
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)

        assertTrue(OverlayPinRegistry.isPinnedHost("https://bsf.tracemonkey.org/find/x"))
        assertTrue(OverlayPinRegistry.isPinnedHost("https://bss-tor.tracemonkey.org/x"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://image.tmdb.org/x.jpg"))
    }
}
