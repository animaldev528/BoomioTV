package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.boomio.MusicIdentifyResult
import com.nuvio.tv.core.boomio.MusicUnavailableReason
import com.nuvio.tv.data.remote.dto.MusicIdentifyDto

/**
 * What the music button is showing.
 *
 * Carries meaning, not copy: the overlay resolves every string, so this stays
 * free of English and the reason a press failed is never flattened into a
 * message that has already lost the distinction.
 *
 * The states are deliberately not "success / error". A `no_match` is the
 * *correct* answer for a scene with no music in it, and the daily cap is a
 * normal state with its own sentence — neither is a fault, and rendering them
 * as one would tell the viewer something untrue.
 */
sealed interface MusicIdentifyUiState {

    /** Nothing asked yet this playback. */
    data object Idle : MusicIdentifyUiState

    /**
     * The press is in flight. Genuinely slow — measured 6.5–9.5 s on an 88 GB
     * remux, because the server fetches and decodes a window of the real audio.
     */
    data object Listening : MusicIdentifyUiState

    /** The server recognised something. */
    data class Found(
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val artworkUrl: String? = null,
        val provider: String? = null,
        /**
         * Carried so "add to library" can hand back what the server actually
         * matched. ISRC is the key the library dedupes on when it is present;
         * without it a save falls back to a case-folded title|artist pair, which
         * is a weaker key and will merge two different recordings that share a
         * name.
         */
        val isrc: String? = null,
        val providerTrackId: String? = null,
        /**
         * Where in the title this cue sits, as the server has it — not the
         * player's position at the moment of the press. A viewer who presses and
         * then watches on for ten minutes should still get "42:13", which is
         * where the song actually was.
         */
        val positionMs: Long? = null,
        /** True when this came from the shared cue index — no provider was called. */
        val fromIndex: Boolean = false,
        /** How many songs this episode/title has in the index, this one included. */
        val cuesInEpisode: Int = 0,
        /**
         * The ffprobe stream index the server actually listened to, and why.
         * Null until the server reports it — see [MusicTrackSelectionDto].
         */
        val listenedToTrack: Int? = null,
        val trackReason: String? = null
    ) : MusicIdentifyUiState {

        /**
         * True when we told the server which track was playing and it listened
         * to a different one anyway. Worth surfacing: it means the identification
         * may describe audio the viewer is not hearing.
         *
         * `reason == "default"` alongside a *reported* track is the signature of
         * the ordinal-vs-global-index mismatch. `only` (one track in the file) and
         * `reported` are both agreement.
         */
        val trackDisagrees: Boolean
            get() = trackReason == "default" && listenedToTrack != null
    }

    /**
     * The server answered, and the answer was not a match. [status] is its own
     * word — `no_match`, `no_stream`, `no_context` — kept so the three can be
     * told apart in copy; they mean very different things to a viewer.
     */
    data class NoMatch(val status: String, val detail: String? = null) : MusicIdentifyUiState

    /** The per-device daily cap. Try again tomorrow; nothing is broken. */
    data object RateLimited : MusicIdentifyUiState

    /** The question never reached the server. */
    data class Unavailable(
        val reason: MusicUnavailableReason,
        val detail: String? = null
    ) : MusicIdentifyUiState
}

/**
 * How "add to library" is going.
 *
 * Separate from [MusicIdentifyUiState] because the two are genuinely
 * independent: the answer stays on screen while the save is in flight, and a
 * failed save must not wipe out what was found.
 *
 * [Saved] and [AlreadySaved] are both successes and are kept apart only so the
 * card can stop implying there is anything left to do. The server reports the
 * distinction itself — a second press of the same track is a no-op that returns
 * the row already there — so nothing here has to guess.
 */
sealed interface MusicSaveState {
    data object Idle : MusicSaveState

    data object Saving : MusicSaveState

    /** Newly written to the library. */
    data object Saved : MusicSaveState

    /** This track was already in the library; nothing changed. */
    data object AlreadySaved : MusicSaveState

    /** The save did not happen, and [reason] says why. */
    data class Failed(
        val reason: MusicUnavailableReason,
        val detail: String? = null
    ) : MusicSaveState
}

/**
 * Map a transport result onto UI state.
 *
 * `ok` with a null match is treated as a miss rather than a crash: the server
 * contract says `match` is set when `status` is `ok`, so a violation of that is
 * still something the viewer can be told honestly instead of a blank card.
 */
fun MusicIdentifyResult.toUiState(): MusicIdentifyUiState = when (this) {
    is MusicIdentifyResult.Unavailable -> MusicIdentifyUiState.Unavailable(reason, detail)

    is MusicIdentifyResult.Answered -> {
        val dto: MusicIdentifyDto = response
        when (dto.status) {
            "ok" -> dto.match?.let { m ->
                MusicIdentifyUiState.Found(
                    title = m.title.orEmpty(),
                    artist = m.artist,
                    album = m.album,
                    artworkUrl = m.artworkUrl,
                    provider = m.provider,
                    isrc = m.isrc,
                    providerTrackId = m.providerTrackId,
                    positionMs = m.positionMs,
                    fromIndex = dto.source == "cache",
                    cuesInEpisode = dto.cuesInEpisode ?: 0,
                    listenedToTrack = dto.track?.chosen,
                    trackReason = dto.track?.reason
                )
            } ?: MusicIdentifyUiState.NoMatch("no_match", dto.error)

            "rate_limited" -> MusicIdentifyUiState.RateLimited

            // Anything unrecognised is reported as a miss carrying the server's
            // own word, so a status added later degrades to "no match here"
            // rather than to a lie about a network fault.
            else -> MusicIdentifyUiState.NoMatch(dto.status ?: "unknown", dto.error)
        }
    }
}
