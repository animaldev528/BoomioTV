package com.nuvio.app.core.overlay

import android.app.Application
import com.nuvio.app.core.network.ServerConfigurationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The repository this test reads is backed by platform storage, so Robolectric is
// required. The SDK is pinned because the module compiles against SDK 37, which
// Robolectric does not map — without it the runner fails to pick an SDK at all.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalServerHostsTest {

    @Test
    fun `keeps public https hosts`() {
        assertEquals(
            setOf("bsc.tracemonkey.org"),
            derivePinnableHosts(listOf("https://bsc.tracemonkey.org")),
        )
    }

    @Test
    fun `keeps a wss companion url, which is not an http scheme`() {
        assertEquals(
            setOf("bsc.tracemonkey.org"),
            derivePinnableHosts(listOf("wss://bsc.tracemonkey.org")),
        )
    }

    @Test
    fun `drops local and rfc1918 addresses`() {
        // isPublicServerHost already rejects these, so a discovered .local name can
        // never itself become a pin target.
        assertTrue(
            derivePinnableHosts(
                listOf(
                    "https://beamstream.local",
                    "https://192.168.68.65",
                    "https://10.77.0.1",
                    "https://172.16.0.1",
                    "https://127.0.0.1",
                    "https://localhost",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `keeps 172_32, which is public`() {
        // The RFC1918 block is 172.16/12; 172.32 is outside it and must survive.
        assertEquals(
            setOf("172.32.0.1"),
            derivePinnableHosts(listOf("https://172.32.0.1")),
        )
    }

    @Test
    fun `ignores blanks and unparseable values`() {
        assertTrue(derivePinnableHosts(listOf("", "   ", "not a url")).isEmpty())
    }

    @Test
    fun `strips the port and lowercases`() {
        assertEquals(
            setOf("bsc.tracemonkey.org"),
            derivePinnableHosts(listOf("https://BSC.Tracemonkey.org:8443/path")),
        )
    }

    @Test
    fun `dedupes the same host reached by two schemes`() {
        assertEquals(
            setOf("bsc.tracemonkey.org"),
            derivePinnableHosts(
                listOf("https://bsc.tracemonkey.org", "wss://bsc.tracemonkey.org"),
            ),
        )
    }

    @Test
    fun `hostOf is null for blank input`() {
        assertNull(hostOf(""))
        assertNull(hostOf("nonsense"))
    }

    @Test
    fun `never pins the configured fallback backend`() {
        // The fallback is the Supabase *cloud* host: it exists so the app has somewhere
        // to go when the primary is gone, so repointing it at a LAN address would
        // destroy the failover. This asserts it never enters the candidate list.
        val fallbackHost = hostOf(ServerConfigurationRepository.active.value.fallbackBackendUrl.orEmpty())
        if (fallbackHost == null) return

        assertFalse(
            localServerHostCandidates().any { hostOf(it) == fallbackHost },
            "the Supabase fallback host must not be pinnable",
        )
    }

    @Test
    fun `pins addon hosts on the server's own domain`() {
        // The shape the reference deployment actually has: the catalogue is 19 addons on
        // the server's domain and 2 that are somebody else's. Only the first group may be
        // repointed, and missing that group is what made the home screen empty on device.
        val serverHosts = setOf("nuvioserver.tracemonkey.org", "bsc.tracemonkey.org")
        val addonUrls = listOf(
            "https://tmdb.tracemonkey.org/74c28b54/qZaWf26/row/genremovie/manifest.json",
            "https://bsf.tracemonkey.org/manifest.json",
            "https://usn.tracemonkey.org/b3a0f61d/manifest.json",
            "https://opensubtitles-v3.strem.io/manifest.json",
            "https://catalog.nuvio.tv/manifest.json",
        )

        assertEquals(
            setOf("tmdb.tracemonkey.org", "bsf.tracemonkey.org", "usn.tracemonkey.org"),
            derivePinnableAddonHosts(serverHosts, addonUrls),
        )
    }

    @Test
    fun `a two-label server host still matches its own subdomains`() {
        // `example.com` has no label to spare, so the suffix is the host itself rather
        // than the empty string -- otherwise every addon would match everything.
        assertEquals(
            setOf("tmdb.example.com"),
            derivePinnableAddonHosts(
                setOf("example.com"),
                listOf("https://tmdb.example.com/manifest.json", "https://cdn.other.net/manifest.json"),
            ),
        )
    }

    @Test
    fun `a shared public suffix yields the host itself, not the shared parent`() {
        // The DuckDNS shape. The owner registered `boomio-tls`; nobody registered `duckdns.org`.
        // Taking the last two labels here would hand the pin a suffix that every free signup in
        // the world sits under, so the suffix is the host and the host is all it can cover.
        assertEquals(
            setOf("boomio-tls.duckdns.org"),
            serverDomainSuffixes(setOf("boomio-tls.duckdns.org")),
        )
    }

    @Test
    fun `an ordinary domain still yields its registrable suffix`() {
        // The regression guard for the fix, and the reason the shared list cannot simply be
        // "everything with two labels": `tracemonkey.org` *is* the owner's, and widening to it is
        // what covers `tmdb.`/`bsf.`/`usn.` and the media-plane hosts that appear in no
        // configuration. Narrowing this case would empty the home screen.
        assertEquals(
            setOf("tracemonkey.org"),
            serverDomainSuffixes(setOf("bsc.tracemonkey.org", "nuvioserver.tracemonkey.org")),
        )
    }

    @Test
    fun `never widens an addon host across a shared suffix`() {
        // ⚠️ The over-match this whole fix exists to prevent, end to end: a host under the same
        // *shared* suffix as the server is somebody else's box, and pinning it would resolve it to
        // the tunnel address instead of its real one. Note the device's own name is still kept —
        // the narrowing is to the exact host, not to nothing.
        assertEquals(
            setOf("boomio-tls.duckdns.org"),
            derivePinnableAddonHosts(
                setOf("boomio-tls.duckdns.org"),
                listOf(
                    "https://boomio-tls.duckdns.org/manifest.json",
                    "https://someone-elses-box.duckdns.org/manifest.json",
                ),
            ),
        )
    }

    @Test
    fun `the service origin pins that host, never the shared duckdns parent`() {
        // ⚠️ **The v2 upgrade makes this the load-bearing case.** `boomio.duckdns.org` is now the
        // origin every boomio URL is built from, so its suffix is what the pin matches every host
        // against. Widening to `duckdns.org` would repoint every free DuckDNS signup in the world
        // at this server's address — and it would look like it worked, because the pin matches.
        assertEquals(
            setOf("boomio.duckdns.org"),
            serverDomainSuffixes(setOf("boomio.duckdns.org")),
        )
    }

    @Test
    fun `a sibling duckdns name is not covered by the service origin`() {
        // The discovery name is a *different* record under the same shared suffix, and it is dialled
        // as a literal from the tuple rather than resolved through the pin — so it must not be
        // dragged in. Neither must anybody else's box.
        assertTrue(
            derivePinnableAddonHosts(
                setOf("boomio.duckdns.org"),
                listOf(
                    "https://boomio-prov.duckdns.org/manifest.json",
                    "https://someone-elses-box.duckdns.org/manifest.json",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `never pins an addon host without a server host to anchor it`() {
        // With nothing discovered there is no domain to be "the same as", so a
        // third-party addon must not become pinnable by accident.
        assertTrue(
            derivePinnableAddonHosts(emptySet(), listOf("https://tmdb.tracemonkey.org/manifest.json"))
                .isEmpty(),
        )
    }

    @Test
    fun `an addon host equal to a server host is kept`() {
        assertEquals(
            setOf("bsc.tracemonkey.org"),
            derivePinnableAddonHosts(
                setOf("bsc.tracemonkey.org"),
                listOf("https://bsc.tracemonkey.org/manifest.json"),
            ),
        )
    }

    @Test
    fun `addon hosts are still filtered by the public-host rule`() {
        // A LAN-local addon URL must not enter the set through the addon door.
        assertTrue(
            derivePinnableAddonHosts(
                setOf("nuvioserver.tracemonkey.org"),
                listOf("https://tmdb.local/manifest.json", "https://192.168.68.65/manifest.json"),
            ).isEmpty(),
        )
    }
}
