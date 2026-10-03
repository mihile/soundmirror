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

    fun onStable(nowMs: Long, lastUnstableMs: Long, deepMode: Boolean): Boolean {
        val gap = lastGapMs ?: return false
        // A 30-second lull was common between actual dropout clusters. Hold the
        // learned reserve through those lulls instead of repeatedly relearning it.
        if (!deepMode && extraMs > 0 && nowMs - gap >= 120_000L &&
            nowMs - lastUnstableMs >= 120_000L && nowMs - lastChangeMs >= 120_000L) {
            extraMs -= 20
            lastChangeMs = nowMs
            return true
        }
        return false
    }
}
