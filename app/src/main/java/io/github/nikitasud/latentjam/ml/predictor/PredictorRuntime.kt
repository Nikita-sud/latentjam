/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.predictor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nikitasud.latentjam.ml.OrtAssets
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the two predictor ONNX sessions (state encoder + scorer @ N=100) and exposes a
 * thin batch=1 API so the recommendation engine can stay pure-Kotlin.
 *
 * The session-feature dimension that scoring_v1 was trained on is 4. The Kotlin recorder
 * always builds a 5-vector (so newer checkpoints with `mean_played_pct` work without code
 * changes); we slice down to whatever the loaded graph wants when the input shape is read
 * out of the ONNX session at load time.
 *
 * `OrtEnvironment` is resolved lazily inside `ensureLoaded()` so the JNI library only loads
 * when a recommendation actually fires — startup never touches it.
 */
@Singleton
class PredictorRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var ortEnvironment: OrtEnvironment? = null
    @Volatile private var stateSession: OrtSession? = null
    @Volatile private var scorerSession: OrtSession? = null
    @Volatile private var sessionFeatDim: Int = 5
    @Volatile private var historyTokenDim: Int = EMBEDDING_DIM

    fun ensureLoaded() {
        if (stateSession != null && scorerSession != null) return
        synchronized(this) {
            val env = ortEnvironment ?: OrtEnvironment.getEnvironment().also { ortEnvironment = it }
            if (stateSession == null) {
                val bytes = OrtAssets.readAsset(context, STATE_ASSET)
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                val s = env.createSession(bytes, opts)
                sessionFeatDim = s.inputInfo["session_features"]?.let {
                    val info = it.info as ai.onnxruntime.TensorInfo
                    info.shape[1].toInt()
                } ?: 5
                historyTokenDim = s.inputInfo["history_small"]?.let {
                    val info = it.info as ai.onnxruntime.TensorInfo
                    info.shape[2].toInt()
                } ?: EMBEDDING_DIM
                stateSession = s
            }
            if (scorerSession == null) {
                val bytes = OrtAssets.readAsset(context, SCORER_ASSET)
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                scorerSession = env.createSession(bytes, opts)
            }
        }
    }

    /** Returns the session-feature dim baked into the loaded state-encoder ONNX. */
    fun sessionFeatDim(): Int {
        ensureLoaded()
        return sessionFeatDim
    }

    /** Returns the per-token history dim (D for v1, D+1 if `use_played_pct=True`). */
    fun historyTokenDim(): Int {
        ensureLoaded()
        return historyTokenDim
    }

    fun encodeState(features: PredictorFeatures): FloatArray {
        ensureLoaded()
        val sess = requireNotNull(stateSession)
        val env = requireNotNull(ortEnvironment)
        val hsTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features.historySmall),
            longArrayOf(1, CONTEXT_K.toLong(), historyTokenDim.toLong()),
        )
        val hmTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features.historyMedium),
            longArrayOf(1, EMBEDDING_DIM.toLong()),
        )
        val hlTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features.historyLarge),
            longArrayOf(1, EMBEDDING_DIM.toLong()),
        )
        val tfTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features.timeFeatures),
            longArrayOf(1, features.timeFeatures.size.toLong()),
        )
        val sfTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features.sessionFeatures),
            longArrayOf(1, sessionFeatDim.toLong()),
        )
        try {
            val inputs = mapOf(
                "history_small" to hsTensor,
                "history_medium" to hmTensor,
                "history_large" to hlTensor,
                "time_features" to tfTensor,
                "session_features" to sfTensor,
            )
            sess.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<FloatArray>
                return out[0]
            }
        } finally {
            hsTensor.close()
            hmTensor.close()
            hlTensor.close()
            tfTensor.close()
            sfTensor.close()
        }
    }

    /**
     * Score [state] against [candidates]. Caller is responsible for zero-padding to exactly
     * [SCORER_N] candidates and masking padded slots back to `-inf` after the call.
     */
    fun score(state: FloatArray, candidates: Array<FloatArray>): FloatArray {
        ensureLoaded()
        require(candidates.size == SCORER_N) {
            "scorer expects exactly $SCORER_N candidates, got ${candidates.size}"
        }
        val sess = requireNotNull(scorerSession)
        val env = requireNotNull(ortEnvironment)
        val flatCandidates = FloatArray(SCORER_N * EMBEDDING_DIM)
        for (i in candidates.indices) {
            System.arraycopy(candidates[i], 0, flatCandidates, i * EMBEDDING_DIM, EMBEDDING_DIM)
        }
        val stateTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(state),
            longArrayOf(1, EMBEDDING_DIM.toLong()),
        )
        val candTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flatCandidates),
            longArrayOf(1, SCORER_N.toLong(), EMBEDDING_DIM.toLong()),
        )
        try {
            sess.run(mapOf("state" to stateTensor, "candidates" to candTensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<FloatArray>
                return out[0]
            }
        } finally {
            stateTensor.close()
            candTensor.close()
        }
    }

    companion object {
        const val STATE_ASSET = "ml/predictor_state.onnx"
        const val SCORER_ASSET = "ml/predictor_scorer_n100.onnx"
        const val CONTEXT_K = 4
        const val EMBEDDING_DIM = 512
        const val SCORER_N = 100
    }
}

/** Feature bundle ready to feed the state encoder. */
data class PredictorFeatures(
    /** (4, D) or (4, D+1) flattened row-major. */
    val historySmall: FloatArray,
    val historyMedium: FloatArray, // (D,)
    val historyLarge: FloatArray,  // (D,)
    val timeFeatures: FloatArray,  // (5,)
    val sessionFeatures: FloatArray, // (sessionFeatDim,)
) {
    /**
     * True if `historySmall` has at least one non-zero element — i.e. some completed-play
     * embedding (current or past session) is filled in. Used as a cold-start gate.
     */
    fun hasHistorySmall(): Boolean {
        for (v in historySmall) if (v != 0f) return true
        return false
    }
}
