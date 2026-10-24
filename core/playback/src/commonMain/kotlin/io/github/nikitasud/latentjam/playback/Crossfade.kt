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
internal fun softClip(sample: Float): Float {
    val magnitude = abs(sample)
    if (magnitude <= SOFT_CLIP_KNEE) return sample
    val over = (magnitude - SOFT_CLIP_KNEE) / (1f - SOFT_CLIP_KNEE)
    val limited = SOFT_CLIP_KNEE + (1f - SOFT_CLIP_KNEE) * tanh(over)
    return if (sample < 0f) -limited else limited
}

/** How far ahead of an overlap the next track's opening is decoded. */
internal const val CROSSFADE_PREROLL_LEAD_MS: Long = 10_000L

/**
 * Opening decoded beyond the overlap itself. A track can end later than its header's duration
 * says; the overlap then runs on into this margin instead of running out of the next track.
 */
internal const val CROSSFADE_INTRO_MARGIN_MS: Long = 2_000L

/** How quickly an overlap that lost its next track (a queue edit) lets that track go. */
internal const val CROSSFADE_CANCEL_MS: Long = 300L

private const val HALF_PI: Double = PI / 2.0
private const val SOFT_CLIP_KNEE: Float = 0.8f

