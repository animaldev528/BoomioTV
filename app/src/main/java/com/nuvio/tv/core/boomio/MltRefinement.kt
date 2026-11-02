package com.nuvio.tv.core.boomio

import com.nuvio.tv.domain.model.Meta

/**
 * A viewer's tilt on a "more like this" answer.
 *
 * Chips only change the question when they differ from what the seed title already implies:
 * a title's own genres are not a refinement, so a sheet left untouched asks the exact
 * question the row would have asked on its own (and hits the same cache entry).
 */
data class MltRefinement(
    val genres: Set<String> = emptySet(),
    val moods: Set<MltMood> = emptySet(),
    val shapes: Set<MltShape> = emptySet()
) {
    /** True when this selection adds nothing to the seed title. */
    fun isNoop(seed: MltRefinement): Boolean =
        genres == seed.genres && moods.isEmpty() && shapes.isEmpty()

    /** Stable cache key fragment for a selection the AI is actually asked about. */
    fun signature(): String = buildString {
        append(genres.sorted().joinToString("/"))
        append('|')
        append(moods.sortedBy { it.ordinal }.joinToString("/") { it.name })
        append('|')
        append(shapes.sortedBy { it.ordinal }.joinToString("/") { it.name })
    }

    /** English prompt clauses; the AI reads these regardless of the UI language. */
    fun queryClauses(seed: MltRefinement): List<String> = buildList {
        if (genres != seed.genres) {
            add(if (genres.isEmpty()) "any genre" else "focused on " + genres.joinToString(", "))
        }
        if (moods.isNotEmpty()) {
            add("but " + moods.sortedBy { it.ordinal }.joinToString(", ") { it.phrase })
        }
        shapes.sortedBy { it.ordinal }.forEach { add(it.phrase) }
    }

    companion object {
        const val MAX_GENRES = 4

        /** Chips a title arrives pre-ticked with. */
        fun seedFor(meta: Meta): MltRefinement = MltRefinement(
            genres = meta.genres.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_GENRES)
                .toSet()
        )
    }
}

enum class MltMood(val phrase: String) {
    LIGHTER("lighter"),
    DARKER("darker"),
    FUNNIER("funnier"),
    SCARIER("scarier"),
    MORE_ACTION("more action-heavy"),
    MORE_MYSTERY("more mysterious"),
    SLOWER("slower-paced")
}

enum class MltShape(val phrase: String) {
    NEWER("newer titles"),
    OLDER("older titles"),
    MOVIES_ONLY("movies only"),
    SHOWS_ONLY("shows only"),
    NO_ANIME("no anime")
}
