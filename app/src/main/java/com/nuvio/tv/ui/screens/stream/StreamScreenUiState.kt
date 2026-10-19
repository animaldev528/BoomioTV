package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus

data class StreamScreenUiState(
    val isLoading: Boolean = true,
    val isDirectAutoPlayFlow: Boolean = false,
    val showDirectAutoPlayOverlay: Boolean = false,
    val autoPlayDecided: Boolean = false,
    val externalPlayerOverlayVisible: Boolean = false,
    val directAutoPlayMessage: String? = null,
    val directAutoPlayProgress: Float? = null,
    val videoId: String = "",
    val contentType: String = "",
    val title: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    // Episode-specific fields
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    val runtime: Int? = null,
    // Movie-specific fields
    val genres: String? = null,
    val year: String? = null,
    val addonStreams: List<AddonStreams> = emptyList(),
    val allStreams: List<Stream> = emptyList(),
    val selectedAddonFilter: String? = null, // null means "All"
    val filteredStreams: List<Stream> = emptyList(),
    val availableAddons: List<String> = emptyList(),
    val sourceChips: List<SourceChipItem> = emptyList(),
    val autoPlayStream: Stream? = null,
    val autoPlayPlaybackInfo: StreamPlaybackInfo? = null,
    val error: String? = null,
    // Why boomio returned nothing, when boomio itself is the reason — a
    // household bandwidth budget that is fully in use, or one where no source
    // fits. Shown only if no other source produced a stream, so the addons keep
    // priority over an explanation from a fallback resolver.
    val boomioMessage: String? = null,
    val playbackErrorMessage: String? = null
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

sealed class StreamScreenEvent {
    data class OnAddonFilterSelected(val addonName: String?) : StreamScreenEvent()
    data class OnStreamSelected(val stream: Stream) : StreamScreenEvent()
    data object OnAutoPlayConsumed : StreamScreenEvent()
    data object OnRefresh : StreamScreenEvent()
    data object OnRetry : StreamScreenEvent()
    data object OnBackPress : StreamScreenEvent()
    data object OnResume : StreamScreenEvent()
}
