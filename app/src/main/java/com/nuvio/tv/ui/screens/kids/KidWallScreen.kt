@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.kids

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.distinctUntilChanged

/** Which full approved-content wall this screen instance shows (see MainActivity KIDS_PROFILE_IDS). */
enum class KidWallKind(val catalogId: String, @StringRes val titleRes: Int) {
    MOVIES(catalogId = "hub-leomovies", titleRes = R.string.nav_movies),
    TV(catalogId = "hub-leoshows", titleRes = R.string.nav_tv)
}

/** Stable focus key for a wall poster (survives page merges; not a grid index). */
private fun kidItemFocusKey(item: MetaPreview): String = "${item.apiType}:${item.id}"

/**
 * Full poster wall of one approved-content catalog (hub-leomovies / hub-leoshows).
 *
 * Leo's Movies/TV drawer entries land here instead of the genre-row browser. The
 * wall lazily pages the whole catalog (infinite scroll) and every tile's
 * long-press opens poster options — the same Add-to-library path Library uses —
 * so OK opens detail/play and long-press adds to his Library/Home wall.
 */
@Composable
fun KidWallScreen(
    kind: KidWallKind,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit,
    onBackPress: () -> Unit,
    viewModel: KidWallViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val items = uiState.items

    BackHandler(onBack = onBackPress)

    LaunchedEffect(kind) {
        viewModel.initialize(kind.catalogId)
    }

    val posterCardStyle = PosterCardDefaults.Style
    val gridState = rememberLazyGridState()

    // Keyed focus restore: remember which poster was focused so returning from
    // Details re-focuses the same title, and the first poster gains focus on load.
    var focusedItemKey by rememberSaveable(kind.catalogId) { mutableStateOf<String?>(null) }
    var shouldRestoreFocus by rememberSaveable(kind.catalogId) { mutableStateOf(true) }
    val restoreFocusRequester = remember { FocusRequester() }

    val focusedItemIndex = remember(items, focusedItemKey) {
        if (items.isEmpty()) 0
        else {
            val key = focusedItemKey
            if (key.isNullOrBlank()) 0
            else items.indexOfFirst { kidItemFocusKey(it) == key }.takeIf { it >= 0 } ?: 0
        }
    }

    // Infinite scroll: fetch the next page as the user nears the bottom.
    LaunchedEffect(gridState, items.size, uiState.hasMore) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to gridState.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 10) {
                    viewModel.loadMore()
                }
            }
    }

    // Initial focus: once the first page lands, focus the previously-selected (or first) poster.
    LaunchedEffect(shouldRestoreFocus, items.size, focusedItemKey) {
        if (!shouldRestoreFocus) return@LaunchedEffect
        if (items.isEmpty()) return@LaunchedEffect
        val targetIndex = focusedItemIndex.coerceIn(0, items.lastIndex)
        val isTargetVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
        if (!isTargetVisible) gridState.animateScrollToItem(targetIndex)
        repeat(2) { withFrameNanos { } }
        try {
            restoreFocusRequester.requestFocus()
            shouldRestoreFocus = false
        } catch (_: IllegalStateException) {
        }
    }

    val showUnavailable = uiState.missingCatalog || uiState.loadError != null

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
                text = stringResource(kind.titleRes),
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioTheme.colors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                showUnavailable -> EmptyScreenState(
                    title = stringResource(R.string.kids_wall_unavailable_title),
                    subtitle = uiState.loadError
                        ?: stringResource(R.string.kids_wall_unavailable_subtitle),
                    icon = Icons.Default.GridView
                )
                uiState.isInitialLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
                items.isEmpty() -> EmptyScreenState(
                    title = stringResource(R.string.catalog_see_all_empty_title),
                    icon = Icons.Default.GridView
                )
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = posterCardStyle.width),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NuvioTheme.spacing.xxxl,
                        end = NuvioTheme.spacing.xxxl,
                        top = NuvioTheme.spacing.md,
                        bottom = NuvioTheme.spacing.xxl
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> kidItemFocusKey(item) }
                    ) { index, item ->
                        val itemFocusKey = kidItemFocusKey(item)
                        val addonBaseUrl = uiState.addonBaseUrl
                        GridContentCard(
                            item = item,
                            posterCardStyle = posterCardStyle,
                            showLabel = true,
                            focusRequester = if (index == focusedItemIndex) restoreFocusRequester else null,
                            onFocused = { focusedItemKey = itemFocusKey },
                            onClick = {
                                focusedItemKey = itemFocusKey
                                if (addonBaseUrl != null) {
                                    onNavigateToDetail(item.id, item.apiType, addonBaseUrl)
                                }
                            },
                            onLongPress = {
                                focusedItemKey = itemFocusKey
                                viewModel.posterOptions.show(item, addonBaseUrl)
                            }
                        )
                    }

                    if (uiState.isLoadingMore) {
                        item(key = "loading_more") {
                            val cardShape = remember(posterCardStyle.cornerRadius) {
                                RoundedCornerShape(posterCardStyle.cornerRadius)
                            }
                            val cardDepthStyle = LocalCardDepthStyle.current
                            Column(modifier = Modifier.width(posterCardStyle.width)) {
                                Card(
                                    onClick = {},
                                    modifier = Modifier
                                        .width(posterCardStyle.width)
                                        .height(posterCardStyle.height)
                                        .then(Modifier.focusProperties { canFocus = false }),
                                    shape = CardDefaults.shape(shape = cardShape),
                                    colors = CardDefaults.colors(
                                        containerColor = NuvioTheme.colors.BackgroundCard,
                                        focusedContainerColor = NuvioTheme.colors.BackgroundCard
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(cardShape)
                                            .then(
                                                Modifier.nuvioCardDepth(
                                                    shape = cardShape,
                                                    surface = CardDepthSurface.POSTERS,
                                                    style = cardDepthStyle
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        LoadingIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val posterOptionsState by viewModel.posterOptions.state.collectAsState()
        PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                onNavigateToDetail(id, type, addonBaseUrl)
            }
        )
    }
}
