/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.LyricLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

/** Build once per lyrics source; position ticks only need a binary search through timed rows. */
internal class LyricsTimeline(lines: List<LyricLine>) {
    private val timedLines = lines.mapIndexedNotNull { index, line ->
        line.timeMs?.let { it to index }
    }.sortedBy { it.first }

    fun activeIndexAt(positionMs: Long): Int {
        val position = positionMs.coerceAtLeast(0L)
        val cue = if (position > Long.MAX_VALUE - LYRICS_LEAD_MS) Long.MAX_VALUE else position + LYRICS_LEAD_MS
        var low = 0
        var high = timedLines.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (timedLines[middle].first <= cue) low = middle + 1 else high = middle
        }
        return timedLines.getOrNull(low - 1)?.second ?: -1
    }
}

internal data class LyricsFollowRequest(
    val activeIndex: Int,
    val interacting: Boolean,
    val interactionEndedAt: TimeMark?,
    val viewportHeight: Int,
    val reduceMotion: Boolean,
)

/**
 * A hold expires even if playback is paused or the same lyric lasts a long time. A new touch,
 * cue, or layout cancels any pending scroll and recalculates the remaining hold from its end.
 */
internal suspend fun followLyrics(
    requests: Flow<LyricsFollowRequest>,
    scroll: suspend (LyricsFollowRequest) -> Unit,
) {
    requests.collectLatest { request ->
        if (request.activeIndex < 0 || request.interacting || request.viewportHeight <= 0) return@collectLatest
        request.interactionEndedAt?.let { endedAt ->
            val remaining = LYRICS_SCROLL_HOLD - endedAt.elapsedNow()
            if (remaining.isPositive()) delay(remaining)
        }
        scroll(request)
    }
}

/** Lines light up slightly before they are sung. */
private const val LYRICS_LEAD_MS = 150L
private val LYRICS_SCROLL_HOLD = 4.seconds
