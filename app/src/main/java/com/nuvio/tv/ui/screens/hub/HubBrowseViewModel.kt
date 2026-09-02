package com.nuvio.tv.ui.screens.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.screens.home.DrillExtraction
import com.nuvio.tv.ui.screens.home.DrillTarget
import com.nuvio.tv.ui.screens.home.REC_DRILL_SUFFIX
import com.nuvio.tv.ui.screens.home.extractDrill
import com.nuvio.tv.ui.screens.home.isCatalogDisabledIn
import com.nuvio.tv.ui.screens.home.isFeaturedHomeCatalog
import com.nuvio.tv.ui.screens.home.isSearchOnlyCatalog
import com.nuvio.tv.ui.screens.home.isSeriesType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Which Movies/TV hub this is. Stays in the hub package — nav imports it. */
enum class HubKind { MOVIES, TV }

/**
 * Movies/TV hub as a rows browser: a vertical list of sections, one per
 * `hub-` catalog of the kind (Genre, Superhero, Year, Extras). Each section
 * streams in as its coarse sub-rows load; a coarse row carries a trailing
 * "More Like This ▸" drill ([DrillTarget]) where finer rows exist.
 *
 * A hub catalog's real sub-rows are discovered through its drill catalog
 * `rec-<rowId>-drilldown` (tiles, id `rec-<subRowId>`); each tile's own catalog
 * provides the posters shown in the row. Non-hub catalogs of the kind become
 * single-row fallback sections so nothing previously reachable disappears.
 */
@HiltViewModel
class HubBrowseViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    companion object {
        private const val MAX_CATALOG_CONCURRENCY = 4
        /** Page size for sub-row content fetches — drill tiles sit on page 0. */
        private const val FETCH_SKIP_STEP = 100
    }

    /** One coarse row: a sub-row's posters plus its optional drill target. */
    data class HubBrowseRow(val row: CatalogRow, val drill: DrillTarget?)

    /** A group: the hub category name (e.g. "Genre") over its coarse rows. */
    data class HubBrowseSection(
        val id: String,
        val groupName: String,
        val rows: List<HubBrowseRow>
    )

    data class HubBrowseUiState(
        val kind: HubKind = HubKind.MOVIES,
        val sections: List<HubBrowseSection> = emptyList(),
        val isLoading: Boolean = true,
        val isEmpty: Boolean = false
    )

    /** A catalog feeding this hub, tagged with whether it is a hub- row. */
    private class SourceSpec(
        val addonId: String,
        val addonName: String,
        val addonBaseUrl: String,
        val catalogId: String,
        val catalogName: String,
        val type: String,
        val isHub: Boolean
    )

    private val _state = MutableStateFlow(HubBrowseUiState())
    val state: StateFlow<HubBrowseUiState> = _state.asStateFlow()

    private val catalogSemaphore = Semaphore(MAX_CATALOG_CONCURRENCY)
    private val publishMutex = Mutex()
    private var initializedKind: HubKind? = null
    private var loadJob: Job? = null

    fun initialize(kind: HubKind) {
        if (initializedKind == kind && loadJob?.isActive == true) return
        initializedKind = kind
        load(kind)
    }

    private fun load(kind: HubKind) {
        loadJob?.cancel()
        _state.value = HubBrowseUiState(kind = kind, isLoading = true)
        loadJob = viewModelScope.launch {
            val addons = runCatching {
                addonRepository.getInstalledAddons().first { it.isNotEmpty() }
            }.getOrDefault(emptyList()).enabledAddons()
            val disabledKeys = layoutPreferenceDataStore.disabledHomeCatalogKeys.first().toSet()
            val sources = buildSources(addons, kind, disabledKeys)
            if (sources.isEmpty()) {
                _state.value = HubBrowseUiState(kind = kind, isLoading = false, isEmpty = true)
                return@launch
            }

            val done = BooleanArray(sources.size)
            val sections = arrayOfNulls<HubBrowseSection>(sources.size)
            sources.forEachIndexed { index, source ->
                launch {
                    val section = loadSection(source)
                    publishMutex.withLock {
                        done[index] = true
                        sections[index] = section
                        publishInOrder(kind, done, sections)
                    }
                }
            }
        }
    }

    /** Movie-type catalogs (or series/tv for the TV hub), hub- first. */
    private fun buildSources(
        addons: List<Addon>,
        kind: HubKind,
        disabledKeys: Set<String>
    ): List<SourceSpec> {
        val all = mutableListOf<SourceSpec>()
        addons.forEach { addon ->
            addon.catalogs.forEach { catalog ->
                val matchesKind = when (kind) {
                    HubKind.MOVIES -> catalog.apiType.equals("movie", ignoreCase = true)
                    HubKind.TV -> isSeriesType(catalog.apiType)
                }
                if (!matchesKind) return@forEach
                if (catalog.isSearchOnlyCatalog()) return@forEach
                // Featured/curated catalogs stay on Home, not the hubs.
                if (catalog.isFeaturedHomeCatalog()) return@forEach
                if (isCatalogDisabledIn(
                        disabledKeys,
                        addon.baseUrl,
                        addon.id,
                        catalog.apiType,
                        catalog.id,
                        catalog.name
                    )
                ) {
                    return@forEach
                }
                all += SourceSpec(
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    isHub = catalog.id.startsWith("hub-")
                )
            }
        }
        // Hub- catalogs become the grouped sections. Genre first (the browse is
        // "genre on top, action, comedy…" then finer rows); Studios/Extras sink to
        // the bottom. Non-hub catalogs of the kind append as single-row fallback
        // sections so nothing previously reachable disappears.
        return all.filter { it.isHub }.sortedBy { hubSectionRank(it.catalogId) } + all.filterNot { it.isHub }
    }

    /** Display order for hub- sections, independent of manifest/rows order.
     *  This is the tab-ribbon order on screen: Genre, Years, Documentaries,
     *  Networks, Superhero, Extras, Studios(movies). */
    private fun hubSectionRank(catalogId: String): Int {
        val id = catalogId.removePrefix("hub-")
        return when {
            id.startsWith("genre") -> 0
            id.startsWith("year") -> 1
            id.startsWith("documentary") -> 2
            id.startsWith("network") -> 3
            id.startsWith("superhero") -> 4
            id.startsWith("extras") -> 5
            id.startsWith("studios") -> 6
            else -> 7
        }
    }

    private suspend fun loadSection(source: SourceSpec): HubBrowseSection? {
        val rows = if (source.isHub) {
            loadHubSectionRows(source)
        } else {
            val ex = fetchRow(source.addonId, source.addonName, source.addonBaseUrl, source.catalogId, source.catalogName, source.type)
            if (ex == null || ex.row.items.isEmpty()) emptyList() else listOf(HubBrowseRow(ex.row, ex.target))
        }
        if (rows.isEmpty()) return null
        return HubBrowseSection(id = source.catalogId, groupName = source.catalogName, rows = rows)
    }

    /** Coarse rows for a hub category, via its drill catalog's sub-row tiles. */
    private suspend fun loadHubSectionRows(source: SourceSpec): List<HubBrowseRow> {
        val rowId = source.catalogId.removePrefix("hub-")
        val drillCatalogId = "rec-$rowId$REC_DRILL_SUFFIX"
        val drill = fetchRow(
            source.addonId, source.addonName, source.addonBaseUrl,
            drillCatalogId, source.catalogName, source.type
        )?.row
        val tiles = drill?.items.orEmpty()
        if (tiles.isEmpty()) {
            // No sub-rows: surface the hub row's own merged content as one row so
            // the group still has something to show.
            val hub = fetchRow(
                source.addonId, source.addonName, source.addonBaseUrl,
                source.catalogId, source.catalogName, source.type
            ) ?: return emptyList()
            return if (hub.row.items.isEmpty()) {
                emptyList()
            } else {
                listOf(HubBrowseRow(hub.row, hub.target))
            }
        }
        val rows = mutableListOf<HubBrowseRow>()
        for (tile in tiles) {
            val sub = fetchRow(
                source.addonId, source.addonName, source.addonBaseUrl,
                tile.id, tile.name, tile.apiType
            ) ?: continue
            if (sub.row.items.isEmpty()) continue
            rows += HubBrowseRow(sub.row, sub.target)
        }
        return rows
    }

    /** One catalog page with its trailing drill tiles stripped. */
    private suspend fun fetchRow(
        addonId: String,
        addonName: String,
        addonBaseUrl: String,
        catalogId: String,
        catalogName: String,
        type: String
    ): DrillExtraction? {
        val result = catalogSemaphore.withPermit {
            catalogRepository.getCatalog(
                addonBaseUrl = addonBaseUrl,
                addonId = addonId,
                addonName = addonName,
                catalogId = catalogId,
                catalogName = catalogName,
                type = type,
                skip = 0,
                skipStep = FETCH_SKIP_STEP,
                extraArgs = emptyMap(),
                supportsSkip = true
            ).first { it !is NetworkResult.Loading }
        }
        val data = (result as? NetworkResult.Success)?.data ?: return null
        if (data.items.isEmpty()) return null
        return data.extractDrill()
    }

    /** Emit the contiguous done-prefix so sections stream in manifest order. */
    private fun publishInOrder(kind: HubKind, done: BooleanArray, sections: Array<HubBrowseSection?>) {
        val out = mutableListOf<HubBrowseSection>()
        var i = 0
        while (i < done.size && done[i]) {
            sections[i]?.let { out += it }
            i++
        }
        val allDone = done.all { it }
        _state.value = HubBrowseUiState(
            kind = kind,
            sections = out,
            isLoading = !allDone,
            isEmpty = allDone && out.isEmpty()
        )
    }
}
