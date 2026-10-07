package com.nuvio.app.core.network

import com.nuvio.tv.domain.model.ServerCapabilities
import com.nuvio.tv.domain.model.ServerConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TV compat layer — **not** part of the overlay port.
 *
 * The overlay derives the hosts it may repoint at a discovered LAN address from the configured
 * server, and it reads that from `ServerConfigurationRepository.active`. The TV has the same
 * *model* ([ServerConfiguration], already carrying `backendUrl` and `fallbackBackendUrl`) but no
 * repository by that name, so this file supplies the shape the overlay imports.
 *
 * ⚠️ **The TV's active configuration is a plain singleton, not a stream** — measured: it is
 * provided as `@Provides @Singleton fun provideActiveServerConfiguration(...):
 * ServerConfiguration = configurationStore.loadActive()` and consumed by direct injection, and
 * changing it is applied by an app restart (`AppRestarter`), not by an emission. So there is
 * nothing to delegate to; this holder is the seam, and [publish] is where the host's
 * configuration path will feed it.
 *
 * ⚠️ **Nothing calls [publish] yet, and that is deliberate** — an inert port adds no call sites.
 * Until it is called, [active] reports an empty configuration and nothing consumes it, because a
 * blank `BOOMIO_OVERLAY_ADDR` keeps the overlay's collectors from ever starting.
 *
 * ⚠️ **[active] is deliberately typed as the TV's own [ServerConfiguration], not a copy.** The
 * overlay only ever reads `backendUrl` and `fallbackBackendUrl` off it, so delegating keeps one
 * definition of what a server configuration is instead of forking a second.
 */
internal object ServerConfigurationRepository {

    /**
     * The inert default: blank URLs, no capabilities, not custom.
     *
     * Every field is blank rather than plausible on purpose. A default that named a real host
     * would become a pin target the moment the overlay started, so the empty value is the only
     * safe one — and it is what `localServerHostCandidates()` reads in a build that has not
     * published a configuration yet.
     */
    private val _active = MutableStateFlow(
        ServerConfiguration(
            backendUrl = "",
            publishableKey = "",
            capabilities = ServerCapabilities(emailPasswordAuth = false, tvLogin = false),
            isCustom = false,
        ),
    )

    /** The active server configuration. Emits the current value on subscribe. */
    val active: StateFlow<ServerConfiguration> = _active.asStateFlow()

    /** Records the active configuration. Called by the host's configuration path. */
    fun publish(configuration: ServerConfiguration) {
        _active.value = configuration
    }
}
