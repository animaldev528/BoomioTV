@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.hub

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.home.CategoryRow
import com.nuvio.tv.ui.screens.home.DrillTarget
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Movies/TV hub as a group ribbon over the active group's rows. One tab per
 * `hub-` catalog of the kind (Genre · Years · Documentaries · Networks ·
 * Superhero · Extras · Studios), ordered by rank; the content area below shows
 * only the selected group's coarse sub-rows, one horizontal [CategoryRow] each
 * (their trailing "More Like This ▸" tile opens the finer drill rows). Non-hub
 * catalogs of the kind append as trailing single-row tabs so nothing previously
 * reachable disappears.
 *
 * Focus: the selected tab is the ribbon entry point reached by Up from content;
 * Left/Right on the ribbon switches groups (selection follows tab focus, so the
 * content below swaps live); Down from a tab enters that group's rows. On
 * arrival the screen lands on the first poster of the first group (content-first,
 * like the old stacked browser), with the ribbon one Up away.
 */
@Composable
fun HubBrowseScreen(
    kind: HubKind,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToDrillDown: (DrillTarget) -> Unit,
    onBackPress: () -> Unit,
    viewModel: HubBrowseViewModel = hiltViewModel()
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(kind) { viewModel.initialize(kind) }
    BackHandler { onBackPress() }

    val titleText = stringResource(
        when (kind) {
            HubKind.MOVIES -> R.string.nav_movies
            HubKind.TV -> R.string.nav_tv
            HubKind.ANIME -> R.string.nav_anime
        }
    )

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
                text = titleText,
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioTheme.colors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        when {
            uiState.isLoading && uiState.sections.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.isEmpty -> {
                EmptyScreenState(
                    title = stringResource(R.string.hub_empty_title),
                    subtitle = stringResource(R.string.hub_empty_subtitle),
                    icon = Icons.Default.GridView
                )
            }

            else -> {
                // Weight lives here — this branch is inside the screen's Column,
                // so the ribbon + rows area takes the remaining vertical space.
                HubRibbonContent(
                    sections = uiState.sections,
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToDrillDown = onNavigateToDrillDown,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Poster-row left margin shared with [CategoryRow]'s labels/posters. */
private val RowEdge = 52.dp

/**
 * The ribbon + the active group's rows. [sections] stream in rank order, so the
 * tab row grows as groups arrive; a single group renders without a ribbon.
 */
@Composable
private fun HubRibbonContent(
    sections: List<HubBrowseViewModel.HubBrowseSection>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToDrillDown: (DrillTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    val activeIndex = selectedIndex.coerceIn(0, sections.lastIndex)
    val active = sections[activeIndex]

    val showRibbon = sections.size > 1

    // One requester per tab so the ribbon's Up return and Back can land on the
    // selected tab regardless of where the ribbon is scrolled.
    val tabFocusRequesters = remember(sections.size) { sections.indices.map { FocusRequester() } }
    val contentListState = rememberLazyListState()
    // Attached to the active group's first poster so entry focus lands in content.
    val firstPosterFocusRequester = remember { FocusRequester() }
    var entryFocusRequested by remember { mutableStateOf(false) }
    // True while a poster in the very top content row holds focus — the only
    // place where Up should leave content for the ribbon.
    var topRowFocused by remember { mutableStateOf(false) }

    // Switching groups starts the content list at the top of the new group.
    LaunchedEffect(contentListState, activeIndex) {
        contentListState.scrollToItem(0)
    }

    // Land entry focus on the active group's first poster (once per screen
    // entry), so the user browses content right away; the ribbon is one Up away.
    LaunchedEffect(activeIndex, sections.size, active.rows) {
        if (!entryFocusRequested && active.rows.isNotEmpty()) {
            entryFocusRequested = true
            repeat(2) { withFrameNanos { } }
            runCatching { firstPosterFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        if (showRibbon) {
            TabRow(
                selectedTabIndex = activeIndex,
                modifier = Modifier
                    .padding(horizontal = RowEdge)
                    .focusRestorer {
                        tabFocusRequesters.getOrNull(activeIndex) ?: FocusRequester.Default
                    }
            ) {
                sections.forEachIndexed { index, section ->
                    Tab(
                        selected = index == activeIndex,
                        onFocus = { selectedIndex = index },
                        onClick = { selectedIndex = index },
                        modifier = tabFocusRequesters.getOrNull(index)
                            ?.let { Modifier.focusRequester(it) }
                            ?: Modifier
                    ) {
                        Text(
                            text = section.groupName,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 220.dp)
                                .padding(
                                    horizontal = NuvioTheme.spacing.lg,
                                    vertical = NuvioTheme.spacing.sm
                                )
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = contentListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // Up from the top content row returns to the SELECTED tab — not
                // whichever chip happens to sit nearest above the poster's column.
                .onPreviewKeyEvent { keyEvent ->
                    val fromTopRow = topRowFocused &&
                        contentListState.firstVisibleItemIndex == 0
                    if (showRibbon && fromTopRow &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.key == Key.DirectionUp
                    ) {
                        val tab = tabFocusRequesters.getOrNull(activeIndex)
                            ?: return@onPreviewKeyEvent false
                        runCatching { tab.requestFocus() }
                        true
                    } else {
                        false
                    }
                },
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            itemsIndexed(
                items = active.rows,
                key = { _, row -> "row_${active.id}_${row.row.catalogId}" }
            ) { rowIndex, rowState ->
                CategoryRow(
                    row = rowState.row,
                    drill = rowState.drill,
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToDrillDown = onNavigateToDrillDown,
                    modifier = if (rowIndex == 0) {
                        Modifier.onFocusChanged { topRowFocused = it.hasFocus }
                    } else {
                        Modifier
                    },
                    firstItemFocusRequester = if (rowIndex == 0) firstPosterFocusRequester else null
                )
            }
        }
    }
}
