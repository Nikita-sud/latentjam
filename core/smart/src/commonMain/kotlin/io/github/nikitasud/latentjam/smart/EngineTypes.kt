/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * Typed failure reasons surfaced by the engine and its backends.
 *
 * These are the ONLY error vocabulary of the module: platform backends must
 * translate their native failures (ORT status codes, Core ML errors, …) into
 * one of these before crossing the common boundary, so callers never need
 * platform-specific error handling.
 */
public sealed interface EngineError {

    /**
     * The similarity model is not loaded — either [SimilarityEngine.initialize]
     * has not succeeded yet, or the platform backend is a stub / the model
     * asset is missing.
     */
    public data object ModelUnavailable : EngineError

    /** The engine is initialized but the vector index contains no tracks. */
    public data object NotIndexed : EngineError

    /**
     * A platform backend failed while loading the model or running inference.
     *
     * @property message Human-readable description of what went wrong.
     * @property cause The underlying platform throwable, when one exists.
     */
    public data class BackendFailure(
        public val message: String,
        public val cause: Throwable? = null,
    ) : EngineError
}

/**
 * Observable lifecycle of a [SimilarityEngine].
 *
 * State transitions are driven exclusively by the engine's own suspend
 * operations; observing this flow never blocks and is safe from any thread
 * (including the UI thread — reading a [kotlinx.coroutines.flow.StateFlow]
 * performs no work).
 */
public sealed interface EngineState {

    /** No model loaded. The state after construction and after [SimilarityEngine.release]. */
    public data object Uninitialized : EngineState

    /** [SimilarityEngine.initialize] is currently loading the model. */
    public data object Initializing : EngineState

    /**
     * Model loaded; queries are possible.
     *
     * @property indexedCount Number of tracks currently in the vector index.
     *   `0` means queries will fail with [EngineError.NotIndexed] until
     *   [SimilarityEngine.indexLibrary] has run.
     */
    public data class Ready(public val indexedCount: Int) : EngineState

    /** Initialization failed. [SimilarityEngine.initialize] may be retried. */
    public data class Failed(public val error: EngineError) : EngineState
}

/**
 * Outcome of a [SimilarityEngine.nextTrack] query.
 *
 * Modeled as a sealed result rather than a nullable id so that "no candidate"
 * and "something went wrong" are distinct, non-throwing outcomes the caller
 * can branch on exhaustively.
 */
public sealed interface NextTrackResult {

    /**
     * The nearest neighbor of the seed track.
     *
     * @property trackId Identifier of the recommended next track.
     * @property similarity Cosine similarity between the seed and the match,
     *   in `[-1, 1]` (higher is more similar).
     */
    public data class Match(
        public val trackId: TrackId,
        public val similarity: Float,
    ) : NextTrackResult

    /**
     * The index contains tracks, but every one of them was excluded by the
     * query's seed/recent/excluded sets.
     */
    public data object NoCandidates : NextTrackResult

    /** The query could not be answered. See [error] for the typed reason. */
    public data class Failure(public val error: EngineError) : NextTrackResult
}

/**
 * Summary of one [SimilarityEngine.indexLibrary] run.
 *
 * @property indexed Number of tracks successfully embedded and upserted.
 * @property failed Number of tracks that could not be indexed.
 * @property errors Per-track failure reasons for the [failed] tracks.
 */
public data class IndexReport(
    public val indexed: Int,
    public val failed: Int,
    public val errors: Map<TrackId, EngineError> = emptyMap(),
)

/**
 * Carrier that lets an [EngineError] travel inside a [kotlin.Result] failure
 * without collapsing to a stringly-typed exception.
 *
 * Backends wrap their typed errors in this exception when returning
 * `Result.failure(...)`; the engine unwraps it with a safe cast. Any OTHER
 * throwable found in a failed `Result` is converted to
 * [EngineError.BackendFailure] as a fallback.
 */
public class SmartEngineException(
    public val error: EngineError,
) : Exception(error.toString(), (error as? EngineError.BackendFailure)?.cause)
