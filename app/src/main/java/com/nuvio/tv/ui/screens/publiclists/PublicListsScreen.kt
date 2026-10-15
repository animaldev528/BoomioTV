@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.publiclists

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The public Trakt lists a title appears on, as browsable rows: one row per list, each row
 * holding that list's titles.
 *
 * A navigated screen rather than a dialog, which is what makes Back useful — it pops to
 * whatever wall the long-press came from, with that wall's focus restore intact. The tiles
 * inside these rows are ordinary posters: pressing one opens its detail page, and
 * long-pressing one opens the same poster options, so "Public Lists" can be entered from a
 * list it produced.
 */
@Composable
fun PublicListsScreen(
    onNavigateToDetail: (id: String, type: String, addonBaseUrl: String) -> Unit,
    onBackPress: () -> Unit,
    viewModel: PublicListsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val posterCardStyle = PosterCardDefaults.Style

    BackHandler(onBack = onBackPress)

    // Rows arrive one at a time, so focus is requested once, on the first one to land —
    // never again, or a later arrival would yank the ring back to the top mid-browse.
    val firstRowFocusRequester = remember { FocusRequester() }
    var shouldFocusFirstRow by remember { mutableStateOf(true) }
    LaunchedEffect(uiState.rows.size) {
        if (!shouldFocusFirstRow || uiState.rows.isEmpty()) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { firstRowFocusRequester.requestFocus() }
        shouldFocusFirstRow = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = NuvioTheme.spacing.xl)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.xxxl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.poster_options_public_lists),
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val caption = buildCaption(uiState)
        if (caption.isNotEmpty()) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.xxxl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                uiState.error != null -> EmptyScreenState(
                    title = stringResource(R.string.poster_options_public_lists_failed),
                    subtitle = uiState.error.orEmpty(),
                    icon = Icons.Default.GridView
                )
                uiState.isLoading && uiState.rows.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
                uiState.isEmpty -> EmptyScreenState(
                    title = stringResource(R.string.poster_options_public_lists_none),
                    subtitle = stringResource(R.string.poster_options_public_lists_subtitle),
                    icon = Icons.Default.GridView
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NuvioTheme.spacing.xxxl,
                        end = NuvioTheme.spacing.xxxl,
                        top = NuvioTheme.spacing.md,
                        bottom = NuvioTheme.spacing.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                ) {
                    items(
                        items = uiState.rows,
                        key = { row -> row.catalogId }
                    ) { row ->
                        val isFirstRow = uiState.rows.firstOrNull()?.catalogId == row.catalogId
                        CatalogRowSection(
                            catalogRow = row,
                            onItemClick = { id, type, addonBaseUrl ->
                                onNavigateToDetail(id, type, addonBaseUrl)
                            },
                            // A list can hold more titles than the page we fetched, but this
                            // screen has no "see all" destination of its own, and a dead See
                            // all card is worse than an honest horizontal row. The row pages
                            // itself instead, as its last cards come into view.
                            showSeeAll = false,
                            onEndReached = { viewModel.loadMore(row.catalogId) },
                            posterCardStyle = posterCardStyle,
                            // The type is implied by the title that was pressed; spelling it
                            // out on every row header would be eight repetitions of "Movies".
                            showCatalogTypeSuffix = false,
                            onItemLongPress = { item: MetaPreview, addonBaseUrl: String ->
                                viewModel.posterOptions.show(item, addonBaseUrl)
                            },
                            // entryFocusRequester, not rowFocusRequester: it lands on a CARD,
                            // so the first thing the user sees is a focused poster rather
                            // than a focused scroll container with no ring anywhere.
                            entryFocusRequester = if (isFirstRow) firstRowFocusRequester else null
                        )
                    }

                    uiState.pendingListName?.let { pending ->
                        item(key = "public_lists_pending") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(posterCardStyle.height)
                                    .padding(horizontal = NuvioTheme.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LoadingIndicator()
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                                Text(
                                    text = stringResource(
                                        R.string.public_lists_loading_named,
                                        pending
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NuvioTheme.colors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (!uiState.isLoading && uiState.unavailable > 0) {
                        item(key = "public_lists_unavailable") {
                            Text(
                                text = stringResource(
                                    R.string.public_lists_unavailable,
                                    uiState.unavailable
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(
                                    horizontal = NuvioTheme.spacing.sm,
                                    vertical = NuvioTheme.spacing.md
                                )
                            )
                        }
                    }
                }
            }
        }

        val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
        PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                onNavigateToDetail(id, type, addonBaseUrl)
            }
        )
    }
}

/**
 * "The Matrix · 771 public lists include this title", or just the count when the route
 * carried no title. The count is Trakt's own total for the whole set, not the number of rows
 * here, so a capped screen still tells the truth about how many lists exist.
 */
@Composable
private fun buildCaption(uiState: PublicListsUiState): String {
    val count = if (uiState.totalAvailable > 0) {
        stringResource(R.string.poster_options_public_lists_more, uiState.totalAvailable)
    } else {
        ""
    }
    return listOf(uiState.pressedTitle, count)
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")
}
