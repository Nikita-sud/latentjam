/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.di.smartLayoutQualifier
import io.github.nikitasud.latentjam.smart.di.smartTextIndexQualifier
import org.koin.core.module.Module
import org.koin.dsl.module

/** iOS audio backend backed by the ONNX Runtime provider installed by the Swift host. */
internal class IosEmbeddingBackend(
    private val config: SmartEngineConfig,
) : EmbeddingBackend {

    private var lease: IosInferenceLease? = null

    override suspend fun loadModel(): Result<Unit> {
        val existingLease = lease?.takeIf { it.currentProvider() != null }
        if (existingLease == null) {
            lease?.release()
            lease = null
        }
        val activeLease = existingLease ?: IosInferenceRegistry.acquire()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val provider = activeLease.currentProvider() ?: run {
            if (existingLease == null) activeLease.release()
            return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        }
        val error = provider.loadAudio()
        return if (error == null) {
            lease = activeLease
            Result.success(Unit)
        } else {
            if (existingLease == null) activeLease.release()
            backendFailure(error)
        }
    }

    override suspend fun loadSemanticModel(): Result<Unit> {
        val existingLease = lease?.takeIf { it.currentProvider() != null }
        if (existingLease == null) {
            lease?.release()
            lease = null
        }
        val activeLease = existingLease ?: IosInferenceRegistry.acquire()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val provider = activeLease.currentProvider() ?: run {
            if (existingLease == null) activeLease.release()
            return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        }
        val error = provider.loadSemantic()
        return if (error == null) {
            lease = activeLease
            Result.success(Unit)
        } else {
            if (existingLease == null) activeLease.release()
            backendFailure(error)
        }
    }

    override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> {
        val provider = lease?.currentProvider()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val uri = descriptor.audioUri
            ?: return invalidAudio("No audio URI")
        val native = provider.embedAudio(uri, descriptor.durationMs ?: -1L, config.embeddingDim)
        val output = when (native.status) {
            IosAudioEmbeddingStatus.SUCCESS -> native.embedding
                ?: return backendFailure("Native audio provider returned success without output")
            IosAudioEmbeddingStatus.INVALID_AUDIO ->
                return invalidAudio(native.technicalDetail ?: "Audio is not decodable")
            IosAudioEmbeddingStatus.UNAVAILABLE ->
                return audioUnavailable(native.technicalDetail ?: "Audio is temporarily unavailable")
            IosAudioEmbeddingStatus.BACKEND_FAILURE ->
                return backendFailure(native.technicalDetail ?: "Audio inference failed")
        }
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
        val provider = lease?.currentProvider()
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

    override fun close(): Unit {
        lease?.release()
        lease = null
    }

    private fun <T> backendFailure(message: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.BackendFailure(message)))

    private fun <T> audioUnavailable(detail: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.AudioUnavailable(detail)))

    private fun <T> invalidAudio(detail: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.InvalidAudio(detail)))
}

public actual fun smartEngineBackendModule(): Module = module {
    single<EmbeddingBackend> { IosEmbeddingBackend(config = get()) }
    single<IndexStore> { IosFileIndexStore(fileName = "smart_index.bin") }
    single<IndexStore>(smartTextIndexQualifier) {
        IosFileIndexStore(fileName = "smart_text_index.bin")
    }
    single<IndexStore>(smartLayoutQualifier) {
        IosFileIndexStore(fileName = "map_layout.bin")
    }
}
