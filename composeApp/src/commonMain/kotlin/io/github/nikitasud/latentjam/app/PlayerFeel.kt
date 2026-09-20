/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NowPlaying
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The arithmetic behind the player's gestures.
 *
 * Every threshold the cover, the seek bar and the transport react to lives here as a pure function,
 * so the composables hold no numbers and the decisions are tested on the JVM instead of by hand.
 */
internal enum class ArtworkDragAxis { HORIZONTAL, VERTICAL, CANCELLED }

/** Undecided inside [slop]; an upward swipe cancels the tap/hold instead of opening actions. */
internal fun artworkDragAxis(dx: Float, dy: Float, slop: Float): ArtworkDragAxis? {
    if (abs(dx) < slop && abs(dy) < slop) return null
    return when {
        abs(dy) > abs(dx) -> if (dy > 0f) ArtworkDragAxis.VERTICAL else ArtworkDragAxis.CANCELLED
        else -> ArtworkDragAxis.HORIZONTAL
    }
}

/** The cover moves almost with the finger; a direction with no neighbour resists like a rubber band. */
internal fun swipeShown(dx: Float, blocked: Boolean): Float =
    if (blocked) dx * SWIPE_BLOCKED_FOLLOW else dx * SWIPE_FOLLOW

/** A skip commits past 30 % of the cover width, never into a direction with no neighbour. */
internal fun swipeCommits(dx: Float, width: Float, blocked: Boolean): Boolean =
    !blocked && width > 0f && abs(dx) > width * SWIPE_COMMIT_FRACTION

/** Pulling the player down moves it three quarters of the way, so the release still feels light. */
internal fun collapseShown(dy: Float): Float = dy.coerceAtLeast(0f) * COLLAPSE_FOLLOW

internal fun collapseCommits(dy: Float, threshold: Float): Boolean = dy > threshold

/**
 * Sliding the finger below the bar slows scrubbing: half speed past [halfAt], a quarter past
 * [quarterAt]. Above the bar the finger is still on the slider, so nothing slows down.
 */
internal fun scrubFineFactor(dyBelowBar: Float, halfAt: Float, quarterAt: Float): Int = when {
    dyBelowBar > quarterAt -> 4
    dyBelowBar > halfAt -> 2
    else -> 1
}

internal fun scrubDeltaMs(dxPx: Float, trackWidthPx: Float, durationMs: Long, fine: Int): Long {
    if (trackWidthPx <= 0f || durationMs <= 0L) return 0L
    return (dxPx / trackWidthPx * durationMs / fine.coerceAtLeast(1)).roundToLong()
}

/** Holding a skip button scans at 4×, doubling every step until 32×. */
internal fun skipHoldMultiplier(heldMs: Long): Int {
    val steps = (heldMs.coerceAtLeast(0L) / SKIP_HOLD_STEP_MS)
        .coerceAtMost(SKIP_HOLD_MAX_STEPS.toLong()).toInt()
    return SKIP_HOLD_BASE shl steps
}

/** A held scan belongs to one queue entry and stops issuing seeks as soon as it reaches an edge. */
internal suspend fun scanPlayerTrack(
    forward: Boolean,
    durationMs: Long,
    state: () -> NowPlaying,
    seek: suspend (Long) -> Unit,
    onEdge: () -> Unit,
) {
    val initial = state()
    val trackId = initial.track?.id ?: return
    val duration = durationMs.coerceAtLeast(0L)
    if (duration == 0L) return
    var position = initial.positionMs.coerceIn(0L, duration)
    var heldMs = 0L
    val direction = if (forward) 1 else -1
    while (currentCoroutineContext().isActive) {
        val current = state()
        if (current.track?.id != trackId || current.queueIndex != initial.queueIndex) return
        val step = SCAN_TICK_MS * skipHoldMultiplier(heldMs) * direction
        val target = (position + step).coerceIn(0L, duration)
        if (target != position) seek(target)
        position = target
        if (position == 0L || position == duration) {
            onEdge()
            return
        }
        delay(SCAN_TICK_MS)
        heldMs += SCAN_TICK_MS
    }
}

/** Average bitrate in kbps from file size and duration; the tag itself does not carry one. */
internal fun estimatedBitrateKbps(sizeBytes: Long?, durationMs: Long?): Int? {
    if (sizeBytes == null || durationMs == null || sizeBytes <= 0L || durationMs <= 0L) return null
    return (sizeBytes * 8.0 / durationMs).roundToInt()
}

/** `FLAC · 1 010 kbps · 34.2 MB`, or as much of that as the facts allow; null without an extension. */
internal fun fileFormatLabel(fileName: String?, sizeBytes: Long?, durationMs: Long?): String? {
    val extension = fileName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() && it.length <= MAX_EXTENSION_LENGTH }
        ?: return null
    val parts = mutableListOf(extension.uppercase())
    estimatedBitrateKbps(sizeBytes, durationMs)?.let { parts += "${groupThousands(it)} kbps" }
    sizeBytes?.takeIf { it > 0L }?.let { bytes ->
        val tenthsOfMegabyte = (bytes / 100_000.0).roundToLong()
        parts += "${tenthsOfMegabyte / 10}.${tenthsOfMegabyte % 10} MB"
    }
    return parts.joinToString(" · ")
}

private fun groupThousands(value: Int): String {
    val digits = value.toString()
    val out = StringBuilder()
    digits.forEachIndexed { index, char ->
        if (index > 0 && (digits.length - index) % 3 == 0) out.append(' ')
        out.append(char)
    }
    return out.toString()
}

private const val SWIPE_FOLLOW = 0.92f
private const val SWIPE_BLOCKED_FOLLOW = 0.3f
private const val SWIPE_COMMIT_FRACTION = 0.3f
private const val COLLAPSE_FOLLOW = 0.75f
private const val SKIP_HOLD_STEP_MS = 1_500L
private const val SKIP_HOLD_BASE = 4
private const val SKIP_HOLD_MAX_STEPS = 3
private const val MAX_EXTENSION_LENGTH = 5
private const val SCAN_TICK_MS = 250L
