/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * Deterministic in-memory [EmbeddingBackend] for tests.
 *
 * Returns the vector registered in [vectors] for the descriptor's id, or a
 * typed [EngineError.BackendFailure] when none is registered. Records call
 * counts so tests can assert on interaction (idempotency, on-the-fly embeds).
 *
 * Like both production backends, [embed] fails with [EngineError.ModelUnavailable]
 * unless a successful [loadModel] came first — tests therefore pin the engine's
 * load-before-embed ordering, not just its call counts.
 */
internal class FakeEmbeddingBackend(
    val vectors: MutableMap<TrackId, FloatArray> = mutableMapOf(),
) : EmbeddingBackend {

    var loadModelResult: Result<Unit> = Result.success(Unit)
    var loadModelCalls: Int = 0
    var embedCalls: Int = 0
    var closed: Boolean = false
    val failures: MutableMap<TrackId, EngineError> = mutableMapOf()
    private var modelLoaded = false

    override suspend fun loadModel(): Result<Unit> {
        loadModelCalls++
        return loadModelResult.onSuccess { modelLoaded = true }
    }

    override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> {
        embedCalls++
        if (!modelLoaded) {
            return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        }
        failures[descriptor.id]?.let { error ->
            return Result.failure(SmartEngineException(error))
        }
        val vector = vectors[descriptor.id]
            ?: return Result.failure(
                SmartEngineException(
                    EngineError.BackendFailure("No fake vector registered for ${descriptor.id.value}"),
                ),
            )
        return Result.success(vector.copyOf())
    }

    override fun close() {
        closed = true
        modelLoaded = false
    }
}
