package com.nuvio.tv.core.device

import android.content.Context
import com.nuvio.tv.data.remote.dto.EffectiveCapabilitiesDto

/**
 * The decode ceiling this box advertises to the media plane, readable from anywhere.
 *
 * ⚠️ **Why this is a process-wide object rather than an injected singleton.** The two call sites
 * that need it — [com.nuvio.tv.core.boomio.BoomioStreamResolver] and the stream repository — have
 * no `Context`, and the value is a single device-wide fact rather than per-caller state. This is
 * the same shape, for the same reason, as [com.nuvio.app.features.boomio.BoomioConfig]: one
 * mutable seam, written where the platform data actually is, read wherever the value is dialled.
 *
 * **The write happens on the capability report**, which is the only thing in the app that
 * enumerates `MediaCodecList` — a platform call far too heavy for a per-request path. The value is
 * persisted so it survives a restart without waiting for the first report, and [loadOnce] brings it
 * back. Until either happens the accessors answer `""`, which sends no cap at all — exactly the
 * permissive behaviour of a build that predates this, never a wrong cap.
 */
object DeviceDecodeCeiling {

    /** The reporter's own prefs file — one store for one device's capability facts. */
    private const val PREFS_NAME = "nuvio_device_capability_report"
    private const val KEY_DECODE_MAX_RESOLUTION = "effective_decode_max_resolution"
    private const val KEY_DECODE_MAX_RES_BY_CODEC = "effective_decode_max_res_by_codec"

    @Volatile private var decodeMaxResolution: String = ""
    @Volatile private var decodeMaxResByCodec: String = ""

    @Volatile private var loaded = false

    /** Read the persisted ceiling back. Idempotent; the first call wins. */
    @Synchronized
    fun loadOnce(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        decodeMaxResolution = prefs.getString(KEY_DECODE_MAX_RESOLUTION, "").orEmpty()
        decodeMaxResByCodec = prefs.getString(KEY_DECODE_MAX_RES_BY_CODEC, "").orEmpty()
        loaded = true
    }

    /** Publish a freshly probed ceiling: in memory for this process, and in prefs for the next. */
    fun publish(context: Context, effective: EffectiveCapabilitiesDto) {
        decodeMaxResolution = effective.decodeMaxResolution
        decodeMaxResByCodec = effective.decodeMaxResByCodec
        loaded = true
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DECODE_MAX_RESOLUTION, decodeMaxResolution)
            .putString(KEY_DECODE_MAX_RES_BY_CODEC, decodeMaxResByCodec)
            .apply()
    }

    /** "1080p"/"2160p", or "" when nothing has been probed yet. */
    fun maxResolution(): String = decodeMaxResolution

    /** "avc:1080p,hevc:2160p,other:1080p", or "" when nothing has been probed yet. */
    fun maxResByCodec(): String = decodeMaxResByCodec
}
