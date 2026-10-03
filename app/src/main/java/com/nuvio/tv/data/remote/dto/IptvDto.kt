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
    @Json(name = "stopMs") val stopMs: Long? = null
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
