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
