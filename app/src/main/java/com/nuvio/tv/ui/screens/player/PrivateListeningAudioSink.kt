package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

private const val TAG = "PrivateListeningSink"

/**
 * Roku-style private-listening tee.
 *
 * Sits between the stock DefaultAudioSink and [PlaybackSpeedAwareAudioSink]
 * (DefaultAudioSink ← this ← PlaybackSpeedAwareAudioSink). While a fork is armed ([startFork]) it
 * mirrors every decoded PCM buffer to a [PrivateListeningAudioSender], which unicasts PCM16 stereo
 * to the companion phone. Any decoded PCM width is folded down to 16 bits on the wire, so a 24-bit
 * source plays rather than going silent while the TV's own output keeps its full precision. The
 * phone hears exactly what the TV is decoding — same clock, no second player, no drift.
 *
 * Because the fork is forced to a decode-to-PCM path by PlaybackSpeedAwareAudioSink (see
 * [PlaybackSpeedAwareAudioSink.setPhoneForcePcm]), this sink is only ever asked to tee PCM, never a
 * compressed passthrough bitstream. Buffers of a non-PCM format are skipped as a defensive fallback
 * during the brief window before the force-PCM reconfigure lands.
 *
 * Audio-thread cost when armed is one memcpy + an enqueue; the worker thread in the sender owns all
 * downmix/encode/network work, and a full queue drops rather than stalls the TV.
 */
internal class PrivateListeningAudioSink(
    sink: AudioSink,
) : ForwardingAudioSink(sink) {

    private val lock = Any()

    @Volatile
    private var sender: PrivateListeningAudioSender? = null

    // PCM layout of the current configured input. Only meaningful while isPcmConfigured is true.
    @Volatile
    private var pcmSampleRate: Int = 48_000
    @Volatile
    private var pcmChannelCount: Int = 2
    @Volatile
    private var pcmEncoding: Int = C.ENCODING_PCM_16BIT
    @Volatile
    private var isPcmConfigured: Boolean = false

    // Presentation time of the last buffer we teed. A buffer that DefaultAudioSink only partially
    // consumed is retried with the SAME PTS; we must not tee it a second time or the phone double-
    // plays that slice. Reset on flush/discontinuity/reconfigure, when the timeline genuinely jumps.
    @Volatile
    private var lastTeedPtsUs: Long = Long.MIN_VALUE

    val isForkActive: Boolean get() = sender != null

    val sentDatagrams: Long get() = sender?.sentDatagrams ?: 0L

    val droppedFrames: Long get() = sender?.droppedFrames ?: 0L

    val sendErrors: Long get() = sender?.sendErrors ?: 0L

    /**
     * Arm the tee to unicast to [phoneIp]:[port]. Returns false (and leaves the existing fork
     * running) if a fork is already active.
     */
    fun startFork(phoneIp: String, port: Int): Boolean {
        synchronized(lock) {
            if (sender != null) return false
            val newSender = PrivateListeningAudioSender(phoneIp, port)
            sender = newSender
            newSender.start()
            // What the wire carries the moment we arm. A fork does not re-pin the renderer, so if
            // the fold was not already reachable this count stays the track's own for the whole
            // session. The phone IP is deliberately not logged -- it is not needed for the downmix
            // question and logcat is a leaking surface.
            Log.i(
                TAG,
                "fork armed; tee carrying ${
                    PrivateListeningAudioSender.pcmFormatName(pcmEncoding)
                } " +
                    "${pcmSampleRate}Hz ${pcmChannelCount}ch " +
                    "(${
                        PrivateListeningAudioSender.datagramsPerSecond(
                            pcmSampleRate,
                            pcmChannelCount,
                        )
                    } datagrams/s) pcmConfigured=$isPcmConfigured",
            )
            return true
        }
    }

    /** Unarm the tee. Safe to call when nothing is armed. */
    fun stopFork() {
        val active = synchronized(lock) {
            val s = sender
            sender = null
            s
        }
        active?.stop()
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val encoding = inputFormat.pcmEncoding
        // ⚠️ Any decoded PCM WIDTH is accepted, not just 16-bit and float. The FFmpeg renderer
        // hands us whatever the source decoded to, and a PCM 2.0 DVD remux decodes to
        // C.ENCODING_PCM_24BIT (21). Accepting only 16-bit/float is what made private listening
        // TOTALLY SILENT on such titles: the sink configured fine, the tee skipped every buffer,
        // and the phone heard nothing at all. The wire is PCM16 regardless (see the sender), so a
        // wider width is folded down there -- which also leaves the TV's own output at full 24-bit.
        if (PrivateListeningAudioSender.pcmBytesPerSample(encoding) > 0 &&
            inputFormat.sampleRate > 0 && inputFormat.channelCount > 0
        ) {
            isPcmConfigured = true
            pcmSampleRate = inputFormat.sampleRate
            pcmChannelCount = inputFormat.channelCount
            pcmEncoding = encoding
            // The REALITY half of the downmix log, and the only place it is observable: this sink
            // sits above DefaultAudioSink, so this count is what the RENDERER produced, not what the
            // track carried. If a 5.1 track arrives here as 6 channels, the fold did not run -- the
            // FFmpeg renderer was not the active one -- and the wire will carry ~485 datagrams/s
            // instead of 2.0's ~161. Pair this with the "downmix ask" line in the route path.
            Log.i(
                TAG,
                "tee configured: ${
                    PrivateListeningAudioSender.pcmFormatName(encoding)
                } ${pcmSampleRate}Hz " +
                    "${pcmChannelCount}ch " +
                    "(${
                        PrivateListeningAudioSender.datagramsPerSecond(
                            pcmSampleRate,
                            pcmChannelCount,
                        )
                    } datagrams/s) fork=${sender != null}",
            )
        } else {
            // Compressed/passthrough or unknown. While a fork is armed the force-PCM policy in
            // PlaybackSpeedAwareAudioSink drives a reconfigure to decoded PCM; until then, skip.
            isPcmConfigured = false
            Log.i(TAG, "tee configured with non-PCM encoding=$encoding -- nothing teed yet")
        }
        lastTeedPtsUs = Long.MIN_VALUE
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val active = sender
        if (active != null && isPcmConfigured && buffer.hasRemaining() &&
            presentationTimeUs != lastTeedPtsUs
        ) {
            lastTeedPtsUs = presentationTimeUs
            active.offer(buffer, presentationTimeUs, pcmSampleRate, pcmChannelCount, pcmEncoding)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() {
        lastTeedPtsUs = Long.MIN_VALUE
        sender?.clearQueue()
        super.flush()
    }

    override fun handleDiscontinuity() {
        lastTeedPtsUs = Long.MIN_VALUE
        sender?.clearQueue()
        super.handleDiscontinuity()
    }

    override fun reset() {
        lastTeedPtsUs = Long.MIN_VALUE
        sender?.clearQueue()
        super.reset()
    }
}
