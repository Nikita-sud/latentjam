/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.SmartEngineException
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS [TextEncoder] — not yet wired to a native inference engine.
 *
 * [encode] returns null, which callers already handle as "no text vector for this track": retrieval
 * falls back to audio-only cosines and the chain's semantic term leans on descriptors alone. The
 * tokenizer it would need is already common code; only the ONNX session is missing.
 */
internal class UnavailableTextEncoder : TextEncoder {

    override suspend fun load(): Result<Unit> =
        Result.failure(SmartEngineException(EngineError.ModelUnavailable))

    override fun encode(metadata: String): FloatArray? = null

    override fun close() = Unit
}

public actual fun smartTextEncoderModule(): Module = module {
    single<TextEncoder> { UnavailableTextEncoder() }
}
