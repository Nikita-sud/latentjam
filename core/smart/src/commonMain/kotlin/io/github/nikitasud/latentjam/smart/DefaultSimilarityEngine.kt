/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one production [SimilarityEngine]: orchestrates a platform
 * [EmbeddingBackend] and a [VectorIndex], nothing more.
 *
 * ### Concurrency model
 * Two layers, both required:
 * 1. [dispatcher] — a background dispatcher (single-parallelism in the DI
 *    graph) that keeps model loading, inference, and index scans off the
 *    caller's thread. This is the "never blocks the Compose UI" guarantee.
 * 2. [mutex] — serializes whole OPERATIONS. A single-parallelism dispatcher
 *    alone only serializes between suspension points; without the mutex, a
 *    `nextTrack` could interleave into the middle of an `indexLibrary` batch
 *    while it awaits an `embed`. The mutex makes each public call atomic.
 *
 * Construct via the Koin module ([io.github.nikitasud.latentjam.smart.di.smartEngineModule])
 * or directly in tests; `internal` because the type is an implementation
 * detail — callers depend on [SimilarityEngine] only.
 */
internal class DefaultSimilarityEngine(
    private val backend: EmbeddingBackend,
    private val index: VectorIndex,
    private val config: SmartEngineConfig,
    private val dispatcher: CoroutineDispatcher,
) : SimilarityEngine {

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<EngineState>(EngineState.Uninitialized)

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()

    override suspend fun initialize(): Result<Unit> = withContext(dispatcher) {
        mutex.withLock {
            // Idempotent: a Ready engine stays Ready; the model is not reloaded.
            if (mutableState.value is EngineState.Ready) return@withLock Result.success(Unit)
            mutableState.value = EngineState.Initializing
            backend.loadModel().fold(
                onSuccess = {
                    mutableState.value = EngineState.Ready(indexedCount = index.size)
                    Result.success(Unit)
                },
                onFailure = { throwable ->
                    val error = throwable.toEngineError()
                    mutableState.value = EngineState.Failed(error)
                    Result.failure(SmartEngineException(error))
                },
            )
        }
    }

    override suspend fun indexLibrary(tracks: List<TrackDescriptor>): IndexReport = withContext(dispatcher) {
        mutex.withLock {
            if (mutableState.value !is EngineState.Ready) {
                return@withLock IndexReport(
                    indexed = 0,
                    failed = tracks.size,
                    errors = tracks.associate { it.id to EngineError.ModelUnavailable },
                )
            }
            var indexed = 0
            val errors = LinkedHashMap<TrackId, EngineError>()
            for (track in tracks) {
                backend.embed(track).fold(
                    onSuccess = { vector ->
                        val rejection = validateAndUpsert(track.id, vector)
                        if (rejection == null) indexed++ else errors[track.id] = rejection
                    },
                    onFailure = { throwable -> errors[track.id] = throwable.toEngineError() },
                )
            }
            mutableState.value = EngineState.Ready(indexedCount = index.size)
            IndexReport(indexed = indexed, failed = errors.size, errors = errors)
        }
    }

    override suspend fun nextTrack(context: ListeningContext): NextTrackResult = withContext(dispatcher) {
        mutex.withLock {
            if (mutableState.value !is EngineState.Ready) {
                return@withLock NextTrackResult.Failure(EngineError.ModelUnavailable)
            }
            if (index.size == 0) {
                return@withLock NextTrackResult.Failure(EngineError.NotIndexed)
            }

            val seed = context.seed
            // Prefer the stored vector — no inference cost for indexed seeds.
            val seedVector = index.vector(seed.id)
                ?: backend.embed(seed).getOrElse { throwable ->
                    return@withLock NextTrackResult.Failure(throwable.toEngineError())
                }
            if (seedVector.size != config.embeddingDim) {
                return@withLock NextTrackResult.Failure(
                    EngineError.BackendFailure(
                        "Backend produced a ${seedVector.size}-dim vector for ${seed.id.value}, " +
                            "expected ${config.embeddingDim}",
                    ),
                )
            }

            val excluded = buildSet {
                add(seed.id)
                addAll(context.recentTrackIds)
                addAll(context.excludedTrackIds)
            }
            val best = index.nearest(query = seedVector, k = 1, exclude = excluded).firstOrNull()
                ?: return@withLock NextTrackResult.NoCandidates
            NextTrackResult.Match(trackId = best.trackId, similarity = best.score)
        }
    }

    override suspend fun release() {
        withContext(dispatcher) {
            mutex.withLock {
                backend.close()
                index.clear()
                mutableState.value = EngineState.Uninitialized
            }
        }
    }

    /** Returns `null` on success, or the typed rejection reason. */
    private fun validateAndUpsert(id: TrackId, vector: FloatArray): EngineError? {
        if (vector.size != config.embeddingDim) {
            return EngineError.BackendFailure(
                "Backend produced a ${vector.size}-dim vector for ${id.value}, " +
                    "expected ${config.embeddingDim}",
            )
        }
        index.upsert(id, vector)
        return null
    }

    private fun Throwable.toEngineError(): EngineError =
        (this as? SmartEngineException)?.error
            ?: EngineError.BackendFailure(message ?: "Unknown backend failure", this)
}
