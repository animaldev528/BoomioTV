package com.nuvio.tv.core.profile

import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The id a certification lookup should use for a catalog item.
 *
 * Episode results are served as Stremio episode ids (`tt9165438:8:1`) with no
 * `imdbId` field and no certificate. TMDB's find-by-IMDb lookup has no record
 * for that composite string, so the kids gate — which fails CLOSED — dropped
 * every episode while whole-title results on the same query survived. An
 * episode has no certificate of its own: TMDB rates the series, so the show id
 * is the right lookup for an episode of it.
 */
internal fun certificationLookupId(raw: String): String {
    val id = raw.trim()
    val separator = id.indexOf(':')
    if (separator <= 0) return id
    val head = id.substring(0, separator)
    // Only an `tt<digits>` head is rewritten. `tmdb:123` and prefixed/opaque ids
    // from other addons keep their original form.
    val imdbShaped = head.length > 2 && head.startsWith("tt") && head.drop(2).all { it.isDigit() }
    return if (imdbShaped) head else id
}

/**
 * The single title gate for a kids profile: a title is shown only when TMDB
 * certifies it at or below [BsmRatingGate.KIDS_CEILING].
 *
 * Unlike the home rating gate this fails CLOSED. Search fans out to Stremio
 * addons that carry no certificate at all and person filmographies list whole
 * careers, so "we could not rate it" must not mean "allowed".
 */
@Singleton
class KidsContentFilter @Inject constructor(
    private val profileManager: ProfileManager,
    private val tmdbMetadataService: TmdbMetadataService
) {

    /** True when the active profile is a kids profile. */
    fun isKidsActive(): Boolean = profileManager.activeProfile?.isKids == true

    suspend fun isAllowed(item: MetaPreview): Boolean {
        val rating = item.ageRating
            ?: tmdbMetadataService.fetchCertification(
                imdbId = certificationLookupId(item.imdbId ?: item.id),
                contentType = item.type
            )
        return rating != null && RatingOrdinal.isAllowed(rating, ceilingFor(item.type))
    }

    /**
     * The active kids profile's ceiling for one kind of title. The parent sets
     * it per profile in BSM (movies and TV separately); null — never chosen —
     * keeps the previous PG-13 behaviour.
     */
    private fun ceilingFor(type: ContentType): String {
        val profile = profileManager.activeProfile ?: return BsmRatingGate.KIDS_CEILING
        val chosen = when (type) {
            ContentType.SERIES -> profile.kidsRatingTv
            else -> profile.kidsRatingMovie
        }
        return chosen?.trim()?.takeIf { it.isNotEmpty() } ?: BsmRatingGate.KIDS_CEILING
    }

    /** Keep only allowed titles. Lookups fan out; each one is cached by TMDB id. */
    suspend fun filter(items: List<MetaPreview>): List<MetaPreview> = coroutineScope {
        items.map { item ->
            async { item.takeIf { isAllowed(item) } }
        }.awaitAll().filterNotNull()
    }
}
