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
    /**
     * Who this programme is, when the ingest pipeline identified it. Null is the
     * ordinary case — roughly a third of programmes stay text — and it is not a
     * failure: an unidentified programme renders as its own title and offers no
     * "go to" action, exactly as the guide did before enrichment existed.
     */
    val match: IptvProgrammeMatch? = null,
) {
    /** 0f..1f through the programme, clamped — safe to call with a stale clock. */
    fun progress(nowMs: Long): Float {
        val span = (endMs - startMs).toFloat()
        if (span <= 0f) return 0f
        return ((nowMs - startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** The label to draw: the canonical name once identified, else the guide text. */
    val displayTitle: String get() = match?.canonical?.takeIf { it.isNotBlank() } ?: title

    /**
     * The episode label, when the description was episode-matched. Deliberately
     * separate from [match] being present: a show-level match knows the series
     * but not which episode, and only the latter can be played directly.
     */
    val episodeLabel: String? get() = match?.let { m ->
        val s = m.season
        val e = m.episode
        if (s == null || e == null) null
        else buildString {
            append('S').append(s.toString().padStart(2, '0'))
            append('E').append(e.toString().padStart(2, '0'))
            m.episodeName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
    }
}

/**
 * Identity for a programme, resolved in bulk at ingest (bss-iptv M1–M5).
 *
 * [season]/[episode] are present only for an episode-matched programme; see
 * [IptvProgramme.episodeLabel]. [mediaType] is already the client vocabulary
 * (`series`/`movie`), so it can be handed straight to a Detail route.
 */
data class IptvProgrammeMatch(
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val year: Int? = null,
    val canonical: String? = null,
    val confidence: Double? = null,
    val source: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    val episodeSource: String? = null,
) {
    /** Identified well enough to open on its own — the "go to" precondition. */
    val isNavigable: Boolean
        get() = !imdbId.isNullOrBlank() && !mediaType.isNullOrBlank()

    /** The episode is known, so play-now needs no round trip at all. */
    val hasEpisode: Boolean
        get() = season != null && episode != null
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

/**
 * What is on a channel now and next, derived from a retained window.
 *
 * The grid keeps the whole window and derives now/next for the preview and the
 * channel list, rather than fetching a collapsed pair: one `/guide` call already
 * carries the afternoon, and asking twice for the same rows is how the two views
 * drift apart.
 */
fun List<IptvProgramme>.nowNextAt(nowMs: Long): IptvNowNext = IptvNowNext(
    now = firstOrNull { nowMs >= it.startMs && nowMs < it.endMs },
    next = firstOrNull { it.startMs > nowMs }
)

/**
 * A retained guide window — the programmes themselves, not a collapsed now/next.
 *
 * [byChannel] simply omits a channel the panel has no data for. That is the
 * common case (the guide covers well under half of a household's channels) and
 * the grid renders it as a blank row rather than hiding the channel, because a
 * missing row reads as a bug while an empty one reads as "no data".
 *
 * [fromMs]/[toMs] echo what the EDGE actually served, which is not necessarily
 * what was asked for: the panel's schedule is short, so a client that sized
 * itself from its own request rather than this would draw days of empty columns.
 */
data class IptvGuideWindow(
    val fromMs: Long = 0L,
    val toMs: Long = 0L,
    val aheadHours: Double? = null,
    val byChannel: Map<String, List<IptvProgramme>> = emptyMap(),
) {
    fun programmes(streamId: String): List<IptvProgramme> = byChannel[streamId].orEmpty()

    /** Channels in this window carrying no guide data — the blank rows. */
    fun blankCount(channels: List<IptvChannel>): Int =
        channels.count { byChannel[it.streamId].isNullOrEmpty() }
}

/** A feed carrying a programme — the same show airs on several. */
data class IptvChannelRef(
    val streamId: String,
    val name: String,
)

/**
 * One hit from the guide-text search: a programme, plus the feeds airing it.
 *
 * [programme.streamId] is not meaningful here — the same programme appears once
 * per search with several carriers — so playback must go through [channels].
 */
data class IptvGuideHit(
    val programme: IptvProgramme,
    val epgChannelId: String? = null,
    val channels: List<IptvChannelRef> = emptyList(),
)
