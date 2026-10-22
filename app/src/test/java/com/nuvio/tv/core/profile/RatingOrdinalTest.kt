package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate is an allow-list once a ceiling exists: a title must carry a rating we can
 * place on the scale, or it is hidden. That is the whole point — an unrecognised
 * international rating must not read as "safe" for a kids profile.
 */
class RatingOrdinalTest {

    @Test
    fun usRatingsKeepTheirKnownOrdinals() {
        assertEquals(0, RatingOrdinal.ordinal("G"))
        assertEquals(1, RatingOrdinal.ordinal("PG"))
        assertEquals(2, RatingOrdinal.ordinal("PG-13"))
        assertEquals(3, RatingOrdinal.ordinal("R"))
        assertEquals(4, RatingOrdinal.ordinal("NC-17"))
        assertEquals(2, RatingOrdinal.ordinal("TV-14"))
        assertEquals(3, RatingOrdinal.ordinal("TV-MA"))
    }

    @Test
    fun internationalAgeRatingsMapByAge() {
        // The reported leak: Romania/Russia certificates for mature films.
        assertEquals(3, RatingOrdinal.ordinal("M15"))
        assertEquals(3, RatingOrdinal.ordinal("16"))
        assertEquals(3, RatingOrdinal.ordinal("16+"))
        assertEquals(3, RatingOrdinal.ordinal("N-15"))
        assertEquals(3, RatingOrdinal.ordinal("FSK 16"))
        assertEquals(3, RatingOrdinal.ordinal("K-16"))
        assertEquals(3, RatingOrdinal.ordinal("MA15+"))
        assertEquals(2, RatingOrdinal.ordinal("12"))
        assertEquals(2, RatingOrdinal.ordinal("12+"))
        assertEquals(2, RatingOrdinal.ordinal("12A"))
        assertEquals(2, RatingOrdinal.ordinal("VM14"))
        assertEquals(4, RatingOrdinal.ordinal("18+"))
        assertEquals(4, RatingOrdinal.ordinal("R18+"))
        assertEquals(1, RatingOrdinal.ordinal("FSK 6"))
    }

    @Test
    fun letterOnlyScalesAreRecognised() {
        assertEquals(0, RatingOrdinal.ordinal("U"))
        assertEquals(0, RatingOrdinal.ordinal("AL"))
        assertEquals(1, RatingOrdinal.ordinal("UA"))
        assertEquals(2, RatingOrdinal.ordinal("M"))
        assertEquals(2, RatingOrdinal.ordinal("IIB"))
        assertEquals(1, RatingOrdinal.ordinal("IIA"))
        assertEquals(4, RatingOrdinal.ordinal("III"))
        assertEquals(4, RatingOrdinal.ordinal("A"))
    }

    @Test
    fun unrecognisedRatingsAreUnknown() {
        assertNull(RatingOrdinal.ordinal(null))
        assertNull(RatingOrdinal.ordinal(""))
        assertNull(RatingOrdinal.ordinal("NR"))
        assertNull(RatingOrdinal.ordinal("Not Rated"))
        assertNull(RatingOrdinal.ordinal("N/A"))
    }

    @Test
    fun ceilingAllowsUpToItsOwnOrdinal() {
        assertTrue(RatingOrdinal.isAllowed("PG-13", "PG-13"))
        assertTrue(RatingOrdinal.isAllowed("PG", "PG-13"))
        assertFalse(RatingOrdinal.isAllowed("R", "PG-13"))
        assertFalse(RatingOrdinal.isAllowed(null, "PG-13"))
    }

    @Test
    fun unknownInternationalRatingsFailClosedUnderATeenCeiling() {
        // The kids profile's default ceiling is PG-13. A mature title certified
        // "M15"/"16"/FSK 16 must be hidden rather than passed as "unknown".
        assertFalse(RatingOrdinal.isAllowed("M15", "PG-13"))
        assertFalse(RatingOrdinal.isAllowed("16", "PG-13"))
        assertFalse(RatingOrdinal.isAllowed("FSK 16", "PG-13"))
        // A legitimately mild certificate still passes.
        assertTrue(RatingOrdinal.isAllowed("FSK 6", "PG-13"))
        assertTrue(RatingOrdinal.isAllowed("U", "PG-13"))
    }

    @Test
    fun noCeilingMeansNoGate() {
        assertTrue(RatingOrdinal.isAllowed("R", null))
        assertTrue(RatingOrdinal.isAllowed(null, null))
        // An unrecognised ceiling cannot gate anything either.
        assertTrue(RatingOrdinal.isAllowed("R", "whatever"))
    }
}
