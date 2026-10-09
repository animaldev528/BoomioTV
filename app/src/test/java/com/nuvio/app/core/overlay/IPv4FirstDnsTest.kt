package com.nuvio.app.core.overlay

import android.app.Application
import com.nuvio.tv.core.network.IPv4FirstDns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.Dns
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ⚠️ This test lives in the overlay package although the class it exercises is
// `com.nuvio.tv.core.network.IPv4FirstDns`, and the placement is deliberate rather than an
// oversight. Robolectric installs Conscrypt's Android provider for the whole module, and its
// `NativeCryptoJni` cannot `loadLibrary` on a JVM; the overlay suite is the one package with a
// `robolectric.properties` turning that off (`conscryptMode=OFF`, see `app/build.gradle.kts`).
// A copy of this file under `com.nuvio.tv.core.network` would need its own properties file and
// would run against a different harness than every other overlay test. The subject is the
// overlay's pin seam, so it belongs with the overlay's seam tests.
//
// ⚠️ Robolectric is required here, not stylistic. `OverlayPinRegistry.pin()` calls
// `android.util.Log`, which a plain JVM host test leaves **unmocked** — every test that
// pins dies with `RuntimeException: Method d in android.util.Log not mocked` before it
// reaches an assertion, so the pin behaviour would go untested while looking present.
// The SDK is pinned because the module compiles against SDK 37, which Robolectric does
// not map. Same treatment as `LocalServerHostsTest` in this package.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IPv4FirstDnsTest {

    private val publicHost = "bsc.tracemonkey.org"
    private val pinned = InetAddress.getByName("192.168.68.65")
    private val overlay = InetAddress.getByName("10.77.0.1")
    private val wan = InetAddress.getByName("209.107.100.169")
    private val publicV4 = InetAddress.getByName("203.0.113.10") as Inet4Address
    private val publicV6 = InetAddress.getByName("2001:db8::1") as Inet6Address

    /** Production's own value, restored after every test — the suppression tests below write it. */
    private val wasCarriesTraffic = OverlayPinRegistry.ownTunnelCarriesTraffic

    @AfterTest
    fun tearDown() {
        OverlayPinRegistry.clearAll()
        OverlayPinRegistry.ownTunnelCarriesTraffic = wasCarriesTraffic
        SecurityPolicyState.reset()
    }

    private fun dnsOf(vararg answers: InetAddress) = IPv4FirstDns(
        delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = answers.toList()
        },
    )

    private fun failingDns() = IPv4FirstDns(
        delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
        },
    )

    @Test
    fun `sorts ipv4 ahead of ipv6 when nothing is pinned`() {
        assertEquals(listOf(publicV4, publicV6), dnsOf(publicV6, publicV4).lookup(publicHost))
    }

    @Test
    fun `puts the pin first and keeps the delegate results behind it`() {
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)

        val result = dnsOf(publicV6, publicV4).lookup(publicHost)

        assertEquals(pinned, result.first())
        // The fallback is the anti-wedge property: a stale pin costs one failed
        // connect, it does not remove the ability to reach the public edge.
        assertEquals(listOf(pinned, publicV4, publicV6), result)
    }

    @Test
    fun `never re-sorts the pin behind an ipv6 from the delegate`() {
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)

        assertEquals(pinned, dnsOf(publicV6).lookup(publicHost).first())
    }

    @Test
    fun `drops a duplicate when the delegate already returns the pinned address`() {
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)

        assertEquals(listOf(pinned, publicV4), dnsOf(pinned, publicV4).lookup(publicHost))
    }

    @Test
    fun `falls back to the pin when system dns fails`() {
        // The case the feature exists for: a LAN where system DNS does not know the name.
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)

        assertEquals(listOf(pinned), failingDns().lookup(publicHost))
    }

    @Test
    fun `propagates system dns failure when there is no pin`() {
        assertFailsWith<UnknownHostException> { failingDns().lookup(publicHost) }
    }

    @Test
    fun `usePins false ignores the registry entirely`() {
        // ⚠️ The escape hatch for this fork's playback clients. Both of `PlayerPlaybackNetworking`'s
        // OkHttp clients end at trust-all TLS — one by construction, one through its SSLException
        // fallback — so TLS would not catch a hostile pin there and the registry must not be read.
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)
        val dns = IPv4FirstDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(publicV4)
            },
            usePins = false,
        )

        assertEquals(listOf(publicV4), dns.lookup(publicHost))
    }

    @Test
    fun `a pin covers every host on the server's domain, including ones learned at runtime`() {
        // The media plane. `bss-dav` and `bss-tor` arrive inside the stream URLs bsf
        // returns, so they are in no configuration and in no addon manifest — a pin set
        // built by enumerating hosts missed them, and the player sat on
        // `failed to connect to bss-dav.tracemonkey.org/153.68.210.49 after 15000ms`
        // while the catalogue worked. Matching the domain is what makes the pin complete.
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)
        val dns = dnsOf(publicV4)

        val stream = dns.lookup("bss-dav.tracemonkey.org")

        assertEquals(pinned, stream.first())
        assertTrue(stream.contains(publicV4))
    }

    @Test
    fun `a pin never covers a third-party host`() {
        // Posters are `image.tmdb.org`, two addons are third-party, and Supabase lives
        // in the cloud. Repointing any of them at a LAN address would simply be wrong.
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)
        val dns = dnsOf(publicV4)

        for (thirdParty in listOf(
            "image.tmdb.org",
            "catalog.nuvio.tv",
            "opensubtitles-v3.strem.io",
        )) {
            val result = dns.lookup(thirdParty)
            assertEquals(listOf(publicV4), result, "$thirdParty must keep resolving publicly")
            assertTrue(result.none { it == pinned }, "$thirdParty must not be pinned")
        }
    }

    @Test
    fun `a pin does not cover a domain that merely ends with the server's domain`() {
        // `eviltracemonkey.org` ends with the string `tracemonkey.org` but is a different
        // domain. The leading `.` in the suffix match is what keeps the two apart.
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)

        val result = dnsOf(publicV4).lookup("eviltracemonkey.org")

        assertEquals(listOf(publicV4), result)
        assertTrue(result.none { it == pinned })
    }

    @Test
    fun `the tunnel answers with the overlay address when both tiers are pinned`() {
        // The phase-4 path, end to end through the seam. On a foreign network the LAN
        // tier has nothing; the tunnel pin is what keeps the app off the public edge.
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf(publicHost), overlay)

        val result = dnsOf(publicV4).lookup(publicHost)

        assertEquals(overlay, result.first())
        // Pin-first, not pin-only: the public address is still there to fall back to, so
        // a dropped tunnel costs one failed connect rather than stranding the client.
        assertTrue(result.contains(publicV4))
    }

    @Test
    fun `the tunnel pin covers the media plane too`() {
        // The phase-5 property, and the reason the domain match is not a LAN-only
        // detail: a stream URL names `bss-dav`, which appears in no configuration, and
        // over the tunnel it would otherwise resolve to the public edge and leave it.
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf(publicHost), overlay)
        val dns = dnsOf(publicV4)

        assertEquals(overlay, dns.lookup("bss-dav.tracemonkey.org").first())
        assertEquals(overlay, dns.lookup("nzbdav.tracemonkey.org").first())
        // A third-party host is still never covered, on either tier.
        assertEquals(listOf(publicV4), dns.lookup("image.tmdb.org"))
    }

    @Test
    fun `the LAN pin outranks the tunnel pin when both are present`() {
        // ⚠️ Restored 2026-10-09 with the ranking itself. Between 2026-10-07 and then the tunnel
        // won this case — "one path everywhere" — which sent a device at home onto the tunnel
        // instead of a LAN link that was already there. The owner's ladder is LAN https first, so
        // rung 1 is the LAN. This test is written against a registry holding both, so it exercises
        // the *rank* alone: no Go tunnel is up in a host test, so nothing here is suppressed.
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf(publicHost), overlay)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)

        assertEquals(pinned, dnsOf(publicV4).lookup(publicHost).first())
    }

    @Test
    fun `clearing the tunnel leaves the LAN pin in place`() {
        // The two tiers are driven by independent triggers, so one reporting "nothing"
        // must not silently unpin the other. A single-slot registry did exactly that.
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf(publicHost), overlay)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(publicHost), pinned)

        OverlayPinRegistry.clear(LocalServerSource.TUNNEL)

        assertEquals(pinned, dnsOf(publicV4).lookup(publicHost).first())
    }

    // -----------------------------------------------------------------------------------------
    // The two direct planes — the v2 chain, at the seam that actually carries it
    // -----------------------------------------------------------------------------------------

    @Test
    fun `both direct pins come before the delegate, LAN then WAN`() {
        // ⚠️ **The owner's "tries the lan ip first, then the wan", end to end.** The service name's
        // public `A` record is the server's *private* address, so off-LAN the name resolves to
        // something undialable and the discovery record's `wan=` literal is the only way to the HTTP
        // plane. Handing the connect both addresses is what makes that survive: a private address on
        // a foreign network is refused immediately, so trying it first costs nothing.
        //
        // ⚠️ `directWanPlayback` is set explicitly, though it now defaults `true` (changed
        // 2026-10-09): this test pins the *chain order* rather than the default, so it says what it
        // needs rather than resting on it. See the toggle test below for the arm taken away.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        val serviceName = "boomio.duckdns.org"
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf(serviceName), wan)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(serviceName), pinned)

        assertEquals(listOf(pinned, wan, publicV4), dnsOf(publicV4).lookup(serviceName))
    }

    @Test
    fun `a forbidden WAN arm leaves the LAN pin answering alone`() {
        // The toggle at the seam, gone the other way from the test above: with the WAN arm suppressed
        // the chain is the LAN literal and then the delegate. ⚠️ `directWanPlayback` now defaults
        // `true` (changed 2026-10-09), so the *tightening* is what is applied here — the collapse
        // serves WAN callers, so the old "forbidden by default" premise was a claim about an edge
        // that does not exist. See `OverlayPinRegistry.isSuppressed`.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = false))
        val serviceName = "boomio.duckdns.org"
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf(serviceName), wan)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(serviceName), pinned)

        assertEquals(listOf(pinned, publicV4), dnsOf(publicV4).lookup(serviceName))
    }

    @Test
    fun `the chain is returned in full when system dns fails`() {
        // The no-LAN-DNS case the whole design exists for: a stranger's house has no resolver entry
        // for the service name, so the pin chain *is* the answer. Returning only the first would
        // strand an off-LAN device on an address it cannot reach.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        val serviceName = "boomio.duckdns.org"
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf(serviceName), wan)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(serviceName), pinned)

        assertEquals(listOf(pinned, wan), failingDns().lookup(serviceName))
    }

    @Test
    fun `while our own tunnel carries traffic only the WAN arm stands down`() {
        // ⚠️ The asymmetry *is* the ladder. The WAN pin is rung 3, behind the tunnel, so a WAN pin
        // left answering here would quietly put every name on the public edge while a healthy tunnel
        // carried nothing — and it would look like success because the edge answers too. The LAN pin
        // is rung 1 and keeps answering; suppressing it here was the 2026-10-07 position, retired.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        val serviceName = "boomio.duckdns.org"
        OverlayPinRegistry.pin(LocalServerSource.WAN, listOf(serviceName), wan)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf(serviceName), pinned)

        OverlayPinRegistry.ownTunnelCarriesTraffic = { true }

        // The LAN literal answers, the WAN one is gone, and the delegate stays behind both.
        assertEquals(listOf(pinned, publicV4), dnsOf(publicV4).lookup(serviceName))
    }
}
