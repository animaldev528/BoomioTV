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

    /**
     * English prompt clauses; the AI reads these regardless of the UI language.
     *
     * @param anchorYear the seed's own release year, when known. Without it the era chips
     *   can only say "older"/"newer", which the model reads as a direction to wander in.
     */
    fun queryClauses(seed: MltRefinement, anchorYear: Int? = null): List<String> = buildList {
        if (genres != seed.genres) {
            add(if (genres.isEmpty()) "any genre" else "focused on " + genres.joinToString(", "))
        }
        if (moods.isNotEmpty()) {
            add("but " + moods.sortedBy { it.ordinal }.joinToString(", ") { it.phrase })
        }
        shapes.sortedBy { it.ordinal }.forEach { add(it.phraseFor(anchorYear)) }
    }

    /** The viewer asked for an era; results are banded around the seed's year. */
    fun prefersOlder(): Boolean = MltShape.OLDER in shapes && MltShape.NEWER !in shapes

    /** The viewer asked for an era; results are banded around the seed's year. */
    fun prefersNewer(): Boolean = MltShape.NEWER in shapes && MltShape.OLDER !in shapes

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

        /** The year inside a `releaseInfo` such as "2008", "2006-2013" or "July 16, 2008". */
        fun yearOf(releaseInfo: String?): Int? =
            RELEASE_YEAR.find(releaseInfo ?: return null)?.value?.toIntOrNull()

        private val RELEASE_YEAR = Regex("\\b(19|20)\\d{2}\\b")
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
    NO_ANIME("no anime");

    /**
     * The era chips are a weighting, not a cliff: "older" means the decade or two before
     * the seed, with anything much older a last resort. Left as a bare "older titles" the
     * model happily goes back to the 1940s, which is not what a viewer picking "older" after
     * The Dark Knight is asking for.
     */
    fun phraseFor(anchorYear: Int?): String {
        if (anchorYear == null) return phrase
        return when (this) {
            OLDER ->
                "older titles, strongly preferring ${anchorYear - 10}-${anchorYear - 1}, " +
                    "then ${anchorYear - 20}-${anchorYear - 11} at lower weight; " +
                    "anything older than ${anchorYear - 20} is a last resort"
            NEWER ->
                "newer titles, strongly preferring ${anchorYear + 1}-${anchorYear + 10}, " +
                    "then ${anchorYear + 11}-${anchorYear + 20} at lower weight; " +
                    "anything newer than ${anchorYear + 20} is a last resort"
            else -> phrase
        }
    }
}
