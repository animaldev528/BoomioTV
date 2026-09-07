package com.boomio.tv.domain.repository

import com.boomio.tv.core.network.NetworkResult
import com.boomio.tv.domain.model.MoreLikeThisPage
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
