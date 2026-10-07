package com.nuvio.app.core.overlay

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two pure decisions the tunnel tier makes, tested without a device.
 *
 * Everything else in `OverlayTunnel` is Android plumbing — a `NetworkCallback`, a
 * `ConnectivityManager` query, a socket — and the value of testing it here would be
 * near zero. These two, though, are where a mistake is *silent*: a bad address parse
 * disables the tier, and a bad subnet check either disables it or, worse, has it act on
 * a VPN that is not ours.
 */
class OverlayTunnelTest {

    private val server = InetAddress.getByName("10.77.0.1")

    @Test
    fun `a dotted quad is parsed without touching the resolver`() {
        val parsed = parseOverlayAddress("10.77.0.1")

        assertEquals("10.77.0.1", parsed?.hostAddress)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        // The value comes from `local.properties`, where a trailing space is invisible
        // and would otherwise disable the whole tier.
        assertEquals("10.77.0.1", parseOverlayAddress("  10.77.0.1  ")?.hostAddress)
    }

    @Test
    fun `blank is not configured, not an error`() {
        assertNull(parseOverlayAddress(""))
        assertNull(parseOverlayAddress("   "))
    }

    @Test
    fun `a hostname is refused rather than resolved`() {
        // ⚠️ The load-bearing one. `InetAddress.getByName` would resolve this through the
        // system resolver — a blocking DNS call on the probe path, and a resolution that
        // hands back exactly the control this seam exists to take away. `BoomioConfig`
        // documents the field as an address; this is what enforces it.
        assertNull(parseOverlayAddress("overlay.example.com"))
        assertNull(parseOverlayAddress("localhost"))
    }

    @Test
    fun `a url is refused`() {
        // A pasted `https://10.77.0.1` is the most likely way a user gets this wrong,
        // because every *other* field in `BoomioConfig` is a URL.
        assertNull(parseOverlayAddress("https://10.77.0.1"))
        assertNull(parseOverlayAddress("10.77.0.1:51820"))
    }

    @Test
    fun `malformed octets are refused`() {
        assertNull(parseOverlayAddress("10.77.0"))
        assertNull(parseOverlayAddress("10.77.0.1.2"))
        assertNull(parseOverlayAddress("10.77.0.256"))
        assertNull(parseOverlayAddress("10.77.0.-1"))
        assertNull(parseOverlayAddress("10.77.0.x"))
    }

    @Test
    fun `a leading zero is refused, not read as decimal`() {
        // `010` parses as ten, so accepting it would silently pin an address the operator
        // never wrote — and one that is not even the same subnet as what they meant.
        assertNull(parseOverlayAddress("10.77.0.010"))
        // A bare `0` is a legitimate octet and must still be accepted.
        assertEquals("10.0.0.0", parseOverlayAddress("10.0.0.0")?.hostAddress)
    }

    @Test
    fun `the client's own overlay address is on the server's subnet`() {
        // The discriminator. WireGuard puts the phone at `10.77.0.2`; that link address
        // is what tells our tunnel apart from any other VPN the user may be running.
        assertTrue(listOf(InetAddress.getByName("10.77.0.2")).sharesOverlaySubnetWith(server))
    }

    @Test
    fun `another vpn's address is not on the server's subnet`() {
        // NordVPN's interface, and an ordinary Wi-Fi address. Neither may be mistaken
        // for the overlay: "a VPN is up" is not the question, "our VPN is up" is.
        assertFalse(listOf(InetAddress.getByName("10.5.0.2")).sharesOverlaySubnetWith(server))
        assertFalse(listOf(InetAddress.getByName("192.168.68.65")).sharesOverlaySubnetWith(server))
        assertFalse(listOf(InetAddress.getByName("172.19.0.1")).sharesOverlaySubnetWith(server))
    }

    @Test
    fun `a neighbouring subnet that shares a prefix is not the overlay`() {
        // `10.77.1.x` shares two octets with `10.77.0.1` and nothing else. A check that
        // stopped at `/16` would call it ours.
        assertFalse(listOf(InetAddress.getByName("10.77.1.2")).sharesOverlaySubnetWith(server))
        assertFalse(listOf(InetAddress.getByName("10.78.0.2")).sharesOverlaySubnetWith(server))
    }

    @Test
    fun `an ipv6 link address is never the overlay`() {
        // A VPN that also carries an IPv6 address must not match on a coincidental byte
        // comparison, and an IPv6-only link must not throw.
        assertFalse(
            listOf(InetAddress.getByName("2001:db8::1"), InetAddress.getByName("fe80::1"))
                .sharesOverlaySubnetWith(server),
        )
    }

    @Test
    fun `an empty link set does not match`() {
        assertFalse(emptyList<InetAddress>().sharesOverlaySubnetWith(server))
    }
}
