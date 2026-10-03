package com.soundmirror.app

import org.junit.Assert.*
import org.junit.Test

class TimedAudioQueueTest {
    private data class Packet(val seq: Long, val frames: Int, val rate: Int = 48000)
    private fun queue() = TimedAudioQueue<Packet>(compareBy { it.seq }) {
        PacketBufferTiming.durationNs(it.frames, it.rate)
    }

    @Test fun splitFlacBlockHasSameBufferBudgetAsWholeBlock() {
        val whole = queue().apply { add(Packet(0, 480)); add(Packet(1, 480)) }
        val split = queue().apply {
            add(Packet(0, 320)); add(Packet(1, 160))
            add(Packet(2, 320)); add(Packet(3, 160))
        }
        val target = PacketBufferTiming.targetNs(2, 10, 0, false)
        assertTrue(whole.durationNs >= target)
        assertTrue(split.durationNs >= target)
        assertTrue(split.durationNs - whole.durationNs < 4)
        assertEquals(2, whole.size)
        assertEquals(4, split.size)
    }

    @Test fun extraReserveIsDurationNotCountOfArbitraryFirstPacket() {
        val queue = queue()
        repeat(9) { queue.add(Packet(it.toLong(), 320)) }
        val target = PacketBufferTiming.targetNs(2, 10, 40, false)
        assertEquals(60_000_000L, target)
        assertTrue(queue.durationNs >= target)
        queue.poll()
        assertTrue(queue.durationNs < target)
    }

    @Test fun deepModeAlwaysPrimesTwoSecondsAcrossAlternatingWireFormats() {
        val queue = queue()
        var seq = 0L
        repeat(199) {
            if (it % 2 == 0) queue.add(Packet(seq++, 480))
            else { queue.add(Packet(seq++, 320)); queue.add(Packet(seq++, 160)) }
        }
        val target = PacketBufferTiming.targetNs(4, 10, 60, true)
        assertEquals(2_000_000_000L, target)
        assertTrue(queue.durationNs < target)
        queue.add(Packet(seq, 480))
        assertTrue(queue.durationNs >= target)
        assertTrue(queue.durationNs - target < 200)
    }

    @Test fun durationAccountingSurvivesReorderingDrainAndReuse() {
        val queue = queue()
        queue.add(Packet(3, 160)); queue.add(Packet(1, 480)); queue.add(Packet(2, 320))
        assertEquals(1L, queue.peek()!!.seq)
        assertEquals(1L, queue.poll()!!.seq)
        assertEquals(2L, queue.poll()!!.seq)
        assertEquals(3L, queue.poll()!!.seq)
        assertNull(queue.poll())
        assertEquals(0L, queue.durationNs)
        assertTrue(queue.isEmpty())
        queue.add(Packet(4, 240))
        assertEquals(5_000_000L, queue.durationNs)
    }

    @Test fun fragmentsDoNotTriggerPrematureBacklogDiscard() {
        val queue = queue()
        repeat(24) { queue.add(Packet(it.toLong(), if (it % 2 == 0) 320 else 160)) }
        val target = PacketBufferTiming.targetNs(4, 10, 0, false)
        val overflow = PacketBufferTiming.overflowNs(target, 10)
        assertEquals(120_000_000L, overflow)
        // Old count gate: 24 > 12. New gate: the actual audio is only 120 ms.
        assertTrue(queue.durationNs < overflow + 24)
        assertFalse(queue.exceedsNs(overflow))
        val hardwareRefill = 50_000_000L
        assertTrue(queue.durationNs < overflow + hardwareRefill)
        queue.add(Packet(24, 480))
        assertTrue(queue.exceedsNs(overflow))
    }

    @Test fun pcmAndOtherSampleRatesUseActualFrames() {
        assertEquals(5_000_000L, PacketBufferTiming.durationNs(240, 48000))
        assertEquals(10_000_000L, PacketBufferTiming.durationNs(441, 44100))
        assertEquals(10_000_000L, PacketBufferTiming.durationNs(960, 96000))
        assertEquals(20_000_000L, PacketBufferTiming.targetNs(4, 5, 0, false))
    }

    @Test fun reorderWaitUsesOnlySpareOutputAndHasHardDeadline() {
        assertEquals(0L, PacketBufferTiming.reorderWaitMs(0, 48000))
        assertEquals(0L, PacketBufferTiming.reorderWaitMs(-480, 48000))
        assertEquals(0L, PacketBufferTiming.reorderWaitMs(2400, 48000))
        assertEquals(5L, PacketBufferTiming.reorderWaitMs(2640, 48000))
        assertEquals(8L, PacketBufferTiming.reorderWaitMs(4800, 48000))
    }
}
