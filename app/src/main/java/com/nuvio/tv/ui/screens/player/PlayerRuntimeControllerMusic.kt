package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.boomio.MusicIdentifyRequest
import com.nuvio.tv.core.boomio.MusicSaveRequest
import com.nuvio.tv.core.boomio.MusicSaveResult
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The music button: ask bsc what is playing, from the player's own state at the
 * moment of the press.
 *
 * The request carries position, stream URL, title and audio track explicitly
 * rather than leaning on the device's telemetry-written position record. That
 * record is written at a ~1 s cadence behind a 60 s TTL, and the one field where
 * staleness changes the answer is the audio track: a viewer who switches to a
 * commentary and presses immediately would otherwise report the track from
 * before the switch, and the service would identify music they are not hearing
 * — then index it for every other user and device, since the cue table is shared.
 *
 * Sending it on this body is also why no server change is needed to honour it:
 * the identify route already reads `body.audioTrack`.
 */
internal fun PlayerRuntimeController.identifyMusic() {
    // A second press while the first is in flight is the same question. Let it
    // ride rather than spending another provider call on it — the daily cap
    // counts provider calls, not presses.
    if (musicIdentifyJob?.isActive == true) return

    val state = _uiState.value
    _uiState.update {
        it.copy(
            showMusicOverlay = true,
            musicIdentify = MusicIdentifyUiState.Listening,
            // A fresh question means a fresh answer, so any save state left over
            // from the previous one is cleared — otherwise a new song would open
            // already claiming to be in the library.
            musicSave = MusicSaveState.Idle
        )
    }

    val request = MusicIdentifyRequest(
        // The companion device id, which is the key the position record is
        // written under (`handleRegister` uses `msg.deviceId`), so the server's
        // fallback lookup agrees with this if a field is ever missing.
        deviceId = syncClientIdentity.currentClientId(),
        positionMs = currentPlaybackPositionMs() ?: 0L,
        imdbId = contentId,
        season = currentSeason,
        episode = currentEpisode,
        streamUrl = getCurrentStreamUrl(),
        // -1 is the player's "nothing selected yet" sentinel, reset on every
        // stream change. Reported as null so the server falls back to the file's
        // default rather than being handed an index that looks plausible and
        // resolves to nothing.
        audioTrack = state.selectedAudioTrackIndex.takeIf { it >= 0 }
    )

    musicIdentifyJob = scope.launch {
        val result = musicClient.identify(request)
        _uiState.update { it.copy(musicIdentify = result.toUiState()) }
    }
}

/**
 * Keep the song that was just identified.
 *
 * Sends back what the identify answer already returned rather than asking the
 * server to identify again — the track is in hand, and a second provider call
 * would spend the daily cap to arrive at the same row.
 *
 * The owner is not sent. bsc takes it from the session, so this client cannot
 * put a song in the wrong person's library even if it wanted to.
 */
internal fun PlayerRuntimeController.saveMusicToLibrary() {
    val state = _uiState.value
    val found = state.musicIdentify as? MusicIdentifyUiState.Found ?: return

    // Already saved, already on its way, or nothing to save. A second press is
    // the same request, and the server would answer `duplicate` — but there is
    // no reason to spend a round trip finding that out.
    if (state.musicSave is MusicSaveState.Saving ||
        state.musicSave is MusicSaveState.Saved ||
        state.musicSave is MusicSaveState.AlreadySaved
    ) {
        return
    }

    _uiState.update { it.copy(musicSave = MusicSaveState.Saving) }

    val request = MusicSaveRequest(
        title = found.title,
        artist = found.artist,
        album = found.album,
        isrc = found.isrc,
        artworkUrl = found.artworkUrl,
        provider = found.provider,
        providerTrackId = found.providerTrackId,
        imdbId = contentId,
        season = currentSeason,
        episode = currentEpisode,
        // The cue's own position, not where the viewer is now.
        positionMs = found.positionMs
    )

    scope.launch {
        val result = musicClient.saveToLibrary(request)
        _uiState.update {
            it.copy(
                musicSave = when (result) {
                    is MusicSaveResult.Stored ->
                        if (result.duplicate) MusicSaveState.AlreadySaved else MusicSaveState.Saved

                    is MusicSaveResult.Failed ->
                        MusicSaveState.Failed(result.reason, result.detail)
                }
            )
        }
    }
}
