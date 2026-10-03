package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Payload for POST /api/prov/devices/capability-report (boomio bsm).
 *
 * Shape matches the WS3 device-caps/1 contract exactly — the backend stores the body
 * verbatim under the device row's `capabilities` column and the bsm dashboard renders
 * it (schema key is the discriminator). The app is a _reporter_: every field below is
 * written by [com.nuvio.tv.core.device.DeviceCapabilityProbe] and never read back.
 */
@JsonClass(generateAdapter = true)
data class DeviceCapabilityReportDto(
    val schema: String = "device-caps/1",
    /** ISO-8601 UTC instant the report was assembled, e.g. 2026-09-03T10:15:30Z. */
    val reportedAt: String = "",
    val device: DeviceInfoDto = DeviceInfoDto(),
    val app: AppInfoDto = AppInfoDto(),
    val display: DisplayCapabilitiesDto = DisplayCapabilitiesDto(),
    val decode: DecodeCapabilitiesDto = DecodeCapabilitiesDto(),
    val effective: EffectiveCapabilitiesDto = EffectiveCapabilitiesDto(),
    /** Active-link measurement + cheap OS network facts. Optional — absent until the box has a number or an active network. Persists verbatim under `devices.capabilities.network.*` (WS3 stores the whole body). */
    val network: NetworkCapabilitiesDto? = null
)

/** Identifies the install to the fleet view. */
@JsonClass(generateAdapter = true)
data class DeviceInfoDto(
    /** Install-scoped id from [com.nuvio.tv.core.sync.SyncClientIdentity] — the row key on bsm. */
    val installId: String = "",
    val manufacturer: String = "",
    val model: String = "",
    /** Silicon vendor, e.g. "Amlogic" — the reliable "which box is this" when [model] is a reseller alias. Null below API 31 (Build.SOC_* is not exposed earlier). */
    val socManufacturer: String? = null,
    /** Silicon part, e.g. "S905X4". Same null caveat as [socManufacturer]. */
    val socModel: String? = null,
    /** Retail/marketing name when the build advertises one (ro.product.marketname, read via hidden SystemProperties). Best-effort and often null — notably on Fire OS, where an AFT*-code → retail map is a separate follow-up. */
    val marketName: String? = null,
    val sdkInt: Int = 0,
    val androidRelease: String = "",
    val abis: List<String> = emptyList()
)

/** Which NuvioTV build produced this report. */
@JsonClass(generateAdapter = true)
data class AppInfoDto(
    val applicationId: String = "",
    val versionName: String = "",
    val versionCode: Int = 0,
    val flavor: String = "",
    val debug: Boolean = false
)

/** What the box currently outputs to the connected TV (reads EDID through DisplayManager, no Activity needed). */
@JsonClass(generateAdapter = true)
data class DisplayCapabilitiesDto(
    /** Current display-mode resolution the sink is running. 0 when unknown. */
    val sinkWidth: Int = 0,
    val sinkHeight: Int = 0,
    /** Refresh rate of the *current* display mode. */
    val maxRefreshHz: Int = 0,
    /** Highest refresh across every mode the sink advertises (Display.supportedModes, straight from the TV's EDID). Distinct from [maxRefreshHz]: a 120 Hz panel sitting at 60 Hz reads 60 vs 120 here. 0 when unknown. */
    val maxSupportedRefreshHz: Int = 0,
    /** True when the platform reports a wide-colour-gamut path to the sink (Display.isWideColorGamut, the public API 26+ capability check). Null below API 26 or when unknown. */
    val wideColorGamut: Boolean? = null,
    /** Tokens in fixed order: "dv", "hdr10", "hdr10plus", "hlg". */
    val sinkHdrTypes: List<String> = emptyList(),
    /** False pre-API-24 or when the platform returned null — treat sinkHdrTypes as unverified. */
    val hdrCapsKnown: Boolean = false
)

/** Per-codec hardware/software decode story. A codec key is null only when no decoder at all exists. */
@JsonClass(generateAdapter = true)
data class DecodeCapabilitiesDto(
    val avc: CodecCapabilityDto? = null,
    val hevc: CodecCapabilityDto? = null,
    val av1: CodecCapabilityDto? = null,
    val vp9: CodecCapabilityDto? = null,
    val dolbyVision: DolbyVisionCapabilityDto? = null
)

@JsonClass(generateAdapter = true)
data class CodecCapabilityDto(
    val hw: Boolean = false,
    val sw: Boolean = false,
    /**
     * Widest/tallest single dimension any hardware decoder advertises (VideoCapabilities
     * upper bound). maxWidth x maxHeight is an upper ceiling, not a guaranteed combination.
     * 0 when the platform exposes no capability range.
     */
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    /** HEVC-only today: "main10", "hdr10", "hdr10plus" — the HDR profile flags the decoders advertise. */
    val hdrProfiles: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class DolbyVisionCapabilityDto(
    /** Advertised DV profiles as "dvhe.07.06" (P7/Dtb), "dvhe.08.06" (P8/St), "dvhe.05.06" (P5/Stn). */
    val profiles: List<String> = emptyList(),
    /**
     * True when a *hardware* video/dolby-vision decoder exists, even if it advertises no
     * profile (some Amlogic boxes hide Profile 8 — see DolbyVisionCodecFallback). Such a box
     * can still decode DV8.1; `profiles` alone would understate it. Software DV decoders are
     * deliberately not counted — they could never present a real DV stream.
     */
    val decoderPresent: Boolean = false
)

/** decode ∩ sink — the ceiling that matters for sending streams to this box+TV pair. */
@JsonClass(generateAdapter = true)
data class EffectiveCapabilitiesDto(
    /** "WxH" the sink runs at, capped to the widest hardware decode ceiling; "" when unknown. */
    val maxResolution: String = "",
    /** HDR tokens the sink advertises AND the box can decode, fixed order: dv, hdr10, hdr10plus, hlg. */
    val hdrUsable: List<String> = emptyList()
)

/**
 * The active link the box reports through. `estimated_bandwidth_mbps` is a *measured* value
 * ([com.nuvio.tv.core.network.NetworkMeter]) and is deliberately **not** mirrored into operator-policy
 * columns (`devices.bandwidth_cap_mbps` etc.) — measurement and operator policy stay separate. The other
 * fields are filled only when the OS exposes them cheaply (all-`null` if there is no active network).
 * Wire names are snake_case to match the bandwidth-report plan contract.
 */
@JsonClass(generateAdapter = true)
data class NetworkCapabilitiesDto(
    /** Transport token: "wifi", "ethernet", "cellular", "vpn", or "other". Null when the OS exposes no active network. */
    val type: String? = null,
    /** Last download measurement, Mbps, 1 decimal. Null before the first successful measure. */
    @Json(name = "estimated_bandwidth_mbps")
    val estimatedBandwidthMbps: Double? = null,
    /** Wi-Fi band in GHz (2.4/5/6). Null off Wi-Fi or when the OS hides it. */
    @Json(name = "frequency_ghz")
    val frequencyGhz: Double? = null,
    /** Wi-Fi RSSI in dBm. Null off Wi-Fi. */
    @Json(name = "signal_strength_dbm")
    val signalStrengthDbm: Int? = null,
    /** True when the active network is metered (NET_CAPABILITY_NOT_METERED absent). Null when unknown. */
    @Json(name = "is_metered")
    val isMetered: Boolean? = null
)
