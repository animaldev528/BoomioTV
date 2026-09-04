package com.nuvio.tv.core.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sync.SyncClientIdentity
import com.nuvio.tv.data.remote.api.BsmApi
import com.nuvio.tv.data.remote.dto.CodecCapabilityDto
import com.nuvio.tv.data.remote.dto.DeviceCapabilityReportDto
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

/**
 * Periodically reports this install's decode + sink capabilities to boomio bsm's
 * POST /api/prov/devices/capability-report (row key = [SyncClientIdentity] install id).
 *
 * The bsm fleet view is what operators watch to know what each box+TV pair on the network can
 * take (see docs/alpha/plans/device-capability-report.md). This class is deliberately best-effort
 * and inert without a BSM url:
 *  - no report when [BuildConfig.BSM_BASE_URL] is blank (the out-of-the-box build);
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
    private val syncClientIdentity: SyncClientIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false
    @Volatile private var heartbeatJob: Job? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    fun start() {
        if (started) return
        started = true
        if (BuildConfig.BSM_BASE_URL.isBlank()) {
            Log.i(TAG, "BSM_BASE_URL blank — device capability reporting disabled")
            return
        }
        installDisplayListener()
        heartbeatJob = scope.launch {
            delay(INITIAL_REPORT_DELAY_MS)
            reportOnce(force = true)
            while (isActive) {
                delay(HEARTBEAT_MS)
                reportOnce(force = true)
            }
        }
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
        }
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
        val bytes = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private companion object {
        const val TAG = "DevCapReporter"
        const val PREFS_NAME = "nuvio_device_capability_report"
        const val KEY_LAST_SIGNATURE = "last_signature"
        const val KEY_LAST_SENT_AT = "last_sent_at_elapsed"

        /** First report shortly after boot so it doesn't fight startup work. */
        const val INITIAL_REPORT_DELAY_MS = 15_000L
        /** Heartbeat per the approved plan (12h); also the re-report cadence while the process lives. */
        const val HEARTBEAT_MS = 12L * 60L * 60L * 1000L
        /** Collapse bursty EDID events into one re-probe. */
        const val DISPLAY_CHANGE_DEBOUNCE_MS = 2_000L
    }
}
