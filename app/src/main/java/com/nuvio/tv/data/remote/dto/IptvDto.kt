package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Wire shapes for the boomio IPTV edge (bss-iptv). Field names mirror the
 * server's JSON exactly; the domain mappers live in IptvClient.
 */

@JsonClass(generateAdapter = true)
data class IptvDeviceCodeDto(
    @Json(name = "device_code") val deviceCode: String? = null,
    @Json(name = "user_code") val userCode: String? = null,
    @Json(name = "verification_uri") val verificationUri: String? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "interval") val interval: Int? = null
)

@JsonClass(generateAdapter = true)
data class IptvPollDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "token") val token: String? = null,
    @Json(name = "session_token") val sessionToken: String? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "display_name") val displayName: String? = null
)

@JsonClass(generateAdapter = true)
data class IptvChannelsDto(
    @Json(name = "count") val count: Int? = null,
    @Json(name = "limited") val limited: Boolean? = null,
    // An empty group selection is a deliberate state ("nothing followed yet"),
    // not a failure — the UI must say so rather than render a bare empty list.
    @Json(name = "unscoped") val unscoped: Boolean? = null,
    @Json(name = "selectionStale") val selectionStale: Boolean? = null,
    @Json(name = "followedGroups") val followedGroups: Int? = null,
    @Json(name = "channels") val channels: List<IptvChannelDto>? = null
)

@JsonClass(generateAdapter = true)
data class IptvChannelDto(
    @Json(name = "streamId") val streamId: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "rawName") val rawName: String? = null,
    @Json(name = "categoryId") val categoryId: String? = null,
    @Json(name = "icon") val icon: String? = null,
    @Json(name = "epgChannelId") val epgChannelId: String? = null,
    @Json(name = "hasEpg") val hasEpg: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class IptvProgrammeDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "startMs") val startMs: Long? = null,
    @Json(name = "stopMs") val stopMs: Long? = null,
    // Absent, never null, when the programme is unresolved — so a guide from a
    // store that has never been enriched deserialises exactly as it did before
    // this field existed. See IptvProgrammeMatchDto.
    @Json(name = "match") val match: IptvProgrammeMatchDto? = null
)

/**
 * Who a programme actually is, resolved in bulk at ingest (the M1–M5 pipeline in
 * bss-iptv) rather than on the client.
 *
 * [season]/[episode] are present ONLY when the description was episode-matched,
 * which is why they are not simply non-null whenever [match] is: a programme
 * matched at show level knows the series but not the episode, and the UI must
 * tell those apart — one can be played directly, the other cannot.
 *
 * [mediaType] arrives already translated to the clients' vocabulary (TMDB's `tv`
 * becomes `series`), matching what the companion layer emits, so it can be fed
 * straight to a Detail route without a second mapping table here.
 */
@JsonClass(generateAdapter = true)
data class IptvProgrammeMatchDto(
    @Json(name = "imdbId") val imdbId: String? = null,
    @Json(name = "tmdbId") val tmdbId: Int? = null,
    @Json(name = "mediaType") val mediaType: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "canonical") val canonical: String? = null,
    @Json(name = "confidence") val confidence: Double? = null,
    // The TITLE's tier ("tmdb" | "gemini"), not the episode's.
    @Json(name = "source") val source: String? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "episodeName") val episodeName: String? = null,
    // Ditto for the EPISODE: "overview_match" (free) vs "gemini_episode" (paid).
    @Json(name = "episodeSource") val episodeSource: String? = null
)

/**
 * One call to /iptv/guide returns programmes for MANY channels, which is what a
 * channel list needs — asking per channel would be one request per row.
 */
@JsonClass(generateAdapter = true)
data class IptvGuideDto(
    @Json(name = "from") val from: String? = null,
    @Json(name = "to") val to: String? = null,
    @Json(name = "count") val count: Int? = null,
    @Json(name = "horizon") val horizon: IptvHorizonDto? = null,
    @Json(name = "channels") val channels: List<IptvGuideChannelDto>? = null
)

@JsonClass(generateAdapter = true)
data class IptvGuideChannelDto(
    @Json(name = "streamId") val streamId: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "epgChannelId") val epgChannelId: String? = null,
    @Json(name = "count") val count: Int? = null,
    @Json(name = "programmes") val programmes: List<IptvProgrammeDto>? = null
)

/**
 * How far the guide actually reaches. The panel's schedule is short (~27h
 * ahead, measured), so a client sizing a week-long grid would render days of
 * empty columns that look like a bug.
 */
@JsonClass(generateAdapter = true)
data class IptvHorizonDto(
    @Json(name = "from") val from: String? = null,
    @Json(name = "to") val to: String? = null,
    @Json(name = "aheadHours") val aheadHours: Double? = null
)

/**
 * Result of POST /iptv/tune/:streamId. [playlist] is a RELATIVE path already
 * carrying ?session_token=…, so the client resolves it against the edge base
 * URL before handing it to the player.
 */
@JsonClass(generateAdapter = true)
data class IptvTuneDto(
    @Json(name = "streamId") val streamId: String? = null,
    @Json(name = "playlist") val playlist: String? = null,
    @Json(name = "contentType") val contentType: String? = null
    // There is deliberately no `status` here. The edge's tune response carries a
    // `session` OBJECT (mediaSequence, buffered segments, uptime, lastError), and
    // declaring it as `String?` made Moshi throw "Expected a string but was
    // BEGIN_OBJECT at path $.status" — which failed every channel tune with the
    // tune having actually succeeded. Nothing read it; it was copied from
    // IptvPollDto, where `status` really is a string.
)

/**
 * Result of POST /iptv/resolve-episode — the on-demand "which episode is this"
 * call, made once when the viewer actually presses play.
 *
 * THE SHAPE IS FLAT ON PURPOSE, matching the server: [episode] null is the
 * documented "just open the show" outcome, and it is the SAME response whether
 * the model abstained, its pick failed verification, or the call timed out.
 * There is deliberately no confidence grade and no `verified` flag to branch on
 * — an unverified pick is a miss, not a hedge, so the caller plays the
 * programme either way and never shows the viewer a guess.
 *
 * [show] is present even when [episode] is null, because opening the show needs
 * that identity. [cached] true means the store already knew and nothing was
 * spent.
 */
@JsonClass(generateAdapter = true)
data class IptvResolveEpisodeDto(
    @Json(name = "streamId") val streamId: String? = null,
    @Json(name = "programme") val programme: IptvResolvedProgrammeDto? = null,
    @Json(name = "show") val show: IptvResolvedShowDto? = null,
    @Json(name = "episode") val episode: IptvResolvedEpisodeDto? = null,
    @Json(name = "outcome") val outcome: String? = null,
    @Json(name = "cached") val cached: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class IptvResolvedProgrammeDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "startUtc") val startUtc: Long? = null,
    @Json(name = "stopUtc") val stopUtc: Long? = null
)

@JsonClass(generateAdapter = true)
data class IptvResolvedShowDto(
    @Json(name = "tmdbId") val tmdbId: Int? = null,
    @Json(name = "imdbId") val imdbId: String? = null,
    @Json(name = "mediaType") val mediaType: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null
)

@JsonClass(generateAdapter = true)
data class IptvResolvedEpisodeDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "confidence") val confidence: Double? = null,
    @Json(name = "source") val source: String? = null
)

/**
 * Result of GET /iptv/search — FTS5 across the guide, the fallback for
 * long-pressing a programme the pipeline could not identify.
 *
 * Programme hits and channel hits are reported SEPARATELY because they answer
 * different questions: "what's on about X" versus "where is channel X". A single
 * merged list would make a channel-name search return nothing (channels carry no
 * programme text), which reads as broken.
 */
@JsonClass(generateAdapter = true)
data class IptvSearchDto(
    @Json(name = "query") val query: String? = null,
    @Json(name = "count") val count: Int? = null,
    @Json(name = "channelCount") val channelCount: Int? = null,
    @Json(name = "results") val results: List<IptvSearchHitDto>? = null,
    @Json(name = "channels") val channels: List<IptvChannelDto>? = null
)

/**
 * A programme hit, flattened: the edge spreads `shape()` at the top level and
 * adds the channel identity alongside it, so this mirrors [IptvProgrammeDto]
 * plus the two extras rather than nesting a programme object.
 */
@JsonClass(generateAdapter = true)
data class IptvSearchHitDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "startMs") val startMs: Long? = null,
    @Json(name = "stopMs") val stopMs: Long? = null,
    @Json(name = "match") val match: IptvProgrammeMatchDto? = null,
    @Json(name = "epgChannelId") val epgChannelId: String? = null,
    // The feeds carrying this programme — the same show airs on several.
    @Json(name = "channels") val channels: List<IptvChannelRefDto>? = null
)

@JsonClass(generateAdapter = true)
data class IptvChannelRefDto(
    @Json(name = "streamId") val streamId: String? = null,
    @Json(name = "name") val name: String? = null
)
