@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvChannelRef
import com.nuvio.tv.domain.model.IptvGuideHit
import com.nuvio.tv.domain.model.IptvProgramme
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    onPlayChannel: (playlistUrl: String, channel: IptvChannel) -> Unit = { _, _ -> },
    onPlayEpisode: (
        imdbId: String, mediaType: String, title: String,
        season: Int, episode: Int, episodeName: String?
    ) -> Unit = { _, _, _, _, _, _ -> },
    onOpenShow: (
        imdbId: String, mediaType: String, title: String, playOnLoad: Boolean
    ) -> Unit = { _, _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()

    // Deliberately no stopPreview() on dispose. TrailerPlayer hands the pooled
    // player back when it leaves the composition, so nothing keeps streaming —
    // and clearing `tuned` here would empty the preview every time a viewer
    // pressed a channel, went fullscreen, and came back.

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

            uiState.search != null -> SearchResults(
                search = uiState.search!!,
                onPlayHit = { hit, channel ->
                    viewModel.playProgramme(hit.programme, channel) { target ->
                        dispatch(target, onPlayChannel, onPlayEpisode, onOpenShow) { viewModel.openSearchFor(it) }
                    }
                },
                onTune = viewModel::tunePreview,
                onClose = viewModel::clearSearch
            )

            else -> GuideContent(
                state = uiState,
                viewModel = viewModel,
                onPlayChannel = onPlayChannel,
                onPlayEpisode = onPlayEpisode,
                onOpenShow = onOpenShow
            )
        }
    }
}

/** Routes a decided play target to the navigation callbacks the screen was given. */
private fun dispatch(
    target: IptvPlayTarget,
    onPlayChannel: (String, IptvChannel) -> Unit,
    onPlayEpisode: (String, String, String, Int, Int, String?) -> Unit,
    onOpenShow: (String, String, String, Boolean) -> Unit,
    onUnknown: (IptvProgramme) -> Unit
) {
    when (target) {
        is IptvPlayTarget.Episode -> onPlayEpisode(
            target.imdbId, target.mediaType, target.title,
            target.season, target.episode, target.episodeName
        )
        // Play-now landed on the show rather than an episode — open it playing,
        // so the press still starts something instead of parking on a detail page.
        is IptvPlayTarget.Show -> onOpenShow(target.imdbId, target.mediaType, target.title, true)
        is IptvPlayTarget.Unknown -> onUnknown(target.programme)
    }
}

private val ErrorRed = Color(0xFFE5484D)

/** Roughly four rows of the bottom half, which is what the grid is sized for. */
private val RowHeight = 84.dp
private val StripHeight = 76.dp

/**
 * How wide one minute of schedule is. Cells are sized from DURATION against this
 * rather than being uniform, because that is what makes a two-hour film visibly
 * longer than a half-hour bulletin — and, more importantly, it is what keeps
 * every row's time axis aligned with every other row's.
 */
private const val MinutesToDp = 5f

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatClock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TimeFormat)

private fun formatRange(startMs: Long, endMs: Long): String =
    "${formatClock(startMs)} – ${formatClock(endMs)}"

private fun minutesToDp(ms: Long): Dp = ((ms / 60_000f) * MinutesToDp).dp

private fun isSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

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

// ── the guide ──────────────────────────────────────────────────────────────

@Composable
private fun GuideContent(
    state: IptvUiState,
    viewModel: IptvViewModel,
    onPlayChannel: (String, IptvChannel) -> Unit,
    onPlayEpisode: (String, String, String, Int, Int, String?) -> Unit,
    onOpenShow: (String, String, String, Boolean) -> Unit
) {
    // Sampled once per composition of the section. Nothing here counts down, so
    // a ticking clock would only cost recompositions; "now" moves when the
    // viewer changes the day or the screen is reopened.
    val now = remember(state.window.fromMs, state.dayOffsetHours) { System.currentTimeMillis() }

    Column(Modifier.fillMaxSize()) {
        GuideHeader(
            state = state,
            blankChannels = state.window.blankCount(state.channels),
            onView = viewModel::setView,
            onDayOffset = viewModel::setDayOffset
        )

        if (state.error != null) {
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            Text(state.error, color = ErrorRed)
        }

        Spacer(Modifier.height(NuvioTheme.spacing.lg))

        // Top half: what you are looking at, and what is playing.
        Row(Modifier.fillMaxWidth().weight(1f)) {
            DescriptionPane(
                focused = state.focused,
                resolving = state.resolving,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            Spacer(Modifier.width(NuvioTheme.spacing.xl))
            PreviewPane(
                tuned = state.tuned,
                modifier = Modifier.width(460.dp).fillMaxHeight()
            )
        }

        Spacer(Modifier.height(NuvioTheme.spacing.lg))

        // Bottom half: the grid.
        when (state.view) {
            IptvView.Guide -> GuideGrid(
                state = state,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onFocus = viewModel::onProgrammeFocused,
                onChannelFocus = viewModel::onChannelFocused,
                onTune = viewModel::tunePreview,
                onPlay = { programme, channel ->
                    viewModel.playProgramme(programme, channel) { target ->
                        dispatch(target, onPlayChannel, onPlayEpisode, onOpenShow) {
                            viewModel.openSearchFor(it)
                        }
                    }
                },
                onLongPress = { programme, _ ->
                    // A matched programme goes to itself; an unmatched one has
                    // nothing to go to, so it searches the guide text. Either
                    // way the press does something.
                    val match = programme.match
                    if (match != null && match.isNavigable) {
                        // "Go to" browses the show — it must not start playing,
                        // or holding OK would be indistinguishable from pressing it.
                        onOpenShow(match.imdbId!!, match.mediaType!!, programme.displayTitle, false)
                    } else {
                        viewModel.openSearchFor(programme)
                    }
                }
            )

            IptvView.Channels -> ChannelList(
                channels = state.channels,
                guide = state.nowNext(now),
                modifier = Modifier.fillMaxWidth().weight(1f),
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

@Composable
private fun GuideHeader(
    state: IptvUiState,
    blankChannels: Int,
    onView: (IptvView) -> Unit,
    onDayOffset: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Live TV",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            Text(
                buildString {
                    append("${state.channels.size} channels")
                    // Blank rows are marked rather than hidden: the panel's guide
                    // covers well under half of a household's channels, and
                    // silently dropping the rest reads as a bug.
                    if (blankChannels > 0) append(" · $blankChannels without guide data")
                },
                color = NuvioTheme.colors.TextTertiary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.weight(1f))
            Chip("Guide", selected = state.view == IptvView.Guide) { onView(IptvView.Guide) }
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            Chip("Channels", selected = state.view == IptvView.Channels) { onView(IptvView.Channels) }
        }

        Spacer(Modifier.height(NuvioTheme.spacing.md))

        // The day stepper. The panel's guide reaches about 50 hours, so the steps
        // tile that horizon rather than offering days it cannot serve.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "From",
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary
            )
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            val base = System.currentTimeMillis()
            DaySteps.forEachIndexed { index, offset ->
                if (index > 0) Spacer(Modifier.width(NuvioTheme.spacing.xs))
                Chip(
                    label = if (offset == 0) "Now" else formatClock(base + offset * 3_600_000L),
                    selected = state.dayOffsetHours == offset
                ) { onDayOffset(offset) }
            }
        }
    }
}

/** Six-hour steps across the measured ~50h horizon. */
private val DaySteps = listOf(0, 6, 12, 18, 24, 30, 36, 42, 48)

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.BackgroundElevated
            else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = if (selected) {
                Border(border = NuvioTheme.focusRing.border(1.dp), shape = RoundedCornerShape(8.dp))
            } else {
                Border.None
            },
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(8.dp))
        ),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp))
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
            )
        }
    }
}

/**
 * What the focused programme is, in full.
 *
 * This pane is the ONLY thing focus drives. It deliberately does not tune — the
 * edge is a single-slot tuner, so following focus would retune on every keypress
 * and collide with the live-party tuner lock.
 */
@Composable
private fun DescriptionPane(focused: IptvFocused?, resolving: Boolean, modifier: Modifier) {
    Column(modifier) {
        if (focused == null) {
            Text(
                "Browse the guide",
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            Text(
                "Move across a row to read what is on, and press OK to play it. " +
                    "Hold OK on a programme to go to the show itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary
            )
            return@Column
        }

        val programme = focused.programme
        if (programme == null) {
            // Focus is on a channel rather than a programme: a rail, or a
            // channel the guide carries nothing for. Both still tune, so name
            // the channel rather than leaving the last programme's description
            // on screen as if it were what the ring is on.
            Text(
                focused.channel.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2
            )
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Text(
                if (focused.channel.hasEpg) {
                    "Nothing on in this window."
                } else {
                    "No guide data for this channel."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.height(NuvioTheme.spacing.md))
            Text(
                "Press OK to watch.",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
            return@Column
        }
        val match = programme.match

        Text(
            focused.channel.name,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextTertiary,
            maxLines = 1
        )
        Spacer(Modifier.height(NuvioTheme.spacing.xs))
        Text(
            programme.displayTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2
        )
        Spacer(Modifier.height(NuvioTheme.spacing.xs))
        Text(
            formatRange(programme.startMs, programme.endMs),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )

        programme.episodeLabel?.let { label ->
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.Primary,
                maxLines = 1
            )
        }

        if (match != null) {
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                match.mediaType?.let { Badge(it.replaceFirstChar(Char::uppercase)) }
                match.year?.let { Badge(it.toString()) }
                if (match.hasEpisode) Badge("Episode known")
            }
        }

        Spacer(Modifier.height(NuvioTheme.spacing.md))
        Text(
            programme.description?.takeIf { it.isNotBlank() }
                ?: "No description for this programme.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 8
        )

        Spacer(Modifier.height(NuvioTheme.spacing.md))
        if (resolving) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                Spacer(Modifier.width(NuvioTheme.spacing.sm))
                Text(
                    "Finding this episode…",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        } else {
            Text(
                when {
                    match == null -> "Press OK to search the guide for this."
                    match.hasEpisode -> "Press OK to play this episode."
                    else -> "Press OK to play · hold OK to open the show."
                },
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        Modifier
            .padding(end = NuvioTheme.spacing.sm)
            .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

/**
 * Live video of the channel that is TUNED — not the one that is focused.
 *
 * The distinction is the whole reason this pane can exist at all: the edge owns
 * a single upstream connection, so a preview that followed the D-pad would
 * retune on every keypress and fight the live-party tuner lock. It changes only
 * when the viewer explicitly tunes a channel.
 */
@Composable
private fun PreviewPane(tuned: IptvTuned?, modifier: Modifier) {
    Box(
        modifier
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(12.dp))
    ) {
        if (tuned == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.lg),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Nothing playing",
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    "Press a channel name to watch it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
        } else {
            // The shared inline player, the same surface the home hero and
            // focused poster cards use — it borrows the app's pooled ExoPlayer
            // rather than creating a second one, and hands it back on dispose.
            TrailerPlayer(
                trailerUrl = tuned.playlistUrl,
                isPlaying = true,
                onEnded = { },
                cropToFill = true,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(NuvioTheme.spacing.md)
                    .background(NuvioTheme.colors.Scrim, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    tuned.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1
                )
            }
        }
    }
}

// ── the grid ───────────────────────────────────────────────────────────────

/** One strip element: either a programme cell or the dead air before/after one. */
private data class StripSlot(val programme: IptvProgramme?, val width: Dp)

/**
 * Lays a channel's programmes onto a shared time axis.
 *
 * Sizing every cell from its DURATION, and filling the gaps between them, is what
 * keeps all the rows aligned: two channels showing the same 20:00 bulletin put it
 * at the same x-offset, which is the entire point of a grid over a list. A strip
 * of uniform cells would be cheaper and would not be a guide.
 */
private fun buildStrip(windowStartMs: Long, programmes: List<IptvProgramme>): List<StripSlot> {
    if (programmes.isEmpty()) return emptyList()
    // A window that never loaded reports 0; falling back to the first programme
    // keeps the strip sane instead of indenting it by 55 years.
    val origin = if (windowStartMs > 0L) windowStartMs else programmes.first().startMs
    val out = mutableListOf<StripSlot>()
    var cursor = origin
    for (programme in programmes) {
        if (programme.startMs > cursor) {
            out += StripSlot(null, minutesToDp(programme.startMs - cursor))
        }
        val end = maxOf(programme.endMs, programme.startMs)
        out += StripSlot(programme, minutesToDp(end - programme.startMs).coerceAtLeast(48.dp))
        cursor = maxOf(cursor, end)
    }
    return out
}

@Composable
private fun GuideGrid(
    state: IptvUiState,
    modifier: Modifier,
    onFocus: (IptvProgramme, IptvChannel) -> Unit,
    onChannelFocus: (IptvChannel) -> Unit,
    onTune: (IptvChannel) -> Unit,
    onPlay: (IptvProgramme, IptvChannel) -> Unit,
    onLongPress: (IptvProgramme, IptvChannel) -> Unit
) {
    // ONE scroll state shared by every row. Rows that scrolled independently
    // would drift out of alignment the moment the viewer moved along one of
    // them, which defeats the grid.
    val timeScroll = rememberScrollState()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        items(items = state.channels, key = { it.streamId }) { channel ->
            ChannelRow(
                channel = channel,
                programmes = state.window.programmes(channel.streamId),
                windowStartMs = state.window.fromMs,
                timeScroll = timeScroll,
                isTuned = state.tuned?.streamId == channel.streamId,
                onFocus = onFocus,
                onChannelFocus = onChannelFocus,
                onTune = onTune,
                onPlay = onPlay,
                onLongPress = onLongPress
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: IptvChannel,
    programmes: List<IptvProgramme>,
    windowStartMs: Long,
    timeScroll: androidx.compose.foundation.ScrollState,
    isTuned: Boolean,
    onFocus: (IptvProgramme, IptvChannel) -> Unit,
    onChannelFocus: (IptvChannel) -> Unit,
    onTune: (IptvChannel) -> Unit,
    onPlay: (IptvProgramme, IptvChannel) -> Unit,
    onLongPress: (IptvProgramme, IptvChannel) -> Unit
) {
    val slots = remember(programmes, windowStartMs) { buildStrip(windowStartMs, programmes) }

    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        ChannelRail(
            channel = channel,
            isTuned = isTuned,
            onTune = { onTune(channel) },
            onFocus = { onChannelFocus(channel) }
        )

        Spacer(Modifier.width(NuvioTheme.spacing.sm))

        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(timeScroll)) {
            if (slots.isEmpty()) {
                // A channel with no guide data is MARKED, not hidden. It still
                // tunes — the guide is a bonus, not a precondition for watching.
                NoGuideCell(
                    onTune = { onTune(channel) },
                    onFocus = { onChannelFocus(channel) }
                )
            } else {
                slots.forEach { slot ->
                    val programme = slot.programme
                    if (programme == null) {
                        Spacer(Modifier.width(slot.width))
                    } else {
                        ProgrammeCell(
                            programme = programme,
                            width = slot.width,
                            onFocus = { onFocus(programme, channel) },
                            onPlay = { onPlay(programme, channel) },
                            onLongPress = { onLongPress(programme, channel) }
                        )
                        Spacer(Modifier.width(NuvioTheme.spacing.xxs))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRail(
    channel: IptvChannel,
    isTuned: Boolean,
    onTune: () -> Unit,
    onFocus: () -> Unit
) {
    Card(
        onClick = onTune,
        modifier = Modifier
            .width(180.dp)
            .fillMaxHeight()
            .onFocusChanged { if (it.isFocused) onFocus() },
        colors = CardDefaults.colors(
            containerColor = if (isTuned) NuvioTheme.colors.BackgroundElevated
            else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = if (isTuned) {
                Border(border = NuvioTheme.focusRing.border(1.dp), shape = RoundedCornerShape(8.dp))
            } else {
                Border.None
            },
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(8.dp))
        ),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.Center) {
            Text(
                channel.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2
            )
            Text(
                when {
                    isTuned -> "Playing"
                    channel.hasEpg -> "Press to watch"
                    else -> "No guide data"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isTuned) NuvioTheme.colors.Primary else NuvioTheme.colors.TextTertiary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun NoGuideCell(onTune: () -> Unit, onFocus: () -> Unit) {
    Card(
        onClick = onTune,
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .onFocusChanged { if (it.isFocused) onFocus() },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(8.dp))
        ),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp))
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                "No guide data",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                maxLines = 1
            )
        }
    }
}

/**
 * One programme. Press plays it; hold goes to the show (or searches the guide,
 * when the programme was never identified).
 *
 * The long-press follows the app's existing card idiom — the shared
 * [rememberLongPressKeyTracker] plus the remote's MENU key — and the
 * `longPressTriggered` guard is what stops a long press also firing the click on
 * release, which would otherwise play a programme the viewer was only asking
 * about.
 */
@Composable
private fun ProgrammeCell(
    programme: IptvProgramme,
    width: Dp,
    onFocus: () -> Unit,
    onPlay: () -> Unit,
    onLongPress: () -> Unit
) {
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    val match = programme.match

    Card(
        onClick = {
            if (longPressTriggered) longPressTriggered = false else onPlay()
        },
        modifier = Modifier
            .width(width)
            .height(StripHeight)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                    native.keyCode == AndroidKeyEvent.KEYCODE_MENU
                ) {
                    longPressTriggered = true
                    onLongPress()
                    return@onPreviewKeyEvent true
                }
                if (longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    // Swallow the release that ended the hold, so the Card's own
                    // click does not also fire and play what was only asked about.
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        longPressTriggered = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    longPressTriggered &&
                    (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                false
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(8.dp))
        ),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp))
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // The canonical name once the programme has been identified, so the
            // grid reads as titles rather than as whatever the panel typed.
            Text(
                programme.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (match != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (match != null) NuvioTheme.colors.TextPrimary
                else NuvioTheme.colors.TextSecondary,
                maxLines = 2
            )
            Text(
                programme.episodeLabel ?: formatClock(programme.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (programme.episodeLabel != null) NuvioTheme.colors.Primary
                else NuvioTheme.colors.TextTertiary,
                maxLines = 1
            )
        }
    }
}

// ── guide search ───────────────────────────────────────────────────────────

/**
 * The guide-text fallback. Reached by holding OK on a programme the pipeline
 * could not identify, and seeded with what that programme calls itself — there is
 * no keyboard, because on a TV a search box you have to type into is worse than
 * no search box at all.
 */
@Composable
private fun SearchResults(
    search: IptvSearchUi,
    onPlayHit: (IptvGuideHit, IptvChannel) -> Unit,
    onTune: (IptvChannel) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Guide search",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            Text(
                "“${search.query}”",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.weight(1f))
            Chip("Back", selected = false, onClick = onClose)
        }

        Spacer(Modifier.height(NuvioTheme.spacing.lg))

        if (search.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (search.error != null) {
            Text(search.error, color = ErrorRed)
            Spacer(Modifier.height(NuvioTheme.spacing.md))
        }

        if (search.programmes.isEmpty() && search.channels.isEmpty()) {
            Message(
                title = "Nothing in the guide",
                body = "The guide has no programme or channel matching that text."
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (search.channels.isNotEmpty()) {
                item {
                    SectionLabel("Channels")
                }
                items(items = search.channels, key = { "ch-${it.streamId}" }) { channel ->
                    SearchRow(
                        title = channel.name,
                        subtitle = if (channel.hasEpg) "Guide available" else "No guide data",
                        onClick = { onTune(channel) }
                    )
                }
            }
            if (search.programmes.isNotEmpty()) {
                item {
                    SectionLabel("Programmes")
                }
                items(items = search.programmes, key = { "pg-${it.programme.title}-${it.programme.startMs}" }) { hit ->
                    val carrier = hit.channels.firstOrNull()
                    SearchRow(
                        title = hit.programme.displayTitle,
                        subtitle = buildString {
                            append(formatRange(hit.programme.startMs, hit.programme.endMs))
                            if (hit.channels.isNotEmpty()) {
                                append(" · ")
                                append(hit.channels.joinToString(", ") { it.name })
                            }
                        },
                        // A search hit carries no stream id of its own — the same
                        // programme airs on several feeds — so playback goes
                        // through the first carrier.
                        onClick = {
                            carrier?.let { ref ->
                                onPlayHit(hit, channelFor(ref, search))
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * A search hit names its carriers by id and name only, while playback needs a
 * full channel. Prefer the catalogue entry we already hold — it carries the icon
 * and guide flags — and fall back to the reference so a hit is still playable.
 */
private fun channelFor(ref: IptvChannelRef, search: IptvSearchUi): IptvChannel =
    search.channels.firstOrNull { it.streamId == ref.streamId }
        ?: IptvChannel(streamId = ref.streamId, name = ref.name)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
    )
}

@Composable
private fun SearchRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(10.dp))
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

// ── the plain channel list (unchanged; kept as the compact presentation) ────

@Composable
private fun ChannelList(
    channels: List<IptvChannel>,
    guide: Map<String, com.nuvio.tv.domain.model.IptvNowNext>,
    modifier: Modifier,
    onSelect: (IptvChannel) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = channels, key = { it.streamId }) { channel ->
            ChannelListRow(
                channel = channel,
                nowNext = guide[channel.streamId],
                onClick = { onSelect(channel) }
            )
        }
    }
}

@Composable
private fun ChannelListRow(
    channel: IptvChannel,
    nowNext: com.nuvio.tv.domain.model.IptvNowNext?,
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
                    now?.displayTitle ?: "—",
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
