package com.soundmirror.app

import android.util.Log

/**
 * Thin Kotlin facade over the native libFLAC decoder (in libopusnative.so).
 * [available] is false if the packaged .so failed to load. Decoding one
 * self-contained FLAC packet reuses a single native decoder handle.
 */
object NativeFlac {
    val available: Boolean = try {
        System.loadLibrary("opusnative")
        true
    } catch (t: Throwable) {
        Log.w("SoundMirror", "native flac unavailable", t)
        false
    }

    external fun nativeCreate(): Long
    external fun nativeDecode(
        handle: Long,
        data: ByteArray,
        len: Int,
        pcmOut: ShortArray,
        maxShorts: Int,
    ): Int
    external fun nativeDestroy(handle: Long)
}
