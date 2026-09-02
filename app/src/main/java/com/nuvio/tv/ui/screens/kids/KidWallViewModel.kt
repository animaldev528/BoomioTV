package com.nuvio.tv.ui.screens.kids

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Full approved-content poster wall for the kids presentation (Leo's profile).
 *
 * Each KidsMovies / KidsTv route owns one instance. It watches the active
 * profile's installed addons (a flow, so the profile's row/hub addon arriving
 * after this screen opens still resolves) until it finds the addon that serves
 * the requested catalog id (hub-leomovies / hub-leoshows), then lazily pages the
 * whole catalog: page 0 on discovery, later pages on scroll via [loadMore],
 * merging each result through [CatalogRow.mergeCatalogPage] (which dedupes and
 * self-terminates when the server returns no more items). Long-press poster
 * options (Add to library) ride on the injected [PosterOptionsController], the
 * same self-contained path LibraryScreen / FolderDetail use — not Home-coupled.
 */
@HiltViewModel
class KidWallViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    val posterOptions: PosterOptionsController
) : ViewModel() {

    private val _uiState = MutableStateFlow(KidWallUiState())
    val uiState: StateFlow<KidWallUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null
    private var loadedForCatalogId: String? = null

    init {
        posterOptions.bind(viewModelScope)
    }

    fun initialize(catalogId: String) {
        // Already showing this wall's pages (e.g. returning from Details keeps this
        // ViewModel alive and re-runs the screen's LaunchedEffect) — nothing to rediscover.
        if (loadedForCatalogId == catalogId && _uiState.value.row != null) return
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            addonRepository.getInstalledAddons().collect { addons ->
                val enabled = addons.enabledAddons()
                // Empty still means the profile's addons aren't hydrated yet — keep waiting.
                if (enabled.isEmpty()) return@collect

                val addon = enabled.firstOrNull { a -> a.catalogs.any { it.id == catalogId } }
                if (addon == null) {
                    // Enabled set is loaded but none serves this wall catalog yet (e.g. the
                    // profile's row addons haven't synced since they were published). Surface a
                    // soft error and re-evaluate on the next emission.
                    _uiState.update {
                        it.copy(isInitialLoading = false, missingCatalog = true, loadError = null)
                    }
                    return@collect
                }
                if (loadedForCatalogId == catalogId) return@collect // pages already loading

                val catalog = addon.catalogs.first { it.id == catalogId }
                loadedForCatalogId = catalogId
                loadFirstPage(addon, catalog)
            }
        }
    }

    private fun loadFirstPage(addon: Addon, catalog: CatalogDescriptor) {
        _uiState.update { it.copy(isInitialLoading = true, missingCatalog = false, loadError = null) }
        viewModelScope.launch {
            val result = catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = catalog.skipStep(),
                extraArgs = emptyMap(),
                supportsSkip = catalog.supportsExtra("skip")
            ).first { it !is NetworkResult.Loading }
            when (result) {
                is NetworkResult.Success ->
                    _uiState.update { it.copy(row = result.data, isInitialLoading = false) }
                is NetworkResult.Error ->
                    _uiState.update { it.copy(isInitialLoading = false, loadError = result.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** Load the next page. Guarded by hasMore / in-flight so scroll callbacks are cheap. */
    fun loadMore() {
        val state = _uiState.value
        val row = state.row ?: return
        if (state.isInitialLoading || !row.hasMore || row.isLoading) return

        _uiState.update { it.copy(row = it.row?.copy(isLoading = true)) }
        viewModelScope.launch {
            val result = catalogRepository.getCatalog(
                addonBaseUrl = row.addonBaseUrl,
                addonId = row.addonId,
                addonName = row.addonName,
                catalogId = row.catalogId,
                catalogName = row.catalogName,
                type = row.apiType,
                skip = row.nextCatalogSkip(),
                skipStep = row.skipStep,
                extraArgs = emptyMap(),
                supportsSkip = row.supportsSkip
            ).first { it !is NetworkResult.Loading }
            when (result) {
                is NetworkResult.Success ->
                    _uiState.update { s ->
                        val current = s.row ?: return@update s
                        s.copy(row = current.mergeCatalogPage(result.data))
                    }
                is NetworkResult.Error ->
                    _uiState.update { s -> s.copy(row = s.row?.copy(isLoading = false)) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

data class KidWallUiState(
    val row: CatalogRow? = null,
    val isInitialLoading: Boolean = true,
    val missingCatalog: Boolean = false,
    val loadError: String? = null
) {
    val items: List<MetaPreview> get() = row?.items.orEmpty()
    val addonBaseUrl: String? get() = row?.addonBaseUrl
    val hasMore: Boolean get() = row?.hasMore == true
    val isLoadingMore: Boolean get() = row?.isLoading == true
}
