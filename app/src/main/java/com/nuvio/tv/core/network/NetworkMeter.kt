package com.nuvio.tv.core.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.tv.core.boomio.boomioOwnHosts
import com.nuvio.tv.core.boomio.resolveHttpClientFor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

private const val TAG = "NetworkMeter"

/**
 * Headless download-throughput meter for the box's current link.
 *
 * Bounded downloads from the boomio bsc edge
 * (`{BOOMIO_COMPANION_URL origin}/api/speedtest/probe` — the same server the app already reaches as a
 * companion over wss), measured the way playback actually pulls bytes: **several connections at once,
 * each read past its TCP slow-start ramp**. The last result is cached in SharedPreferences so stream
 * requests and the device-caps report always have a value on a cold start before the meter has run
 * again.
 *
 * ── Why it is not one cold eight-second stream ───────────────────────────────
 * A single short stream measures the warm-up, not the link: TCP slow-start fills the pipe over the
 * first seconds, so counting from byte one under-reads by whatever the ramp cost, and one flow is
 * limited by its own congestion window even when the path could carry far more. This number is not
 * merely displayed — it is reported as `mobileCapMbps` and the server **drops every stream above it** —
 * so an under-read is a device being handed a worse list than it can play. Hence: N connections
 * (matching playback's parallel range reads), the slow-start window discarded, and a sample promoted
 * to a cap only when *every* connection produced a sustained steady window. A partial measurement is
 * discarded rather than published; the previous cached value is left in place.
 *
 * The meter is the automatic replacement for the diagnostic-only test in
 * `NetworkSettingsScreen.runSpeedTest()`: it runs on the device-caps reporter's cadence (shortly after
 * boot and on each 12 h heartbeat) rather than from a Settings button. It is best-effort and inert when
 * `BOOMIO_COMPANION_URL` is blank or the probe is unreachable — it never throws and never blocks the
 * caller past the bounded window.
 *
 * ── The certificate on the probe ─────────────────────────────────────────────
 * The probe is dialled at the bsc edge, a host of ours, and `/api/speedtest/probe` is on the
 * enforced side of it — a request that arrives without the certificate is served as an
 * *unidentified* device rather than refused. So the probe travels over the certificate-bearing
 * twin of the ordinary client, chosen per request off the probe URL's own host (see
 * [resolveHttpClientFor]); the certificate is never offered to a host that is not ours.
 */
@Singleton
class NetworkMeter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    // Reached only through [resolveHttpClientFor]; see its KDoc for the leak that scoping prevents.
    @Named("boomioClientCertificate")
    private val certificateOkHttpClient: OkHttpClient
) {
    private val probeClient: OkHttpClient by lazy {
        // The probe streams for up to MEASURE_READ_MS; a slow-but-alive link must not be cut by the
        // shared client's read timeout, so give the probe its own longer one. HTTP/1.1 is forced so
        // that [PROBE_CONNECTIONS] concurrent calls occupy that many *separate* TCP connections —
        // under HTTP/2 OkHttp multiplexes them onto one connection, and the measurement collapses
        // back to the single cold stream this meter exists to stop trusting.
        okHttpClient.newBuilder()
            .readTimeout(MEASURE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    /** [probeClient]'s certificate-bearing twin, for when the probe URL's host is one of ours. */
    private val certificateProbeClient: OkHttpClient by lazy {
        certificateOkHttpClient.newBuilder()
            .readTimeout(MEASURE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    /** Last measured download throughput in Mbps (rounded to 1 decimal), or null when never measured. */
    fun lastMeasuredMbps(): Double? {
        val mbps = prefs().getFloat(KEY_LAST_MEASURED_MBPS, -1f)
        return if (mbps > 0f) roundTo1Decimal(mbps.toDouble()) else null
    }

    /**
     * Runs one probe across [PROBE_CONNECTIONS] connections at once, caches the result, and returns
     * Mbps (1 decimal). Returns null when the probe is unconfigured, any connection fails, or a
     * connection never reached a sustained steady window — the previous cached value (if any) is left
     * intact rather than overwritten with a partial reading.
     */
    suspend fun measure(): Double? {
        val probe = probeUrl()?.toHttpUrlOrNull() ?: return null
        // Pick the client off the URL's own host so the certificate rides the probe to our own edge
        // and is never offered anywhere else. See [resolveHttpClientFor].
        val client = resolveHttpClientFor(probe.host, boomioOwnHosts(), probeClient, certificateProbeClient)
        // One Call per connection: separate TCP connections (HTTP/1.1, see the client above), each
        // carrying its own headers, so the certificate reaches the edge on all of them.
        val calls = (0 until PROBE_CONNECTIONS).map {
            client.newCall(Request.Builder().url(probe).get().build())
        }
        val result = withTimeoutOrNull(MEASURE_TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { measureConcurrently(calls) }
        }
        if (result == null) {
            // Timeout or partial sample: release every connection so a stalled blocking read can't linger.
            calls.forEach { runCatching { it.cancel() } }
        } else {
            prefs().edit()
                .putFloat(KEY_LAST_MEASURED_MBPS, result.toFloat())
                .putLong(KEY_LAST_MEASURED_AT_MS, System.currentTimeMillis())
                .apply()
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Measured link %.1f Mbps across %d connections",
                    result, PROBE_CONNECTIONS
                )
            )
        }
        return result
    }

    /**
     * Runs [calls] at once and returns their summed steady-state throughput — the aggregate the link
     * actually carried, which is what several parallel range reads would see.
     *
     * Every connection must yield a valid steady sample, or the whole measurement is discarded: a
     * partial sample (a rate-limited, refused or stalled connection) is exactly the artifact that
     * must never be promoted to a bandwidth cap.
     */
    private fun measureConcurrently(calls: List<Call>): Double? {
        val pool = Executors.newFixedThreadPool(calls.size)
        return try {
            val futures = calls.map { call -> pool.submit(Callable { measureOne(call) }) }
            val rates = futures.map { it.get(MEASURE_READ_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS) }
            if (rates.any { it == null }) {
                Log.w(TAG, "Probe discarded: a connection produced no sustained steady sample")
                return null
            }
            roundTo1Decimal(rates.filterNotNull().sum())
        } catch (e: Exception) {
            Log.w(TAG, "Parallel probe failed: ${e.message}")
            null
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * One connection's steady-state throughput in Mbps, or null when it never produced a steady
     * sample. Bytes read during [WARMUP_MS] are deliberately dropped; only the window after them is
     * measured.
     */
    private fun measureOne(call: Call): Double? = runCatching {
        call.execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val start = System.currentTimeMillis()
            val warmupEnd = start + WARMUP_MS
            val deadline = start + MEASURE_READ_MS
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var steadyStart = 0L
            var steadyBytes = 0L
            body.byteStream().use { input ->
                while (System.currentTimeMillis() < deadline) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (steadyStart == 0L) {
                        // Still inside the slow-start ramp — drop these bytes rather than count them.
                        if (System.currentTimeMillis() >= warmupEnd) {
                            steadyStart = System.currentTimeMillis()
                            steadyBytes = read.toLong()
                        }
                    } else {
                        steadyBytes += read
                    }
                }
            }
            if (steadyStart == 0L) return null
            val steadyMs = (System.currentTimeMillis() - steadyStart).coerceAtLeast(1L)
            // Too short a steady window is noise, not a measurement.
            if (steadyMs < MIN_STEADY_MS) return null
            steadyBytes * 8.0 / (steadyMs * 1000.0)
        }
    }.getOrNull()

    private fun probeUrl(): String? {
        // Off the seam, not the baked constant: the probe must follow the origin the rest of the
        // app dials, or the measurement describes a host nothing else uses.
        val companion = BoomioConfig.companionBaseUrl.trim()
        if (companion.isBlank()) {
            Log.d(TAG, "companion base url blank — automated bandwidth measurement disabled")
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

        /**
         * Concurrent probe connections. Playback pulls with several range connections at once, so one
         * stream under-reads the link; two is also the most the probe endpoint allows per IP per minute
         * (bsc `speedtestLimiter`, `max: 2`), so opening more would draw 429s rather than bandwidth.
         */
        const val PROBE_CONNECTIONS = 2
        /** Bytes transferred before the steady window opens are dropped — the TCP slow-start ramp. */
        const val WARMUP_MS = 2_500L
        /** Total read time per connection: the warm-up plus the steady window that is actually measured. */
        const val MEASURE_READ_MS = 7_000L
        /** A steady window shorter than this is not a measurement — the sample is discarded. */
        const val MIN_STEADY_MS = 3_000L
        /** Probe-client read timeout; must exceed the read window so a slow link is measured, not cut. */
        const val MEASURE_READ_TIMEOUT_MS = 15_000L
        /** Outer bound on the whole measurement (covers connect + stall) so callers stay bounded. */
        const val MEASURE_TOTAL_TIMEOUT_MS = 12_000L
        const val READ_BUFFER_BYTES = 64 * 1024
    }
}
