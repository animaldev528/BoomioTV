package com.nuvio.app.core.overlay

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The egress check's pure half: what counts as an address, when two addresses may be compared, and
 * where a subnet ends.
 *
 * **What this covers, and what it deliberately does not.** Every case here is deterministic and
 * offline — nothing is fetched and no socket is opened. What is left out is the part that cannot be
 * tested this way: whether an echo service is reachable. The verdict that once consumed those
 * answers — an egress-address match deciding home-vs-away — was deleted with the v1→v2 record
 * change, so the helpers below are now a general capability with a tested pure half rather than a
 * live seam. See `OverlayEgress.localLinkAddresses`.
 *
 * ⚠️ **The subnet arithmetic is the reason this file exists.** The house is not a `/24` — the
 * deployment advertises `192.168.68.0/22` — so a string-prefix comparison that looks obviously
 * right passes every `/24` case and still puts a device on `192.168.69.x` off-LAN. The `/22` cases
 * below are the ones that fail a plausible implementation.
 *
 * No Robolectric: nothing here reaches an Android class. `InetAddress` is plain JVM.
 */
class OverlayEgressTest {

    // -----------------------------------------------------------------------------------------
    // parseEgressIp — the body sniff
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a bare address is read off the body`() {
        assertEquals("209.107.100.169", parseEgressIp("209.107.100.169"))
    }

    @Test
    fun `surrounding whitespace and a trailing newline are stripped`() {
        assertEquals("209.107.100.169", parseEgressIp("  209.107.100.169\n"))
    }

    @Test
    fun `an IPv6 address is accepted`() {
        // Echo services answer over IPv6 when the client has it, and refusing that would silently
        // reduce this test to "IPv4 only" on exactly the networks most likely to be misjudged.
        //
        // ⚠️ The expectation is the *expanded* form, because `InetAddress.hostAddress` normalises:
        // it returns `2001:db8:0:0:0:0:0:1`, not the compressed literal that went in. Writing the
        // compressed form here reads as obviously right and fails. Nothing downstream compares
        // these strings textually — `sameFamilyAddress` re-parses both sides — so normalising is
        // harmless and the test just has to expect it.
        assertEquals("2001:db8:0:0:0:0:0:1", parseEgressIp("2001:db8::1\n"))
    }

    @Test
    fun `a captive portal's HTML is not an answer`() {
        // ⚠️ This is the case that makes the body sniff load-bearing. A portal answers 200 OK with a
        // login page; the status line and the content-type both say "success".
        assertNull(parseEgressIp("<html><body>Sign in to the network</body></html>"))
    }

    @Test
    fun `a JSON body is not an answer`() {
        assertNull(parseEgressIp("{\"ip\":\"209.107.100.169\"}"))
    }

    @Test
    fun `a hostname is not an answer`() {
        // ⚠️ Not pedantry: `InetAddress.getByName` resolves hostnames, so accepting this would let a
        // third-party echo service aim this device's resolver at a name of its choosing.
        assertNull(parseEgressIp("ifconfig.me"))
    }

    @Test
    fun `an empty or absent body is not an answer`() {
        assertNull(parseEgressIp(""))
        assertNull(parseEgressIp("   "))
        assertNull(parseEgressIp(null))
    }

    @Test
    fun `an over-long body is not an answer`() {
        assertNull(parseEgressIp("1".repeat(46)))
    }

    // -----------------------------------------------------------------------------------------
    // literalOrNull — the boundary the above rests on
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a dotted quad is a literal and a hostname is not`() {
        assertNotNull(literalOrNull("192.168.68.65"))
        assertNull(literalOrNull("example.com"))
        assertNull(literalOrNull("boomio.duckdns.org"))
    }

    @Test
    fun `bare hex is not an IPv6 literal without a colon`() {
        assertNull(literalOrNull("abcdef"))
        assertNotNull(literalOrNull("2001:db8::1"))
    }

    // -----------------------------------------------------------------------------------------
    // sameFamilyAddress — the IPv6 trap
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the same address compares equal`() {
        assertEquals(true, sameFamilyAddress("209.107.100.169", "209.107.100.169"))
    }

    @Test
    fun `a different address compares unequal`() {
        // The hotspot case: the device exits through a carrier NAT, the server publishes the house.
        assertEquals(false, sameFamilyAddress("209.107.100.169", "100.64.12.7"))
    }

    @Test
    fun `a family mismatch has no verdict rather than a false one`() {
        // ⚠️ The single most consequential null in this file. An IPv4-only DuckDNS `A` record against
        // a device handed IPv6 can never be equal, and answering `false` would send a phone sitting
        // on the sofa to the WAN address — which does not hairpin (architecture §7) and would fail.
        assertNull(sameFamilyAddress("209.107.100.169", "2001:db8::1"))
        assertNull(sameFamilyAddress("2001:db8::1", "209.107.100.169"))
    }

    @Test
    fun `a blank or unparseable side has no verdict`() {
        assertNull(sameFamilyAddress(null, "209.107.100.169"))
        assertNull(sameFamilyAddress("209.107.100.169", null))
        assertNull(sameFamilyAddress("", "209.107.100.169"))
        assertNull(sameFamilyAddress("209.107.100.169", "not-an-address"))
    }

    // -----------------------------------------------------------------------------------------
    // isOnLink — the offline half
    // -----------------------------------------------------------------------------------------

    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun `an address inside the subnet is on-link`() {
        assertTrue(isOnLink(ip("192.168.68.65"), 24, ip("192.168.68.65")))
        assertTrue(isOnLink(ip("192.168.68.65"), 24, ip("192.168.68.200")))
    }

    @Test
    fun `an address outside the subnet is not on-link`() {
        assertFalse(isOnLink(ip("192.168.68.65"), 24, ip("192.168.69.5")))
        // The measured hotspot: a device on 10.151.x.x asking about the house LAN address.
        assertFalse(isOnLink(ip("10.151.14.131"), 24, ip("192.168.68.65")))
    }

    @Test
    fun `the house is a slash-22, so the neighbouring third octet is still on-link`() {
        // ⚠️ The case a string-prefix comparison gets wrong. `.68` and `.69` share a /22 and the
        // deployment really does advertise 192.168.68.0/22, so this is the normal case at home, not
        // an edge case.
        assertTrue(isOnLink(ip("192.168.68.65"), 22, ip("192.168.69.5")))
        assertFalse(isOnLink(ip("192.168.68.65"), 22, ip("192.168.72.1")))
    }

    @Test
    fun `a prefix that is not a whole number of octets masks correctly`() {
        // /26 splits the last octet at .63/.64, which is where an off-by-one in the mask would hide.
        assertTrue(isOnLink(ip("192.168.68.1"), 26, ip("192.168.68.33")))
        assertFalse(isOnLink(ip("192.168.68.1"), 26, ip("192.168.68.65")))
    }

    @Test
    fun `a zero-length prefix covers everything in its family`() {
        assertTrue(isOnLink(ip("192.168.68.65"), 0, ip("8.8.8.8")))
    }

    @Test
    fun `addresses of different families are never on-link`() {
        assertFalse(isOnLink(ip("192.168.68.65"), 24, ip("2001:db8::1")))
    }

    @Test
    fun `an IPv6 subnet masks by the same arithmetic`() {
        assertTrue(isOnLink(ip("2001:db8::1"), 64, ip("2001:db8::abcd")))
        assertFalse(isOnLink(ip("2001:db8::1"), 64, ip("2001:db9::1")))
    }
}
