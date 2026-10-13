package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.BsmHistoryImportRequestDto
import com.nuvio.tv.data.remote.dto.BsmHistoryImportResponseDto
import com.nuvio.tv.data.remote.dto.BsmProfileRatingDto
import com.nuvio.tv.data.remote.dto.DeviceCapabilityReportDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface BsmApi {
    @GET("api/nuvio/profile-ratings")
    suspend fun getProfileRatings(): Response<List<BsmProfileRatingDto>>

    /**
     * UPSERT a device-caps/1 report keyed on the install id (boomio bsm fleet view).
     * [DeviceCapabilityReporter] posts it on boot, on display change, and on a ~12h heartbeat.
     */
    @POST("api/prov/devices/capability-report")
    suspend fun reportCapabilities(@Body body: DeviceCapabilityReportDto): Response<Unit>

    /**
     * Seed the CALLER's own taste pool from watch history the client already holds.
     *
     * The route is authenticated by a Supabase access token rather than by an operator session,
     * so the bearer is passed per request — the same shape [TraktApi] uses — rather than attached
     * by an interceptor. This client is shared with the two anonymous calls above and is also the
     * one that carries the device certificate, so an interceptor adding `Authorization` here
     * would attach a token to calls that must stay anonymous.
     *
     * Path is relative to `BSM_BASE_URL`, which already ends in `/bsm`; the collapsed edge serves
     * the route under its `/bsm` prefix.
     */
    @POST("tvpair/history-import")
    suspend fun importHistory(
        @Header("Authorization") authorization: String,
        @Body body: BsmHistoryImportRequestDto
    ): Response<BsmHistoryImportResponseDto>
}
