/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.di.smartTextIndexQualifier
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
            ?: return audioUnavailable("No audio URI")
        val output = provider.embedAudio(uri, descriptor.durationMs ?: -1L, config.embeddingDim)
            ?: return audioUnavailable("Could not decode or embed audio")
        if (output.size != config.embeddingDim || !output.all(Float::isFinite)) {
            return backendFailure(
                "Model produced an invalid embedding (size=${output.size}, " +
                    "expected=${config.embeddingDim})",
            )
        }
        return Result.success(output)
    }

    override suspend fun classify(embeddings: List<FloatArray>): Result<List<FloatArray>> {
        if (embeddings.isEmpty()) return Result.success(emptyList())
        if (embeddings.any { it.size != config.embeddingDim || !it.all(Float::isFinite) }) {
            return backendFailure("Semantic head received an invalid embedding batch")
        }
        val provider = IosInferenceRegistry.current()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        provider.loadSemantic()?.let { return backendFailure(it) }
        val flattened = FloatArray(embeddings.size * config.embeddingDim)
        for (row in embeddings.indices) {
            embeddings[row].copyInto(flattened, destinationOffset = row * config.embeddingDim)
        }
        val output = provider.classifySemantics(
            embeddings = flattened,
            batchSize = embeddings.size,
            inputDim = config.embeddingDim,
            outputDim = TrackSemantics.OUTPUT_SIZE,
        ) ?: return backendFailure("Semantic classification failed")
        if (
            output.size != embeddings.size * TrackSemantics.OUTPUT_SIZE ||
            !output.all(Float::isFinite)
        ) {
            return backendFailure("Semantic head produced an invalid output")
        }
        return Result.success(
            List(embeddings.size) { row ->
                output.copyOfRange(
                    row * TrackSemantics.OUTPUT_SIZE,
                    (row + 1) * TrackSemantics.OUTPUT_SIZE,
                )
            },
        )
    }

    override fun close(): Unit = Unit

    private fun <T> backendFailure(message: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.BackendFailure(message)))

    private fun <T> audioUnavailable(detail: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.AudioUnavailable(detail)))
}

public actual fun smartEngineBackendModule(): Module = module {
    single<EmbeddingBackend> { IosEmbeddingBackend(config = get()) }
    single<IndexStore> { IosFileIndexStore(fileName = "smart_index.bin") }
    single<IndexStore>(smartTextIndexQualifier) {
        IosFileIndexStore(fileName = "smart_text_index.bin")
    }
}
