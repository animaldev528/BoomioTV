package com.nuvio.tv.core.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.tv.core.network.NetworkMeter
import com.nuvio.tv.core.sync.SyncClientIdentity
import com.nuvio.tv.data.remote.api.BsmApi
import com.nuvio.tv.data.remote.dto.CodecCapabilityDto
import com.nuvio.tv.data.remote.dto.DeviceCapabilityReportDto
import com.nuvio.tv.data.remote.dto.NetworkCapabilitiesDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Periodically reports this install's decode + sink capabilities to boomio bsm's
 * POST /api/prov/devices/capability-report (row key = [SyncClientIdentity] install id).
 *
 * The bsm fleet view is what operators watch to know what each box+TV pair on the network can
 * take (see docs/alpha/plans/device-capability-report.md). This class is deliberately best-effort
 * and inert without a BSM url:
 *  - no report when [BoomioConfig.bsmBaseUrl] is blank (never on a build with a compiled floor);
 *  - no crash, no queue, no user-visible state on failure — the next tick simply retries;
 *  - identical consecutive reports are suppressed so a display-listener storm (resolution/HDR
 *    re-negotiation) doesn't spam the fleet table — but a report always goes out at least once per
 *    heartbeat so bsm's last_seen stays fresh.
 *
 * Start once from [com.nuvio.tv.NuvioApplication.onCreate]; [start] is idempotent.
 */
@Singleton
class DeviceCapabilityReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bsmApi: BsmApi,
    private val syncClientIdentity: SyncClientIdentity,
    private val networkMeter: NetworkMeter
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false
    @Volatile private var heartbeatJob: Job? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    fun start() {
        if (started) return
        started = true
        // ⚠️ Published BEFORE the bsm gate below, and on its own dispatcher. The decode ceiling is
        // a local fact about this box+panel, and the media plane needs it whether or not bsm is
        // reachable — a box that cannot report must still be sent streams it can decode. The
        // loadOnce is what restores the last probed value after a restart, so the first /find of a
        // cold launch is already capped rather than waiting a heartbeat.
        DeviceDecodeCeiling.loadOnce(context)
        scope.launch { publishDecodeCeiling() }
        if (BoomioConfig.bsmBaseUrl.isBlank()) {
            Log.i(TAG, "bsm base url blank — device capability reporting disabled")
            return
        }
        installDisplayListener()
        heartbeatJob = scope.launch {
            delay(INITIAL_REPORT_DELAY_MS)
            // Boot report first (prompt, cached network value if any) so the fleet row appears ~15s
            // after boot; then a fresh measurement, re-reporting only if it changed the payload.
            reportOnce(force = true)
            measureThenReReportIfChanged()
            while (isActive) {
                delay(HEARTBEAT_MS)
                // Heartbeat: fresh measurement first, then the always-send report (keeps last_seen
                // honest AND carries the new number — the bandwidth-report plan's 12h cadence).
                measureThenReport()
            }
        }
    }

    /**
     * Probe the decode ceiling and publish it, saying plainly which of the three outcomes happened.
     *
     * ⚠️ **Why this is bounded, retried, and logs before it publishes.** This used to be a bare
     * `runCatching { withContext(Dispatchers.Default) { … } }.onSuccess { publish(); Log.i(…) }`.
     * On the Shield (2026-10-10) that block neither succeeded nor failed: `MediaCodecList`
     * enumeration was starved behind the boot-time overlay work, so the coroutine sat inside
     * `withContext` for the life of the process, and the box logged no ceiling line at all. A block
     * that never returns calls neither `onSuccess` nor `onFailure`, and — the subtler half — a throw
     * from *inside* `onSuccess` is not routed to `onFailure` either, so `publish()` failing would
     * have looked identical to the probe never running. Silence is the one outcome a best-effort
     * probe must never produce, because it is indistinguishable from a working one.
     *
     * (Nothing was actually broken on that box: [reportOnce] publishes the same value on its own
     * tick, 15 s later. This path exists so the *first* `/find` of a cold launch is already capped
     * rather than waiting for a report — and that half had silently stopped happening.)
     *
     * Each attempt is timed and retried a bounded number of times. ⚠️ `withTimeoutOrNull` cannot
     * interrupt `MediaCodecList` — it is a blocking platform call, not a cancellable one — so a
     * genuinely wedged attempt leaves its Default-dispatcher thread behind. That is exactly why the
     * attempt count is small and finite rather than a loop.
     */
    private suspend fun publishDecodeCeiling() {
        // A value restored from prefs by loadOnce is a real ceiling; there is nothing to probe.
        if (DeviceDecodeCeiling.maxResByCodec().isNotEmpty()) {
            Log.i(TAG, "Decode ceiling restored from prefs: ${DeviceDecodeCeiling.maxResolution()} " +
                "(${DeviceDecodeCeiling.maxResByCodec()})")
            return
        }
        for (attempt in 1..DECODE_PROBE_ATTEMPTS) {
            val effective = runCatching {
                withTimeoutOrNull(DECODE_PROBE_TIMEOUT_MS) {
                    withContext(Dispatchers.Default) { DeviceCapabilityProbe.effectiveCeiling(context) }
                }
            }.getOrElse { t ->
                Log.w(TAG, "Decode-ceiling probe threw on attempt $attempt/$DECODE_PROBE_ATTEMPTS " +
                    "— no cap will be sent", t)
                null
            }
            if (effective == null) {
                Log.w(TAG, "Decode-ceiling probe did not finish within ${DECODE_PROBE_TIMEOUT_MS}ms " +
                    "(attempt $attempt/$DECODE_PROBE_ATTEMPTS) — no cap will be sent")
                if (attempt < DECODE_PROBE_ATTEMPTS) delay(DECODE_PROBE_RETRY_MS)
                continue
            }
            // Guarded separately so a persistence failure cannot swallow the ceiling we just
            // measured — the media plane reads the in-memory value either way.
            val persistFailure = runCatching { DeviceDecodeCeiling.publish(context, effective) }
                .exceptionOrNull()
            val persistNote = if (persistFailure != null) " [persist failed: $persistFailure]" else ""
            Log.i(TAG, "Decode ceiling published: ${effective.decodeMaxResolution} " +
                "(${effective.decodeMaxResByCodec})$persistNote")
            return
        }
        // Not fatal: reportOnce republishes the ceiling on its tick, so this costs the cold-launch
        // head start, not the cap.
        Log.w(TAG, "Decode-ceiling probe gave up after $DECODE_PROBE_ATTEMPTS attempts — the report " +
            "tick will publish one instead")
    }

    /** One download measurement, then re-report only if it changed the payload (signature differs). */
    private suspend fun measureThenReReportIfChanged() {
        val before = networkMeter.lastMeasuredMbps()
        val measured = networkMeter.measure()
        if (measured != null && measured != before) {
            reportOnce(force = false)
        }
    }

    /** Fresh download measurement, then the always-send report. */
    private suspend fun measureThenReport() {
        networkMeter.measure()
        reportOnce(force = true)
    }

    /**
     * Re-probe when the display changes (TV EDID re-negotiation, resolution/HDR switch). A
     * [force=false] report is suppressed while capabilities are unchanged and the last successful
     * post is recent, so a burst of EDID events collapses into nothing instead of spamming bsm.
     */
    private fun installDisplayListener() {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        val handler = Handler(Looper.getMainLooper())
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = scheduleReReport()
            override fun onDisplayRemoved(displayId: Int) = scheduleReReport()
            override fun onDisplayChanged(displayId: Int) = scheduleReReport()
        }
        runCatching {
            dm.registerDisplayListener(listener, handler)
            displayListener = listener
        }.onFailure {
            Log.w(TAG, "DisplayManager listener unavailable", it)
        }
    }

    private fun scheduleReReport() {
        if (!started) return
        scope.launch {
            delay(DISPLAY_CHANGE_DEBOUNCE_MS)
            reportOnce(force = false)
        }
    }

    /**
     * @param force  true for the boot and heartbeat posts — always send, so bsm's last_seen stays
     *               fresh even when nothing changed. false only for display-change events, which
     *               dedupe against the last successful post (identical content + recent send = skip).
     */
    private suspend fun reportOnce(force: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSignature = prefs.getString(KEY_LAST_SIGNATURE, null)
        val lastSentAt = prefs.getLong(KEY_LAST_SENT_AT, 0L)

        val report = withContext(Dispatchers.Default) {
            DeviceCapabilityProbe.buildReport(context, syncClientIdentity.currentClientId())
                .copy(network = buildNetworkSection(networkMeter.lastMeasuredMbps()))
        }
        // Every report re-publishes the ceiling: a display or HDMI re-negotiation is exactly the
        // event that changes it (the display listener is what woke this tick), and reporting it is
        // the one place the values are already computed.
        DeviceDecodeCeiling.publish(context, report.effective)
        val signature = signatureOf(report)

        if (!force && lastSignature == signature && now - lastSentAt < HEARTBEAT_MS) {
            Log.d(TAG, "Capabilities unchanged since last report — skipping")
            return
        }

        try {
            val response = bsmApi.reportCapabilities(report)
            if (response.isSuccessful) {
                prefs.edit()
                    .putString(KEY_LAST_SIGNATURE, signature)
                    .putLong(KEY_LAST_SENT_AT, now)
                    .apply()
                Log.i(TAG, "Reported device capabilities to bsm " +
                    "${report.display.sinkWidth}x${report.display.sinkHeight} " +
                    "hdr=${report.effective.hdrUsable}")
            } else {
                Log.w(TAG, "Capability report rejected: HTTP ${response.code()} — will retry next tick")
            }
        } catch (t: Throwable) {
            // Best-effort: bsm offline / DNS / socket — next heartbeat or display change retries.
            Log.w(TAG, "Capability report failed — will retry next tick", t)
        }
    }

    /** Content hash (ignores reportedAt); unchanged capabilities yield an identical signature. */
    private fun signatureOf(report: DeviceCapabilityReportDto): String {
        val sb = StringBuilder()
        sb.append(report.device.installId).append('|')
        // Identity, not just capability: a fingerprint arriving or changing is a
        // real content change. On an in-place upgrade the installId is unchanged,
        // so this is what makes the box re-post once and lets bsm backfill
        // hw_fingerprint onto the row it already has.
        sb.append(report.device.hwFingerprint).append('|')
        sb.append(report.device.socManufacturer).append('/').append(report.device.socModel)
            .append('/').append(report.device.marketName).append('|')
        sb.append(report.display.sinkWidth).append('x').append(report.display.sinkHeight).append('|')
        sb.append(report.display.maxRefreshHz).append('/').append(report.display.maxSupportedRefreshHz)
            .append('/').append(report.display.wideColorGamut).append('|')
        sb.append(report.display.sinkHdrTypes).append('|')
        sb.append(report.display.hdrCapsKnown).append('|')
        fun codec(tag: String, c: CodecCapabilityDto?) {
            sb.append(tag).append(':')
                .append(c?.hw).append(c?.sw)
                .append(c?.maxWidth).append('x').append(c?.maxHeight)
                .append(c?.hdrProfiles).append('|')
        }
        codec("avc", report.decode.avc)
        codec("hevc", report.decode.hevc)
        codec("av1", report.decode.av1)
        codec("vp9", report.decode.vp9)
        report.decode.dolbyVision?.let {
            sb.append("dv:").append(it.decoderPresent).append(it.profiles).append('|')
        }
        report.network?.let { n ->
            sb.append("net:").append(n.type).append('/').append(n.isMetered)
                .append('/').append(n.frequencyGhz).append('/').append(n.signalStrengthDbm)
                .append('/').append(n.estimatedBandwidthMbps).append('|')
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /**
     * The report's `network` section: the measured value from the meter plus whatever the OS exposes
     * about the active link cheaply. Returns null only when there is neither a measured value nor an
     * active network — so an all-null object is never sent.
     */
    private fun buildNetworkSection(measuredMbps: Double?): NetworkCapabilitiesDto? {
        var type: String? = null
        var isMetered: Boolean? = null
        var frequencyGhz: Double? = null
        var signalStrengthDbm: Int? = null
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching
            val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            type = when {
                onWifi -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (onWifi) {
                val wifiInfo = context.getSystemService(WifiManager::class.java)?.connectionInfo
                wifiInfo?.frequency?.takeIf { it > 0 }?.let { frequencyGhz = it / 1000.0 }
                wifiInfo?.rssi?.takeIf { it != Int.MAX_VALUE }?.let { signalStrengthDbm = it }
            }
        }
        if (measuredMbps == null && type == null) return null
        return NetworkCapabilitiesDto(
            type = type,
            estimatedBandwidthMbps = measuredMbps,
            frequencyGhz = frequencyGhz,
            signalStrengthDbm = signalStrengthDbm,
            isMetered = isMetered
        )
    }

    private companion object {
        const val TAG = "DevCapReporter"
        const val PREFS_NAME = "nuvio_device_capability_report"
        const val KEY_LAST_SIGNATURE = "last_signature"
        const val KEY_LAST_SENT_AT = "last_sent_at_elapsed"

        /**
         * The boot-time decode probe's budget. Generous — a cold `MediaCodecList` enumeration on a
         * slow box is seconds, not milliseconds — but finite, because an unbounded probe is one
         * that can silently never happen (see [publishDecodeCeiling]).
         */
        const val DECODE_PROBE_TIMEOUT_MS = 20_000L
        /** Bounded: a wedged attempt cannot be interrupted, so each retry costs a thread. */
        const val DECODE_PROBE_ATTEMPTS = 3
        /** Long enough for the boot-time overlay work that starved the first attempt to subside. */
        const val DECODE_PROBE_RETRY_MS = 5_000L

        /** First report shortly after boot so it doesn't fight startup work. */
        const val INITIAL_REPORT_DELAY_MS = 15_000L
        /** Heartbeat per the approved plan (12h); also the re-report cadence while the process lives. */
        const val HEARTBEAT_MS = 12L * 60L * 60L * 1000L
        /** Collapse bursty EDID events into one re-probe. */
        const val DISPLAY_CHANGE_DEBOUNCE_MS = 2_000L
    }
}
