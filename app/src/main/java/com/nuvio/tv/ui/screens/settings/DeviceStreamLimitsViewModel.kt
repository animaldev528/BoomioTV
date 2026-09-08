package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.streams.CodecFamily
import com.nuvio.tv.core.streams.DeviceStreamLimits
import com.nuvio.tv.data.local.DeviceStreamLimitsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@HiltViewModel
class DeviceStreamLimitsViewModel @Inject constructor(
    private val dataStore: DeviceStreamLimitsDataStore
) : ViewModel() {
    /** Live per-profile stream-limit settings for the active profile. */
    val settings: Flow<DeviceStreamLimits> = dataStore.settings

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setMasterEnabled(enabled) }
    }

    fun setCodecEnabled(family: CodecFamily, enabled: Boolean) {
        viewModelScope.launch { dataStore.setCodecEnabled(family, enabled) }
    }

    fun setCodecCeilingMbps(family: CodecFamily, mbps: Int) {
        viewModelScope.launch { dataStore.setCodecCeilingMbps(family, mbps) }
    }

    fun setOtherEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setOtherEnabled(enabled) }
    }

    fun setOtherCeilingMbps(mbps: Int) {
        viewModelScope.launch { dataStore.setOtherCeilingMbps(mbps) }
    }
}
