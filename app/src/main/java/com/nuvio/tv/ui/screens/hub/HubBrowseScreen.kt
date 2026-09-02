@file:OptIn(ExperimentalTvMaterial3Api::class)

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.home.CategoryRow
import com.nuvio.tv.ui.screens.home.DrillTarget
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Movies/TV hub as a grouped rows browser (replaces the merged grid + source
 * chips). One section per `hub-` catalog of the kind — Genre first, then
 * Superhero, Year, Extras (Studios last where present) — each with a group
 * header then one horizontal content row per coarse sub-row. The coarse rows
 * are the exact visual of the drill screens ([CategoryRow]); their trailing
 * "More Like This ▸" tile opens [Screen.CategoryRows] for the finer rows.
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    uiState.sections.forEach { section ->
                        item(key = "hdr_${section.id}") {
                            SectionHeader(section.groupName)
                        }
                        items(
                            items = section.rows,
                            key = { row -> "row_${section.id}_${row.row.catalogId}" }
                        ) { rowState ->
                            CategoryRow(
                                row = rowState.row,
                                drill = rowState.drill,
                                onNavigateToDetail = onNavigateToDetail,
                                onNavigateToDrillDown = onNavigateToDrillDown
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Non-focusable group title (Genre / Superhero / Year / Extras) over its rows. */
@Composable
private fun SectionHeader(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.titleLarge,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(start = 52.dp, top = NuvioTheme.spacing.lg, bottom = 2.dp)
    )
}
