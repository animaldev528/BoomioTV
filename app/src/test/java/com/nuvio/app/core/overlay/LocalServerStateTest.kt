package com.nuvio.app.core.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ranking rule, tested on its own.
 *
 * This is a pure function precisely so it can be: the flow that calls it is deliberately
 * asynchronous (it is derived, not written), and a test of *what the user is told* should
 * not have to wait on a dispatcher to find out.
 */
class LocalServerStateTest {

    private val foundLan = LocalServerStatus.Found("192.168.68.65", LocalServerSource.LAN)
    private val foundTunnel = LocalServerStatus.Found("10.77.0.1", LocalServerSource.TUNNEL)
    private val foundWan = LocalServerStatus.Found("203.0.113.7", LocalServerSource.WAN)

    private fun statuses(vararg pairs: Pair<LocalServerSource, LocalServerStatus>) =
        mapOf(*pairs).effectiveStatus()

    @Test
    fun `nothing reported reads as idle`() {
        assertEquals(LocalServerStatus.Idle, emptyMap<LocalServerSource, LocalServerStatus>().effectiveStatus())
    }

    @Test
    fun `a single source is reported as-is`() {
        assertEquals(foundLan, statuses(LocalServerSource.LAN to foundLan))
        assertEquals(foundTunnel, statuses(LocalServerSource.TUNNEL to foundTunnel))
    }

    @Test
    fun `the LAN source wins when both found something`() {
        // Both tiers work here, so the ranking is what decides. ⚠️ This is the *restoration*
        // of 2026-10-09: between 2026-10-07 and then the tunnel won this case, on "the app
        // brings its own tunnel up at home too, so preferring the LAN pin leaves the shipped
        // transport unexercised exactly where it is cheapest to exercise". Read as a claim
        // about which path to prefer, that argument holds — but the owner's ladder is LAN
        // https, then LAN WireGuard, then WAN https, then WAN WireGuard, so a tunnel on the
        // home network is a detour around a link that is already there. Rung 1 belongs to the
        // LAN. The display has to agree with the registry, or the row would name an address
        // the app is not using.
        assertEquals(
            foundLan,
            statuses(LocalServerSource.LAN to foundLan, LocalServerSource.TUNNEL to foundTunnel),
        )
    }

    @Test
    fun `both direct planes lose to the tunnel when they are only idle`() {
        // The WAN arm is rung 3 and the LAN arm rung 1, but *idle* is idle: a source that has
        // reported nothing must never mask a working one, whatever its rank.
        assertEquals(
            foundTunnel,
            statuses(
                LocalServerSource.LAN to LocalServerStatus.Idle,
                LocalServerSource.TUNNEL to foundTunnel,
                LocalServerSource.WAN to LocalServerStatus.Idle,
            ),
        )
    }

    @Test
    fun `the WAN source loses to both the LAN and the tunnel`() {
        // Rung 3 is last: it is the same server as `LAN` seen from off the property, so it is
        // only ever the answer when nothing nearer reported anything.
        assertEquals(
            foundLan,
            statuses(LocalServerSource.LAN to foundLan, LocalServerSource.WAN to foundWan),
        )
        assertEquals(
            foundTunnel,
            statuses(LocalServerSource.TUNNEL to foundTunnel, LocalServerSource.WAN to foundWan),
        )
    }

    @Test
    fun `a tunnel find outranks a LAN failure`() {
        // ⚠️ The case the ranking exists for. Off the home network the browse reports
        // "not on Wi-Fi or Ethernet" — true, and useless — while the tunnel is carrying
        // every request. Showing the failure would tell the user the app is stuck on the
        // public edge in the exact moment it is not.
        assertEquals(
            foundTunnel,
            statuses(
                LocalServerSource.LAN to LocalServerStatus.Unavailable("Not on Wi-Fi or Ethernet"),
                LocalServerSource.TUNNEL to foundTunnel,
            ),
        )
    }

    @Test
    fun `a tunnel find outranks a LAN search still in flight`() {
        assertEquals(
            foundTunnel,
            statuses(
                LocalServerSource.LAN to LocalServerStatus.Searching,
                LocalServerSource.TUNNEL to foundTunnel,
            ),
        )
    }

    @Test
    fun `the LAN find outranks a tunnel that is merely idle`() {
        // The tunnel is inert on most devices — no `BOOMIO_OVERLAY_ADDR`, or no VPN at
        // all — and an idle source must never mask a working one.
        assertEquals(
            foundLan,
            statuses(LocalServerSource.LAN to foundLan, LocalServerSource.TUNNEL to LocalServerStatus.Idle),
        )
    }

    @Test
    fun `a failure is shown when nothing was found`() {
        // Failing that, a real diagnostic still surfaces rather than sitting behind an
        // `Idle`: "not on Wi-Fi" is what tells a user why discovery did nothing.
        assertEquals(
            LocalServerStatus.Unavailable("Not on Wi-Fi or Ethernet"),
            statuses(
                LocalServerSource.LAN to LocalServerStatus.Unavailable("Not on Wi-Fi or Ethernet"),
                LocalServerSource.TUNNEL to LocalServerStatus.Idle,
            ),
        )
    }

    @Test
    fun `all idle reads as idle, not as the last source checked`() {
        assertEquals(
            LocalServerStatus.Idle,
            statuses(
                LocalServerSource.LAN to LocalServerStatus.Idle,
                LocalServerSource.TUNNEL to LocalServerStatus.Idle,
            ),
        )
    }
}
