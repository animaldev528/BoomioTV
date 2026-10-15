package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import kotlinx.coroutines.flow.first

/**
 * One Home ribbon out of a single catalog: the addon's `rec-_home` parent.
 *
 * The parent's content is not titles but one tile per top-level row of the profile's board,
 * and a tile's meta id IS that row's catalog id -- the same idiom the "More Like This ▸"
 * drill catalogs use, one level up. Expanding it here makes the whole ribbon one manifest
 * entry whose row lineup, row order and row names are all server *content*: a daily change
 * then costs no new catalog ids, and so needs no cold launch to reach the screen.
 *
 * The addon only publishes the parent while its own REC_HOME_HUB flag is on, so a build with
 * this support against an addon that does not offer it behaves exactly as before.
 */
const val REC_HOME_HUB_CATALOG_ID = "rec-_home"

/** A row of the parent catalog: [id] is that row's own catalog id. */
data class HomeHubTile(val id: String, val name: String, val type: String)

/** Key into [HomeViewModel.homeHubTiles] for one addon's parent catalog. */
internal fun homeHubTileKey(addonId: String, catalogId: String): String = "${addonId}_$catalogId"

/**
 * The rows the parent catalog [catalog] stands for, or null when it is not a parent -- or is
 * one whose tiles are not known -- in which case it is an ordinary row like any other.
 *
 * A tile id names a catalog the addon already publishes (one per row), so the manifest's own
 * descriptor for it is used verbatim: that carries the row's real type, extras and page size.
 * A tile whose id is not in the manifest (the board moved between the manifest fetch and the
 * tile fetch) is synthesised from the parent so the row still loads.
 */
internal fun HomeViewModel.homeHubRows(
    addon: Addon,
    catalog: CatalogDescriptor
): List<Pair<Addon, CatalogDescriptor>>? {
    if (catalog.id != REC_HOME_HUB_CATALOG_ID) return null
    val tiles = homeHubTiles[homeHubTileKey(addon.id, catalog.id)] ?: return null
    val published = addon.catalogs.associateBy { it.id }
    return tiles.map { tile ->
        addon to (published[tile.id] ?: catalog.copy(
            type = ContentType.fromString(tile.type),
            rawType = tile.type,
            id = tile.id,
            name = tile.name
        ))
    }
}

/**
 * Fetch every installed parent catalog and remember its tiles, so the order and load steps
 * that follow can treat one catalog as the N rows it names rather than as one row of tiles.
 *
 * A parent that cannot be fetched, or that names no rows, is left out of the map: it then
 * contributes one ordinary row (its tiles), which is how a build without this support shows
 * it -- an empty Home would be the worse failure.
 */
internal suspend fun HomeViewModel.loadHomeHubTiles(addons: List<Addon>) {
    val parents = addons.flatMap { addon ->
        addon.catalogs.filter { it.id == REC_HOME_HUB_CATALOG_ID }.map { addon to it }
    }
    val tilesByParent = mutableMapOf<String, List<HomeHubTile>>()
    parents.forEach { (addon, catalog) ->
        val result = runCatching {
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = catalog.skipStep(),
                supportsSkip = catalog.supportsExtra("skip")
            ).first { it !is NetworkResult.Loading }
        }.getOrNull()
        val items = (result as? NetworkResult.Success)?.data?.items.orEmpty()
        if (items.isEmpty()) return@forEach
        tilesByParent[homeHubTileKey(addon.id, catalog.id)] = items.map { meta ->
            HomeHubTile(id = meta.id, name = meta.name, type = meta.apiType)
        }
    }
    homeHubTiles = tilesByParent
}
