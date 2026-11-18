/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.PickSurface
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.playback.StartCause
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.associateById
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
    /**
     * False while SMART is switched off in settings: the rows play as themselves, nothing is planned behind them. Asked
     * again once the plan is ready, since planning takes seconds and the switch may have been flipped meanwhile.
     */
    continuation: () -> Boolean = { true },
) = playFromForYou(playback, tracks, startIndex, library, isCurrent, continuation) { seed ->
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
    continuation: () -> Boolean = { true },
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
    PersonalSignals.pick(PickSurface.FOR_YOU)
    playback.play(tracks, index)
    if (!continuation()) return
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
    if (!continuation()) return
    val byId = library.associateBy { it.id }
    val tail = planned.filter { it !in rowIds }.mapNotNull(byId::get)
    playback.appendSmartPlan(tail, smartPlanPositions(planned).filterKeys { it !in rowIds })
}

/**
 * A For You card while SMART is switched off: its track, and the library on from it in the Tracks tab's order, as a tap
 * on a track row there plays. A card that played one track and then fell silent would be the only such control.
 */
internal suspend fun playLibraryFrom(playback: PlaybackController, track: TrackDescriptor, songs: List<TrackDescriptor>) {
    val index = songs.indexOfFirst { it.id == track.id }
    if (index >= 0) playback.play(songs, index) else playback.play(listOf(track), 0)
}

/**
 * A start that plans its queue before it plays: the For You hero card, "SMART from here" on a Map region and on a
 * mix. Plans from [seed] and starts [seed] with the plan after it.
 *
 * [beforePlay] runs right before the start (the queue's source, the pick surface). [songs] resolves the planned ids,
 * asked once the plan is ready. With [smart] false (SMART switched off; asked again once the plan is ready, since
 * planning takes seconds) nothing is planned and [withoutSmart] plays instead. [resumeAtMs] is where an unfinished
 * track goes on.
 *
 * @return whether playback was started.
 */
internal suspend fun playPlannedFrom(
    playback: PlaybackController,
    seed: TrackDescriptor,
    songs: () -> Map<TrackId, TrackDescriptor>,
    isCurrent: () -> Boolean = { true },
    resumeAtMs: Long? = null,
    smart: () -> Boolean = { true },
    withoutSmart: suspend () -> Unit = {},
    beforePlay: () -> Unit = {},
    plan: suspend () -> List<TrackId>,
): Boolean {
    val watch = LaterStartWatch(playback)
    val queue = if (!smart()) emptyList() else plan()
    // Planning takes seconds. What the listener started meanwhile is the newer wish: a planned start
    // that arrived after it replaced the song they had just picked.
    if (!isCurrent() || watch.startedSince()) return false
    beforePlay()
    if (!smart()) {
        withoutSmart()
    } else {
        val byId = songs()
        playback.play(listOf(seed) + queue.mapNotNull(byId::get), 0, smartPlanPositions(queue))
    }
    resumeAtMs?.let { playback.seekTo(it) }
    return true
}

/**
 * Tells whether the listener started other music since this was made.
 *
 * Every screen marks its pick right before it starts playback ([PersonalSignals.pick]), which is known at once. A
 * start no screen marks (a voice command, the notification) is known only when the player reports it; on Android
 * that report can also be of a start asked for earlier, so there it counts only if this app asked the player for
 * something since ([PlaybackController.userPlaybackRevision], which stays 0 where the player reports at once). A
 * track that followed by itself is no such start.
 */
internal class LaterStartWatch(private val playback: PlaybackController) {
    private val picks = PersonalSignals.pickCount
    private val revision = playback.userPlaybackRevision
    private val start = playback.state.value.playbackStart?.sequence

    fun startedSince(): Boolean {
        if (PersonalSignals.pickCount != picks) return true
        val revisionNow = playback.userPlaybackRevision
        if (revisionNow != 0L && revisionNow == revision) return false
        val now = playback.state.value.playbackStart ?: return false
        return now.sequence != start && now.cause == StartCause.USER_PICK
    }
}

/**
 * [playPlannedFrom] with SMART's own planner over [library] (SMART's eligible tracks, asked for only when it plans),
 * the queue's [source] and the pick's [surface].
 */
internal suspend fun playSmartFrom(
    playback: PlaybackController,
    engine: SimilarityEngine,
    history: ListeningHistory,
    seed: TrackDescriptor,
    library: suspend () -> List<TrackDescriptor>,
    queueLength: Int,
    companionGroups: List<Set<TrackId>>,
    source: QueueSource,
    surface: PickSurface,
    songs: () -> Map<TrackId, TrackDescriptor>,
    isCurrent: () -> Boolean = { true },
    resumeAtMs: Long? = null,
    smart: () -> Boolean = { true },
    withoutSmart: suspend () -> Unit = {},
): Boolean = playPlannedFrom(
    playback, seed, songs, isCurrent, resumeAtMs, smart, withoutSmart,
    beforePlay = {
        AppGraph.queueSource.value = source
        PersonalSignals.pick(surface)
    },
) { engine.smartQueue(seed, library(), queueLength, smartHistoryFor(history, seed), companionGroups) }

/**
 * The For You hero card: the personal signal picked the seed, SMART decides what follows it. With SMART switched off
 * the card plays its track and the library on from it ([songs] in the Tracks tab's order).
 */
internal suspend fun playHeroFromForYou(
    playback: PlaybackController,
    engine: SimilarityEngine,
    history: ListeningHistory,
    hero: ForYouHero,
    library: suspend () -> List<TrackDescriptor>,
    queueLength: Int,
    companionGroups: List<Set<TrackId>>,
    songs: List<TrackDescriptor>,
    isCurrent: () -> Boolean,
    smart: () -> Boolean,
): Boolean = playSmartFrom(
    playback, engine, history, hero.track, library, queueLength, companionGroups,
    source = QueueSource(QueueSourceKind.FOR_YOU), surface = PickSurface.FOR_YOU,
    songs = { songs.associateById() }, isCurrent = isCurrent, resumeAtMs = hero.resumeAtMs, smart = smart,
    withoutSmart = { playLibraryFrom(playback, hero.track, songs) },
)

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
