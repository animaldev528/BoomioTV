package com.boomio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomio.tv.core.tracking.TrackingProviderId
import com.boomio.tv.core.tracking.TrackingSourceController
import com.boomio.tv.core.tracking.TrackingSourceSelection
import com.boomio.tv.core.tracking.availableLibrarySourceModes
import com.boomio.tv.core.tracking.availableWatchProgressSources
import com.boomio.tv.core.tracking.effectiveTrackingSourceSelection
import com.boomio.tv.data.local.TraktAuthDataStore
import com.boomio.tv.data.local.TraktSettingsDataStore
import com.boomio.tv.data.local.WatchProgressSource
import com.boomio.tv.data.simkl.SimklAnimeIdPreference
import com.boomio.tv.data.simkl.SimklAuthRepository
import com.boomio.tv.data.simkl.SimklSyncRepository
import com.boomio.tv.domain.model.LibrarySourceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackingSettingsUiState(
    val watchProgressSource: WatchProgressSource = WatchProgressSource.NUVIO_SYNC,
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val connectedProviderIds: Set<TrackingProviderId> = emptySet(),
    val simklAnimeIdPreference: SimklAnimeIdPreference = SimklAnimeIdPreference.DEFAULT,
    val isReady: Boolean = false
) {
    val availableWatchProgressSources: List<WatchProgressSource>
        get() = availableWatchProgressSources(connectedProviderIds)

    val availableLibrarySourceModes: List<LibrarySourceMode>
        get() = availableLibrarySourceModes(connectedProviderIds)
}

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    private val sourceController: TrackingSourceController,
    private val settingsDataStore: TraktSettingsDataStore,
    private val simklSyncRepository: SimklSyncRepository,
    traktAuthDataStore: TraktAuthDataStore,
    simklAuthRepository: SimklAuthRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackingSettingsUiState())
    val uiState: StateFlow<TrackingSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                sourceController.watchProgressSource,
                sourceController.librarySourceMode,
                traktAuthDataStore.state,
                simklAuthRepository.state,
                settingsDataStore.simklAnimeIdPreference
            ) { watchProgressSource, librarySourceMode, traktState, simklState, animeIdPref ->
                val connectedProviderIds = buildSet {
                    if (traktState.isAuthenticated) add(TrackingProviderId.TRAKT)
                    if (simklState.isAuthenticated) add(TrackingProviderId.SIMKL)
                }
                val effective = effectiveTrackingSourceSelection(
                    requested = TrackingSourceSelection(watchProgressSource, librarySourceMode),
                    connectedProviderIds = connectedProviderIds
                )
                TrackingSettingsUiState(
                    watchProgressSource = effective.watchProgressSource,
                    librarySourceMode = effective.librarySourceMode,
                    connectedProviderIds = connectedProviderIds,
                    simklAnimeIdPreference = animeIdPref,
                    isReady = true
                )
            }.collect { state ->
                _uiState.value = state
                sourceController.reconcileConnectedProviders(state.connectedProviderIds)
            }
        }
    }

    fun selectWatchProgressSource(source: WatchProgressSource) {
        viewModelScope.launch {
            sourceController.selectWatchProgressSource(source)
        }
    }

    fun selectLibrarySourceMode(mode: LibrarySourceMode) {
        viewModelScope.launch {
            sourceController.selectLibrarySourceMode(mode)
        }
    }

    fun selectSimklAnimeIdPreference(preference: SimklAnimeIdPreference) {
        viewModelScope.launch {
            settingsDataStore.setSimklAnimeIdPreference(preference)
            simklSyncRepository.invalidateProjections(preference)
        }
    }
}
