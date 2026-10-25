/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.pow

/**
 * Where a track's sound really begins and ends, in ms of its decoded audio: the first and the last
 * [SOUND_WINDOW_MS] window above [SOUND_THRESHOLD_DBFS]. Everything between is the track, quiet
 * passages and a hidden bonus after a long gap included — only the silence before the first sound
 * and after the last one lies outside.
 */
public data class SoundSpan(public val startMs: Long, public val endMs: Long) {
    public fun isValid(): Boolean = startMs >= 0L && endMs > startMs
}

/**
 * Finds a track's [SoundSpan] from the PCM a full-track scan already decodes (the loudness meter),
 * so no track is decoded twice for it. Feed interleaved float samples in order; [finish] gives null
 * for a track with no sound at all.
 */
internal class SoundSpanTracker(sampleRate: Int, private val channels: Int) {
    private val windowFrames = (sampleRate.toLong() * SOUND_WINDOW_MS / 1000L).coerceAtLeast(1L)
    private val threshold = 10.0.pow(SOUND_THRESHOLD_DBFS / 10.0) // mean square, full scale = 1
    private var framesInWindow = 0L
    private var sumInWindow = 0.0
    private var window = 0L
    private var first = -1L
    private var last = -1L

    fun add(samples: FloatArray, frames: Int) {
        var index = 0
        repeat(frames) {
            repeat(channels) {
                val sample = samples[index++].toDouble()
                sumInWindow += sample * sample
            }
            if (++framesInWindow == windowFrames) closeWindow()
        }
    }

    fun finish(): SoundSpan? {
        if (framesInWindow > 0L) closeWindow()
        if (first < 0L) return null
        return SoundSpan(startMs = first * SOUND_WINDOW_MS, endMs = (last + 1L) * SOUND_WINDOW_MS)
    }

    private fun closeWindow() {
        if (sumInWindow / (framesInWindow * channels) > threshold) {
            if (first < 0L) first = window
            last = window
        }
        window++
        framesInWindow = 0L
        sumInWindow = 0.0
    }
}

/**
 * Where an overlap meets a seam's two tracks: it ends at [outgoingEndMs] — the outgoing track's
 * last sound, so the next track never rises over its trailing silence — and the incoming track
 * starts at [incomingStartMs], its first sound. With nothing measured they are the outgoing file's
 * end and 0: the seam as it was before sound edges existed.
 */
internal data class SeamAnchors(val outgoingEndMs: Long, val incomingStartMs: Long) {
    /** The outgoing track's trailing silence the overlap leaves out. */
    fun trailMs(outgoingDurationMs: Long): Long = (outgoingDurationMs - outgoingEndMs).coerceAtLeast(0L)
}

/**
 * The [SeamAnchors] for an outgoing track of [outgoingDurationMs] with its measured [outgoing] span
 * and an incoming track's [incoming] span. Only a real edge of silence moves an anchor: one shorter
 * than [MIN_SOUND_TRIM_MS] is left, a guard of [SOUND_EDGE_GUARD_MS] stays on the sound's side, and
 * an edge longer than [MAX_SOUND_TRIM_MS] or a span that does not fit the track is not trusted.
 */
internal fun seamAnchors(outgoingDurationMs: Long, outgoing: SoundSpan?, incoming: SoundSpan?): SeamAnchors {
    val end = outgoing?.takeIf { it.isValid() && outgoingDurationMs > 0L && it.endMs <= outgoingDurationMs + SPAN_SLACK_MS }
        ?.let { span ->
            val trail = outgoingDurationMs - (span.endMs + SOUND_EDGE_GUARD_MS)
            if (trail in MIN_SOUND_TRIM_MS..MAX_SOUND_TRIM_MS) outgoingDurationMs - trail else null
        } ?: outgoingDurationMs
    val start = incoming?.takeIf { it.isValid() }
        ?.let { span ->
            val lead = span.startMs - SOUND_EDGE_GUARD_MS
            if (lead in MIN_SOUND_TRIM_MS..MAX_SOUND_TRIM_MS) lead else null
        } ?: 0L
    return SeamAnchors(outgoingEndMs = end, incomingStartMs = start)
}

/** Below this level a window of a track counts as silence. */
internal const val SOUND_THRESHOLD_DBFS: Double = -50.0

/** The windows a track's sound is measured in. */
internal const val SOUND_WINDOW_MS: Long = 50L

/** Kept on the sound's side of a measured edge: a scan's decoder may run a few ms off playback's. */
internal const val SOUND_EDGE_GUARD_MS: Long = 100L

/** Shorter silence at an edge is part of how the track was mastered and is left alone. */
internal const val MIN_SOUND_TRIM_MS: Long = 300L

/** Longer silence at an edge is not taken out: the measurement or the file is not what it seems. */
internal const val MAX_SOUND_TRIM_MS: Long = 30_000L

/** A measured end may run this far past the file's declared duration (container rounding). */
private const val SPAN_SLACK_MS: Long = 2_000L
