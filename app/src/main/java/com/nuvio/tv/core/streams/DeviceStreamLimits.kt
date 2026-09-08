package com.nuvio.tv.core.streams

/**
 * Per-codec decode limits a device can actually hold. Devices lie about what
 * they support (e.g. a box that measures ~120 Mbps on the link yet chokes on
 * 50 Mbps HEVC), so the user declares, per codec family, an allow-list entry and
 * a bitrate ceiling in Mbps. The resolver sends these to bsf on each `/find` so a
 * stream the box can't decode (or can't hold at its bitrate) never reaches the
 * picker — server-side is the only place it can work, since bsf strips
 * codec/bitrate before the client ever sees a stream.
 *
 * Canonical family keys match bsf's parser verbatim (lowercase `avc|hevc|av1|vp9`),
 * and `codecs=` / `codecCapsMbps=` are the query params bsf reads.
 */
enum class CodecFamily(
    /** Canonical key bsf recognizes on the query string. */
    val canonical: String,
    /** Human label for the settings UI. */
    val label: String,
    /** Suggested default ceiling in Mbps (owner example: AVC/HEVC ≤60, AV1 ≤12). */
    val defaultCeilingMbps: Int,
) {
    AVC("avc", "H.264 / AVC", 60),
    HEVC("hevc", "H.265 / HEVC", 60),
    AV1("av1", "AV1", 12),
    VP9("vp9", "VP9", 60),
}

/** One codec family's configured limit: whether the device decodes it at all, and
 *  at what bitrate ceiling (Mbps) when it does. */
data class CodecLimitConfig(
    val family: CodecFamily,
    val enabled: Boolean,
    val ceilingMbps: Int,
)

/** Runtime "device stream limits" filter. When [enabled] the resolver declares the
 *  enabled [CodecFamily] allow-list plus per-codec ceilings on every boomio /find;
 *  when disabled it sends neither param (today's behavior exactly).
 *
 *  Alongside the literal-detectable [CodecFamily] rows there is a reserved catch-all
 *  for content bsf cannot classify into a declared family (unknown codecs, and
 *  families that are declared in bsf's vocabulary but that the device didn't list,
 *  e.g. high-bitrate UHD BD remuxes whose filenames never spell a codec). It is NOT a
 *  [CodecFamily] member — [OTHER_CANONICAL] (`other`) is the reserved key bsf maps
 *  unclassified families to, so its ceiling lives here as [otherEnabled]/[otherMbps]
 *  and is appended to `codecCapsMbps=` only (never to the `codecs=` allow-list). */
data class DeviceStreamLimits(
    val enabled: Boolean,
    val limits: List<CodecLimitConfig>,
    /** Whether the catch-all `other` ceiling applies when [enabled]. */
    val otherEnabled: Boolean,
    /** Catch-all ceiling in Mbps for codec families bsf can't classify into a declared row. */
    val otherMbps: Int,
) {
    /** The rows that actually apply — empty when the master toggle is off. */
    val activeLimits: List<CodecLimitConfig>
        get() = if (enabled) limits.filter { it.enabled } else emptyList()

    /** True when the master toggle AND the catch-all toggle are both on. */
    val otherActive: Boolean
        get() = enabled && otherEnabled

    /** `codecs=` value: comma-joined canonical families of enabled rows. */
    fun codecsParam(): String? =
        activeLimits.joinToString(",") { it.family.canonical }.takeIf { it.isNotBlank() }

    /** `codecCapsMbps=` value: `family:mbps` pairs of enabled rows, plus the reserved
     *  `other:<n>` catch-all when [otherActive]. */
    fun codecCapsMbpsParam(): String? {
        val pairs = activeLimits.mapTo(mutableListOf()) { "${it.family.canonical}:${it.ceilingMbps}" }
        if (otherActive) pairs += "$OTHER_CANONICAL:$otherMbps"
        return pairs.joinToString(",").takeIf { it.isNotBlank() }
    }

    companion object {
        /** Reserved catch-all canonical key bsf maps unknown/undeclared families to. */
        const val OTHER_CANONICAL = "other"

        /** Suggested catch-all ceiling in Mbps (owner example: same as AVC/HEVC/VP9). */
        const val DEFAULT_OTHER_MBPS = 60

        /** Defaults — master on, every family + the catch-all enabled at their
         *  suggested ceilings (all-on preserves today's no-drops Gate-2 behavior). */
        fun defaults(): DeviceStreamLimits = DeviceStreamLimits(
            enabled = true,
            limits = CodecFamily.entries.map { family ->
                CodecLimitConfig(family, enabled = true, ceilingMbps = family.defaultCeilingMbps)
            },
            otherEnabled = true,
            otherMbps = DEFAULT_OTHER_MBPS,
        )
    }
}
