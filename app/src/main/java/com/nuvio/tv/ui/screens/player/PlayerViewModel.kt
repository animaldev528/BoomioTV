package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.activity.ActivityEventReporter
import com.nuvio.tv.core.boomio.ActiveCompanionPlayer
import com.nuvio.tv.core.boomio.BoomioCompanionManager
import com.nuvio.tv.core.boomio.CompanionPlaybackBridge
import com.nuvio.tv.core.boomio.CompanionPlaybackSnapshot
import com.nuvio.tv.core.boomio.MusicClient
import com.nuvio.tv.core.boomio.MusicIdentifyResult
import com.nuvio.tv.core.boomio.TrickplayClient
import com.nuvio.tv.core.boomio.TrickplaySet
import com.nuvio.tv.core.sync.SyncClientIdentity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.debrid.DirectDebridStreamPreparer
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackProgressStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.data.local.AudioDelayRouteDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.trailer.TrailerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val trackingScrobbleCoordinator: TrackingScrobbleCoordinator,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val skipIntroRepository: SkipIntroRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val bingeGroupCacheDataStore: com.nuvio.tv.data.local.BingeGroupCacheDataStore,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val trackPreferenceDataStore: com.nuvio.tv.data.local.TrackPreferenceDataStore,
    private val audioDelayRouteDataStore: AudioDelayRouteDataStore,
    private val torrentService: TorrentService,
    private val torrentSettings: TorrentSettings,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListRepository: MDBListRepository,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val traktRelatedService: TraktRelatedService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val directDebridResolver: DirectDebridResolver,
    private val directDebridStreamPreparer: DirectDebridStreamPreparer,
    private val cloudLibraryRepository: CloudLibraryRepository,
    private val cloudPlaybackProgressStore: CloudLibraryPlaybackProgressStore,
    private val cloudPlaybackSessionStore: CloudLibraryPlaybackSessionStore,
    private val streamBadgePresentation: com.nuvio.tv.core.streams.StreamBadgePresentation,
    private val playbackIssueReportRepository: com.nuvio.tv.data.repository.PlaybackIssueReportRepository,
    private val externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    private val subtitleFileCache: com.nuvio.tv.core.player.SubtitleFileCache,
    private val tvRecommendationManager: com.nuvio.tv.core.recommendations.TvRecommendationManager,
    profileManager: com.nuvio.tv.core.profile.ProfileManager,
    private val activityEventReporter: ActivityEventReporter,
    private val companionPlaybackBridge: CompanionPlaybackBridge,
    private val companionManager: BoomioCompanionManager,
    private val musicClient: MusicClient,
    private val trickplayClient: TrickplayClient,
    private val syncClientIdentity: SyncClientIdentity,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    init {
        // Release trailer player codec resources so the full-screen player can
        // claim hardware decoders without contention (prevents black screen).
        trailerPlayerPool.yield()
    }

    internal val controller = PlayerRuntimeController(
        context = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        addonRepository = addonRepository,
        pluginManager = pluginManager,
        subtitleRepository = subtitleRepository,
        parentalGuideRepository = parentalGuideRepository,
        trackingScrobbleCoordinator = trackingScrobbleCoordinator,
        traktEpisodeMappingService = traktEpisodeMappingService,
        skipIntroRepository = skipIntroRepository,
        playerSettingsDataStore = playerSettingsDataStore,
        deviceLocalPlayerPreferences = deviceLocalPlayerPreferences,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        streamBadgeSettingsDataStore = streamBadgeSettingsDataStore,
        bingeGroupCacheDataStore = bingeGroupCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchedItemsPreferences = watchedItemsPreferences,
        trackPreferenceDataStore = trackPreferenceDataStore,
        audioDelayRouteDataStore = audioDelayRouteDataStore,
        torrentService = torrentService,
        torrentSettings = torrentSettings,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        directDebridResolver = directDebridResolver,
        directDebridStreamPreparer = directDebridStreamPreparer,
        cloudLibraryRepository = cloudLibraryRepository,
        cloudPlaybackProgressStore = cloudPlaybackProgressStore,
        cloudPlaybackSessionStore = cloudPlaybackSessionStore,
        streamBadgePresentation = streamBadgePresentation,
        playbackIssueReportRepository = playbackIssueReportRepository,
        tvRecommendationManager = tvRecommendationManager,
        profileId = savedStateHandle.get<String>("profileId")?.toIntOrNull()
            ?: profileManager.activeProfileId.value,
        activityEventReporter = activityEventReporter,
        musicClient = musicClient,
        syncClientIdentity = syncClientIdentity,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope
    )

    private val postPlayRecommendationController = PostPlayRecommendationController(
        playbackController = controller,
        playerSettingsDataStore = playerSettingsDataStore,
        metaRepository = metaRepository,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        mdbListRepository = mdbListRepository,
        mdbListSettingsDataStore = mdbListSettingsDataStore,
        traktRelatedService = traktRelatedService,
        traktAuthDataStore = traktAuthDataStore,
        traktSettingsDataStore = traktSettingsDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchProgressRepository = watchProgressRepository,
        watchedSeriesStateHolder = watchedSeriesStateHolder,
        trailerService = trailerService,
        trailerSettingsDataStore = trailerSettingsDataStore,
        trailerPlayerPool = trailerPlayerPool,
        scope = viewModelScope
    )

    /** True while applying an inbound hub command, so its effect isn't re-reported. */
    private var suppressPartyReport = false

    /**
     * Companion (bsc) surface for this screen's playback. Registered while the
     * player is alive so [companionManager] can report telemetry and forward
     * inbound play/pause/seek/stop commands from the hub.
     */
    private val companionActivePlayer = object : ActiveCompanionPlayer {
        override val playbackSnapshot: CompanionPlaybackSnapshot
            get() = CompanionPlaybackSnapshot(
                positionMs = controller.currentPlaybackPositionMs() ?: 0L,
                durationMs = controller.currentPlaybackDurationMs(),
                isPlaying = controller.isPlaybackCurrentlyPlaying(),
                streamUrl = controller.getCurrentStreamUrl(),
                imdbId = controller.contentId,
                title = controller.title,
                season = controller.currentSeason,
                episode = controller.currentEpisode,
                posterUrl = controller.poster,
                logoUrl = controller.logo,
                // "channel" for live IPTV. Lets the phone tell a channel from a
                // VOD title even though both put an id in the imdbId slot.
                contentType = controller.contentType,
                // Which audio track the viewer is hearing. Already a 0-based
                // ordinal into the audio list, which is the contract the music
                // API expects. -1 means "nothing selected yet" — reported as
                // null so the server falls back to the file's default rather
                // than being told "track -1".
                audioTrack = controller.uiState.value.selectedAudioTrackIndex.takeIf { it >= 0 }
            )

        override fun togglePlayPause(reportParty: Boolean) {
            if (reportParty) {
                onEvent(PlayerEvent.OnPlayPause)
            } else {
                suppressPartyReport = true
                try {
                    onEvent(PlayerEvent.OnPlayPause)
                } finally {
                    suppressPartyReport = false
                }
            }
        }
        override fun pause() = controller.setPlaybackPaused(true)
        override fun resume() = controller.setPlaybackPaused(false)
        override fun seekTo(positionMs: Long) = controller.seekPlaybackTo(positionMs)
        override fun stop() = controller.stopAndRelease()
        override fun setVolume(fraction: Float) {
            // Main-thread-only: companion commands arrive via the manager's main
            // handler. Scales this player's own audio; device volume is untouched.
            // Routed through the controller so a private-listening mute survives
            // the slider instead of being overwritten by it.
            controller.setPlayerVolume(fraction)
        }
        override fun startPhoneAudioFork(phoneIp: String, port: Int): Boolean =
            // No mute here: the phone's private-listening screen owns the TV-speaker
            // switch, and sends it on the same frame. A fork the phone asked for
            // without stating a preference leaves this TV audible — never silence a
            // room on a guess.
            controller.startPhoneAudioFork(phoneIp, port)

        override fun setTvSpeakersEnabled(enabled: Boolean) {
            // Main-thread-only, like every companion command. Routed through the
            // controller's single volume authority so it composes with the volume
            // slider rather than fighting it.
            controller.setPrivateListeningMute(!enabled)
        }

        override fun stopPhoneAudioFork() {
            controller.stopPhoneAudioFork()
            // Always un-mute on teardown: the fork is gone, so the room must be
            // audible again whatever the last preference was.
            controller.setPrivateListeningMute(false)
        }
        override val isPhoneAudioForkActive: Boolean
            get() = controller.isPhoneAudioForkActive()

        // A phone's "what is this?" press, answered with this playback's own
        // position, stream URL and audio-track ordinal — the fields the phone
        // has no way to supply. The ordinal is why the press comes here at all;
        // see [ActiveCompanionPlayer.identifyMusicForCompanion].
        override fun identifyMusicForCompanion(onResult: (MusicIdentifyResult) -> Unit) =
            controller.identifyMusicForCompanion(onResult)
    }

    init {
        companionPlaybackBridge.registerActivePlayer(companionActivePlayer)
    }

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    val playbackTimeline: StateFlow<PlaybackTimelineState>
        get() = controller.playbackTimeline

    val postPlayRecommendationUiState: StateFlow<PostPlayRecommendationUiState>
        get() = postPlayRecommendationController.uiState

    // ── Scrub preview (trickplay) ────────────────────────────────────────────
    //
    // The frame the viewer is scrubbing to. Resolved lazily, on the first scrub
    // of a title, and null for the ordinary case of a title whose sprites have
    // not been rendered yet — "no thumbnail" is a state, not an error, and
    // nothing here may delay or fail a seek.

    private val _scrubPreview = MutableStateFlow<TrickplaySet?>(null)

    /** Non-null once this title's sprite set has been resolved. */
    val scrubPreview: StateFlow<TrickplaySet?> = _scrubPreview.asStateFlow()

    /** mediaKey + token of the set in [scrubPreview] / the in-flight request. */
    private var scrubPreviewKey: String? = null
    private var scrubPreviewRequestedAtMs = 0L
    private var scrubPreviewJob: Job? = null

    /**
     * Fetch this title's sprite set, at most once per title per token.
     *
     * Called on every preview-seek event, so the guards matter: a drag emits one
     * event per D-pad repeat, and without them a single scrub would fire dozens
     * of lookups. A title that has no sprites is retried only after
     * [SCRUB_PREVIEW_RETRY_MS], because the first play is exactly when the edge
     * starts rendering them — asking once and never again would mean the
     * thumbnails that appear mid-episode never show up at all.
     */
    fun prepareScrubPreview() {
        val mediaKey = currentTrickplayMediaKey() ?: return
        val token = currentStreamToken() ?: return
        val key = "$mediaKey\u0000$token"
        val now = System.currentTimeMillis()

        if (key == scrubPreviewKey) {
            if (scrubPreviewJob?.isActive == true) return
            if (_scrubPreview.value != null) return
            if (now - scrubPreviewRequestedAtMs < SCRUB_PREVIEW_RETRY_MS) return
        } else {
            // A different episode (or a re-minted token): the cues already in
            // hand describe frames of something else, or carry a token the edge
            // will now refuse. Drop them rather than show the wrong picture.
            _scrubPreview.value = null
            scrubPreviewJob?.cancel()
        }

        scrubPreviewKey = key
        scrubPreviewRequestedAtMs = now
        scrubPreviewJob = viewModelScope.launch {
            val set = trickplayClient.lookup(mediaKey, token)
            // Publish only if this is still what is being watched: a slow lookup
            // that lands after the viewer moved to the next episode must not
            // overwrite that episode's preview with this one's.
            if (scrubPreviewKey == key) _scrubPreview.value = set
        }
    }

    /**
     * The server's own key convention (lib/skip-store.js `mediaKeyFor`), built
     * here because the handoff response does not carry it: `imdb:<id>` for a
     * film, `imdb:<id>:s<season>e<episode>` for an episode. Season and episode
     * are UNPADDED — `s3e7`, never `s03e07`.
     *
     * Null for anything that is not a TMDB/IMDb-keyed title (a live IPTV
     * channel puts a channel id in the same slot), which is also everything the
     * server's media-key allowlist would reject.
     */
    private fun currentTrickplayMediaKey(): String? {
        val imdbId = controller.contentId?.takeIf { it.startsWith("tt") } ?: return null
        val season = controller.currentSeason
        val episode = controller.currentEpisode
        return if (season != null && episode != null) {
            "imdb:$imdbId:s$season" + "e$episode"
        } else {
            "imdb:$imdbId"
        }
    }

    /**
     * The stream token, now delivered as a nav arg: the server returns it
     * beside the stream URL, and the URL the client actually plays carries no
     * token.
     *
     * Not the companion session token: the sprite routes verify this one with
     * the same `verifyStreamToken()` the playback proxy uses, IP binding
     * included, so the token that plays is the token that previews. The URL
     * query-param fallback below remains for the Stremio/cast handoff path,
     * whose URL still carries `?token=`. Null for a local file or a torrent,
     * which have no edge and no sprites.
     */
    private fun currentStreamToken(): String? =
        controller.streamToken?.takeIf { it.isNotBlank() }
            ?: controller.getCurrentStreamUrl()
                .toHttpUrlOrNull()
                ?.queryParameter("token")
                ?.takeIf { it.isNotBlank() }

    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    fun getCurrentFileSizeBytes(): Long? = controller.currentVideoSize

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun getPlayerNativeMemoryBytes(): Long? {
        val allocator = controller._loadControl?.allocator as? androidx.media3.exoplayer.upstream.DefaultAllocator ?: return null
        return allocator.totalBytesAllocated.toLong().coerceAtLeast(0L)
    }

    fun stopAndRelease() {
        postPlayRecommendationController.stop()
        controller.stopAndRelease()
    }

    fun playPostPlayTrailer() {
        postPlayRecommendationController.playTrailer()
    }

    fun onPostPlayTrailerEnded() {
        postPlayRecommendationController.onTrailerEnded()
    }

    fun showPreviousPostPlayRecommendation() {
        postPlayRecommendationController.showPreviousRecommendation()
    }

    fun showNextPostPlayRecommendation() {
        postPlayRecommendationController.showNextRecommendation()
    }

    fun returnToPlayerFromPostPlay() {
        postPlayRecommendationController.returnToPlayer()
    }

    fun scheduleHideControls() {
        controller.scheduleHideControls()
    }

    fun onUserInteraction() {
        controller.onUserInteraction()
    }

    fun hideControls() {
        controller.hideControls()
    }

    fun attachHostActivity(activity: android.app.Activity?) {
        controller.attachHostActivity(activity)
    }

    fun attachMpvView(view: NuvioMpvSurfaceView?) {
        controller.attachMpvView(view)
    }

    fun pauseForLifecycle() {
        controller.pauseForLifecycle()
    }

    fun resumeForLifecycle() {
        controller.resumeForLifecycle()
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun onEvent(event: PlayerEvent) {
        // The one funnel every scrub passes through, whichever surface raised it
        // (the controls row, or the seek overlay shown with the controls hidden),
        // so the sprite lookup is kicked off in exactly one place.
        if (event is PlayerEvent.OnPreviewSeekBy) prepareScrubPreview()
        controller.onEvent(event)
        if (!suppressPartyReport) reportPartyEvent(event)
    }

    /**
     * Re-broadcast local play/pause/seek to the companion hub when this playback
     * belongs to a watch party, so the hub can mirror it to all other members.
     * No-op when not in a party — the manager checks internally.
     */
    private fun reportPartyEvent(event: PlayerEvent) {
        when (event) {
            PlayerEvent.OnPlayPause -> companionManager.reportPartyPlayPause()
            is PlayerEvent.OnSeekTo -> companionManager.reportPartySeek(event.position)
            is PlayerEvent.OnSeekBy -> {
                val current = controller.currentPlaybackPositionMs() ?: 0L
                val maxDuration = controller.currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
                companionManager.reportPartySeek(
                    (current + event.deltaMs).coerceAtLeast(0L).coerceAtMost(maxDuration)
                )
            }
            // FF/RW translate to short seeks internally; mirror the same step so
            // party members land on the same position the local controller applied.
            PlayerEvent.OnSeekForward -> reportPartyEvent(PlayerEvent.OnSeekBy(PlayerScrubRates.STEP_SHORT_MS))
            PlayerEvent.OnSeekBackward -> reportPartyEvent(PlayerEvent.OnSeekBy(-PlayerScrubRates.STEP_SHORT_MS))
            else -> Unit
        }
    }

    fun bindExoSubtitleView(subtitleView: androidx.media3.ui.SubtitleView?) {
        controller.bindExoSubtitleView(subtitleView)
    }

    fun consumePendingExitReason() {
        controller.consumePendingExitReason()
    }

    override fun onCleared() {
        companionPlaybackBridge.unregisterActivePlayer(companionActivePlayer)
        // Release the fork before the bridge loses the active player, so the phone gets a clean
        // end even if no audio_fork_stop frame ever arrives. (The controller also stops it on
        // release — this covers the gap between VM teardown and the controller's own lifecycle.)
        companionActivePlayer.stopPhoneAudioFork()
        postPlayRecommendationController.stop()
        controller.onCleared()
        // Allow the trailer player to be re-created when returning to home screen.
        trailerPlayerPool.reclaim()
        super.onCleared()
    }

    /**
     * Save watch progress returned by an external player after "Open in External Player".
     * Uses the controller's current content metadata (contentId, season, episode, etc.)
     * which are still available since the controller hasn't been cleared yet.
     */
    fun saveExternalPlayerProgress(positionMs: Long, durationMs: Long?) {
        val effectiveDuration = durationMs ?: controller.playbackTimeline.value.duration
        controller.saveWatchProgressInternal(
            position = positionMs,
            duration = effectiveDuration
        )
    }

    /**
     * Launch the current stream in an external player via the centralized tracker.
     *
     * Keep the ViewModel alive until the external intent has been handed to the launcher.
     * This lets the caller navigate away only after a successful handoff, while failures
     * remain visible on the current player screen (#2560).
     */
    fun launchInExternalPlayer(
        activityContext: Context,
        resumePositionMs: Long,
        onResult: (Boolean) -> Unit
    ) {
        val url = controller.getCurrentStreamUrl()
        if (url.isBlank()) {
            onResult(false)
            return
        }
        val contentId = controller.contentId
            ?: controller.cloudPlaybackContext?.item?.stableKey
            ?: run {
            onResult(false)
            return
        }
        val videoId = controller.currentVideoId ?: contentId
        val metadata = com.nuvio.tv.core.player.ExternalPlaybackMetadata(
            contentId = contentId,
            contentType = controller.contentType ?: "movie",
            contentName = controller.contentName ?: controller.title,
            poster = controller.poster,
            backdrop = controller.backdrop,
            logo = controller.logo,
            videoId = videoId,
            season = controller.currentSeason,
            episode = controller.currentEpisode,
            episodeTitle = controller.currentEpisodeTitle,
            year = controller.year,
            profileId = controller.profileId
        )
        val headers = controller.getCurrentHeaders()
        val nextEpisodeSnapshot = controller.metaVideos
            .takeIf { it.isNotEmpty() }
            ?.let { videos ->
                com.nuvio.tv.core.player.resolveExternalNextEpisodeSnapshot(
                    videos = videos,
                    currentSeason = metadata.season,
                    currentEpisode = metadata.episode
                )
            }

        // Capture already-loaded addon subtitles before handing off. Preparation stays in the
        // ViewModel scope because the player screen remains alive until the intent is sent.
        val subtitleInputs = if (controller.uiState.value.subtitleStyle.preferredLanguage.trim().lowercase() != "none") {
            val addonSubtitles = controller.uiState.value.addonSubtitles
            if (addonSubtitles.isNotEmpty()) {
                addonSubtitles.map {
                    com.nuvio.tv.core.player.SubtitleInput(
                        url = it.url,
                        name = "${it.getDisplayLanguage()} - ${it.addonName}",
                        lang = it.lang
                    )
                }
            } else null
        } else null

        viewModelScope.launch {
            val cachedSubtitles = subtitleInputs?.let { inputs ->
                try {
                    withTimeoutOrNull(10_000L) {
                        subtitleFileCache.cacheSubtitles(inputs)
                    }
                } catch (_: Exception) {
                    // Subtitle forwarding is best-effort; the external launch must still proceed.
                    null
                }
            }

            // Stop the internal player only after preparation has completed and immediately
            // before sending the external intent.
            controller.stopAndRelease()
            val launched = try {
                externalPlaybackTracker.launchPlayer(
                    metadata = metadata,
                    url = url,
                    title = metadata.buildPlayerTitle(),
                    headers = headers,
                    resumePositionMs = resumePositionMs,
                    subtitles = cachedSubtitles,
                    nextEpisodeSnapshot = nextEpisodeSnapshot,
                    cloudSessionToken = controller.cloudSessionToken,
                    context = activityContext
                )
            } catch (_: Exception) {
                false
            }
            onResult(launched)
        }
    }

    private companion object {
        /**
         * How long a "this title has no sprites" answer stands before a later
         * scrub may ask again. The edge renders sprites on the first play, so the
         * answer really does change — but not between two D-pad repeats.
         */
        const val SCRUB_PREVIEW_RETRY_MS = 45_000L
    }
}
