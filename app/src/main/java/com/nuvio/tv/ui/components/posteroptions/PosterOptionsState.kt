package com.nuvio.tv.ui.components.posteroptions

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TastePick
import com.nuvio.tv.domain.model.TraktPublicListRef
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
    // Public-list lookup ("which public Trakt lists is this on"). Deliberately separate
    // from the listPicker* block above: that one is the caller's OWN lists and is editable,
    // this one is read-only and covers everybody's. It is a public API, so unlike the
    // picker it needs neither a Trakt sign-in nor a non-local library source.
    val publicListsActive: Boolean = false,
    val publicListsTitle: String? = null,
    val publicListsPending: Boolean = false,
    val publicListsError: String? = null,
    val publicLists: List<TraktPublicListRef> = emptyList(),
    val publicListsTotal: Int = 0,
    val removalConfirmations: List<TrackingMembershipRemovalConfirmation> = emptyList()
)
