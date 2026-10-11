package com.boomio.opus

/**
 * Kotlin surface over `libboomioopus` — libopus plus a small JNI bridge.
 *
 * ⚠️ **The package and class name are deliberately identical in the TV app and
 * the phone app.** JNI resolves a native method from its *Java* class name, so
 * sharing `com.boomio.opus.OpusCodec` lets both clients load ONE shared object
 * built once. Renaming or moving this class breaks every native symbol and
 * requires rebuilding the `.so`.
 *
 * The API is synchronous and allocation-free on the hot path — the caller owns
 * the sample and packet buffers and passes them in — because it runs once per
 * audio frame on a real-time path.
 *
 * Nothing here throws. Every native call returns a libopus result code
 * (samples or bytes ≥ 0, negative `OPUS_*` error), and a negative return means
 * "this frame is unusable" rather than "the session is over".
 */
object OpusCodec {

    /**
     * Whether the native library actually loaded.
     *
     * The fork must degrade to raw PCM rather than crash if it did not: a
     * per-ABI split APK can legitimately be missing this library on a device,
     * and a private-listening session that starts silent is far worse than one
     * that starts in the older, noisier format.
     */
    val available: Boolean = runCatching {
        System.loadLibrary("boomioopus")
        true
    }.getOrElse { false }

    /** libopus version string, or "unavailable". For the logcat proof line. */
    fun versionOrUnavailable(): String =
        if (available) runCatching { version() }.getOrElse { "error" } else "unavailable"

    // ---------------------------------------------------------------- encoder

    /**
     * @param sampleRate  one of 8000/12000/16000/24000/48000
     * @param channels    1 or 2 (mapping family 0; multichannel is not supported)
     * @param frameMs     frame duration the caller will feed (we use 20)
     * @param packetLossPerc expected loss, which drives how much in-band FEC to add
     * @return opaque handle, or 0 if the encoder could not be created
     */
    external fun encoderCreate(
        sampleRate: Int,
        channels: Int,
        bitrate: Int,
        frameMs: Int,
        packetLossPerc: Int,
        fec: Boolean,
    ): Long

    /**
     * @param pcm         interleaved signed 16-bit, exactly `frameSize * channels` samples
     * @param sampleCount samples PER CHANNEL (libopus convention), not total
     * @param out         receives the packet. Its LENGTH is the encoder's `max_data_bytes`,
     *                    so it is also the packet-size ceiling: libopus fits the frame into
     *                    it rather than overrunning, and cannot return more than `out.size`.
     *                    The fork sizes it to keep a datagram inside the MTU (1191 bytes
     *                    behind a 9-byte header); a VBR transient is capped, not dropped.
     * @return bytes written, or a negative error
     */
    external fun encoderEncode(
        handle: Long,
        pcm: ShortArray,
        sampleCount: Int,
        out: ByteArray,
    ): Int

    external fun encoderDestroy(handle: Long)

    // ---------------------------------------------------------------- decoder

    external fun decoderCreate(sampleRate: Int, channels: Int): Long

    /**
     * Decode one packet, or conceal one lost frame.
     *
     * @param data     the packet, or null/empty to request **packet-loss
     *                 concealment** (libopus synthesises a frame from its own
     *                 decoder state — this is strictly better than replaying
     *                 the previous slice of PCM)
     * @param fec      true = decode the *previous* frame out of this packet's
     *                 in-band FEC instead of this one. This genuinely recovers
     *                 a single lost packet rather than papering over it.
     * @param frameSize samples per channel to produce
     * @return samples per channel written, or a negative error
     */
    external fun decoderDecode(
        handle: Long,
        data: ByteArray?,
        dataOffset: Int,
        dataLen: Int,
        pcm: ShortArray,
        frameSize: Int,
        fec: Boolean,
    ): Int

    external fun decoderDestroy(handle: Long)

    // ------------------------------------------------------------ introspection

    private external fun version(): String
}
