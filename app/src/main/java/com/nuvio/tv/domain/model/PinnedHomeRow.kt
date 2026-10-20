package com.nuvio.tv.domain.model

import java.util.Locale

/**
 * What a pinned Home row is built from.
 *
 * The row itself is never a [Collection]: Home renders a collection as *folder cards*,
 * so a collection can carry titles into a folder but not onto the wall. A pinned row is
 * therefore materialised as an ordinary [CatalogRow] and appended to the resolved home
 * rows — see [com.nuvio.tv.data.repository.PinnedHomeRowResolver].
 */
enum class PinnedRowKind {
    /** A Trakt list, pinned from any of its items. */
    TRAKT_LIST,

    /** An episode search (bsf's `ai-search-episodes`), pinned from one of its results. */
    EPISODE_SEARCH,

    /** The single "Actors" row, union-merged from people long-pressed in Search. */
    PEOPLE
}

/** One person on the [PinnedRowKind.PEOPLE] row. `profilePhotoUrl` is already absolute. */
data class PinnedPerson(
    val tmdbId: Int,
    val name: String,
    val profilePhotoUrl: String? = null
)

/**
 * A locally stored request for a Home row. Profile-scoped, never synced: the row is a
 * shortcut into someone else's list, not an asset of ours.
 */
data class PinnedHomeRow(
    val id: String,
    val kind: PinnedRowKind,
    val title: String,
    val traktListId: Long? = null,
    val traktMediaType: String? = null,
    val traktSortBy: String? = null,
    val traktSortHow: String? = null,
    val searchTerm: String? = null,
    val catalogType: String? = null,
    val addonBaseUrl: String? = null,
    val addonId: String? = null,
    val catalogId: String? = null,
    val people: List<PinnedPerson> = emptyList()
) {
    /**
     * Dedupe identity. Pinning the same list, or the same search term, twice must replace
     * the existing row rather than append a second copy of it.
     */
    val stableKey: String
        get() = when (kind) {
            PinnedRowKind.TRAKT_LIST ->
                "trakt_list:${traktListId ?: 0L}:${normalize(traktMediaType)}"
            PinnedRowKind.EPISODE_SEARCH ->
                "episode_search:${normalize(searchTerm)}"
            PinnedRowKind.PEOPLE -> "people"
        }

    /** The Trakt source this row reads, for [PinnedRowKind.TRAKT_LIST] only. */
    fun traktSource(): TraktCollectionSource? {
        val listId = traktListId ?: return null
        return TraktCollectionSource(
            title = title,
            traktListId = listId,
            mediaType = mediaTypeOf(traktMediaType),
            sortBy = traktSortBy ?: TraktListSort.RANK.value,
            sortHow = traktSortHow ?: TraktSortHow.ASC.value
        )
    }

    companion object {
        const val PEOPLE_ROW_ID = "pinned_people"
        const val PEOPLE_RAW_TYPE = "person"

        /** Everything synthetic about a pinned row is namespaced under this addon id. */
        const val PINNED_ADDON_ID = "pinned"

        /**
         * Row id, derived from [stableKey] so the same list pinned twice always lands on the
         * same id. Sanitised because the id doubles as a catalog id and a LazyColumn key.
         */
        fun idFor(kind: PinnedRowKind, stableKey: String): String {
            val safe = buildString {
                stableKey.forEach { char -> append(if (char.isLetterOrDigit()) char else '_') }
            }
            return "${kind.name.lowercase(Locale.US)}_$safe"
        }

        fun mediaTypeOf(value: String?): TmdbCollectionMediaType =
            when (value?.trim()?.lowercase(Locale.US)) {
                "tv", "show", "series" -> TmdbCollectionMediaType.TV
                else -> TmdbCollectionMediaType.MOVIE
            }

        private fun normalize(value: String?): String =
            value.orEmpty().trim().lowercase(Locale.US)
    }
}

/** Narrows an arbitrary `apiType` to the Trakt media type the list resolver expects. */
fun traktMediaTypeOf(apiType: String?): String =
    when (apiType?.trim()?.lowercase(Locale.US)) {
        "series", "show", "tv", "anime" -> "tv"
        else -> "movie"
    }

/** `person:<tmdbId>` — the id a person tile carries on the pinned Actors row. */
fun personPreviewId(tmdbId: Int): String = "${PinnedHomeRow.PEOPLE_RAW_TYPE}:$tmdbId"

/** Parses the tmdb id back out of a [personPreviewId], or null when this is not a person tile. */
fun tmdbIdFromPersonPreviewId(id: String?): Int? {
    val raw = id?.trim().orEmpty()
    if (!raw.startsWith("${PinnedHomeRow.PEOPLE_RAW_TYPE}:")) return null
    return raw.substringAfter(':').toIntOrNull()
}
