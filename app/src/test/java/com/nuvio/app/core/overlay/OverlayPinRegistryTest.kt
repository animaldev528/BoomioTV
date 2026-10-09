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
    private val wanAddress = InetAddress.getByName("209.107.100.169")
    private val overlay = InetAddress.getByName("10.77.0.1")

    /**
     * Production's own value, captured before any test mutates it.
     *
     * The suppression tests below write this directly, and a leaked `{ true }` would silently redden
     * every test that expects a direct pin to answer — in this class and, since the registry is a
     * singleton over a shared JVM, in the classes beside it.
     */
    private val wasCarriesTraffic = OverlayPinRegistry.ownTunnelCarriesTraffic

    @AfterTest
    fun tearDown() {
        OverlayPinRegistry.clearAll()
        OverlayPinRegistry.ownTunnelCarriesTraffic = wasCarriesTraffic
        SecurityPolicyState.reset()
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
        // Both *direct* arms stand down in this state; the tunnel arm is never suppressed, because
        // it names the overlay address the kernel can reach rather than a direct route — it is
        // exactly the right answer here and must survive.
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

    // -----------------------------------------------------------------------------------------
    // The WAN arm — the v2 record's second address, and the chain it forms with the LAN one
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the LAN and WAN pins form one chain, LAN first`() {
        // ⚠️ **A chain, not a winner.** The two arms name the *same server* at two addresses, and
        // nothing here knows which network the device is standing on. `lookup` keeps its
        // single-address contract and answers with the first — the owner's "tries the lan ip first,
        // then the wan" — while `lookupChain` hands the dialler both, so a LAN connect refused on a
        // foreign network falls through to the WAN literal instead of failing outright.
        //
        // ⚠️ The WAN arm is only visible here because the policy permits it: `directWanPlayback`
        // defaults to `false`, which suppresses it. See the test below.
        OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        // Pinned WAN first, on purpose: the order must come from the enum's rank, not from the
        // order the two arms happened to land in.
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf("boomio.duckdns.org"), wanAddress)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("boomio.duckdns.org"), pinned)

        assertEquals(
            listOf(pinned, wanAddress),
            OverlayPinRegistry.lookupChain("boomio.duckdns.org"),
        )
        assertEquals(pinned, OverlayPinRegistry.lookup("boomio.duckdns.org"))
    }

    @Test
    fun `the WAN arm obeys directWanPlayback and the LAN arm does not`() {
        // ⚠️ **The default policy forbids the WAN arm, and that is the toggle working, not a bug.**
        // `directWanPlayback` defaults to `false` on the server ("a WAN caller gets 404 at today's
        // edge"), and a WAN pin *is* a direct WAN route — so it is suppressed by default while the
        // LAN pin, whose toggle defaults `true`, answers. The client may not quietly promote a
        // direct route the server's policy forbids.
        OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf("boomio.duckdns.org"), wanAddress)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("boomio.duckdns.org"), pinned)

        // Default policy: the LAN literal answers, the WAN one does not.
        assertEquals(listOf(pinned), OverlayPinRegistry.lookupChain("boomio.duckdns.org"))

        // Loosening it restores the fall-through with no re-discovery — the pin never left.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        assertEquals(
            listOf(pinned, wanAddress),
            OverlayPinRegistry.lookupChain("boomio.duckdns.org"),
        )

        // An operator tightening it again takes the arm away just as instantly.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = false))
        assertEquals(listOf(pinned), OverlayPinRegistry.lookupChain("boomio.duckdns.org"))
    }

    @Test
    fun `a permitted WAN pin alone covers the domain exactly as a LAN pin does`() {
        // The off-LAN case: the LAN arm never answered (mDNS found nothing), so the WAN literal the
        // TXT record published is the whole route — and every host on the service domain must
        // resolve through it, since the prefixes are told apart by path, never by host.
        OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf("boomio.duckdns.org"), wanAddress)

        assertEquals(wanAddress, OverlayPinRegistry.lookup("boomio.duckdns.org"))
        assertTrue(OverlayPinRegistry.isPinnedHost("https://bsf.boomio.duckdns.org/find/x"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://image.tmdb.org/x.jpg"))
    }

    @Test
    fun `both direct pins stand down while our own tunnel carries traffic`() {
        // ⚠️ **The symmetry is the point, and suppressing only the LAN arm is the bug it prevents.**
        // A WAN pin left answering would put every request that reached the registry on the public
        // edge while a healthy tunnel carried nothing — and it would look like success, because the
        // edge answers too. The policy is loosened first so that the *tunnel*, not the toggle, is
        // what the assertion is testing.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf("boomio.duckdns.org"), wanAddress)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("boomio.duckdns.org"), pinned)

        OverlayPinRegistry.ownTunnelCarriesTraffic = { true }
        assertNull(OverlayPinRegistry.lookup("boomio.duckdns.org"))
        assertTrue(OverlayPinRegistry.lookupChain("boomio.duckdns.org").isEmpty())
        assertFalse(OverlayPinRegistry.isPinnedHost("https://bsf.boomio.duckdns.org/x"))

        // Both arms are still held, so the transition back needs no re-discovery.
        OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
        assertEquals(pinned, OverlayPinRegistry.lookup("boomio.duckdns.org"))
    }
}
