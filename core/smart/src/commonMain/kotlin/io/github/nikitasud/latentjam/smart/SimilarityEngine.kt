/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.flow.StateFlow

/**
 * On-device next-track similarity engine ("SMART" shuffle).
 *
 * Given the current listening context, the engine returns the id of the
 * nearest-neighbor track in embedding space: tracks are encoded into
 * fixed-size vectors by a platform [EmbeddingBackend] (a CNN audio encoder in
 * production), stored in a [VectorIndex], and queried by cosine similarity.
 *
 * ### Separation of concerns — the load-bearing contract
 * This interface knows NOTHING about playback. No player, queue,
 * media-session, or media-library type may ever appear in its signatures —
 * only the engine's own value objects ([TrackDescriptor], [ListeningContext],
 * [TrackId], …). Callers observe their playback state machine however they
 * like, project it into a [ListeningContext], and apply the returned
 * [TrackId] to their queue themselves. The engine is a pure
 * question-answering service.
 *
 * ### Threading
 * All suspend functions are main-safe: implementations confine their work
 * (model loading, tensor ops, index scans) to a background dispatcher
 * internally, so calling from a UI-bound coroutine scope is fine and will
 * never jank the Compose UI. Calls are serialized internally — concurrent
 * callers queue rather than interleave.
 *
 * ### Lifecycle
 * `Uninitialized → initialize() → Ready → release() → Uninitialized`, with
 * `Failed` reachable from a failed [initialize] (retry allowed). The engine
 * is intended to be a process-wide singleton owned by the DI graph; whoever
 * owns the graph is responsible for calling [release] on teardown.
 */
public interface SimilarityEngine {

    /**
     * Current lifecycle state. Safe to collect or read from any thread;
     * intended for UI ("preparing smart shuffle…") and for gating callers.
     */
    public val state: StateFlow<EngineState>

    /**
     * Loads the similarity model via the platform backend.
     *
     * Idempotent: calling while already [EngineState.Ready] returns success
     * immediately without touching the backend. Calling after a failure
     * retries. The heavy work happens on the engine's background dispatcher —
     * never call-site's thread.
     *
     * @return success, or a failure whose exception is a [SmartEngineException]
     *   carrying the typed [EngineError].
     */
    public suspend fun initialize(): Result<Unit>

    /**
     * Embeds and indexes the given tracks, replacing any previous vector for
     * the same [TrackId] (upsert semantics). Requires [EngineState.Ready].
     *
     * This is the expensive, batch side of the engine (decode + CNN forward
     * pass per track in production) and is expected to be driven by a
     * background scheduler (WorkManager / BGTaskScheduler), not by playback.
     * Per-track failures do not abort the batch; they are reported in the
     * returned [IndexReport].
     */
    public suspend fun indexLibrary(tracks: List<TrackDescriptor>): IndexReport

    /**
     * Returns the nearest neighbor of [ListeningContext.seed] among indexed
     * tracks, excluding the seed itself, [ListeningContext.recentTrackIds],
     * and [ListeningContext.excludedTrackIds].
     *
     * If the seed track is already indexed its stored vector is reused;
     * otherwise the backend embeds it on the fly. Never throws for expected
     * conditions — all outcomes are values of [NextTrackResult].
     */
    public suspend fun nextTrack(context: ListeningContext): NextTrackResult

    /**
     * The stored embedding for [trackId], or `null` if it is not indexed.
     *
     * Exposed so the UI can express a track's position in latent space —
     * similar-sounding tracks land near each other, so a colour derived from
     * this vector is a visual echo of the similarity model itself.
     */
    public suspend fun embedding(trackId: TrackId): FloatArray?

    /**
     * Releases the model and clears the index, returning the engine to
     * [EngineState.Uninitialized]. The engine may be [initialize]d again
     * afterwards. Safe to call in any state.
     */
    public suspend fun release()
}
