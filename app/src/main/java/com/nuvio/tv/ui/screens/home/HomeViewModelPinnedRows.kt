package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.PinnedHomeRow
import com.nuvio.tv.domain.model.tmdbIdFromPersonPreviewId
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps [HomeViewModel.pinnedRowsCache] in step with the stored pinned rows.
 *
 * Deliberately the same shape as `observeCollectionsPipeline()`: debounce the store, re-resolve,
 * ask the catalog pipeline to rebuild. Resolution is network-bound (a Trakt list, a bsf episode
 * search), so a row that fails right now resolves to nothing and simply stays absent — the row
 * is still stored and comes back on the next rebuild, rather than disappearing from the user's
 * settings because Trakt had a bad minute.
 */
@OptIn(FlowPreview::class)
internal fun HomeViewModel.observePinnedHomeRows() {
    viewModelScope.launch {
        pinnedHomeRows.rows
            .distinctUntilChanged()
            .debounce(300)
            .collectLatest { rows ->
                val resolved = rows.mapNotNull { row ->
                    runCatching { pinnedHomeRowResolver.resolve(row) }.getOrNull()
                }
                pinnedRowsCache = resolved
                pinnedRowIdsByItemId = buildMap {
                    resolved.forEach { catalogRow ->
                        catalogRow.items.forEach { item -> putIfAbsent(item.id, catalogRow.catalogId) }
                    }
                }
                scheduleUpdateCatalogRows()
            }
    }
}

/** The pinned row that renders [itemId], or null when the tile is a normal addon row's. */
internal fun HomeViewModel.pinnedRowIdForItem(itemId: String): String? =
    pinnedRowIdsByItemId[itemId]

/** The display name for a person tile, so Home can open the same CastDetail Search does. */
internal fun HomeViewModel.personNameFor(tmdbId: Int): String =
    pinnedRowsCache.asSequence()
        .flatMap { it.items.asSequence() }
        .firstOrNull { item ->
            item.rawType == PinnedHomeRow.PEOPLE_RAW_TYPE &&
                tmdbIdFromPersonPreviewId(item.id) == tmdbId
        }
        ?.name
        .orEmpty()

/** Un-pins one row. The store emits, and the pipeline rebuilds Home without it. */
internal fun HomeViewModel.removePinnedRow(rowId: String) {
    viewModelScope.launch {
        pinnedHomeRows.remove(rowId)
    }
}

/**
 * Takes one person off the Cast & crew row. The store drops the row itself when that was the
 * last person, so the row is never left behind empty.
 */
internal fun HomeViewModel.removePersonFromPeopleRow(tmdbId: Int) {
    viewModelScope.launch {
        pinnedHomeRows.removePerson(tmdbId)
    }
}
