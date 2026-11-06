package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class KidsContentFilterTest {

    @Test
    fun `episode id asks TMDB for the show certificate`() {
        // bsf serves AI-search episodes as `tt<show>:<season>:<episode>` with no
        // imdbId field. TMDB cannot find that composite id; the show id resolves.
        assertEquals("tt9165438", certificationLookupId("tt9165438:8:1"))
        assertEquals("tt9165438", certificationLookupId("  tt9165438:8:1  "))
    }

    @Test
    fun `plain title ids are unchanged`() {
        assertEquals("tt9165438", certificationLookupId("tt9165438"))
        assertEquals("tmdb:111474", certificationLookupId("tmdb:111474"))
    }

    @Test
    fun `a non-imdb prefix is not stripped`() {
        assertEquals("series:tt9165438", certificationLookupId("series:tt9165438"))
        assertEquals("trakt:123", certificationLookupId("trakt:123"))
    }

    @Test
    fun `a tt head without digits is not stripped`() {
        assertEquals("ttabc:1:2", certificationLookupId("ttabc:1:2"))
    }
}
