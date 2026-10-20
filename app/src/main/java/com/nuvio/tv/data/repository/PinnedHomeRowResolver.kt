package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PinnedHomeRow
import com.nuvio.tv.domain.model.PinnedRowKind
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.personPreviewId
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a [PinnedHomeRow] into the ordinary [CatalogRow] Home renders.
 *
 * Nothing downstream knows these rows are pinned: they arrive as `HomeRow.Catalog` like any
 * other, which is what makes every layout (classic, grid, modern) render them without a
 * second code path — and what makes the landscape episode row come out landscape for free,
 * because the shape is carried per item by bsf.
 *
 * A row that cannot be resolved right now (Trakt down, add-on removed) resolves to null and
 * simply does not appear. It stays stored, so the next Home rebuild can pick it up.
 */
@Singleton
class PinnedHomeRowResolver @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val traktPublicListSourceResolver: TraktPublicListSourceResolver
) {
    suspend fun resolve(row: PinnedHomeRow): CatalogRow? = runCatching {
        when (row.kind) {
            PinnedRowKind.TRAKT_LIST -> resolveTraktList(row)
            PinnedRowKind.EPISODE_SEARCH -> resolveEpisodeSearch(row)
            PinnedRowKind.PEOPLE -> resolvePeople(row)
        }
    }.getOrNull()

    private suspend fun resolveTraktList(row: PinnedHomeRow): CatalogRow? {
        val source = row.traktSource() ?: return null
        val result = traktPublicListSourceResolver
            .resolve(source, page = 1)
            .first { it !is NetworkResult.Loading }
        val resolved = (result as? NetworkResult.Success)?.data ?: return null
        return resolved.asPinned(row)
    }

    private suspend fun resolveEpisodeSearch(row: PinnedHomeRow): CatalogRow? = withContext(Dispatchers.IO) {
        val searchTerm = row.searchTerm?.trim().orEmpty()
        val addonBaseUrl = row.addonBaseUrl ?: return@withContext null
        val addonId = row.addonId ?: return@withContext null
        val catalogId = row.catalogId ?: return@withContext null
        val type = row.catalogType ?: "series"
        val result = catalogRepository.getCatalog(
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            addonName = row.title,
            catalogId = catalogId,
            catalogName = row.title,
            type = type,
            extraArgs = mapOf("search" to searchTerm),
            supportsSkip = false
        ).first { it !is NetworkResult.Loading }
        val resolved = (result as? NetworkResult.Success)?.data ?: return@withContext null
        resolved.asPinned(row)
    }

    private fun resolvePeople(row: PinnedHomeRow): CatalogRow? {
        if (row.people.isEmpty()) return null
        val items = row.people.map { person ->
            MetaPreview(
                id = personPreviewId(person.tmdbId),
                type = ContentType.UNKNOWN,
                rawType = PinnedHomeRow.PEOPLE_RAW_TYPE,
                name = person.name,
                poster = person.profilePhotoUrl,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            )
        }
        return CatalogRow(
            addonId = PinnedHomeRow.PINNED_ADDON_ID,
            addonName = "",
            addonBaseUrl = "",
            catalogId = row.id,
            catalogName = row.title,
            type = ContentType.UNKNOWN,
            rawType = PinnedHomeRow.PEOPLE_RAW_TYPE,
            items = items,
            isLoading = false,
            hasMore = false,
            supportsSkip = false
        )
    }

    /**
     * Re-keys a resolved row onto the pinned identity.
     *
     * `hasMore = false` and the item cap are deliberate: a pinned row has no See-all
     * destination behind it, so it must never render one.
     */
    private fun CatalogRow.asPinned(row: PinnedHomeRow) = copy(
        addonId = PinnedHomeRow.PINNED_ADDON_ID,
        addonName = "",
        addonBaseUrl = "",
        catalogId = row.id,
        catalogName = row.title,
        items = items.filter { it.id.isNotBlank() }.distinctBy { "${it.apiType}:${it.id}" }.take(MAX_ITEMS),
        isLoading = false,
        hasMore = false,
        currentPage = 0,
        supportsSkip = false,
        nextSkip = 0
    )

    companion object {
        /** Below every See-all threshold in [com.nuvio.tv.ui.components.CatalogRowSection]. */
        private const val MAX_ITEMS = 14
    }
}
