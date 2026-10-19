package com.nuvio.tv.ui.screens.kids

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.KidsContentFilter
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How many times a wall's first page may retry before surfacing the unavailable state. */
private const val FIRST_PAGE_MAX_ATTEMPTS = 4

/**
 * Delay before each first-page retry. The shared kids board resolves its whole row on
 * the first cold request, which can outlast the edge timeout (a 504) before the result
 * lands in the addon's cache; retrying over ~50 s lets that resolve finish instead of
 * stranding the wall on "still updating".
 */
private val FIRST_PAGE_RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L)

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
    private val kidsContentFilter: KidsContentFilter,
    val posterOptions: PosterOptionsController
) : ViewModel() {

    private val _uiState = MutableStateFlow(KidWallUiState())
    val uiState: StateFlow<KidWallUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null
    private var retryJob: Job? = null
    /** True when this wall is paging a general catalog because no hub board exists. */
    private var usingFallbackCatalog = false
    private var loadedForCatalogId: String? = null

    init {
        posterOptions.bind(viewModelScope)
    }

    fun initialize(catalogId: String) {
        // Already showing this wall's pages (e.g. returning from Details keeps this
        // ViewModel alive and re-runs the screen's LaunchedEffect) — nothing to rediscover.
        if (loadedForCatalogId == catalogId && _uiState.value.row != null) return
        discoveryJob?.cancel()
        retryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            addonRepository.getInstalledAddons().collect { addons ->
                val enabled = addons.enabledAddons()
                // Empty still means the profile's addons aren't hydrated yet — keep waiting.
                if (enabled.isEmpty()) return@collect

                val addon = enabled.firstOrNull { a -> a.catalogs.any { it.id == catalogId } }
                if (addon == null) {
                    // No curated board for this profile yet (a freshly created kids profile,
                    // or its row addons haven't synced). Rather than showing "still updating"
                    // forever, page the first enabled catalog of the same kind and keep only
                    // titles with a known certificate at or below the kids ceiling.
                    if (loadedForCatalogId == catalogId && usingFallbackCatalog) return@collect
                    val apiType = if (catalogId.endsWith("movies")) "movie" else "series"
                    val fallback = enabled.firstNotNullOfOrNull { candidate ->
                        candidate.catalogs
                            .firstOrNull { it.apiType == apiType }
                            ?.let { catalog -> candidate to catalog }
                    }
                    if (fallback == null) {
                        _uiState.update {
                            it.copy(isInitialLoading = false, missingCatalog = true, loadError = null)
                        }
                        return@collect
                    }
                    loadedForCatalogId = catalogId
                    usingFallbackCatalog = true
                    loadFirstPage(fallback.first, fallback.second)
                    return@collect
                }
                // Pages already loading from the curated board. While showing the certified
                // fallback, take the board as soon as its addon shows up (addon sync can land
                // after this screen opened) instead of paging a general catalog all session.
                if (loadedForCatalogId == catalogId && !usingFallbackCatalog) return@collect

                val catalog = addon.catalogs.first { it.id == catalogId }
                usingFallbackCatalog = false
                loadedForCatalogId = catalogId
                loadFirstPage(addon, catalog)
            }
        }
    }

    private fun loadFirstPage(addon: Addon, catalog: CatalogDescriptor) {
        _uiState.update { it.copy(isInitialLoading = true, missingCatalog = false, loadError = null) }
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            var attempt = 0
            while (true) {
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
                    is NetworkResult.Success -> {
                        val page = if (usingFallbackCatalog) kidsFiltered(result.data) else result.data
                        _uiState.update { it.copy(row = page, isInitialLoading = false) }
                        return@launch
                    }
                    is NetworkResult.Error -> {
                        attempt += 1
                        if (attempt >= FIRST_PAGE_MAX_ATTEMPTS) {
                            _uiState.update { it.copy(isInitialLoading = false, loadError = result.message) }
                            return@launch
                        }
                        // A first-open wall request can out-last the request timeout when
                        // the row-addon server is cold; it keeps resolving in the background
                        // and writes a warm cache, so a short retry usually lands instantly.
                        // Only the first page retries — later pages never gate the wall.
                        delay(FIRST_PAGE_RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(FIRST_PAGE_RETRY_DELAYS_MS.lastIndex)])
                    }
                    NetworkResult.Loading -> Unit
                }
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
                is NetworkResult.Success -> {
                    val page = if (usingFallbackCatalog) kidsFiltered(result.data) else result.data
                    _uiState.update { s ->
                        val current = s.row ?: return@update s
                        s.copy(row = current.mergeCatalogPage(page))
                    }
                }
                is NetworkResult.Error ->
                    _uiState.update { s -> s.copy(row = s.row?.copy(isLoading = false)) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * Keep only titles TMDB certifies at or below the kids ceiling.
     * Unknown certificates are dropped: the fallback catalog is a general one, so
     * "we could not rate it" is not safe enough for a kids wall.
     */
    private suspend fun kidsFiltered(row: CatalogRow): CatalogRow =
        row.copy(items = kidsContentFilter.filter(row.items))
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
