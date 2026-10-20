package com.nuvio.tv.ui.components.posteroptions

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PinnedHomeRow
import com.nuvio.tv.domain.model.PinnedRowKind
import com.nuvio.tv.domain.model.TastePick
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation

/**
 * What the long-pressed tile's *row* is, when that row is something Home can host.
 *
 * The tile alone is not enough to pin anything: adding a Trakt list needs the list, adding an
 * episode search needs the term. Screens that know their row hand this in with `show`, and it
 * is what turns the dialog's "Add to Home" into a concrete [PinnedHomeRow].
 */
@Immutable
data class PosterOptionsRowContext(
    val kind: PinnedRowKind,
    val title: String,
    val traktListId: Long? = null,
    val traktMediaType: String? = null,
    val traktSortBy: String? = null,
    val traktSortHow: String? = null,
    val searchTerm: String? = null,
    val catalogType: String? = null,
    val addonBaseUrl: String? = null,
    val addonId: String? = null,
    val catalogId: String? = null
) {
    fun toPinnedRow(): PinnedHomeRow {
        val row = PinnedHomeRow(
            id = "",
            kind = kind,
            title = title,
            traktListId = traktListId,
            traktMediaType = traktMediaType,
            traktSortBy = traktSortBy,
            traktSortHow = traktSortHow,
            searchTerm = searchTerm,
            catalogType = catalogType,
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            catalogId = catalogId
        )
        return row.copy(id = PinnedHomeRow.idFor(kind, row.stableKey))
    }

    companion object {
        fun episodeSearch(
            searchTerm: String,
            addonBaseUrl: String,
            addonId: String,
            catalogId: String,
            catalogType: String
        ): PosterOptionsRowContext = PosterOptionsRowContext(
            kind = PinnedRowKind.EPISODE_SEARCH,
            title = searchTerm,
            searchTerm = searchTerm,
            catalogType = catalogType,
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            catalogId = catalogId
        )

        fun traktList(
            title: String,
            traktListId: Long,
            traktMediaType: String,
            sortBy: String? = null,
            sortHow: String? = null
        ): PosterOptionsRowContext = PosterOptionsRowContext(
            kind = PinnedRowKind.TRAKT_LIST,
            title = title,
            traktListId = traktListId,
            traktMediaType = traktMediaType,
            traktSortBy = sortBy,
            traktSortHow = sortHow
        )
    }
}

@Immutable
data class PosterOptionsState(
    val target: MetaPreview? = null,
    /**
     * The row the pressed tile came from, when that row can be pinned to Home. Null on every
     * surface where "Add to Home" would be meaningless (Continue Watching, a library tab that
     * is not a Trakt list, search results that are not an episode search).
     */
    val rowContext: PosterOptionsRowContext? = null,
    /** The original item ID before canonicalization (e.g. "tmdb:123"). Used for optimistic badge updates. */
    val originalItemId: String? = null,
    val addonBaseUrl: String = "",
    val isInLibrary: Boolean = false,
    val isWatched: Boolean = false,
    val isLibraryPending: Boolean = false,
    val isWatchedPending: Boolean = false,
    // Like-bootstrap (movie/series only). likeVisible is the profile gate
    // (active profile opted in and not kids); likeTarget is the resolved
    // movie:<tmdb>/series:<tmdb> identity (null when the tile isn't likable).
    val likeVisible: Boolean = false,
    val likeTarget: TastePick? = null,
    val isLiked: Boolean = false,
    val isLikePending: Boolean = false,
    /** "Not interested" is in flight for this target. */
    val isNotInterestedPending: Boolean = false,
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val libraryListTabs: List<LibraryListTab> = emptyList(),
    val listPickerActive: Boolean = false,
    val listPickerTitle: String? = null,
    val listPickerContentType: String? = null,
    val listPickerMembership: Map<String, Boolean> = emptyMap(),
    val listPickerPending: Boolean = false,
    val listPickerError: String? = null,
    val removalConfirmations: List<TrackingMembershipRemovalConfirmation> = emptyList()
)
