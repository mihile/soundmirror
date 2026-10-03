package com.soundmirror.app

import org.junit.Assert.*
import org.junit.Test

class NetworkBufferPolicyTest {
    @Test fun repeatedDropoutsFromDeviceLogReachBoundedReserve() {
        val policy = NetworkBufferPolicy()
        // 00:17:43.482, :49.935, :50.830, :53.335, 00:18:02.356, :13.087.
        for (time in listOf(0L, 6453L, 7348L, 9853L, 18874L, 29605L)) {
            policy.onGap(time, false)
        }
        assertEquals(60, policy.extraMs)
        // The real log then had a 30-second lull, followed by another dropout.
        assertFalse(policy.onStable(59_605L, 29_605L, false))
        assertEquals(60, policy.extraMs)
    }

    @Test fun isolatedPausesAndDeepModeDoNotIncreaseLatency() {
        val policy = NetworkBufferPolicy()
        policy.onGap(0L, false)
        policy.onGap(61_000L, false)
        assertEquals(0, policy.extraMs)
        policy.onGap(62_000L, true)
        assertEquals(0, policy.extraMs)
    }

    @Test fun sustainedStabilityRemovesReserveGradually() {
        val policy = NetworkBufferPolicy()
        for (time in listOf(0L, 10_000L, 20_000L, 30_000L)) policy.onGap(time, false)
        assertFalse(policy.onStable(149_999L, 30_000L, false))
        assertTrue(policy.onStable(150_000L, 30_000L, false))
        assertEquals(40, policy.extraMs)
        assertFalse(policy.onStable(150_001L, 30_000L, false))
        assertFalse(policy.onStable(270_000L, 260_000L, false))
        assertTrue(policy.onStable(380_000L, 260_000L, false))
        assertEquals(20, policy.extraMs)
    }
}
