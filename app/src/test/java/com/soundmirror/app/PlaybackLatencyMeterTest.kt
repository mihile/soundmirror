package com.soundmirror.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackLatencyMeterTest {
    private val time = 10_000_000_000L

    @Test fun measuresActualPacketResidenceIncludingReceiveChannelAndDecode() {
        val meter = PlaybackLatencyMeter(48000)
        val sample = meter.sample(time, time + 20_000_000, 4800, 5760, 4320,
            time + 30_000_000)!!
        assertEquals(40.0, sample.receiverMs, 0.001)
        assertEquals(20.0, sample.appWaitMs, 0.001)
        assertEquals(20.0, sample.outputMs, 0.001)
        assertFalse(sample.timestampBased)
        // A REAL extra 100 ms before writing changes the measurement by 100 ms.
        // No setting, packet count, or configured delay is given to the meter.
        val waiting = meter.sample(time - 100_000_000, time + 20_000_000,
            4800, 5760, 4320, time + 30_000_000)!!
        assertEquals(140.0, waiting.receiverMs, 0.001)
    }

    @Test fun blockingWriteTimeIsNotCountedTwiceAndUsesFirstFrameNotPacketEnd() {
        val meter = PlaybackLatencyMeter(48000)
        val shortWrite = meter.sample(time, time + 20_000_000, 4800, 5760, 4320,
            time + 30_000_000)!!
        // Twenty more ms passed inside write(), and 960 frames really played.
        val longWrite = meter.sample(time, time + 20_000_000, 4800, 5760, 5280,
            time + 50_000_000)!!
        assertEquals(shortWrite.receiverMs, longWrite.receiverMs, 0.001)
    }

    @Test fun noAudioOrInvalidPacketDoesNotInventConfiguredLatency() {
        val meter = PlaybackLatencyMeter(48000)
        assertNull(meter.sample(time, time, 0, 480, 0, time))
        assertNull(meter.sample(time, time, 480, 480, 100, time))
        assertNull(meter.sample(time, time - 1, 480, 960, 100, time))
        assertNull(meter.sample(time, time, 480, 960, 1000, time))
    }

    @Test fun advancingTimestampMapsTheSamePacketToPresentationTime() {
        val meter = stableMeter()
        val sample = meter.sample(time + 10_000_000, time + 20_000_000,
            4800, 5760, 480, time + 30_000_000)!!
        assertTrue(sample.timestampBased)
        assertEquals(90.0, sample.receiverMs, 0.001)
        assertEquals(10.0, sample.appWaitMs, 0.001)
        assertEquals(80.0, sample.outputMs, 0.001)
    }

    @Test fun futureCommittedTimestampIsValid() {
        val meter = PlaybackLatencyMeter(48000)
        meter.timestamp(0, time + 100_000_000, time, 10000)
        meter.timestamp(480, time + 110_000_000, time + 10_000_000, 10000)
        val sample = meter.sample(time, time + 20_000_000, 4800, 5760, 480,
            time + 30_000_000)!!
        assertTrue(sample.timestampBased)
        assertEquals(200.0, sample.receiverMs, 0.001)
    }

    @Test fun timestampsNeedAdvancingFramesAndUnavailableFallsBackExplicitly() {
        val meter = PlaybackLatencyMeter(48000)
        meter.timestamp(480, time, time, 5760)
        meter.timestamp(480, time, time + 500_000_000, 5760)
        assertFalse(meter.sample(time, time, 4800, 5760, 4320, time)!!.timestampBased)
        val stable = stableMeter()
        stable.timestampUnavailable(time + 30_000_000)
        assertFalse(stable.sample(time, time, 4800, 5760, 4320, time + 30_000_000)!!.timestampBased)
    }

    @Test fun resetAfterPauseFlushUnderrunOrRouteChangeDiscardsOldClock() {
        val meter = stableMeter()
        meter.reset()
        assertTrue(meter.shouldPoll(time + 30_000_000))
        assertFalse(meter.sample(time, time, 4800, 5760, 4320, time + 30_000_000)!!.timestampBased)
    }

    @Test fun staleOrImpossibleTimestampDoesNotBecomeARealMeasurement() {
        val meter = stableMeter()
        meter.timestamp(480, time - 3_000_000_000, time, 5760)
        assertFalse(meter.sample(time, time, 4800, 5760, 4320, time + 30_000_000)!!.timestampBased)
        meter.timestamp(6000, time, time, 5760)
        assertFalse(meter.sample(time, time, 4800, 5760, 4320, time + 30_000_000)!!.timestampBased)
    }

    @Test fun stableClockIsPolledSparinglyAndExpiresAfterLongMetricPause() {
        val meter = stableMeter()
        assertFalse(meter.shouldPoll(time + 1_000_000_000))
        assertTrue(meter.shouldPoll(time + 10_020_000_000))
        val later = time + 12_000_000_000
        assertFalse(meter.sample(later, later, 4800, 5760, 4320, later)!!.timestampBased)
    }

    @Test fun playbackHeadAndTimestampWrapAt32BitsWithoutHugeLatency() {
        val wrap = 1L shl 32
        val meter = PlaybackLatencyMeter(48000)
        val fallback = meter.sample(time, time + 20_000_000, wrap + 4500,
            wrap + 4800, 4320, time + 30_000_000)!!
        assertEquals(33.75, fallback.receiverMs, 0.001)
        meter.timestamp(wrap - 480, time, time, wrap + 10000)
        meter.timestamp(0, time + 10_000_000, time + 10_000_000, wrap + 10000)
        val stamped = meter.sample(time, time, wrap + 480, wrap + 960,
            0, time + 10_000_000)!!
        assertTrue(stamped.timestampBased)
        assertEquals(20.0, stamped.receiverMs, 0.001)
    }

    @Test fun mixedFlacAndPcmPacketLengthsDoNotAffectFirstFrameMeasurement() {
        val meter = PlaybackLatencyMeter(48000)
        val flac = meter.sample(time, time, 4800, 5280, 4320, time)!!
        val pcmFragment = meter.sample(time, time, 4800, 4960, 4320, time)!!
        assertEquals(flac.receiverMs, pcmFragment.receiverMs, 0.001)
    }

    private fun stableMeter() = PlaybackLatencyMeter(48000).also {
        it.timestamp(0, time, time + 10_000_000, 10000)
        it.timestamp(480, time + 10_000_000, time + 20_000_000, 10000)
    }

    @Test fun separatesBaseBufferingFromAdditionalOutputPathWithoutDoubleCounting() {
        val meter = PlaybackLatencyMeter(48000)
        meter.timestamp(0, time + 100_000_000, time, 10000)
        meter.timestamp(480, time + 110_000_000, time + 10_000_000, 10000)
        val sample = meter.sample(time, time + 20_000_000, 4800, 5760, 4320,
            time + 30_000_000)!!
        assertEquals(40.0, sample.audioMs!!, 0.001)
        assertEquals(160.0, sample.deviceExtraMs!!, 0.001)
        assertEquals(sample.receiverMs, sample.audioMs + sample.deviceExtraMs, 0.001)
    }

    @Test fun unavailableTimestampStillShowsAudioButNeverInventsDeviceOffset() {
        val meter = PlaybackLatencyMeter(48000)
        val sample = meter.sample(time, time + 20_000_000, 4800, 5760, 4320,
            time + 30_000_000)!!
        assertEquals(40.0, sample.audioMs!!, 0.001)
        assertNull(sample.deviceExtraMs)
    }

    @Test fun inconsistentHeadAndTimestampDoNotFabricateAZeroOrNegativeOffset() {
        val sample = stableMeter().sample(time + 10_000_000, time + 20_000_000,
            4800, 5760, 480, time + 30_000_000)!!
        assertNotNull(sample.audioMs)
        assertNull(sample.deviceExtraMs)
    }

    @Test fun routeResetClearsDeviceOffsetWhileBaseMeasurementRemainsAvailable() {
        val meter = stableMeter()
        meter.reset()
        val sample = meter.sample(time, time + 20_000_000, 4800, 5760, 4320,
            time + 30_000_000)!!
        assertEquals(40.0, sample.audioMs!!, 0.001)
        assertNull(sample.deviceExtraMs)
    }

    @Test fun baseMatchesIndependentQueueAccountingAndDeviceOffsetIsNotAddedTwice() {
        // Reproduce the real 239 ms + 211 ms case with a 4 ms one-way estimate.
        val meter = PlaybackLatencyMeter(48000)
        meter.timestamp(37920, time + 236_000_000, time + 20_000_000, 48480)
        meter.timestamp(38400, time + 246_000_000, time + 30_000_000, 48480)
        val now = time + 73_000_000
        val firstFrame = 48000L
        val writtenFrames = 48480L
        val head = 40224L
        val sample = meter.sample(time, time + 63_000_000,
            firstFrame, writtenFrames, head, now)!!
        val elapsedSinceReceiveMs = (now - time) / 1_000_000.0
        val queuedFramesMs = (writtenFrames - head) * 1000.0 / 48000
        val thisPacketMs = (writtenFrames - firstFrame) * 1000.0 / 48000
        // Independent identity: received packet's age + output queue, minus the
        // current packet duration because latency follows its first (not last) frame.
        assertEquals(elapsedSinceReceiveMs + queuedFramesMs - thisPacketMs,
            sample.audioMs!!, 0.001)
        assertEquals(239.0, sample.audioMs + 4.0, 0.001)
        assertEquals(211.0, sample.deviceExtraMs!!, 0.001)
        assertEquals(sample.receiverMs + 4.0,
            sample.audioMs + 4.0 + sample.deviceExtraMs, 0.001)
    }
}
