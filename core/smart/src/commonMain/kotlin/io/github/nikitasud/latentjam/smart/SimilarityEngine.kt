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
     * Encodes any missing metadata-text vectors for [library], and persists them.
     *
     * Cheap and idempotent — tracks that already have one are skipped — but not free on a cold
     * library, so callers should run it in the background rather than in front of a SMART press.
     * Separate from [indexLibrary] because audio embedding is expensive enough to stay
     * user-initiated, while this can simply happen.
     *
     * @return how many vectors were added
     */
    public suspend fun ensureMetadataVectors(library: List<TrackDescriptor>): Int

    /**
     * A snapshot of every stored metadata-text vector, by track.
     *
     * Exposed for callers that want to reason about the SHAPE of a library rather than about one
     * neighbourhood of it — clustering it into regions, for instance. Taken under the engine's own
     * lock and copied, because [VectorIndex] implementations are not thread-safe and background
     * indexing may be writing into this one at the time.
     *
     * Empty when the platform has no text encoder, or before [initialize].
     */
    public suspend fun metadataVectors(): Map<TrackId, FloatArray>

    /**
     * Finds tracks whose trusted metadata embedding is closest to free-form [query] text.
     *
     * Intended as the semantic half of a hybrid search: callers keep exact title/artist/album
     * matching first, then use these hits to expand intent queries such as "90s dance". The same
     * small on-device text encoder and precomputed index used by SMART are reused; no audio decode,
     * network request, or additional model is involved.
     */
    public suspend fun semanticSearch(query: String, limit: Int = 50): List<ScoredTrack>

    /**
     * Builds a SMART queue: a coherent walk of up to [length] tracks starting from [seed].
     *
     * This is not [nextTrack] repeated. The walk carries state — how far it has drifted from the
     * seed, which artists and titles it has already used, how much the energy jumped last hop — and
     * that state is what keeps a queue coherent instead of letting it wander into whatever happens
     * to sit nearest at each step.
     *
     * @param library every track the queue may draw from; unindexed ones are ignored
     * @return track ids in play order, excluding [seed]; empty when SMART cannot run
     */
    public suspend fun smartQueue(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        length: Int,
        /** Oldest-first, device-local observations; empty preserves the exact cold-start path. */
        history: List<SmartHistoryEvent> = emptyList(),
    ): List<TrackId>

    /**
     * Releases the model and clears the index, returning the engine to
     * [EngineState.Uninitialized]. The engine may be [initialize]d again
     * afterwards. Safe to call in any state.
     */
    public suspend fun release()
}
