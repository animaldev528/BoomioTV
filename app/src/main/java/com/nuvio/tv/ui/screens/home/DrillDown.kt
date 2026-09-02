package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview

/** Catalog ids the rec addon uses for drill-down: the trailing tile + the drill catalog. */
const val REC_DRILL_PREFIX = "rec-drill:"
const val REC_DRILL_SUFFIX = "-drilldown"

/** Where a row's "More Like This ▸" drill tile leads: its sub-rows browser. */
data class DrillTarget(
    val rowId: String,
    val drillCatalogId: String,
    val addonId: String,
    val addonBaseUrl: String,
    val type: String,
    val title: String
)

/** True for the addon-appended "More Like This ▸" meta (id rec-drill:<rowId>). */
fun MetaPreview.isDrillTile(): Boolean = id.startsWith(REC_DRILL_PREFIX)

/** A catalog row with its trailing drill tile(s) stripped, plus the drill target. */
data class DrillExtraction(val row: CatalogRow, val target: DrillTarget?)

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
