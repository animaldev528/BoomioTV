package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.model.CatalogRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
        title: String
    ) {
        val key = "$drillCatalogId|$addonId|$type"
        if (initializedKey == key) return
        initializedKey = key
        viewModelScope.launch { load(drillCatalogId, addonId, addonBaseUrl, type, title) }
    }

    private suspend fun load(
        drillCatalogId: String,
        addonId: String,
        addonBaseUrl: String,
        type: String,
        title: String
    ) {
        _state.value = UiState(title = title, isLoading = true)
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
    }

    private suspend fun fetch(
        catalogId: String,
        addonId: String,
        addonBaseUrl: String,
        type: String,
        name: String
    ): CatalogRow? {
        val result = catalogRepository
            .getCatalog(
                addonBaseUrl = addonBaseUrl,
                addonId = addonId,
                addonName = addonId,
                catalogId = catalogId,
                catalogName = name,
                type = type,
                skip = 0,
                skipStep = 100,
                extraArgs = emptyMap(),
                supportsSkip = true
            )
            .first { it !is NetworkResult.Loading }
        return (result as? NetworkResult.Success)?.data
    }
}
