/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Always stays in the PCM chain, so toggling EQ mid-track never needs a seek or player rebuild. */
internal class EqualizerAudioProcessor(
    private val meter: EqualizerMeter? = null,
    /** Set by the sink when the renderer repositions; the next stream then starts with a fade. */
    private val restart: AtomicBoolean = AtomicBoolean(false),
    private val readSettings: () -> SoftwareEqualizerSettings,
) : BaseAudioProcessor() {
    private var equalizer: NativeEqualizer? = null
    /**
     * DefaultAudioSink flushes the chain again once the new AudioTrack exists, so one reposition
     * reaches onFlush twice; the fade waits for the first PCM instead of riding on a single flush.
     */
    private var fadePending = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.channelCount > 32 || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.sampleRate > 768000) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        equalizer?.close()
        equalizer = NativeEqualizer(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, readSettings())
            .also {
                if (restart.getAndSet(false)) fadePending = true
                if (fadePending) it.fadeIn()
            }
    }

    override fun onReset() { equalizer?.close(); equalizer = null; fadePending = false }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val dsp = checkNotNull(equalizer)
        dsp.update(readSettings())
        val output = replaceOutputBuffer(inputBuffer.remaining())
        dsp.processPcm16(inputBuffer, output)
        fadePending = false
        output.flip()
        meter?.report(inputAudioFormat.sampleRate, dsp.takeMeterGain())
    }

    override fun onQueueEndOfStream() {
        val dsp = equalizer ?: return
        val pending = dsp.pendingFrames
        if (pending == 0) return
        val previous = output
        val prefix = ByteArray(previous.remaining()).also { previous.get(it) }
        val output = replaceOutputBuffer(prefix.size + pending * inputAudioFormat.channelCount * 2)
        output.put(prefix)
        dsp.drainPcm16(output)
        output.flip()
        meter?.report(inputAudioFormat.sampleRate, dsp.takeMeterGain())
    }

}

/**
 * Compressed passthrough/offload would skip ALL PCM processors. Decode locally for every route,
 * even when EQ is currently off, so speaker, Bluetooth and USB respond to live edits equally.
 * DefaultAudioSink converts high-resolution/float PCM to PCM16 before our custom processor.
 */
internal open class EqualizerRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val restart = AtomicBoolean(false)
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(buildAudioProcessors(context, restart))
            .build()
        return PcmOnlyAudioSink(sink, restart)
    }

    protected open fun buildAudioProcessors(context: Context, restart: AtomicBoolean): Array<AudioProcessor> {
        val controller = AndroidEqualizerController.get(context)
        return arrayOf(EqualizerAudioProcessor(controller.meter, restart) { controller.settings })
    }
}

/**
 * Only the renderer calls the public [flush]: a seek, a skip or the first start. DefaultAudioSink's
 * own reconfiguration between tracks flushes internally, and a gapless join never flushes, so a
 * continuous stream is never faded. Arming before the flush lets the processor's onFlush see it.
 */
internal class PcmOnlyAudioSink(sink: AudioSink, private val restart: AtomicBoolean = AtomicBoolean(false)) : ForwardingAudioSink(sink) {
    override fun flush() {
        restart.set(true)
        super.flush()
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
        else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
}
