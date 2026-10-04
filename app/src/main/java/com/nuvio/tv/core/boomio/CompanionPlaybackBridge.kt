package com.nuvio.tv.core.boomio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A `play` command received from the bsc companion hub (phone remote or watch
 * party). The nav layer consumes this and builds the player screen route.
 */
data class CompanionPlayRequest(
    val streamUrl: String,
    val title: String?,
    val imdbId: String?,
    val season: Int?,
    val episode: Int?,
    val resumeFromMs: Long,
    val startPaused: Boolean,
    val partyId: String?,
    val source: String?,
    /**
     * Catalog type for the player route. Live TV is selected SOLELY by
     * `"channel"` (see LivePlaybackUiPolicy) — the URL is no discriminator, since
     * live and VOD are both HLS. Without this the consumer could only infer
     * movie/series from `season`, so a channel pushed by the companion (or an
     * IPTV watch party) would tune correctly and then draw a VOD seek bar over
     * live television. Null keeps the old inference for existing callers.
     */
    val contentType: String? = null
)

/** Snapshot of the active player's state, reported to the hub at ~1s cadence. */
data class CompanionPlaybackSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val streamUrl: String,
    val imdbId: String?,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val posterUrl: String?,
    val logoUrl: String?,
    /**
     * Catalog type of what is playing — `"channel"` for live TV. Reported so the
     * phone can NAME the channel it is on: for an IPTV session [imdbId] already
     * carries the streamId, but without this flag that is indistinguishable from
     * a VOD imdbId, so "start a party on what the Shield is watching" could not
     * tell a channel from a movie.
     */
    val contentType: String? = null,
    /**
     * Which audio track the viewer is actually hearing, as a **0-based ordinal
     * into the audio-only track list** — NOT ffprobe's global stream index.
     *
     * The distinction matters and both ways it can go wrong are silent. The
     * server's `selectAudioStream` (`bsc/lib/music/extract.js`) matches this
     * against `s.index` over a list already filtered to audio streams — so an
     * ordinal is compared to a global index, and on a normal movie (video is 0)
     * the two disagree:
     *
     * - ordinal `0` matches nothing and quietly falls back to the file's
     *   `default` track;
     * - ordinal `1` matches global index 1, which is the **first** audio track,
     *   and comes back as though we had been obeyed (`reason: "reported"`).
     *
     * Either way, a viewer who deliberately picked a commentary track can have
     * music identified from a track they are not hearing, and written to
     * `music_cues` — a table shared by every user and device. The server fix is
     * one line (index the audio array by the ordinal); until it lands this field
     * is the agreed contract, and the response's `track` object is the only thing
     * that makes a disagreement visible.
     *
     * `selectedAudioTrackIndex` in the player's uiState is exactly this ordinal:
     * both the ExoPlayer and MPV paths number audio from 0 within the audio
     * list, so `PlayerRuntimeControllerTracks.kt` and this agree.
     */
    val audioTrack: Int? = null
)

/**
 * The pause/seek/telemetry surface the active player exposes to the companion
 * manager. Registered by the player screen while it is alive; the manager reads
 * it for telemetry and forwards inbound play-control commands to it.
 */
interface ActiveCompanionPlayer {
    val playbackSnapshot: CompanionPlaybackSnapshot

    fun togglePlayPause(reportParty: Boolean = true)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()

    /**
     * Set the player's own volume, 0–1. Used by the companion remote when the OS
     * blocks device-stream volume changes — scaling the player is always honored.
     */
    fun setVolume(fraction: Float)

    /**
     * Arm Roku-style private listening: tee the audio this player is already decoding for its own
     * speakers to the phone at [phoneIp]:[phonePort] over UDP. The phone supplies its LAN address
     * (never server-derived — the hub relays `phoneIp`/`port` as-is). Fails when the player can't
     * fork (e.g. running on the mpv engine, which has no decoded-audio seam).
     *
     * @return true when a fork is now streaming; false with no state change otherwise.
     */
    fun startPhoneAudioFork(phoneIp: String, port: Int): Boolean = false

    /** Unarm private listening. Safe to call when nothing is forked. */
    fun stopPhoneAudioFork() = Unit

    /** True while a private-listening fork is streaming to a phone ("phone attached"). */
    val isPhoneAudioForkActive: Boolean
        get() = false

    /**
     * Answer "what is this playing?" for the paired phone.
     *
     * The phone cannot ask bsc itself, even though it holds the session token
     * that would let it: the identify route selects which audio stream to listen
     * to from the track ordinal the viewer is hearing, and no telemetry record
     * carries one (`handlePosition` in bsc's `device-relay.js` writes position,
     * not track). A phone-side call would fall back to the file's default
     * disposition and identify audio the viewer may have switched away from —
     * the commentary-track mismatch that the ordinal work exists to close.
     * This player is the only party holding that ordinal, so the question lands
     * here.
     *
     * [onResult] is called exactly once, on the main thread. The implementation
     * must not touch `showMusicOverlay`: a press made on the phone is answered on
     * the phone, and putting a card over the picture is the thing it exists to
     * avoid.
     */
    fun identifyMusicForCompanion(onResult: (MusicIdentifyResult) -> Unit)
}

/**
 * The text-entry surface the Search screen exposes to the companion manager
 * while it is in front. Registered by the search composable while alive; the
 * manager forwards inbound `keyboard_input`/`keyboard_submit` frames to it.
 */
interface CompanionSearchInput {
    /** Replace the search field's whole text with [text] and run live search. */
    fun onRemoteText(text: String)

    /** Run the search as if Enter was pressed (Enter / IME Done). */
    fun submit()
}

/**
 * Decouples the singleton bsc WebSocket receiver ([BoomioCompanionManager]) from
 * the screen-bound player.
 *
 * - Play requests flow manager → nav layer via [pendingPlayRequest].
 * - Play control flows hub → active player via [activePlayer].
 *
 * Nothing here touches the player internals, so the manager can be a Hilt
 * singleton that outlives any single player screen.
 */
@Singleton
class CompanionPlaybackBridge @Inject constructor() {

    private val _pendingPlayRequest = MutableStateFlow<CompanionPlayRequest?>(null)
    /** A `play` command awaiting navigation. The latest one wins. */
    val pendingPlayRequest: StateFlow<CompanionPlayRequest?> = _pendingPlayRequest.asStateFlow()

    private val _activePlayer = MutableStateFlow<ActiveCompanionPlayer?>(null)
    /** The currently-active player surface, registered while a player screen is alive. */
    val activePlayer: StateFlow<ActiveCompanionPlayer?> = _activePlayer.asStateFlow()

    private val _activeSearchInput = MutableStateFlow<CompanionSearchInput?>(null)
    /** The currently-active Search screen text surface, if Search is in front. */
    val activeSearchInput: StateFlow<CompanionSearchInput?> = _activeSearchInput.asStateFlow()

    private val _searchRequestTick = MutableStateFlow(0)
    /**
     * Monotonic tick that increments each time the companion asks to open the
     * Search screen (`stealth_search`). The nav layer collects it and navigates;
     * a tick count avoids the consume/null races of a nullable one-shot.
     */
    val searchRequestTick: StateFlow<Int> = _searchRequestTick.asStateFlow()

    fun postPlayRequest(request: CompanionPlayRequest) {
        _pendingPlayRequest.value = request
    }

    /** Consume the pending play request after navigating to it. */
    fun consumePlayRequest() {
        _pendingPlayRequest.value = null
    }

    /** Ask the nav layer to open the TV's Search screen. */
    fun requestSearchScreen() {
        _searchRequestTick.value += 1
    }

    fun registerActivePlayer(player: ActiveCompanionPlayer) {
        _activePlayer.value = player
    }

    fun unregisterActivePlayer(player: ActiveCompanionPlayer) {
        _activePlayer.compareAndSet(player, null)
    }

    fun registerSearchInput(input: CompanionSearchInput) {
        _activeSearchInput.value = input
    }

    fun unregisterSearchInput(input: CompanionSearchInput) {
        _activeSearchInput.compareAndSet(input, null)
    }
}
