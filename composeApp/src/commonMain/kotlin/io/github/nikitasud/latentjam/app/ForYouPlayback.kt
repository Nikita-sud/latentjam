/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Starts a queue from a For You row, mix or collection so that it does not stop with the row.
 *
 * A row is eight to twelve tracks; played as an ordinary queue it ended after them in every mode
 * but SMART, and in SMART mode the row's own order was discarded for the tapped track alone. Now:
 * in SMART mode the rows play in their order and SMART extends the queue after them as usual
 * ([PlaybackController.playInOrder]); in OFF and ON the rows start at once and one SMART
 * continuation from the last of them, [queueLength] long, is appended after them when it is ready
 * ([PlaybackController.appendSmartPlan]) — the length the hero card's continuation has. ON shuffles
 * the rows; the continuation follows them. With repeat all no continuation is appended: the row
 * itself loops.
 */
internal suspend fun playFromForYou(
    playback: PlaybackController,
    engine: SimilarityEngine,
    history: ListeningHistory,
    tracks: List<TrackDescriptor>,
    startIndex: Int,
    library: List<TrackDescriptor>,
    queueLength: Int,
    companionGroups: List<Set<TrackId>>,
    isCurrent: () -> Boolean = { true },
) = playFromForYou(playback, tracks, startIndex, library, isCurrent) { seed ->
    engine.smartQueue(seed, library, queueLength, smartHistoryFor(history, seed), companionGroups)
}

/**
 * [playFromForYou] with the continuation planner as [plan]. [isCurrent] turns false once a newer For
 * You start was made (see [ForYouPlaybackLauncher]); the continuation is then not appended.
 */
internal suspend fun playFromForYou(
    playback: PlaybackController,
    tracks: List<TrackDescriptor>,
    startIndex: Int,
    library: List<TrackDescriptor>,
    isCurrent: () -> Boolean = { true },
    plan: suspend (seed: TrackDescriptor) -> List<TrackId>,
) {
    if (tracks.isEmpty()) return
    val index = startIndex.coerceIn(0, tracks.lastIndex)
    if (playback.state.value.shuffleMode == ShuffleMode.SMART) {
        playback.playInOrder(tracks, index)
        return
    }
    // The rows start at once; the continuation is planned behind them. Waiting for the planner
    // before play() left a tap silent for seconds and could let a late For You start override
    // something the listener picked elsewhere in the meantime.
    playback.play(tracks, index)
    val seed = tracks.last()
    val planned = runCatching { plan(seed) }.getOrElse { failure ->
        // A continuation is a courtesy: if SMART cannot plan, the row still plays as itself.
        if (failure is CancellationException) throw failure
        return
    }
    currentCoroutineContext().ensureActive()
    // A newer For You tap owns the queue now, even when it started the same row: checking the
    // queue's contents alone let two quick taps both pass and append the continuation twice.
    if (!isCurrent()) return
    // Append only to the queue this call started: any other start or edit since then wins.
    val rowIds = tracks.mapTo(HashSet()) { it.id }
    val now = playback.state.value
    if (now.queue.size != tracks.size || now.queue.any { it.id !in rowIds }) return
    // Repeat all loops the queue: the row is what was asked for, not the row plus twenty SMART
    // picks. Read here, at the last moment, since the planner may have taken seconds.
    if (now.repeatMode == RepeatMode.ALL) return
    val byId = library.associateBy { it.id }
    val tail = planned.filter { it !in rowIds }.mapNotNull(byId::get)
    playback.appendSmartPlan(tail, smartPlanPositions(planned).filterKeys { it !in rowIds })
}

/**
 * One For You start at a time. A tap cancels the previous tap's start, whose continuation may still
 * be planning, and only the newest may append one. Main-thread only, like the taps.
 */
internal class ForYouPlaybackLauncher {
    private var job: Job? = null
    private var generation = 0L

    fun launch(scope: CoroutineScope, start: suspend (isCurrent: () -> Boolean) -> Unit) {
        job?.cancel()
        val mine = ++generation
        job = scope.launch { start { generation == mine } }
    }
}
