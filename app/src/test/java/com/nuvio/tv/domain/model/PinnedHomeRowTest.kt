package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Cast & crew row is one row holding many people. Membership drives every action's label
 * (add vs remove), so the set it resolves to has to be exact — including when there is no row.
 */
class PinnedHomeRowTest {

    private fun person(id: Int, name: String = "Person $id") =
        PinnedPerson(tmdbId = id, name = name, profilePhotoUrl = null)

    private fun peopleRow(vararg ids: Int) = PinnedHomeRow(
        id = PinnedHomeRow.PEOPLE_ROW_ID,
        kind = PinnedRowKind.PEOPLE,
        title = "Cast & crew",
        people = ids.map { person(it) }
    )

    @Test
    fun `no rows means nobody is on the row`() {
        assertEquals(emptySet<Int>(), emptyList<PinnedHomeRow>().castCrewIds())
    }

    @Test
    fun `a row of another kind contributes nobody`() {
        val list = PinnedHomeRow(
            id = "trakt_list_1_movie",
            kind = PinnedRowKind.TRAKT_LIST,
            title = "Watchlist",
            traktListId = 1L
        )
        assertEquals(emptySet<Int>(), listOf(list).castCrewIds())
    }

    @Test
    fun `the people row contributes every member`() {
        val rows = listOf(
            PinnedHomeRow(id = "trakt_list_1_movie", kind = PinnedRowKind.TRAKT_LIST, title = "Watchlist", traktListId = 1L),
            peopleRow(12898, 2157),
            PinnedHomeRow(id = "episode_search_x", kind = PinnedRowKind.EPISODE_SEARCH, title = "x")
        )
        assertEquals(setOf(12898, 2157), rows.castCrewIds())
    }

    @Test
    fun `an empty people row contributes nobody`() {
        assertEquals(emptySet<Int>(), listOf(peopleRow()).castCrewIds())
    }

    @Test
    fun `every person row shares one stable key`() {
        assertEquals(peopleRow(1).stableKey, peopleRow(2, 3).stableKey)
    }

    @Test
    fun `a person tile id round-trips through its raw type`() {
        assertEquals(12898, tmdbIdFromPersonPreviewId(personPreviewId(12898)))
        assertEquals(null, tmdbIdFromPersonPreviewId("tt0114709"))
    }
}
