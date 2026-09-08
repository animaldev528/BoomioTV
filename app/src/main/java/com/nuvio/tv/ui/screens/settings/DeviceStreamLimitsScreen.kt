@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.CodecFamily
import com.nuvio.tv.core.streams.CodecLimitConfig
import com.nuvio.tv.core.streams.DeviceStreamLimits
import com.nuvio.tv.data.local.DeviceStreamLimitsDataStore
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * "Device stream limits" settings pane (Stream quality rail category, inline).
 *
 * Lets the user declare, per codec family, whether the device decodes it at all
 * and a bitrate ceiling in Mbps. All families start enabled at a sensible
 * ceiling (master ON) so an upgrade hides nothing except over-ceiling streams —
 * the per-codec checkbox is the "I don't want this codec at all" escape hatch
 * (bsf hard-drops a declared-but-unchecked family), and the ceiling is what
 * filters a family's high-bitrate streams.
 */
@Composable
fun DeviceStreamLimitsContent(
    viewModel: DeviceStreamLimitsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val limits by viewModel.settings.collectAsStateWithLifecycle(
        initialValue = DeviceStreamLimits.defaults()
    )

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_stream_quality),
            subtitle = stringResource(R.string.settings_stream_quality_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "stream_limits_master") {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_stream_quality_master_title),
                            subtitle = stringResource(R.string.settings_stream_quality_master_subtitle),
                            checked = limits.enabled,
                            onToggle = { viewModel.setMasterEnabled(!limits.enabled) },
                            modifier = Modifier
                                .padding(top = NuvioTheme.spacing.xxs)
                                .then(
                                    if (initialFocusRequester != null) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }

                    // One enable toggle + one 1 Mbps-stepped ceiling slider per family.
                    CodecFamily.entries.forEach { family ->
                        val cfg = limits.limits.firstOrNull { it.family == family }
                            ?: CodecLimitConfig(family, enabled = true, ceilingMbps = family.defaultCeilingMbps)
                        item(key = "codec_toggle_${family.canonical}") {
                            SettingsToggleRow(
                                title = family.label,
                                subtitle = stringResource(
                                    if (cfg.enabled) {
                                        R.string.settings_stream_quality_codec_on_subtitle
                                    } else {
                                        R.string.settings_stream_quality_codec_off_subtitle
                                    }
                                ),
                                checked = cfg.enabled,
                                onToggle = { viewModel.setCodecEnabled(family, !cfg.enabled) },
                                enabled = limits.enabled
                            )
                        }
                        item(key = "codec_ceiling_${family.canonical}") {
                            SliderSettingsItem(
                                icon = null,
                                title = stringResource(
                                    R.string.settings_stream_quality_ceiling_title,
                                    family.label
                                ),
                                value = cfg.ceilingMbps,
                                valueText = stringResource(
                                    R.string.settings_stream_quality_mbps_format,
                                    cfg.ceilingMbps
                                ),
                                minValue = DeviceStreamLimitsDataStore.MIN_CEILING_MBPS,
                                maxValue = DeviceStreamLimitsDataStore.MAX_CEILING_MBPS,
                                step = 1,
                                onValueChange = { mbps -> viewModel.setCodecCeilingMbps(family, mbps) },
                                enabled = cfg.enabled && limits.enabled
                            )
                        }
                    }

                    // Reserved catch-all for content whose codec family bsf can't
                    // classify into a declared row (unknown codecs, e.g. high-bitrate
                    // UHD BD remuxes whose filenames never spell a codec). Not a
                    // CodecFamily member — it appends the reserved `other` key to
                    // codecCapsMbps only, never to the codecs allow-list.
                    item(key = "codec_toggle_${DeviceStreamLimits.OTHER_CANONICAL}") {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_stream_quality_other_title),
                            subtitle = stringResource(R.string.settings_stream_quality_other_subtitle),
                            checked = limits.otherEnabled,
                            onToggle = { viewModel.setOtherEnabled(!limits.otherEnabled) },
                            enabled = limits.enabled
                        )
                    }
                    item(key = "codec_ceiling_${DeviceStreamLimits.OTHER_CANONICAL}") {
                        SliderSettingsItem(
                            icon = null,
                            title = stringResource(R.string.settings_stream_quality_other_ceiling_title),
                            value = limits.otherMbps,
                            valueText = stringResource(
                                R.string.settings_stream_quality_mbps_format,
                                limits.otherMbps
                            ),
                            minValue = DeviceStreamLimitsDataStore.MIN_CEILING_MBPS,
                            maxValue = DeviceStreamLimitsDataStore.MAX_CEILING_MBPS,
                            step = 1,
                            onValueChange = { mbps -> viewModel.setOtherCeilingMbps(mbps) },
                            enabled = limits.otherEnabled && limits.enabled
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
}
