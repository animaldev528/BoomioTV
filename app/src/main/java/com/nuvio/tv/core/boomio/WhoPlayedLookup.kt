package com.nuvio.tv.core.boomio

import com.nuvio.tv.domain.model.PersonSearchPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reverse casting lookup: "who played buzz lightyear" / "who played the astronaut in toy
 * story" → the performer, as a People-strip card.
 *
 * The strip's normal source is TMDB `/search/person`, which matches NAMES only — a character
 * name returns nothing, and a described role ("the astronaut in toy story") is not a name at
 * all. The boomio addon resolves the role with its Gemini-backed `/who-played` endpoint and
 * grounds the answer to a TMDB person id, so the card opens the same CastDetail screen every
 * other person tile opens.
 *
 * ⚠️ Keep [INTENT_PATTERNS] in step with `INTENT_PATTERNS` in `bsf/lib/who-played.js`: this
 * side only decides whether to spend the round trip, and the server refuses a non-question
 * before it calls the model. A mismatch would either hide the feature or pay for nothing.
 */
object WhoPlayedLookup {

    private val INTENT_PATTERNS = listOf(
        Regex("""\bwho\s+(?:played|plays|voiced|voices|dubbed)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhat\s+(?:actor|actress|voice\s+actor)\s+(?:played|plays|voiced|voices|dubbed)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwho\s+(?:was|is)\s+the\s+(?:actor|actress|voice)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:name|tell\s+me)\s+the\s+(?:actor|actress)\s+(?:who|that)\s+(?:played|voiced)\b""", RegexOption.IGNORE_CASE),
    )

    /** Catalog id of the boomio addon's AI search; any of its catalogs identifies the addon. */
    const val AI_SEARCH_CATALOG_ID = "ai-search"

    /**
     * True when [query] is asking who performed a role. The role must have at least two
     * characters after the trigger, so "who played" alone (mid-typing) does not pay for a
     * model call.
     */
    fun matches(query: String): Boolean {
        val match = INTENT_PATTERNS.firstOrNull { it.containsMatchIn(query) } ?: return false
        val trigger = match.find(query) ?: return false
        return query.substring(trigger.range.last + 1).trim().length >= 2
    }

    /**
     * Resolves [query] against the boomio addon at [addonBaseUrl].
     *
     * Never throws: a slow model, an offline box, or a malformed body yields an empty list,
     * which simply leaves the People strip hidden. The server caches answers per query, so
     * live-search keystrokes that repeat a query cost nothing.
     */
    suspend fun lookup(
        addonBaseUrl: String,
        query: String,
        limit: Int = 12,
    ): List<PersonSearchPreview> = withContext(Dispatchers.IO) {
        if (!matches(query)) return@withContext emptyList()
        val trimmedBase = addonBaseUrl.trim().trimEnd('/')
        if (trimmedBase.isEmpty()) return@withContext emptyList()

        try {
            val url = URL("$trimmedBase/who-played?q=" + URLEncoder.encode(query.trim(), "UTF-8"))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                // The box resolves the role with a model (~2.5 s measured) and then grounds
                // the answer in TMDB, so this must clear the server's model timeout.
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode !in 200..299) return@withContext emptyList()
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parsePeople(body, limit)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Maps the endpoint's `{people:[…]}` body to strip cards. Visible for tests. */
    fun parsePeople(body: String, limit: Int = 12): List<PersonSearchPreview> {
        return try {
            val people = JSONObject(body).optJSONArray("people") ?: return emptyList()
            (0 until people.length()).asSequence()
                .mapNotNull { people.optJSONObject(it) }
                .mapNotNull { person ->
                    val tmdbId = person.optInt("tmdbId", 0)
                    val name = person.optString("name").takeIf { it.isNotBlank() }
                    if (tmdbId <= 0 || name == null) return@mapNotNull null
                    PersonSearchPreview(
                        tmdbId = tmdbId,
                        name = name,
                        profilePhotoUrl = person.optString("profilePhotoUrl")
                            .takeIf { it.isNotBlank() },
                        knownForLabel = person.optString("caption").takeIf { it.isNotBlank() }
                    )
                }
                .take(limit)
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
