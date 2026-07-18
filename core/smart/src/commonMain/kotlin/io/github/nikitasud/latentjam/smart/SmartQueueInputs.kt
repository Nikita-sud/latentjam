/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import org.koin.core.module.Module

/** Persisted text-index key. Bumping it invalidates cached metadata vectors. */
internal const val TEXT_INDEX_VERSION: String = "minilm-l6-v2-int8"

/**
 * Supplies the packed semantic-descriptor asset. A seam rather than a direct asset read so the
 * chain's inputs stay platform-agnostic and tests can hand over bytes directly.
 */
public interface DescriptorSource {
    /** @return the packed asset, or null when it is not bundled on this platform. */
    public suspend fun read(): ByteArray?
}

/**
 * Wall-clock reading for the predictor's time features.
 *
 * The encoder was trained with hour-of-day and day-of-week inputs, so the same seed can lead
 * somewhere different on a Tuesday morning than a Friday night. [Unknown] pins them to a neutral
 * midweek evening, which is what platforms without a clock binding get.
 */
public interface SmartClock {
    public fun timeFeatures(): FloatArray

    public companion object Unknown : SmartClock {
        override fun timeFeatures(): FloatArray = PredictorRuntime.timeFeatures(20, 2)
    }
}

/**
 * Koin bindings for the chain's platform inputs: the descriptor asset and the clock.
 */
public expect fun smartChainInputsModule(): Module
