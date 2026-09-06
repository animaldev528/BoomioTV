package com.nuvio.tv.core.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "NetworkMeter"

/**
 * Headless download-throughput meter for the box's current link.
 *
 * One bounded download from the boomio bsc edge (`{BOOMIO_COMPANION_URL origin}/api/speedtest/probe`
 * — the same server the app already reaches as a companion over wss, measured over a short window).
 * The last result is cached in SharedPreferences so stream requests and the device-caps report always
 * have a value on a cold start before the meter has run again.
 *
 * The meter is the automatic replacement for the diagnostic-only test in
 * `NetworkSettingsScreen.runSpeedTest()`: it runs on the device-caps reporter's cadence (shortly after
 * boot and on each 12 h heartbeat) rather than from a Settings button. It is best-effort and inert when
 * `BOOMIO_COMPANION_URL` is blank or the probe is unreachable — it never throws and never blocks the
 * caller past the bounded window.
 */
@Singleton
class NetworkMeter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val probeClient: OkHttpClient by lazy {
        // The probe streams for up to MEASURE_WINDOW_MS; a slow-but-alive link must not be cut by the
        // shared client's read timeout, so give the probe its own longer one.
        okHttpClient.newBuilder()
            .readTimeout(MEASURE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Last measured download throughput in Mbps (rounded to 1 decimal), or null when never measured. */
    fun lastMeasuredMbps(): Double? {
        val mbps = prefs().getFloat(KEY_LAST_MEASURED_MBPS, -1f)
        return if (mbps > 0f) roundTo1Decimal(mbps.toDouble()) else null
    }

    /**
     * Runs one probe download, caches the result, and returns Mbps (1 decimal). Returns null when the
     * probe is unconfigured, the download fails, or nothing was read within the window — the previous
     * cached value (if any) is left intact.
     */
    suspend fun measure(): Double? {
        val url = probeUrl() ?: return null
        val call = probeClient.newCall(Request.Builder().url(url).get().build())
        val result = withTimeoutOrNull(MEASURE_TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { downloadAndMeasure(call) }
        }
        if (result == null) {
            // Timeout path: release the connection so a stalled blocking read can't linger.
            runCatching { call.cancel() }
        } else {
            prefs().edit()
                .putFloat(KEY_LAST_MEASURED_MBPS, result.toFloat())
                .putLong(KEY_LAST_MEASURED_AT_MS, System.currentTimeMillis())
                .apply()
            Log.i(TAG, String.format(Locale.US, "Measured link %.1f Mbps", result))
        }
        return result
    }

    /** Reads bytes from the probe until EOF (fast links, accurate) or the wall-clock window elapses. */
    private fun downloadAndMeasure(call: Call): Double? = runCatching {
        call.execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val start = System.currentTimeMillis()
            val deadline = start + MEASURE_WINDOW_MS
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0L
            body.byteStream().use { input ->
                while (System.currentTimeMillis() < deadline) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
            }
            val elapsed = System.currentTimeMillis() - start
            // Ignore sub-second samples — TCP slow-start noise dominates a measurement that short.
            if (total <= 0L || elapsed < MIN_SAMPLE_ELAPSED_MS) return null
            roundTo1Decimal(total * 8.0 / (elapsed * 1000.0))
        }
    }.getOrNull()

    private fun probeUrl(): String? {
        val companion = BuildConfig.BOOMIO_COMPANION_URL.trim()
        if (companion.isBlank()) {
            Log.d(TAG, "BOOMIO_COMPANION_URL blank — automated bandwidth measurement disabled")
            return null
        }
        // The app only ever talks to bsc over wss (companion); the probe is plain HTTP on the same host.
        val origin = companion
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .trimEnd('/')
        return "$origin/api/speedtest/probe"
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun roundTo1Decimal(value: Double): Double = (value * 10).roundToInt() / 10.0

    private companion object {
        const val PREFS_NAME = "nuvio_network_meter"
        const val KEY_LAST_MEASURED_MBPS = "last_measured_mbps"
        const val KEY_LAST_MEASURED_AT_MS = "last_measured_at_ms"

        /** Wall-clock window for one probe download — short enough to be a cheap cadence probe. */
        const val MEASURE_WINDOW_MS = 8_000L
        /** Probe-client read timeout; must exceed the window so a slow link is measured, not cut. */
        const val MEASURE_READ_TIMEOUT_MS = 15_000L
        /** Outer bound on the whole measurement (covers connect + stall) so callers stay bounded. */
        const val MEASURE_TOTAL_TIMEOUT_MS = 12_000L
        /** Ignore samples shorter than this — TCP slow-start noise would dominate. */
        const val MIN_SAMPLE_ELAPSED_MS = 200L
        const val READ_BUFFER_BYTES = 64 * 1024
    }
}
