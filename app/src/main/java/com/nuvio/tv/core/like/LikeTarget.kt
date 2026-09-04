package com.nuvio.tv.core.like

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TastePick
import com.nuvio.tv.domain.model.TastePickType

/**
 * Resolves the canonical like identity for a poster tile: pick_type in
 * (movie, series) + tmdb id + display name. This is the ONLY shape the backend
 * `taste_picks` table accepts, so a tile that cannot be mapped to a tmdb id
 * (e.g. an IMDb-only item with no TMDB counterpart) simply offers no Like.
 *
 * Resolution order mirrors stream resolution:
 *   1. tmdb id embedded in the content id (tmdb:123 / 123) via [parseContentIds];
 *   2. otherwise an IMDb id ("tt…") is reverse-mapped through
 *      [TmdbService.imdbToTmdb] using the same media-type normalisation the
 *      shared poster-options controller uses for library ids.
 */
suspend fun resolveLikeTarget(item: MetaPreview, tmdbService: TmdbService): TastePick? {
    val series = item.apiType.equals("series", ignoreCase = true) ||
        item.apiType.equals("tv", ignoreCase = true) ||
        item.apiType.equals("anime", ignoreCase = true)
    val movie = item.apiType.equals("movie", ignoreCase = true)
    if (!movie && !series) return null

    val pickType = if (series) TastePickType.SERIES else TastePickType.MOVIE
    val mediaType = if (series) "tv" else "movie"

    val parsed = parseContentIds(item.id)
    val tmdbId = parsed.tmdb?.toLong()
        ?: item.id.toLongOrNull()
        ?: reverseImdb(item, mediaType, tmdbService)
        ?: return null

    return TastePick(pickType = pickType, tmdbId = tmdbId, name = item.name)
}

private suspend fun reverseImdb(item: MetaPreview, mediaType: String, tmdbService: TmdbService): Long? {
    val candidates = buildList {
        item.imdbId?.takeIf { it.startsWith("tt") }?.let(::add)
        if (item.id.startsWith("tt")) add(item.id)
    }.distinct()
    for (candidate in candidates) {
        val tmdbId = tmdbService.imdbToTmdb(candidate, mediaType) ?: continue
        return tmdbId.toLong()
    }
    return null
}
