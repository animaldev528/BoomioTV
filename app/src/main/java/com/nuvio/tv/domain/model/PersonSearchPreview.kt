package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * A single person hit in the Search "People" strip, backed by a client-side TMDB
 * `/search/person`. Clicking a card opens the existing [PersonDetail] CastDetail
 * screen for [tmdbId], so this carries just enough to render the strip card.
 */
@Immutable
data class PersonSearchPreview(
    val tmdbId: Int,
    val name: String,
    /** Absolute profile image URL (or null when TMDB has no photo for the person). */
    val profilePhotoUrl: String?,
    /** Short caption, e.g. "Actor" or "Actor · Forrest Gump / Cast Away" (may be null). */
    val knownForLabel: String?
)
