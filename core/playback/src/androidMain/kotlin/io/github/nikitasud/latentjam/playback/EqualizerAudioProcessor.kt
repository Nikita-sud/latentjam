/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
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
 * DefaultAudioSink converts high-resolution/float PCM to PCM16 before our custom processors.
 *
 * The chain is [SeamResamplingAudioProcessor] (one rate across a seam), [CrossfadeAudioProcessor]
 * (loudness, transitions, overlaps), then the equalizer, so an overlap is equalised and limited as
 * one signal. The audio renderer names the track each
 * stream belongs to through the sink's [PcmStreams].
 */
internal open class EqualizerRenderersFactory(
    context: Context,
    private val crossfade: CrossfadeSettings = AndroidCrossfade.settings,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val restart = AtomicBoolean(false)
        val streams = PcmStreams()
        val chain = CrossfadeProcessorChain(
            CrossfadeAudioProcessor(crossfade, streams),
            *buildAudioProcessors(context, restart),
            resampler = SeamResamplingAudioProcessor(crossfade, streams),
        )
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessorChain(chain)
            .build()
        return PcmOnlyAudioSink(sink, restart, streams)
    }

    protected open fun buildAudioProcessors(context: Context, restart: AtomicBoolean): Array<AudioProcessor> {
        val controller = AndroidEqualizerController.get(context)
        return arrayOf(EqualizerAudioProcessor(controller.meter, restart) { controller.settings })
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val streams = (audioSink as? PcmOnlyAudioSink)?.streams
        if (streams == null) {
            super.buildAudioRenderers(
                context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink,
                eventHandler, eventListener, out,
            )
            return
        }
        out += StreamTaggingAudioRenderer(
            context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler,
            eventListener, audioSink, streams,
        )
    }
}

/**
 * The stock audio renderer, telling [streams] which media item each output stream plays.
 *
 * A stream is registered when the renderer starts reading it and becomes current when its first
 * output follows the previous stream's last — the moment Media3 moves its output stream offset, or
 * a reposition, which makes the most recently read stream the output one.
 */
internal class StreamTaggingAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
    private val streams: PcmStreams,
) : MediaCodecAudioRenderer(
    context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink,
) {
    private val read = ArrayDeque<PcmStream>()
    private val window = Timeline.Window()
    private val period = Timeline.Period()

    override fun onStreamChanged(
        formats: Array<out Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        val uid = mediaPeriodId.periodUid
        val mediaId = windowOf(uid)?.mediaItem?.mediaId
        read.addLast(
            PcmStream(
                offsetUs,
                mediaId,
                duration = { windowOf(uid)?.durationUs ?: C.TIME_UNSET },
                windowIndex = { windowIndexOf(uid) },
            ),
        )
        while (read.size > MAX_READ_STREAMS) read.removeFirst()
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onOutputStreamOffsetUsChanged(outputStreamOffsetUs: Long) {
        read.lastOrNull { it.key == outputStreamOffsetUs }?.let { streams.current = it }
        super.onOutputStreamOffsetUsChanged(outputStreamOffsetUs)
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        // Before the sink flushes inside super: the processor reads the stream at that flush.
        read.lastOrNull()?.let { streams.current = it }
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
    }

    private fun windowOf(periodUid: Any): Timeline.Window? {
        val index = windowIndexOf(periodUid)
        if (index == C.INDEX_UNSET) return null
        return timeline.getWindow(index, window)
    }

    private fun windowIndexOf(periodUid: Any): Int {
        val timeline = timeline
        val index = timeline.getIndexOfPeriod(periodUid)
        if (index == C.INDEX_UNSET) return C.INDEX_UNSET
        return timeline.getPeriod(index, period).windowIndex
    }

    private companion object {
        const val MAX_READ_STREAMS = 4
    }
}

/**
 * [DefaultAudioSink.DefaultAudioProcessorChain] plus the opening an overlap already played: the sink
 * checkpoints its position at every join and maps later playout through [getMediaDuration], so the
 * dropped frames count as heard and the next track's position reads where its sound is.
 */
internal class CrossfadeProcessorChain(
    private val crossfade: CrossfadeAudioProcessor,
    vararg processors: AudioProcessor,
    resampler: SeamResamplingAudioProcessor? = null,
) : AudioProcessorChain {
    private val inner = DefaultAudioSink.DefaultAudioProcessorChain(
        *listOfNotNull(resampler, crossfade).toTypedArray(), *processors,
    )
    private var skippedUs = 0L

    override fun getAudioProcessors(): Array<AudioProcessor> = inner.audioProcessors
    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
        inner.applyPlaybackParameters(playbackParameters)

    // Called on every checkpoint whatever the speed path, right before the processors flush.
    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        skippedUs = crossfade.prepareSegment()
        return inner.applySkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getMediaDuration(playoutDuration: Long): Long = inner.getMediaDuration(playoutDuration) + skippedUs
    override fun getSkippedOutputFrameCount(): Long = inner.skippedOutputFrameCount
}

/**
 * Only the renderer calls the public [flush]: a seek, a skip or the first start. DefaultAudioSink's
 * own reconfiguration between tracks flushes internally, so a continuous stream is never faded by
 * the equalizer. Arming before the flush lets the processors' onFlush see it. [streams], when given,
 * learns where each buffer sits in its track and counts those repositions.
 */
internal class PcmOnlyAudioSink(
    sink: AudioSink,
    private val restart: AtomicBoolean = AtomicBoolean(false),
    internal val streams: PcmStreams? = null,
) : ForwardingAudioSink(sink) {
    private var outputStreamOffsetUs = 0L

    override fun flush() {
        restart.set(true)
        streams?.let { it.repositions++ }
        super.flush()
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        streams?.bufferMediaUs = (presentationTimeUs - outputStreamOffsetUs).coerceAtLeast(0L)
        streams?.let { it.repositionsAtData = it.repositions }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
        else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
}
