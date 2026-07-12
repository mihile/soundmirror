// JNI bridge to native libFLAC. Decodes one self-contained FLAC packet (header +
// one block) to interleaved 16-bit PCM. Native decoding avoids the per-packet object
// allocation / GC churn in the Kotlin layer, so the lossless path costs
// far less battery. Used by com.soundmirror.app.NativeFlac.
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include "FLAC/stream_decoder.h"

// One persistent decoder per connection. Reset before each packet so the same decoder
// (and its internal buffers) is reused across packets — no per-packet allocation.
struct FlacState {
    FLAC__StreamDecoder *dec;
    const uint8_t *in;
    size_t in_len;
    size_t in_pos;
    int16_t *out;   // borrowed jshort array
    int out_cap;    // capacity in shorts
    int out_len;    // shorts written so far
    int error;
};

static FLAC__StreamDecoderReadStatus read_cb(
        const FLAC__StreamDecoder *, FLAC__byte buffer[], size_t *bytes, void *client) {
    FlacState *s = static_cast<FlacState *>(client);
    size_t avail = s->in_len - s->in_pos;
    if (avail == 0) {
        *bytes = 0;
        return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
    }
    size_t n = (*bytes < avail) ? *bytes : avail;
    memcpy(buffer, s->in + s->in_pos, n);
    s->in_pos += n;
    *bytes = n;
    return FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
}

static FLAC__StreamDecoderWriteStatus write_cb(
        const FLAC__StreamDecoder *, const FLAC__Frame *frame,
        const FLAC__int32 *const buffer[], void *client) {
    FlacState *s = static_cast<FlacState *>(client);
    unsigned ch = frame->header.channels;
    unsigned bs = frame->header.blocksize;
    int shift = static_cast<int>(frame->header.bits_per_sample) - 16;
    for (unsigned i = 0; i < bs; i++) {
        for (unsigned c = 0; c < ch; c++) {
            if (s->out_len >= s->out_cap) {
                return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
            }
            FLAC__int32 v = buffer[c][i];
            if (shift > 0) v >>= shift; else if (shift < 0) v <<= -shift;
            s->out[s->out_len++] = static_cast<int16_t>(v);
        }
    }
    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

static void error_cb(const FLAC__StreamDecoder *, FLAC__StreamDecoderErrorStatus, void *client) {
    static_cast<FlacState *>(client)->error = 1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_soundmirror_app_NativeFlac_nativeCreate(JNIEnv *, jclass) {
    FlacState *s = static_cast<FlacState *>(calloc(1, sizeof(FlacState)));
    if (s == nullptr) return 0;
    s->dec = FLAC__stream_decoder_new();
    if (s->dec == nullptr) {
        free(s);
        return 0;
    }
    FLAC__StreamDecoderInitStatus st = FLAC__stream_decoder_init_stream(
            s->dec, read_cb, nullptr, nullptr, nullptr, nullptr,
            write_cb, nullptr, error_cb, s);
    if (st != FLAC__STREAM_DECODER_INIT_STATUS_OK) {
        FLAC__stream_decoder_delete(s->dec);
        free(s);
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_soundmirror_app_NativeFlac_nativeDecode(
        JNIEnv *env, jclass, jlong handle, jbyteArray data, jint len,
        jshortArray pcm_out, jint max_shorts) {
    FlacState *s = reinterpret_cast<FlacState *>(handle);
    if (s == nullptr) return -1;
    jbyte *in = env->GetByteArrayElements(data, nullptr);
    jshort *out = env->GetShortArrayElements(pcm_out, nullptr);
    if (in == nullptr || out == nullptr) {
        if (in) env->ReleaseByteArrayElements(data, in, JNI_ABORT);
        if (out) env->ReleaseShortArrayElements(pcm_out, out, JNI_ABORT);
        return -1;
    }
    s->in = reinterpret_cast<const uint8_t *>(in);
    s->in_len = static_cast<size_t>(len);
    s->in_pos = 0;
    s->out = reinterpret_cast<int16_t *>(out);
    s->out_cap = max_shorts;
    s->out_len = 0;
    s->error = 0;

    FLAC__stream_decoder_reset(s->dec);
    FLAC__stream_decoder_process_until_end_of_stream(s->dec);

    int result = s->error ? -1 : s->out_len;
    env->ReleaseByteArrayElements(data, in, JNI_ABORT);
    env->ReleaseShortArrayElements(pcm_out, out, 0); // commit decoded PCM
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_soundmirror_app_NativeFlac_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    FlacState *s = reinterpret_cast<FlacState *>(handle);
    if (s == nullptr) return;
    if (s->dec != nullptr) {
        FLAC__stream_decoder_finish(s->dec);
        FLAC__stream_decoder_delete(s->dec);
    }
    free(s);
}
