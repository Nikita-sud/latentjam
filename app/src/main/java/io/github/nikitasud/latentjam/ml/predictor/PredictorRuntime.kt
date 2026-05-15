/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * PredictorRuntime.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.ml.predictor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nikitasud.latentjam.ml.MlSettings
import io.github.nikitasud.latentjam.ml.OrtAssets
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Owns the two predictor ONNX sessions (state encoder + scorer @ N=100) and exposes a thin batch=1
 * API so the recommendation engine can stay pure-Kotlin.
 *
 * The session-feature dimension that scoring_v1 was trained on is 4. The Kotlin recorder always
 * builds a 5-vector (so newer checkpoints with `mean_played_pct` work without code changes); we
 * slice down to whatever the loaded graph wants when the input shape is read out of the ONNX
 * session at load time.
 *
 * `OrtEnvironment` is resolved lazily inside `ensureLoaded()` so the JNI library only loads when a
 * recommendation actually fires — startup never touches it.
 */
@Singleton
class PredictorRuntime
@Inject
constructor(@ApplicationContext private val context: Context, private val mlSettings: MlSettings) {
    @Volatile private var ortEnvironment: OrtEnvironment? = null
    @Volatile private var stateSession: OrtSession? = null
    @Volatile private var scorerSession: OrtSession? = null
    @Volatile private var sessionFeatDim: Int = 5
    @Volatile private var historyTokenDim: Int = EMBEDDING_DIM
    @Volatile private var loadedFromUserModel: Boolean = false

    /**
     * True iff the most recently loaded state-encoder session came from [userStateModelFile] rather
     * than the bundled asset. Surfaced for diagnostics.
     */
    fun isUsingUserModel(): Boolean = loadedFromUserModel

    /**
     * Drop the cached sessions so the next [ensureLoaded] re-reads from disk. Call this from
     * RetrainWorker after writing a new user model so playback picks it up without an app restart.
     */
    fun reload() {
        synchronized(this) {
            stateSession?.close()
            scorerSession?.close()
            stateSession = null
            scorerSession = null
            loadedFromUserModel = false
        }
    }

    /**
     * Delete any user-trained state-encoder model and revert to the bundled baseline asset on next
     * [ensureLoaded]. Surfaced via "Reset to baseline" in settings for users whose adaptation went
     * sideways and want to start over.
     */
    fun resetToBaseline() {
        synchronized(this) {
            val userFile = userStateModelFile(context)
            val versionFile = userStateVersionFile(context)
            if (userFile.exists() && !userFile.delete()) {
                Timber.w("PredictorRuntime: failed to delete user model %s", userFile)
            }
            if (versionFile.exists() && !versionFile.delete()) {
                Timber.w("PredictorRuntime: failed to delete user model version %s", versionFile)
            }
        }
        reload()
    }

    fun ensureLoaded() {
        if (stateSession != null && scorerSession != null) return
        synchronized(this) {
            val env = ortEnvironment ?: OrtEnvironment.getEnvironment().also { ortEnvironment = it }
            if (stateSession == null) {
                val (bytes, fromUser) = readStateModelBytes()
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                val s = env.createSession(bytes, opts)
                sessionFeatDim =
                    s.inputInfo["session_features"]?.let {
                        val info = it.info as ai.onnxruntime.TensorInfo
                        info.shape[1].toInt()
                    } ?: 5
                historyTokenDim =
                    s.inputInfo["history_small"]?.let {
                        val info = it.info as ai.onnxruntime.TensorInfo
                        info.shape[2].toInt()
                    } ?: EMBEDDING_DIM
                stateSession = s
                loadedFromUserModel = fromUser
                Timber.i(
                    "PredictorRuntime state loaded: source=%s sessionFeatDim=%d historyTokenDim=%d",
                    if (fromUser) "user" else "asset",
                    sessionFeatDim,
                    historyTokenDim,
                )
            }
            if (scorerSession == null) {
                val bytes = OrtAssets.readAsset(context, SCORER_ASSET)
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                scorerSession = env.createSession(bytes, opts)
            }
        }
    }

    /**
     * Pick the state-encoder bytes to load. Prefers the user-trained file at [userStateModelFile]
     * when:
     * 1. The file exists and is non-empty
     * 2. Its sidecar version matches the currently bundled `mlSettings.predictorVersion` —
     *    otherwise the user model was trained against an incompatible baseline architecture and
     *    gets discarded so we don't crash on a tensor-shape mismatch at inference time
     *
     * The sidecar mismatch path also deletes the stale user files so we don't repeat the
     * compatibility check on every cold start.
     */
    private fun readStateModelBytes(): Pair<ByteArray, Boolean> {
        val userFile = userStateModelFile(context)
        if (userFile.exists() && userFile.length() > 0) {
            val versionFile = userStateVersionFile(context)
            val storedVersion = if (versionFile.exists()) versionFile.readText().trim() else null
            val expectedVersion = mlSettings.predictorVersion
            if (storedVersion == expectedVersion) {
                return userFile.readBytes() to true
            }
            Timber.w(
                "PredictorRuntime: user model version (%s) != baseline (%s); discarding",
                storedVersion ?: "<missing>",
                expectedVersion,
            )
            userFile.delete()
            if (versionFile.exists()) versionFile.delete()
        }
        return OrtAssets.readAsset(context, STATE_ASSET) to false
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
        val hsTensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(features.historySmall),
                longArrayOf(1, CONTEXT_K.toLong(), historyTokenDim.toLong()),
            )
        val hmTensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(features.historyMedium),
                longArrayOf(1, EMBEDDING_DIM.toLong()),
            )
        val hlTensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(features.historyLarge),
                longArrayOf(1, EMBEDDING_DIM.toLong()),
            )
        val tfTensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(features.timeFeatures),
                longArrayOf(1, features.timeFeatures.size.toLong()),
            )
        val sfTensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(features.sessionFeatures),
                longArrayOf(1, sessionFeatDim.toLong()),
            )
        try {
            val inputs =
                mapOf(
                    "history_small" to hsTensor,
                    "history_medium" to hmTensor,
                    "history_large" to hlTensor,
                    "time_features" to tfTensor,
                    "session_features" to sfTensor,
                )
            sess.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST") val out = result[0].value as Array<FloatArray>
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
        val stateTensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(state),
                longArrayOf(1, EMBEDDING_DIM.toLong()),
            )
        val candTensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(flatCandidates),
                longArrayOf(1, SCORER_N.toLong(), EMBEDDING_DIM.toLong()),
            )
        try {
            sess.run(mapOf("state" to stateTensor, "candidates" to candTensor)).use { result ->
                @Suppress("UNCHECKED_CAST") val out = result[0].value as Array<FloatArray>
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

        // User-trained state-encoder lives in private app storage (filesDir, not cacheDir —
        // we don't want the OS to silently evict it under storage pressure). The .version
        // sidecar holds the predictor-version string the model was trained against; on app
        // start we compare it to mlSettings.predictorVersion and discard if they differ.
        private const val USER_MODEL_DIR = "ml"
        private const val USER_STATE_MODEL_FILENAME = "predictor_state_user.onnx"
        private const val USER_STATE_VERSION_FILENAME = "predictor_state_user.version"

        fun userStateModelFile(context: Context): File =
            File(File(context.filesDir, USER_MODEL_DIR), USER_STATE_MODEL_FILENAME).also {
                it.parentFile?.mkdirs()
            }

        fun userStateVersionFile(context: Context): File =
            File(File(context.filesDir, USER_MODEL_DIR), USER_STATE_VERSION_FILENAME).also {
                it.parentFile?.mkdirs()
            }
    }
}

/** Feature bundle ready to feed the state encoder. */
data class PredictorFeatures(
    /** (4, D) or (4, D+1) flattened row-major. */
    val historySmall: FloatArray,
    val historyMedium: FloatArray, // (D,)
    val historyLarge: FloatArray, // (D,)
    val timeFeatures: FloatArray, // (5,)
    val sessionFeatures: FloatArray, // (sessionFeatDim,)
) {
    /**
     * True if `historySmall` has at least one non-zero element — i.e. some completed-play embedding
     * (current or past session) is filled in. Used as a cold-start gate.
     */
    fun hasHistorySmall(): Boolean {
        for (v in historySmall) if (v != 0f) return true
        return false
    }
}
