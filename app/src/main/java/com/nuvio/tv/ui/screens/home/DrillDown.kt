package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.first

/** Catalog ids the rec addon uses for drill-down: the trailing tile + the drill catalog. */
const val REC_DRILL_PREFIX = "rec-drill:"
const val REC_DRILL_SUFFIX = "-drilldown"

/** One drill-down source: a `rec-<rowId>-drilldown` catalog on one row addon. */
data class DrillSource(
    val rowId: String,
    val drillCatalogId: String,
    val addonId: String,
    val addonBaseUrl: String,
    val type: String
)

/** Where a row's "More Like This ▸" drill tile leads: its sub-rows browser.
 *  [secondary] lets a Home group row (a merged hub- movie+series pair) drill into
 *  both kinds' sub-row catalogs — the primary is the movie side, secondary the
 *  series side (or vice versa). Non-group rows carry only the primary. */
data class DrillTarget(
    val rowId: String,
    val drillCatalogId: String,
    val addonId: String,
    val addonBaseUrl: String,
    val type: String,
    val title: String,
    val secondary: DrillSource? = null
)

/** True for the addon-appended "More Like This ▸" meta (id rec-drill:<rowId>). */
fun MetaPreview.isDrillTile(): Boolean = id.startsWith(REC_DRILL_PREFIX)

/** A catalog row with its trailing drill tile(s) stripped, plus the drill target. */
data class DrillExtraction(val row: CatalogRow, val target: DrillTarget?)

/**
 * Largest single row [fetchCatalogAll] will drain eagerly. Enough to fully render
 * the deepest curated shelves (a decade/studio/genre sub-row is a few hundred
 * titles at most) while bounding the pathological case where a "row" is really a
 * whole catalog — a flat hub over the full approved universe (e.g. hub-leomovies)
 * or a giant generic-addon fallback. Without the bound those rows chain
 * sequential ~25-title pages for minutes on a weak device before the row appears
 * ("Movies never loads"); with it the shelf still renders in full and runaway
 * catalogs stop at a full-screen's worth instead of stalling first paint.
 */
private const val FETCH_ALL_MAX_ITEMS = 240

/**
 * Fetch a catalog to completion across skip pages, up to [maxItems]. The row
 * addon pages at 25 titles while a deep shelf (a decade row) is intentionally
 * richer than one page — without this the browser silently truncated rows at the
 * first page and a 40-title 1980s row showed only 25 posters. Returns null when
 * the first page is empty or unreachable; otherwise concatenates the remaining
 * pages (deduped) onto the first [CatalogRow], carrying hasMore/nextSkip forward
 * and stopping early once [maxItems] is reached.
 */
suspend fun CatalogRepository.fetchCatalogAll(
    addonId: String,
    addonName: String,
    addonBaseUrl: String,
    catalogId: String,
    catalogName: String,
    type: String,
    skipStep: Int = 100,
    maxItems: Int = FETCH_ALL_MAX_ITEMS
): CatalogRow? {
    suspend fun page(skip: Int): CatalogRow? =
        (getCatalog(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            skip = skip,
            skipStep = skipStep,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data

    val first = page(0) ?: return null
    if (first.items.isEmpty()) return first
    var all = first
    val seen = all.items.map { it.id }.toMutableSet()
    // Advance by real-content count, not raw metas: the addon appends a drill
    // tile to page 0, which must not shift the skip offset (or content between
    // the last poster and the tile would be skipped on the next page).
    var offset = first.items.count { !it.isDrillTile() }
    while (offset > 0 && all.items.size < maxItems) {
        val next = page(offset) ?: break
        val content = next.items.filterNot { it.isDrillTile() }
        if (content.isEmpty()) {
            all = all.copy(hasMore = false)
            break
        }
        // Fill only up to the cap: a giant catalog must not delay first paint by
        // chaining the whole tail. Enough of this page to top up to maxItems.
        val room = maxItems - all.items.size
        val fresh = content.filter { seen.add(it.id) }
        all = all.copy(
            items = all.items + fresh.take(room),
            nextSkip = offset + content.size,
            hasMore = next.hasMore
        )
        offset += content.size
        if (all.items.size >= maxItems) break
    }
    return all
}

fun CatalogRow.extractDrill(): DrillExtraction {
    val tiles = items.filter { it.isDrillTile() }
    if (tiles.isEmpty()) return DrillExtraction(this, null)
    val t = tiles.first()
    val rowId = t.id.removePrefix(REC_DRILL_PREFIX)
    return DrillExtraction(
        row = copy(items = items.filterNot { it.isDrillTile() }),
        target = DrillTarget(
            rowId = rowId,
            drillCatalogId = "rec-$rowId$REC_DRILL_SUFFIX",
            addonId = addonId,
            addonBaseUrl = addonBaseUrl,
            type = apiType,
            title = "More Like This"
        )
    )
}
