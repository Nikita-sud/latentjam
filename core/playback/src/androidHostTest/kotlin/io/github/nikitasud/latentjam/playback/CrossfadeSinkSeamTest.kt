/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real DefaultAudioSink, with the app's stages in front of it ([PcmOnlyAudioSink],
 * [SeamResamplingAudioSink]) and inside it ([CrossfadeAudioProcessor]). It is fed the way the audio
 * renderer feeds it, over an output that plays everything at once and keeps what it played.
 */
internal class CrossfadeSinkSeamTest {
    private val settings = CrossfadeSettings().apply { fadeMs = 12_000 }
    private val streams = PcmStreams()
    private val errors = mutableListOf<Exception>()

    /** Left-channel samples played since [recording] was set. */
    private var played = ShortArray(1 shl 20)
    private var playedCount = 0
    private var recording = false

    private inner class Output(private val config: AudioOutputProvider.OutputConfig) : AudioOutput {
        private var frames = 0L
        override fun play() = Unit
        override fun pause() = Unit
        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            val shorts = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            while (recording && shorts.remaining() >= 2) {
                if (playedCount == played.size) played = played.copyOf(played.size * 2)
                played[playedCount++] = shorts.get()
                shorts.get()
            }
            frames += buffer.remaining() / 4
            buffer.position(buffer.limit())
            return true
        }
        override fun flush() { frames = 0L }
        override fun stop() = Unit
        override fun release() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun isOffloadedPlayback() = false
        override fun getAudioSessionId() = config.audioSessionId
        override fun getSampleRate() = config.sampleRate
        override fun getBufferSizeInFrames() = (config.bufferSize / 4).toLong()
        override fun getPositionUs() = frames * 1_000_000L / config.sampleRate
        override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
        override fun isStalled() = false
        override fun addListener(listener: AudioOutput.Listener) = Unit
        override fun removeListener(listener: AudioOutput.Listener) = Unit
        override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
        override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
        override fun setOffloadEndOfStream() = Unit
        override fun attachAuxEffect(effectId: Int) = Unit
        override fun setAuxEffectSendLevel(level: Float) = Unit
        override fun setPreferredDevice(preferredDevice: android.media.AudioDeviceInfo?) = Unit
    }

    private val provider = object : AudioOutputProvider {
        override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig) =
            AudioOutputProvider.FormatSupport.Builder()
                .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
        override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig) =
            AudioOutputProvider.OutputConfig.Builder()
                .setEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(formatConfig.format.sampleRate)
                .setChannelMask(CHANNEL_OUT_STEREO)
                .setBufferSize(formatConfig.format.sampleRate)
                .build()
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = Output(config)
        override fun addListener(listener: AudioOutputProvider.Listener) = Unit
        override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
        override fun release() = Unit
    }

    private val sink: AudioSink = PcmOnlyAudioSink(
        SeamResamplingAudioSink(
            DefaultAudioSink.Builder(android.content.ContextWrapper(null))
                .setAudioProcessorChain(CrossfadeProcessorChain(CrossfadeAudioProcessor(settings, streams)))
                .setAudioOutputProvider(provider)
                .build(),
            settings,
            streams,
        ),
        AtomicBoolean(false),
        streams,
    ).apply {
        setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() = Unit
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
            override fun onAudioSinkError(audioSinkError: Exception) { errors += audioSinkError }
        })
    }

    private fun format(rate: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelCount(2).build()

    /** Feeds frames [from] until [to] at [rate], [chunk] frames a buffer, renderer time from [offsetUs]. */
    private fun feed(rate: Int, offsetUs: Long, from: Long, to: Long, chunk: Int, sample: (Long) -> Int) {
        var frame = from
        while (frame < to) {
            val count = minOf(chunk.toLong(), to - frame).toInt()
            val buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count) { val v = sample(frame + it).toShort(); buffer.putShort(v); buffer.putShort(v) }
            buffer.flip()
            var tries = 0
            while (!sink.handleBuffer(buffer, offsetUs + frame * 1_000_000L / rate, 1)) {
                check(++tries < 1_000) { "the sink never took the buffer at frame $frame" }
            }
            frame += count
        }
    }

    /** The next track as a ruler: each sample is its own time in ms. */
    private fun msAt(frame: Long, rate: Int) = (frame * 1000L / rate).toInt()

    /**
     * The seam from the phone (2026-10-28): Call Me Maybe (Opus, 48 kHz, 192.921 s declared) into
     * Hot Stuff (MP3, 44.1 kHz), a 12 s overlap ending at its last sound 471 ms before the file
     * ends. The Opus decoder put out 0.99 s more than the pages declare, so Media3 marked the
     * output stream change only that far into Hot Stuff; the mp3 needs a decoder of its own.
     */
    @Test
    fun theOpeningHeardInAnOverlapIsNotPlayedAgainWhenTheNextTrackComesFromAnotherDecoder() {
        val offsetA = 1_000_000_000_000L
        val durationAUs = 192_921_041L
        val a = PcmStream(offsetA, "a", { durationAUs })
        val offsetB = offsetA + durationAUs
        val b = PcmStream(offsetB, "b", { 200_000_000L })
        val read = listOf(a, b)
        val heardMs = 12_000 + 471
        // The next track's opening, captured at the output's 48 kHz.
        val introFrames = (introCaptureMs(heardMs.toLong()) * 48).toInt()
        settings.plan = OverlapPlan(
            outgoingId = "a", incomingId = "b", overlapUs = 12_000_000L, sampleRate = 48_000, channelCount = 2,
            pcm = ByteBuffer.allocateDirect(introFrames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(introFrames) { val v = msAt(it.toLong(), 48_000).toShort(); putShort(v); putShort(v) }
                flip()
            },
            frames = introFrames,
            outgoingEndUs = 192_450_000L,
        )

        streams.current = a
        sink.flush()
        sink.configure(format(48_000), 0, null)
        sink.setOutputStreamOffsetUs(offsetA)
        sink.play()
        val decodedA = 48_000L * 193_913 / 1000
        val lastSoundA = 48_000L * 192_450 / 1000
        feed(48_000, offsetA, 0, lastSoundA, 960) { 3000 }
        // From A's last sound on, B's opening plays alone.
        recording = true
        feed(48_000, offsetA, lastSoundA, decodedA, 960) { 0 }

        // A new decoder for the mp3, created while B is read: its first buffer starts B.
        val switch = CodecStreamSwitch()
        switch.onCodecInitialized(b)
        sink.configure(format(44_100), 0, null)
        switch.beforeOutput(streams.current, read)?.let { next ->
            streams.current = next
            sink.setOutputStreamOffsetUs(next.key)
            sink.handleDiscontinuity()
        }
        val lateFrames = 44_100L * 992 / 1000
        feed(44_100, offsetB, 0, lateFrames, 1152) { msAt(it, 44_100) }
        // Media3's own mark, 0.99 s in: the same stream, nothing moves.
        streams.current = b
        sink.setOutputStreamOffsetUs(offsetB)
        sink.handleDiscontinuity()
        feed(44_100, offsetB, lateFrames, 44_100L * 25, 1152) { msAt(it, 44_100) }

        // Re-synced quietly at the join, as at any gapless one.
        assertEquals(emptyList(), errors.map { it.message })
        // 25 s of B decoded: its own position, its first 12.47 s heard under A.
        val positionUs = sink.getCurrentPositionUs(false) - offsetB
        assertTrue(abs(positionUs - 25_000_000L) < 50_000L, "position in B: $positionUs")
        // What played from A's last sound is B's opening, then B, without a step back or a jump.
        val ruler = played.copyOf(playedCount)
        val first = ruler.first().toInt()
        assertTrue(first in 12_000 - 2..12_000 + 2, "B came in at $first ms")
        var worst = 0
        for (i in 1 until ruler.size) worst = maxOf(worst, abs(ruler[i] - ruler[i - 1]))
        assertTrue(worst <= 2, "largest step between samples: $worst ms")
        assertTrue(abs(ruler.last() - 25_000) <= 2, "B ended at ${ruler.last()} ms")
    }

    @Test
    fun aDecoderCreatedForTheStreamAlreadyPlayingMovesNothing() {
        val a = PcmStream(0L, "a", { 1L })
        val b = PcmStream(10L, "b", { 1L })
        val switch = CodecStreamSwitch()
        // The first decoder, after a seek, or one re-created inside a track.
        switch.onCodecInitialized(a)
        assertEquals(null, switch.beforeOutput(a, listOf(a)))
        // Only the first buffer after the decoder is created counts, and never backwards.
        switch.onCodecInitialized(b)
        assertEquals(b, switch.beforeOutput(a, listOf(a, b)))
        assertEquals(null, switch.beforeOutput(a, listOf(a, b)))
        switch.onCodecInitialized(a)
        assertEquals(null, switch.beforeOutput(b, listOf(a, b)))
    }

    private companion object {
        /** AudioFormat.CHANNEL_OUT_STEREO. */
        const val CHANNEL_OUT_STEREO = 12
    }
}
