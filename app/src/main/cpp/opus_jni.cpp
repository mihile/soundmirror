// JNI bridge to native libopus. Exposes a minimal decoder API used by
// com.soundmirror.app.NativeOpus. Native decoding is faster and lighter on the
// battery than the pure-Java Concentus fallback.
#include <jni.h>
#include <opus.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_soundmirror_app_NativeOpus_nativeCreate(JNIEnv *, jclass, jint sample_rate, jint channels) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sample_rate, channels, &err);
    if (err != OPUS_OK || dec == nullptr) {
        return 0;
    }
    return reinterpret_cast<jlong>(dec);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_soundmirror_app_NativeOpus_nativeDecode(
        JNIEnv *env, jclass, jlong handle, jbyteArray data, jint len,
        jshortArray pcm_out, jint frame_size, jboolean fec) {
    OpusDecoder *dec = reinterpret_cast<OpusDecoder *>(handle);
    if (dec == nullptr) {
        return -1;
    }
    jshort *out = env->GetShortArrayElements(pcm_out, nullptr);
    if (out == nullptr) {
        return -1;
    }

    int decoded;
    if (data == nullptr || len <= 0) {
        // Packet loss concealment: null input asks Opus to synthesise a frame.
        decoded = opus_decode(dec, nullptr, 0, out, frame_size, fec ? 1 : 0);
    } else {
        jbyte *in = env->GetByteArrayElements(data, nullptr);
        decoded = opus_decode(dec, reinterpret_cast<const unsigned char *>(in), len,
                              out, frame_size, fec ? 1 : 0);
        env->ReleaseByteArrayElements(data, in, JNI_ABORT);
    }

    env->ReleaseShortArrayElements(pcm_out, out, 0);
    return decoded;
}

extern "C" JNIEXPORT void JNICALL
Java_com_soundmirror_app_NativeOpus_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    OpusDecoder *dec = reinterpret_cast<OpusDecoder *>(handle);
    if (dec != nullptr) {
        opus_decoder_destroy(dec);
    }
}
