package com.boomio.tv.data.remote.api

import com.boomio.tv.data.remote.dto.UniqueContributionsResponseDto
import retrofit2.Response
import retrofit2.http.GET

interface UniqueContributionsApi {

    @GET("api/unique-contributions")
    suspend fun getUniqueContributions(): Response<UniqueContributionsResponseDto>
}
