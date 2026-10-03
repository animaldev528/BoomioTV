package com.nuvio.tv.domain.model

/**
 * A live channel from the boomio IPTV edge (`GET /iptv/channels`).
 *
 * Mirrors the edge's response exactly. [name] is the normalized display name
 * (the panel emits HTML entities and Unicode pseudo-small-caps that would
 * otherwise render as garbage); [rawName] keeps the untouched panel spelling.
 */
data class IptvChannel(
    val streamId: String,
    val name: String,
    val rawName: String? = null,
    val categoryId: String? = null,
    val icon: String? = null,
    val epgChannelId: String? = null,
    val hasEpg: Boolean = false,
)

/** One programme in a channel's guide. Times are epoch millis, already resolved. */
data class IptvProgramme(
    val title: String,
    val description: String? = null,
    val startMs: Long,
    val endMs: Long,
) {
    /** 0f..1f through the programme, clamped — safe to call with a stale clock. */
    fun progress(nowMs: Long): Float {
        val span = (endMs - startMs).toFloat()
        if (span <= 0f) return 0f
        return ((nowMs - startMs).toFloat() / span).coerceIn(0f, 1f)
    }
}

/**
 * What is on a channel now and next.
 *
 * The panel's guide is SHORT — measured at roughly 27 hours ahead — so [next]
 * being null is a normal state, not an error: the client must render "no
 * information" rather than imply the channel stops.
 */
data class IptvNowNext(
    val now: IptvProgramme? = null,
    val next: IptvProgramme? = null,
)
