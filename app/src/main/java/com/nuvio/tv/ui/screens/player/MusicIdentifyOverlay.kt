@file:OptIn(ExperimentalTvMaterial3Api::class)

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.boomio.MusicUnavailableReason
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Lifts the card clear of the transport row.
 *
 * The control bar is laid out from the bottom of the screen (a `BottomCenter`
 * column in `PlayerScreen`), so a card pinned to the literal corner would sit on
 * top of the play and seek buttons. This is that row plus its padding — a layout
 * measurement, not a design token, which is why it is a local constant.
 */
private val TransportRowInset = 96.dp

/**
 * The music button's answer, drawn over the player.
 *
 * A small card in the bottom-right rather than a centred takeover, and it does
 * **not** dim the picture: the point is to tell the viewer what they are hearing
 * without interrupting what they are watching, and a film that dims every time
 * someone presses the button is itself an interruption. It closes on BACK, and
 * playback is never paused for it.
 *
 * Every state here is a *result*, including the disappointing ones. A `no_match`
 * means the service listened and recognised nothing — the ordinary answer for a
 * scene with no music, and not a fault to apologise for. Only [MusicIdentifyUiState.Unavailable]
 * is a failure, and it says so.
 *
 * When something is found the card also offers to keep it. That is the only
 * interactive thing here, so it is also the only thing that takes focus — and
 * only while there is something left to do: once the track is in the library the
 * action is replaced by a status line, which keeps centre-press free to dismiss
 * the card again rather than landing on a button that would do nothing.
 */
@Composable
fun MusicIdentifyOverlay(
    visible: Boolean,
    state: MusicIdentifyUiState,
    saveState: MusicSaveState,
    onClose: () -> Unit,
    onAddToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val found = state as? MusicIdentifyUiState.Found
    // The action is live only while pressing it would change something.
    val actionAvailable = saveState is MusicSaveState.Idle || saveState is MusicSaveState.Failed
    val actionFocus = remember { FocusRequester() }

    // Centre dismisses, as before — except while the action is live, where it
    // has to reach the button instead.
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        dismissOnCenter = !(found != null && actionAvailable),
        drawBackdrop = false,
        modifier = modifier
    ) {
        LaunchedEffect(visible, found?.title, actionAvailable) {
            if (visible && found != null && actionAvailable) {
                // The scaffold claims focus as it appears; this runs after and
                // retries across frames, so it wins that race rather than
                // depending on which effect composed first.
                actionFocus.requestFocusAfterFrames()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = NuvioTheme.spacing.xxl,
                    end = NuvioTheme.spacing.xxl,
                    bottom = TransportRowInset
                ),
            contentAlignment = Alignment.BottomEnd
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
                    is MusicIdentifyUiState.Found -> FoundBody(
                        state = state,
                        saveState = saveState,
                        onAddToLibrary = onAddToLibrary,
                        actionModifier = Modifier.focusRequester(actionFocus)
                    )
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
private fun FoundBody(
    state: MusicIdentifyUiState.Found,
    saveState: MusicSaveState,
    onAddToLibrary: () -> Unit,
    actionModifier: Modifier = Modifier
) {
    Column {
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

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        SaveAction(saveState = saveState, onAddToLibrary = onAddToLibrary, modifier = actionModifier)
    }
}

/**
 * The one control on this card.
 *
 * A completed save is shown as a line of text rather than a disabled button:
 * there is nothing left to press, and saying so is more legible than a control
 * that looks available and is not.
 */
@Composable
private fun SaveAction(
    saveState: MusicSaveState,
    onAddToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (saveState) {
        MusicSaveState.Idle -> Button(onClick = onAddToLibrary, modifier = modifier) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(text = stringResource(R.string.music_add_to_library))
        }

        MusicSaveState.Saving -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = stringResource(R.string.music_adding),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        MusicSaveState.Saved -> SavedLine(stringResource(R.string.music_in_library))

        MusicSaveState.AlreadySaved -> SavedLine(stringResource(R.string.music_already_in_library))

        is MusicSaveState.Failed -> Column {
            Text(
                text = stringResource(saveFailTitleRes(saveState.reason)),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFC107)
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = stringResource(saveFailHintRes(saveState.reason)),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            // Offered again: a save that failed on a flaky network is worth
            // another try without making the viewer re-identify the song.
            Button(onClick = onAddToLibrary, modifier = modifier) {
                Text(text = stringResource(R.string.music_add_retry))
            }
        }
    }
}

@Composable
private fun SavedLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color(0xFF34D399),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF34D399)
        )
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
    MusicUnavailableReason.NOT_LINKED -> R.string.music_not_linked
    MusicUnavailableReason.NETWORK -> R.string.music_unavailable
}

private fun unavailableHintRes(reason: MusicUnavailableReason): Int = when (reason) {
    MusicUnavailableReason.NOT_PAIRED -> R.string.music_not_paired_hint
    MusicUnavailableReason.NOT_CONFIGURED -> R.string.music_not_configured_hint
    MusicUnavailableReason.NOT_LINKED -> R.string.music_not_linked_hint
    MusicUnavailableReason.NETWORK -> R.string.music_unavailable_hint
}

/** Why a save did not happen, in the viewer's terms. */
private fun saveFailTitleRes(reason: MusicUnavailableReason): Int = when (reason) {
    MusicUnavailableReason.NOT_LINKED -> R.string.music_save_not_linked
    MusicUnavailableReason.NOT_CONFIGURED -> R.string.music_not_configured
    MusicUnavailableReason.NOT_PAIRED -> R.string.music_not_paired
    MusicUnavailableReason.NETWORK -> R.string.music_save_failed
}

private fun saveFailHintRes(reason: MusicUnavailableReason): Int = when (reason) {
    MusicUnavailableReason.NOT_LINKED -> R.string.music_save_not_linked_hint
    MusicUnavailableReason.NOT_CONFIGURED -> R.string.music_not_configured_hint
    MusicUnavailableReason.NOT_PAIRED -> R.string.music_not_paired_hint
    MusicUnavailableReason.NETWORK -> R.string.music_save_failed_hint
}
