package com.soundmirror.app

import kotlin.math.abs

/** Measurement only: never changes playback or reads a configured buffer target. */
internal class PlaybackLatencyMeter(private val sampleRate: Int) {
    data class Sample(
        val receiverMs: Double,
        val appWaitMs: Double,
        val outputMs: Double,
        val timestampBased: Boolean,
        val audioMs: Double?,
        val deviceExtraMs: Double?,
    )

    private var anchorFrame: Long? = null
    private var anchorNs = 0L
    private var stable = false
    private var lastPollNs: Long? = null
    private var failedPolls = 0

    fun reset() {
        anchorFrame = null
        anchorNs = 0L
        stable = false
        lastPollNs = null
        failedPolls = 0
    }

    fun shouldPoll(nowNs: Long): Boolean {
        val interval = if (stable || failedPolls >= 5) 10_000_000_000L else 500_000_000L
        return lastPollNs?.let { nowNs - it >= interval } ?: true
    }

    fun timestampUnavailable(nowNs: Long) {
        lastPollNs = nowNs
        anchorFrame = null
        stable = false
        failedPolls++
    }

    fun timestamp(rawFrame: Long, timeNs: Long, nowNs: Long, writtenFrames: Long) {
        val frame = unwrapNear(rawFrame, writtenFrames)
        // AudioTrack may legitimately report a frame committed for future output.
        if (sampleRate <= 0 || frame !in 0..writtenFrames || timeNs <= 0 ||
            abs(nowNs - timeNs) > 2_000_000_000L) {
            timestampUnavailable(nowNs)
            return
        }
        val previous = anchorFrame
        val elapsed = timeNs - anchorNs
        val advanced = previous != null && frame > previous && elapsed > 0
        val expectedFrames = elapsed * sampleRate / 1_000_000_000.0
        stable = advanced && abs((frame - previous!!) - expectedFrames) <= sampleRate * 0.05
        anchorFrame = frame
        anchorNs = timeNs
        lastPollNs = nowNs
        failedPolls = if (stable) 0 else failedPolls + 1
    }

    fun sample(
        receiveNs: Long,
        writeStartNs: Long,
        firstFrame: Long,
        writtenFrames: Long,
        rawHead: Long,
        nowNs: Long,
    ): Sample? {
        if (sampleRate <= 0 || receiveNs <= 0 || writeStartNs < receiveNs ||
            nowNs < writeStartNs || firstFrame < 0 || firstFrame >= writtenFrames) return null
        val head = unwrapNear(rawHead, writtenFrames)
        // No observed playback yet: don't substitute the configured prebuffer size.
        if (head <= 0 || head > writtenFrames) return null
        val useTimestamp = stable && anchorFrame != null &&
            lastPollNs?.let { nowNs - it in 0..11_000_000_000L } == true
        val frameNs = 1_000_000_000.0 / sampleRate
        val headPresentationNs = nowNs + (firstFrame - head) * frameNs
        var timestampBased = useTimestamp
        var presentationNs = if (useTimestamp) {
            anchorNs + (firstFrame - anchorFrame!!) * frameNs
        } else {
            headPresentationNs
        }
        // A route/clock discontinuity must not produce a negative output delay.
        if (presentationNs < writeStartNs && timestampBased) {
            reset()
            timestampBased = false
            presentationNs = headPresentationNs
        }
        if (presentationNs < writeStartNs) return null
        val appWaitMs = (writeStartNs - receiveNs) / 1_000_000.0
        val outputMs = (presentationNs - writeStartNs) / 1_000_000.0
        // Receive-to-write includes socket-channel wait, reorder queue and decoding.
        // Use the SAME packet's first frame, so blocking write time isn't double-counted.
        // The head-based path contains receiver/decoder/AudioTrack buffering. The
        // timestamp-minus-head residual estimates the ADDITIONAL output path, not
        // a calibrated Bluetooth/DAC hardware-only latency. Never insert a preset
        // offset or claim zero extra delay when timestamps are unavailable/inconsistent.
        val audioMs = if (headPresentationNs >= writeStartNs) {
            (headPresentationNs - receiveNs) / 1_000_000.0
        } else null
        val deviceExtraMs = if (timestampBased && audioMs != null &&
            presentationNs >= headPresentationNs) {
            (presentationNs - headPresentationNs) / 1_000_000.0
        } else null
        return Sample(appWaitMs + outputMs, appWaitMs, outputMs, timestampBased,
            audioMs, deviceExtraMs)
    }

    private fun unwrapNear(raw: Long, reference: Long): Long {
        val modulus = 1L shl 32
        var value = (reference and -modulus) + (raw and (modulus - 1))
        if (value - reference > modulus / 2) value -= modulus
        if (reference - value > modulus / 2) value += modulus
        return value
    }
}
