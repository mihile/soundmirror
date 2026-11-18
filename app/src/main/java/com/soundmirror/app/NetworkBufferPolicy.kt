package com.soundmirror.app

/** Session-local extra jitter reserve. All times are monotonic milliseconds. */
internal class NetworkBufferPolicy {
    var extraMs = 0
        private set
    private var lastGapMs: Long? = null
    private var lastChangeMs = 0L

    fun onGap(nowMs: Long, deepMode: Boolean): Boolean {
        val previous = lastGapMs
        lastGapMs = nowMs
        // Occasional dropouts several seconds apart still indicate a persistent
        // network problem. One isolated pause must not raise the reserve.
        if (!deepMode && previous != null && nowMs - previous in 0L..60_000L && extraMs < 60) {
            extraMs += 20
            lastChangeMs = nowMs
            return true
        }
        return false
    }

    fun onDeliveryStall(nowMs: Long, stallMs: Long, outputMs: Long, prebufferMs: Long): Boolean {
        // Reserve enough for the observed excess delivery time, with one small
        // margin for playout scheduling. Keep the extra latency bounded.
        val needed = (stallMs + 20L - outputMs - prebufferMs).coerceIn(0L, 120L)
        val reserve = (((needed + 19L) / 20L) * 20L).toInt()
        if (reserve <= extraMs) return false
        extraMs = reserve
        lastChangeMs = nowMs
        return true
    }

    fun onStable(nowMs: Long, lastUnstableMs: Long, deepMode: Boolean): Boolean {
        val gap = lastGapMs ?: return false
        // A larger reserve absorbs a burst immediately. Shed that temporary
        // extra more quickly once the connection has settled.
        val stabilityMs = if (extraMs > 60) 30_000L else 120_000L
        // A 30-second lull was common between actual dropout clusters. Hold the
        // learned reserve through those lulls instead of repeatedly relearning it.
        if (!deepMode && extraMs > 0 && nowMs - gap >= stabilityMs &&
            nowMs - lastUnstableMs >= stabilityMs && nowMs - lastChangeMs >= stabilityMs) {
            extraMs -= 20
            lastChangeMs = nowMs
            return true
        }
        return false
    }
}
