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
import kotlin.math.roundToInt

/** Always stays in the PCM chain, so toggling EQ mid-track never needs a seek or player rebuild. */
internal class EqualizerAudioProcessor(
    private val readSettings: () -> SoftwareEqualizerSettings,
) : BaseAudioProcessor() {
    private var equalizer: SoftwareEqualizer? = null
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.sampleRate <= 0) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        frame = FloatArray(inputAudioFormat.channelCount)
        equalizer = SoftwareEqualizer(inputAudioFormat.sampleRate, frame.size, readSettings())
    }

    override fun onReset() { equalizer = null; frame = FloatArray(0) }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 drains the pipeline with its shared EMPTY_BUFFER. Never replace/copy that buffer:
        // BaseAudioProcessor initially owns the very same singleton, and put(self) is illegal.
        if (!inputBuffer.hasRemaining()) return
        val dsp = checkNotNull(equalizer)
        dsp.update(readSettings())
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (dsp.bypassed) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= frame.size * 2) {
                for (channel in frame.indices) frame[channel] = inputBuffer.short / 32768f
                dsp.process(frame)
                for (sample in frame) output.putShort((sample * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        output.flip()
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
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(buildAudioProcessors(context))
            .build()
        return PcmOnlyAudioSink(sink)
    }

    protected open fun buildAudioProcessors(context: Context): Array<AudioProcessor> {
        val controller = AndroidEqualizerController.get(context)
        return arrayOf(EqualizerAudioProcessor { controller.settings })
    }
}

internal class PcmOnlyAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
        else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
}
