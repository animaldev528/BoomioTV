package com.nuvio.tv.ui.screens.kids

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MoreLikeThisList
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MoreLikeThisRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Result wall behind the long-press "More like this" (kids walls AND adult
 * AI-search rows). One instance per
 * [com.nuvio.tv.ui.navigation.Screen.MoreLikeThis] route entry.
 *
 * Given a pressed title's type + tt id, it watches the ACTIVE profile's
 * installed addons (same discovery as [KidWallViewModel]) until it finds that
 * profile's curated row addon — the publish_addons row manifests under a
 * /row/ base URL. The more-like-this endpoint is per-user (keyed by the user id
 * in the addon's URL, not by catalog or row), so ANY of the profile's row
 * addons answers for its whole curated universe; the addon is re-resolved rather
 * than trusting the pressed tile's own addon, so a Saved tile added from any
 * addon still queries the profile's curated picks (hub-leomovies for Leo,
 * hub-foryoupicksmovie & co. for adult personas — matched generically by the
 * /row/ URL + a hub-* catalog of the requested media type).
 * It then lazily pages the endpoint's curated co-members: page 0 on discovery,
 * later pages on scroll via [loadMore] (server offsets by skip = items seen so
 * far). The facet [lists] returned on page 0 feed the screen's caption chips. A
 * title outside the profile's curated universe returns an empty page (or the
 * endpoint errors) — both fall back to TMDB recommendations so the wall always
 * has an answer instead of dead-ending on an empty state.
 */
@HiltViewModel
class MoreLikeThisViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val moreLikeThisRepository: MoreLikeThisRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    val posterOptions: PosterOptionsController
) : ViewModel() {

    companion object {
        private const val TAG = "MoreLikeThisViewModel"
    }

    private val _uiState = MutableStateFlow(MoreLikeThisUiState())
    val uiState: StateFlow<MoreLikeThisUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null
    private var loadedForKey: String? = null

    /** Keys already answered from the TMDB fallback while no curated row addon exists. */
    private val tmdbFallbackKeys = mutableSetOf<String>()

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

                val addon = curatedRowAddonFor(enabled, itemType)
                if (addon == null) {
                    // Enabled set is loaded but none serves this profile's curated rows yet —
                    // either still syncing after a publish or a profile with no curated rows
                    // at all (onboarding starter). Don't leave a dead "not available yet" wall:
                    // answer from TMDB now. loadedForKey is deliberately NOT set and the key is
                    // only guarded once, so when row addons do appear the collector above falls
                    // through and the curated page replaces this.
                    if (key !in tmdbFallbackKeys) {
                        tmdbFallbackKeys += key
                        loadTmdbFallback(metaId, normalizedType(itemType))
                    }
                    return@collect
                }
                if (loadedForKey == key) return@collect // pages already loading

                loadedForKey = key
                loadFirstPage(addon, normalizedType(itemType), metaId, exclude)
            }
        }
    }

    private fun loadFirstPage(
        addon: Addon,
        requestType: String,
        metaId: String,
        exclude: List<String>
    ) {
        _uiState.update { it.copy(isInitialLoading = true, missingCatalog = false, loadError = null) }
        viewModelScope.launch {
            val result = moreLikeThisRepository.getMoreLikeThis(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                type = requestType,
                metaId = metaId,
                skip = 0,
                exclude = exclude
            ).first { it !is NetworkResult.Loading }
            when (result) {
                is NetworkResult.Success -> {
                    val page = result.data
                    if (page.items.isNotEmpty()) {
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
                    } else {
                        // The resolver is a membership query over the profile's curated doc,
                        // so an arbitrary title (not a curated member) correctly returns no
                        // co-members. Don't dead-end on an empty wall — answer from TMDB.
                        loadTmdbFallback(metaId, requestType)
                    }
                }
                is NetworkResult.Error -> {
                    // Resolver errored (e.g. unknown tt for this profile). Log and answer
                    // from TMDB rather than surfacing a dead "not available" wall.
                    Log.w(TAG, "curated more-like-this failed for $requestType:$metaId — ${result.message}")
                    loadTmdbFallback(metaId, requestType)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * TMDB fallback for the curated resolver's empty/error answers (see
     * [loadFirstPage]). Resolves the pressed tt id to a tmdb id via
     * [TmdbService.ensureTmdbId], then asks [TmdbMetadataService.fetchMoreLikeThis]
     * for landscape similar-title cards. The tiles belong to no curated row, so
     * the addon base is cleared (null): Detail and poster-options resolve the
     * `tmdb:` ids through TMDB, and paging is off ([MoreLikeThisUiState.hasMore]
     * false). Returns an empty list when the title has no tmdb mapping or no
     * recommendations — the screen's ordinary empty wall then stands.
     */
    private fun loadTmdbFallback(metaId: String, requestType: String) {
        viewModelScope.launch {
            val contentType = if (requestType == "movie") ContentType.MOVIE else ContentType.SERIES
            val items = try {
                val tmdbId = tmdbService.ensureTmdbId(metaId, if (contentType == ContentType.MOVIE) "movie" else "tv")
                if (tmdbId == null) {
                    Log.w(TAG, "no tmdb id for $requestType:$metaId — nothing to recommend from")
                    emptyList()
                } else {
                    tmdbMetadataService.fetchMoreLikeThis(tmdbId, contentType)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tmdb more-like-this fallback failed for $requestType:$metaId — ${e.message}")
                emptyList()
            }

            _uiState.update { s ->
                s.copy(
                    items = items,
                    lists = emptyList(),
                    isInitialLoading = false,
                    hasMore = false,
                    isLoadingMore = false,
                    loadError = null,
                    addonBaseUrl = null,
                    addonId = "",
                    addonName = "",
                    metaId = metaId,
                    itemType = requestType,
                    exclude = s.exclude
                )
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

/**
 * Resolve the ACTIVE profile's curated row addon that answers a more-like-this
 * query for [itemType].
 *
 * publish_addons.py publishes each profile's curated rows as one addon per row
 * under a `/row/<rowId>/` base URL (hub-leomovies/hub-leoshows for Leo,
 * hub-foryoupicksmovie & co. for adult personas). The server's more-like-this
 * resource is keyed by the user id embedded in that URL — the row id and catalog
 * are irrelevant to the query — so ANY of the profile's row addons serves its
 * whole curated universe. Resolution therefore scans the profile's enabled
 * addons for a `/row/` addon, preferring one that advertises a `hub-*` catalog of
 * the requested media type, then any `/row/` addon of that type, then any `/row/`
 * addon at all. Returns null when the profile has no curated row addons (no
 * persona published), which the caller surfaces as a soft "still updating" state.
 */
private fun curatedRowAddonFor(addons: List<Addon>, itemType: String): Addon? {
    val rowAddons = addons.filter { "/row/" in it.baseUrl }
    if (rowAddons.isEmpty()) return null

    fun Addon.advertisesHubCatalog(): Boolean =
        catalogs.any { it.id.startsWith("hub-") }

    return rowAddons.firstOrNull { a -> a.advertisesHubCatalog() && a.servesType(itemType) }
        ?: rowAddons.firstOrNull { it.servesType(itemType) }
        ?: rowAddons.firstOrNull { it.advertisesHubCatalog() }
        ?: rowAddons.first()
}

/** Whether a row addon advertises a catalog of the requested media type. */
private fun Addon.servesType(itemType: String): Boolean =
    catalogs.any { it.apiType.matchesType(itemType) }

/** Server-facing media type: movie, or series (tv/anime presses are series). */
private fun normalizedType(itemType: String): String =
    if (itemType.equals("movie", ignoreCase = true)) "movie" else "series"

private fun String.matchesType(itemType: String): Boolean {
    val normalized = normalizedType(itemType)
    return if (normalized == "movie") {
        equals("movie", ignoreCase = true)
    } else {
        equals("series", ignoreCase = true) ||
            equals("tv", ignoreCase = true) ||
            equals("anime", ignoreCase = true)
    }
}
