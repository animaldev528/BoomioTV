package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ComingSoonTest {
    private val today = LocalDate.of(2026, 10, 20)

    private fun video(season: Int, episode: Int, released: String?) = CwVideoSummary(
        id = "v-$season-$episode",
        title = "Episode $episode",
        released = released,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )

    private fun nextUp(
        contentId: String,
        season: Int,
        episode: Int,
        released: String?,
        hasAired: Boolean = false,
        releaseTs: Long? = null
    ) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = contentId,
            contentType = "series",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "v-$season-$episode",
            season = season,
            episode = episode,
            episodeTitle = null,
            thumbnail = null,
            released = released,
            hasAired = hasAired,
            lastWatched = 0L,
            sortTimestamp = 0L,
            releaseTimestamp = releaseTs
        )
    )

    @Test
    fun selectionKeepsOnlyDatedEpisodesInsideTheWindow() {
        val selected = selectUpcomingEpisodes(
            videos = listOf(
                video(1, 1, "2026-10-01T00:00:00.000Z"),
                video(1, 2, "2026-10-20T00:00:00.000Z"),
                video(1, 3, "2026-10-22T00:00:00.000Z"),
                video(1, 4, "2026-11-19T00:00:00.000Z"),
                video(1, 5, null)
            ),
            today = today,
            windowDays = 30L
        )

        assertEquals(listOf(1 to 3), selected.map { it.video.season to it.video.episode })
    }

    @Test
    fun selectionIsSortedSoonestFirstAndDeduplicated() {
        val selected = selectUpcomingEpisodes(
            videos = listOf(
                video(2, 4, "2026-10-29"),
                video(2, 1, "2026-10-22"),
                video(2, 3, "2026-10-27"),
                video(2, 1, "2026-10-22")
            ),
            today = today
        )

        assertEquals(
            listOf(1, 3, 4),
            selected.map { it.video.episode }
        )
    }

    @Test
    fun selectionHonoursTheItemCap() {
        val videos = (1..5).map { episode -> video(1, episode, "2026-10-2$episode") }

        val selected = selectUpcomingEpisodes(videos, today, maxItems = 2)

        assertEquals(listOf(1, 2), selected.map { it.video.episode })
    }

    @Test
    fun selectionIgnoresSpecialsAndBlankFields() {
        val selected = selectUpcomingEpisodes(
            videos = listOf(
                video(0, 1, "2026-10-22"),
                video(1, 0, "2026-10-22"),
                video(1, 2, "not-a-date")
            ),
            today = today
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun mergeAppendsExtrasSoonestFirstAndSkipsListedEpisodes() {
        val listed = nextUp("tt1", 2, 5, "2026-10-22")
        val extras = listOf(
            nextUp("tt1", 2, 5, "2026-10-22", releaseTs = 100L),
            nextUp("tt2", 3, 2, "2026-10-29", releaseTs = 200L),
            nextUp("tt2", 3, 1, "2026-10-25", releaseTs = 150L)
        )

        val merged = mergeUpcomingItems(
            upcoming = listOf(listed),
            comingSoon = extras,
            mode = ContinueWatchingSortMode.SPLIT_UPCOMING
        )

        assertEquals(3, merged.size)
        assertEquals(listed, merged[0])
        assertEquals(3 to 1, merged[1].let { it as ContinueWatchingItem.NextUp }.info.let { it.season to it.episode })
        assertEquals(3 to 2, merged[2].let { it as ContinueWatchingItem.NextUp }.info.let { it.season to it.episode })
    }

    @Test
    fun mergeIsIgnoredWhenTheRowIsNotSplit() {
        val upcoming = listOf(nextUp("tt1", 1, 2, "2026-10-22"))

        val merged = mergeUpcomingItems(
            upcoming = upcoming,
            comingSoon = listOf(nextUp("tt2", 1, 2, "2026-10-23")),
            mode = ContinueWatchingSortMode.DEFAULT
        )

        assertEquals(upcoming, merged)
    }

    @Test
    fun mergeWithNoExtrasKeepsTheRowUntouched() {
        val upcoming = listOf(nextUp("tt1", 1, 2, "2026-10-22"))

        val merged = mergeUpcomingItems(
            upcoming = upcoming,
            comingSoon = emptyList(),
            mode = ContinueWatchingSortMode.SPLIT_UPCOMING
        )

        assertEquals(upcoming, merged)
    }
}
