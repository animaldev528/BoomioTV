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
import com.nuvio.tv.ui.screens.home.FETCH_ALL_MAX_ITEMS
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
 * provides the posters shown in the row. Non-hub catalogs of the kind become
 * single-row fallback sections so nothing previously reachable disappears.
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
        /** Page size for sub-row content fetches — drill tiles sit on page 0. */
        private const val FETCH_SKIP_STEP = 100
        /** A plain catalog becomes at most this many rows (keeps a tab bounded). */
        private const val MAX_PLAIN_SECTION_ROWS = 6
        /** Titles per split row; one addon page, so a split costs one request. */
        private const val PLAIN_ROW_ITEMS = 25
        /** A split row thinner than this reads as a bug, not a shelf: drop it. */
        private const val MIN_PLAIN_ROW_ITEMS = 4
        /** Extras a plain catalog can be split by, in preference order. */
        private val PLAIN_SPLIT_EXTRAS = listOf("genre", "year", "language")
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
        val isHub: Boolean,
        /** Extra a plain catalog splits by (genre/year/language), when offered. */
        val splitExtra: String? = null,
        /** That extra's selectable values, "All" removed. */
        val splitOptions: List<String> = emptyList()
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
                val split = catalog.extra.firstOrNull { extra ->
                    PLAIN_SPLIT_EXTRAS.any { it.equals(extra.name, ignoreCase = true) } &&
                        extra.options.orEmpty().any { !it.equals("All", ignoreCase = true) }
                }
                val splitOptions = split?.options.orEmpty()
                    .filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
                all += SourceSpec(
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    isHub = catalog.id.startsWith("hub-"),
                    splitExtra = split?.name,
                    splitOptions = splitOptions
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
            loadPlainSectionRows(source)
        }
        if (rows.isEmpty()) return null
        return HubBrowseSection(id = source.catalogId, groupName = source.catalogName, rows = rows)
    }

    /**
     * Rows for a plain (non-hub) catalog. Structurally such a catalog is a
     * single shelf, so a tab fed only by plain catalogs (the stock Nuvio
     * addon's Critically Acclaimed / International Cinema / Documentaries,
     * a kid board's Movies/TV) used to render one lonely strip — useless on
     * a TV. Split it into several real rows instead:
     *
     *  - one row per genre/year/language option the manifest advertises
     *    (Critically Acclaimed → Action / Adventure / …; Movies by Year →
     *    2025 / 2024 / …), which the addon already serves as distinct
     *    catalog states; else
     *  - one row per page of the catalog (25 titles) when it has no such
     *    extra, so a long shelf still reads as browsable rows.
     *
     * Every row carries a unique catalogId — it is the LazyColumn row key —
     * and the trailing "More Like This ▸" drill tile stays on the first row
     * only. Split-by-option rows are titled with their option ("Action",
     * "2025"); sliced rows keep the catalog's own title.
     */
    private suspend fun loadPlainSectionRows(source: SourceSpec): List<HubBrowseRow> {
        val extraName = source.splitExtra
        if (extraName != null && source.splitOptions.isNotEmpty()) {
            val rows = mutableListOf<HubBrowseRow>()
            for (option in source.splitOptions.take(MAX_PLAIN_SECTION_ROWS)) {
                val ex = fetchRow(
                    source.addonId, source.addonName, source.addonBaseUrl,
                    source.catalogId, option, source.type,
                    extraArgs = mapOf(extraName to option),
                    maxItems = PLAIN_ROW_ITEMS
                ) ?: continue
                if (ex.row.items.size < MIN_PLAIN_ROW_ITEMS) continue
                rows += HubBrowseRow(
                    ex.row.copy(
                        catalogId = "${source.catalogId}#$extraName=$option",
                        extraArgs = mapOf(extraName to option)
                    ),
                    ex.target
                )
            }
            if (rows.isNotEmpty()) return rows
        }
        val ex = fetchRow(
            source.addonId, source.addonName, source.addonBaseUrl,
            source.catalogId, source.catalogName, source.type
        ) ?: return emptyList()
        return chunkIntoRows(ex, source.catalogId)
    }

    /** Slice one long flat shelf into page-sized rows (25 titles each). */
    private fun chunkIntoRows(ex: DrillExtraction, catalogId: String): List<HubBrowseRow> =
        ex.row.items
            .chunked(PLAIN_ROW_ITEMS)
            .take(MAX_PLAIN_SECTION_ROWS)
            .mapIndexed { index, chunk ->
                HubBrowseRow(
                    ex.row.copy(
                        catalogId = if (index == 0) catalogId else "$catalogId#p$index",
                        items = chunk,
                        hasMore = false
                    ),
                    if (index == 0) ex.target else null
                )
            }
                .filter { it.row.items.isNotEmpty() }


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
            // No sub-rows: surface the hub row's own merged content, sliced into
            // page-sized rows so a flat hub (e.g. a kid board's movies) is still a
            // browsable multi-row tab instead of one endless strip.
            val hub = fetchRow(
                source.addonId, source.addonName, source.addonBaseUrl,
                source.catalogId, source.catalogName, source.type
            ) ?: return emptyList()
            return chunkIntoRows(hub, source.catalogId)
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

    /** A catalog row fetched to completion, with its trailing drill tiles stripped. */
    private suspend fun fetchRow(
        addonId: String,
        addonName: String,
        addonBaseUrl: String,
        catalogId: String,
        catalogName: String,
        type: String,
        extraArgs: Map<String, String> = emptyMap(),
        maxItems: Int = FETCH_ALL_MAX_ITEMS
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
                skipStep = FETCH_SKIP_STEP,
                maxItems = maxItems,
                extraArgs = extraArgs
            )
        } ?: return null
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
