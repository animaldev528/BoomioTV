package com.nuvio.tv.core.boomio

import org.junit.Assert.assertFalse
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
}
