package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.companionRestBaseUrl
import com.nuvio.app.features.boomio.createBoomioHttpClient
import com.nuvio.tv.core.device.DeviceDecodeCeiling
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The half of the engine failover that an error code cannot reach.
 *
 * ## Why an error code is not enough
 *
 * [maybeAutoSwitchInternalPlayerOnStartupError] reacts to a `PlaybackException`. That covers a
 * decoder that **throws**. It does not cover a decoder that **refuses and is replaced**: on the
 * Fire TV Stick every video decoder stops at 1920x1088, so `OMX.MTK.VIDEO.DECODER.HEVC` rejects
 * `configure` with `-EINVAL` (`configureCodec(-22)`), Android silently substitutes
 * `OMX.google.*` software decoding, and **no exception is ever raised**. The surface stays black,
 * the spinner never resolves, and the error-keyed path has nothing to key on. The viewer's only
 * symptom is "stuck on starting stream" — which is exactly the report that produced this file.
 *
 * What *does* arrive, on the tracks-changed callback that media3 fires anyway, is the verdict:
 *
 *     VIDEO_TRACK: mime=video/hevc size=3840x2160 support=NO_EXCEEDS_CAPABILITIES
 *
 * `FORMAT_EXCEEDS_CAPABILITIES` is media3 stating, in the same vocabulary the device already
 * publishes to bsf, that this offer is beyond it. So the trigger belongs here — on the verdict,
 * next to the VC-1 bypass in [updateAvailableTracks] — rather than on an exception that this
 * failure mode never throws.
 *
 * ## Why the device's own ceiling has to agree
 *
 * media3 is sometimes **wrongly conservative**, which is why the `selectAllTracks` override
 * upgrades `FORMAT_EXCEEDS_CAPABILITIES` to `FORMAT_HANDLED` for <=1080p AVC/HEVC. Switching
 * engines on media3's word alone would therefore move healthy playback to libmpv on every such
 * device — a regression in exchange for a fix.
 *
 * So this module never acts on one opinion. It requires **both**:
 *
 *  1. media3 says `FORMAT_EXCEEDS_CAPABILITIES` for the effective video track, **and**
 *  2. the stream's height is above the ceiling this device declared for that codec family in
 *     [DeviceDecodeCeiling] — the same per-codec map the box already sends to bsf as
 *     `codecCapsMaxRes`.
 *
 * Two independent measurements of the same claim, and the switch happens only where they agree.
 * A device whose decoders are fine (the Shield) never satisfies (2); a 1080p offer on the
 * Firestick never satisfies it either, because 1080 is not above its declared 1080 ceiling. Only
 * the genuinely-over-ceiling offer moves — which is the case that spins today.
 *
 * Nothing here fires when the ceiling has never been probed: an empty map means "we do not know
 * what this box can do", and "we do not know" must never be read as "it cannot".
 */

/**
 * One offer the device's declared ceiling says it cannot decode.
 *
 * Carries the whole diagnosis so the log line, the server report and the switch message are all
 * built from the same record rather than three independent readings of the player's state.
 */
internal data class DecodeCeilingVerdict(
    val codecFamily: String,
    val declaredCeilingHeight: Int,
    val formatHeight: Int,
    val formatWidth: Int,
    val mimeType: String,
    val codecs: String?,
    val media3Support: String,
    val signature: String,
) {
    /** Human-readable one-liner: the log line, the switch reason and the report's comment. */
    val summary: String
        get() = "$mimeType ${codecs ?: "(no codec string)"} ${formatWidth}x$formatHeight " +
            "exceeds this device's declared $codecFamily ceiling of ${declaredCeilingHeight}p " +
            "(media3 verdict: $media3Support)"
}

/**
 * Maps a media3 mime type onto the codec-family key bsf's gate and the capability probe both use.
 *
 * Returns `null` for a video mime outside the enumerated families: the caller falls back to the
 * probe's `other` catch-all, which is the weakest decoder on the box and therefore the only
 * ceiling we can promise. Deliberately not a guess at the codec from the container.
 */
internal fun decodeCodecFamilyForMime(mimeType: String?): String? {
    val mime = mimeType?.trim()?.lowercase() ?: return null
    return when {
        mime == "video/avc" -> "avc"
        mime == "video/hevc" -> "hevc"
        mime == "video/x-vnd.on2.vp9" -> "vp9"
        mime == "video/av01" -> "av1"
        else -> null
    }
}

/**
 * Parses one probe resolution token (`1080p`, `2160p`, `4k`) into a line count.
 *
 * Returns `null` rather than a guess for anything that is not a resolution: an unreadable token
 * must leave the ceiling unknown, because a wrong ceiling produces a wrong switch.
 */
internal fun parseDeclaredCeilingHeight(token: String): Int? {
    val value = token.trim().lowercase()
    if (value.isEmpty()) return null
    when (value) {
        "4k", "uhd" -> return 2160
        "8k" -> return 4320
    }
    val digits = value.takeWhile { it.isDigit() }
    val height = digits.toIntOrNull() ?: return null
    // No real video format is shorter than 240 lines; a smaller number means the token was not a
    // resolution at all, and treating it as one would understate the ceiling.
    return height.takeIf { it >= 240 }
}

/**
 * The declared ceiling for one codec family, from the probe's `avc:1080p,hevc:2160p,other:1080p`.
 *
 * An exact family match wins. Failing that the `other` catch-all is used — the probe writes it as
 * the weakest decoder on the box, which is what an unenumerated codec has to be measured against.
 * `null` when neither is present, meaning the ceiling for this offer is unknown.
 */
internal fun declaredCeilingHeightForFamily(declaredMaxResByCodec: String, family: String): Int? {
    var catchAll: Int? = null
    for (entry in declaredMaxResByCodec.split(',')) {
        val key = entry.substringBefore(':', "").trim().lowercase()
        val value = entry.substringAfter(':', "").trim()
        if (key.isEmpty() || value.isEmpty()) continue
        val height = parseDeclaredCeilingHeight(value) ?: continue
        if (key == family) return height
        if (key == "other") catchAll = height
    }
    return catchAll
}

/**
 * The verdict for the effective video track, or `null` when the two measurements do not agree.
 *
 * Read-only and side-effect-free apart from one log line on the "media3 says no but no ceiling is
 * known" path, which is the interesting near-miss: it is the shape a device takes before its
 * capability report has ever landed.
 */
@UnstableApi
internal fun PlayerRuntimeController.decodeCeilingVerdict(
    format: Format?,
    media3Support: Int,
): DecodeCeilingVerdict? {
    if (format == null) return null
    // Only the one verdict that means "a decoder exists but cannot take this size". A missing
    // decoder (UNSUPPORTED_SUBTYPE) or DRM (UNSUPPORTED_DRM) is a different problem with a
    // different recovery, and HANDLED / UNSUPPORTED_TYPE are not verdicts about this offer.
    if (media3Support != C.FORMAT_EXCEEDS_CAPABILITIES) return null
    val height = format.height
    val width = format.width.coerceAtLeast(0)
    if (height <= 0) return null

    val declared = DeviceDecodeCeiling.maxResByCodec()
    if (declared.isBlank()) {
        Log.i(
            PlayerRuntimeController.TAG,
            "DECODE_CEILING: media3 reports ${Util.getFormatSupportString(media3Support)} for " +
                "${format.sampleMimeType} ${width}x$height, but no decode ceiling has been probed " +
                "on this device yet — not acting on media3's word alone"
        )
        return null
    }

    val family = decodeCodecFamilyForMime(format.sampleMimeType) ?: "other"
    val ceiling = declaredCeilingHeightForFamily(declared, family) ?: return null
    if (height <= ceiling) return null

    return DecodeCeilingVerdict(
        codecFamily = family,
        declaredCeilingHeight = ceiling,
        formatHeight = height,
        formatWidth = width,
        mimeType = format.sampleMimeType ?: "unknown",
        codecs = format.codecs,
        media3Support = Util.getFormatSupportString(media3Support),
        // The stream URL is the identity of the *playback*, so a replay of the same title after a
        // reload is treated as new. It is a key only — it is never sent anywhere.
        signature = "${currentStreamUrl}|${format.sampleMimeType}|${format.codecs}|${width}x$height"
    )
}

/**
 * Report a decode incompatibility the device proved by declaring a ceiling and then being handed
 * something above it, and move playback to the software-decoding engine.
 *
 * Called from [updateAvailableTracks] — the tracks-changed callback — because that is where the
 * verdict arrives for a decoder that fails silently. Reports **before** switching so the operator
 * learns about the offer even when the switch itself is not possible (a failover already spent on
 * this playback, or playback already past first frame).
 *
 * Each (stream, format) is handled once per player instance: the tracks callback fires repeatedly
 * for one stream, and neither the report nor the switch should repeat with it.
 */
@UnstableApi
internal fun PlayerRuntimeController.maybeFailOverForDecodeIncompatibility(
    format: Format?,
    media3Support: Int,
) {
    val verdict = decodeCeilingVerdict(format, media3Support) ?: return
    if (!decodeIncompatibilityHandledSignatures.add(verdict.signature)) return

    Log.w(PlayerRuntimeController.TAG, "DECODE_CEILING: ${verdict.summary}")
    reportDecodeIncompatibility(verdict)

    // allowEngineFailover = true: the single hop this guards already lives inside the failover as
    // startupEngineFailoverTriggered, so a second switch cannot happen from here.
    maybeAutoSwitchInternalPlayerOnStartupError(
        detailedError = verdict.summary,
        allowEngineFailover = true,
        decodeCeilingVerdict = verdict.summary
    )
}

/**
 * Tells the server what this device could not decode.
 *
 * `POST {bsc}/api/telemetry/report` — the existing playback-report ingest, whose body schema is
 * `.passthrough()`: the flat `device_type`/`video_codec`/`resolution`/`error` keys are the ones it
 * names, and the `ecosystem`/`model`/`sourceCodec`/`imdbId`/`issues`/`comment` keys are the ones
 * its writer reads. No server change is needed for this to land in `playback_reports`.
 *
 * Fire-and-forget, and silent on failure: this is diagnosis, and a device that cannot reach the
 * server is exactly the device that must still be able to play. The log line above is the on-box
 * record; this is the fleet-wide one.
 *
 * ⚠️ The format is reported; the **URL never is**. `currentStreamUrl` carries the per-stream relay
 * token, and a report is a record that outlives the stream.
 */
internal fun PlayerRuntimeController.reportDecodeIncompatibility(verdict: DecodeCeilingVerdict) {
    val base = BoomioConfig.companionRestBaseUrl.trimEnd('/')
    if (base.isBlank()) return

    val deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(" ")
        .take(64)
    val imdbId = contentId?.trim()?.takeIf { it.startsWith("tt") }

    val payload = buildJsonObject {
        put("device_type", deviceModel)
        put("model", deviceModel)
        put("ecosystem", "androidtv")
        put("video_codec", verdict.codecFamily)
        put("sourceCodec", verdict.codecFamily)
        put("resolution", "${verdict.formatWidth}x${verdict.formatHeight}")
        put("success", false)
        put("error", "FORMAT_EXCEEDS_CAPABILITIES")
        put("issues", buildJsonArray { add(JsonPrimitive("decode-incompatible")) })
        put("comment", verdict.summary.take(400))
        imdbId?.let { put("imdbId", it) }
    }

    scope.launch(Dispatchers.IO) {
        runCatching {
            decodeIncompatibilityHttpClient.post("$base/api/telemetry/report") {
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        }.onFailure {
            Log.i(
                PlayerRuntimeController.TAG,
                "DECODE_CEILING: report not delivered (${it.javaClass.simpleName})"
            )
        }
    }
}

/**
 * One client for the process.
 *
 * Not per-report and not on the controller: the report is rare, and an unused OkHttp client per
 * playback would cost more than it saves. File-private so nothing else adopts it by accident.
 */
private val decodeIncompatibilityHttpClient: HttpClient by lazy { createBoomioHttpClient() }
