package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.PersonSearchPreview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/** Headshot tiles: 2:3 like TMDB profile images, close to the poster-card footprint. */
private val PEOPLE_TILE_WIDTH = 126.dp
/** Minimum caption height so name + caption tiles and name-only tiles align across the strip. */
private val PEOPLE_TILE_CAPTION_MIN_HEIGHT = 56.dp

/**
 * A horizontal "People" strip rendered above the addon catalog rows in search results.
 * Each tile is a TMDB person hit; clicking one opens the person's CastDetail screen.
 *
 * The section is a lighter-weight sibling of [com.nuvio.tv.ui.components.CatalogRowSection]:
 * it deliberately has no per-item focus-restorer machinery. Entry focus is handled by
 * attaching [entryFocusRequester] to whichever card is the focus target, so callers can
 * land search-entry focus on the strip (C3) without the row-internal requesters catalog
 * rows carry.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PeopleRowSection(
    people: List<PersonSearchPreview>,
    onPersonClick: (PersonSearchPreview) -> Unit,
    onPersonFocused: (Int) -> Unit = {},
    entryFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    // Tracks the last card the user focused so entry focus can re-attach there (e.g. after the
    // caller drops focus and asks again). Reset each composition of the section.
    val lastFocusedItemIndex = remember { mutableIntStateOf(-1) }
    val latestOnPersonFocused by rememberUpdatedState(onPersonFocused)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_people_section_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = NuvioTheme.spacing.xxxl, end = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            itemsIndexed(
                items = people,
                key = { _, person -> person.tmdbId },
                contentType = { _, _ -> "person_tile" }
            ) { index, person ->
                val targetIndex = if (lastFocusedItemIndex.intValue >= 0) {
                    lastFocusedItemIndex.intValue
                } else {
                    0
                }
                val isEntryTarget by remember(entryFocusRequester, index, targetIndex) {
                    derivedStateOf {
                        entryFocusRequester != null && index == targetIndex
                    }
                }
                val onClick = remember(person.tmdbId) {
                    { onPersonClick(person) }
                }
                val onFocus = remember(person.tmdbId, index) {
                    { focused: Boolean ->
                        if (focused) {
                            lastFocusedItemIndex.intValue = index
                            latestOnPersonFocused(index)
                        }
                    }
                }

                PersonTile(
                    person = person,
                    onClick = onClick,
                    onFocusChanged = onFocus,
                    modifier = Modifier.then(
                        if (isEntryTarget) Modifier.focusRequester(entryFocusRequester!!) else Modifier
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PersonTile(
    person: PersonSearchPreview,
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.xl)
    val tileBackground = NuvioTheme.colors.BackgroundCard
    val bgPainter = remember(tileBackground) { ColorPainter(tileBackground) }
    Card(
        onClick = onClick,
        modifier = modifier
            .width(PEOPLE_TILE_WIDTH)
            .onFocusChanged { state -> onFocusChanged(state.hasFocus) },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = shape
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.06f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tileBackground)
        ) {
            Column {
                val photo = person.profilePhotoUrl
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .then(if (photo.isNullOrBlank()) Modifier.background(NuvioTheme.colors.SurfaceVariant) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (!photo.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(photo)
                                .crossfade(true)
                                .build(),
                            contentDescription = person.name,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = bgPainter,
                            error = bgPainter,
                            fallback = bgPainter,
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = person.name.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleLarge,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PEOPLE_TILE_CAPTION_MIN_HEIGHT)
                        .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.hairline)
                ) {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (person.knownForLabel != null) {
                        Text(
                            text = person.knownForLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        // Reserve a caption-height line so name-only tiles match caption tiles.
                        Box(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    }
                }
            }
        }
    }
}
