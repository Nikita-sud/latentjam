/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** The slider's contract: transitions can soften up to this many seconds each side. */
public const val MAX_CROSSFADE_SECONDS: Int = 12

/** Fine enough to keep short gain ramps smooth instead of stepping audibly. */
internal const val GAIN_RAMP_TICK_MS: Long = 20L

/** How long a newly learned normalization value takes to reach its target. */
internal const val NORMALIZATION_RAMP_MS: Long = 400L

/** A persisted or programmatic value degrades to the nearest legal one, never to a crash. */
public fun sanitizeCrossfadeSeconds(value: Int): Int = value.coerceIn(0, MAX_CROSSFADE_SECONDS)

/**
 * Amplitude factor for smooth transitions: rises over the first [fadeMs] of a track and falls
 * over its last [fadeMs].
 *
 * The fallback for a seam that cannot overlap (see [overlapLengthMs]): the join stays where it
 * is and only stops being abrupt. Tracks shorter than two fades scale both slopes to halves so a
 * rise and a fall always fit. An unknown duration fades in only — never a surprise mute in the
 * middle of a stream whose end the player cannot see.
 */
public fun crossfadeFactor(
    positionMs: Long,
    durationMs: Long,
    fadeMs: Long,
    fadeIn: Boolean = true,
    fadeOut: Boolean = true,
): Float {
    if (fadeMs <= 0) return 1f
    val position = positionMs.coerceAtLeast(0)
    if (durationMs <= 0) {
        if (!fadeIn) return 1f
        return (position.coerceAtMost(fadeMs).toFloat() / fadeMs).coerceIn(0f, 1f)
    }
    val effectiveFade = minOf(fadeMs, durationMs / 2)
    if (effectiveFade <= 0) return 1f
    val rise = if (fadeIn) position.coerceAtMost(effectiveFade).toFloat() / effectiveFade else 1f
    val remaining = (durationMs - position).coerceAtLeast(0)
    val fall = if (fadeOut) remaining.coerceAtMost(effectiveFade).toFloat() / effectiveFade else 1f
    return minOf(rise, fall).coerceIn(0f, 1f)
}

/**
 * True when [next] is the track that follows [previous] on the same album: the seam the artist
 * wrote (a live set, a segued concept album, a symphony's movements) is not a transition to soften.
 * Same album title and album artist (the display artist when untagged), and the next track number
 * on the same disc or track 1 of the next disc. Missing numbering never counts as a continuation.
 */
internal fun continuesAlbum(previous: TrackDescriptor?, next: TrackDescriptor?): Boolean {
    if (previous == null || next == null || previous.id == next.id) return false
    val album = previous.album?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (!album.equals(next.album?.trim(), ignoreCase = true)) return false
    val previousArtist = (previous.albumArtist ?: previous.artist)?.trim()
    val nextArtist = (next.albumArtist ?: next.artist)?.trim()
    if (previousArtist.isNullOrEmpty() || !previousArtist.equals(nextArtist, ignoreCase = true)) return false
    val track = previous.trackNumber ?: return false
    val nextTrack = next.trackNumber ?: return false
    val disc = previous.discNumber ?: 1
    val nextDisc = next.discNumber ?: 1
    return (nextDisc == disc && nextTrack == track + 1) || (nextDisc == disc + 1 && nextTrack == 1)
}

/** Whether a track's start and its end may fade: an album seam played in order is left alone. */
internal data class FadeEdges(val fadeIn: Boolean, val fadeOut: Boolean) {
    companion object {
        val BOTH = FadeEdges(fadeIn = true, fadeOut = true)
    }
}

/**
 * [FadeEdges] of the queue's rows that are not [FadeEdges.BOTH], keyed by each row's player index
 * ([mediaIndices], parallel to [rows], which are in the order they play): no fade where
 * [continuesAlbum] joins a row to its neighbour. Per row, not per track: album row 3 queued again
 * with Play next keeps the album's seams. Only those rows are kept, so a 50 000-row queue costs a
 * map of its album seams. With [repeatsItself] each row's neighbour is itself.
 */
internal fun queueFadeEdges(
    rows: List<TrackDescriptor?>,
    mediaIndices: List<Int>,
    repeatsItself: Boolean,
): Map<Int, FadeEdges> {
    val edges = HashMap<Int, FadeEdges>()
    rows.forEachIndexed { index, current ->
        current ?: return@forEachIndexed
        val mediaIndex = mediaIndices.getOrNull(index) ?: return@forEachIndexed
        val previous = if (repeatsItself) current else rows.getOrNull(index - 1)
        val next = if (repeatsItself) current else rows.getOrNull(index + 1)
        val rowEdges = FadeEdges(!continuesAlbum(previous, current), !continuesAlbum(current, next))
        if (rowEdges != FadeEdges.BOTH) edges[mediaIndex] = rowEdges
    }
    return edges
}

/** dB-linear interpolation avoids a newly measured track dropping several dB in one audio buffer. */
internal fun normalizationRampVolume(
    from: Float,
    to: Float,
    elapsedMs: Long,
    durationMs: Long = NORMALIZATION_RAMP_MS,
): Float {
    val start = from.coerceIn(0f, 1f)
    val target = to.coerceIn(0f, 1f)
    if (durationMs <= 0L || elapsedMs >= durationMs) return target
    if (elapsedMs <= 0L || start == target) return start
    val progress = elapsedMs.toFloat() / durationMs
    return if (start > 0f && target > 0f) {
        (start * (target / start).pow(progress)).coerceIn(0f, 1f)
    } else {
        (start + (target - start) * progress).coerceIn(0f, 1f)
    }
}

/**
 * How long two tracks actually overlap at a seam: the setting, but never more than a third of
 * either track. A track can be overlapped at both ends, so a third each side leaves it at least a
 * third heard on its own. Zero when either length is unknown — an overlap must know where the
 * outgoing track ends.
 */
internal fun overlapLengthMs(fadeMs: Long, outgoingMs: Long, incomingMs: Long): Long {
    if (fadeMs <= 0L || outgoingMs <= 0L || incomingMs <= 0L) return 0L
    return minOf(fadeMs, outgoingMs / 3L, incomingMs / 3L).coerceAtLeast(0L)
}

/**
 * Outgoing amplitude [progress] (0..1) through an overlap. With [overlapInGain] the two powers
 * sum to one, so two unrelated songs keep a steady loudness instead of dipping in the middle.
 */
internal fun overlapOutGain(progress: Float): Float =
    cos(progress.coerceIn(0f, 1f) * HALF_PI).toFloat()

/** Incoming amplitude [progress] (0..1) through an overlap; see [overlapOutGain]. */
internal fun overlapInGain(progress: Float): Float =
    sin(progress.coerceIn(0f, 1f) * HALF_PI).toFloat()

/**
 * True once the next track's opening should be decoded so it is ready before the overlap
 * begins: [CROSSFADE_PREROLL_LEAD_MS] ahead of it, which leaves time for a slow storage read.
 */
internal fun prerollsNow(positionMs: Long, durationMs: Long, overlapMs: Long): Boolean =
    overlapMs > 0L && durationMs > 0L &&
        durationMs - positionMs.coerceAtLeast(0L) <= overlapMs + CROSSFADE_PREROLL_LEAD_MS

/**
 * Keeps a sum of two full-scale songs out of hard clipping: unchanged below the knee, and a smooth
 * approach to full scale above it. Only an overlap ever reaches the knee — one normalised track
 * never exceeds full scale on its own.
 */
internal fun softClip(sample: Float, knee: Float = SOFT_CLIP_KNEE): Float {
    val magnitude = abs(sample)
    if (magnitude <= knee || knee >= 1f) return sample
    val over = (magnitude - knee) / (1f - knee)
    val limited = knee + (1f - knee) * tanh(over)
    return if (sample < 0f) -limited else limited
}

/**
 * The knee for an overlap's sum, from how much both tracks sound ([presence], 0..1): full scale
 * — no limiting below it — where one track plays alone, so nothing changes at either end of the
 * overlap, sliding down to [SOFT_CLIP_KNEE] as both become audible.
 */
internal fun overlapKnee(presence: Float): Float =
    1f - (1f - SOFT_CLIP_KNEE) * presence.coerceIn(0f, 1f)

/**
 * The current track's envelope on a player that overlaps tracks deck by deck (iOS): its start, then
 * its end, multiplied.
 *
 * The start is the rest of the overlap that brought the track in ([riseMs], a sine rise on its own
 * position), else nothing at all for a track that came in through an overlap ([joinedByOverlap]:
 * its rise is over and it must not fade in again), else the plain fade-in. The end follows an
 * overlap's out-curve whenever one is cued, sounding or let go ([curveMs]) — 1 until that overlap
 * begins — and the plain fade-out only when there is none. An overlap is often shorter than the
 * slider (a third of a short neighbour); a plain fade before it would sink the track and the curve
 * would then lift it back to full where the overlap starts. Met at sound edges ([seamAnchors]), the
 * rise begins at the track's first sound ([riseFromMs]) and the out-curve ends at its last
 * ([curveEndMs]), silent after it.
 */
internal fun deckEnvelope(
    positionMs: Long,
    durationMs: Long,
    fadeMs: Long,
    edges: FadeEdges,
    riseMs: Long,
    joinedByOverlap: Boolean,
    curveMs: Long,
    riseFromMs: Long = 0L,
    curveEndMs: Long = durationMs,
): Float {
    val start = when {
        riseMs > 0L && positionMs < riseFromMs + riseMs -> overlapInGain((positionMs - riseFromMs).toFloat() / riseMs)
        joinedByOverlap -> 1f
        fadeMs > 0L && edges.fadeIn -> crossfadeFactor(positionMs, durationMs, fadeMs, fadeIn = true, fadeOut = false)
        else -> 1f
    }
    val end = when {
        curveMs > 0L && durationMs > 0L -> {
            val progress = (positionMs - (curveEndMs - curveMs)).toFloat() / curveMs
            if (progress < 1f) overlapOutGain(progress) else 0f
        }
        fadeMs > 0L && edges.fadeOut -> crossfadeFactor(positionMs, durationMs, fadeMs, fadeIn = false, fadeOut = true)
        else -> 1f
    }
    return start * end
}

/**
 * Where the next track starts when an overlap begins at [positionMs] of an outgoing track of
 * [durationMs]: 0 — its cued opening — when the overlap starts on time, which on a player that
 * looks every few milliseconds means up to [LATE_OVERLAP_START_MS] late; otherwise as far into
 * the next track as the overlap has got, so both curves run on one clock (a seek into the window,
 * the slider raised inside it).
 */
internal fun lateOverlapStartMs(positionMs: Long, durationMs: Long, overlapMs: Long): Long {
    val late = positionMs - (durationMs - overlapMs)
    return if (late > LATE_OVERLAP_START_MS) late else 0L
}

/** How late an overlap may begin and still play its next track from the cued opening. */
internal const val LATE_OVERLAP_START_MS: Long = 100L

/** How far ahead of an overlap the next track's opening is decoded. */
internal const val CROSSFADE_PREROLL_LEAD_MS: Long = 10_000L

/**
 * Opening decoded beyond the overlap itself. A track can end later than its header's duration
 * says; the overlap then runs on into this margin instead of running out of the next track.
 */
internal const val CROSSFADE_INTRO_MARGIN_MS: Long = 2_000L

/**
 * How much of the next track's opening to decode ahead for a seam that may sound over [heardMs]
 * of it: its leading silence, the overlap, the outgoing track's trailing silence.
 *
 * At the join the player plays on from the rest of this opening while its decoder works through
 * its own copy of all of it ([CrossfadeAudioProcessor]), so the rest must last as long as that
 * takes. A decoder of small packets (Opus: 20 ms each) gets through about ten times real time in
 * the player's render loop, which a fixed two seconds after twelve heard fell short of — the sound
 * stuttered. Half of what is heard, at least [CROSSFADE_INTRO_MARGIN_MS], asks three times.
 */
internal fun introCaptureMs(heardMs: Long): Long =
    heardMs + maxOf(CROSSFADE_INTRO_MARGIN_MS, heardMs / 2L)

/** How quickly an overlap that lost its next track (a queue edit) lets that track go. */
internal const val CROSSFADE_CANCEL_MS: Long = 300L

private const val HALF_PI: Double = PI / 2.0
private const val SOFT_CLIP_KNEE: Float = 0.8f

