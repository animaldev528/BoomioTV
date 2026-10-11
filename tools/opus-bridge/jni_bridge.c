/*
 * libboomioopus -- a thin JNI surface over libopus for the private-listening
 * audio fork.
 *
 * WHY THIS EXISTS
 * The fork used to ship raw PCM16: 1536 kbit/s in 161 datagrams of 1192 bytes
 * every second, with zero redundancy. Measured loss on the phone tracked the
 * packet RATE, not the signal (0% at 20 pps, ~2.5% at 161 pps), so the fix is
 * to send far fewer, smaller packets -- which only a codec can do. Opus at
 * 128 kbit/s / 20 ms is 320 bytes and 50 packets/s: 3.2x fewer packets and 12x
 * less airtime, and libopus's decoder conceals a lost frame far better than
 * replaying the previous slice of PCM.
 *
 * WHY ONE .so FOR BOTH APPS
 * The class is deliberately named com.boomio.opus.OpusCodec in BOTH trees --
 * the TV encodes, the phone decodes, and they load the same binary. JNI symbol
 * names are derived from the Java class name, so a shared package means one
 * set of symbols and one build instead of two. AGP's default ProGuard config
 * already carries `-keepclasseswithmembernames,includedescriptorclasses class *
 * { native <methods>; }`, so R8 will not rename this class or its natives.
 *
 * CONVENTIONS
 *  - A handle is an opaque jlong (a pointer). 0 means "failed to create".
 *  - Every entry point returns a libopus result code (samples or bytes >= 0,
 *    negative OPUS_* error) and NEVER throws. The Kotlin side treats any
 *    negative return as "this frame is unusable" and falls back.
 *  - frameSize / sampleCount are always SAMPLES PER CHANNEL (libopus
 *    convention), not total samples.
 */

#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>

#include <opus.h>

#define LOG_TAG "BoomioOpus"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

#define JNI_FN(name) Java_com_boomio_opus_OpusCodec_##name

/* Samples per channel for a frame duration. */
static int frame_samples(int sample_rate, int frame_ms) {
    if (sample_rate <= 0 || frame_ms <= 0) return 0;
    return (sample_rate / 1000) * frame_ms;
}

/* ------------------------------------------------------------------ */
/* Encoder                                                             */
/* ------------------------------------------------------------------ */

JNIEXPORT jlong JNICALL
JNI_FN(encoderCreate)(JNIEnv *env, jobject thiz,
                      jint sampleRate, jint channels, jint bitrate,
                      jint frameMs, jint packetLossPerc, jboolean fec) {
    (void) env; (void) thiz;

    /* libopus accepts 8/12/16/24/48 kHz. The caller checks this too; this is
     * the backstop so a bad rate can never produce a NaN-filled stream. */
    if (sampleRate != 8000 && sampleRate != 12000 && sampleRate != 16000 &&
        sampleRate != 24000 && sampleRate != 48000) {
        LOGE("encoderCreate: unsupported sample rate %d", sampleRate);
        return 0;
    }
    /* Mapping family 0 (the simple path) is mono/stereo only. Multichannel
     * would need opus_multistream_encoder_create; the TV falls back to PCM
     * for those tracks rather than silently dropping channels. */
    if (channels < 1 || channels > 2) {
        LOGE("encoderCreate: unsupported channel count %d", channels);
        return 0;
    }
    if (frame_samples(sampleRate, frameMs) <= 0) {
        LOGE("encoderCreate: bad frame %d ms at %d Hz", frameMs, sampleRate);
        return 0;
    }

    int err = OPUS_OK;
    OpusEncoder *enc = opus_encoder_create(sampleRate, channels,
                                           OPUS_APPLICATION_AUDIO, &err);
    if (enc == NULL || err != OPUS_OK) {
        LOGE("opus_encoder_create failed: %s", opus_strerror(err));
        return 0;
    }

    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    /* 10 is the slowest/best setting. Encoding runs on the sender's worker
     * thread, never the audio thread, so the cost is affordable and buys
     * quality at a bitrate this low. */
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(10));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC));
    opus_encoder_ctl(enc, OPUS_SET_LSB_DEPTH(16));

    if (fec) {
        /* In-band FEC (LBRR) puts a low-bitrate copy of the PREVIOUS frame in
         * this one, so a single lost packet can be rebuilt from its successor
         * -- real resistance, not just concealment. It costs bitrate, and it
         * requires the decoder to hold one frame back, so the flag is carried
         * in every packet header and the receiver adapts. */
        opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
        opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(packetLossPerc));
    } else {
        opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));
        opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(0));
    }

    LOGI("encoder: %d Hz, %d ch, %d bps, %d ms, fec=%d ploss=%d%%",
         sampleRate, channels, bitrate, frameMs, fec ? 1 : 0, packetLossPerc);
    return (jlong) (intptr_t) enc;
}

JNIEXPORT jint JNICALL
JNI_FN(encoderEncode)(JNIEnv *env, jobject thiz,
                      jlong handle, jshortArray pcm, jint sampleCount,
                      jbyteArray out) {
    (void) thiz;
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) handle;
    if (enc == NULL || pcm == NULL || out == NULL) return OPUS_BAD_ARG;

    const jsize pcmLen = (*env)->GetArrayLength(env, pcm);
    const jsize outCap = (*env)->GetArrayLength(env, out);
    if (sampleCount <= 0 || sampleCount > pcmLen || outCap <= 0) return OPUS_BAD_ARG;

    jshort *pcmPtr = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (pcmPtr == NULL) return OPUS_ALLOC_FAIL;

    jbyte *outPtr = (*env)->GetByteArrayElements(env, out, NULL);
    if (outPtr == NULL) {
        (*env)->ReleaseShortArrayElements(env, pcm, pcmPtr, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }

    int n = opus_encode(enc, (const opus_int16 *) pcmPtr, sampleCount,
                        (unsigned char *) outPtr, outCap);

    (*env)->ReleaseShortArrayElements(env, pcm, pcmPtr, JNI_ABORT);
    /* mode 0 commits the output; JNI_ABORT discards it on failure. */
    (*env)->ReleaseByteArrayElements(env, out, outPtr, n > 0 ? 0 : JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
JNI_FN(encoderDestroy)(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) handle;
    if (enc != NULL) opus_encoder_destroy(enc);
}

/* ------------------------------------------------------------------ */
/* Decoder                                                             */
/* ------------------------------------------------------------------ */

JNIEXPORT jlong JNICALL
JNI_FN(decoderCreate)(JNIEnv *env, jobject thiz, jint sampleRate, jint channels) {
    (void) env; (void) thiz;

    if (sampleRate != 8000 && sampleRate != 12000 && sampleRate != 16000 &&
        sampleRate != 24000 && sampleRate != 48000) {
        LOGE("decoderCreate: unsupported sample rate %d", sampleRate);
        return 0;
    }
    if (channels < 1 || channels > 2) {
        LOGE("decoderCreate: unsupported channel count %d", channels);
        return 0;
    }

    int err = OPUS_OK;
    OpusDecoder *dec = opus_decoder_create(sampleRate, channels, &err);
    if (dec == NULL || err != OPUS_OK) {
        LOGE("opus_decoder_create failed: %s", opus_strerror(err));
        return 0;
    }
    LOGI("decoder: %d Hz, %d ch", sampleRate, channels);
    return (jlong) (intptr_t) dec;
}

/*
 * Decode one packet, or conceal one lost frame.
 *
 *   data == NULL (or dataLen <= 0)  -> packet-loss concealment: libopus
 *                                      synthesises a plausible frame from its
 *                                      own decoder state.
 *   fec  == true                    -> decode the PREVIOUS frame out of this
 *                                      packet's in-band FEC instead of this
 *                                      one. This is how a single lost packet
 *                                      is genuinely recovered rather than
 *                                      papered over.
 *
 * Returns samples per channel written, or a negative OPUS_* error.
 */
JNIEXPORT jint JNICALL
JNI_FN(decoderDecode)(JNIEnv *env, jobject thiz,
                      jlong handle, jbyteArray data, jint dataOffset, jint dataLen,
                      jshortArray pcm, jint frameSize, jboolean fec) {
    (void) thiz;
    OpusDecoder *dec = (OpusDecoder *) (intptr_t) handle;
    if (dec == NULL || pcm == NULL) return OPUS_BAD_ARG;

    const jsize pcmLen = (*env)->GetArrayLength(env, pcm);
    if (frameSize <= 0 || pcmLen < frameSize) return OPUS_BAD_ARG;

    jshort *pcmPtr = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (pcmPtr == NULL) return OPUS_ALLOC_FAIL;

    unsigned char *inPtr = NULL;
    const unsigned char *in = NULL;
    jint inLen = 0;

    if (data != NULL && dataLen > 0) {
        const jsize dataCap = (*env)->GetArrayLength(env, data);
        if (dataOffset < 0 || dataLen < 0 || dataOffset + dataLen > dataCap) {
            (*env)->ReleaseShortArrayElements(env, pcm, pcmPtr, JNI_ABORT);
            return OPUS_BAD_ARG;
        }
        inPtr = (unsigned char *) (*env)->GetByteArrayElements(env, data, NULL);
        if (inPtr == NULL) {
            (*env)->ReleaseShortArrayElements(env, pcm, pcmPtr, JNI_ABORT);
            return OPUS_ALLOC_FAIL;
        }
        in = inPtr + dataOffset;
        inLen = dataLen;
    }

    int n = opus_decode(dec, in, inLen, (opus_int16 *) pcmPtr, frameSize,
                        fec ? 1 : 0);

    if (inPtr != NULL) {
        (*env)->ReleaseByteArrayElements(env, data, (jbyte *) inPtr, JNI_ABORT);
    }
    (*env)->ReleaseShortArrayElements(env, pcm, pcmPtr, n > 0 ? 0 : JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
JNI_FN(decoderDestroy)(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    OpusDecoder *dec = (OpusDecoder *) (intptr_t) handle;
    if (dec != NULL) opus_decoder_destroy(dec);
}

/* ------------------------------------------------------------------ */
/* Introspection                                                       */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
JNI_FN(version)(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, opus_get_version_string());
}
