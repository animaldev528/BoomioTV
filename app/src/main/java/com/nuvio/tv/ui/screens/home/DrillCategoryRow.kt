package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.ui.components.ContentCard

/**
 * A horizontal content row in the drill/browse rows browsers: the sub-row name
 * on top, its posters in a [LazyRow], and — when [drill] is set — a trailing
 * "More Like This ▸" tile that opens that row's own drill-down.
 *
 * Shared by [CategoryRowsScreen] (one drill catalog) and the Movies/TV hub
 * rows-browser (`ui.screens.hub.HubBrowseScreen`) so both look identical.
 * [firstItemFocusRequester], when non-null, is attached to this row's first
 * poster so a host screen can land entry focus on content beneath its header.
 */
@Composable
internal fun CategoryRow(
    row: CatalogRow,
    drill: DrillTarget?,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToDrillDown: (DrillTarget) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null
) {
    Column(modifier.padding(vertical = 10.dp)) {
        Text(
            row.catalogName,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(start = 52.dp, bottom = 6.dp)
        )
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(row.items) { index, item ->
                ContentCard(
                    item = item,
                    // Host screens that need to land entry focus on this row's
                    // first poster (the hub tab ribbon) forward a requester;
                    // the drill tile, if any, sits after the items.
                    focusRequester = if (index == 0) firstItemFocusRequester else null,
                    onClick = { onNavigateToDetail(item.id, item.apiType, row.addonBaseUrl) }
                )
            }
            if (drill != null) {
                item(key = "drill") {
                    DrillTile(drill, onNavigateToDrillDown)
                }
            }
        }
    }
}

@Composable
private fun DrillTile(target: DrillTarget, onNavigateToDrillDown: (DrillTarget) -> Unit) {
    Box(
        modifier = Modifier
            .width(140.dp)
            .height(210.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222))
            .focusable()
            .clickable { onNavigateToDrillDown(target) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "More Like This ▸",
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(8.dp)
        )
    }
}
