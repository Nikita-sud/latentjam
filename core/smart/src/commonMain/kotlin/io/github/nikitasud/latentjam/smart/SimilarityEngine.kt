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
     * Reconciles both persisted indexes with the currently visible library.
     *
     * Call this after a library scan and before indexing chunks. Changed identities among the
     * supplied rows are always invalidated. When [pruneMissing] is true, tracks absent from the
     * argument are also removed; pass false for a partial/ambiguous scan (for example while media
     * permission is unavailable) so it cannot erase durable rows or remembered audio failures.
     *
     * @return number of stale audio fingerprints removed
     */
    public suspend fun synchronizeLibrary(
        library: List<TrackDescriptor>,
        pruneMissing: Boolean = true,
    ): Int

    /**
     * Re-keys audio vectors across verified tag-only writes, before [synchronizeLibrary] would
     * discard them for their file's new revision. A carry-over settles when its track appears in
     * [library] with a revision other than its old one. It applies when the stored vector was made
     * from that old revision and the file is exactly the expected length; otherwise the track is
     * re-analysed as usual. One whose track still shows the old revision stays unsettled: the
     * rescan has not landed yet.
     */
    public suspend fun carryOverAudio(
        library: List<TrackDescriptor>,
        carryOvers: List<AudioCarryOver>,
    ): AudioCarryOverResult = AudioCarryOverResult(emptySet(), emptySet())

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
     * Performs the same in-memory work as [indexLibrary], but permits the durable snapshot to be
     * coalesced with later batches through [persistPendingAnalysis].
     *
     * Intended only for a long-running scheduler that supplies its own bounded checkpoint cadence.
     * The default keeps the ordinary durable-per-call contract, so alternate implementations stay
     * safe without opting into deferred persistence.
     */
    public suspend fun stageLibraryIndex(tracks: List<TrackDescriptor>): IndexReport =
        indexLibrary(tracks)

    /**
     * Clears durable invalid-audio markers for [ids], making those unchanged tracks eligible for
     * the next [indexLibrary] call again.
     *
     * This is the explicit user-retry seam. Merely restarting a background indexing job must not
     * silently defeat the failure cache, while a visible Retry action must be able to recover from
     * a codec/platform update or an earlier misclassification without requiring the media to be
     * edited. The cleared snapshot is persisted before this method returns.
     *
     * @return number of remembered track failures cleared
     */
    public suspend fun retryFailedTracks(ids: List<TrackId>): Int

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
     * How many of [ids] still need an audio embedding — the cheap "is there any indexing work"
     * question, so launch paths can decide whether a foreground service and its notification
     * are warranted before committing to them. An unchanged track with a remembered local decode
     * failure is not work until its descriptor identity changes. Callers synchronize the current
     * library first so those identities can be reconciled. Never touches the backend.
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
    public suspend fun libraryMixFeatures(
        ids: List<TrackId>,
        /** Explicitly permits loading the audio model to fill optional semantic classifications. */
        loadMissingSemantics: Boolean = false,
    ): LibraryMixFeatures?

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
     * Performs the same in-memory work as [ensureMetadataVectors], while allowing a scheduler to
     * combine several small interactive batches into one durable snapshot checkpoint.
     *
     * The default delegates to the durable operation. Implementations that defer the write must
     * retain dirty state until [persistPendingAnalysis] returns successfully.
     */
    public suspend fun stageMetadataVectors(library: List<TrackDescriptor>): Int =
        ensureMetadataVectors(library)

    /**
     * Persists all analysis mutations staged since the last successful checkpoint.
     *
     * Idempotent and main-safe. A scheduler should call this at a bounded interval and at every
     * normal-completion or cancellation boundary. The default is a no-op because the default
     * staging methods above already persist each call.
     */
    public suspend fun persistPendingAnalysis(): Unit = Unit

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
     * Starts a new SMART queue: a coherent walk of up to [length] tracks from [seed].
     * A previous plan ending at this track never changes the new queue's intent or exclusions.
     * Automatic queue top-ups use [continueSmartQueue] to carry the preceding plan's walk.
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
        /**
         * Track groups the listener explicitly asked to keep together (opted-in playlists),
         * passed as bare id sets — the engine stays ignorant of what a playlist is. Empty
         * preserves the exact shipped chain.
         */
        companionGroups: List<Set<TrackId>> = emptyList(),
    ): List<TrackId>

    /**
     * Extends a SMART queue from its last planned track [seed]. When a matching walk is still
     * available, it carries the preceding plan's intent and recent picks into this plan; otherwise
     * it starts from [seed]. [precedingTrackIds] are the queue's recent tracks immediately before
     * [seed], oldest first, so a stale plan cannot be resumed merely by choosing its last track
     * ([continuesSmartPlan] decides against the plan's own rows, and lets the listener's queue edits
     * through). After the listener removed or moved the queue's last rows, or the app discarded the
     * queue's future to replan it, [seed] is an earlier track of the walk: it resumes as it stood
     * there, and the tracks it picked after [seed] stay spent, so one the listener removed is not
     * planned again in this walk. [library] contains only tracks available to append to the queue, so
     * the caller keeps out what is queued and what the listener removed from this queue.
     *
     * User-initiated starts use [smartQueue], even if their seed ended a previous plan. The default
     * delegates there so engines without continuation support retain their existing behavior.
     */
    public suspend fun continueSmartQueue(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        length: Int,
        history: List<SmartHistoryEvent> = emptyList(),
        companionGroups: List<Set<TrackId>> = emptyList(),
        precedingTrackIds: List<TrackId> = emptyList(),
    ): List<TrackId> = smartQueue(seed, library, length, history, companionGroups)

    /**
     * How strongly SMART avoids several tracks in a row by one artist, for plans requested from now on (the
     * listener's setting; see SmartEngineConfig.artistRunPenalty). Engines without SMART ignore it.
     */
    public fun setArtistRunPenalty(penalty: Float) {}

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

/**
 * Whether a queue that still ends at the track a SMART plan expected is the queue that plan was made
 * for, not a new start that happens to end there. [plannedBefore] are the tracks that preceded that
 * track when it was planned or served, [queuedBefore] the queue's tracks before it now, oldest first
 * (the `precedingTrackIds` of [SimilarityEngine.continueSmartQueue]). The engine resumes a walk by
 * this rule, and the app's queue top-up keeps its own cached plan by the same one.
 *
 * One track in both is enough, wherever it sits now. Between top-ups the listener removes and moves
 * upcoming tracks, Play next inserts one right after the playing track (inside a short queue's
 * window), and a planned slot that was no longer eligible never reached the queue: none of that is a
 * new queue, yet each shifts the window. Order is ignored on purpose: a move reorders the shared
 * tracks, while one coincidentally shared track is always in order, so an order check would refuse
 * real edits without telling a coincidence apart. A new start has nothing before its seed (SMART
 * playback starts from the picked track alone), so an empty window never continues a plan.
 */
public fun continuesSmartPlan(plannedBefore: List<TrackId>, queuedBefore: List<TrackId>): Boolean {
    if (plannedBefore.isEmpty() || queuedBefore.isEmpty()) return false
    val planned = plannedBefore.toHashSet()
    return queuedBefore.any { it in planned }
}
