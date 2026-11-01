/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.IndexReport
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the gated audio pass needs from the engine; a seam so host tests can drive it. */
internal interface AudioPassEngine {
    suspend fun missingFromIndex(ids: List<TrackId>): Int
    /** Stages [chunk]; [upcoming] is the next chunk, whose decoding may start meanwhile. */
    suspend fun stage(chunk: List<TrackDescriptor>, upcoming: List<TrackDescriptor>): IndexReport
    suspend fun persist()
    fun envelopeRefused(): Boolean

    /** The remembered failures phase 1 would report for [tracks], read-only. */
    suspend fun rememberedFailures(tracks: List<TrackDescriptor>): Map<TrackId, EngineError>

    /** Emits whenever analysis moves to another track; renews the wake lock. */
    val trackChanges: Flow<Any?>

    /** Emits on every device or playback change the table reads. */
    val signals: Flow<Any?>
}

/** How the audio pass ended. */
internal enum class AudioPassEnd { COMPLETED, PAUSED }

/**
 * The audio pass of [AppGraph.ensureAutomaticIndexing] (step 4) under the state table (docs/analysis-094-plan.md 1e-min).
 *
 * Every phase change, notification and state update happens on the pass's own coroutine, except the "Pausing…" mark,
 * which the pause watcher sets through [MutableStateFlow.update] and the pass keeps until it stops. The pass is shown
 * (the foreground service and its notification) the first time it may run with work left, whether that is at the start
 * or after a Continue that came during steps 1–3.
 */
internal class GatedAudioPass(
    private val engine: AudioPassEngine,
    private val control: AnalysisRunControl,
    private val state: MutableStateFlow<AutomaticIndexingState>,
    private val scope: CoroutineScope,
    private val show: suspend (text: String, done: Int, pauseLabel: String?) -> Unit,
    private val notices: suspend (AnalysisNotice) -> String,
    private val progressText: suspend (done: Int, total: Int, etaMinutes: Int?) -> String,
    private val onFirstShow: () -> Unit = {},
    /** The pass ran its first chunk under the table: a stop reason left from an earlier pass no longer holds. */
    private val onRunning: () -> Unit = {},
    private val elapsedMs: () -> Long = ::analysisElapsedMs,
    private val nowMs: () -> Long,
    private val yieldMs: Long = AUTOMATIC_INDEX_YIELD_MS,
    private val checkpointTracks: Int = AUTOMATIC_INDEX_CHECKPOINT_TRACKS,
) {
    /** Whether the pass has been shown; read by the caller for the stopped notice. */
    var shown = false
        private set

    suspend fun run(
        order: List<TrackDescriptor>,
        allIds: List<TrackId>,
        total: Int,
        progress: AnalysisProgress,
        failures: MutableMap<TrackId, EngineError>,
        eta: IndexingEta,
        gatedAtStart: Boolean,
        shownAtStart: Boolean,
    ): AudioPassEnd {
        shown = shownAtStart
        val pauseLabel = notices(AnalysisNotice.PauseAction)
        val cadence = AutomaticIndexCheckpointCadence(tracksPerCheckpoint = checkpointTracks)
        var gated = gatedAtStart
        var visited = 0
        // The phase the pass last published; RUNNING only once it has actually run.
        var shownPhase: AnalysisPhase? = null
        var pausing = false
        var ranGated = false
        state.update { it.copy(pausable = gated) }
        if (gated) control.listen()
        val watchers = if (gated) scope.launch {
            launch { engine.signals.collect { control.onSignal(engine.envelopeRefused()) } }
            // The lock is renewed per track: a pass stuck inside one lets it lapse.
            launch { engine.trackChanges.collect { control.renew() } }
            launch {
                control.userPaused.collect { paused ->
                    if (!paused || !shown) return@collect
                    state.update { it.copy(phase = AnalysisPhase.PAUSING, etaMinutes = null) }
                    // At once, not at the boundary: the chunk's own update below says the same while still paused.
                    runCatching { show(notices(AnalysisNotice.Waiting(AnalysisPhase.PAUSING)), state.value.done, null) }
                }
            }
        } else null
        try {
            val chunks = order.chunked(AUTOMATIC_INDEX_CHUNK_SIZE)
            for ((position, chunk) in chunks.withIndex()) {
                // Nothing left to analyse: the table no longer applies (the count, not an O(N) query per chunk).
                if (gated && progress.analysed >= total) {
                    gated = false
                    state.update { it.copy(pausable = false, phase = null) }
                }
                if (gated) {
                    var waitStarted: Long? = null
                    val runnable = control.awaitRunnable(
                        envelopeRefused = engine::envelopeRefused,
                        onPhase = { phase ->
                            if (phase == AnalysisPhase.RUNNING || phase == shownPhase) return@awaitRunnable
                            shownPhase = phase
                            state.update { it.copy(running = true, phase = phase, etaMinutes = null, progressAt = null) }
                            if (shown) show(notices(AnalysisNotice.Waiting(phase)), progress.analysed, pauseLabel)
                            Unit
                        },
                        onWaitStart = {
                            waitStarted = elapsedMs()
                            engine.persist()
                        },
                    )
                    if (!runnable) return AudioPassEnd.PAUSED
                    // The wait is no part of the pass's pace, deep sleep included.
                    waitStarted?.let { eta.skip(elapsedMs() - it) }
                    if (shownPhase != AnalysisPhase.RUNNING) {
                        shownPhase = AnalysisPhase.RUNNING
                        val first = !shown
                        if (!ranGated) {
                            ranGated = true
                            onRunning()
                        }
                        if (first) {
                            // Paused at the start, and continued since (also during steps 1–3).
                            shown = true
                            onFirstShow()
                        }
                        state.update {
                            it.copy(
                                running = true,
                                phase = null,
                                stopReason = null,
                                progressAt = TimeSource.Monotonic.markNow(),
                            )
                        }
                        if (first || visited > 0) show(progressText(progress.analysed, total, null), progress.analysed, pauseLabel)
                    }
                } else if (shownPhase == null) {
                    shownPhase = AnalysisPhase.RUNNING
                    state.update { it.copy(progressAt = TimeSource.Monotonic.markNow()) }
                }
                val missingBefore = engine.missingFromIndex(chunk.map(TrackDescriptor::id))
                val report = engine.stage(chunk, chunks.getOrNull(position + 1).orEmpty())
                failures.putAll(report.errors)
                visited += chunk.size
                progress.visited(missingBefore)
                cadence.afterBatch(chunk.size) { engine.persist() }
                // The pass's own pace: tracks already analysed cost it nothing.
                val remaining = eta.remainingMs(visited, total, nowMs())?.let(IndexingEta::minutesFrom)
                pausing = gated && control.userPaused.value && shown
                if (shown) {
                    if (pausing) {
                        show(notices(AnalysisNotice.Waiting(AnalysisPhase.PAUSING)), progress.analysed, null)
                    } else {
                        // Pause is offered only where it acts.
                        show(progressText(progress.analysed, total, remaining), progress.analysed, pauseLabel.takeIf { gated })
                    }
                }
                state.update {
                    it.copy(
                        done = progress.analysed,
                        failures = failures.toMap(),
                        phase = if (pausing) AnalysisPhase.PAUSING else null,
                        tooLarge = engine.envelopeRefused(),
                        etaMinutes = remaining.takeIf { !pausing },
                        progressAt = TimeSource.Monotonic.markNow().takeIf { !pausing },
                    )
                }
                // A short scheduling gap also gives thermal management a chance to settle;
                // 75 ms per model batch is invisible beside audio inference but materially
                // improves gesture latency on mid-range phones.
                if (report.indexed > 0 || report.failed > 0) delay(yieldMs)
            }
        } finally {
            withContext(NonCancellable) {
                // Joined, so no signal can take the wake lock again after the release below.
                watchers?.cancelAndJoin()
                control.release()
                state.update { it.copy(pausable = false) }
            }
        }
        cadence.flush { engine.persist() }
        return AudioPassEnd.COMPLETED
    }

    /** The failure list a gated or stopped pass publishes: what it saw, plus what the engine remembers. */
    suspend fun publishedFailures(
        tracks: List<TrackDescriptor>,
        failures: MutableMap<TrackId, EngineError>,
    ): Map<TrackId, EngineError> {
        for ((id, error) in engine.rememberedFailures(tracks)) failures.getOrPut(id) { error }
        return failures.toMap()
    }
}
