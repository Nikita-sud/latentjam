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
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.ByteOrder

/** The opening of a track as [CrossfadeIntroCapture] decoded it: PCM16 at [sampleRate]. */
internal class PcmIntro(
    val sampleRate: Int,
    val channelCount: Int,
    val pcm: ByteBuffer,
    val frames: Int,
    val durationMs: Long,
    /** The [IntroScratch] slot [pcm] lives in. */
    val slot: Int,
)

/**
 * The process's scratch memory for decoded openings: two slots, each a memory-mapped file that is
 * unlinked as soon as it is mapped, so its pages are file cache the system can write out — on ART a
 * direct buffer is a managed array that counts against the heap limit, which a 48 MB phone cannot
 * spare for a 2.5–8 MB opening.
 *
 * A slot is mapped once and grown only when an opening needs more, so long background playback
 * keeps at most two mappings instead of one per seam waiting for the collector. Two, because a
 * new capture must never write where the mixer may still be reading — an overlap let go fades its
 * opening out over [CROSSFADE_CANCEL_MS] — so it takes the slot [CrossfadeSettings.soundingSlot]
 * is not. Each mapping is filled with zeros through the file before it is mapped: the blocks are
 * then really allocated, and a full disk fails here, as "no capture", instead of as a SIGBUS when
 * the mixer's pages are first written.
 */
internal class IntroScratch(private val directory: File) {
    private val slots = arrayOfNulls<ByteBuffer>(SLOTS)

    /** The slot a new capture may fill while the mixer reads [sounding] (or nothing, -1). */
    fun freeSlot(sounding: Int): Int = if (sounding == 0) 1 else 0

    /** A view of [slot] of exactly [bytes], position 0, or null when the space is not there. Any thread. */
    @Synchronized
    fun claim(slot: Int, bytes: Int): ByteBuffer? {
        val mapping = slots[slot]?.takeIf { it.capacity() >= bytes }
            ?: map(bytes)?.also { slots[slot] = it }
            ?: return null
        return mapping.duplicate().apply { clear(); limit(bytes) }.order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun map(bytes: Int): ByteBuffer? = runCatching {
        val file = File.createTempFile("crossfade-intro", ".pcm", directory)
        try {
            RandomAccessFile(file, "rw").use { scratch ->
                val zeros = ByteBuffer.allocate(ZERO_CHUNK_BYTES)
                var written = 0L
                while (written < bytes) {
                    zeros.clear()
                    zeros.limit(minOf(ZERO_CHUNK_BYTES.toLong(), bytes - written).toInt())
                    written += scratch.channel.write(zeros, written)
                }
                scratch.channel.force(false)
                // The mapping outlives the channel and the name.
                scratch.channel.map(FileChannel.MapMode.READ_WRITE, 0L, bytes.toLong())
            }
        } finally {
            file.delete()
        }
    }.getOrNull()

    companion object {
        const val SLOTS = 2
        private const val ZERO_CHUNK_BYTES = 64 * 1024

        @Volatile private var instance: IntroScratch? = null

        fun get(context: Context): IntroScratch = instance ?: synchronized(this) {
            instance ?: IntroScratch(context.applicationContext.cacheDir).also { instance = it }
        }
    }
}

/**
 * Decodes the first [lengthMs] of [item] without playing it, for an overlap to mix in.
 *
 * A short-lived ExoPlayer with the main player's decoder and the same sink stages — gapless
 * trimming, conversion to PCM16 — but an output that discards instead of sounding, so it runs as
 * fast as the decoder does. The PCM it captures is therefore what the main player will produce for
 * the same frames, which lets the main player drop exactly the part already heard.
 *
 * The capture goes to [slot] of [scratch], off the Java heap. Above [MAX_INTRO_BYTES], when the
 * scratch space cannot be reserved, or when the heap has less than [MIN_HEAP_HEADROOM_BYTES] free
 * for the short-lived decoder itself, there is no capture and the seam falls back to the plain
 * fades. Main thread only; [onDone] gets null on any failure.
 */
internal class CrossfadeIntroCapture(
    private val context: Context,
    private val item: MediaItem,
    private val lengthMs: Long,
    private val scratch: IntroScratch,
    private val slot: Int,
    private val onDone: (PcmIntro?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var finished = false
    private val capture = CaptureProcessor()

    fun start() {
        if (heapHeadroomBytes() < MIN_HEAP_HEADROOM_BYTES) {
            finished = true
            mainHandler.post { onDone(null) }
            return
        }
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
                    .setBufferDurationsMs(LOAD_MIN_MS, LOAD_MAX_MS, LOAD_START_MS, LOAD_START_MS)
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
            buffer = scratch.claim(slot, targetBytes) ?: run {
                failed = true
                mainHandler.post { finish(null) }
                return
            }
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
                slot = slot,
            )
        }
    }

    private companion object {
        /** About 21.8 s of 96 kHz stereo PCM16; the 14 s a 12 s overlap decodes at 44.1/48 kHz is a third of it. */
        const val MAX_INTRO_BYTES = 8L * 1024 * 1024
        const val TIMEOUT_MS = 15_000L
        const val LOAD_MIN_MS = 2_000
        const val LOAD_MAX_MS = 5_000

        /**
         * Starts decoding on a quarter second. The byte cap below holds under 3 s of 96 kHz PCM, so
         * a start threshold near it would wait for data the cap never lets in.
         */
        const val LOAD_START_MS = 250
        const val LOAD_TARGET_BYTES = 1024 * 1024

        /** The decoder's own buffers and objects, with room to spare for everything else. */
        const val MIN_HEAP_HEADROOM_BYTES = 12L * 1024 * 1024

        fun heapHeadroomBytes(): Long = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }
    }
}

/** Everything about the real output's configuration, except that nothing reaches a speaker. */
private class DiscardingOutputProvider(provider: AudioOutputProvider) : ForwardingAudioOutputProvider(provider) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = DiscardingAudioOutput(config)
}

/**
 * Consumes every write at once and reports all but one buffer of it as played, so the decoder sets
 * the pace. That last buffer is pending audio, as on a real track: without it the audio renderer is
 * never ready, the player never leaves buffering, its loaded samples are never released, and the
 * loader stops at its byte cap — at 96 kHz, before the opening is complete.
 */
private class DiscardingAudioOutput(private val config: AudioOutputProvider.OutputConfig) : AudioOutput {
    private val frameBytes = Util.getPcmFrameSize(config.encoding, Integer.bitCount(config.channelMask)).coerceAtLeast(1)
    private var frames = 0L
    private var stopped = false
    private val bufferFrames = (config.bufferSize / frameBytes).toLong().coerceAtLeast(1L)
    private var playbackParameters = PlaybackParameters.DEFAULT

    override fun play() { stopped = false }
    override fun pause() = Unit
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        frames += buffer.remaining() / frameBytes
        buffer.position(buffer.limit())
        return true
    }
    override fun flush() {
        frames = 0L
        stopped = false
    }

    // The end of the stream: the held-back buffer has "played" too, so the sink can end and the
    // player reach STATE_ENDED — how a track shorter than the request completes its capture.
    override fun stop() { stopped = true }
    override fun release() = Unit
    override fun setVolume(volume: Float) = Unit
    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = config.audioSessionId
    override fun getSampleRate(): Int = config.sampleRate
    override fun getBufferSizeInFrames(): Long = (config.bufferSize / frameBytes).toLong()
    override fun getPositionUs(): Long =
        (if (stopped) frames else (frames - bufferFrames).coerceAtLeast(0L)) * 1_000_000L /
            config.sampleRate.coerceAtLeast(1)
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
