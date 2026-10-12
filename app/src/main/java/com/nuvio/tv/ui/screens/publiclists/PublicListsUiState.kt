package com.nuvio.tv.ui.screens.publiclists

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CatalogRow

/**
 * The public-lists browse screen: the public Trakt lists a title appears on, one
 * [CatalogRow] per list.
 *
 * Rows stream in. The reverse lookup answers with the list *refs* in one request, and each
 * ref then costs its own item fetch, so [rows] grows while [pendingListName] names whatever
 * is being fetched right now. That is deliberate rather than batching everything behind one
 * spinner: a list item fetch can be slow or fail on its own, and a row that has arrived
 * should be usable immediately.
 */
@Immutable
data class PublicListsUiState(
    /** The title the user long-pressed, for the header. */
    val pressedTitle: String = "",
    /** One row per public list that contains the title, in the order Trakt ranked them. */
    val rows: List<CatalogRow> = emptyList(),
    /** The list currently being turned into a row; null once nothing is in flight. */
    val pendingListName: String? = null,
    val isLoading: Boolean = true,
    /** How many public lists Trakt says contain the title. May exceed what we render. */
    val totalAvailable: Int = 0,
    /** Lists found but not rendered — empty of this media type, private, or a failed fetch. */
    val unavailable: Int = 0,
    val error: String? = null
) {
    val isEmpty: Boolean get() = !isLoading && error == null && rows.isEmpty()
}
