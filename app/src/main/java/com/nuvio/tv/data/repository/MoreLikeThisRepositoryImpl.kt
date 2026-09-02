package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MoreLikeThisListDto
import com.nuvio.tv.domain.model.MoreLikeThisList
import com.nuvio.tv.domain.model.MoreLikeThisPage
import com.nuvio.tv.domain.repository.MoreLikeThisRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class MoreLikeThisRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi
) : MoreLikeThisRepository {

    companion object {
        private const val TAG = "MoreLikeThisRepository"

        /** Server pages co-members 25 at a time (MLT_PAGE_SIZE). */
        private const val MLT_PAGE_SIZE = 25
    }

    override fun getMoreLikeThis(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        type: String,
        metaId: String,
        skip: Int,
        exclude: List<String>
    ): Flow<NetworkResult<MoreLikeThisPage>> = flow {
        emit(NetworkResult.Loading)

        val url = buildMoreLikeThisUrl(addonBaseUrl, type, metaId, skip, exclude)
        Log.d(
            TAG,
            "Fetching more-like-this addonId=$addonId addonName=$addonName type=$type metaId=$metaId skip=$skip exclude=${exclude.size} url=$url"
        )

        when (val result = safeApiCall(context) { api.getMoreLikeThis(url) }) {
            is NetworkResult.Success -> {
                val lists = (result.data.lists ?: emptyList()).mapNotNull { it.toDomain() }
                val items = (result.data.metas ?: emptyList())
                    .mapNotNull { it?.toDomainOrNull(type, addonBaseUrl) }
                    .distinctBy { it.id }
                Log.d(
                    TAG,
                    "more-like-this success addonId=$addonId type=$type metaId=$metaId lists=${lists.size} items=${items.size}"
                )
                emit(
                    NetworkResult.Success(
                        MoreLikeThisPage(
                            addonId = addonId,
                            addonName = addonName,
                            addonBaseUrl = addonBaseUrl,
                            itemType = type,
                            lists = lists,
                            items = items,
                            hasMore = items.size == MLT_PAGE_SIZE
                        )
                    )
                )
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "more-like-this failed addonId=$addonId type=$type metaId=$metaId code=${result.code} message=${result.message} url=$url"
                )
                emit(result)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    /**
     * {base}/more-like-this/{type}/{metaId}.json?skip=N[&exclude=tt…,tt…], preserving
     * any base query. [exclude] hides the wall the user drilled FROM so a recursive
     * "More like this" stays fresh (see Screen.MoreLikeThis). tt ids are [a-z0-9]+ so
     * they pass through unencoded.
     */
    private fun buildMoreLikeThisUrl(
        baseUrl: String,
        type: String,
        metaId: String,
        skip: Int,
        exclude: List<String>
    ): String {
        val trimmed = baseUrl.trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val basePath = if (queryStart >= 0) trimmed.substring(0, queryStart).trimEnd('/') else trimmed
        val baseQuery = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val excludePart = exclude.joinToString(",") { it.trim() }
            .takeIf { it.isNotEmpty() }
            ?.let { "&exclude=$it" }
            .orEmpty()
        return "$basePath/more-like-this/$type/$metaId.json?skip=$skip$excludePart$baseQuery"
    }

    private fun MoreLikeThisListDto.toDomain(): MoreLikeThisList? {
        val key = key?.takeIf { it.isNotBlank() } ?: return null
        val name = name?.takeIf { it.isNotBlank() } ?: return null
        return MoreLikeThisList(key = key, kind = kind.orEmpty(), name = name, count = count ?: 0)
    }
}
