package com.nuvio.tv.ui.screens.settings

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.PlayerSettings

/**
 * Shared memory budget constants and helpers for buffer + parallel connection settings.
 * Used by both PlaybackSettingsViewModel and PlaybackBufferNetworkSettings UI.
 */
@UnstableApi
object MemoryBudget {
    const val TAG = "MemoryBudget"

    // Heap-tiered budget ratios. Low-RAM devices (Fire TV class) need more
    // headroom for codec/surface/UI; high-RAM devices can dedicate more to buffering.
    private const val LOW_HEAP_RATIO = 0.65
    private const val HIGH_HEAP_RATIO = 0.85
    private const val HIGH_HEAP_THRESHOLD_MB = 512L
    // The buffer allocator is on the Java heap; on low-RAM reserve a slice for the UI/decoder/caches
    // and give the rest to the buffer (a flat % of max heap overcommits and starves them).
    private const val LOW_HEAP_RESERVE_MB = 210L
    // The manifest sets largeHeap=true, so Runtime.maxMemory() is pinned at the same
    // ceiling (~512 MB) on a 1 GB Firestick and an 8 GB Shield. The heap ceiling alone
    // therefore cannot decide the tier: a 2 GB box reads as high-RAM and would be handed
    // an 85% (~435 MB) byte budget — most of the device. Physical RAM is the honest input.
    private const val LOW_PHYSICAL_RAM_MB = 2_300L
    // Absolute byte-budget ceilings by physical RAM, so a largeHeap-inflated ratio can
    // never take a slice the codec, surface buffers and UI need on a tight box.
    private const val BUDGET_CEIL_1GB_MB = 72
    private const val BUDGET_CEIL_2GB_MB = 128
    private const val BUDGET_CEIL_3GB_MB = 256

    /** ParallelRangeDataSource schedules maxAhead = parallelConnections + 1 chunks concurrently */
    private const val BUFFER_OVERHEAD = 2

    const val MIN_CONNECTIONS = 2
    const val MAX_CONNECTIONS = 4
    const val MIN_CHUNK_MB = 8
    const val MAX_CHUNK_MB = 128
    // Low-RAM devices (1-2 GB class): the session's protected set (playhead
    // window + tail moov chunks + pinned side chunks) puts the worst-case
    // retained floor at roughly connections + 10 chunks on a scatter-heavy
    // file, so chunk size is hard-capped at 16 MB on that tier — above it
    // the floor alone exceeds what those devices can hold. Applies in
    // performance mode too; the cap is a tier property, not a budget one.
    private const val LOW_RAM_MAX_CHUNK_MB = 16
    const val BUFFER_STEP_MB = 25
    const val MIN_BUFFER_MB = 25
    const val MAX_BUFFER_MB = 1024 * 4
    private const val DEFAULT_EFFECTIVE_BUFFER_MB = 50

    val defaultBufferSizeMb: Int = if (BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB > 0) {
        BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
    } else {
        DEFAULT_EFFECTIVE_BUFFER_MB
    }

    private val maxHeapMb: Long = Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    /** Physical RAM from /proc/meminfo, or 0 when it cannot be read. */
    private val physicalRamMb: Long = try {
        java.io.File("/proc/meminfo").useLines { lines ->
            val match = java.util.regex.Pattern.compile("\\d+").matcher(lines.firstOrNull().orEmpty())
            if (match.find()) match.group().toLong() / 1024L else 0L
        }
    } catch (_: Exception) {
        0L
    }

    /**
     * True for TV-stick class hardware: a small heap, or 2 GB of physical RAM or less
     * regardless of the largeHeap-inflated heap ceiling.
     */
    val isLowRamTier: Boolean =
        maxHeapMb < HIGH_HEAP_THRESHOLD_MB || (physicalRamMb in 1..LOW_PHYSICAL_RAM_MB)

    // A byte cap alone is wrong in both directions: 46 MB on a 1 GB stick is ~5 s at a
    // 71 Mbps remux but ~100 s at 3.6 Mbps, so low-bitrate content silently keeps far
    // more heap than the box can spare. Pair the byte cap with a time window; the
    // effective buffer is whichever binds first (bytes win, see the stock builder).
    val stockMinBufferMs: Int = if (isLowRamTier) 4_000 else 8_000
    val stockMaxBufferMs: Int = if (isLowRamTier) 10_000 else 20_000
    /** Start gate and post-rebuffer gate; both must be <= [stockMinBufferMs]. */
    val stockStartGateMs: Int = 1_000
    val stockRebufferGateMs: Int = 2_000

    /** Max source bitrate to prefer on this tier when auto-selecting a stream (Mbps). */
    val preferredMaxBitrateMbps: Int = if (isLowRamTier) 15 else Int.MAX_VALUE

    /** Hard ceiling the ratio budget is clamped to for this device's physical RAM. */
    private val budgetCeilMb: Int = when {
        // Unreadable: keep the heap-derived budget rather than guessing.
        physicalRamMb <= 0L -> Int.MAX_VALUE
        physicalRamMb < 1_450L -> BUDGET_CEIL_1GB_MB
        physicalRamMb < LOW_PHYSICAL_RAM_MB -> BUDGET_CEIL_2GB_MB
        physicalRamMb < 3_200L -> BUDGET_CEIL_3GB_MB
        else -> Int.MAX_VALUE
    }

    // Pre-cap ratio budget; conversionBudgetMb derives from this so DV7 headroom isn't cut by the cap.
    private val rawBudgetMb: Int =
        (maxHeapMb * (if (isLowRamTier) LOW_HEAP_RATIO else HIGH_HEAP_RATIO))
            .toInt()
            .coerceAtMost(budgetCeilMb)

    val budgetMb: Int =
        if (isLowRamTier)
            rawBudgetMb.coerceAtMost((maxHeapMb - LOW_HEAP_RESERVE_MB).toInt()).coerceAtLeast(MIN_BUFFER_MB)
        else rawBudgetMb

    // DV7 conversion headroom: a third of the raw budget on low-RAM, half on high-RAM; never above budget.
    val conversionBudgetMb: Int =
        (if (isLowRamTier) rawBudgetMb / 3 else rawBudgetMb / 2)
            .coerceAtMost(budgetMb).coerceAtLeast(MIN_BUFFER_MB)

    fun effectiveBufferMb(stored: Int): Int =
        if (stored > 0) stored else defaultBufferSizeMb

    /**
     * Number of chunk-sized buffers alive concurrently in ParallelRangeDataSource.
     * Accounts for active chunks (connectionCount) and idle recycled buffers in the pool (connectionCount).
     */
    fun bufferCount(connectionCount: Int): Int =
        connectionCount * 2

    fun parallelOverheadMb(connectionCount: Int, chunkSizeMb: Int): Int =
        bufferCount(connectionCount) * chunkSizeMb

    fun totalUsageMb(bufferMb: Int, connectionCount: Int, chunkSizeMb: Int, parallelEnabled: Boolean): Int =
        bufferMb + if (parallelEnabled) parallelOverheadMb(connectionCount, chunkSizeMb) else 0

    /** Hard chunk-size ceiling for this device tier; binds everywhere, including performance mode. */
    val tierMaxChunkMb: Int = if (isLowRamTier) LOW_RAM_MAX_CHUNK_MB else MAX_CHUNK_MB

    /** Max chunk size that fits budget given current buffer size */
    fun maxChunkMb(bufferMb: Int, connectionCount: Int): Int =
        ((budgetMb - bufferMb) / bufferCount(connectionCount)).coerceIn(MIN_CHUNK_MB, tierMaxChunkMb)

    /** Max buffer size that fits budget given current parallel overhead */
    fun maxBufferMb(parallelOverheadMb: Int): Int =
        ((budgetMb - parallelOverheadMb) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceIn(MIN_BUFFER_MB, MAX_BUFFER_MB)

    /** Slider max for target buffer, optionally raised to 2GB when override is on. */
    fun maxBufferMbWithOverride(parallelOverheadMb: Int, allowLargeTargetBuffer: Boolean): Int {
        val safeMax = maxBufferMb(parallelOverheadMb)
        return if (allowLargeTargetBuffer) {
            PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB
                .coerceAtMost(MAX_BUFFER_MB)
                .coerceAtLeast(safeMax)
        } else {
            safeMax
        }
    }

    /**
     * Enforce budget: reduce chunk first, then buffer as last resort.
     * Returns (adjustedBufferMb, adjustedChunkMb).
     */
    fun enforce(bufferMb: Int, chunkMb: Int, connectionCount: Int): Pair<Int, Int> {
        val buffers = bufferCount(connectionCount)
        if (bufferMb + buffers * chunkMb <= budgetMb) return bufferMb to chunkMb

        val newChunkMb = maxChunkMb(bufferMb, connectionCount)
        if (bufferMb + buffers * newChunkMb <= budgetMb) return bufferMb to newChunkMb

        // Even min chunk doesn't fit, also reduce buffer
        val newBufferMb = ((budgetMb - buffers * MIN_CHUNK_MB) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceAtLeast(MIN_BUFFER_MB)
        return newBufferMb to MIN_CHUNK_MB
    }

    /**
     * Determines the memory usage status based on total usage, safe limit, and warning limit.
     */
    fun getUsageStatus(
        totalUsageMb: Int,
        safeLimitMb: Int,
        warningLimitMb: Int
    ): MemoryUsageStatus {
        return when {
            totalUsageMb > warningLimitMb -> MemoryUsageStatus.DANGER
            totalUsageMb > safeLimitMb -> MemoryUsageStatus.WARNING
            else -> MemoryUsageStatus.SAFE
        }
    }
}

@UnstableApi
enum class MemoryUsageStatus {
    SAFE,
    WARNING,
    DANGER
}

