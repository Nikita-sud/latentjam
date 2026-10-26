/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the app wants from the PCM gain stage: written on the main thread by the controller and the
 * service, read on the playback thread by [CrossfadeAudioProcessor]. Each field is replaced whole.
 */
internal class CrossfadeSettings {
    /** The "Smooth transitions" width; 0 keeps hard track boundaries. */
    @Volatile var fadeMs: Long = 0L
        set(value) {
            field = value
            onChange?.invoke()
        }

    /** Loudness normalisation per media id; a missing id plays at full volume. */
    @Volatile var trackGains: Map<String, Float> = emptyMap()

    /**
     * Which ends of each queue row may fade, by the row's player index; see [queueFadeEdges]. A
     * missing row fades both. Published by the controller from its copy of the timeline, so for
     * the moment a reorder takes to reach it (one IPC round trip) an index may name the row's old
     * place; the envelope's slew turns such a brief mismatch into a glide, not a step.
     */
    @Volatile var edges: Map<Int, FadeEdges> = emptyMap()

    /** Where each measured track's sound begins and ends, by media id; see [seamAnchors]. */
    @Volatile var soundSpans: Map<String, SoundSpan> = emptyMap()

    /**
     * Playback stops when the current track ends (the sleep timer's "end of track"): no overlap is
     * prepared, and one in progress lets the next track go, so the track fades out on its own.
     * [onChange] tells the [CrossfadeCoordinator] at once, so the plan is withdrawn now, not at
     * its next periodic look.
     */
    @Volatile var finishingTrack: Boolean = false
        set(value) {
            field = value
            onChange?.invoke()
        }

    /** Told when [fadeMs] or [finishingTrack] changes: the coordinator, which sleeps at 0. */
    @Volatile var onChange: (() -> Unit)? = null

    /**
     * Next was pressed during an overlap: the player seeks to the incoming track at the point heard,
     * and that track's first PCM continues the overlap's rise instead of starting a fade. Recorded
     * before the seek and taken by the first segment of that track to carry data, whatever flushes
     * (a renderer disable, a reposition) come first.
     */
    @Volatile var pendingRise: PendingRise? = null

    /**
     * The sample rate the main player's output runs at ([SeamResamplingAudioProcessor]); the rate an
     * opening is captured at, so its frames are the ones the player will produce. 0 before any.
     */
    @Volatile var outputRate: Int = 0

    /** Which scratch slot ([IntroScratch]) the processor is reading an opening from; -1 for none. */
    @Volatile var soundingSlot: Int = -1

    /** The next track's decoded opening, ready to overlap the end of the current one. */
    @Volatile var plan: OverlapPlan? = null

    /**
     * The outgoing media id whose seam's opening is being decoded right now, before its [plan]
     * exists: the processor waits for it a little past the start of the fade window (a seek close to
     * the end starts the decode late) instead of settling on the plain fades.
     */
    @Volatile var preparingFor: String? = null

    /** One line per seam decision, for the log; playback thread. */
    @Volatile var seamLog: (String) -> Unit = {}

    /**
     * The plan whose opening is sounding under its outgoing track, from the moment the mix is
     * settled (at the start of the fade window, its sound a little later)
     * until the player reports the join ([CrossfadeCoordinator] clears it then) or the overlap is
     * let go. Where in it the listener is comes from the player's own position, which is what is
     * heard, not from the frames processed ahead of the speaker.
     */
    @Volatile var mixing: OverlapPlan? = null

    fun gainOf(mediaId: String?): Float = mediaId?.let(trackGains::get) ?: 1f
    fun edgesAt(mediaIndex: Int?): FadeEdges = mediaIndex?.let(edges::get) ?: FadeEdges.BOTH
}

/**
 * The first [frames] of [incomingId], decoded by the same pipeline the player will later decode it
 * with, to be mixed into the [overlapUs] of [outgoingId] before [outgoingEndUs] (its last sound;
 * [C.TIME_UNSET] for the end of the file). The opening sounds from [incomingStartFrame], its first
 * sound, and plays on over whatever silence the outgoing track has left after its end. [pcm] is
 * little-endian interleaved PCM16 starting at position 0, and is never written again once a plan
 * is published.
 */
internal class OverlapPlan(
    val outgoingId: String,
    val incomingId: String,
    val overlapUs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val pcm: ByteBuffer,
    val frames: Int,
    /** The [IntroScratch] slot holding [pcm]; -1 when it is not a scratch slot. */
    val slot: Int = -1,
    val outgoingEndUs: Long = C.TIME_UNSET,
    val incomingStartFrame: Int = 0,
)

/** See [CrossfadeSettings.pendingRise]. */
internal data class PendingRise(val mediaId: String, val positionUs: Long, val riseUs: Long, val fromUs: Long = 0L)

/** The process-wide settings the service's player reads; the controller lives in the same process. */
internal object AndroidCrossfade {
    val settings = CrossfadeSettings().apply {
        seamLog = { line -> android.util.Log.i("LJCrossfade", line) }
    }
}

/** The track a run of PCM belongs to, as the audio renderer knows it. [key] is unique per play. */
internal class PcmStream(
    val key: Long,
    val mediaId: String?,
    private val duration: () -> Long,
    private val windowIndex: () -> Int = { C.INDEX_UNSET },
) {
    /** The window's duration, [C.TIME_UNSET] while unknown. Read on the playback thread. */
    val durationUs: Long get() = duration()

    /** The row's current player index, after any queue edit; null once it left the queue. */
    val mediaIndex: Int? get() = windowIndex().takeIf { it != C.INDEX_UNSET }
}

/**
 * Playback-thread hand-off between the audio renderer, the sink around it and the processor inside
 * it. The renderer names the stream it outputs; the sink says where each buffer sits in its track
 * and counts repositions (the renderer's flushes: a seek, a skip, a first start).
 */
internal class PcmStreams {
    var current: PcmStream? = null
    var bufferMediaUs: Long = 0L
    var repositions: Long = 0L

    /** [repositions] when the last buffer reached the sink; equal to it once PCM follows a reposition. */
    var repositionsAtData: Long = -1L
}

/**
 * The player's PCM gain stage, ahead of the equalizer: loudness normalisation per track, the smooth
 * transition at each end of a track, and the real overlap of one track's end with the next one's
 * opening.
 *
 * An overlap mixes [OverlapPlan.pcm] into the outgoing track's last [OverlapPlan.overlapUs] with
 * equal-power curves. When the player then reaches the incoming track by itself (a gapless join,
 * which the sink always drains and flushes the chain at), the frames already heard are dropped and
 * reported through [CrossfadeProcessorChain] so the player's position stays the track's own. A seam
 * that cannot overlap — no plan in time, a different sample rate or channel count, a queue edit —
 * falls back to [crossfadeFactor]'s fades, the behaviour from before overlaps existed.
 *
 * Playback thread only, except [CrossfadeSettings] and [PcmStreams] reads as documented there.
 */
internal class CrossfadeAudioProcessor(
    private val settings: CrossfadeSettings,
    private val streams: PcmStreams,
) : BaseAudioProcessor() {

    private var segment: Segment? = null
    private var repositionsAtData: Long = -1L
    private var pendingDecision: Decision? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.sampleRate <= 0
        ) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    /**
     * Called by [CrossfadeProcessorChain] just before the sink checkpoints its position and flushes
     * this processor: the duration already heard of the track about to begin, which the sink must
     * add to the playout time to report that track's real position.
     */
    fun prepareSegment(): Long {
        val decision = decide()
        pendingDecision = decision
        val skipped = when (decision) {
            is Decision.Join -> decision.mix?.nextIntroFrame?.toLong() ?: 0L
            // The sink can checkpoint twice at one join (a new format, then the discontinuity);
            // the second still precedes the frames the first one's skip will drop. In general: how
            // far the next frame out runs ahead of the next frame in.
            Decision.Continue -> segment?.let { it.frames - it.inputFrames } ?: 0L
            is Decision.Fresh -> 0L
        }
        return framesToUs(skipped)
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        val decision = pendingDecision ?: decide()
        pendingDecision = null
        when (decision) {
            Decision.Continue -> Unit
            is Decision.Fresh -> {
                segment = Segment(decision.stream, previousGain = null)
                settings.mixing = null
            }
            is Decision.Join -> {
                val previous = decision.previous
                val mix = decision.mix
                val next = Segment(decision.stream, previousGain = previous.gain)
                if (mix != null) {
                    // The overlap's clock carries on past the join from the track's own index —
                    // also for an outgoing track that ended before its header said. The track
                    // plays on from its decoded opening while the decoder works through its own
                    // copy of it, which is dropped: dropping alone would leave the sink nothing to
                    // play for as long as that takes, and the player would stop to rebuffer.
                    next.frames = mix.nextIntroFrame.toLong()
                    next.skip = mix.plan.frames.toLong()
                    next.bridge = Bridge(mix.plan, from = mix.nextIntroFrame).takeIf { it.length > 0 }
                    next.incomingFrames = mix.overlapFrames
                    next.incomingFrom = mix.plan.incomingStartFrame.toLong()
                } else if (!settings.edgesAt(decision.stream?.mediaIndex).fadeIn) {
                    // A segued album seam plays on: its loudness change ramps instead of stepping.
                    next.rampFrom = previous.gain
                }
                // [CrossfadeSettings.mixing] stays: until the join is heard, Next still lands on the
                // point of the incoming track already heard.
                segment = next
            }
        }
    }

    override fun onReset() {
        segment = null
        pendingDecision = null
        repositionsAtData = -1L
        settings.mixing = null
    }

    private fun decide(): Decision {
        val previous = segment
        val stream = streams.current
        if (previous == null || streams.repositions != repositionsAtData) return Decision.Fresh(stream)
        if (!previous.hadData || stream?.key == previous.stream?.key) return Decision.Continue
        val mix = previous.mix?.takeIf {
            !it.cancelled && it.nextIntroFrame > 0 && it.plan.incomingId == stream?.mediaId
        }
        return Decision.Join(stream, previous, mix)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val segment = segment ?: Segment(streams.current, previousGain = null).also { segment = it }
        if (!segment.hadData) {
            segment.hadData = true
            segment.startUs = streams.bufferMediaUs
            // The first track to play after the Next's seek spends the rise, whoever it is: only the
            // track and point the Next landed on take it, so a quicker second Next, another pick or
            // a new queue lets it lapse instead of a later play of that track starting on it.
            settings.pendingRise?.let { pending ->
                settings.pendingRise = null
                if (pending.mediaId == segment.stream?.mediaId &&
                    abs(segment.startUs - pending.positionUs) <= RISE_LANDING_TOLERANCE_US
                ) {
                    segment.riseUs = pending.riseUs
                    segment.riseFromUs = pending.fromUs
                }
            }
        }
        repositionsAtData = streams.repositions
        val channels = inputAudioFormat.channelCount
        val frameBytes = channels * 2
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val available = inputBuffer.remaining() / frameBytes
        val skipped = minOf(segment.skip, available.toLong()).toInt()
        if (skipped > 0) {
            inputBuffer.position(inputBuffer.position() + skipped * frameBytes)
            segment.skip -= skipped
        }
        segment.inputFrames += available
        val bridge = segment.bridge
        val bridged = bridge?.due(segment.skip) ?: 0
        val frames = available - skipped
        if (frames + bridged <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val output = replaceOutputBuffer((bridged + frames) * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bridge != null && bridged > 0) playBridge(segment, bridge, bridged, output, channels)
        if (frames > 0) process(segment, inputBuffer, output, frames, channels)
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun playBridge(segment: Segment, bridge: Bridge, frames: Int, output: ByteBuffer, channels: Int) {
        val frameBytes = channels * 2
        val pcm = bridge.plan.pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        pcm.position((bridge.from + bridge.emitted) * frameBytes)
        process(segment, pcm, output, frames, channels)
        bridge.emitted += frames
        if (bridge.emitted >= bridge.length) segment.bridge = null
    }

    private fun process(segment: Segment, input: ByteBuffer, output: ByteBuffer, frames: Int, channels: Int) {
        val rate = inputAudioFormat.sampleRate
        val fadeMs = settings.fadeMs
        val mediaId = segment.stream?.mediaId
        val durationUs = segment.stream?.durationUs?.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
        val durationMs = durationUs / 1000L
        val edges = settings.edgesAt(segment.stream?.mediaIndex)
        val targetGain = settings.gainOf(mediaId)
        // The slot an opening may be read from is published BEFORE the plan is read for this buffer:
        // the coordinator withdraws a plan and only then picks a free slot, so either it sees this
        // slot as taken, or this buffer sees the plan gone and starts no mix from it.
        val candidate = settings.plan
        settings.soundingSlot = segment.mix?.plan?.slot
            ?: segment.bridge?.plan?.slot
            ?: candidate?.takeIf { it.outgoingId == mediaId }?.slot
            ?: settings.soundingSlot
        val livePlan = settings.plan
        val envelopeStep = 1f / (rate * ENVELOPE_SLEW_MS / 1000f)
        val introStep = 1f / (rate * CROSSFADE_CANCEL_MS / 1000f)
        val rampFrames = NORMALIZATION_RAMP_MS * rate / 1000L
        val fallbackFadeUs = minOf(fadeMs * 1000L, durationUs / 2L)
        // An overlap meets the outgoing track's last sound: its end is settled where that window
        // begins, which a long trailing silence puts before the file's own fade window.
        val soundEndUs = livePlan?.takeIf { it.outgoingId == mediaId }?.let { anchorUs(it, durationUs) } ?: durationUs
        val decideAtUs = minOf(durationUs - fallbackFadeUs, soundEndUs - minOf(fadeMs * 1000L, soundEndUs / 2L))
        // A queue edit can end an album continuation, or begin one, under a fading track.
        if (segment.end == End.NONE && edges.fadeOut) segment.end = End.FADE
        if (segment.end == End.FADE && !edges.fadeOut) segment.end = End.NONE
        val mix = segment.mix
        if (mix != null && !mix.cancelled && (livePlan !== mix.plan || fadeMs <= 0L)) mix.cancelled = true
        var incomingGain = Float.NaN
        var mixing = false
        var readSlot = -1

        for (frame in 0 until frames) {
            val index = segment.frames
            val mediaUs = segment.startUs + index * 1_000_000L / rate

            // Loudness: the track's own gain, ramped when it changes under the music.
            if (segment.gainTarget != targetGain || segment.rampFrom != null) segment.beginGainRamp(targetGain)
            val gain = segment.gainAt(rampFrames, rate)

            // How this track ends is settled once, where its fallback fade would begin.
            if (segment.end == End.UNDECIDED && fadeMs > 0L && durationUs > 0L &&
                mediaUs >= decideAtUs
            ) {
                segment.decideEnd(edges, livePlan, durationUs, mediaUs, rate, index)
            }

            var envelope = 1f
            if (segment.incomingFrames > 0L) {
                val progress = (index - segment.incomingFrom).toFloat() / segment.incomingFrames
                if (progress < 1f) envelope = overlapInGain(progress)
            } else if (segment.riseUs > 0L) {
                if (mediaUs < segment.riseFromUs + segment.riseUs) {
                    envelope = overlapInGain((mediaUs - segment.riseFromUs).toFloat() / segment.riseUs)
                }
            } else if (fadeMs > 0L && edges.fadeIn) {
                envelope = crossfadeFactor(mediaUs / 1000L, durationMs, fadeMs, fadeIn = true, fadeOut = false)
            }
            val activeMix = segment.mix
            var introIndex = -1
            if (activeMix != null && index >= activeMix.startFrame) {
                val progress = (index - activeMix.startFrame).toFloat() / activeMix.overlapFrames
                envelope *= if (progress < 1f) overlapOutGain(progress) else 0f
                if (incomingGain.isNaN()) incomingGain = settings.gainOf(activeMix.plan.incomingId)
                val introTarget = if (activeMix.cancelled) 0f else overlapInGain(progress) * incomingGain
                // Rising never faster than a cancel falls: a mix begun late comes in over that
                // time from where its curve has got to, not as a step.
                val rising = introTarget > activeMix.introGain
                activeMix.introGain = slew(activeMix.introGain, introTarget, if (activeMix.cancelled || rising) introStep else 1f)
                val candidate = (index - activeMix.startFrame).toInt() + activeMix.plan.incomingStartFrame
                if (candidate < activeMix.plan.frames) {
                    if (activeMix.introGain > 0f) {
                        introIndex = candidate
                        readSlot = activeMix.plan.slot
                    }
                    if (!activeMix.cancelled) {
                        activeMix.nextIntroFrame = candidate + 1
                        activeMix.mixedFrames = index - activeMix.startFrame + 1
                        mixing = true
                    }
                }
            } else if (segment.end == End.FADE && fadeMs > 0L) {
                envelope *= crossfadeFactor(mediaUs / 1000L, durationMs, fadeMs, fadeIn = false, fadeOut = true)
            }
            segment.envelope = if (segment.envelopeFresh) envelope else slew(segment.envelope, envelope, envelopeStep)
            segment.envelopeFresh = false
            val own = gain * segment.envelope

            for (channel in 0 until channels) {
                var sample = input.short * own
                if (introIndex >= 0) {
                    val pcm = activeMix!!.plan.pcm
                    sample += pcm.getShort((introIndex * channels + channel) * 2) * activeMix.introGain
                    // The limiter's knee follows how much both tracks sound: full scale at the
                    // overlap's ends, where one track plays alone and must sound as without it,
                    // lower once both are audible. From the curves only: a quieter-normalised next
                    // track still sums near full scale with a loud outgoing one.
                    val incomingCurve = activeMix.introGain / incomingGain.coerceAtLeast(MIN_CURVE_GAIN)
                    val knee = overlapKnee(4f * segment.envelope * incomingCurve)
                    sample = softClip(sample / PCM16_SCALE, knee) * PCM16_SCALE
                }
                output.putShort(sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            segment.frames++
        }
        // Still taken between buffers while the mix may read on: playing, or fading out after a
        // withdrawal.
        settings.soundingSlot = segment.mix?.takeIf { !it.cancelled || it.introGain > 0f }?.plan?.slot
            ?: segment.bridge?.plan?.slot
            ?: readSlot
        val reported = segment.mix ?: return
        if (mixing) {
            settings.mixing = reported.plan
        } else if (reported.cancelled && settings.mixing === reported.plan) {
            settings.mixing = null
        }
    }

    /** Where [plan]'s overlap ends in its outgoing track of [durationUs]: its last sound. */
    private fun anchorUs(plan: OverlapPlan, durationUs: Long): Long =
        plan.outgoingEndUs.takeIf { it != C.TIME_UNSET && it in 1L..durationUs } ?: durationUs

    private fun framesToUs(frames: Long): Long =
        inputAudioFormat.sampleRate.takeIf { it > 0 }?.let { frames * 1_000_000L / it } ?: 0L

    private enum class End { UNDECIDED, NONE, FADE, MIX }

    /** One track's run of PCM between two flushes that were not a mid-track reconfiguration. */
    private inner class Segment(val stream: PcmStream?, previousGain: Float?) {
        var hadData = false
        var startUs = 0L

        /** The track's index of the next frame out: [startUs] + frames is the media time. */
        var frames = 0L

        /** Frames of input consumed, dropped ones included; [frames] is ahead of it after a join. */
        var inputFrames = 0L

        /** Input frames still to drop: the decoder's copy of the opening an overlap decoded. */
        var skip = 0L

        /** The decoded opening the track plays from while [skip] runs down; null once played. */
        var bridge: Bridge? = null

        /** The length of an overlap that began in the previous segment; its clock is [frames]. */
        var incomingFrames = 0L

        /** The frame that overlap brought this track in at: its first sound. */
        var incomingFrom = 0L

        /** Set when Next left an overlap for this track: its opening rises over this long. */
        var riseUs = 0L

        /** Where in the track that rise began: its first sound. */
        var riseFromUs = 0L

        var end = End.UNDECIDED
        var mix: Mix? = null

        var envelope = 1f
        var envelopeFresh = true

        var gain = previousGain ?: 1f
        var gainTarget = Float.NaN
        var rampFrom: Float? = null
        private var rampStart = 1f
        private var rampElapsed = -1L

        fun beginGainRamp(target: Float) {
            val from = rampFrom
            rampFrom = null
            if (gainTarget.isNaN() && from == null) {
                // A new track's loudness is installed before its first sample sounds.
                gain = target
                gainTarget = target
                return
            }
            rampStart = from ?: gain
            gainTarget = target
            rampElapsed = 0L
        }

        fun gainAt(rampFrames: Long, rate: Int): Float {
            if (rampElapsed < 0L) return gain
            gain = normalizationRampVolume(rampStart, gainTarget, rampElapsed * 1000L / rate)
            rampElapsed++
            if (rampElapsed >= rampFrames) {
                gain = gainTarget
                rampElapsed = -1L
            }
            return gain
        }

        /**
         * Settles, at the start of the fallback fade window, how this track ends. An overlap is
         * only begun on time — or straight after a seek into the window — never halfway through a
         * window it missed, where the next track would cut in loud.
         */
        fun decideEnd(edges: FadeEdges, plan: OverlapPlan?, durationUs: Long, mediaUs: Long, rate: Int, index: Long) {
            if (!edges.fadeOut) {
                end = End.NONE
                return
            }
            // Never into the same track: its next play would start seconds in (see CrossfadeCoordinator).
            val usable = plan != null && plan.outgoingId == stream?.mediaId && plan.incomingId != plan.outgoingId &&
                plan.overlapUs > 0L &&
                plan.sampleRate == rate && plan.channelCount == inputAudioFormat.channelCount &&
                plan.frames > 0
            if (!usable) {
                // Its opening is still being decoded (a seek close to the end started that late):
                // play on at full level for a moment rather than settle on the plain fades.
                val windowUs = durationUs - minOf(settings.fadeMs * 1000L, durationUs / 2L)
                val waitUs = minOf(MAX_LATE_MIX_US, settings.fadeMs * 1000L / 2L)
                if (settings.preparingFor == stream?.mediaId && mediaUs - windowUs <= waitUs) return
                end = End.FADE
                settings.seamLog("seam ${stream?.mediaId}: fade at ${mediaUs / 1000} ms (${if (plan == null) "no opening" else "opening unusable"})")
                return
            }
            val endUs = anchorUs(plan, durationUs)
            val mixStartUs = endUs - plan.overlapUs
            val windowUs = endUs - minOf(settings.fadeMs * 1000L, endUs / 2L)
            // A start before the window — the slider lowered after the opening was decoded for a
            // longer overlap — would cut the next track in loud and partway into its opening.
            val missedStart = mixStartUs < windowUs - LATE_START_US
            // An opening that arrived late (a seek close to the end started its decode late) still
            // mixes, on its own clock: the next track comes in where the overlap has got to, rising
            // over a short ramp — up to a bound, past which it would cut in well into its opening.
            val lateUs = mediaUs - mixStartUs
            val maxLateUs = maxOf(LATE_START_US, minOf(MAX_LATE_MIX_US, plan.overlapUs / 2L))
            val onTime = (!missedStart && lateUs <= maxLateUs) || index == 0L
            if (!onTime) {
                end = End.FADE
                settings.seamLog("seam ${stream?.mediaId}: fade at ${mediaUs / 1000} ms (opening ${lateUs / 1000} ms late)")
                return
            }
            val startFrame = index + ceilDiv((mixStartUs - mediaUs) * rate, 1_000_000L)
            end = End.MIX
            mix = Mix(plan, startFrame, overlapFrames = (plan.overlapUs * rate / 1_000_000L).coerceAtLeast(1L))
            settings.seamLog(
                "seam ${stream?.mediaId} -> ${plan.incomingId}: overlap ${plan.overlapUs / 1000} ms ending at " +
                    "${endUs / 1000} of ${durationUs / 1000} ms, from frame ${plan.incomingStartFrame} at $rate Hz" +
                    if (lateUs > LATE_START_US) ", ${lateUs / 1000} ms late" else "",
            )
            // Settled now, sounding a little later: from here a slider drag must not re-plan the
            // seam (see CrossfadeCoordinator.plannedFadeMs); Next still waits for the start.
            settings.mixing = plan
        }
    }

    private class Mix(val plan: OverlapPlan, val startFrame: Long, val overlapFrames: Long) {
        var introGain = 0f
        var nextIntroFrame = 0
        var mixedFrames = 0L
        var cancelled = false
    }

    /**
     * The decoded opening from [from] — the first frame the overlap did not play — to its end. It
     * goes out alongside the frames dropped, in proportion, so it lasts until the decoder reaches
     * the frame after it: [skip] at the start is all of [plan]'s frames.
     */
    private class Bridge(val plan: OverlapPlan, val from: Int) {
        val length = plan.frames - from
        private val skip = plan.frames.toLong()
        var emitted = 0

        /** Frames to put out now that [skipLeft] input frames remain to drop. */
        fun due(skipLeft: Long): Int {
            val target = if (skipLeft <= 0L) length.toLong() else (skip - skipLeft) * length / skip
            return (target - emitted).toInt().coerceAtLeast(0)
        }
    }

    private sealed interface Decision {
        /** A reconfiguration inside one track: nothing about the track changes. */
        data object Continue : Decision

        /** After a reposition: a new start, nothing carried over. */
        class Fresh(val stream: PcmStream?) : Decision

        /** The player moved on to [stream] by itself; [mix] is the overlap it continues. */
        class Join(val stream: PcmStream?, val previous: Segment, val mix: Mix?) : Decision
    }

    private companion object {
        const val PCM16_SCALE = 32768f

        /** Below this a track's loudness gain is silence; it only guards the division. */
        const val MIN_CURVE_GAIN = 1e-3f

        /** A Next's landing and the first PCM after its seek: within a few decoder frames. */
        const val RISE_LANDING_TOLERANCE_US = 200_000L

        /** A change of envelope source glides; no curve of its own is ever this steep. */
        const val ENVELOPE_SLEW_MS = 400L

        /** How late an overlap may still begin as if on time — about one audio buffer. */
        const val LATE_START_US = 100_000L

        /** How late it may still begin on its own clock, at most half of it ([Segment.decideEnd]). */
        const val MAX_LATE_MIX_US = 3_000_000L

        fun slew(current: Float, target: Float, step: Float): Float = when {
            target > current + step -> current + step
            target < current - step -> current - step
            else -> target
        }

        fun ceilDiv(value: Long, divisor: Long): Long = -Math.floorDiv(-value, divisor)
    }
}
