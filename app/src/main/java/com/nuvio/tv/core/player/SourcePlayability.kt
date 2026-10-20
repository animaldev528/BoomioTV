package com.nuvio.tv.core.player

import android.media.MediaCodecList
import android.util.Log
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.settings.MemoryBudget

/**
 * Client-side codec gate.
 *
 * The media plane filters by the ceiling this box advertises, but it does not know which
 * decoders the box actually has: a Fire TV Stick has no VC-1 decoder at all, so a VC-1
 * remux was offered, selected, and answered with `ERROR_CODE_DECODER_INIT_FAILED` after
 * the user had already left the picker. This checks the source's codec against
 * [MediaCodecList] before the source is shown or auto-selected.
 *
 * Font of truth is the server's parsed metadata (`clientResolve.stream.raw.parsed.codec`);
 * the filename/name text is only a fallback for groups that do not carry it. When the
 * source says nothing about its codec, it is allowed — a false negative here costs a
 * playable stream, which is worse than the error the player already handles.
 */
object SourcePlayability {
    private const val TAG = "SourcePlayability"

    private const val MIME_AVC = "video/avc"
    private const val MIME_HEVC = "video/hevc"
    private const val MIME_AV1 = "video/av01"
    private const val MIME_VP9 = "video/x-vnd.on2.vp9"
    private const val MIME_MPEG2 = "video/mpeg2"
    /** VC-1 ships under two MIME names depending on container. */
    private val VC1_MIMES = listOf("video/wvc1", "video/x-ms-wmv")

    /** Decoder MIME types on this device. Kept process-wide: the probe is not cheap. */
    private val decoderMimes: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { !it.isEncoder }
                .flatMap { it.supportedTypes.asSequence() }
                .toSet()
        }.onFailure { Log.w(TAG, "MediaCodecList probe failed; allowing every codec", it) }
            .getOrDefault(emptySet())
    }

    /** False only when the probe succeeded and this family is absent. */
    private fun hasDecoder(mime: String): Boolean = decoderMimes.isEmpty() || mime in decoderMimes

    /** "avc" | "hevc" | "av1" | "vc1" | "vp9" | "mpeg2", or null when the source is silent. */
    fun codecFamily(stream: Stream): String? {
        val parsed = stream.clientResolve?.stream?.raw?.parsed?.codec.orEmpty()
        val haystack = buildString {
            append(parsed).append(' ')
            append(stream.behaviorHints?.filename.orEmpty()).append(' ')
            append(stream.name.orEmpty()).append(' ')
            append(stream.title.orEmpty()).append(' ')
            append(stream.description.orEmpty())
        }.lowercase()
        return when {
            // Check before the others: "vc-1"/"wvc1"/"wmv" never contain the other tokens,
            // but the AV1 test must not fire on a stray "av1" inside a longer word.
            haystack.contains("vc-1") || haystack.contains("vc1") || haystack.contains("wvc1") ||
                haystack.contains("wmv") || haystack.contains("xvid") || haystack.contains("divx") -> "vc1"
            Regex("\\bav1\\b").containsMatchIn(haystack) -> "av1"
            haystack.contains("hevc") || haystack.contains("h.265") || haystack.contains("h265") ||
                haystack.contains("x265") -> "hevc"
            haystack.contains("avc") || haystack.contains("h.264") || haystack.contains("h264") ||
                haystack.contains("x264") -> "avc"
            haystack.contains("vp9") -> "vp9"
            haystack.contains("mpeg2") || haystack.contains("mpg2") -> "mpeg2"
            else -> null
        }
    }

    /** Source height in lines, from parsed metadata or the filename. Null when unknown. */
    fun heightOf(stream: Stream): Int? {
        val parsed = stream.clientResolve?.stream?.raw?.parsed?.resolution.orEmpty()
        val text = if (parsed.isNotBlank()) parsed else {
            (stream.behaviorHints?.filename.orEmpty() + ' ' + stream.name.orEmpty())
        }
        return Regex("(\\d{3,4})[pi]", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /**
     * Whether this device has a decoder for the source. Unknown codec or an unreadable
     * codec list both answer true.
     */
    fun isCompatible(stream: Stream): Boolean = when (codecFamily(stream)) {
        null -> true
        "avc" -> hasDecoder(MIME_AVC)
        "hevc" -> hasDecoder(MIME_HEVC)
        "av1" -> hasDecoder(MIME_AV1)
        "vp9" -> hasDecoder(MIME_VP9)
        "mpeg2" -> hasDecoder(MIME_MPEG2)
        "vc1" -> VC1_MIMES.any { hasDecoder(it) }
        else -> true
    }

    /** Approximate bitrate in Mbps, when both size and duration are known. */
    fun estimatedBitrateMbps(stream: Stream): Int? {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val sizeBytes = stream.behaviorHints?.videoSize ?: stream.clientResolve?.stream?.raw?.size
        val rawDuration = parsed?.duration
        if (sizeBytes == null || sizeBytes <= 0 || rawDuration == null || rawDuration <= 0) return null
        // Duration arrives in ms; values this small are seconds from an older payload.
        val durationMs = if (rawDuration < 100_000L) rawDuration * 1_000L else rawDuration
        val mbps = sizeBytes.toDouble() * 8.0 / (durationMs / 1_000.0) / 1_000_000.0
        return mbps.toInt()
    }

    /** AVC at 1080p or below: the shape a 1 GB box can decode without heroes. */
    fun isLightweight(stream: Stream): Boolean =
        codecFamily(stream) == "avc" && (heightOf(stream) ?: 1080) <= 1080

    /**
     * On the low-RAM tier, put streams this box can play cheaply first: AVC <=1080p within
     * the tier's bitrate ceiling, then any other AVC <=1080p, then everything else.
     * Ordering only — nothing is hidden, so the picker still shows every compatible source.
     */
    fun orderForDevice(streams: List<Stream>): List<Stream> {
        if (!MemoryBudget.isLowRamTier || streams.size < 2) return streams
        val bitrateCeiling = MemoryBudget.preferredMaxBitrateMbps
        return streams.sortedBy { stream ->
            when {
                isLightweight(stream) && (estimatedBitrateMbps(stream) ?: 0) <= bitrateCeiling -> 0
                isLightweight(stream) -> 1
                else -> 2
            }
        }
    }
}
