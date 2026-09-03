package com.nuvio.tv.core.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.Display
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.dto.AppInfoDto
import com.nuvio.tv.data.remote.dto.CodecCapabilityDto
import com.nuvio.tv.data.remote.dto.DecodeCapabilitiesDto
import com.nuvio.tv.data.remote.dto.DeviceCapabilityReportDto
import com.nuvio.tv.data.remote.dto.DeviceInfoDto
import com.nuvio.tv.data.remote.dto.DisplayCapabilitiesDto
import com.nuvio.tv.data.remote.dto.DolbyVisionCapabilityDto
import com.nuvio.tv.data.remote.dto.EffectiveCapabilitiesDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Assembles a device-caps/1 report for the bsm fleet view (POST /api/prov/devices/capability-report).
 *
 * Two independent surfaces are probed and then intersected:
 *  - **decode**: MediaCodecList — which of AVC/HEVC/AV1/VP9/DV the box decodes in hardware, its
 *    resolution ceiling, and the HDR profile flags its HEVC/DV decoders advertise.
 *  - **sink**: DisplayManager current display mode + HdrCapabilities — what the connected TV
 *    actually accepts through the HDMI/EDID path. No Activity is required.
 *
 * `effective = decode ∩ sink` is the number that matters for sending streams: what this
 * box+TV pair can actually present. Read as a self-describing report, never as an answer key:
 * null codec = absent, empty profile list = unadvertised (not necessarily unsupported — see
 * [DolbyVisionCapabilityDto.decoderPresent] and the hardware/software caveats below).
 *
 * Runs best off the main thread (MediaCodecList enumeration + VideoCapabilities queries are
 * platform calls) — the reporter calls this on a background dispatcher.
 */
object DeviceCapabilityProbe {

    private const val TAG = "DeviceCapabilityProbe"
    private const val MIME_AVC = "video/avc"
    private const val MIME_HEVC = "video/hevc"
    private const val MIME_AV1 = "video/av01"
    private const val MIME_VP9 = "video/x-vnd.on2.vp9"
    private const val MIME_DV = "video/dolby-vision"

    private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun buildReport(context: Context, installId: String): DeviceCapabilityReportDto {
        val sink = probeSink(context)
        val decode = probeDecode()
        return DeviceCapabilityReportDto(
            schema = "device-caps/1",
            reportedAt = ISO_UTC.format(Date()),
            device = DeviceInfoDto(
                installId = installId,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdkInt = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE,
                abis = Build.SUPPORTED_ABIS.toList()
            ),
            app = AppInfoDto(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                flavor = BuildConfig.FLAVOR,
                debug = BuildConfig.DEBUG
            ),
            display = DisplayCapabilitiesDto(
                sinkWidth = sink.width,
                sinkHeight = sink.height,
                maxRefreshHz = sink.refreshHz,
                sinkHdrTypes = sink.hdrTypes,
                hdrCapsKnown = sink.hdrCapsKnown
            ),
            decode = decode.dto,
            effective = computeEffective(sink, decode.dto)
        )
    }

    // ---- Sink (TV over EDID, via DisplayManager) ----

    private class SinkSnapshot(
        val width: Int,
        val height: Int,
        val refreshHz: Int,
        val hdrTypes: List<String>,
        val hdrCapsKnown: Boolean
    )

    private fun probeSink(context: Context): SinkSnapshot = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null) {
            SinkSnapshot(0, 0, 0, emptyList(), false)
        } else {
            val mode = display.mode
            val hdrTypes: IntArray? = display.hdrCapabilities?.supportedHdrTypes
            val names = hdrTypes
                ?.mapNotNull { hdrTypeToken(it) }
                // Fixed report order: dv, hdr10, hdr10plus, hlg.
                ?.distinct()
                ?.sortedBy { HDR_ORDER.indexOf(it) }
                ?: emptyList()
            SinkSnapshot(
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                refreshHz = mode.refreshRate.roundToInt(),
                hdrTypes = names,
                hdrCapsKnown = hdrTypes != null
            )
        }
    }.getOrElse { e ->
        Log.w(TAG, "Sink probe failed; reporting unknown display", e)
        SinkSnapshot(0, 0, 0, emptyList(), false)
    }

    private val HDR_ORDER = listOf("dv", "hdr10", "hdr10plus", "hlg")

    private fun hdrTypeToken(type: Int): String? = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dv"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "hdr10"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10plus"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
        else -> null
    }

    // ---- Decode (MediaCodecList) ----

    /** Accumulator for one codec across every matching decoder. */
    private class CodecScan {
        var hw = false
        var sw = false
        var maxWidth = 0
        var maxHeight = 0
        val hdrProfiles = linkedSetOf<String>()

        fun toDto() = CodecCapabilityDto(
            hw = hw,
            sw = sw,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            hdrProfiles = hdrProfiles.toList()
        )
    }

    private class DvScan {
        var decoderPresent = false
        val profiles = linkedSetOf<String>()
    }

    private class DecodeScanResult(val dto: DecodeCapabilitiesDto)

    private fun probeDecode(): DecodeScanResult {
        // All-null DTO on enumeration failure (never throws outward; each step is guarded) —
        // null codec = unknown rather than a plausible "present but unsupported".
        val empty = DecodeScanResult(DecodeCapabilitiesDto())
        val list = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
        } catch (e: Throwable) {
            Log.w(TAG, "MediaCodecList unavailable", e)
            return empty
        }

        val avc = CodecScan()
        val hevc = CodecScan()
        val av1 = CodecScan()
        val vp9 = CodecScan()
        val dv = DvScan()

        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (mime in info.supportedTypes) {
                val isHevc = mime.equals(MIME_HEVC, ignoreCase = true)
                val isDv = mime.equals(MIME_DV, ignoreCase = true)
                val scan = when {
                    isHevc -> hevc
                    mime.equals(MIME_AVC, ignoreCase = true) -> avc
                    mime.equals(MIME_AV1, ignoreCase = true) -> av1
                    mime.equals(MIME_VP9, ignoreCase = true) -> vp9
                    else -> null
                }
                if (scan == null && !isDv) continue

                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                val isHw = isHardwareAccelerated(info)
                val isSw = isSoftwareOnly(info)

                if (isDv) {
                    // Only a hardware DV decoder makes DV usable; a software one isn't worth
                    // reporting as "present" (a 4k DV stream would never survive sw decode).
                    if (isHw) {
                        dv.decoderPresent = true
                        for (pl in caps.profileLevels.orEmpty()) {
                            dvToken(pl.profile)?.let { dv.profiles.add(it) }
                        }
                    }
                } else if (scan != null) {
                    if (isHw) scan.hw = true
                    if (isSw) scan.sw = true
                    // Resolution ceiling comes from hardware decoders only — a software codec is
                    // never the reason we'd cap a stream.
                    if (isHw) {
                        observeCeiling(scan, caps)
                        if (isHevc) {
                            for (pl in caps.profileLevels.orEmpty()) {
                                hevcToken(pl.profile)?.let { hevc.hdrProfiles.add(it) }
                            }
                        }
                    }
                }
            }
        }

        val dto = DecodeCapabilitiesDto(
            avc = avc.toDto().takeIf { it.hw || it.sw },
            hevc = hevc.toDto().takeIf { it.hw || it.sw },
            av1 = av1.toDto().takeIf { it.hw || it.sw },
            vp9 = vp9.toDto().takeIf { it.hw || it.sw },
            dolbyVision = if (dv.decoderPresent || dv.profiles.isNotEmpty()) {
                DolbyVisionCapabilityDto(
                    profiles = dv.profiles.toList(),
                    decoderPresent = dv.decoderPresent
                )
            } else {
                null
            }
        )
        return DecodeScanResult(dto)
    }

    /** Raises the widest/tallest single dimension any hardware decoder advertises. */
    private fun observeCeiling(scan: CodecScan, caps: MediaCodecInfo.CodecCapabilities) {
        val vc = caps.videoCapabilities ?: return
        runCatching {
            val w = vc.supportedWidths.upper
            if (w in 1..20000 && w > scan.maxWidth) scan.maxWidth = w
        }
        runCatching {
            val h = vc.supportedHeights.upper
            if (h in 1..20000 && h > scan.maxHeight) scan.maxHeight = h
        }
    }

    /** API 29+ uses platform flags; below that we infer from the component-name family. */
    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            !info.name.startsWith("OMX.google.", ignoreCase = true)
        }

    private fun isSoftwareOnly(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isSoftwareOnly
        } else {
            info.name.startsWith("OMX.google.", ignoreCase = true)
        }

    /** HEVC HDR profile flags a decoder advertises. Constants are API 21+/28+/30+ but inlined at compile. */
    private fun hevcToken(profile: Int): String? = when (profile) {
        CodecProfileLevel.HEVCProfileMain10 -> "main10"
        CodecProfileLevel.HEVCProfileMain10HDR10 -> "hdr10"
        CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "hdr10plus"
        else -> null
    }

    /** DV profile constants (API 24+; minSdk is 24). Tokens describe the profile family, not a bitstream. */
    private fun dvToken(profile: Int): String? = when (profile) {
        CodecProfileLevel.DolbyVisionProfileDvheDtb -> "dvhe.07.06" // P7
        CodecProfileLevel.DolbyVisionProfileDvheStn -> "dvhe.05.06" // P5
        CodecProfileLevel.DolbyVisionProfileDvheSt -> "dvhe.08.06"  // P8
        else -> null
    }

    // ---- Effective (decode ∩ sink) ----

    private fun computeEffective(
        sink: SinkSnapshot,
        decode: DecodeCapabilitiesDto
    ): EffectiveCapabilitiesDto {
        val hwCeilings = listOfNotNull(decode.avc, decode.hevc, decode.av1, decode.vp9)
            .filter { it.hw }

        // maxWidth/maxHeight are per-codec independent ceilings (a decoder advertises its widest
        // width and tallest height separately, not one guaranteed combo), so take the widest across
        // all HW codecs as the deliverable ceiling. Effective resolution is then the sink's current
        // mode, capped only when that ceiling is genuinely below it (e.g. only a 1080p VP9 decoder
        // on a 4k TV) — otherwise a 4k sink on a 4k-capable HEVC box stays 4k.
        val maxDeliverableW = hwCeilings.maxOfOrNull { it.maxWidth } ?: 0
        val maxDeliverableH = hwCeilings.maxOfOrNull { it.maxHeight } ?: 0
        val capW = if (maxDeliverableW > 0) sink.width.coerceAtMost(maxDeliverableW) else sink.width
        val capH = if (maxDeliverableH > 0) sink.height.coerceAtMost(maxDeliverableH) else sink.height
        val maxResolution = if (capW > 0 && capH > 0) "${capW}x${capH}" else ""

        val hevc = decode.hevc
        // HEVC Main10 is the base layer for HDR10/HLG. When the decoder advertises no profiles we
        // assume a HW HEVC decoder handles 10-bit (some platforms just don't list them); when it
        // does, require main10/hdr10.
        val hevcHandlesMain10 = hevc?.hw == true &&
            (hevc.hdrProfiles.isEmpty() || hevc.hdrProfiles.any { it == "main10" || it == "hdr10" })

        val usable = mutableListOf<String>()
        if (sink.hdrCapsKnown) {
            for (token in HDR_ORDER) {
                if (token !in sink.hdrTypes) continue
                val ok = when (token) {
                    "dv" -> decode.dolbyVision?.decoderPresent == true
                    "hdr10" -> hevcHandlesMain10
                    "hdr10plus" -> hevc?.hw == true && "hdr10plus" in hevc.hdrProfiles
                    "hlg" -> hevc?.hw == true
                    else -> false
                }
                if (ok) usable.add(token)
            }
        }

        return EffectiveCapabilitiesDto(
            maxResolution = maxResolution,
            hdrUsable = usable
        )
    }
}
