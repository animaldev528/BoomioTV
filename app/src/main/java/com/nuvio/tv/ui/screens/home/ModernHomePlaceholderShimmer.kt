package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.isPlaceholder

/**
 * A catalog row draws the sweeping placeholder shimmer only while it is still loading and its
 * cards are placeholders. Once the real posters arrive nothing reads the shimmer offset any more.
 */
internal fun showsPlaceholderShimmer(isLoading: Boolean, firstItemImageUrl: String?): Boolean =
    isLoading && firstItemImageUrl.isPlaceholder()

internal fun HeroCarouselRow.showsPlaceholderShimmer(): Boolean =
    showsPlaceholderShimmer(isLoading, items.list.firstOrNull()?.imageUrl)

/**
 * A row with nothing left to draw: its tiles were all removed (the profile's rating gate hides
 * every unrated tile on a kids profile) and it is no longer fetching.
 *
 * Such a row must never reach the list. Every focus path on Home — initial pick, pending focus,
 * vertical fast-scroll landing, LazyColumn focus restoration — assumes the row it lands on holds
 * at least one focusable card, so an empty row stays a bare title that swallows DPAD_UP/DOWN.
 */
internal fun hasNothingToShow(itemCount: Int, isLoading: Boolean): Boolean =
    itemCount == 0 && !isLoading

internal fun HeroCarouselRow.hasNothingToShow(): Boolean =
    hasNothingToShow(items.list.size, isLoading)
