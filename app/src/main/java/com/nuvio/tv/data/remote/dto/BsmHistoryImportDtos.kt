package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One watch-history row, in the exact shape `bsm/lib/history-rows.js` sanitises.
 *
 * [tmdbId] is the pool's identity and is NOT optional: a row without a positive one is dropped
 * server-side before it can reach `taste_picks`, so the client must not send what it cannot key.
 * [tsMs] is an ordering key — the server sorts oldest-first and gives the NEWEST row the highest
 * taste position, so an unknown time belongs at 0 (oldest), never at "now".
 */
@JsonClass(generateAdapter = true)
data class BsmHistoryRowDto(
    val kind: String,
    @Json(name = "tmdb_id") val tmdbId: Int,
    val title: String,
    val year: Int? = null,
    @Json(name = "ts_ms") val tsMs: Long = 0L,
    val rating: Double? = null
)

/**
 * Body of `POST /bsm/tvpair/history-import`.
 *
 * [profileIndex] is which of the CALLER'S OWN profiles to seed — the account itself comes from
 * the bearer token and a body-supplied user id cannot redirect the write. [provider] is
 * provenance for the server's log; the core never reads from Trakt itself.
 *
 * `mode` and `min_rating` are deliberately left out: `importer.selectLikes` picks ratings-vs-
 * watched adaptively from the rated/watched coverage ratio, and forcing either from here would
 * only hard-code a guess the server is better placed to make.
 */
@JsonClass(generateAdapter = true)
data class BsmHistoryImportRequestDto(
    @Json(name = "profile_index") val profileIndex: Int,
    val provider: String,
    val watched: List<BsmHistoryRowDto>,
    val rated: List<BsmHistoryRowDto> = emptyList()
)

/**
 * The server's report. Only the fields the client acts on are declared — Moshi ignores the rest.
 *
 * [tasteCompleted] is the one with a side effect: the server flips it inside the same call that
 * inserts rows, so mirroring it locally is what lets the profile stop advertising the first-run
 * taste hint without waiting for a profile pull.
 */
@JsonClass(generateAdapter = true)
data class BsmHistoryImportResponseDto(
    val status: String? = null,
    val mode: String? = null,
    val liked: Int = 0,
    val inserted: Int = 0,
    val skippedNoId: Int = 0,
    val dropped: Int = 0,
    val profileIndex: Int = 0,
    val tasteCompleted: Boolean = false
)
