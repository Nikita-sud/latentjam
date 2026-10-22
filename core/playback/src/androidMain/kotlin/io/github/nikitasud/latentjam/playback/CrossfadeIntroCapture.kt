/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The opening of a track as [CrossfadeIntroCapture] decoded it: PCM16 at [sampleRate]. */
internal class PcmIntro(val sampleRate: Int, val channelCount: Int, val pcm: ByteBuffer, val frames: Int, val durationMs: Long)

/**
 * Decodes the first [lengthMs] of [item] without playing it, for an overlap to mix in.
 *
 * A short-lived ExoPlayer with the main player's decoder and the same sink stages — gapless
 * trimming, conversion to PCM16 — but an output that discards instead of sounding, so it runs as
 * fast as the decoder does. The PCM it captures is therefore what the main player will produce for
 * the same frames, which lets the main player drop exactly the part already heard.
 *
 * The capture lives off the Java heap (a direct buffer) and is refused above [MAX_INTRO_BYTES]:
 * small-heap phones keep their headroom. Main thread only; [onDone] gets null on any failure.
 */
internal class CrossfadeIntroCapture(
    private val context: Context,
    private val item: MediaItem,
    private val lengthMs: Long,
    private val onDone: (PcmIntro?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var finished = false
    private val capture = CaptureProcessor()

    fun start() {
        val sink = PcmOnlyAudioSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf<AudioProcessor>(capture))
                .setAudioOutputProvider(
                    DiscardingOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()),
                )
                .build(),
        )
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, sink))
        }
        val built = ExoPlayer.Builder(context, renderers)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(LOAD_MIN_MS, LOAD_MAX_MS, LOAD_MIN_MS, LOAD_MIN_MS)
                    .setTargetBufferBytes(LOAD_TARGET_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            .setLooper(Looper.getMainLooper())
            .build()
        player = built
        built.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = finish(null)
            override fun onPlaybackStateChanged(playbackState: Int) {
                // A track shorter than the request ends first; what was captured is all of it.
                if (playbackState == Player.STATE_ENDED) finish(capture.result(built.duration))
            }
        })
        built.volume = 0f
        built.setMediaItem(item)
        built.prepare()
        built.play()
        mainHandler.postDelayed({ finish(null) }, TIMEOUT_MS)
    }

    fun cancel() {
        finished = true
        release()
    }

    private fun finish(intro: PcmIntro?) {
        if (finished) return
        finished = true
        release()
        onDone(intro)
    }

    private fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    /** Copies the PCM16 that reaches the end of the sink stages; posts once it has enough. */
    private inner class CaptureProcessor : BaseAudioProcessor() {
        @Volatile private var buffer: ByteBuffer? = null
        @Volatile private var failed = false
        private var targetBytes = 0

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            return inputAudioFormat
        }

        override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
            if (buffer != null) return
            val frameBytes = inputAudioFormat.channelCount * 2
            val bytes = lengthMs * inputAudioFormat.sampleRate / 1000L * frameBytes
            if (bytes <= 0L || bytes > MAX_INTRO_BYTES) {
                failed = true
                mainHandler.post { finish(null) }
                return
            }
            targetBytes = bytes.toInt()
            buffer = ByteBuffer.allocateDirect(targetBytes).order(ByteOrder.LITTLE_ENDIAN)
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            // An empty drain is the shared EMPTY_BUFFER, which an empty output buffer also is.
            if (remaining == 0) return
            val target = buffer
            if (target != null && !failed && target.hasRemaining()) {
                val slice = inputBuffer.duplicate()
                slice.limit(slice.position() + minOf(remaining, target.remaining()))
                target.put(slice)
                if (!target.hasRemaining()) mainHandler.post { finish(result(C.TIME_UNSET)) }
            }
            // Passed on so the discarding output's clock advances and the loader keeps reading ahead.
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
        }

        /** Main thread, after the player posted completion: a read-only view of what was captured. */
        fun result(durationMs: Long): PcmIntro? {
            val target = buffer ?: return null
            if (failed) return null
            val frameBytes = inputAudioFormat.channelCount * 2
            val view = target.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val frames = view.position() / frameBytes
            if (frames <= 0) return null
            view.flip()
            return PcmIntro(
                sampleRate = inputAudioFormat.sampleRate,
                channelCount = inputAudioFormat.channelCount,
                pcm = view.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
                frames = frames,
                durationMs = durationMs.takeIf { it != C.TIME_UNSET } ?: player?.duration ?: C.TIME_UNSET,
            )
        }
    }

    private companion object {
        /** About 16 s of 96 kHz stereo; the 12 s maximum at 44.1/48 kHz needs a third of it. */
        const val MAX_INTRO_BYTES = 8L * 1024 * 1024
        const val TIMEOUT_MS = 15_000L
        const val LOAD_MIN_MS = 2_000
        const val LOAD_MAX_MS = 5_000
        const val LOAD_TARGET_BYTES = 2 * 1024 * 1024
    }
}

/** Everything about the real output's configuration, except that nothing reaches a speaker. */
private class DiscardingOutputProvider(provider: AudioOutputProvider) : ForwardingAudioOutputProvider(provider) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = DiscardingAudioOutput(config)
}

/** Consumes every write at once and reports it as played, so the decoder sets the pace. */
private class DiscardingAudioOutput(private val config: AudioOutputProvider.OutputConfig) : AudioOutput {
    private val frameBytes = Util.getPcmFrameSize(config.encoding, Integer.bitCount(config.channelMask)).coerceAtLeast(1)
    private var frames = 0L
    private var playbackParameters = PlaybackParameters.DEFAULT

    override fun play() = Unit
    override fun pause() = Unit
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        frames += buffer.remaining() / frameBytes
        buffer.position(buffer.limit())
        return true
    }
    override fun flush() { frames = 0L }
    override fun stop() = Unit
    override fun release() = Unit
    override fun setVolume(volume: Float) = Unit
    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = config.audioSessionId
    override fun getSampleRate(): Int = config.sampleRate
    override fun getBufferSizeInFrames(): Long = (config.bufferSize / frameBytes).toLong()
    override fun getPositionUs(): Long = frames * 1_000_000L / config.sampleRate.coerceAtLeast(1)
    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters
    override fun isStalled(): Boolean = false
    override fun addListener(listener: AudioOutput.Listener) = Unit
    override fun removeListener(listener: AudioOutput.Listener) = Unit
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) { playbackParameters = playbackParams }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: android.media.AudioDeviceInfo?) = Unit
}
