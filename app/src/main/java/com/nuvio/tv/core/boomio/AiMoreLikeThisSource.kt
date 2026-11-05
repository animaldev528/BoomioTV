package com.nuvio.tv.core.boomio

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "More Like This" answered by the boomio AI, not by a recommendation graph.
 *
 * The catalog is the addon's `ai-search` one, asked the way a person would: "shows like
 * Severance". The box grounds the answer in TMDB and returns whole `MetaPreview`s, so a
 * screen gets its picks in one round trip — TMDB's `/recommendations` path needs a details
 * call per pick to fill the cards, and Trakt only knows what its own users related.
 *
 * Never throws: a missing addon, an offline box or an empty answer all yield an empty list,
 * and callers keep whatever fallback they had.
 */
@Singleton
class AiMoreLikeThisSource @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) {
    private data class Cached(val fetchedAtMs: Long, val picks: List<MetaPreview>)

    /** Seed content id + refinement -> picks. One AI answer costs a few seconds, so a revisit should not. */
    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * @param refinement the refine-sheet chips, or null for the plain "more like this" answer.
     *   A selection that merely echoes the title is treated as null, so an untouched sheet
     *   never costs a second round trip.
     */
    suspend fun fetch(
        meta: Meta,
        refinement: MltRefinement? = null,
        limit: Int = DEFAULT_LIMIT
    ): List<MetaPreview> {
        val effective = refinement?.takeUnless { it.isNoop(MltRefinement.seedFor(meta)) }
        val cacheKey = meta.id + "|" + (effective?.signature() ?: DEFAULT_KEY)
        val cached = cache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
            return cached.picks.take(limit)
        }

        val isMovie = when (meta.type) {
            ContentType.MOVIE -> true
            ContentType.SERIES, ContentType.TV -> false
            else -> meta.apiType == "movie"
        }
        val catalogType = if (isMovie) "movie" else "series"
        val query = buildQuery(meta, isMovie, effective)

        val candidates = search(catalogType, query).orEmpty()
            .filter { it.id != meta.id && !it.name.equals(meta.name, ignoreCase = true) }
        val picks = bandByEra(candidates, meta, effective).take(limit)
        if (picks.isNotEmpty()) {
            cache[cacheKey] = Cached(System.currentTimeMillis(), picks)
        }
        return picks
    }

    /**
     * The same answer for a title known only by name — what the long-press wall has,
     * since a poster tile carries no genres and no release year. "movies like X" is
     * exactly the question the detail row asks before any chip is applied, so the
     * wall opens on the picks an untouched row would have shown.
     */
    suspend fun fetchByTitle(
        name: String,
        isMovie: Boolean,
        limit: Int = DEFAULT_LIMIT
    ): List<MetaPreview> {
        val seed = name.trim()
        if (seed.isEmpty()) return emptyList()

        val catalogType = if (isMovie) "movie" else "series"
        val cacheKey = "title|$catalogType|${seed.lowercase()}"
        val cached = cache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
            return cached.picks.take(limit)
        }

        val query = (if (isMovie) "movies like " else "shows like ") + seed
        val picks = search(catalogType, query).orEmpty()
            .filter { !it.name.equals(seed, ignoreCase = true) }
            .take(limit)
        if (picks.isNotEmpty()) {
            cache[cacheKey] = Cached(System.currentTimeMillis(), picks)
        }
        return picks
    }

    /**
     * One `ai-search` round trip against the installed addon that advertises it for
     * [catalogType]. Null means no such addon is installed at all, which callers treat
     * the same as an empty answer.
     */
    private suspend fun search(catalogType: String, query: String): List<MetaPreview>? {
        val target = addonRepository.getInstalledAddons().first()
            .asSequence()
            .filter { it.enabled }
            .flatMap { addon -> addon.catalogs.asSequence().map { addon to it } }
            .firstOrNull { (_, catalog) ->
                catalog.id == WhoPlayedLookup.AI_SEARCH_CATALOG_ID &&
                    catalog.apiType == catalogType &&
                    catalog.supportsExtra("search")
            } ?: return null

        val (addon, catalog) = target
        val result = catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = catalog.skipStep(),
            extraArgs = mapOf("search" to query),
            supportsSkip = false
        ).firstOrNull { it !is NetworkResult.Loading }

        return (result as? NetworkResult.Success)?.data?.items
    }

    private fun buildQuery(meta: Meta, isMovie: Boolean, refinement: MltRefinement?): String {
        val base = if (isMovie) "movies like ${meta.name}" else "shows like ${meta.name}"
        val clauses = refinement
            ?.queryClauses(MltRefinement.seedFor(meta), MltRefinement.yearOf(meta.releaseInfo))
            .orEmpty()
        return if (clauses.isEmpty()) base else "$base, " + clauses.joinToString(", ")
    }

    /**
     * "Older" and "newer" are weights, not cutoffs. The AI still decides what *feels* alike;
     * this only demotes the outliers, so a 1940s noir cannot outrank a 2005 thriller on an
     * "older" ask. Full weight inside ten years of the seed, half weight to twenty, and
     * everything else — including the wrong direction, or an unknown year — sinks together
     * in the order the AI gave them.
     */
    private fun bandByEra(
        picks: List<MetaPreview>,
        meta: Meta,
        refinement: MltRefinement?
    ): List<MetaPreview> {
        val older = refinement?.prefersOlder() == true
        val newer = refinement?.prefersNewer() == true
        if (older == newer) return picks
        val anchor = MltRefinement.yearOf(meta.releaseInfo) ?: return picks
        return picks.sortedBy { pick ->
            val year = MltRefinement.yearOf(pick.releaseInfo) ?: return@sortedBy ERA_LAST_RESORT
            val distance = if (older) anchor - year else year - anchor
            when (distance) {
                in 1..ERA_NEAR_YEARS -> 0
                in (ERA_NEAR_YEARS + 1)..ERA_FAR_YEARS -> 1
                else -> ERA_LAST_RESORT
            }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 12

        private const val DEFAULT_KEY = "default"

        /** Long enough to make back-and-forth navigation free, short enough to stay fresh. */
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** The era weighting: full strength to ten years from the seed, half strength to twenty. */
        private const val ERA_NEAR_YEARS = 10
        private const val ERA_FAR_YEARS = 20
        private const val ERA_LAST_RESORT = 2
    }
}
