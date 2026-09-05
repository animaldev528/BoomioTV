package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Immutable
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.ratingGateKey
import com.nuvio.tv.domain.model.stableItemKey
import com.nuvio.tv.ui.util.asStable
import java.util.Locale
import kotlinx.coroutines.withContext

@Immutable
internal data class ModernHomePresentationInput(
    val homeRows: List<HomeRow>,
    val catalogRows: List<CatalogRow>,
    val continueWatchingItems: List<ContinueWatchingItem>,
    val upcomingItems: List<ContinueWatchingItem>,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val showFullReleaseDate: Boolean,
    val showImdbRatings: Boolean,
    val localeTag: String,
    val gatedItemKeys: Set<String> = emptySet()
)

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache,
    context: Context,
    maxCatalogRows: Int? = null
): ModernHomePresentationState {
    val visibleHomeRows = resolveVisibleHomeRows(input)
    val localizedContext = getLocalizedContext(context)
    val strContinueWatching = localizedContext.getString(R.string.continue_watching)
    val strAirsDate = localizedContext.getString(R.string.cw_airs_date)
    val strUpcoming = localizedContext.getString(R.string.cw_upcoming)
    val strTypeMovie = localizedContext.getString(R.string.type_movie)
    val strTypeSeries = localizedContext.getString(R.string.type_series)

    val rows = buildList {
        val activeCatalogKeys = LinkedHashSet<String>()
        val activeCollectionKeys = LinkedHashSet<String>()
        val catalogRowLimit = maxCatalogRows?.coerceAtLeast(0)
        var renderedCatalogRows = 0

        if (input.continueWatchingItems.isNotEmpty()) {
            val reuseContinueWatchingRow =
                cache.continueWatchingRow != null &&
                    cache.continueWatchingItems == input.continueWatchingItems &&
                    cache.continueWatchingTitle == strContinueWatching &&
                    cache.continueWatchingAirsDateTemplate == strAirsDate &&
                    cache.continueWatchingUpcomingLabel == strUpcoming &&
                    cache.continueWatchingUseLandscapePosters == input.useLandscapePosters &&
                    cache.continueWatchingShowImdbRatings == input.showImdbRatings
            val continueWatchingRow = if (reuseContinueWatchingRow) {
                checkNotNull(cache.continueWatchingRow)
            } else {
                HeroCarouselRow(
                    key = MODERN_CONTINUE_WATCHING_ROW_KEY,
                    title = strContinueWatching,
                    globalRowIndex = -1,
                    items = input.continueWatchingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            showImdbRatings = input.showImdbRatings,
                            airsDateTemplate = strAirsDate,
                            upcomingLabel = strUpcoming,
                            context = localizedContext
                        )
                    }.asStable()
                )
            }
            cache.continueWatchingItems = input.continueWatchingItems
            cache.continueWatchingTitle = strContinueWatching
            cache.continueWatchingAirsDateTemplate = strAirsDate
            cache.continueWatchingUpcomingLabel = strUpcoming
            cache.continueWatchingUseLandscapePosters = input.useLandscapePosters
            cache.continueWatchingShowImdbRatings = input.showImdbRatings
            cache.continueWatchingRow = continueWatchingRow
            add(continueWatchingRow)
        } else {
            cache.continueWatchingItems = emptyList()
            cache.continueWatchingRow = null
        }

        // Upcoming row (SPLIT_UPCOMING mode)
        val strUpcomingSectionTitle = localizedContext.getString(R.string.upcoming_section_title)
        if (input.upcomingItems.isNotEmpty()) {
            val reuseUpcomingRow =
                cache.upcomingRow != null &&
                    cache.upcomingItems == input.upcomingItems &&
                    cache.upcomingTitle == strUpcomingSectionTitle &&
                    cache.upcomingUseLandscapePosters == input.useLandscapePosters &&
                    cache.upcomingShowImdbRatings == input.showImdbRatings
            val upcomingRow = if (reuseUpcomingRow) {
                checkNotNull(cache.upcomingRow)
            } else {
                HeroCarouselRow(
                    key = MODERN_UPCOMING_ROW_KEY,
                    title = strUpcomingSectionTitle,
                    globalRowIndex = -1,
                    items = input.upcomingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            showImdbRatings = input.showImdbRatings,
                            airsDateTemplate = strAirsDate,
                            upcomingLabel = strUpcoming,
                            context = localizedContext
                        )
                    }.asStable()
                )
            }
            cache.upcomingItems = input.upcomingItems
            cache.upcomingTitle = strUpcomingSectionTitle
            cache.upcomingUseLandscapePosters = input.useLandscapePosters
            cache.upcomingShowImdbRatings = input.showImdbRatings
            cache.upcomingRow = upcomingRow
            add(upcomingRow)
        } else {
            cache.upcomingItems = emptyList()
            cache.upcomingRow = null
        }

        visibleHomeRows.forEachIndexed { index, homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val row = homeRow.row
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val rowKey = catalogRowKey(row)
                    activeCatalogKeys += rowKey
                    val cached = cache.catalogRows[rowKey]
                    val currentLocaleTag = LocaleCache.localeTag
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == row &&
                            cached.useLandscapePosters == input.useLandscapePosters &&
                            cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix &&
                            cached.showImdbRatings == input.showImdbRatings &&
                            cached.localeTag == currentLocaleTag &&
                            cached.gatedItemKeys == input.gatedItemKeys

                    val mappedRow = if (canReuseMappedRow) {
                        val cachedMappedRow = checkNotNull(cached).mappedRow
                        if (cachedMappedRow.globalRowIndex == index) {
                            cachedMappedRow
                        } else {
                            cachedMappedRow.copy(globalRowIndex = index)
                        }
                    } else {
                        val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                        val rowItemCache = cache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                        HeroCarouselRow(
                            key = rowKey,
                            title = catalogRowTitle(
                                row = row,
                                // Home rows are now only featured/curated catalogs;
                                // their names already describe the content, so the
                                // per-service " - Movie/Series" suffix no longer applies.
                                showCatalogTypeSuffix = false,
                                strTypeMovie = strTypeMovie,
                                strTypeSeries = strTypeSeries
                            ),
                            globalRowIndex = index,
                            catalogId = row.catalogId,
                            addonId = row.addonId,
                            apiType = row.apiType,
                            supportsSkip = row.supportsSkip,
                            hasMore = row.hasMore,
                            isLoading = row.isLoading,
                            items = run {
                                // A Home hub-group row merges two sibling hub- catalogs and
                                // carries BOTH their "More Like This ▸" tiles, so its drill opens
                                // both kinds' sub-rows (primary + secondary). Ordinary rows carry
                                // exactly one tile, so this collapses to today's behavior.
                                val drillTiles = row.items.filter { it.isDrillTile() }
                                val contentItems = row.items
                                    .filterNot { it.ratingGateKey in input.gatedItemKeys }
                                    .filterNot { it.isDrillTile() }
                                val mapped = contentItems.mapIndexed { itemIndex, item ->
                                    val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                                    rowItemOccurrenceCounts[item.id] = occurrence + 1
                                    val cacheKey = "${item.id}_$occurrence"
                                    val cachedItem = rowItemCache[cacheKey]
                                    if (cachedItem != null &&
                                        cachedItem.source == item &&
                                        cachedItem.useLandscapePosters == input.useLandscapePosters &&
                                        cachedItem.showFullReleaseDate == input.showFullReleaseDate &&
                                        cachedItem.showImdbRatings == input.showImdbRatings
                                    ) {
                                        cachedItem.carouselItem.let { cached ->
                                            val stableItemKey = "${rowKey}_$itemIndex"
                                            if (cached.key == stableItemKey) cached
                                            else cached.copy(key = stableItemKey)
                                        }
                                    } else {
                                        val built = buildCatalogItem(
                                            item = item,
                                            row = row,
                                            useLandscapePosters = input.useLandscapePosters,
                                            occurrence = occurrence,
                                            strTypeMovie = strTypeMovie,
                                            strTypeSeries = strTypeSeries,
                                            showFullReleaseDate = input.showFullReleaseDate,
                                            showImdbRatings = input.showImdbRatings,
                                            previousCachedItem = cachedItem?.carouselItem
                                        ).copy(key = "${rowKey}_$itemIndex")
                                        rowItemCache[cacheKey] = CachedCarouselItem(
                                            source = item,
                                            useLandscapePosters = input.useLandscapePosters,
                                            showFullReleaseDate = input.showFullReleaseDate,
                                            showImdbRatings = input.showImdbRatings,
                                            carouselItem = built
                                        )
                                        built
                                    }
                                }.toMutableList()
                                if (drillTiles.isNotEmpty()) {
                                    mapped.add(
                                        ModernCarouselItem(
                                            key = "${rowKey}_drill",
                                            title = "More Like This ▸",
                                            subtitle = null,
                                            imageUrl = null,
                                            heroPreview = HeroPreview(
                                                title = "More Like This ▸",
                                                logo = null, description = null,
                                                contentTypeText = null, isSeries = false,
                                                yearText = null, runtimeText = null,
                                                imdbText = null, ageRatingText = null,
                                                statusText = null, countryText = null,
                                                languageText = null,
                                                genres = emptyList<String>().asStable(),
                                                poster = null, backdrop = null, imageUrl = null
                                            ),
                                            payload = ModernPayload.Drill(buildHomeDrillTarget(row, drillTiles)),
                                            metaPreview = drillTiles.first()
                                        )
                                    )
                                }
                                mapped.asStable()
                            }
                        )
                    }

                    cache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                        source = row,
                        useLandscapePosters = input.useLandscapePosters,
                        showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                        showImdbRatings = input.showImdbRatings,
                        localeTag = currentLocaleTag,
                        gatedItemKeys = input.gatedItemKeys,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.CollectionRow -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    val collection = homeRow.collection
                    val rowKey = collectionRowKey(collection)
                    activeCollectionKeys += rowKey
                    val cached = cache.collectionRows[rowKey]
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == collection

                    val mappedRow = if (canReuseMappedRow) {
                        val cachedMappedRow = checkNotNull(cached).mappedRow
                        if (cachedMappedRow.globalRowIndex == index) {
                            cachedMappedRow
                        } else {
                            cachedMappedRow.copy(globalRowIndex = index)
                        }
                    } else {
                        val occurrenceCounts = mutableMapOf<String, Int>()
                        HeroCarouselRow(
                            key = rowKey,
                            title = collection.title,
                            globalRowIndex = index,
                            items = collection.folders.map { folder ->
                                val occurrence = occurrenceCounts.getOrDefault(folder.id, 0)
                                occurrenceCounts[folder.id] = occurrence + 1
                                buildCollectionFolderItem(
                                    collection = collection,
                                    folder = folder,
                                    occurrence = occurrence
                                )
                            }.asStable()
                        )
                    }

                    cache.collectionRows[rowKey] = ModernCollectionRowBuildCacheEntry(
                        source = collection,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.PlaceholderCatalog -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val stableRowKey = homeRow.stableCatalogKey
                    val fakeItemCount = 8
                    val fakeItems = (0 until fakeItemCount).map { i ->
                        val fakeId = "__placeholder_${homeRow.catalogKey}_$i"
                        ModernCarouselItem(
                            key = "${stableRowKey}_$i",
                            title = "",
                            subtitle = null,
                            // Dummy URL triggers shimmer instead of MonochromePosterPlaceholder
                            imageUrl = PLACEHOLDER_IMAGE_URL,
                            heroPreview = HeroPreview(
                                title = "", logo = null, description = null,
                                contentTypeText = null, yearText = null, imdbText = null,
                                genres = com.nuvio.tv.ui.util.StableList(emptyList()), poster = null, backdrop = null,
                                imageUrl = PLACEHOLDER_IMAGE_URL
                            ),
                            payload = ModernPayload.Catalog(
                                focusKey = "placeholder_${homeRow.catalogKey}_$i",
                                itemId = fakeId,
                                itemType = homeRow.apiType,
                                addonBaseUrl = homeRow.addonBaseUrl,
                                trailerTitle = "",
                                trailerReleaseInfo = null,
                                trailerApiType = homeRow.apiType
                            )
                        )
                    }.asStable()
                    val placeholderTitle = homeRow.catalogName.replaceFirstChar { it.uppercase() }
                    val placeholderRow = HeroCarouselRow(
                        key = stableRowKey,
                        title = placeholderTitle,
                        globalRowIndex = index,
                        catalogId = homeRow.catalogId,
                        addonId = homeRow.addonId,
                        apiType = homeRow.apiType,
                        items = fakeItems,
                        isLoading = true
                    )
                    add(placeholderRow)
                }
            }
        }

        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
        cache.collectionRows.keys.retainAll(activeCollectionKeys)
    }

    val lookups = buildCarouselRowLookups(rows)
    return ModernHomePresentationState(
        rows = rows.asStable(),
        lookups = lookups
    )
}

private fun resolveVisibleHomeRows(input: ModernHomePresentationInput): List<HomeRow> {
    val resolved: List<HomeRow>
    if (input.homeRows.isNotEmpty()) {
        val latestCatalogByKey = input.catalogRows.associateBy(::catalogRowKey)
        resolved = input.homeRows.mapNotNull { homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val latest = latestCatalogByKey[catalogRowKey(homeRow.row)] ?: homeRow.row
                    latest.takeIf { it.items.isNotEmpty() }?.let(HomeRow::Catalog)
                }
                is HomeRow.CollectionRow -> {
                    homeRow.collection.takeIf(Collection::hasVisibleFolders)?.let(HomeRow::CollectionRow)
                }
                is HomeRow.PlaceholderCatalog -> {
                    // Keep placeholder rows as-is — they'll be rendered as shimmer skeletons
                    homeRow
                }
            }
        }
    } else {
        resolved = input.catalogRows
            .filter { it.items.isNotEmpty() }
            .map(HomeRow::Catalog)
    }
    // Home is the only place a profile's hub- movie + series siblings sit side by side
    // (the Movies/TV hubs keep them as separate per-kind sections). Fold each same-named
    // pair into ONE door row so Home doesn't stack two vague "Genre" / "Years" walls; the
    // door's drill opens both kinds' sub-rows. Solo rows (Studios, Documentaries…) and
    // distinct-named rows (Anime Series / Anime Movies) pass through untouched.
    return collapseHubGroupHomeRows(resolved)
}

/** Category base shared by a hub- movie/series sibling pair: `hub-genremovie` +
 *  `hub-genreseries` both key to `genre`. Returns null for anything that isn't a
 *  kind-suffixed `hub-` catalog (rec- shelves, per-service catalogs, anime doors). */
private fun hubGroupBaseOf(catalogId: String): String? {
    if (!catalogId.startsWith("hub-")) return null
    val id = catalogId.removePrefix("hub-")
    if (id.isEmpty()) return null
    val base = when {
        id.endsWith("series") -> id.removeSuffix("series")
        id.endsWith("movie") -> id.removeSuffix("movie")
        id.endsWith("tv") -> id.removeSuffix("tv")
        else -> return null
    }
    return base.takeIf { it.isNotEmpty() && !it.endsWith('-') }
}

/**
 * Replaces each same-named hub- sibling group (≥2 present, non-empty rows) with a single
 * merged door row at the first sibling's position: one label, an evenly-mixed sample of the
 * kinds, and both siblings' "More Like This ▸" tiles appended so the drill covers both drill
 * catalogs. Members merged into an earlier door are dropped. Collections/placeholders and
 * non-group catalog rows are untouched.
 */
private fun collapseHubGroupHomeRows(rows: List<HomeRow>): List<HomeRow> {
    val catalogs = rows.filterIsInstance<HomeRow.Catalog>()
    if (catalogs.size < 2) return rows

    val byBase = LinkedHashMap<String, MutableList<CatalogRow>>()
    catalogs.forEach { homeRow ->
        val row = homeRow.row
        val base = hubGroupBaseOf(row.catalogId) ?: return@forEach
        val list = byBase.getOrPut(base) { mutableListOf() }
        val first = list.firstOrNull()
        if (first == null || first.catalogName.equals(row.catalogName, ignoreCase = true)) list += row
    }

    val merges = byBase.filterValues { it.size >= 2 }.values
    if (merges.isEmpty()) return rows

    val mergedByLeaderKey = HashMap<String, CatalogRow>()
    val dropKeys = HashSet<String>()
    merges.forEach { members ->
        val merged = mergeHubGroupRows(members)
        mergedByLeaderKey[catalogRowKey(members.first())] = merged
        members.drop(1).forEach { dropKeys += catalogRowKey(it) }
    }

    return rows.mapNotNull { homeRow ->
        if (homeRow is HomeRow.Catalog) {
            val key = catalogRowKey(homeRow.row)
            if (key in dropKeys) null
            else {
                val merged = mergedByLeaderKey[key]
                if (merged != null) HomeRow.Catalog(merged) else homeRow
            }
        } else {
            homeRow
        }
    }
}

/** Folds a hub- sibling group into one row: label + addon identity from the leader (the
 *  first sibling in feed order), an even round-robin content mix capped at [HUB_GROUP_CAP]
 *  posters (drill into the drill catalog for the full depth), and the members' drill tiles
 *  appended in member order so the door's drill opens every kind's sub-rows. */
private fun mergeHubGroupRows(members: List<CatalogRow>): CatalogRow {
    val leader = members.first()
    val contentByMember = members.map { member -> member.items.filterNot { it.isDrillTile() } }
    val cap = HUB_GROUP_CAP
    val picked = mutableListOf<MetaPreview>()
    val seen = HashSet<String>()
    val ptr = IntArray(members.size)
    var guard = 0
    val totalContent = contentByMember.sumOf { it.size }
    while (picked.size < cap && guard < totalContent + members.size) {
        guard++
        var advanced = false
        for (m in members.indices) {
            if (picked.size >= cap) break
            val content = contentByMember[m]
            while (ptr[m] < content.size) {
                val item = content[ptr[m]++]
                val key = "${item.apiType}:${item.id}"
                if (seen.add(key)) {
                    picked += item
                    advanced = true
                    break
                }
            }
        }
        if (!advanced) break
    }
    val drillTiles = members.flatMap { member -> member.items.filter { it.isDrillTile() } }
    return leader.copy(
        catalogId = "hubgrp-${hubGroupBaseOf(leader.catalogId) ?: leader.catalogId}",
        items = picked + drillTiles,
        isLoading = false,
        hasMore = false
    )
}

private const val HUB_GROUP_CAP = 60

private fun collectionRowKey(collection: Collection): String {
    return "collection_${collection.id}"
}

/** Builds a row's drill target from its trailing "More Like This ▸" tile(s). A hub-group
 *  merged row carries two tiles (the movie + series siblings it folds together), so its
 *  drill opens both drill catalogs ([DrillTarget.secondary]); an ordinary row carries one
 *  tile, giving the same single-source target as before. Each tile's origin addon comes from
 *  the tile's stamped [MetaPreview.sourceAddonBaseUrl] (catalog mapper), falling back to the
 *  row's addon for legacy rows. */
private fun buildHomeDrillTarget(row: CatalogRow, tiles: List<MetaPreview>): DrillTarget {
    fun drillFor(tile: MetaPreview): DrillSource {
        val tileRowId = tile.id.removePrefix(REC_DRILL_PREFIX)
        return DrillSource(
            rowId = tileRowId,
            drillCatalogId = "rec-$tileRowId$REC_DRILL_SUFFIX",
            addonId = row.addonId,
            addonBaseUrl = tile.sourceAddonBaseUrl ?: row.addonBaseUrl,
            type = tile.apiType.ifBlank { row.apiType }
        )
    }
    val primary = drillFor(tiles.first())
    val secondary = tiles.getOrNull(1)?.let { drillFor(it) }
    return DrillTarget(
        rowId = primary.rowId,
        drillCatalogId = primary.drillCatalogId,
        addonId = primary.addonId,
        addonBaseUrl = primary.addonBaseUrl,
        type = primary.type,
        title = "More Like This",
        secondary = secondary
    )
}

private fun getLocalizedContext(context: Context): Context {
    val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
        ?: return context
    val locale = Locale.forLanguageTag(tag)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config)
}

private fun Collection.hasVisibleFolders(): Boolean {
    return folders.isNotEmpty()
}

internal fun buildCarouselRowLookups(carouselRows: List<HeroCarouselRow>): CarouselRowLookups {
    val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
    val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
    val rowKeyByGlobalRowIndex = LinkedHashMap<Int, String>(carouselRows.size)
    val firstHeroPreviewByRow = LinkedHashMap<String, HeroPreview>(carouselRows.size)
    val fallbackBackdropByRow = LinkedHashMap<String, String>(carouselRows.size)
    val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
    val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
    val activeCatalogItemIds = LinkedHashSet<String>()

    carouselRows.forEachIndexed { index, row ->
        rowIndexByKey[row.key] = index
        rowByKey[row.key] = row
        if (row.globalRowIndex >= 0) {
            rowKeyByGlobalRowIndex[row.globalRowIndex] = row.key
        }
        row.items.list.firstOrNull()?.heroPreview?.let { firstHeroPreviewByRow[row.key] = it }
        row.items.list.firstNotNullOfOrNull { item ->
            item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
        }?.let { fallbackBackdropByRow[row.key] = it }
        activeRowKeys += row.key

        val itemKeys = LinkedHashSet<String>(row.items.list.size)
        row.items.list.forEach { item ->
            itemKeys.add(item.key)
            val payload = item.payload
            if (payload is ModernPayload.Catalog) {
                activeCatalogItemIds += payload.itemId
            }
        }
        activeItemKeysByRow[row.key] = itemKeys
    }

    return CarouselRowLookups(
        rowIndexByKey = rowIndexByKey.asStable(),
        rowByKey = rowByKey.asStable(),
        rowKeyByGlobalRowIndex = rowKeyByGlobalRowIndex.asStable(),
        firstHeroPreviewByRow = firstHeroPreviewByRow.asStable(),
        fallbackBackdropByRow = fallbackBackdropByRow.asStable(),
        activeRowKeys = activeRowKeys.asStable(),
        activeItemKeysByRow = activeItemKeysByRow.asStable(),
        activeCatalogItemIds = activeCatalogItemIds.asStable()
    )
}
