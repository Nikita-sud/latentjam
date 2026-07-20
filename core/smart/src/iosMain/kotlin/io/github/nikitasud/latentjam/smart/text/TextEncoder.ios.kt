/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.IosInferenceRegistry
import io.github.nikitasud.latentjam.smart.SmartEngineException
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.readBytes
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

/** iOS MiniLM encoder: common WordPiece tokenizer, native ONNX Runtime transformer. */
@OptIn(ExperimentalForeignApi::class)
internal class IosTextEncoder : TextEncoder {

    private var tokenizer: BertWordPieceTokenizer? = null

    override suspend fun load(): Result<Unit> {
        val provider = IosInferenceRegistry.current()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        return try {
            val path = NSBundle.mainBundle.pathForResource(
                name = "text_vocab", ofType = "txt", inDirectory = "ml",
            ) ?: NSBundle.mainBundle.pathForResource(
                name = "text_vocab", ofType = "txt",
            ) ?: return failure("Bundled MiniLM vocabulary is missing")
            val data = NSFileManager.defaultManager.contentsAtPath(path)
                ?: return failure("Bundled MiniLM vocabulary is unreadable")
            val pointer = data.bytes?.reinterpret<ByteVar>()
                ?: return failure("Bundled MiniLM vocabulary is empty")
            val text = pointer.readBytes(data.length.toInt()).decodeToString()
            tokenizer = BertWordPieceTokenizer(
                BertWordPieceTokenizer.parseVocab(text.lineSequence()),
            )
            val error = provider.loadText()
            if (error == null) Result.success(Unit) else failure(error)
        } catch (t: Throwable) {
            tokenizer = null
            failure("Failed to load MiniLM: ${t.message}")
        }
    }

    override fun encode(metadata: String): FloatArray? {
        if (metadata.isBlank()) return null
        val provider = IosInferenceRegistry.current() ?: return null
        val ids = (tokenizer ?: return null).encode(metadata)
        return provider.encodeText(LongArray(ids.size) { ids[it].toLong() })
            ?.takeIf { it.size == TextEncoder.TEXT_DIM && it.all(Float::isFinite) }
    }

    override fun close(): Unit {
        tokenizer = null
    }

    private fun <T> failure(message: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.BackendFailure(message)))
}

public actual fun smartTextEncoderModule(): Module = module {
    single<TextEncoder> { IosTextEncoder() }
}
