package com.nuvio.tv.data.remote.dto

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
    val effective: EffectiveCapabilitiesDto = EffectiveCapabilitiesDto()
)

/** Identifies the install to the fleet view. */
@JsonClass(generateAdapter = true)
data class DeviceInfoDto(
    /** Install-scoped id from [com.nuvio.tv.core.sync.SyncClientIdentity] — the row key on bsm. */
    val installId: String = "",
    val manufacturer: String = "",
    val model: String = "",
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
    val maxRefreshHz: Int = 0,
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
