package com.nuvio.tv.core.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-model tests for the per-codec decode-limit filter's query-param builders,
 * in particular the reserved `other` catch-all ceiling added for content whose
 * codec family bsf can't classify into a declared row.
 */
class DeviceStreamLimitsTest {

    @Test
    fun `defaults append the other catch-all ceiling`() {
        val param = DeviceStreamLimits.defaults().codecCapsMbpsParam()
        assertEquals("avc:60,hevc:60,av1:12,vp9:60,other:60", param)
    }

    @Test
    fun `codecCapsMbpsParam omits other when the catch-all is disabled`() {
        val param = limits(otherEnabled = false).codecCapsMbpsParam()
        assertEquals("avc:60,hevc:60,av1:12,vp9:60", param)
    }

    @Test
    fun `codecCapsMbpsParam carries a custom other ceiling`() {
        val param = limits(otherMbps = 40).codecCapsMbpsParam()
        assertEquals("avc:60,hevc:60,av1:12,vp9:60,other:40", param)
    }

    @Test
    fun `codecCapsMbpsParam is null when the master toggle is off`() {
        // Master off suppresses both params entirely even when the catch-all is on.
        assertNull(limits(enabled = false).codecCapsMbpsParam())
    }

    @Test
    fun `codecCapsMbpsParam is just the catch-all when no family is enabled`() {
        val param = limits(familyEnabled = { false }).codecCapsMbpsParam()
        assertEquals("other:60", param)
    }

    @Test
    fun `codecsParam never lists the reserved other key`() {
        val param = DeviceStreamLimits.defaults().codecsParam()
        assertEquals("avc,hevc,av1,vp9", param)
        assertEquals("avc,hevc,av1,vp9", limits(familyEnabled = { false }).codecsParam())
    }

    @Test
    fun `declared family keeps its own ceiling even when the catch-all is lower`() {
        val param = limits(
            ceilings = mapOf(CodecFamily.HEVC to 60),
            otherMbps = 20,
        ).codecCapsMbpsParam()
        assertEquals("avc:60,hevc:60,av1:12,vp9:60,other:20", param)
    }

    private fun limits(
        enabled: Boolean = true,
        familyEnabled: (CodecFamily) -> Boolean = { true },
        ceilings: Map<CodecFamily, Int> = emptyMap(),
        otherEnabled: Boolean = true,
        otherMbps: Int = DeviceStreamLimits.DEFAULT_OTHER_MBPS,
    ): DeviceStreamLimits = DeviceStreamLimits(
        enabled = enabled,
        limits = CodecFamily.entries.map { family ->
            CodecLimitConfig(
                family = family,
                enabled = familyEnabled(family),
                ceilingMbps = ceilings[family] ?: family.defaultCeilingMbps,
            )
        },
        otherEnabled = otherEnabled,
        otherMbps = otherMbps,
    )
}
