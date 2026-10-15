package com.nuvio.tv.ui.screens.publiclists

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.TraktPublicListRef
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

/**
 * Backs the public-lists screen (see `Screen.PublicLists`).
 *
 * Two requests deep, in order:
 *
 *  1. the Trakt reverse lookup — which public lists contain this title — which answers with
 *     list *refs* and, in a header, how many there are in total;
 *  2. one item fetch per ref, via [TraktPublicListSourceResolver], which is the same resolver
 *     the collection editor uses for a Trakt-list source, so a list renders here exactly the
 *     way it renders as a collection.
 *
 * The lookups are sequential on purpose. Each rides
 * [com.nuvio.tv.data.repository.TraktAuthService]'s rate-limit slot and retry ladder, and
 * firing eight of them at once would spend the same budget while making a partial failure
 * harder to attribute to a list.
 */
@HiltViewModel
class PublicListsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktLibraryService: TraktLibraryService,
    private val traktPublicListSourceResolver: TraktPublicListSourceResolver,
    val posterOptions: PosterOptionsController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val itemType: String = savedStateHandle.get<String>("itemType").orEmpty()
    private val itemId: String = savedStateHandle.get<String>("itemId").orEmpty()
    private val title: String = decodeArg(savedStateHandle.get<String>("title"))
    private val imdbId: String = decodeArg(savedStateHandle.get<String>("imdbId"))

    /** "series"/"tv"/"anime" all mean the show endpoint; anything else is a movie. */
    private val isSeries: Boolean = itemType.equals("series", true) ||
        itemType.equals("tv", true) ||
        itemType.equals("anime", true) ||
        itemType.equals("show", true)

    private val _uiState = MutableStateFlow(PublicListsUiState(pressedTitle = title))
    val uiState: StateFlow<PublicListsUiState> = _uiState.asStateFlow()

    /**
     * The source behind each rendered row, by row key, so the row can be paged further
     * without the screen having to hold on to Trakt ids.
     */
    private val rowSources = mutableMapOf<String, TraktCollectionSource>()

    /** Rows with a page in flight, so scrolling at the end cannot stack requests. */
    private val pagingRows = mutableSetOf<String>()

    init {
        posterOptions.bind(viewModelScope)
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.value = PublicListsUiState(pressedTitle = title, isLoading = true)
            // The rows are rebuilt from scratch, so what the old ones knew about paging goes
            // with them.
            rowSources.clear()
            pagingRows.clear()

            val found = runCatching {
                traktLibraryService.getPublicListsContaining(
                    // itemId may be a "tmdb:" content id, which Trakt must not be handed as
                    // an id; the tile's own IMDb id wins when the route carried one, and
                    // with neither the service refuses rather than answering about the wrong
                    // title. See TraktApi.getMoviePublicLists.
                    item = LibraryEntryInput(
                        itemId = itemId,
                        itemType = itemType,
                        title = title,
                        imdbId = imdbId.takeIf { it.isNotBlank() }
                    ),
                    limit = MAX_ROWS
                )
            }.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message
                            ?: context.getString(R.string.poster_options_public_lists_failed)
                    )
                }
                return@launch
            }

            // A ref with no numeric id cannot be fetched as a list; drop it rather than
            // guess at one (the name alone is not an id).
            val renderable = found.lists.filter { it.traktListId != null }
            _uiState.update {
                it.copy(totalAvailable = found.totalAvailable, isLoading = renderable.isNotEmpty())
            }

            var unavailable = 0
            renderable.forEach { ref ->
                _uiState.update { it.copy(pendingListName = ref.name) }
                val row = resolveRow(ref)
                if (row == null || row.items.isEmpty()) {
                    unavailable++
                } else {
                    _uiState.update { it.copy(rows = it.rows + row) }
                }
            }
            _uiState.update {
                it.copy(isLoading = false, pendingListName = null, unavailable = unavailable)
            }
        }
    }

    private suspend fun resolveRow(ref: TraktPublicListRef): CatalogRow? {
        val listId = ref.traktListId ?: return null
        val source = TraktCollectionSource(
            title = rowName(ref),
            traktListId = listId,
            mediaType = if (isSeries) TmdbCollectionMediaType.TV else TmdbCollectionMediaType.MOVIE
        )
        // The flow emits Loading first, then one terminal Success or Error. Take the
        // terminal one; a null here is a list we cannot render, counted as unavailable.
        val row = traktPublicListSourceResolver.resolve(source)
            .firstOrNull { it !is NetworkResult.Loading }
            ?.let { (it as? NetworkResult.Success<CatalogRow>)?.data }
        if (row != null) rowSources[row.catalogId] = source
        return row
    }

    /**
     * Put the next page of [rowKey] on the end of that row, for the screen to call as the row's
     * last cards come into view.
     *
     * One page here is [TraktPublicListSourceResolver.PAGE_LIMIT] titles, so without this a
     * list is exactly one screen-and-a-bit wide no matter how many titles it holds. The merge
     * is the collection folder's: ids already in the row are dropped, and a page that adds
     * nothing ends the row so the scroll cannot keep asking for it.
     */
    fun loadMore(rowKey: String) {
        val source = rowSources[rowKey] ?: return
        val row = _uiState.value.rows.firstOrNull { it.catalogId == rowKey } ?: return
        if (!row.hasMore || row.isLoading || !pagingRows.add(rowKey)) return

        _uiState.update { state ->
            state.copy(rows = state.rows.map { existing ->
                if (existing.catalogId == rowKey) existing.copy(isLoading = true) else existing
            })
        }

        viewModelScope.launch {
            val page = runCatching {
                traktPublicListSourceResolver.resolve(source, row.currentPage + 1)
                    .firstOrNull { it !is NetworkResult.Loading }
            }.getOrNull()?.let { (it as? NetworkResult.Success<CatalogRow>)?.data }

            val existingKeys = row.items.mapTo(mutableSetOf()) { "${it.apiType}:${it.id}" }
            val added = page?.items.orEmpty().filter { "${it.apiType}:${it.id}" !in existingKeys }

            _uiState.update { state ->
                state.copy(rows = state.rows.map { existing ->
                    if (existing.catalogId != rowKey) existing
                    else if (page == null) existing.copy(isLoading = false)
                    else existing.copy(
                        items = existing.items + added,
                        hasMore = page.hasMore && added.isNotEmpty(),
                        currentPage = page.currentPage,
                        isLoading = false
                    )
                })
            }
            pagingRows.remove(rowKey)
        }
    }

    /**
     * The row header. Owner is folded in because a public list's name alone is ambiguous —
     * "Watchlist" on its own does not say whose.
     */
    private fun rowName(ref: TraktPublicListRef): String {
        val owner = ref.ownerUsername?.takeIf { it.isNotBlank() } ?: return ref.name
        return "${ref.name} · ${context.getString(R.string.poster_options_public_lists_owner, owner)}"
    }

    private fun decodeArg(raw: String?): String {
        val value = raw.orEmpty()
        if (value.isBlank()) return ""
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    companion object {
        /**
         * How many lists are turned into rows. The reverse lookup is asked for exactly this
         * many, so the request and the screen agree; [PublicListsUiState.totalAvailable]
         * still reports the real total, and the header says so when it is larger.
         */
        const val MAX_ROWS = 8
    }
}
