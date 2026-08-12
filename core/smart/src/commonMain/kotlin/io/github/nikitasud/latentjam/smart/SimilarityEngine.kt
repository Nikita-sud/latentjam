/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorCoverage
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSpace
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
 * `Uninitialized → initialize() → Ready → release() → Uninitialized`. The
 * heavy audio model is NOT part of this transition: it loads lazily inside the
 * first operation that must embed or classify, and a load failure surfaces as
 * that operation's typed error (retried on the next need) rather than as a
 * `Failed` engine — a restored index keeps serving queries regardless. The
 * engine is intended to be a process-wide singleton owned by the DI graph;
 * whoever owns the graph is responsible for calling [release] on teardown.
 */
public interface SimilarityEngine {

    /**
     * Current lifecycle state. Safe to collect or read from any thread;
     * intended for UI ("preparing smart shuffle…") and for gating callers.
     */
    public val state: StateFlow<EngineState>

    /**
     * Restores the persisted vector indexes and loads the queue-chain models
     * (scorer, text encoder). The audio encoder itself is NOT loaded here: it
     * loads lazily inside the first operation that must embed or classify, so
     * a fully indexed library launches without paying tens of MB of ONNX
     * session it may never run.
     *
     * Idempotent: calling while already [EngineState.Ready] returns success
     * immediately. The heavy work happens on the engine's background
     * dispatcher — never call-site's thread.
     *
     * @return success, or a failure whose exception is a [SmartEngineException]
     *   carrying the typed [EngineError].
     */
    public suspend fun initialize(): Result<Unit>

    /**
     * Reconciles both persisted indexes with the complete, currently visible library.
     *
     * Call this after a library scan and before indexing chunks. Tracks that were deleted or
     * hidden must not remain recommendation candidates or inflate [EngineState.Ready.indexedCount].
     * The argument is intentionally the complete library; a partial batch would make pruning
     * ambiguous.
     *
     * @return number of stale audio fingerprints removed
     */
    public suspend fun synchronizeLibrary(library: List<TrackDescriptor>): Int

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
     * How many of [ids] have no audio embedding yet — the cheap "is there any indexing work"
     * question, so launch paths can decide whether a foreground service and its notification
     * are warranted before committing to them. Pure index-membership lookups under the engine
     * lock; never touches the backend.
     */
    public suspend fun missingFromIndex(ids: List<TrackId>): Int

    /**
     * The stored embedding for [trackId], or `null` if it is not indexed.
     *
     * Exposed so the UI can express a track's position in latent space —
     * similar-sounding tracks land near each other, so a colour derived from
     * this vector is a visual echo of the similarity model itself.
     */
    public suspend fun embedding(trackId: TrackId): FloatArray?

    /**
     * Builds the strongest covered, one-shot vector space for library-level My Mixes.
     *
     * The engine reads its audio and metadata indexes under one lock and writes directly into one
     * owned row matrix. This avoids exposing mutable index state and avoids materializing two full
     * defensive snapshots beside the fused output on large libraries.
     */
    public suspend fun libraryMixVectors(ids: List<TrackId>): LibraryVectorSpace?

    /**
     * Which tracks [libraryMixVectors] would cover, and from which modality, without building the
     * rows.
     *
     * For callers that only need the population — a cached-layout check, a track-count ceiling, a
     * mappable-set filter. Building a space to read `trackIds` off it allocates
     * `trackIds.size × dim` floats and normalises every one of them; on an 877-track library that
     * is 4.7 MB per call, discarded unread. The selection is shared with the build, so the two
     * cannot disagree about which tracks are covered.
     */
    public suspend fun libraryMixCoverage(ids: List<TrackId>): LibraryVectorCoverage?

    /**
     * Builds mix vectors and batches the corresponding audio fingerprints through the universal
     * semantic head under the same engine lock.
     *
     * The semantics map may be sparse when audio is unavailable or the optional head cannot run;
     * clustering remains usable through [LibraryMixFeatures.vectorSpace].
     */
    public suspend fun libraryMixFeatures(ids: List<TrackId>): LibraryMixFeatures?

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
     * Deletes audio and metadata vectors from memory and durable storage without unloading models.
     * The next automatic indexing pass can rebuild them from the still-local library.
     */
    public suspend fun clearAnalysis()

    /**
     * Releases the model and clears the index, returning the engine to
     * [EngineState.Uninitialized]. The engine may be [initialize]d again
     * afterwards. Safe to call in any state.
     */
    public suspend fun release()
}
