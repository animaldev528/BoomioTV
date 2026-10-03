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
