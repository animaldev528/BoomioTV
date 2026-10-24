package com.nuvio.app.core.overlay

import android.app.Application
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.features.boomio.BOOMIO_SERVICE_HOST
import com.nuvio.tv.domain.model.ServerCapabilities
import com.nuvio.tv.domain.model.ServerConfiguration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule that keeps a hand-picked server from being dragged back to whichever box advertises
 * first. See `ConfiguredServerIdentity.kt` for why it exists.
 *
 * Robolectric for the same reason as [LocalServerHostsTest]: the repository it reads is backed by
 * platform storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfiguredServerIdentityTest {

    @AfterTest
    fun `reset the published configuration`() {
        ServerConfigurationRepository.publish(configuration(isCustom = false))
    }

    @Test
    fun `a build with no custom server accepts every advert`() {
        ServerConfigurationRepository.publish(configuration(isCustom = false))

        assertTrue(advertMatchesConfiguredServer("boomio.duckdns.org"))
        assertTrue(advertMatchesConfiguredServer("some-other-box.example"))
        // Even an advert that names no server at all: discovery is the only authority there is.
        assertTrue(advertMatchesConfiguredServer(null))
    }

    @Test
    fun `a custom server accepts only the advert that names the same host`() {
        ServerConfigurationRepository.publish(
            configuration(isCustom = true, backendUrl = "https://other.duckdns.org")
        )

        assertTrue(advertMatchesConfiguredServer("other.duckdns.org"))
        // The advert is a bare label, not necessarily a URL; both spellings are the same host.
        assertTrue(advertMatchesConfiguredServer("https://other.duckdns.org"))
        assertTrue(advertMatchesConfiguredServer("OTHER.duckdns.org"))
    }

    @Test
    fun `a custom server refuses the other deployment's advert`() {
        ServerConfigurationRepository.publish(
            configuration(isCustom = true, backendUrl = "https://other.duckdns.org")
        )

        assertFalse(advertMatchesConfiguredServer("boomio.duckdns.org"))
        // A private literal configured by hand cannot be attributed to any advert, including one
        // that happens to advertise a name — the pin it would place is the hijack.
        assertFalse(advertMatchesConfiguredServer("192.168.68.65"))
    }

    @Test
    fun `a custom server refuses an advert with no name`() {
        ServerConfigurationRepository.publish(
            configuration(isCustom = true, backendUrl = "https://other.duckdns.org")
        )

        // v1 adverts predate `svc=`; "unknown" must not read as "mine".
        assertFalse(advertMatchesConfiguredServer(null))
        assertFalse(advertMatchesConfiguredServer("  "))
    }

    // -----------------------------------------------------------------------------------------
    // The discovery record follows the configured server
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a stock build reads the record it was compiled for`() {
        ServerConfigurationRepository.publish(configuration(isCustom = false))

        assertEquals(BOOMIO_SERVICE_HOST, overlayDiscoveryRecordName())
    }

    @Test
    fun `a custom server's host is the record, never the build's`() {
        ServerConfigurationRepository.publish(
            configuration(isCustom = true, backendUrl = "https://boomiopi.duckdns.org")
        )

        assertEquals("boomiopi.duckdns.org", overlayDiscoveryRecordName())
    }

    @Test
    fun `a path on the configured URL never reaches the record`() {
        ServerConfigurationRepository.publish(
            configuration(isCustom = true, backendUrl = "https://boomiopi.duckdns.org/auth/v1")
        )

        assertEquals("boomiopi.duckdns.org", overlayDiscoveryRecordName())
    }

    @Test
    fun `a literal server is read as a literal, not swapped for the build's name`() {
        ServerConfigurationRepository.publish(
            configuration(isCustom = true, backendUrl = "https://192.0.2.10")
        )

        // The record lookup will find no TXT and the caller declines. Answering with the build's
        // deployment name here would be exactly the cross-deployment dial the rule prevents.
        assertEquals("192.0.2.10", overlayDiscoveryRecordName())
    }

    private fun configuration(
        isCustom: Boolean,
        backendUrl: String = "https://boomio.duckdns.org",
    ) = ServerConfiguration(
        backendUrl = backendUrl,
        publishableKey = "test-key",
        capabilities = ServerCapabilities(emailPasswordAuth = false, tvLogin = true),
        isCustom = isCustom,
    )
}
