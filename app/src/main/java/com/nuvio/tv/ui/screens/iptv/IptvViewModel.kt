package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.boomio.IptvAuthStore
import com.nuvio.tv.core.boomio.IptvClient
import com.nuvio.tv.core.boomio.IptvPollResult
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
                    it.copy(loading = false, paired = false, channels = emptyList(), guide = emptyMap())
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
            if (result.channels.isNotEmpty()) loadGuide(result.channels.map { it.streamId })
        }
    }

    private fun loadGuide(streamIds: List<String>) {
        viewModelScope.launch {
            val guide = client.nowNextFor(streamIds)
            if (guide.isNotEmpty()) _uiState.update { it.copy(guide = guide) }
        }
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
        super.onCleared()
    }

    private companion object {
        const val DEVICE_CODE_TTL_MS = 300_000L
    }
}
