package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import com.boomio.opus.OpusCodec
import com.nuvio.tv.core.boomio.ForkCodec
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Unicast sender for Roku-style private listening.
 *
 * The TV already decodes the audio it is playing for its own speakers; [PrivateListeningAudioSink]
 * memcpys each decoded PCM buffer on the audio thread into a bounded queue, and this sender's own
 * worker thread drains that queue and sends it to the companion phone over UDP as PCM16 in the
 * DECODER'S NATIVE channel layout. The wire is always 16-bit: the decoder's own PCM *width* is
 * folded down here (24-bit sources are truncated, never dropped), so a plain stereo PCM remux
 * reaches the phone instead of going silent. The TV never downmixes: it hands 5.1/7.1 through
 * untouched and lets the phone render them with a matching AudioTrack, so Android's own mixer (or
 * a multichannel-capable sink) does whatever fold the earbuds need. An earlier TV-side stereo
 * downmix was the source of an audible artifact on surround content; passthrough is clean. Because
 * it shares the main decode clock it cannot drift from the TV the way a second shadow player could.
 *
 * Two wire formats share one datagram header, and bytes 0..3 discriminate them: a plausible
 * sample rate means PCM (below), and [ForkOpusWire.MAGIC] means one Opus packet, whose header
 * [ForkOpusWire] defines. A receiver that knows only the PCM format drops an Opus datagram instead
 * of playing it as noise, which is what lets the codec change without a handshake in either
 * direction -- and lets this sender fall back to PCM for one track and back to Opus for the next.
 *
 * PCM wire format (one datagram each, from the TV to phoneIp:port):
 *   bytes 0..3  sample rate in Hz, big-endian int32   — phone (re)inits its AudioTrack from this,
 *   bytes 4..5  sequence number, big-endian uint16    — increments per datagram; lets the phone
 *                                                       detect loss and gap bytes,
 *   byte   6    channel count (1..8)                  — the native layout of the decoded PCM; the
 *                                                       phone maps it to a matching AudioTrack mask,
 *   bytes 7..   PCM16 signed little-endian interleaved, [byte 6] channels, whole frames only.
 *
 * No separate format header is needed: every datagram is self-describing, so a phone that starts
 * listening mid-stream syncs on its very first datagram, and a mid-stream sample-rate OR channel
 * change is carried by the per-datagram fields. Sequence gaps are expected (UDP + deliberate drops
 * under backpressure); the phone should treat a gap as a short silence rather than a fatal error.
 *
 * The sender must never block the audio thread: [offer] only memcpys into a fresh byte[] and
 * enqueues (dropping when the bounded queue is full). All encode/network work happens on the
 * worker thread.
 */
internal class PrivateListeningAudioSender(
    private val phoneIp: String,
    private val phonePort: Int,
    /**
     * Which payload to put on the wire. [ForkCodec.PCM] is the original transport and the default,
     * so a caller that negotiated nothing -- or a phone that predates Opus -- is unchanged. The
     * codec is a request rather than a promise: [opusFallbackReason] records the case where the
     * track could not be carried as Opus after all.
     */
    private val codec: ForkCodec = ForkCodec.PCM,
) {

    /** One decoded PCM buffer captured on the audio thread. */
    private class PcmFrame(
        val bytes: ByteArray,
        val sampleRate: Int,
        val channelCount: Int,
        val pcmEncoding: Int,
        val ptsUs: Long,
    )

    companion object {
        private const val TAG = "PrivateListeningSender"

        /** Keep datagrams under the typical 1500-byte Ethernet MTU (UDP/IP headers ~28B). */
        private const val MAX_DATAGRAM = 1200
        private const val PREAMBLE_BYTES = 7
        private const val MAX_CHUNK = MAX_DATAGRAM - PREAMBLE_BYTES

        /** Bounded so a stalled phone can never back-pressure the audio thread or grow memory. */
        private const val QUEUE_CAPACITY = 128
        private const val POLL_TIMEOUT_MS = 20L

        /** Layouts the wire can carry (the phone maps these to AudioFormat channel masks). */
        private val SUPPORTED_CHANNEL_COUNTS = 1..8

        /**
         * Bytes per sample for a decoded PCM encoding, or 0 when the wire cannot carry it.
         *
         * This is the sink's acceptance test as well as the worker's conversion key: the FFmpeg
         * renderer hands us whatever width the source decoded to (a PCM 2.0 DVD remux decodes to
         * [C.ENCODING_PCM_24BIT]), so a narrow whitelist silently drops every buffer and the phone
         * gets nothing. The wire itself is always PCM16 -- see [renderPcm16], which folds any width
         * down -- so accepting a width here costs only the conversion.
         */
        fun pcmBytesPerSample(pcmEncoding: Int): Int = when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }

        /** Logging only: a short name for the width the decoder produced. */
        fun pcmFormatName(pcmEncoding: Int): String = when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> "pcm8"
            C.ENCODING_PCM_16BIT -> "pcm16"
            C.ENCODING_PCM_24BIT -> "pcm24"
            C.ENCODING_PCM_32BIT -> "pcm32"
            C.ENCODING_PCM_FLOAT -> "float32"
            else -> "encoding=$pcmEncoding"
        }

        /**
         * Datagrams/second the worker will emit for a PCM layout, derived from the same [MAX_CHUNK]
         * the send loop uses so a logged figure cannot drift from the wire.
         *
         * This is the number that matters for the private-listening crackle. A lost datagram costs
         * the same *time* whatever the layout -- but 5.1 splits that time into ~3x more datagrams,
         * so a given loss rate splices ~3x more concealments per second. The send loop converts to
         * PCM16 first, so a frame is always 2 * channelCount bytes.
         *
         * Logging only; never consulted by playback.
         */
        fun datagramsPerSecond(sampleRate: Int, channelCount: Int): Int {
            if (sampleRate <= 0 || channelCount <= 0) return 0
            val framesPerChunk = MAX_CHUNK / (2 * channelCount)
            if (framesPerChunk <= 0) return 0
            return (sampleRate.toDouble() / framesPerChunk + 0.5).toInt()
        }
    }

    @Volatile
    private var running = false

    private val queue = ArrayBlockingQueue<PcmFrame>(QUEUE_CAPACITY)

    @Volatile
    var sentDatagrams: Long = 0
        private set

    @Volatile
    var droppedFrames: Long = 0
        private set

    @Volatile
    var sendErrors: Long = 0
        private set

    /** True once an encoder was really created -- i.e. the wire is carrying Opus, not just asked to. */
    @Volatile
    var opusActive: Boolean = false
        private set

    /** Opus datagrams emitted: one per codec frame, so one per 20 ms. */
    @Volatile
    var opusPackets: Long = 0
        private set

    /**
     * Non-null once the fork asked for Opus and is carrying PCM instead, with the reason. See
     * [noteOpusFallback] for why a silent fallback must not be possible.
     */
    @Volatile
    var opusFallbackReason: String? = null
        private set

    /**
     * Set by [clearQueue] on the audio thread and consumed by the worker, which owns the encoder.
     * A seek must not let pre-seek samples into the first post-seek packet.
     */
    @Volatile
    private var flushPending = false

    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    /** Spawn the worker. Idempotent — a fork that is already running is left alone. */
    @Synchronized
    fun start(): Boolean {
        if (running) return false
        running = true
        val t = Thread(::runWorker, "nuvio-private-listening-sender")
        t.isDaemon = true
        worker = t
        t.start()
        return true
    }

    /** Stop the worker and drop any queued frames. Safe to call more than once. */
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        queue.clear()
        // The honest total for the session, and the only place two things are recorded: which side
        // lost the audio, and which codec ACTUALLY ran. These counters were readable from
        // [PrivateListeningAudioSink] but nothing ever read them, so a fork the TV itself starved
        // looked identical to one the network ate -- and the phone's receiver log can only ever see
        // the *result* of both. Likewise a codec fallback is logged once as a warning, and that
        // scrolls out of logcat long before a long session ends; this line cannot.
        val carried = when {
            opusActive -> "opus"
            opusFallbackReason != null -> "pcm, opus fell back: $opusFallbackReason"
            codec == ForkCodec.OPUS -> "pcm, opus never started"
            else -> "pcm"
        }
        Log.i(
            TAG,
            "fork sender stopped: $sentDatagrams datagrams sent ($carried), $droppedFrames frame(s) " +
                "dropped on a full queue, $sendErrors send error(s), $opusPackets opus packet(s)",
        )
    }

    /**
     * Called on the audio thread. Copies [source]'s remaining bytes (one memcpy) and enqueues them
     * for the worker. Never blocks; drops the frame if the queue is full so a stalled phone cannot
     * stall the TV.
     */
    fun offer(source: ByteBuffer, ptsUs: Long, sampleRate: Int, channelCount: Int, pcmEncoding: Int) {
        if (!running) return
        val n = source.remaining()
        if (n <= 0) return
        val bytes = ByteArray(n)
        source.duplicate().get(bytes)
        if (!queue.offer(PcmFrame(bytes, sampleRate, channelCount, pcmEncoding, ptsUs))) {
            droppedFrames++
        }
    }

    /** Drop all queued frames (seek / discontinuity / flush) so the phone resyncs to the next PTS. */
    fun clearQueue() {
        queue.clear()
        // The encoder lives on the worker thread, so a half-filled frame is dropped there.
        flushPending = true
    }

    /**
     * Record -- once -- that the fork asked for Opus and is carrying PCM instead, and say why.
     *
     * A silent fallback is the failure this exists to prevent: the fork would work, sound worse,
     * and look in the log exactly like one that never negotiated Opus at all. The reason arrives as
     * a lambda because this is called from the per-frame path, and the early return must skip the
     * string build too.
     */
    private fun noteOpusFallback(reason: () -> String) {
        if (opusFallbackReason != null) return
        val text = reason()
        opusFallbackReason = text
        Log.w(TAG, "fork asked for opus, carrying pcm: $text")
    }

    private fun runWorker() {
        val socket: DatagramSocket = try {
            DatagramSocket()
        } catch (e: IOException) {
            Log.e(TAG, "unable to open UDP socket for $phoneIp:$phonePort", e)
            running = false
            return
        }
        val address: InetAddress = try {
            InetAddress.getByName(phoneIp)
        } catch (e: Exception) {
            Log.e(TAG, "bad phone IP '$phoneIp'", e)
            running = false
            socket.close()
            return
        }
        socket.use {
            var seq = 0
            var encoder: ForkOpusEncoder? = null
            var encoderChannels = 0
            var encoderFailed = false
            try {
                while (running) {
                    if (flushPending) {
                        // A flush / discontinuity / seek. Whatever the encoder has staged belongs to
                        // audio the phone has just been told to forget, so drop it: gluing it onto
                        // the first frame after the jump splices two unrelated points of the
                        // timeline together, which is a click at exactly the wrong moment.
                        flushPending = false
                        encoder?.reset()
                    }
                    val frame = try {
                        queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        null
                    }
                    if (!running || frame == null) continue
                    try {
                        val pcm16 = renderPcm16(frame)
                        if (pcm16 == null) {
                            droppedFrames++
                            continue
                        }
                        val channels = frame.channelCount

                        if (codec == ForkCodec.OPUS) {
                            // Opus carries 48 kHz mono/stereo only -- libopus mapping family 0,
                            // because the bridge exposes no multistream encoder. A 5.1/7.1 track,
                            // or any other rate, keeps the PCM path: cutting the datagram count is
                            // not worth folding or dropping channels for, and the phone is the end
                            // that folds anyway.
                            //
                            // OpusCodec.available is part of the test so a per-ABI APK that shipped
                            // without the native library falls back on its first frame instead of
                            // allocating an encoder per frame forever.
                            if (OpusCodec.available &&
                                frame.sampleRate == ForkOpusWire.SAMPLE_RATE &&
                                channels in 1..ForkOpusWire.MAX_CHANNELS
                            ) {
                                if (encoder != null && encoderChannels != channels) {
                                    encoder.close()
                                    encoder = null
                                }
                                if (encoder == null && !encoderFailed) {
                                    val fresh = ForkOpusEncoder(channels)
                                    if (fresh.isUsable) {
                                        encoder = fresh
                                        encoderChannels = channels
                                        opusActive = true
                                        // The on-device proof: which codec, how many datagrams/s. A
                                        // fork still emitting ~161/s here never took this path.
                                        Log.i(
                                            TAG,
                                            "fork carrying opus ${ForkOpusWire.BITRATE / 1000}kbps " +
                                                "${ForkOpusWire.FRAME_MS}ms ${channels}ch " +
                                                "(${ForkOpusWire.PACKETS_PER_SECOND} datagrams/s, " +
                                                "fec=off) libopus ${OpusCodec.versionOrUnavailable()}",
                                        )
                                    } else {
                                        encoderFailed = true
                                        noteOpusFallback { "libopus could not create an encoder" }
                                    }
                                }
                                val enc = encoder
                                if (enc != null) {
                                    enc.encode(pcm16) { packet, length ->
                                        // The encoder's packet buffer is reused, so its bytes are
                                        // copied into a fresh datagram here.
                                        val datagram = ByteArray(ForkOpusWire.HEADER_BYTES + length)
                                        datagram[0] = (ForkOpusWire.MAGIC ushr 24).toByte()
                                        datagram[1] = (ForkOpusWire.MAGIC ushr 16).toByte()
                                        datagram[2] = (ForkOpusWire.MAGIC ushr 8).toByte()
                                        datagram[3] = ForkOpusWire.MAGIC.toByte()
                                        datagram[4] = (seq ushr 8).toByte()
                                        datagram[5] = seq.toByte()
                                        datagram[6] = channels.toByte()
                                        datagram[7] = ForkOpusWire.FRAME_MS.toByte()
                                        // Format version in the high nibble. Bit 0 would say the
                                        // packet carries in-band FEC, which this encoder never adds.
                                        datagram[8] = (ForkOpusWire.VERSION shl 4).toByte()
                                        System.arraycopy(
                                            packet,
                                            0,
                                            datagram,
                                            ForkOpusWire.HEADER_BYTES,
                                            length,
                                        )
                                        seq = (seq + 1) and 0xFFFF
                                        socket.send(
                                            DatagramPacket(
                                                datagram,
                                                datagram.size,
                                                address,
                                                phonePort,
                                            ),
                                        )
                                        sentDatagrams++
                                        opusPackets++
                                    }
                                    continue
                                }
                            } else {
                                noteOpusFallback {
                                    "${frame.sampleRate}Hz ${channels}ch is outside the opus path"
                                }
                            }
                        }

                        // Chunk on whole frames so the phone's AudioTrack write never splits a
                        // sample (a misaligned tail would be silently dropped by write's frame
                        // rounding).
                        val frameBytes = 2 * channels
                        val chunkMax = (MAX_CHUNK / frameBytes) * frameBytes
                        var offset = 0
                        while (offset < pcm16.size) {
                            val chunk = minOf(chunkMax, pcm16.size - offset)
                            // Rebuild the preamble each datagram: rate is constant per frame, seq advances.
                            val datagram = ByteArray(PREAMBLE_BYTES + chunk)
                            datagram[0] = (frame.sampleRate ushr 24).toByte()
                            datagram[1] = (frame.sampleRate ushr 16).toByte()
                            datagram[2] = (frame.sampleRate ushr 8).toByte()
                            datagram[3] = frame.sampleRate.toByte()
                            datagram[4] = (seq ushr 8).toByte()
                            datagram[5] = seq.toByte()
                            datagram[6] = channels.toByte()
                            System.arraycopy(pcm16, offset, datagram, PREAMBLE_BYTES, chunk)
                            offset += chunk
                            seq = (seq + 1) and 0xFFFF
                            socket.send(DatagramPacket(datagram, datagram.size, address, phonePort))
                            sentDatagrams++
                        }
                    } catch (e: IOException) {
                        sendErrors++
                        if (sendErrors <= 3) Log.w(TAG, "UDP send to $phoneIp:$phonePort failed", e)
                    }
                }
            } finally {
                encoder?.close()
            }
        }
    }

    /**
     * Fold one captured buffer down to PCM16 signed little-endian in its native interleaved layout.
     * Returns null for a layout the wire cannot carry (an unsupported width or channel count).
     *
     * 16-bit input is returned untouched (same reference), so the common path costs nothing. Every
     * other width converts sample-by-sample: float — decoders hand float buffers for Atmos/E-AC3
     * folds — with a clamp to +-1.0, which a legal float never exceeds so in practice it is a
     * no-op; and the wider integer widths (24-/32-bit PCM sources, which is how a plain DVD remux
     * arrives) by keeping the TOP two bytes of each little-endian sample. That reduction is a pure
     * shift, so it needs no clamp and cannot clip.
     *
     * The channel ORDER is the decoder's (the standard MediaCodec layout
     * FL FR FC LFE BL BR [SL SR]), which the phone's channel mask must match — see the phone-side
     * receiver.
     */
    private fun renderPcm16(frame: PcmFrame): ByteArray? {
        val n = frame.channelCount
        if (n !in SUPPORTED_CHANNEL_COUNTS) return null
        val bps = pcmBytesPerSample(frame.pcmEncoding)
        if (bps == 0) return null
        val whole = frame.bytes.size / (bps * n) * (bps * n)
        if (whole <= 0) return null
        if (bps == 2) {
            return if (whole == frame.bytes.size) frame.bytes else frame.bytes.copyOf(whole)
        }
        val out = ByteArray((whole / bps) * 2)
        var o = 0
        var i = 0
        while (i < whole) {
            val s16 = when (bps) {
                // Unsigned 8-bit is biased by 128; the wider widths are signed little-endian.
                1 -> ((frame.bytes[i].toInt() and 0xFF) - 128) shl 8
                3 -> (frame.bytes[i + 1].toInt() and 0xFF) or (frame.bytes[i + 2].toInt() shl 8)
                4 -> if (frame.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                    clampToS16(Float.fromBits(readLeInt32(frame.bytes, i)))
                } else {
                    (frame.bytes[i + 2].toInt() and 0xFF) or (frame.bytes[i + 3].toInt() shl 8)
                }
                else -> return null
            }
            writeS16Le(out, o, s16)
            o += 2
            i += bps
        }
        return out
    }

    /** Little-endian int32 at [i] — the bit pattern of a float32 when the decoder handed us float. */
    private fun readLeInt32(bytes: ByteArray, i: Int): Int =
        (bytes[i].toInt() and 0xFF) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            ((bytes[i + 2].toInt() and 0xFF) shl 16) or
            (bytes[i + 3].toInt() shl 24)

    private fun clampToS16(v: Float): Int {
        val clamped = if (v > 1f) 1f else if (v < -1f) -1f else v
        return (clamped * 32767f).toInt()
    }

    private fun writeS16Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = value.toByte()
        dst[offset + 1] = (value ushr 8).toByte()
    }
}
