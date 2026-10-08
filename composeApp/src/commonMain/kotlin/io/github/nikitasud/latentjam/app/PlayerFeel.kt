/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.geometry.Rect
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

/** Fade in only the revealed side, reaching full opacity before a swipe can commit. */
internal fun artworkNeighbourReveal(travel: Float, width: Float, forward: Boolean): Float {
    if (width <= 0f) return 0f
    val towardsNeighbour = if (forward) -travel else travel
    val progress = (towardsNeighbour / (width * 0.24f)).coerceIn(0f, 1f)
    return progress * progress * (3f - 2f * progress)
}

/** Past this pull a release would close the player; the cover announces it with one tick. */
internal fun collapseCommits(dy: Float, threshold: Float): Boolean = dy > threshold

/**
 * Whether the library under the player needs drawing. The full player is opaque and fills the
 * window, so only at [progress] 1 (fully expanded, which no drag or settle can leave untouched) is
 * the library hidden, and drawing it would only add overdraw to every frame of the player's own
 * motion. Any pull at all uncovers part of it again.
 */
internal fun libraryDrawnUnderPlayer(progress: Float): Boolean = progress < 1f

/**
 * True when the sheet's surface leaves no part of the window uncovered, so the library under it
 * need not be drawn at all: it reaches every edge (within half a pixel) and its corners are square.
 */
internal fun sheetCoversWindow(surface: Rect, radius: Float, width: Float, height: Float): Boolean =
    surface.left <= 0.5f && surface.top <= 0.5f &&
        surface.right >= width - 0.5f && surface.bottom >= height - 0.5f && radius < 0.5f

/**
 * The parts of the window the sheet's rounded surface leaves uncovered, as at most four disjoint
 * rectangles: a band across the top down to below the top corners, one across the bottom up to
 * above the bottom corners, and a strip on each side between them. The library is drawn once per
 * piece with a plain rectangle clip, which lets the renderer skip everything outside it, and the
 * pieces never overlap, so the scrim drawn in each is never doubled. Each piece reaches [inset]
 * under the surface's edge so its antialiased rim never lets the floor show through.
 */
internal fun sheetUncoveredPieces(
    surface: Rect,
    radius: Float,
    inset: Float,
    width: Float,
    height: Float,
): List<Rect> {
    val r = radius.coerceAtLeast(0f)
    val topEdge = if (surface.top <= 0.5f && r < 0.5f) 0f else (surface.top + r + inset).coerceIn(0f, height)
    val bottomEdge = if (surface.bottom >= height - 0.5f && r < 0.5f) {
        height
    } else {
        (surface.bottom - r - inset).coerceIn(topEdge, height)
    }
    val pieces = ArrayList<Rect>(4)
    if (topEdge > 0f) pieces += Rect(0f, 0f, width, topEdge)
    if (bottomEdge < height) pieces += Rect(0f, bottomEdge, width, height)
    if (bottomEdge > topEdge) {
        if (surface.left > 0.5f) pieces += Rect(0f, topEdge, (surface.left + inset).coerceAtMost(width), bottomEdge)
        if (surface.right < width - 0.5f) pieces += Rect((surface.right - inset).coerceAtLeast(0f), topEdge, width, bottomEdge)
    }
    return pieces
}
/**
 * Where a released drag of the player settles. A fling decides by its direction alone; a slow
 * release stays where it started unless the finger carried it past [commitFraction] of the way,
 * the same distance in both directions. [velocity] is in expansions per second, positive opening.
 */
internal fun expansionSettlesOpen(
    progress: Float,
    velocity: Float,
    wasOpen: Boolean,
    commitFraction: Float,
    flingVelocity: Float,
): Boolean = when {
    velocity > flingVelocity -> true
    velocity < -flingVelocity -> false
    wasOpen -> progress > 1f - commitFraction
    else -> progress > commitFraction
}

/**
 * The sheet's corner radius: the pill's at 0, square at 1. It falls with the square of the
 * remaining way, so the corners have gone (below a pixel, where the clip turns into a cheap
 * rectangle) during the whole of a short pull, while the card still reads as a card low down.
 */
internal fun sheetCornerRadius(pillRadius: Float, progress: Float): Float {
    val left = (1f - progress).coerceIn(0f, 1f)
    return pillRadius * left * left
}

/** The mini player's own content leaves early, before the full player's begins to show. */
internal fun miniPlayerContentAlpha(progress: Float): Float =
    (1f - progress / MINI_CONTENT_FADE).coerceIn(0f, 1f)

/** The full player's content arrives over the middle of the travel and is whole well before the top. */
internal fun fullPlayerContentAlpha(progress: Float): Float =
    ((progress - FULL_CONTENT_FADE_START) / FULL_CONTENT_FADE_SPAN).coerceIn(0f, 1f)

/** The pill's colour turns into the player's floor over the first half of the travel. */
internal fun playerSurfaceColorFraction(progress: Float): Float =
    (progress / SURFACE_COLOR_SPAN).coerceIn(0f, 1f)

/** The page under the player dims with it, so the growing surface reads as being in front. */
internal fun libraryScrimAlpha(progress: Float): Float =
    LIBRARY_SCRIM_MAX * progress.coerceIn(0f, 1f)

/**
 * The mini player's card in the window: inside the navigation bars, [margin] from the edges,
 * [height] tall. Mirrors the pill's own padding so the surface that grows out of it starts exactly
 * on top of it.
 */
internal fun miniPlayerBounds(
    width: Float,
    height: Float,
    insetLeft: Float,
    insetRight: Float,
    insetBottom: Float,
    margin: Float,
    pillHeight: Float,
): Rect {
    val bottom = height - insetBottom - margin
    return Rect(insetLeft + margin, bottom - pillHeight, width - insetRight - margin, bottom)
}

/** The 48 dp thumbnail inside the pill, at its start edge and vertically centred. */
internal fun miniPlayerThumbnailBounds(pill: Rect, startPadding: Float, size: Float, rtl: Boolean): Rect {
    val left = if (rtl) pill.right - startPadding - size else pill.left + startPadding
    val top = pill.center.y - size / 2f
    return Rect(left, top, left + size, top + size)
}

private const val MINI_CONTENT_FADE = 0.25f
private const val FULL_CONTENT_FADE_START = 0.1f
private const val FULL_CONTENT_FADE_SPAN = 0.5f
private const val SURFACE_COLOR_SPAN = 0.5f
private const val LIBRARY_SCRIM_MAX = 0.32f


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

/**
 * `FLAC · 1 010 kbps · 34.2 MB`, or as much of that as the facts allow; null without an extension.
 * The size is written with [currentDecimalSeparator] — the same rule [megabytesLabel] follows — so
 * this label reads "34,2 MB" in the languages that put a comma there. [separator] is a parameter so
 * a caller that knows its own separator can pin it.
 */
internal fun fileFormatLabel(
    fileName: String?,
    sizeBytes: Long?,
    durationMs: Long?,
    separator: Char = currentDecimalSeparator(),
): String? {
    val extension = fileName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() && it.length <= MAX_EXTENSION_LENGTH }
        ?: return null
    val parts = mutableListOf(extension.uppercase())
    estimatedBitrateKbps(sizeBytes, durationMs)?.let { parts += "${groupThousands(it)} kbps" }
    sizeBytes?.takeIf { it > 0L }?.let { bytes ->
        val tenthsOfMegabyte = (bytes / 100_000.0).roundToLong()
        parts += "${tenthsOfMegabyte / 10}$separator${tenthsOfMegabyte % 10} MB"
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
private const val SKIP_HOLD_STEP_MS = 1_500L
private const val SKIP_HOLD_BASE = 4
private const val SKIP_HOLD_MAX_STEPS = 3
private const val MAX_EXTENSION_LENGTH = 5
private const val SCAN_TICK_MS = 250L
