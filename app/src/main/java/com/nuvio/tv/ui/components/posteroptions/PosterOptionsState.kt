package com.nuvio.tv.ui.components.posteroptions

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TastePick
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation

@Immutable
data class PosterOptionsState(
    val target: MetaPreview? = null,
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
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val libraryListTabs: List<LibraryListTab> = emptyList(),
    val listPickerActive: Boolean = false,
    val listPickerTitle: String? = null,
    val listPickerContentType: String? = null,
    val listPickerMembership: Map<String, Boolean> = emptyMap(),
    val listPickerPending: Boolean = false,
    val listPickerError: String? = null,
    // Read-only "Lists" overview (long-press → Lists). Deliberately separate from
    // the picker above: the picker EDITS membership and has a Save, this only
    // reports which lists already contain the title and has a Close.
    val listsOverviewActive: Boolean = false,
    val listsOverviewTitle: String? = null,
    val listsOverviewContentType: String? = null,
    val listsOverviewPending: Boolean = false,
    val listsOverviewError: String? = null,
    val listsOverviewMembership: Map<String, Boolean> = emptyMap(),
    val removalConfirmations: List<TrackingMembershipRemovalConfirmation> = emptyList()
)
