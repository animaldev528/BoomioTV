package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.MoreLikeThisPage
import kotlinx.coroutines.flow.Flow

interface MoreLikeThisRepository {
    fun getMoreLikeThis(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        type: String,
        metaId: String,
        skip: Int = 0,
        exclude: List<String> = emptyList()
    ): Flow<NetworkResult<MoreLikeThisPage>>
}
