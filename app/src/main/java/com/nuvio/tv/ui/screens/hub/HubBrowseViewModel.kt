package com.nuvio.tv.ui.screens.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.nuvio.tv.ui.screens.home.fetchCatalogAll
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

/** Which Movies/TV/Anime hub this is. Stays in the hub package — nav imports it. */
enum class HubKind { MOVIES, TV, ANIME }

/**
 * Movies/TV hub as a rows browser: a vertical list of sections, one per
 * `hub-` catalog of the kind (Genre, Superhero, Year, Extras). Each section
 * streams in as its coarse sub-rows load; a coarse row carries a trailing
 * "More Like This ▸" drill ([DrillTarget]) where finer rows exist.
 *
 * A hub catalog's real sub-rows are discovered through its drill catalog
 * `rec-<rowId>-drilldown` (tiles, id `rec-<subRowId>`); each tile's own catalog
 * provides the posters shown in the row.
 *
 * A catalog that is not a `hub-` one carries no sub-rows, so it can only ever
 * render as a SINGLE row — as a ribbon tab that is a one-row item sitting next
 * to hubs that already cover the same ground. Those tabs are dropped; they
 * survive only on a profile with no hubs at all (a fresh profile before its
 * board is built), so the screen is never empty.
 *
 * Rows publish as they arrive and sections load in ribbon order a few at a
 * time, so the tab the user is looking at fills first instead of waiting behind
 * every other tab's fetch.
 *
 * Anime is a separate destination: ja-language animation leaves regular
 * rotation entirely, so its `hub-anim*` catalogs are excluded from the MOVIES
 * and TV hubs and shown only under the Anime hub (a left-menu item, one tab
 * each for Anime Series and Anime Movies).
 */
@HiltViewModel
class HubBrowseViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    companion object {
        private const val MAX_CATALOG_CONCURRENCY = 4
        /**
         * Sections in flight. Sections are started in ribbon order, so capping
         * them keeps the semaphore for the tab on screen instead of sharing it
         * with every other tab — the first section finishes in a few requests
         * rather than behind the whole ribbon's worth.
         */
        private const val MAX_SECTION_CONCURRENCY = 3
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
            val sectionsInFlight = Semaphore(MAX_SECTION_CONCURRENCY)
            sources.forEachIndexed { index, source ->
                launch {
                    // Permits are handed out in ribbon order, so the tab on
                    // screen gets the network first and the tabs behind it
                    // stream in afterwards.
                    sectionsInFlight.withPermit {
                        fun publish(section: HubBrowseSection?) {
                            sections[index] = section
                            publishInOrder(kind, done, sections)
                        }
                        val section = loadSection(source) { partial ->
                            publishMutex.withLock { publish(partial) }
                        }
                        publishMutex.withLock {
                            done[index] = true
                            publish(section)
                        }
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
                // Anime's hub-anim* catalogs are their own destination: MOVIES/TV
                // never see them (anime leaves regular rotation), and the ANIME
                // hub serves only them — not the Movies/TV group catalogs.
                val isAnimeHub = catalog.id.startsWith("hub-anim")
                val matchesKind = when (kind) {
                    HubKind.MOVIES ->
                        catalog.apiType.equals("movie", ignoreCase = true) && !isAnimeHub
                    HubKind.TV -> isSeriesType(catalog.apiType) && !isAnimeHub
                    HubKind.ANIME -> isAnimeHub
                }
                if (!matchesKind) return@forEach
                if (catalog.isSearchOnlyCatalog()) return@forEach
                // Featured/curated catalogs (rec- shelves, per-service Popular/
                // Trending) stay on Home, not the hubs. hub- catalogs are the
                // hubs' OWN grouped sections (Genre/Years/Networks/Studios/...),
                // so they must never be excluded here even though Home's featured
                // allowlist may also admit them — otherwise the hub screens starve.
                if (!catalog.id.startsWith("hub-") && catalog.isFeaturedHomeCatalog()) return@forEach
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
        // the bottom. A non-hub catalog of the kind carries no sub-rows, so it can
        // only ever render one row — as a ribbon tab that is a one-row item beside
        // hubs that already cover the same ground. It survives only when there is
        // no hub at all (a fresh profile whose board has not been built yet), so
        // the screen is never empty.
        val hubs = all.filter { it.isHub }.sortedBy { hubSectionRank(it.catalogId) }
        return hubs.ifEmpty { all }
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

    private suspend fun loadSection(
        source: SourceSpec,
        onPartial: suspend (HubBrowseSection) -> Unit
    ): HubBrowseSection? {
        val rows = if (source.isHub) {
            loadHubSectionRows(source) { partial ->
                onPartial(HubBrowseSection(source.catalogId, source.catalogName, partial))
            }
        } else {
            val ex = fetchRow(source.addonId, source.addonName, source.addonBaseUrl, source.catalogId, source.catalogName, source.type)
            if (ex == null || ex.row.items.isEmpty()) emptyList() else listOf(HubBrowseRow(ex.row, ex.target))
        }
        if (rows.isEmpty()) return null
        return HubBrowseSection(id = source.catalogId, groupName = source.catalogName, rows = rows)
    }

    /**
     * Coarse rows for a hub category, via its drill catalog's sub-row tiles.
     * [onPartial] gets the rows accumulated so far after each one lands, so the
     * section fills on screen instead of appearing all at once at the end.
     */
    private suspend fun loadHubSectionRows(
        source: SourceSpec,
        onPartial: suspend (List<HubBrowseRow>) -> Unit
    ): List<HubBrowseRow> {
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
            if (hub.row.items.isEmpty()) return emptyList()
            val single = listOf(HubBrowseRow(hub.row, hub.target))
            onPartial(single)
            return single
        }
        val rows = mutableListOf<HubBrowseRow>()
        for (tile in tiles) {
            val sub = fetchRow(
                source.addonId, source.addonName, source.addonBaseUrl,
                tile.id, tile.name, tile.apiType
            ) ?: continue
            if (sub.row.items.isEmpty()) continue
            rows += HubBrowseRow(sub.row, sub.target)
            onPartial(rows.toList())
        }
        return rows
    }

    /** A catalog row fetched to completion, with its trailing drill tiles stripped. */
    private suspend fun fetchRow(
        addonId: String,
        addonName: String,
        addonBaseUrl: String,
        catalogId: String,
        catalogName: String,
        type: String
    ): DrillExtraction? {
        // The whole paged fetch holds one permit so concurrent rows stay bounded.
        val data = catalogSemaphore.withPermit {
            catalogRepository.fetchCatalogAll(
                addonId = addonId,
                addonName = addonName,
                addonBaseUrl = addonBaseUrl,
                catalogId = catalogId,
                catalogName = catalogName,
                type = type,
                skipStep = FETCH_SKIP_STEP
            )
        } ?: return null
        if (data.items.isEmpty()) return null
        return data.extractDrill()
    }

    /**
     * Emit the contiguous done-prefix so sections stream in manifest order, plus
     * the one section still being filled at the frontier — that is the tab on
     * screen, so its rows paint as they land instead of at the end.
     */
    private fun publishInOrder(kind: HubKind, done: BooleanArray, sections: Array<HubBrowseSection?>) {
        val out = mutableListOf<HubBrowseSection>()
        var i = 0
        while (i < done.size) {
            val section = sections[i]
            if (done[i]) {
                section?.let { out += it }
                i++
            } else {
                section?.let { out += it }
                break
            }
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
