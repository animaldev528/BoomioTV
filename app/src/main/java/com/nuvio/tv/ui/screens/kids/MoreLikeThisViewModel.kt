package com.nuvio.tv.ui.screens.kids

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MoreLikeThisList
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MoreLikeThisRepository
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
 * Result wall behind Leo's long-press "More like this". One instance per
 * [com.nuvio.tv.ui.navigation.Screen.MoreLikeThis] route entry.
 *
 * Given a pressed approved title's type + tt id, it watches the active profile's
 * installed addons (same discovery as [KidWallViewModel]) until it finds the row
 * addon serving the matching hub catalog (hub-leomovies for movies, hub-leoshows
 * for series — re-resolved by catalog id, NOT the pressed tile's own addon, so a
 * Saved tile added from any addon still queries the approved leokid universe).
 * It then lazily pages the endpoint's approved co-members: page 0 on discovery,
 * later pages on scroll via [loadMore] (server offsets by skip = items seen so
 * far). The facet [lists] returned on page 0 feed the screen's caption chips.
 */
@HiltViewModel
class MoreLikeThisViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val moreLikeThisRepository: MoreLikeThisRepository,
    val posterOptions: PosterOptionsController
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoreLikeThisUiState())
    val uiState: StateFlow<MoreLikeThisUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null
    private var loadedForKey: String? = null

    init {
        posterOptions.bind(viewModelScope)
    }

    fun initialize(itemType: String, metaId: String, exclude: List<String> = emptyList()) {
        // The exclusion set is part of the identity: the same seed title pushed from a
        // different parent wall is a distinct drill (fresh results) and must re-page.
        val key = "$itemType:$metaId:${exclude.joinToString(",")}"
        if (loadedForKey == key) return // same title already paging — nothing to rediscover

        discoveryJob?.cancel()
        _uiState.update { MoreLikeThisUiState(isInitialLoading = true) }

        discoveryJob = viewModelScope.launch {
            addonRepository.getInstalledAddons().collect { addons ->
                val enabled = addons.enabledAddons()
                // Empty still means the profile's addons aren't hydrated yet — keep waiting.
                if (enabled.isEmpty()) return@collect

                val catalogId = hubCatalogIdFor(itemType)
                val addon = enabled.firstOrNull { a -> a.catalogs.any { it.id == catalogId } }
                if (addon == null) {
                    // Enabled set is loaded but none serves this hub yet (row addons not synced
                    // since publish). Soft error; re-evaluate on the next emission.
                    _uiState.update {
                        it.copy(isInitialLoading = false, missingCatalog = true, loadError = null)
                    }
                    return@collect
                }
                if (loadedForKey == key) return@collect // pages already loading

                val catalog = addon.catalogs.first { it.id == catalogId }
                loadedForKey = key
                loadFirstPage(addon, catalog, metaId, exclude)
            }
        }
    }

    private fun loadFirstPage(
        addon: Addon,
        catalog: CatalogDescriptor,
        metaId: String,
        exclude: List<String>
    ) {
        _uiState.update { it.copy(isInitialLoading = true, missingCatalog = false, loadError = null) }
        viewModelScope.launch {
            val result = moreLikeThisRepository.getMoreLikeThis(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                type = catalog.apiType,
                metaId = metaId,
                skip = 0,
                exclude = exclude
            ).first { it !is NetworkResult.Loading }
            when (result) {
                is NetworkResult.Success -> {
                    val page = result.data
                    _uiState.update {
                        it.copy(
                            metaId = metaId,
                            addonId = addon.id,
                            addonName = addon.displayName,
                            lists = page.lists,
                            items = page.items,
                            addonBaseUrl = page.addonBaseUrl,
                            itemType = page.itemType,
                            exclude = exclude,
                            isInitialLoading = false,
                            hasMore = page.hasMore,
                            isLoadingMore = false,
                            loadError = null
                        )
                    }
                }
                is NetworkResult.Error ->
                    _uiState.update { it.copy(isInitialLoading = false, loadError = result.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** Load the next page. Guarded by hasMore / in-flight so scroll callbacks are cheap. */
    fun loadMore() {
        val state = _uiState.value
        val baseUrl = state.addonBaseUrl ?: return
        if (state.isInitialLoading || state.isLoadingMore || !state.hasMore) return
        if (state.items.isEmpty() || state.itemType.isEmpty()) return

        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val result = moreLikeThisRepository.getMoreLikeThis(
                addonBaseUrl = baseUrl,
                addonId = state.addonId,
                addonName = state.addonName,
                type = state.itemType,
                metaId = state.metaId,
                skip = state.items.size,
                exclude = state.exclude
            ).first { it !is NetworkResult.Loading }
            when (result) {
                is NetworkResult.Success -> {
                    val page = result.data
                    _uiState.update { s ->
                        val existing = s.items.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toHashSet()
                        val fresh = page.items.filter { "${it.apiType}:${it.id}" !in existing }
                        s.copy(
                            items = s.items + fresh,
                            hasMore = page.hasMore,
                            isLoadingMore = false,
                            loadError = null
                        )
                    }
                }
                is NetworkResult.Error ->
                    _uiState.update { s -> s.copy(isLoadingMore = false) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

data class MoreLikeThisUiState(
    val itemType: String = "",
    val metaId: String = "",
    val addonId: String = "",
    val addonName: String = "",
    val addonBaseUrl: String? = null,
    val lists: List<MoreLikeThisList> = emptyList(),
    val items: List<MetaPreview> = emptyList(),
    val exclude: List<String> = emptyList(),
    val isInitialLoading: Boolean = true,
    val missingCatalog: Boolean = false,
    val loadError: String? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
) {
    /**
     * Caption chips: the tightest curated lists the pressed title sits on. Distinct
     * by name — a title that is both "Movie" and "Series" universes or sits on two
     * same-named facet lists must not show "Disney Junior · Disney Junior".
     */
    val caption: String
        get() = lists.distinctBy { it.name }.take(3).joinToString(" · ") { it.name }
}

/** The full approved-content hub catalog that serves MLT for a given type. */
fun hubCatalogIdFor(itemType: String): String =
    if (itemType.equals("movie", ignoreCase = true)) {
        KidWallKind.MOVIES.catalogId
    } else {
        KidWallKind.TV.catalogId
    }
