package com.boomio.tv.data.remote.api

import com.boomio.tv.data.remote.dto.BsmProfileRatingDto
import retrofit2.Response
import retrofit2.http.GET

interface BsmApi {
    @GET("api/nuvio/profile-ratings")
    suspend fun getProfileRatings(): Response<List<BsmProfileRatingDto>>
}
