package com.nuvio.tv.domain.model

/**
 * One thing the user said they like in the on-TV taste picker. The wire format
 * mirrors the backend `taste_picks` table (nuvio-server migration
 * 00000000000011): the boomio curated-row engine reads these back and expands
 * them into availability-gated home shelves ("More like X", "{person} movies /
 * shows", "{genre} movies / shows").
 *
 * Note on genre picks: `tmdbId` carries the MOVIE-taxonomy TMDB genre id (the
 * engine maps it to the TV taxonomy itself when it builds the series wall), so a
 * genre pick looks like `TastePick(GENRE, 878, "Science Fiction")`.
 */
enum class TastePickType(val wire: String) {
    MOVIE("movie"),
    SERIES("series"),
    PERSON("person"),
    GENRE("genre");

    companion object {
        fun fromWire(value: String): TastePickType? = values().firstOrNull { it.wire == value }
    }
}

data class TastePick(
    val pickType: TastePickType,
    val tmdbId: Long,
    val name: String,
    val posterPath: String? = null
)
