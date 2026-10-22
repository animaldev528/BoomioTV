package com.nuvio.tv.core.profile

import java.util.Locale

/**
 * Maps content-rating strings to a single ordinal scale (higher = more restrictive)
 * so an item's rating can be compared against a BSM ceiling
 * (G / PG / PG-13 / R / NC-17).
 *
 * A rating is recognised in two ways:
 *  - a known letter-only scale token ("R", "TV-MA", "IIA", "M", ...), or
 *  - any rating carrying an age ("M15", "16", "FSK 16", "MA15+", "12A", "R18+", "K-16", ...),
 *    mapped by that age: ≤5 all ages, ≤9 mild, ≤14 teens, ≤16 mature, older adults.
 *
 * That second rule is the allow-list: an unrecognised or missing rating is *not*
 * allowed when a ceiling is in force, so an international system we have never
 * seen fails closed instead of slipping mature titles onto a kids profile.
 */
object RatingOrdinal {

    /** Letter-only tokens whose age is not written in the string. */
    private val EXPLICIT: Map<String, Int> = mapOf(
        // all ages
        "G" to 0, "TV-Y" to 0, "TV-G" to 0, "E" to 0, "EC" to 0, "U" to 0,
        "T" to 0, "ALL" to 0, "AL" to 0, "ATP" to 0, "AP" to 0, "AA" to 0,
        "S" to 0, "KN" to 0, "BTL" to 0, "SU" to 0, "B/O" to 0, "BO" to 0,
        // mild
        "PG" to 1, "TV-Y7" to 1, "TV-Y7-FV" to 1, "TV-PG" to 1, "UA" to 1, "IIA" to 1,
        // teens
        "PG-13" to 2, "TV-14" to 2, "12A" to 2, "12" to 2, "M" to 2, "IIB" to 2, "B" to 2,
        // mature
        "R" to 3, "TV-MA" to 3, "15" to 3, "MA" to 3,
        // adults only — "A" is India's adults certificate; Spain/Mexico also use it for
        // all ages, and without a country the conservative reading is the safe one.
        "NC-17" to 4, "X" to 4, "18" to 4, "A" to 4, "C" to 4, "D" to 4, "III" to 4,
        "R18" to 4, "R18+" to 4, "X18+" to 4
    )

    private val AGE = Regex("""\d{1,3}""")

    /** Upper-case, whitespace- and dot-free, so "FSK 16" and "M.15" read as "FSK16"/"M15". */
    private fun normalize(rating: String?): String? = rating
        ?.trim()
        ?.uppercase(Locale.US)
        ?.filterNot { it.isWhitespace() || it == '.' }
        ?.takeIf { it.isNotEmpty() }

    private fun byAge(age: Int): Int = when {
        age <= 5 -> 0
        age <= 9 -> 1
        age <= 14 -> 2
        age <= 16 -> 3
        else -> 4
    }

    fun ordinal(rating: String?): Int? {
        val normalized = normalize(rating) ?: return null
        EXPLICIT[normalized]?.let { return it }
        val age = AGE.find(normalized)?.value?.toIntOrNull() ?: return null
        return byAge(age)
    }

    /**
     * Returns true when an item with [itemRating] may be shown under a profile whose
     * ceiling is [ceiling]. An unknown ceiling means no gate (fail open); once a
     * ceiling is in force, however, the item must carry a rating we recognise
     * (fail closed) — "we could not classify it" must not mean "allowed".
     */
    fun isAllowed(itemRating: String?, ceiling: String?): Boolean {
        val ceilingOrdinal = ordinal(ceiling) ?: return true
        val itemOrdinal = ordinal(itemRating) ?: return false
        return itemOrdinal <= ceilingOrdinal
    }
}
