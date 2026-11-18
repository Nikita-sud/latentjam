/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.IndexReport
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.isAnalysisTimeout
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

    /**
     * Loads what a chunk with work needs before its first track (the audio model). Called once, before the first such
     * chunk is marked as in flight: a process that ends while the model loads is then no chunk's doing.
     */
    suspend fun prepare() = Unit
    fun envelopeRefused(): Boolean

    /** The remembered failures phase 1 would report for [tracks], read-only. */
    suspend fun rememberedFailures(tracks: List<TrackDescriptor>): Map<TrackId, EngineError>

    /** Emits whenever analysis moves to another track; renews the wake lock. */
    val trackChanges: Flow<Any?>

    /** Emits on every device or playback change the table reads. */
    val signals: Flow<Any?>
}

/**
 * Analysis stalled twice with nothing analysed in between (GatedAudioPass.stagePastAStall): the pass ends as a failed
 * pass does, and the restart rules decide when it is tried again.
 */
internal class AnalysisPassStalled : RuntimeException("analysis stalled")

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
        /** Tracks analysed first, each in a chunk of its own ([analysisChunks]). */
        alone: Set<TrackId> = emptySet(),
        /** Told of every chunk, so one that ends the process can be found ([AnalysisInFlight]). */
        inFlight: InFlightMarker = InFlightMarker,
    ): AudioPassEnd {
        shown = shownAtStart
        stalledBefore = false
        analysedSinceStall = false
        val pauseLabel = notices(AnalysisNotice.PauseAction)
        val cadence = AutomaticIndexCheckpointCadence(tracksPerCheckpoint = checkpointTracks)
        var gated = gatedAtStart
        var visited = 0
        // The phase the pass last published; RUNNING only once it has actually run.
        var shownPhase: AnalysisPhase? = null
        var pausing = false
        var ranGated = false
        var prepared = false
        state.update { it.copy(pausable = gated) }
        if (gated) control.listen()
        val watchers = if (gated) scope.launch {
            launch { engine.signals.collect { control.onSignal(engine.envelopeRefused()) } }
            // The lock is renewed per track: a pass stuck inside one lets it lapse.
            launch { engine.trackChanges.collect { control.renew() } }
            launch {
                control.userPaused.collect { paused ->
                    if (!paused || !shown || !gated) return@collect
                    state.update { it.copy(phase = AnalysisPhase.PAUSING, etaMinutes = null) }
                    // At once, not at the boundary: the chunk's own update below says the same while still paused.
                    runCatching { show(notices(AnalysisNotice.Waiting(AnalysisPhase.PAUSING)), state.value.done, null) }
                }
            }
        } else null
        try {
            val chunks = analysisChunks(order, alone)
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
                // No hand-over when the pass will stop at the next boundary: nothing decodes past a pause.
                val upcoming = if (!ANALYSIS_CARRY_DECODING || (gated && control.userPaused.value)) {
                    emptyList()
                } else {
                    chunks.getOrNull(position + 1).orEmpty()
                }
                if (missingBefore > 0) {
                    if (!prepared) {
                        // Before the marker: a process that ends while the model loads is no track's doing.
                        prepared = true
                        engine.prepare()
                    }
                    inFlight.begin(chunk)
                }
                // Completed only when the engine went through the whole chunk: a stalled one was cut short, and the
                // tracks it did not reach (crash suspects among them) are still to be analysed.
                var completed = false
                val report = try {
                    stagePastAStall(chunk, upcoming).also { completed = !it.stalled }
                } catch (exhausted: Error) {
                    // Out of memory in this chunk. The pass stops for it (runAutomaticIndexingSafely) and the process
                    // lives, so the marker below is cleared and no launch ever finds it: without a word here the next
                    // pass met the same chunk and stopped the same way, for good. Told as a crashed chunk, its tracks
                    // get a ledger attempt and are taken one by one, and the ledger ends the one that does it.
                    if (exhausted.isOutOfMemory()) {
                        try {
                            inFlight.crashed(chunk)
                        } catch (_: Throwable) {
                            // No memory to note it either: the error itself still goes on.
                        }
                    }
                    throw exhausted
                } finally {
                    inFlight.end(chunk, completed)
                }
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
                // Stalled twice with nothing analysed in between (stagePastAStall). What the chunk reported is out and
                // checkpointed like any chunk's; going on would cost every remaining track its 90 s, a ledger attempt
                // and a leaked thread.
                if (report.stalled) throw AnalysisPassStalled()
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

    /** Whether a chunk of this pass stalled, and whether a track was analysed since the last one that did. */
    private var stalledBefore = false
    private var analysedSinceStall = false

    /**
     * Stages [chunk] and goes past a stall. A stalled report ([IndexReport.stalled]) means three tracks in a row timed
     * out: a sign that the storage or the extractor service stopped answering, but three hung files do sit together
     * too. 0.9.5 analysed the rest of the library past them in the same pass, and a pass that ended here left it
     * waiting for a restart. So the tracks that timed out stay behind (the retry ledger holds them) and the rest of
     * the chunk is staged again. Only a stall that follows another with no track analysed in between ends the pass:
     * then nothing but timeouts has come back for six tracks in a row, and the report returned says [stalled].
     */
    private suspend fun stagePastAStall(chunk: List<TrackDescriptor>, upcoming: List<TrackDescriptor>): IndexReport {
        var work = chunk
        var indexed = 0
        val errors = LinkedHashMap<TrackId, EngineError>()
        while (true) {
            val report = engine.stage(work, upcoming)
            if (work === chunk && !report.stalled) return report.also { if (it.indexed > 0) analysedSinceStall = true }
            indexed += report.indexed
            errors.putAll(report.errors)
            if (report.indexed > 0) analysedSinceStall = true
            val merged = report.copy(indexed = indexed, failed = errors.size, errors = errors)
            if (!report.stalled) return merged
            val again = stalledBefore && !analysedSinceStall
            stalledBefore = true
            analysedSinceStall = false
            if (again) return merged
            // Every stalled report names at least the track its row ended on, so this shrinks until it is done.
            work = work.filterNot { errors[it.id]?.isAnalysisTimeout() == true }
            if (work.isEmpty()) return merged.copy(stalled = false)
        }
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

/**
 * The pass's chunks: the [alone] tracks first, each in a chunk of its own (a chunk that ended the process is taken
 * apart, so the next end names one track), then the rest [AUTOMATIC_INDEX_CHUNK_SIZE] at a time, as every pass.
 */
internal fun analysisChunks(order: List<TrackDescriptor>, alone: Set<TrackId>): List<List<TrackDescriptor>> =
    if (alone.isEmpty()) {
        order.chunked(AUTOMATIC_INDEX_CHUNK_SIZE)
    } else {
        order.filter { it.id in alone }.map(::listOf) + order.filterNot { it.id in alone }.chunked(AUTOMATIC_INDEX_CHUNK_SIZE)
    }

/**
 * Whether the pass hands each chunk its successor, so decoding carries on through the gap between chunks (plan P4).
 * Off: on the 4-core emulator with the 30 s test clips it was byte-identical but slower (487–527 ms per track against
 * 461–470 without), so it stays off until a physical phone with full-length tracks shows a gain.
 */
internal const val ANALYSIS_CARRY_DECODING: Boolean = false
