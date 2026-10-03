@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvNowNext
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The IPTV section: a live channel list with now/next, plus the device-code
 * pairing surface the section depends on.
 *
 * Pairing lives inside this screen rather than on its own route because it is
 * not a destination a viewer chooses — it is the state this section is in until
 * someone approves the TV. One screen, one place that decides what to show.
 */
@Composable
fun IptvScreen(
    viewModel: IptvViewModel = hiltViewModel(),
    onPlayChannel: (playlistUrl: String, channel: IptvChannel) -> Unit = { _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        when {
            !uiState.configured -> Message(
                title = "IPTV is not set up on this device",
                body = "This build has no live-TV service configured."
            )

            !uiState.paired -> PairingPane(
                pairing = uiState.pairing,
                loading = uiState.loading,
                error = uiState.error,
                onPair = viewModel::startPairing
            )

            uiState.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            // Paired but following nothing. This is a deliberate state, not a
            // failure, so it says what to do rather than showing an empty list.
            uiState.unscoped -> Message(
                title = "No channel groups yet",
                body = "Choose which channel groups to follow in the Boomio dashboard, then come back."
            )

            else -> ChannelList(
                channels = uiState.channels,
                guide = uiState.guide,
                error = uiState.error,
                onSelect = { channel ->
                    viewModel.tune(
                        streamId = channel.streamId,
                        onReady = { url -> onPlayChannel(url, channel) },
                        onError = { /* surfaced via uiState.error */ }
                    )
                }
            )
        }
    }
}

private val ErrorRed = Color(0xFFE5484D)

@Composable
private fun Message(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun PairingPane(
    pairing: IptvPairingUi?,
    loading: Boolean,
    error: String?,
    onPair: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            pairing == null -> {
                Text(
                    "Pair this TV",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Live TV needs this TV approved once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(Modifier.height(24.dp))
                PairingButton("Get a pairing code", loading, onPair)
            }

            // A device code lives five minutes, so running out is ordinary —
            // it offers a fresh code rather than reading like a failure.
            pairing.expired -> {
                Text(
                    "That code expired",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pairing codes last five minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(Modifier.height(24.dp))
                PairingButton("Get a new code", false, onPair)
            }

            else -> {
                Text("Enter this code", style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary)
                Spacer(Modifier.height(12.dp))
                Text(
                    pairing.userCode,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(Modifier.height(20.dp))
                Text("at", style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                Text(
                    pairing.verificationUri,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Waiting for approval…", color = NuvioTheme.colors.TextSecondary)
                }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(20.dp))
            Text(error, color = ErrorRed)
        }
    }
}

@Composable
private fun PairingButton(label: String, disabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = { if (!disabled) onClick() },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (disabled) NuvioTheme.colors.TextDisabled else NuvioTheme.colors.TextPrimary
            )
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<IptvChannel>,
    guide: Map<String, IptvNowNext>,
    error: String?,
    onSelect: (IptvChannel) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Live TV",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.width(12.dp))
            Text("${channels.size} channels", color = NuvioTheme.colors.TextSecondary)
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = ErrorRed)
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = channels, key = { it.streamId }) { channel ->
                ChannelRow(
                    channel = channel,
                    nowNext = guide[channel.streamId],
                    onClick = { onSelect(channel) }
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: IptvChannel,
    nowNext: IptvNowNext?,
    onClick: () -> Unit
) {
    val now = nowNext?.now
    val progress = now?.progress(System.currentTimeMillis())

    // No manual focus state here: Card's focusedContainerColor/focusedBorder
    // already track it, so a local isFocused flag would only duplicate the Card.
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(260.dp)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1
                )
                // The panel's guide covers only ~14% of channels, so "no guide
                // data" is the common case and must not look like an error.
                Text(
                    if (channel.hasEpg) "Guide" else "No guide data",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }

            Spacer(Modifier.width(24.dp))

            Column(Modifier.fillMaxWidth()) {
                Text(
                    now?.title ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1
                )
                Text(
                    buildString {
                        nowNext?.next?.title?.let { append("Next: ").append(it) }
                        if (progress != null && progress > 0f) {
                            if (isNotEmpty()) append("  ·  ")
                            append("${(progress * 100).toInt()}%")
                        }
                    }.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}
