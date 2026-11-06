package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A kids profile's Home gate fails CLOSED: it hides a tile whose `ageRating`
 * is null (see `HomeViewModelPresentationPipeline.computeGatedItemKeys` and
 * `RatingOrdinal.isAllowed`). The kids row addons emit the tile's certificate
 * precisely so the row can render, so the catalog mapper is the seam that must
 * carry it — a dropped field costs the profile the whole Home (`ageRating`
 * present in the JSON, no posters on screen).
 */
class CatalogMapperAgeRatingTest {

    private val adapter = Moshi.Builder().build().adapter(CatalogResponseDto::class.java)

    @Test
    fun `catalog tile ageRating reaches the domain model`() {
        val preview = parse(
            """
            {"metas":[{"id":"tt26443616","type":"movie","name":"Hoppers","ageRating":"PG"}]}
            """.trimIndent()
        )

        assertEquals("PG", preview.ageRating)
    }

    @Test
    fun `a blank ageRating is treated as unknown`() {
        val preview = parse(
            """
            {"metas":[{"id":"tt26443616","type":"movie","name":"Hoppers","ageRating":"  "}]}
            """.trimIndent()
        )

        assertNull(preview.ageRating)
    }

    @Test
    fun `an addon without ageRating still maps the tile`() {
        val preview = parse(
            """
            {"metas":[{"id":"tt26443616","type":"movie","name":"Hoppers"}]}
            """.trimIndent()
        )

        assertEquals("Hoppers", preview.name)
        assertNull(preview.ageRating)
    }

    private fun parse(json: String) =
        adapter.fromJson(json)!!.metas.single()!!.toDomainOrNull("movie")!!
}
