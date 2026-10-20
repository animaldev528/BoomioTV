package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressMirrorTest {
    private fun seed(contentId: String, season: Int, episode: Int, lastWatched: Long) =
        WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "",
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 0L,
            duration = 1L,
            lastWatched = lastWatched
        )

    @Test
    fun keepsNewestSeedPerShow() {
        val entries = selectMirrorEntries(
            listOf(
                seed("tt1", 1, 2, 20L),
                seed("tt1", 1, 3, 30L),
                seed("tt2", 2, 1, 10L)
            )
        )

        assertEquals(listOf("tt1", "tt2"), entries.map { it.contentId })
        assertEquals(30L, entries.first().lastWatched)
    }

    @Test
    fun dropsBlankContentIds() {
        val entries = selectMirrorEntries(
            listOf(
                seed(" ", 1, 1, 5L),
                seed("tt1", 1, 1, 1L)
            )
        )

        assertEquals(listOf("tt1"), entries.map { it.contentId })
    }

    @Test
    fun emptySeedsStayEmpty() {
        assertEquals(emptyList<WatchProgress>(), selectMirrorEntries(emptyList()))
    }
}
