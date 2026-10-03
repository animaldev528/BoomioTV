package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Roku-style private-listening tee.
 *
 * Sits between the stock DefaultAudioSink and [PlaybackSpeedAwareAudioSink]
 * (DefaultAudioSink ← this ← PlaybackSpeedAwareAudioSink). While a fork is armed ([startFork]) it
 * mirrors every decoded PCM buffer to a [PrivateListeningAudioSender], which unicasts PCM16 stereo
 * to the companion phone. The phone hears exactly what the TV is decoding — same clock, no second
 * player, no drift.
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
    private var pcmIsFloat: Boolean = false
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
        if ((encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) &&
            inputFormat.sampleRate > 0 && inputFormat.channelCount > 0
        ) {
            isPcmConfigured = true
            pcmSampleRate = inputFormat.sampleRate
            pcmChannelCount = inputFormat.channelCount
            pcmIsFloat = encoding == C.ENCODING_PCM_FLOAT
        } else {
            // Compressed/passthrough or unknown. While a fork is armed the force-PCM policy in
            // PlaybackSpeedAwareAudioSink drives a reconfigure to decoded PCM; until then, skip.
            isPcmConfigured = false
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
            active.offer(buffer, presentationTimeUs, pcmSampleRate, pcmChannelCount, pcmIsFloat)
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
