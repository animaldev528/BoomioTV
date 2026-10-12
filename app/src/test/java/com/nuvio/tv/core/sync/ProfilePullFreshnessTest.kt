package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePullFreshnessTest {
    @Test
    fun recentMatchingAccountPullIsFresh() {
        val freshness = ProfilePullFreshness(userId = "user", pulledAtMs = 1_000L)

        assertTrue(freshness.isRecent("user", nowMs = 10_999L))
    }

    @Test
    fun differentExpiredOrFuturePullIsNotFresh() {
        val freshness = ProfilePullFreshness(userId = "user", pulledAtMs = 1_000L)

        assertFalse(freshness.isRecent("other", nowMs = 2_000L))
        assertFalse(freshness.isRecent("user", nowMs = 11_000L))
        assertFalse(freshness.isRecent("user", nowMs = 999L))
    }

    /**
     * The un-stamped value is what [ProfileSyncService.pullFromRemote] leaves behind when it seeded
     * an empty remote range from the LOCAL profile list. That list still carries client defaults for
     * the taste/kids flags — not what the server ends up storing — so it must never satisfy the
     * freshness window: doing so would serve those defaults to every pull for
     * PROFILE_PULL_MIN_INTERVAL_MS, which on a first login is the whole onboarding sequence, and the
     * account would run its first session with the like-bootstrap and taste hint switched off.
     */
    @Test
    fun defaultFreshnessNeverSuppressesAPull() {
        assertFalse(ProfilePullFreshness().isRecent("user", nowMs = 1L))
        assertFalse(ProfilePullFreshness().isRecent("user", nowMs = 11_000L))
        assertFalse(ProfilePullFreshness().isRecent("user", nowMs = Long.MAX_VALUE))
    }
}
