package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Wire shape of `POST /api/music/identify` (bsc).
 *
 * [status] is the server's own word, and every value is a normal outcome the UI
 * has copy for — not an error code:
 *
 * - `ok`           — [match] is set.
 * - `no_match`     — we listened and recognised nothing. The ordinary answer for
 *                    score-and-ambience scenes; a commentary track matching
 *                    nothing is *correct*, not a failure.
 * - `no_stream`    — the stream could not be fetched or has no audio to probe.
 * - `no_context`   — we were not told what is playing.
 * - `rate_limited` — the per-device daily cap. Arrives as HTTP 429.
 */
@JsonClass(generateAdapter = true)
data class MusicIdentifyDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "match") val match: MusicMatchDto? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "cues_in_episode") val cuesInEpisode: Int? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "track") val track: MusicTrackSelectionDto? = null
)

/** One identified song. Mirrors `cues.toPublicCue` on the server. */
@JsonClass(generateAdapter = true)
data class MusicMatchDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "artist") val artist: String? = null,
    @Json(name = "album") val album: String? = null,
    @Json(name = "isrc") val isrc: String? = null,
    @Json(name = "artworkUrl") val artworkUrl: String? = null,
    @Json(name = "previewUrl") val previewUrl: String? = null,
    @Json(name = "provider") val provider: String? = null,
    @Json(name = "providerTrackId") val providerTrackId: String? = null,
    @Json(name = "confidence") val confidence: Double? = null,
    @Json(name = "positionMs") val positionMs: Long? = null,
    @Json(name = "hits") val hits: Int? = null
)

/**
 * Which audio stream the server actually listened to.
 *
 * The audio-track number is the one part of this contract that can be wrong
 * *silently*: the TV sends a 0-based ordinal into its audio list, the server
 * compares it against ffprobe's global stream index, and a mismatch used to
 * degrade to the file's default track with nothing in the response to say so.
 * On a title where the viewer picked commentary, that means identifying — and
 * then indexing for every other user and device — music they are not hearing.
 *
 * The server has sent all three fields since boomio #78 (`lib/music/index.js`
 * `selectAudioStream` via `extract.js`), which also split the mismatch out of
 * the fallbacks: see [MusicTrackSelectionDto.reason] for the closed set.
 */
@JsonClass(generateAdapter = true)
data class MusicTrackSelectionDto(
    /** What the client asked for. */
    @Json(name = "reported") val reported: Int? = null,
    /** The ffprobe stream index the server used. */
    @Json(name = "chosen") val chosen: Int? = null,
    /**
     * The closed set, from `extract.js:selectAudioStream`:
     *
     * - `reported`     — the track we asked for; agreement.
     * - `unresolved`   — we asked for one and it did not index a stream, so the
     *                    server fell back anyway. **The only disagreement.**
     * - `only`         — one audio stream in the file.
     * - `default`      — we reported nothing; the file's default disposition.
     * - `first`        — we reported nothing; no default flagged, took the first.
     */
    @Json(name = "reason") val reason: String? = null
)
