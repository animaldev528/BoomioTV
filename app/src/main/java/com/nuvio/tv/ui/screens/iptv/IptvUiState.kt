package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvGuideHit
import com.nuvio.tv.domain.model.IptvGuideWindow
import com.nuvio.tv.domain.model.IptvNowNext
import com.nuvio.tv.domain.model.IptvProgramme
import com.nuvio.tv.domain.model.nowNextAt

/**
 * State of the IPTV section.
 *
 * The section has four distinct things it can be showing, and keeping them as
 * separate fields (rather than one opaque "state" enum) lets the screen decide
 * precedence in one readable place:
 *
 *  - [configured] false  -> this build has no IPTV edge compiled in
 *  - [paired] false      -> show pairing (with [pairing] once a code is issued)
 *  - [unscoped] true     -> paired, but the household follows no channel groups
 *  - otherwise           -> the guide, with [window] filled in where the panel
 *                           actually has data
 */
data class IptvUiState(
    val configured: Boolean = true,
    val loading: Boolean = true,
    val paired: Boolean = false,
    val pairing: IptvPairingUi? = null,
    val channels: List<IptvChannel> = emptyList(),
    val unscoped: Boolean = false,
    val error: String? = null,

    // ── M6: the guide grid ──────────────────────────────────────────────────

    /**
     * The retained guide WINDOW — the programmes themselves, not a collapsed
     * now/next. Both views derive from this one fetch, so the list and the grid
     * cannot disagree about what is on.
     */
    val window: IptvGuideWindow = IptvGuideWindow(),
    val guideLoading: Boolean = false,
    /** How far ahead the window starts, in whole hours from now. 0 == "now". */
    val dayOffsetHours: Int = 0,
    /** What the description pane is describing — the focused programme. */
    val focused: IptvFocused? = null,
    /**
     * The channel playing in the preview pane. Deliberately NOT derived from
     * focus: the preview mirrors the tuned channel and must not follow the
     * D-pad, because scrolling a grid over a single-slot tuner would retune on
     * every keypress and collide with the live-party tuner lock.
     */
    val tuned: IptvTuned? = null,
    /** A play-now resolve is in flight; the press should read as "working". */
    val resolving: Boolean = false,
    val view: IptvView = IptvView.Guide,

    /** Guide-text search, the fallback when a programme was never identified. */
    val search: IptvSearchUi? = null
) {
    /** Now/next per channel, derived from the window rather than fetched again. */
    fun nowNext(nowMs: Long): Map<String, IptvNowNext> =
        window.byChannel.mapValues { (_, programmes) -> programmes.nowNextAt(nowMs) }
}

/** Which of the two presentations the section is showing. */
enum class IptvView { Guide, Channels }

/**
 * What the description pane is about: a channel, and the programme focused
 * within it when there is one.
 *
 * [programme] is null when focus is on a channel rather than on a programme —
 * a channel rail, or a channel the guide carries nothing for. Both are
 * focusable and both tune, so the pane has to be able to describe them.
 */
data class IptvFocused(
    val programme: IptvProgramme?,
    val channel: IptvChannel
)

/**
 * The channel in the preview pane. Carries the tuned playlist URL because that
 * is what the preview surface plays — the same URL the full-screen player would
 * use, so going fullscreen from the preview is a keep-alive no-op on the edge
 * rather than a second upstream connection.
 */
data class IptvTuned(
    val streamId: String,
    val name: String,
    val playlistUrl: String
)

/**
 * Guide-text search results. Programme hits and channel hits are kept apart
 * because they answer different questions — "what's on about X" versus "where is
 * channel X" — and a channel-name search that returned an empty programme list
 * would read as broken.
 */
data class IptvSearchUi(
    val query: String,
    val programmes: List<IptvGuideHit> = emptyList(),
    val channels: List<IptvChannel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/**
 * The code a viewer reads off the screen and approves elsewhere.
 *
 * [expired] is a first-class state rather than an error string: a device code
 * lives 5 minutes, so running out is an ordinary outcome that should offer a
 * fresh code, not read like a failure.
 */
data class IptvPairingUi(
    val userCode: String,
    val verificationUri: String,
    val expired: Boolean = false
)

/**
 * Where a "play this programme" press should go, decided by the ViewModel
 * because only it knows what the store already resolved.
 *
 * [Unknown] is a real outcome, not a failure: a programme the ingest pipeline
 * never identified has no show to open, so the screen falls back to searching
 * the guide text — the same thing a long-press does. It is the honest answer
 * rather than a button that silently does nothing.
 */
sealed interface IptvPlayTarget {
    /** The episode is known — play it directly, with no further call. */
    data class Episode(
        val imdbId: String,
        val mediaType: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val episodeName: String?
    ) : IptvPlayTarget

    /** Only the show is known. Open it and let the existing path pick an episode. */
    data class Show(
        val imdbId: String,
        val mediaType: String,
        val title: String
    ) : IptvPlayTarget

    /** No identity at all — fall back to searching the guide text. */
    data class Unknown(val programme: IptvProgramme) : IptvPlayTarget
}
