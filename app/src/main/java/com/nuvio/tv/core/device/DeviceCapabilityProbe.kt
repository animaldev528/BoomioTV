package com.nuvio.tv.core.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.provider.Settings
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Assembles a device-caps/1 report for the bsm fleet view (POST /api/prov/devices/capability-report).
 *
 * Three surfaces are probed — the box's identity, then the decode/sink pair that is intersected:
 *  - **box**: Build.MODEL plus, when the platform exposes them, SoC and market name — which physical
 *    device we're on, since [model] alone is often a reseller alias on ATV boxes.
 *  - **decode**: MediaCodecList — which of AVC/HEVC/AV1/VP9/DV the box decodes in hardware, its
 *    resolution ceiling, and the HDR profile flags its HEVC/DV decoders advertise.
 *  - **sink**: DisplayManager current display mode + supported modes + HdrCapabilities — what the
 *    connected TV actually accepts through the HDMI/EDID path. No Activity is required.
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

    /** build.prop key some ATV builds (Chromecast/Shield-class devices) set to the retail name. */
    private const val PROP_MARKET_NAME = "ro.product.marketname"

    /** Salt for [hardwareFingerprint]. Version-tagged so the scheme can rotate. */
    private const val FP_SALT = "boomio-devcap-fp-v1|"

    /** The Android 2.2 build that handed every device the SAME ANDROID_ID. */
    private const val LEGACY_ANDROID_ID_COLLISION = "9774d56d682e549c"

    private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    /**
     * Best-effort read of a build.prop property through the hidden SystemProperties API (reflection).
     * runCatching absorbs hidden-API policy differences across Android versions — a build that
     * refuses the call yields null (and may log an access warning), never a crash.
     */
    private fun readSystemProperty(name: String): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * A per-device fingerprint that survives a reinstall, so clearing the app and
     * re-enrolling rejoins the existing bsm row instead of duplicating it.
     *
     * ⚠️ ANDROID_ID deliberately, and NOT the usual suspects:
     *  - Build.FINGERPRINT / Build.MODEL are BUILD strings — identical on every
     *    unit of the same model + firmware, so they name a model, not a box.
     *  - Build.SERIAL / getSerial() reads "unknown" to an unprivileged app on
     *    Android 10+; a MAC needs restricted permissions and is randomised or
     *    stubbed on modern Android. Neither can be relied on.
     *  - ANDROID_ID is scoped to (app signing key, user, device), which is what
     *    makes it survive an uninstall/reinstall OF THIS APP — the exact path that
     *    produced nine identical "NVIDIA SHIELD Android TV" rows.
     *
     * ⚠️ Measured scope of "stable": survives a data clear, an uninstall/reinstall
     * and an app update; does NOT survive a factory reset (ANDROID_ID is
     * regenerated with the user data). Nothing an unprivileged app can read does.
     * A factory-reset-stable identity must be written at image/install time —
     * that is a deployment decision, not a code trick.
     *
     * SHA-256'd with a version-tagged salt: bsm only compares it for equality, so
     * the raw hardware-linked id never reaches the fleet view. Returns "" when the
     * platform will not supply one, and bsm then falls back to the install id —
     * so such a box behaves exactly as it does today.
     *
     * ⚠️ **This is the only derivation of it, and it has two readers that must
     * agree**: the capability report below, and the certificate registration body
     * ([com.nuvio.app.core.mtls.CertRegistrationRequestDto.hwFingerprint]), where
     * bsc uses it to find the box's canonical row. A second copy of the salt-and-
     * digest anywhere would be a fingerprint that silently disagrees with itself,
     * and the symptom would be a re-enrolled box quietly getting a second row —
     * the exact fault this exists to prevent.
     */
    @Suppress("HardwareIds")
    internal fun hardwareFingerprint(context: Context): String = try {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (raw.isNullOrBlank() || raw == LEGACY_ANDROID_ID_COLLISION) {
            ""
        } else {
            MessageDigest.getInstance("SHA-256")
                .digest((FP_SALT + raw).toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
                .take(32)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "ANDROID_ID unavailable — a re-enroll will not rejoin its existing row", t)
        ""
    }

    /**
     * Just the decode ceiling — the slice of a report the media plane reads.
     *
     * ⚠️ Exists so a caller that needs only the ceiling does not pay for a whole report: the
     * fingerprint, install id and app/version block are for bsm's fleet view, not for deciding what
     * to send a player. Still enumerates `MediaCodecList`, so it is a background-thread call.
     */
    fun effectiveCeiling(context: Context): EffectiveCapabilitiesDto =
        computeEffective(probeSink(context), probeDecode().dto)

    fun buildReport(context: Context, installId: String): DeviceCapabilityReportDto {
        val sink = probeSink(context)
        val decode = probeDecode()
        return DeviceCapabilityReportDto(
            schema = "device-caps/1",
            reportedAt = ISO_UTC.format(Date()),
            device = DeviceInfoDto(
                installId = installId,
                hwFingerprint = hardwareFingerprint(context),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                // Build.SOC_* only exists as a real field on API 31+; the SDK gate keeps the
                // getstatic (and its NoSuchFieldError on older boxes) from ever executing.
                socManufacturer =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.ifBlank { null } else null,
                socModel =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.ifBlank { null } else null,
                marketName = readSystemProperty(PROP_MARKET_NAME),
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
                maxSupportedRefreshHz = sink.maxSupportedRefreshHz,
                wideColorGamut = sink.wideColorGamut,
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
        /** Highest refresh across the sink's full advertised mode set, vs [refreshHz] = current mode. */
        val maxSupportedRefreshHz: Int,
        /** Null below API 26 / when the platform doesn't say. */
        val wideColorGamut: Boolean?,
        val hdrTypes: List<String>,
        val hdrCapsKnown: Boolean
    )

    private fun probeSink(context: Context): SinkSnapshot = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null) {
            SinkSnapshot(0, 0, 0, 0, null, emptyList(), false)
        } else {
            val mode = display.mode
            // supportedModes = every mode the TV's EDID advertises (API 23+; minSdk 24). Max refresh
            // is the panel's ceiling — the current mode may be running lower (60 Hz on a 120 Hz panel),
            // which is what refreshHz (below) reflects.
            val maxSupportedRefreshHz = runCatching {
                var best = mode.refreshRate
                for (m in display.supportedModes) {
                    if (m.refreshRate > best) best = m.refreshRate
                }
                best.roundToInt()
            }.getOrElse { mode.refreshRate.roundToInt() }
            val wideColorGamut =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) display.isWideColorGamut else null
            val hdrTypes: IntArray? = display.hdrCapabilities?.supportedHdrTypes
            val names = hdrTypes
                ?.toList()
                ?.mapNotNull { hdrTypeToken(it) }
                // Fixed report order: dv, hdr10, hdr10plus, hlg.
                ?.distinct()
                ?.sortedBy { HDR_ORDER.indexOf(it) }
                ?: emptyList()
            SinkSnapshot(
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                refreshHz = mode.refreshRate.roundToInt(),
                maxSupportedRefreshHz = maxSupportedRefreshHz,
                wideColorGamut = wideColorGamut,
                hdrTypes = names,
                hdrCapsKnown = hdrTypes != null
            )
        }
    }.getOrElse { e ->
        Log.w(TAG, "Sink probe failed; reporting unknown display", e)
        SinkSnapshot(0, 0, 0, 0, null, emptyList(), false)
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

    /**
     * The height tiers the media plane speaks, tallest first. A decode rectangle and a
     * release's own label are both snapped DOWN to one of these, so a "1080p" decoder that
     * advertises 1920x1088 and one that advertises 1920x1080 are the same ceiling.
     */
    private val DELIVERABLE_TIERS = intArrayOf(4320, 2160, 1440, 1080, 720, 480)

    /** The standard tier [height] satisfies, or 0 when it is below every tier / unknown. */
    private fun tier(height: Int): Int = DELIVERABLE_TIERS.firstOrNull { height >= it } ?: 0

    /** A tier back in bsf's own vocabulary — `maxres` reads "1080p"/"2160p", never "WxH". */
    private fun resToken(tier: Int): String = if (tier > 0) "${tier}p" else ""

    /**
     * The height tier this one hardware decoder can actually deliver, or 0 when it advertises
     * no usable range or has no hardware decoder at all.
     *
     * ⚠️ **The rectangle, not the two maxima.** [CodecCapabilityDto.maxWidth] and `maxHeight`
     * are independent upper bounds — "the widest frame" and "the tallest frame" — so the
     * largest 16:9 frame the pair guarantees is `min(maxHeight, maxWidth * 9 / 16)` tall.
     * Taking the two maxima separately is how this reported a combination no single decoder
     * advertised.
     */
    private fun decoderTier(cap: CodecCapabilityDto?): Int {
        if (cap == null || !cap.hw) return 0
        if (cap.maxWidth <= 0 || cap.maxHeight <= 0) return 0
        return tier(minOf(cap.maxHeight, cap.maxWidth * 9 / 16))
    }

    private fun computeEffective(
        sink: SinkSnapshot,
        decode: DecodeCapabilitiesDto
    ): EffectiveCapabilitiesDto {
        // Per-codec decode ceilings, and only from codecs with a real hardware decoder — a
        // software decoder cannot present a stream, so it must never raise this ceiling.
        val byCodec = linkedMapOf<String, Int>()
        for ((family, cap) in listOf(
            "avc" to decode.avc,
            "hevc" to decode.hevc,
            "av1" to decode.av1,
            "vp9" to decode.vp9
        )) {
            val t = decoderTier(cap)
            if (t > 0) byCodec[family] = t
        }
        val bestDecoderTier = byCodec.values.maxOrNull() ?: 0
        val weakestDecoderTier = byCodec.values.minOrNull() ?: 0

        // ⚠️ The media-plane ceiling is the DECODE ceiling, deliberately NOT clamped to the
        // sink. A panel limits what the box can *show*, not what it can *decode*: a 1080p TV
        // whose decoders take 2160 plays a 4K release downscaled, and clamping here would
        // strip the only available release of a title that exists in 4K. What must not be
        // sent is a resolution no decoder advertises — the Fire TV Stick, whose every decoder
        // stops at 1920x1088, was handed 3840x2076 HEVC and died in configureCodec(-22).
        val decodeMaxResolution = resToken(bestDecoderTier)
        val decodeMaxResByCodec = buildString {
            for ((family, t) in byCodec) {
                if (isNotEmpty()) append(',')
                append(family).append(':').append(resToken(t))
            }
            // The catch-all bsf's gate reserves for a stream whose codec word it cannot read
            // (`other`). Conservative on purpose: an unidentifiable 4K release goes to the
            // weakest decoder this box has, because that is the only one we can promise it to.
            if (weakestDecoderTier > 0) {
                if (isNotEmpty()) append(',')
                append("other:").append(resToken(weakestDecoderTier))
            }
        }

        // The report's own field keeps its "WxH" contract and its decode ∩ sink meaning —
        // bsm's fleet view reads it — but the pair is now one decoder's rectangle clamped to
        // the sink's mode, rather than a splice of two different decoders' maxima.
        val sinkTier = tier(sink.height)
        val capTier = when {
            bestDecoderTier > 0 && sinkTier > 0 -> minOf(sinkTier, bestDecoderTier)
            else -> maxOf(sinkTier, bestDecoderTier)
        }
        val maxResolution = if (capTier > 0) "${capTier * 16 / 9}x$capTier" else ""

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
            hdrUsable = usable,
            decodeMaxResolution = decodeMaxResolution,
            decodeMaxResByCodec = decodeMaxResByCodec
        )
    }
}
