package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.boomio.IptvAuthStore
import com.nuvio.tv.core.boomio.IptvClient
import com.nuvio.tv.core.boomio.IptvPollResult
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvProgramme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IptvViewModel @Inject constructor(
    private val client: IptvClient,
    private val authStore: IptvAuthStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(IptvUiState(configured = client.isConfigured()))
    val uiState: StateFlow<IptvUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var guideJob: Job? = null
    private var searchJob: Job? = null
    private var tuneJob: Job? = null

    init {
        refresh()
    }

    /** Loads the channel list, or drops to pairing when there is no session. */
    fun refresh() {
        viewModelScope.launch {
            if (!client.isConfigured()) {
                _uiState.update { it.copy(configured = false, loading = false) }
                return@launch
            }
            _uiState.update { it.copy(loading = true, error = null) }

            if (authStore.currentToken().isNullOrBlank()) {
                _uiState.update { it.copy(loading = false, paired = false, channels = emptyList()) }
                return@launch
            }

            val result = client.channels()
            if (result.unauthorized) {
                // The session aged out (90 days) or was revoked. Drop it so the
                // screen offers pairing again instead of failing every launch.
                _uiState.update {
                    it.copy(
                        loading = false,
                        paired = false,
                        channels = emptyList(),
                        window = it.window.copy(byChannel = emptyMap())
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    loading = false,
                    paired = true,
                    channels = result.channels,
                    unscoped = result.unscoped,
                    error = result.error
                )
            }
            if (result.channels.isNotEmpty()) loadGuide()
        }
    }

    /**
     * Loads the guide window for every followed channel.
     *
     * One fetch feeds both presentations — the grid draws the programmes and the
     * channel list derives its now/next from the same window — so the two can
     * never disagree about what is on, and the section makes one set of requests
     * instead of two.
     */
    private fun loadGuide() {
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            val channels = _uiState.value.channels
            if (channels.isEmpty()) return@launch
            _uiState.update { it.copy(guideLoading = true) }
            val window = client.guideWindow(
                streamIds = channels.map { it.streamId },
                fromMs = System.currentTimeMillis() + _uiState.value.dayOffsetHours * HOUR_MS,
                hours = WINDOW_HOURS
            )
            _uiState.update { it.copy(window = window, guideLoading = false) }
        }
    }

    /**
     * Moves the window forward. The panel's guide only reaches about 50 hours, so
     * the stepper is bounded by [MAX_OFFSET_HOURS] rather than letting a viewer
     * page into days of schedule that do not exist.
     */
    fun setDayOffset(hours: Int) {
        val clamped = hours.coerceIn(0, MAX_OFFSET_HOURS)
        if (clamped == _uiState.value.dayOffsetHours) return
        _uiState.update { it.copy(dayOffsetHours = clamped, focused = null) }
        loadGuide()
    }

    fun setView(view: IptvView) = _uiState.update { it.copy(view = view) }

    /**
     * Remembers what the description pane should describe.
     *
     * Focus drives the description and NOTHING else. In particular it must never
     * tune: the edge is a single-slot tuner, so following focus would retune on
     * every keypress and fight the live-party tuner lock.
     */
    fun onProgrammeFocused(programme: IptvProgramme, channel: IptvChannel) {
        _uiState.update { it.copy(focused = IptvFocused(programme, channel)) }
    }

    /**
     * Tunes [channel] into the preview pane.
     *
     * Only ever called from an explicit press, never from focus. Re-tuning the
     * channel already showing is left to the edge, where it is a keep-alive
     * no-op rather than a second upstream connection.
     */
    fun tunePreview(channel: IptvChannel) {
        if (_uiState.value.tuned?.streamId == channel.streamId && _uiState.value.tuned != null) return
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch {
            client.tune(channel.streamId)
                .onSuccess { url ->
                    _uiState.update {
                        it.copy(
                            tuned = IptvTuned(channel.streamId, channel.name, url),
                            error = null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Could not start ${channel.name}")
                    }
                }
        }
    }

    fun stopPreview() {
        tuneJob?.cancel()
        _uiState.update { it.copy(tuned = null) }
    }

    /**
     * Decides what pressing a programme should actually do, cheapest step first.
     *
     *  1. **Already resolved** — the bulk pass paid for this one, so the episode
     *     plays with no call and no wait.
     *  2. **Identified, episode unknown** — ask the edge once, on press. Asking on
     *     FOCUS would spend a model call for every cell merely scrolled past,
     *     which is why this is tied to intent rather than to the D-pad.
     *  3. **No identity** — there is no show to open, so the screen falls back to
     *     searching the guide text. This is a real outcome, not a failure.
     *
     * Step 2 is deliberately skipped for an unidentified programme: the edge
     * short-circuits to `unavailable` when the title was never matched, so asking
     * would spend a round trip to be told what the guide already said.
     */
    fun playProgramme(
        programme: IptvProgramme,
        channel: IptvChannel,
        onTarget: (IptvPlayTarget) -> Unit
    ) {
        val match = programme.match
        if (match != null) {
            val imdbId = match.imdbId
            val mediaType = match.mediaType
            if (!imdbId.isNullOrBlank() && !mediaType.isNullOrBlank()) {
                val season = match.season
                val episode = match.episode
                if (season != null && episode != null) {
                    onTarget(
                        IptvPlayTarget.Episode(
                            imdbId = imdbId,
                            mediaType = mediaType,
                            title = programme.displayTitle,
                            season = season,
                            episode = episode,
                            episodeName = match.episodeName
                        )
                    )
                    return
                }
                resolveThenPlay(programme, channel, imdbId, mediaType, onTarget)
                return
            }
        }
        onTarget(IptvPlayTarget.Unknown(programme))
    }

    /**
     * The on-press resolve. Every failure lands on the same floor — open the show
     * — so there is one fallback path rather than three: no episode proposed, a
     * proposal TMDB could not corroborate, or the call timing out all produce the
     * same experience, and the viewer is never shown a guess.
     */
    private fun resolveThenPlay(
        programme: IptvProgramme,
        channel: IptvChannel,
        imdbId: String,
        mediaType: String,
        onTarget: (IptvPlayTarget) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = true) }
            val resolved = client.resolveEpisode(channel.streamId, programme.startMs)
            _uiState.update { it.copy(resolving = false) }

            // The show identity falls back to the guide's own match: a resolve
            // that returned no episode still tells us nothing new about WHO the
            // programme is, and the guide already knew.
            val showId = resolved?.imdbId?.takeIf { it.isNotBlank() } ?: imdbId
            val showType = resolved?.mediaType?.takeIf { it.isNotBlank() } ?: mediaType
            val showTitle = resolved?.showTitle?.takeIf { it.isNotBlank() } ?: programme.displayTitle

            val season = resolved?.season
            val episode = resolved?.episode
            if (resolved != null && resolved.hasEpisode && season != null && episode != null) {
                onTarget(
                    IptvPlayTarget.Episode(
                        imdbId = showId,
                        mediaType = showType,
                        title = showTitle,
                        season = season,
                        episode = episode,
                        episodeName = resolved.episodeName
                    )
                )
            } else {
                onTarget(IptvPlayTarget.Show(showId, showType, showTitle))
            }
        }
    }

    /**
     * Searches the guide text — the fallback for a programme the ingest pipeline
     * could not identify, so long-pressing is never a dead interaction.
     */
    fun runGuideSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _uiState.update { it.copy(search = null) }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(search = IptvSearchUi(query = q, loading = true)) }
            val result = client.searchGuide(q)
            _uiState.update {
                it.copy(
                    search = IptvSearchUi(
                        query = q,
                        programmes = result.programmes,
                        channels = result.channels,
                        loading = false,
                        error = result.error
                    )
                )
            }
        }
    }

    /** The long-press fallback: search the guide for what this programme calls itself. */
    fun openSearchFor(programme: IptvProgramme) = runGuideSearch(programme.title)

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(search = null) }
    }

    /** Requests a device code and polls until a human approves it. */
    fun startPairing() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, pairing = null, error = null) }
            client.requestPairing()
                .onSuccess { request ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            pairing = IptvPairingUi(
                                userCode = request.userCode,
                                verificationUri = request.verificationUri
                            )
                        )
                    }
                    pollUntilPaired(request.deviceCode, request.intervalSeconds)
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(loading = false, error = e.message ?: "Could not reach the IPTV service")
                    }
                }
        }
    }

    private suspend fun pollUntilPaired(deviceCode: String, intervalSeconds: Int) {
        // The code the edge issued expires in 300s; stop polling on the same
        // clock so the UI and the server agree about when it is dead.
        val deadline = System.currentTimeMillis() + DEVICE_CODE_TTL_MS
        while (System.currentTimeMillis() < deadline) {
            delay(intervalSeconds * 1000L)
            when (val result = client.pollPairing(deviceCode)) {
                is IptvPollResult.Paired -> {
                    authStore.setSessionToken(result.token)
                    _uiState.update { it.copy(pairing = null) }
                    refresh()
                    return
                }
                IptvPollResult.Expired -> {
                    _uiState.update { it.copy(pairing = it.pairing?.copy(expired = true)) }
                    return
                }
                // A transient failure is not terminal — the code is still live,
                // so keep polling rather than stranding the viewer.
                is IptvPollResult.Failed -> Unit
                IptvPollResult.Pending -> Unit
            }
        }
        _uiState.update { it.copy(pairing = it.pairing?.copy(expired = true)) }
    }

    /**
     * Reserves the tuner for [streamId] and hands back a playlist URL carrying
     * its own session token, so the player needs no knowledge of edge auth.
     */
    fun tune(streamId: String, onReady: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            client.tune(streamId)
                .onSuccess { url ->
                    _uiState.update { it.copy(error = null) }
                    onReady(url)
                }
                .onFailure { e ->
                    val message = e.message ?: "Could not start this channel"
                    // Also put it in uiState: this is the screen's primary
                    // interaction, and a failure the viewer cannot see reads as
                    // "the button did nothing". ChannelList already renders
                    // uiState.error, so this is what makes it visible.
                    _uiState.update { it.copy(error = message) }
                    onError(message)
                }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    override fun onCleared() {
        pollJob?.cancel()
        guideJob?.cancel()
        searchJob?.cancel()
        tuneJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val DEVICE_CODE_TTL_MS = 300_000L
        const val HOUR_MS = 3_600_000L

        /** One screen of schedule. Six hours keeps a cell a readable width. */
        const val WINDOW_HOURS = 6

        /**
         * The panel's guide is measured at ~50.5h ahead, so the stepper stops at
         * two days rather than paging into schedule the panel does not have —
         * which would render as an empty grid that looks broken.
         */
        const val MAX_OFFSET_HOURS = 48
    }
}
