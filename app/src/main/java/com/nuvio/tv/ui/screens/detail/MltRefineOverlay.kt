package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.boomio.MltMood
import com.nuvio.tv.core.boomio.MltRefinement
import com.nuvio.tv.core.boomio.MltShape
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The "refine" sheet behind More Like This: a handful of chips picked with the D-pad that
 * turn "shows like Severance" into "shows like Severance, but lighter, movies only".
 *
 * Full screen on purpose — the stock dialog cannot scroll, and seven chips already overflow
 * a 540 dp TV.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MltRefineOverlay(
    title: String,
    seedGenres: List<String>,
    initialRefinement: MltRefinement?,
    onDismiss: () -> Unit,
    onApply: (MltRefinement) -> Unit
) {
    val seedGenreSet = remember(seedGenres) { seedGenres.toSet() }
    var genres by remember(seedGenreSet) {
        mutableStateOf(initialRefinement?.genres ?: seedGenreSet)
    }
    var moods by remember { mutableStateOf(initialRefinement?.moods ?: emptySet<MltMood>()) }
    var shapes by remember { mutableStateOf(initialRefinement?.shapes ?: emptySet<MltShape>()) }

    val firstChipFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        firstChipFocusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF00A0A0A))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 64.dp, vertical = 36.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = stringResource(R.string.detail_mlt_refine_title, title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary
                )

                if (seedGenres.isNotEmpty()) {
                    ChipGroup(label = stringResource(R.string.detail_mlt_refine_group_genres)) {
                        seedGenres.forEachIndexed { index, genre ->
                            MltChip(
                                label = genre,
                                selected = genre in genres,
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstChipFocusRequester)
                                } else {
                                    Modifier
                                },
                                onClick = {
                                    genres = if (genre in genres) genres - genre else genres + genre
                                }
                            )
                        }
                    }
                }

                ChipGroup(label = stringResource(R.string.detail_mlt_refine_group_feel)) {
                    MltMood.entries.forEach { mood ->
                        MltChip(
                            label = moodLabel(mood),
                            selected = mood in moods,
                            modifier = if (seedGenres.isEmpty() && mood == MltMood.entries.first()) {
                                Modifier.focusRequester(firstChipFocusRequester)
                            } else {
                                Modifier
                            },
                            onClick = {
                                moods = when {
                                    mood in moods -> moods - mood
                                    moods.size >= MAX_MOODS -> moods
                                    else -> moods + mood
                                }
                            }
                        )
                    }
                }

                ChipGroup(label = stringResource(R.string.detail_mlt_refine_group_shape)) {
                    MltShape.entries.forEach { shape ->
                        MltChip(
                            label = shapeLabel(shape),
                            selected = shape in shapes,
                            onClick = {
                                shapes = when {
                                    shape in shapes -> shapes - shape
                                    else -> {
                                        val exclusive = when (shape) {
                                            MltShape.MOVIES_ONLY -> shapes - MltShape.SHOWS_ONLY
                                            MltShape.SHOWS_ONLY -> shapes - MltShape.MOVIES_ONLY
                                            else -> shapes
                                        }
                                        if (exclusive.size >= MAX_SHAPES) exclusive else exclusive + shape
                                    }
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onApply(MltRefinement(genres, moods, shapes)) },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.TextPrimary,
                            contentColor = NuvioTheme.colors.Background
                        )
                    ) {
                        Text(stringResource(R.string.detail_mlt_refine_apply))
                    }
                    Button(
                        onClick = {
                            genres = seedGenreSet
                            moods = emptySet()
                            shapes = emptySet()
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.detail_mlt_refine_reset))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(
    label: String,
    content: @Composable FlowRowScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextTertiary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

/**
 * A pill chip built on the tv-material3 clickable Surface. The Surface is what makes the
 * remote's select button work: a bare `Modifier.focusable()` Box only ever sees ENTER, so a
 * DPAD_CENTER press silently did nothing and the sheet looked unresponsive.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MltChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pill = RoundedCornerShape(percent = 50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                NuvioTheme.colors.FocusRing.copy(alpha = 0.22f)
            } else {
                NuvioTheme.colors.BackgroundCard
            },
            contentColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            focusedContainerColor = if (selected) {
                NuvioTheme.colors.FocusRing.copy(alpha = 0.34f)
            } else {
                NuvioTheme.colors.FocusBackground
            },
            focusedContentColor = NuvioTheme.colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) {
                        NuvioTheme.colors.FocusRing.copy(alpha = 0.65f)
                    } else {
                        NuvioTheme.colors.Border
                    }
                ),
                shape = pill
            ),
            focusedBorder = Border(border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing), shape = pill)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun moodLabel(mood: MltMood): String = when (mood) {
    MltMood.LIGHTER -> stringResource(R.string.detail_mlt_mood_lighter)
    MltMood.DARKER -> stringResource(R.string.detail_mlt_mood_darker)
    MltMood.FUNNIER -> stringResource(R.string.detail_mlt_mood_funnier)
    MltMood.SCARIER -> stringResource(R.string.detail_mlt_mood_scarier)
    MltMood.MORE_ACTION -> stringResource(R.string.detail_mlt_mood_more_action)
    MltMood.MORE_MYSTERY -> stringResource(R.string.detail_mlt_mood_more_mystery)
    MltMood.SLOWER -> stringResource(R.string.detail_mlt_mood_slower)
}

@Composable
private fun shapeLabel(shape: MltShape): String = when (shape) {
    MltShape.NEWER -> stringResource(R.string.detail_mlt_shape_newer)
    MltShape.OLDER -> stringResource(R.string.detail_mlt_shape_older)
    MltShape.MOVIES_ONLY -> stringResource(R.string.detail_mlt_shape_movies_only)
    MltShape.SHOWS_ONLY -> stringResource(R.string.detail_mlt_shape_shows_only)
    MltShape.NO_ANIME -> stringResource(R.string.detail_mlt_shape_no_anime)
}

private const val MAX_MOODS = 3
private const val MAX_SHAPES = 2
