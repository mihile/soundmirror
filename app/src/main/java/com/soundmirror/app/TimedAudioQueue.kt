package com.soundmirror.app

import java.util.PriorityQueue

/** Single-consumer queue: packet ownership transfers out before pooled data is reused. */
internal class TimedAudioQueue<T>(comparator: Comparator<T>, private val durationOf: (T) -> Long) {
    private val packets = PriorityQueue(comparator)
    var durationNs: Long = 0L
        private set
    val size: Int get() = packets.size

    fun add(packet: T) {
        packets.add(packet)
        durationNs += durationOf(packet)
    }

    fun poll(): T? = packets.poll()?.also {
        durationNs -= durationOf(it)
    }

    fun peek(): T? = packets.peek()
    // durationOf rounds each packet up by less than 1 ns. Don't discard a
    // packet just because that sub-frame rounding crossed an exact limit.
    fun exceedsNs(limitNs: Long): Boolean = durationNs - size > limitNs
    fun isEmpty(): Boolean = packets.isEmpty()
    fun isNotEmpty(): Boolean = packets.isNotEmpty()
}

internal object PacketBufferTiming {
    fun durationNs(frames: Int, sampleRate: Int): Long =
        // Round each duration up by <1 ns, not down: otherwise two FLAC
        // fragments totaling exactly 10 ms can miss a 10 ms gate by 1 ns.
        (frames.toLong() * 1_000_000_000L + sampleRate.coerceAtLeast(1) - 1L) /
            sampleRate.coerceAtLeast(1)

    fun targetNs(packets: Int, nominalPacketMs: Int, extraMs: Int, deep: Boolean): Long =
        if (deep) maxOf(packets * nominalPacketMs, 2000) * 1_000_000L
        else (packets * nominalPacketMs + extraMs) * 1_000_000L

    fun overflowNs(targetNs: Long, nominalPacketMs: Int): Long =
        maxOf(targetNs * 2L, targetNs + nominalPacketMs * 8_000_000L)

    // Spend at most 8 ms of existing output headroom on reordering. This isn't
    // a permanent queue reserve and never delays ordinary consecutive packets.
    fun reorderWaitMs(outputFrames: Long, sampleRate: Int): Long =
        (outputFrames.coerceAtLeast(0L) * 1000L / sampleRate.coerceAtLeast(1) - 50L)
            .coerceIn(0L, 8L)
}
