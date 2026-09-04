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
 * worker thread drains that queue, converts to PCM16 stereo (with a dialogue-preserving downmix for
 * 5.1/7.1), and sends it to the companion phone over UDP. Because it shares the main decode clock
 * it cannot drift from the TV the way a second shadow player could.
 *
 * Wire format (one datagram each, from the TV to phoneIp:port):
 *   bytes 0..3  sample rate in Hz, big-endian int32   — phone (re)inits its AudioTrack from this,
 *   bytes 4..5  sequence number, big-endian uint16    — increments per datagram; lets the phone
 *                                                       detect loss and gap bytes,
 *   bytes 6..   PCM16 signed little-endian, stereo interleaved.
 *
 * No separate format header is needed: every datagram is self-describing, so a phone that starts
 * listening mid-stream syncs on its very first datagram, and a mid-stream sample-rate change is
 * carried by the per-datagram rate. Sequence gaps are expected (UDP + deliberate drops under
 * backpressure); the phone should treat a gap as a short silence rather than a fatal error.
 *
 * The sender must never block the audio thread: [offer] only memcpys into a fresh byte[] and
 * enqueues (dropping when the bounded queue is full). All downmix/encode/network work happens on
 * the worker thread.
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
        private const val PREAMBLE_BYTES = 6
        private const val MAX_CHUNK = MAX_DATAGRAM - PREAMBLE_BYTES

        /** Bounded so a stalled phone can never back-pressure the audio thread or grow memory. */
        private const val QUEUE_CAPACITY = 128
        private const val POLL_TIMEOUT_MS = 20L

        // Dialogue-preserving downmix coefficients (R6). Center is retained at ~ -3 dB on both
        // channels; the back/side surrounds are folded in at -3 dB / -6 dB.
        private const val CENTER_GAIN = 0.7071f   // 1 / sqrt(2)
        private const val BACK_GAIN = 0.7071f
        private const val SIDE_GAIN = 0.5f
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
                    val pcm16Stereo = renderStereoPcm16(frame)
                    if (pcm16Stereo == null) {
                        droppedFrames++
                        continue
                    }
                    var offset = 0
                    while (offset < pcm16Stereo.size) {
                        val chunk = minOf(MAX_CHUNK, pcm16Stereo.size - offset)
                        // Rebuild the preamble each datagram: rate is constant per frame, seq advances.
                        val datagram = ByteArray(PREAMBLE_BYTES + chunk)
                        datagram[0] = (frame.sampleRate ushr 24).toByte()
                        datagram[1] = (frame.sampleRate ushr 16).toByte()
                        datagram[2] = (frame.sampleRate ushr 8).toByte()
                        datagram[3] = frame.sampleRate.toByte()
                        datagram[4] = (seq ushr 8).toByte()
                        datagram[5] = seq.toByte()
                        System.arraycopy(pcm16Stereo, offset, datagram, PREAMBLE_BYTES, chunk)
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
     * Convert one captured buffer to PCM16 signed little-endian, stereo interleaved.
     * Returns null when the buffer's layout is not convertible (shouldn't happen — the sink only
     * forwards PCM formats).
     */
    private fun renderStereoPcm16(frame: PcmFrame): ByteArray? {
        val n = frame.channelCount
        if (n <= 0) return null
        val sampleCount = if (frame.isFloat) {
            frame.bytes.size / (4 * n)
        } else {
            frame.bytes.size / (2 * n)
        }
        if (sampleCount <= 0) return null

        val input = ByteBuffer.wrap(frame.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArray(sampleCount * 4) // 2ch * 2 bytes

        val useCenterDownmix = when (n) {
            6 -> true   // FL FR FC LFE BL BR
            8 -> true   // FL FR FC LFE BL BR SL SR
            else -> false
        }

        var o = 0
        for (k in 0 until sampleCount) {
            val (l, r) = when {
                n == 1 -> {
                    val s = sample(input, frame.isFloat, k * n)
                    s to s
                }
                !useCenterDownmix -> {
                    // n >= 2 with an unknown/non-surround layout: keep the first two channels.
                    sample(input, frame.isFloat, k * n) to sample(input, frame.isFloat, k * n + 1)
                }
                n == 6 -> {
                    val fl = sample(input, frame.isFloat, k * 6 + 0)
                    val fr = sample(input, frame.isFloat, k * 6 + 1)
                    val fc = sample(input, frame.isFloat, k * 6 + 2)
                    // LFE (index 3) deliberately omitted — a sub path adds little to phone earbuds.
                    val bl = sample(input, frame.isFloat, k * 6 + 4)
                    val br = sample(input, frame.isFloat, k * 6 + 5)
                    (fl + CENTER_GAIN * fc + BACK_GAIN * bl) to (fr + CENTER_GAIN * fc + BACK_GAIN * br)
                }
                else -> { // n == 8
                    val fl = sample(input, frame.isFloat, k * 8 + 0)
                    val fr = sample(input, frame.isFloat, k * 8 + 1)
                    val fc = sample(input, frame.isFloat, k * 8 + 2)
                    // LFE (index 3) deliberately omitted.
                    val bl = sample(input, frame.isFloat, k * 8 + 4)
                    val br = sample(input, frame.isFloat, k * 8 + 5)
                    val sl = sample(input, frame.isFloat, k * 8 + 6)
                    val sr = sample(input, frame.isFloat, k * 8 + 7)
                    (fl + CENTER_GAIN * fc + BACK_GAIN * bl + SIDE_GAIN * sl) to
                        (fr + CENTER_GAIN * fc + BACK_GAIN * br + SIDE_GAIN * sr)
                }
            }
            writeS16Le(out, o, clampToS16(l))
            writeS16Le(out, o + 2, clampToS16(r))
            o += 4
        }
        return out
    }

    /**
     * Sample at absolute interleaved sample index [i] (channel position within the buffer), as a
     * float in -1..1. Converts the sample index to a byte offset internally.
     */
    private fun sample(input: ByteBuffer, isFloat: Boolean, i: Int): Float {
        return if (isFloat) {
            input.getFloat(i * 4)
        } else {
            input.getShort(i * 2).toFloat() / 32768f
        }
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
