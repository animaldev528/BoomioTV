package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.model.CatalogRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Rows-browser for a drill-down catalog (rec-<rowId>-drilldown).
 *
 * Fetches the drill catalog's items (sub-row tiles, id rec-<subRowId>), then
 * fetches each sub-row's catalog and exposes it as a [CatalogRow] so the screen
 * renders the sub-rows as horizontal content rows. Each row's trailing
 * "More Like This ▸" tile (stripped via [extractDrill]) becomes the drill target
 * for the next level.
 */
@HiltViewModel
class CategoryRowsViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    data class RowState(val row: CatalogRow, val drill: DrillTarget?)

    data class UiState(
        val title: String = "",
        val rows: List<RowState> = emptyList(),
        val isLoading: Boolean = true,
        val isEmpty: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var initializedKey: String? = null

    fun initialize(
        drillCatalogId: String,
        addonId: String,
        addonBaseUrl: String,
        type: String,
        title: String,
        secondaryCatalogId: String? = null,
        secondaryAddonId: String? = null,
        secondaryAddonBaseUrl: String? = null,
        secondaryType: String? = null
    ) {
        val key = "$drillCatalogId|$addonId|$type|${secondaryCatalogId.orEmpty()}|${secondaryAddonBaseUrl.orEmpty()}"
        if (initializedKey == key) return
        initializedKey = key
        viewModelScope.launch {
            load(
                drillCatalogId, addonId, addonBaseUrl, type, title,
                secondaryCatalogId, secondaryAddonId, secondaryAddonBaseUrl, secondaryType
            )
        }
    }

    private suspend fun load(
        drillCatalogId: String,
        addonId: String,
        addonBaseUrl: String,
        type: String,
        title: String,
        secondaryCatalogId: String?,
        secondaryAddonId: String?,
        secondaryAddonBaseUrl: String?,
        secondaryType: String?
    ) {
        _state.value = UiState(title = title, isLoading = true)

        // Single-drill path (rec rows, and every drill on the Movies/TV hub): the
        // primary drill catalog alone is the whole browser, unchanged.
        if (secondaryCatalogId.isNullOrBlank()) {
            val drill = fetch(drillCatalogId, addonId, addonBaseUrl, type, title)
            if (drill == null) {
                _state.value = UiState(title = title, isLoading = false, error = "Couldn't load this category")
                return
            }
            val tiles = drill.items
            if (tiles.isEmpty()) {
                _state.value = UiState(title = title, isLoading = false, isEmpty = true)
                return
            }
            val rows = mutableListOf<RowState>()
            for (tile in tiles) {
                val sub = fetch(tile.id, addonId, addonBaseUrl, tile.apiType, tile.name)
                if (sub == null || sub.items.isEmpty()) continue
                val ex = sub.extractDrill()
                rows.add(RowState(ex.row, ex.target))
            }
            _state.value = UiState(title = title, rows = rows, isLoading = false, isEmpty = rows.isEmpty())
            return
        }

        // Combined-drill path: a Home hub-group door (hub-genremovie + hub-genreseries
        // folded into one row) drills into BOTH siblings' sub-row catalogs — the movie
        // genre rails, then the series genre rails. Each sibling's drill catalog and its
        // sub-rows live on that sibling's own row addon, so each drain uses its own addon.
        val rows = mutableListOf<RowState>()
        var anyDrillReachable = false

        suspend fun drain(dCatalogId: String, dAddonId: String, dBaseUrl: String, dType: String) {
            val drill = fetch(dCatalogId, dAddonId, dBaseUrl, dType, title)
                ?: return
            val tiles = drill.items
            if (tiles.isEmpty()) return
            anyDrillReachable = true
            for (tile in tiles) {
                val sub = fetch(tile.id, dAddonId, dBaseUrl, tile.apiType, tile.name)
                if (sub == null || sub.items.isEmpty()) continue
                val ex = sub.extractDrill()
                rows.add(RowState(ex.row, ex.target))
            }
        }

        drain(drillCatalogId, addonId, addonBaseUrl, type)
        drain(
            secondaryCatalogId,
            secondaryAddonId ?: addonId,
            secondaryAddonBaseUrl ?: addonBaseUrl,
            secondaryType ?: type
        )

        _state.value = UiState(
            title = title,
            rows = rows,
            isLoading = false,
            isEmpty = rows.isEmpty() && anyDrillReachable,
            error = if (rows.isEmpty() && !anyDrillReachable) "Couldn't load this category" else null
        )
    }

    private suspend fun fetch(
        catalogId: String,
        addonId: String,
        addonBaseUrl: String,
        type: String,
        name: String
    ): CatalogRow? =
        catalogRepository.fetchCatalogAll(
            addonId = addonId,
            addonName = addonId,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = name,
            type = type
        )
}
