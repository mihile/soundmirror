package com.soundmirror.app

import android.util.Log

/**
 * Thin Kotlin facade over the native libopus decoder (libopusnative.so).
 * [available] is false if the .so failed to load, letting callers fall back to
 * the pure-Java Concentus decoder.
 */
object NativeOpus {
    val available: Boolean = try {
        System.loadLibrary("opusnative")
        true
    } catch (t: Throwable) {
        Log.w("SoundMirror", "native opus unavailable, using Concentus", t)
        false
    }

    external fun nativeCreate(sampleRate: Int, channels: Int): Long
    external fun nativeDecode(
        handle: Long,
        data: ByteArray?,
        len: Int,
        pcmOut: ShortArray,
        frameSize: Int,
        fec: Boolean,
    ): Int
    external fun nativeDestroy(handle: Long)
}
