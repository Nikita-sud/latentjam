/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import org.koin.core.module.Module
import org.koin.dsl.module

/** iOS audio backend backed by the ONNX Runtime provider installed by the Swift host. */
internal class IosEmbeddingBackend(
    private val config: SmartEngineConfig,
) : EmbeddingBackend {

    override suspend fun loadModel(): Result<Unit> {
        val provider = IosInferenceRegistry.current()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val error = provider.loadAudio()
        return if (error == null) Result.success(Unit) else backendFailure(error)
    }

    override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> {
        val provider = IosInferenceRegistry.current()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val uri = descriptor.audioUri
            ?: return backendFailure("No audioUri for ${descriptor.id.value}")
        val output = provider.embedAudio(uri, descriptor.durationMs ?: -1L, config.embeddingDim)
            ?: return backendFailure("Could not decode or embed ${descriptor.id.value}")
        if (output.size != config.embeddingDim || !output.all(Float::isFinite)) {
            return backendFailure(
                "Model produced an invalid embedding (size=${output.size}, " +
                    "expected=${config.embeddingDim})",
            )
        }
        return Result.success(output)
    }

    override fun close(): Unit = Unit

    private fun <T> backendFailure(message: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.BackendFailure(message)))
}

public actual fun smartEngineBackendModule(): Module = module {
    single<EmbeddingBackend> { IosEmbeddingBackend(config = get()) }
}
