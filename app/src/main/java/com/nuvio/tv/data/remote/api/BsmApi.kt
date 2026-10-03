package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.BsmProfileRatingDto
import com.nuvio.tv.data.remote.dto.DeviceCapabilityReportDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
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
}
