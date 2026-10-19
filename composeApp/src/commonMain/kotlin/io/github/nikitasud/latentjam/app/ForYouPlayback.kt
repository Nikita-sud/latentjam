/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Starts a queue from a For You row, mix or collection so that it does not stop with the row.
 *
 * A row is eight to twelve tracks; played as an ordinary queue it ended after them in every mode
 * but SMART, and in SMART mode the row's own order was discarded for the tapped track alone. Now:
 * in SMART mode the rows play in their order and SMART extends the queue after them as usual
 * ([PlaybackController.playInOrder]); in OFF and ON the rows start at once and one SMART
 * continuation from the last of them, [queueLength] long, is appended after them when it is ready
 * ([PlaybackController.appendSmartPlan]) — the length the hero card's continuation has. ON shuffles
 * the rows; the continuation follows them.
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
    val planned = runCatching {
        engine.smartQueue(
            seed,
            library,
            queueLength,
            smartHistoryFor(history, seed),
            companionGroups,
        )
    }.getOrElse { failure ->
        // A continuation is a courtesy: if SMART cannot plan, the row still plays as itself.
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        return
    }
    // Append only to the queue this call started: any other start or edit since then wins.
    val rowIds = tracks.mapTo(HashSet()) { it.id }
    val queue = playback.state.value.queue
    if (queue.size != tracks.size || queue.any { it.id !in rowIds }) return
    val byId = library.associateBy { it.id }
    val tail = planned.filter { it !in rowIds }.mapNotNull(byId::get)
    playback.appendSmartPlan(tail, smartPlanPositions(planned).filterKeys { it !in rowIds })
}
