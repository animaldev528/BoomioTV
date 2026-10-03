package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.boomio.MusicUnavailableReason
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The music button's answer, drawn over the player.
 *
 * Deliberately small and centred rather than a full-screen takeover: the point
 * is to tell the viewer what they are hearing without interrupting what they are
 * watching. It closes on BACK or centre, and playback is never paused for it.
 *
 * Every state here is a *result*, including the disappointing ones. A `no_match`
 * means the service listened and recognised nothing — the ordinary answer for a
 * scene with no music, and not a fault to apologise for. Only [MusicIdentifyUiState.Unavailable]
 * is a failure, and it says so.
 */
@Composable
fun MusicIdentifyOverlay(
    visible: Boolean,
    state: MusicIdentifyUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        dismissOnCenter = true,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(NuvioTheme.spacing.xxl),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.88f))
                    .padding(NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.Center
            ) {
                when (state) {
                    MusicIdentifyUiState.Idle -> Unit
                    MusicIdentifyUiState.Listening -> ListeningBody()
                    is MusicIdentifyUiState.Found -> FoundBody(state)
                    is MusicIdentifyUiState.NoMatch -> NoticeBody(
                        title = stringResource(statusTitleRes(state.status)),
                        body = stringResource(statusHintRes(state.status))
                    )
                    MusicIdentifyUiState.RateLimited -> NoticeBody(
                        title = stringResource(R.string.music_rate_limited),
                        body = stringResource(R.string.music_rate_limited_hint)
                    )
                    is MusicIdentifyUiState.Unavailable -> NoticeBody(
                        title = stringResource(unavailableTitleRes(state.reason)),
                        body = stringResource(unavailableHintRes(state.reason))
                    )
                }
            }
        }
    }
}

@Composable
private fun ListeningBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = Color.White,
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Column {
            Text(
                text = stringResource(R.string.music_listening),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.music_listening_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun FoundBody(state: MusicIdentifyUiState.Found) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            val artwork = state.artworkUrl
            if (artwork.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp)
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artwork)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))

        Column {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = state.artist?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.music_artist_unknown),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            state.album?.takeIf { it.isNotBlank() }?.let { album ->
                Text(
                    text = album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

            // Where the answer came from. A cue-index hit cost no provider call,
            // which is worth saying plainly — it is why a re-press is instant.
            val provenance = when {
                state.fromIndex -> stringResource(R.string.music_source_index)
                !state.provider.isNullOrBlank() ->
                    stringResource(R.string.music_source_provider, state.provider)
                else -> null
            }
            provenance?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }

            if (state.cuesInEpisode > 0) {
                Text(
                    text = stringResource(
                        if (state.cuesInEpisode == 1) R.string.music_cues_one else R.string.music_cues_many,
                        state.cuesInEpisode
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }

            // Surfaced only when the server reports that it listened to a
            // different track than the viewer selected. That is the one failure
            // that would otherwise be invisible: the answer looks fine and is
            // about audio they are not hearing.
            if (state.trackDisagrees) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.music_track_mismatch),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFC107)
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeBody(title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/** The server's own word for a miss, mapped to a sentence. */
private fun statusTitleRes(status: String): Int = when (status) {
    "no_stream" -> R.string.music_no_stream
    "no_context" -> R.string.music_no_context
    else -> R.string.music_no_match
}

private fun statusHintRes(status: String): Int = when (status) {
    "no_stream" -> R.string.music_no_stream_hint
    "no_context" -> R.string.music_no_context_hint
    else -> R.string.music_no_match_hint
}

private fun unavailableTitleRes(reason: MusicUnavailableReason): Int = when (reason) {
    MusicUnavailableReason.NOT_PAIRED -> R.string.music_not_paired
    MusicUnavailableReason.NOT_CONFIGURED -> R.string.music_not_configured
    MusicUnavailableReason.NETWORK -> R.string.music_unavailable
}

private fun unavailableHintRes(reason: MusicUnavailableReason): Int = when (reason) {
    MusicUnavailableReason.NOT_PAIRED -> R.string.music_not_paired_hint
    MusicUnavailableReason.NOT_CONFIGURED -> R.string.music_not_configured_hint
    MusicUnavailableReason.NETWORK -> R.string.music_unavailable_hint
}
