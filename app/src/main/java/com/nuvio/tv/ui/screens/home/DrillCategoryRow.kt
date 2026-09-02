package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DrillTile(target: DrillTarget, onNavigateToDrillDown: (DrillTarget) -> Unit) {
    // tv-material3 Card(onClick=…) — the same clickable primitive the posters
    // use — because a plain focusable/clickable Box does not respond to the
    // TV D-pad CENTER/ENTER in the hub rows-browser.
    Card(
        onClick = { onNavigateToDrillDown(target) },
        modifier = Modifier
            .width(140.dp)
            .height(210.dp),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF222222),
            focusedContainerColor = Color(0xFF303030)
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
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
}
