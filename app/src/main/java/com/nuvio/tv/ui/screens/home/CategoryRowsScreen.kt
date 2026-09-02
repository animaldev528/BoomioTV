package com.nuvio.tv.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator

/**
 * Rows-browser: given a drill-down catalog (rec-<rowId>-drilldown), render each
 * sub-row as a horizontal content row with a trailing "More Like This ▸" tile
 * that recurses into that sub-row's own drill-down.
 */
@Composable
fun CategoryRowsScreen(
    drillCatalogId: String,
    addonId: String,
    addonBaseUrl: String,
    type: String,
    title: String,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToDrillDown: (DrillTarget) -> Unit,
    onBackPress: () -> Unit,
    viewModel: CategoryRowsViewModel = hiltViewModel()
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(drillCatalogId, addonId, type) {
        viewModel.initialize(drillCatalogId, addonId, addonBaseUrl, type, title)
    }
    BackHandler { onBackPress() }

    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Text(
            uiState.title.ifBlank { title },
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(start = 52.dp, bottom = 12.dp)
        )
        when {
            uiState.isLoading -> LoadingIndicator()
            uiState.isEmpty -> EmptyScreenState("Nothing here yet")
            uiState.error != null -> Text(
                uiState.error.orEmpty(),
                modifier = Modifier.padding(52.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                uiState.rows.forEach { rowState ->
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
