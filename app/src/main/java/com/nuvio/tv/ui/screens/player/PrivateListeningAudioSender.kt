package com.nuvio.tv.ui.screens.player

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Unicast sender for Roku-style private listening.
 *
 * The TV already decodes the audio it is playing for its own speakers; [PrivateListeningAudioSink]
 * memcpys each decoded PCM buffer on the audio thread into a bounded queue, and this sender's own
 * worker thread drains that queue and sends it to the companion phone over UDP as PCM16 in the
 * DECODER'S NATIVE channel layout. The TV never downmixes: it hands 5.1/7.1 through untouched and
 * lets the phone render them with a matching AudioTrack, so Android's own mixer (or a
 * multichannel-capable sink) does whatever fold the earbuds need. An earlier TV-side stereo
 * downmix was the source of an audible artifact on surround content; passthrough is clean. Because
 * it shares the main decode clock it cannot drift from the TV the way a second shadow player could.
 *
 * Wire format (one datagram each, from the TV to phoneIp:port):
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
) {

    /** One decoded PCM buffer captured on the audio thread. */
    private class PcmFrame(
        val bytes: ByteArray,
        val sampleRate: Int,
        val channelCount: Int,
        val isFloat: Boolean,
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
    }

    /**
     * Called on the audio thread. Copies [source]'s remaining bytes (one memcpy) and enqueues them
     * for the worker. Never blocks; drops the frame if the queue is full so a stalled phone cannot
     * stall the TV.
     */
    fun offer(source: ByteBuffer, ptsUs: Long, sampleRate: Int, channelCount: Int, isFloat: Boolean) {
        if (!running) return
        val n = source.remaining()
        if (n <= 0) return
        val bytes = ByteArray(n)
        source.duplicate().get(bytes)
        if (!queue.offer(PcmFrame(bytes, sampleRate, channelCount, isFloat, ptsUs))) {
            droppedFrames++
        }
    }

    /** Drop all queued frames (seek / discontinuity / flush) so the phone resyncs to the next PTS. */
    fun clearQueue() {
        queue.clear()
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
            while (running) {
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
                    // Chunk on whole frames so the phone's AudioTrack write never splits a sample
                    // (a misaligned tail would be silently dropped by write's frame rounding).
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
        }
    }

    /**
     * Convert one captured buffer to PCM16 signed little-endian in its native interleaved layout.
     * Returns null for an unsupported layout (shouldn't happen — the sink only forwards PCM).
     *
     * int16 input is returned untouched (same reference). float input — decoders hand float buffers
     * for Atmos/E-AC3 folds — is converted sample-by-sample with a clamp to +-1.0; a legal float
     * never exceeds that, so in practice the clamp is a no-op. The channel ORDER is the decoder's
     * (the standard MediaCodec layout FL FR FC LFE BL BR [SL SR]), which the phone's channel mask
     * must match — see the phone-side receiver.
     */
    private fun renderPcm16(frame: PcmFrame): ByteArray? {
        val n = frame.channelCount
        if (n !in SUPPORTED_CHANNEL_COUNTS) return null
        if (!frame.isFloat) {
            val whole = frame.bytes.size / (2 * n) * (2 * n)
            if (whole <= 0) return null
            return if (whole == frame.bytes.size) frame.bytes else frame.bytes.copyOf(whole)
        }
        val sampleCount = frame.bytes.size / (4 * n)
        if (sampleCount <= 0) return null
        val input = ByteBuffer.wrap(frame.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArray(sampleCount * 2 * n)
        var o = 0
        for (i in 0 until sampleCount * n) {
            writeS16Le(out, o, clampToS16(input.getFloat(i * 4)))
            o += 2
        }
        return out
    }

    private fun clampToS16(v: Float): Int {
        val clamped = if (v > 1f) 1f else if (v < -1f) -1f else v
        return (clamped * 32767f).toInt()
    }

    private fun writeS16Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = value.toByte()
        dst[offset + 1] = (value ushr 8).toByte()
    }
}
