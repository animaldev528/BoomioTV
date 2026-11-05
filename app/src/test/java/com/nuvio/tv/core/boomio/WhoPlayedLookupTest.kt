package com.nuvio.tv.core.boomio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intent gate decides whether search spends a round trip (and a model call) on the
 * boomio `/who-played` endpoint, so it must fire on real questions and stay quiet on
 * ordinary searches — including a half-typed question.
 */
class WhoPlayedLookupTest {

    @Test
    fun `matches the interrogative forms users type`() {
        val questions = listOf(
            "who played buzz lightyear",
            "Who played the astronaut in toy story",
            "who plays the genie in aladdin",
            "who voiced woody",
            "what actor played indiana jones",
            "who is the actor in jurassic park",
            "name the actress who played rose",
        )
        questions.forEach { assertTrue(it, WhoPlayedLookup.matches(it)) }
    }

    @Test
    fun `ignores ordinary searches`() {
        val searches = listOf("buzz lightyear", "toy story", "the who", "who", "inception", "")
        searches.forEach { assertFalse(it, WhoPlayedLookup.matches(it)) }
    }

    @Test
    fun `does not fire on a half-typed question`() {
        assertFalse(WhoPlayedLookup.matches("who played"))
        assertFalse(WhoPlayedLookup.matches("who played "))
    }

    @Test
    fun `keeps a tokenised addon URL on the path, not inside its query`() {
        // The Pi advertises `…/bsf?token=…`; concatenating the path onto that string makes
        // the token swallow `/who-played` and the edge answers 404. Regression: #77.
        assertEquals(
            "https://boomiopi.duckdns.org/bsf/who-played?q=who+played+cain+robocop+2&token=abc123",
            WhoPlayedLookup.buildLookupUrl(
                "https://boomiopi.duckdns.org/bsf?token=abc123",
                "who played cain robocop 2",
            ),
        )
    }

    @Test
    fun `builds the plain endpoint when the addon URL has no query`() {
        assertEquals(
            "https://box.example/bsf/who-played?q=who+played+woody",
            WhoPlayedLookup.buildLookupUrl("https://box.example/bsf/", "who played woody"),
        )
    }

    @Test
    fun `rejects an unusable base`() {
        assertNull(WhoPlayedLookup.buildLookupUrl("   ", "who played woody"))
        assertNull(WhoPlayedLookup.buildLookupUrl("not a url", "who played woody"))
    }
}
