package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.boomio.opus.OpusCodec

/**
 * The Opus fork's wire format — the TV half.
 *
 * ⚠️ **These constants are duplicated verbatim in the phone app** (its own `ForkOpusWire`
 * in its own `ForkOpus.kt`). The two clients ship as separate apps in separate repositories
 * and cannot share a module, so the two copies are kept identical by hand — changing one
 * without the other silently breaks the fork.
 *
 * The header is nine bytes, TV → phone:
 * ```
 *   bytes 0..3  magic 0x4F505553 ("OPUS", big-endian)
 *   bytes 4..5  sequence (big-endian uint16, wraps) — the same counter the PCM path uses
 *   byte   6    channel count (1..2)
 *   byte   7    frame duration in ms (20)
 *   byte   8    flags: bits 4..7 format version, bit 0 = in-band FEC present
 *   bytes 9..   one Opus packet
 * ```
 */
internal object ForkOpusWire {
    /**
     * `0x4F505553` — "OPUS" as a big-endian int32, placed in the four bytes the legacy
     * header uses for the sample rate.
     *
     * ⚠️ That placement *is* the discrimination mechanism, and it is what makes the format
     * change safe on a mixed fleet. A receiver that predates Opus reads bytes 0..3 as a
     * sample rate and drops anything outside 8000..192000 — and 1330664787 is not a rate, so
     * it discards these datagrams instead of playing them as noise. A current receiver reads
     * the same four bytes and branches. No handshake is needed in either direction, which is
     * also why the TV can fall back to PCM for one track and back to Opus for the next
     * without telling anyone.
     */
    const val MAGIC = 0x4F505553

    const val HEADER_BYTES = 9

    /** Bumped only for an incompatible header change; the receiver rejects what it cannot read. */
    const val VERSION = 0
    const val FLAG_FEC = 0x01

    /**
     * The only rate the Opus path carries: libopus's own native rate, so nothing is resampled.
     * A track at any other rate takes the PCM path (v1 has no resampler).
     */
    const val SAMPLE_RATE = 48_000

    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS

    /**
     * The measured knee. Mean SNR runs 40.1 dB @64k → 41.5 @96k → 42.6 @128k → 43.0 @320k,
     * so past 128k doubling the bitrate buys 0.3 dB (inaudible) while the packet grows toward
     * the MTU. "The lowest rate within ~1 dB of the best that fits" is the rule, not "the
     * highest SNR that fits".
     */
    const val BITRATE = 128_000

    /**
     * libopus mapping family 0 — the simple path — is mono/stereo only. A wider track needs
     * `opus_multistream_*`, which the bridge does not expose, so 5.1/7.1 takes the PCM path
     * rather than being silently folded or truncated.
     */
    const val MAX_CHANNELS = 2

    /** One packet per frame: 20 ms frames therefore mean 50 datagrams/s. */
    const val PACKETS_PER_SECOND = 1000 / FRAME_MS

    /**
     * Opus's `max_data_bytes`, chosen so the finished datagram can never exceed the 1200-byte
     * budget the PCM path already holds to. A VBR frame that would have run longer is
     * truncated by the encoder rather than fragmenting on the wire.
     */
    const val MAX_PACKET_BYTES = 1200 - HEADER_BYTES
}

/**
 * Encodes the tee's PCM into Opus packets for the private-listening fork.
 *
 * Owned and used only by the sender's worker thread. [encode] is fed the same PCM16 bytes the
 * PCM path would have put straight on the wire, in the decoder's own interleaved layout.
 *
 * **Why this exists.** Raw PCM16 stereo at 48 kHz is 1536 kbit/s in 161 datagrams of 1192
 * bytes per second, with zero redundancy. Measured loss on the phone tracked the packet
 * *rate*, not the signal or the signal strength — 0% at 20 pps, ~2.5% at 161 pps — so the
 * lever is fewer, smaller datagrams. Opus at 128 kbit/s / 20 ms is ~322 bytes and 50
 * packets/s: **3.2x fewer loss events and 11.9x less airtime** through the same access point.
 *
 * ⚠️ **In-band FEC ships OFF, deliberately, and it is not a bit-budget trade.**
 * `OPUS_SET_INBAND_FEC` + a loss percentage buys +11.1 dB on a *lost* frame but costs
 * **-12.6 dB on every frame** (42.6 → 29.9 dB) and pins the worst transient frames at
 * -0.5 dB — worse than the -3.6 dB loss it exists to protect against. It does not recover
 * with bitrate (it saturates at ~30 dB from 128k to 320k), which is the tell: the encoder
 * leaves CELT for the more robust SILK/hybrid mode, a structural quality cap rather than
 * borrowed bits. Its cost is also invisible in packet size (322 B → 319 B), so only a
 * decoded-quality measurement can see it. The decoder-side PLC that replaces it is free.
 */
internal class ForkOpusEncoder(private val channelCount: Int) {

    private var handle: Long = OpusCodec.encoderCreate(
        ForkOpusWire.SAMPLE_RATE,
        channelCount,
        ForkOpusWire.BITRATE,
        ForkOpusWire.FRAME_MS,
        0,     // expected packet loss: only meaningful alongside in-band FEC
        false, // in-band FEC: ships OFF, see the class comment
    )

    /** False when the native library or the encoder could not be had; the caller sends PCM. */
    val isUsable: Boolean get() = handle != 0L

    /** One codec frame, interleaved, filled a whole frame at a time. */
    private val staging = ShortArray(ForkOpusWire.FRAME_SAMPLES * channelCount)

    /** Samples per channel currently staged, i.e. how far into [staging] the next frame goes. */
    private var stagedShorts = 0

    /** Reused packet buffer. Valid only until the next [encode] call. */
    private val packet = ByteArray(ForkOpusWire.MAX_PACKET_BYTES)

    var encodedPackets: Long = 0
        private set

    var encodeErrors: Long = 0
        private set

    /**
     * Consume one captured buffer: stage its whole frames and emit every complete codec frame
     * through [emit].
     *
     * The accumulator is not optional. Media3 hands the tee buffers of whatever size the
     * decoder produced (~1024 frames observed), while `opus_encode` requires exactly
     * [ForkOpusWire.FRAME_SAMPLES] per channel — so a buffer contributes a packet plus a
     * carried remainder, or just a remainder. [emit] receives the encoder's **reused** packet
     * buffer and its length; it must copy the bytes out before returning.
     */
    fun encode(pcm16: ByteArray, emit: (ByteArray, Int) -> Unit) {
        if (handle == 0L) return
        val frameBytes = 2 * channelCount
        val limit = pcm16.size - (pcm16.size % frameBytes)
        var i = 0
        while (i < limit) {
            var c = 0
            while (c < channelCount) {
                staging[stagedShorts + c] = readS16Le(pcm16, i + 2 * c)
                c++
            }
            stagedShorts += channelCount
            i += frameBytes
            if (stagedShorts == staging.size) {
                // Clear the counter BEFORE flushing, not after: [emit] sends on a live socket and
                // can throw, and a counter left at capacity would make the next call index past the
                // end of [staging]. A frame lost to a send error is already counted as one.
                stagedShorts = 0
                flush(emit)
            }
        }
    }

    /**
     * Drop whatever is staged. Called on a flush/discontinuity/seek: those samples belong to
     * audio the phone has been told to forget, and gluing them onto the first frame after the
     * jump would splice two unrelated points in the timeline together.
     */
    fun reset() {
        stagedShorts = 0
    }

    fun close() {
        if (handle != 0L) {
            OpusCodec.encoderDestroy(handle)
            handle = 0L
        }
    }

    private fun flush(emit: (ByteArray, Int) -> Unit) {
        val n = OpusCodec.encoderEncode(handle, staging, ForkOpusWire.FRAME_SAMPLES, packet)
        if (n <= 0) {
            encodeErrors++
            if (encodeErrors <= 3) Log.w(TAG, "opus encode failed ($n) — frame dropped")
            return
        }
        encodedPackets++
        emit(packet, n)
    }

    private fun readS16Le(bytes: ByteArray, i: Int): Short =
        ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()

    private companion object {
        /** The same tag [PrivateListeningAudioSender] uses: the whole fork sender is one filter. */
        private const val TAG = "PrivateListeningSender"
    }
}
