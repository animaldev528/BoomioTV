package com.nuvio.app.features.addons

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TV compat layer — **not** part of the overlay port.
 *
 * ⚠️ **This is the seam that decides whether the home screen works.** Most of the app's server
 * hosts are not in the server configuration at all — they are addon manifest hosts on the
 * server's own domain (17 row addons on `tmdb.`, plus `bsf.` and `usn.` on the reference
 * deployment). The overlay finds them by reading every installed addon's manifest URL, and it
 * reads them from `AddonRepository.uiState.value.addons.map { it.manifestUrl }`. Miss the addon
 * plane and the pin covers auth and the companion plane while the entire catalogue keeps
 * resolving to the public edge — which on the LAN is unreachable, because hairpin NAT is off.
 *
 * ⚠️ **The TV's own addon repository is a different shape**, so this cannot delegate its *type*:
 * measured, `com.nuvio.tv.data.repository.AddonRepositoryImpl` exposes
 * `getInstalledAddons(): Flow<List<Addon>>`, and its model `com.nuvio.tv.domain.model.Addon`
 * carries the manifest base as **`baseUrl`**, not `manifestUrl` (the wire URL is built as
 * `"$baseUrl/manifest.json"` at the call site). The overlay needs `uiState` and `manifestUrl`,
 * and those names are part of the byte-identical transplant, so this file supplies them and the
 * host maps `baseUrl → manifestUrl` when it publishes.
 *
 * ⚠️ **Nothing calls [AddonRepository.publish] yet, and that is deliberate** — an inert port adds
 * no call sites. Until it is called, [AddonUiState.addons] is empty and no addon host is
 * pinnable, which is inert and correct: the overlay's collectors never start while
 * `BOOMIO_OVERLAY_ADDR` is blank. Note also that the real catalogue arrives *asynchronously*,
 * seconds after launch, so the publisher must keep publishing rather than seed once.
 */

/**
 * The one field the overlay reads off an addon: the URL its manifest is served from.
 *
 * ⚠️ A base URL is acceptable here and is what the TV has. The overlay only ever takes the
 * *host* of this string, and `<base>` and `<base>/manifest.json` share a host — so the mapping
 * cannot change which hosts become pinnable.
 */
internal data class AddonRef(val manifestUrl: String)

/** The installed addons for the active profile. */
internal data class AddonUiState(val addons: List<AddonRef> = emptyList())

internal object AddonRepository {

    /** Empty until [publish] is called — see the file comment on why that is the inert reading. */
    private val _uiState = MutableStateFlow(AddonUiState())

    val uiState: StateFlow<AddonUiState> = _uiState.asStateFlow()

    /**
     * Records the installed addons.
     *
     * Call on **every** change, not once at start-up: the overlay binds its pin to this flow
     * precisely because the addon set grows after the first browse.
     */
    fun publish(addons: List<AddonRef>) {
        _uiState.value = AddonUiState(addons)
    }
}
