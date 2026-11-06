package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.StableList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A kids profile's rating gate hides every tile it cannot rate, which can empty a whole row.
 * The row must then leave the list: Home's focus paths (initial pick, pending focus, vertical
 * landing, focus restoration) all assume the row they land on has a card to focus, so an empty
 * row renders as a title that swallows DPAD_UP/DOWN.
 */
class ModernHomeEmptyRowTest {

    @Test
    fun `a row emptied by the rating gate is dropped`() {
        assertTrue(row(key = "trending", itemCount = 0, isLoading = false).hasNothingToShow())
    }

    @Test
    fun `a row still fetching keeps its slot`() {
        assertFalse(row(key = "trending", itemCount = 0, isLoading = true).hasNothingToShow())
    }

    @Test
    fun `a row with even one surviving tile is kept`() {
        assertFalse(row(key = "trending", itemCount = 1, isLoading = false).hasNothingToShow())
    }

    private fun row(key: String, itemCount: Int, isLoading: Boolean): HeroCarouselRow =
        HeroCarouselRow(
            key = key,
            title = "Trending Movies",
            globalRowIndex = 0,
            items = StableList(List(itemCount) { catalogItem("$key-$it") }),
            isLoading = isLoading
        )

    private fun catalogItem(key: String): ModernCarouselItem {
        return ModernCarouselItem(
            key = key,
            title = key,
            subtitle = null,
            imageUrl = null,
            heroPreview = HeroPreview(
                title = key,
                logo = null,
                description = null,
                contentTypeText = null,
                yearText = null,
                imdbText = null,
                genres = StableList(),
                poster = null,
                backdrop = null,
                imageUrl = null
            ),
            payload = ModernPayload.Catalog(
                focusKey = key,
                itemId = key,
                itemType = "movie",
                addonBaseUrl = "https://example.test/manifest.json",
                trailerTitle = key,
                trailerReleaseInfo = null,
                trailerApiType = "movie"
            )
        )
    }
}
