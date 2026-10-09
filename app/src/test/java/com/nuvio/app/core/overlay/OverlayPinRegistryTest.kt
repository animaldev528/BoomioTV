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
    fun `the two sources hold their own pins and the LAN is ranked first`() {
        // The tiers are driven by independent triggers that know nothing about each
        // other. With one slot, a tunnel probe landing a second after a browse would
        // replace a working LAN pin with the overlay address — silently, and for the
        // rest of the session.
        //
        // ⚠️ The LAN wins the rank. It lost it between 2026-10-07 and 2026-10-09, on "the
        // most local path available" — but the owner's ladder is LAN https, then LAN
        // WireGuard, then WAN https, then WAN WireGuard, so rung 1 is the LAN, and the
        // 2026-10-07 reversal is retired.
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
    }

    @Test
    fun `the LAN pin keeps answering while our own tunnel carries traffic`() {
        // ⚠️ **This is the rung-1 rule, and 2026-10-07 had it backwards.** While our own
        // tunnel carries, the LAN arm is *not* suppressed: the ladder is LAN https, then LAN
        // WireGuard, so a tunnel on the home network is a detour around a link that is already
        // there, and skipping the LAN pin would take the shipped transport off the direct link
        // on the one network where that link is cheapest. What stands down in this state is the
        // *WAN* arm — rung 3, behind the tunnel — not the LAN one.
        val was = OverlayPinRegistry.ownTunnelCarriesTraffic
        try {
            OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

            // Tunnel down: the LAN pin is the route, as it always was.
            OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
            assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))

            // Tunnel up: the LAN pin still answers. Nothing about a carrying tunnel suppresses
            // rung 1, so the transition changes nothing here.
            OverlayPinRegistry.ownTunnelCarriesTraffic = { true }
            assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
            assertEquals(pinned, OverlayPinRegistry.lookup("bss-dav.tracemonkey.org"))
            assertTrue(OverlayPinRegistry.isPinnedHost("https://bss-dav.tracemonkey.org/x.mkv"))

            // And the transition back is instant, with no re-browse: the pin never left.
            OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
            assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        } finally {
            OverlayPinRegistry.ownTunnelCarriesTraffic = was
        }
    }

    @Test
    fun `a tunnel pin still answers while our own tunnel carries traffic`() {
        // The tunnel arm is never suppressed: it names the overlay address the kernel can reach
        // rather than a direct route, so it is exactly the right answer here and must survive.
        // With no LAN pin present it is the whole answer — which is the off-LAN case, and the
        // reason this arm must not be caught by the WAN arm's rule.
        val was = OverlayPinRegistry.ownTunnelCarriesTraffic
        try {
            OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)
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
        // ⚠️ The WAN arm is visible here because the policy permits it — `directWanPlayback`
        // defaults to `true` (changed 2026-10-09), and this test sets it explicitly so the
        // assertion does not rest on that default. See the toggle test below.
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
        // ⚠️ **A WAN pin is a *direct WAN route*, and that is exactly what the toggle names.**
        // `directWanPlayback` defaults to `true` (changed 2026-10-09), so by default the WAN
        // literal answers alongside the LAN one: the collapse *serves* WAN callers rather than
        // 404ing them, so refusing the arm by default was a claim nothing implemented. An operator
        // tightening it to `false` takes the arm away, and the client obeys — it may not quietly
        // promote a direct route the server's policy forbids. The LAN arm answers to its own
        // toggle (`directLanPlayback`, default `true`) and never to this one.
        OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf("boomio.duckdns.org"), wanAddress)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("boomio.duckdns.org"), pinned)

        // Default policy: both literals answer, LAN first.
        assertEquals(
            listOf(pinned, wanAddress),
            OverlayPinRegistry.lookupChain("boomio.duckdns.org"),
        )

        // An operator tightening it removes the arm with no re-discovery — the pin never left.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = false))
        assertEquals(listOf(pinned), OverlayPinRegistry.lookupChain("boomio.duckdns.org"))

        // And loosening it restores the fall-through just as instantly.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        assertEquals(
            listOf(pinned, wanAddress),
            OverlayPinRegistry.lookupChain("boomio.duckdns.org"),
        )
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
    fun `the WAN pin stands down while our own tunnel carries traffic`() {
        // ⚠️ **Only the WAN arm stands down in this state, and that asymmetry *is* the ladder.**
        // The WAN pin is rung 3, behind the tunnel, so a WAN pin left answering would put requests
        // on the public edge while a healthy tunnel carried nothing — and it would look like
        // success, because the edge answers too. The LAN arm is rung 1 and keeps answering
        // (see the LAN test above); suppressing it here was the 2026-10-07 position, retired.
        // The policy is left permissive so that the *tunnel*, not the toggle, is what the
        // assertion is testing.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf("boomio.duckdns.org"), wanAddress)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("boomio.duckdns.org"), pinned)

        OverlayPinRegistry.ownTunnelCarriesTraffic = { true }
        // The WAN arm is gone; the LAN arm is all that is left, and it is the right answer.
        assertEquals(pinned, OverlayPinRegistry.lookup("boomio.duckdns.org"))
        assertEquals(listOf(pinned), OverlayPinRegistry.lookupChain("boomio.duckdns.org"))
        assertTrue(OverlayPinRegistry.isPinnedHost("https://bsf.boomio.duckdns.org/x"))

        // The WAN arm is still held, so dropping the tunnel restores the chain with no re-discovery.
        OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
        assertEquals(
            listOf(pinned, wanAddress),
            OverlayPinRegistry.lookupChain("boomio.duckdns.org"),
        )
    }
}
