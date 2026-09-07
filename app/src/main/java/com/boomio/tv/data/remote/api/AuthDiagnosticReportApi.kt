package com.boomio.tv.data.remote.api

import com.boomio.tv.data.remote.dto.AuthDiagnosticReportRequestDto
import com.boomio.tv.data.remote.dto.AuthDiagnosticReportResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthDiagnosticReportApi {
    @POST("api/auth-diagnostics")
    suspend fun createAuthDiagnosticReport(
        @Body body: AuthDiagnosticReportRequestDto
    ): Response<AuthDiagnosticReportResponseDto>
}
