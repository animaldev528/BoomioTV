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

        val target = addonRepository.getInstalledAddons().first()
            .asSequence()
            .filter { it.enabled }
            .flatMap { addon -> addon.catalogs.asSequence().map { addon to it } }
            .firstOrNull { (_, catalog) ->
                catalog.id == WhoPlayedLookup.AI_SEARCH_CATALOG_ID &&
                    catalog.apiType == catalogType &&
                    catalog.supportsExtra("search")
            } ?: return emptyList()

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

        val picks = when (result) {
            is NetworkResult.Success -> result.data.items
                .filter { it.id != meta.id && !it.name.equals(meta.name, ignoreCase = true) }
                .take(limit)
            else -> emptyList()
        }
        if (picks.isNotEmpty()) {
            cache[cacheKey] = Cached(System.currentTimeMillis(), picks)
        }
        return picks
    }

    private fun buildQuery(meta: Meta, isMovie: Boolean, refinement: MltRefinement?): String {
        val base = if (isMovie) "movies like ${meta.name}" else "shows like ${meta.name}"
        val clauses = refinement?.queryClauses(MltRefinement.seedFor(meta)).orEmpty()
        return if (clauses.isEmpty()) base else "$base, " + clauses.joinToString(", ")
    }

    companion object {
        const val DEFAULT_LIMIT = 12

        private const val DEFAULT_KEY = "default"

        /** Long enough to make back-and-forth navigation free, short enough to stay fresh. */
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
