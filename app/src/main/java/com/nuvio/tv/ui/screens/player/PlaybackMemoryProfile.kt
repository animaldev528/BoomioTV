package com.nuvio.tv.ui.screens.player

import android.os.Debug
import android.util.Log

/**
 * Per-session memory telemetry, one line every 10 s while playing plus a summary on release.
 *
 * The Java heap looks healthy right up to the moment the lowmemorykiller takes the process:
 * on the 2 GB Shield the heap stayed flat at ~175 MB while total PSS reached ~580 MB and the
 * box SIGKILLed the app. PSS and the native allocation are the numbers worth watching, and
 * neither was ever logged. `adb logcat | grep MEM_PROFILE` is the whole tooling needed.
 *
 * Sample-only (no allocation, no coroutine of its own); [sample] is called from the existing
 * 500 ms progress loop under its 10 s throttle.
 */
internal object PlaybackMemoryProfile {
    private const val MB = 1024L * 1024L

    private var startedAtMs = 0L
    private var peakHeapMb = 0L
    private var peakNativeMb = 0L
    private var peakPssMb = 0L

    fun sample(
        tag: String,
        aheadMs: Long,
        loading: Boolean,
        bitrateBps: Long?,
        width: Int?,
        height: Int?
    ) {
        if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()

        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val heapMaxMb = runtime.maxMemory() / MB
        val nativeMb = Debug.getNativeHeapAllocatedSize() / MB
        // Debug.getPss() is in kB and is still the cheapest process-wide number available
        // without an ActivityManager round-trip.
        val pssMb = runCatching { Debug.getPss() }.getOrDefault(0L) / 1024

        peakHeapMb = maxOf(peakHeapMb, heapUsedMb)
        peakNativeMb = maxOf(peakNativeMb, nativeMb)
        peakPssMb = maxOf(peakPssMb, pssMb)

        val bitrate = bitrateBps?.takeIf { it > 0 }
            ?.let { String.format("%.1f", it / 1_000_000.0) }
            ?: "?"
        Log.i(
            tag,
            "MEM_PROFILE: heap=${heapUsedMb}/${heapMaxMb}MB native=${nativeMb}MB pss=${pssMb}MB " +
                "peakPss=${peakPssMb}MB ahead=${aheadMs / 1000}s loading=$loading " +
                "bitrate=${bitrate}Mbps surface=${width ?: 0}x${height ?: 0}"
        )
    }

    /** One line at teardown, then the next playback starts a fresh peak window. */
    fun summary(tag: String, reason: String) {
        if (startedAtMs == 0L) return
        val sessionMs = System.currentTimeMillis() - startedAtMs
        Log.i(
            tag,
            "MEM_PROFILE_SUMMARY: reason=$reason sessionMs=$sessionMs " +
                "peakHeap=${peakHeapMb}MB peakNative=${peakNativeMb}MB peakPss=${peakPssMb}MB"
        )
        startedAtMs = 0L
        peakHeapMb = 0L
        peakNativeMb = 0L
        peakPssMb = 0L
    }
}
