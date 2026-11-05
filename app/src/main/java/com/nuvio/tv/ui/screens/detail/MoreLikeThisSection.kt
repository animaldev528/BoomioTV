package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MoreLikeThisSection(
    items: List<MetaPreview>,
    sourceLabel: String? = null,
    refineEnabled: Boolean = false,
    isRefining: Boolean = false,
    isRefined: Boolean = false,
    onRefineClick: () -> Unit = {},
    onClearRefinement: () -> Unit = {},
    posterCardCornerRadius: Dp = NuvioTheme.spacing.md,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    sectionFocusRequester: FocusRequester? = null,
    restoreItemId: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {},
    onItemFocused: (MetaPreview) -> Unit = {},
    onItemClick: (MetaPreview) -> Unit,
    onItemLongPress: (MetaPreview) -> Unit = {},
    isItemWatched: (MetaPreview) -> Boolean = { false }
) {
    if (items.isEmpty()) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val restoreFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    LaunchedEffect(items) {
        val validIds = items.mapTo(mutableSetOf()) { it.id }
        itemFocusRequesters.keys.retainAll(validIds)
    }

    var restorePending by remember { mutableStateOf(false) }

    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken <= 0 || restoreItemId.isNullOrBlank()) {
            restorePending = false
            return@LaunchedEffect
        }
        if (items.none { it.id == restoreItemId }) {
            restorePending = false
            return@LaunchedEffect
        }
        restorePending = true
        restoreFocusRequester.requestFocusAfterFrames()
    }

    val landscapeStyle = remember(posterCardCornerRadius) {
        PosterCardStyle(
            width = 260.dp,
            height = 146.dp,
            cornerRadius = posterCardCornerRadius,
            focusedBorderWidth = NuvioTheme.spacing.xxs,
            focusedScale = 1.02f
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sectionFocusRequester != null) Modifier.focusRequester(sectionFocusRequester) else Modifier)
                .focusRestorer { if (restorePending) restoreFocusRequester else firstItemFocusRequester }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (refineEnabled) {
                item(key = "mlt-refine") {
                    MltActionPill(
                        label = stringResource(R.string.detail_more_like_this_refine),
                        enabled = !isRefining,
                        onClick = onRefineClick
                    )
                }
                if (isRefined) {
                    item(key = "mlt-clear") {
                        MltActionPill(
                            label = stringResource(R.string.detail_more_like_this_clear),
                            enabled = !isRefining,
                            onClick = onClearRefinement
                        )
                    }
                }
            }
            itemsIndexed(
                items = items,
                key = { index, item -> item.id + "|" + item.name + "|" + index }
            ) { index, item ->
                val isRestoreTarget = item.id == restoreItemId
                val isFirstItem = index == 0
                val focusRequester = when {
                    isRestoreTarget -> restoreFocusRequester
                    isFirstItem -> firstItemFocusRequester
                    else -> remember(item.id) { itemFocusRequesters.getOrPut(item.id) { FocusRequester() } }
                }

                Column {
                    GridContentCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = { onItemLongPress(item) },
                        posterCardStyle = landscapeStyle,
                        showLabel = true,
                        imageCrossfade = true,
                        isWatched = isItemWatched(item),
                        focusRequester = focusRequester,
                        upFocusRequester = upFocusRequester,
                        downFocusRequester = downFocusRequester,
                        onFocused = {
                            onItemFocused(item)
                            if (isRestoreTarget && restoreFocusToken > 0) {
                                restorePending = false
                                onRestoreFocusHandled()
                            }
                        }
                    )
                    val year = item.releaseInfo
                    if (!year.isNullOrBlank()) {
                        Text(
                            text = year,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(landscapeStyle.width)
                                .padding(start = NuvioTheme.spacing.xxs, end = NuvioTheme.spacing.xxs, top = NuvioTheme.spacing.xxs)
                        )
                    }
                }
            }
        }

        sourceLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.End)
                        .padding(end = 10.dp, top = NuvioTheme.spacing.xxs, bottom = NuvioTheme.spacing.xxs)
                )
            }
    }
}

/** The small leading pill that opens the refine sheet, and its Clear sibling. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MltActionPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(48.dp),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            disabledContainerColor = NuvioTheme.colors.BackgroundCard.copy(alpha = 0.5f),
            disabledContentColor = NuvioTheme.colors.TextTertiary
        )
    ) {
        Text(label, maxLines = 1)
    }
}
