/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.encoder

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nikitasud.latentjam.ml.OrtAssets
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Encoder runtime backed by **ONNX Runtime + QNN HTP** (Qualcomm Hexagon NPU)
 * running the LAION-CLAP HTSAT audio tower (27.5 M params, Apache-2.0, 512-d
 * L2-normalized embeddings).
 *
 * **Input contract**: raw mono float32 PCM at 48 kHz with shape `(B, 480_000)`
 * — ten seconds per window. The mel-spectrogram frontend (STFT via conv1d,
 * Hann window, n_fft=1024, hop=480, 64-mel htk filterbank, log-dB, expand
 * to the 4-chunk fusion bundle) is baked into the ONNX graph. Output:
 * `(B, 512)` L2-normalized embedding.
 *
 * **Backends, in cascade order**:
 *   1. `QNN_HTP` — Hexagon V75 NPU. With `enable_htp_fp16_precision`, ORT
 *      runtime-converts FP32 weights to fp16 and offloads to HTP. Measured
 *      ~58 ms warm per window. HTP-vs-CPU cosine parity ≥ 0.999924 on 10
 *      seeded inputs.
 *   2. `CPU` — ORT's CPU EP. Fallback for non-Qualcomm devices. ~205 ms warm
 *      per window.
 *
 * The HTSAT graph uses a rank-5 window-partition rewrite — upstream HF's
 * rank-6 reshape triggers QNN graphAddNode op-error 6007. See
 * `latentjam-research/scripts/export_clap_audio_onnx.py` for the export.
 *
 * The HTP path requires three non-obvious bits of plumbing wired up around
 * this class:
 *   - `<uses-native-library android:name="libcdsprpc.so" required=false/>` in
 *     the manifest, so FastRPC to the Hexagon DSP is dlopen-able by a non-
 *     system app on Android 13+.
 *   - `useLegacyPackaging = true` on `jniLibs` in `build.gradle`, so AGP
 *     extracts `libQnnHtpV??Skel.so` to disk; the DSP-side dynamic loader
 *     cannot read libs embedded inside an unextracted APK.
 *   - `com.microsoft.onnxruntime:onnxruntime-android-qnn` as the gradle dep
 *     — the `-qnn` flavor of the AAR transitively pulls
 *     `com.qualcomm.qti:qnn-runtime`, which ships the per-Hexagon-arch
 *     `libQnnHtpV??Skel.so` set.
 *
 * `OrtEnvironment` and the session are lazily created on first call to
 * [embed] — QNN graph finalization at session-create takes ~16 s the first
 * time (cached for subsequent app starts), so we don't pay it at app launch.
 */
@Singleton
class EncoderRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var ortEnvironment: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile var backend: Backend = Backend.UNINITIALIZED
        private set

    enum class Backend { UNINITIALIZED, QNN_HTP, CPU }

    fun ensureLoaded() {
        if (session != null) return
        synchronized(this) {
            if (session != null) return
            val env = ortEnvironment
                ?: OrtEnvironment.getEnvironment().also { ortEnvironment = it }
            val modelBytes = OrtAssets.readAsset(context, ENCODER_ASSET)

            val attempted = mutableListOf<Backend>()
            for (target in listOf(Backend.QNN_HTP, Backend.CPU)) {
                attempted.add(target)
                val s = tryCreateSession(env, modelBytes, target)
                if (s != null) {
                    session = s
                    backend = target
                    Timber.i(
                        "EncoderRuntime loaded: backend=%s (cascade tried %s)",
                        target, attempted,
                    )
                    return
                }
            }
            error("EncoderRuntime: all backends failed (tried $attempted)")
        }
    }

    private fun tryCreateSession(
        env: OrtEnvironment,
        modelBytes: ByteArray,
        target: Backend,
    ): OrtSession? = try {
        val opts = OrtSession.SessionOptions()
        when (target) {
            Backend.QNN_HTP -> {
                opts.addQnn(
                    mapOf(
                        "backend_path" to "libQnnHtp.so",
                        "enable_htp_fp16_precision" to "1",
                        "htp_performance_mode" to "burst",
                        "htp_graph_finalization_optimization_mode" to "3",
                        "vtcm_mb" to "8",
                    )
                )
            }
            Backend.CPU -> opts.setIntraOpNumThreads(4)
            Backend.UNINITIALIZED -> error("unreachable")
        }
        val s = env.createSession(modelBytes, opts)
        runProbe(env, s)
        s
    } catch (e: Throwable) {
        Timber.w(e, "EncoderRuntime: %s init failed", target)
        null
    }

    /** One-shot dummy inference. Catches op-level errors and warms the HTP context. */
    private fun runProbe(env: OrtEnvironment, s: OrtSession) {
        val dummy = FloatBuffer.allocate(WINDOW_SAMPLES)
        OnnxTensor.createTensor(env, dummy, longArrayOf(1L, WINDOW_SAMPLES.toLong())).use { tensor ->
            s.run(mapOf(INPUT_NAME to tensor)).use { /* discard */ }
        }
    }

    /**
     * Run the encoder on `batch` consecutive `WINDOW_SAMPLES`-sized FP32
     * windows packed into [windowsFlat]. Returns a `(batch, EMBEDDING_DIM)`
     * array of L2-normalized embeddings.
     *
     * The model has a baked-in batch=1 reshape op, so we call the session
     * once per window rather than resizing the input tensor.
     */
    fun embed(windowsFlat: FloatBuffer, batch: Int): Array<FloatArray> {
        ensureLoaded()
        val s = requireNotNull(session)
        val env = requireNotNull(ortEnvironment)

        windowsFlat.rewind()
        val out = Array(batch) { FloatArray(EMBEDDING_DIM) }
        val shape = longArrayOf(1L, WINDOW_SAMPLES.toLong())
        for (b in 0 until batch) {
            windowsFlat.position(b * WINDOW_SAMPLES)
            val slice = windowsFlat.slice().limit(WINDOW_SAMPLES) as FloatBuffer
            OnnxTensor.createTensor(env, slice, shape).use { input ->
                s.run(mapOf(INPUT_NAME to input)).use { result ->
                    val outTensor = result[0] as OnnxTensor
                    val buf = outTensor.floatBuffer
                    buf.rewind()
                    buf.get(out[b], 0, EMBEDDING_DIM)
                }
            }
        }
        return out
    }

    companion object {
        const val ENCODER_ASSET = "ml/clap_audio.onnx"
        const val INPUT_NAME = "waveform"
        /** 10-s window @ 48 kHz — CLAP's fixed input length. */
        const val WINDOW_SAMPLES = 480_000
        /** Sample rate the encoder expects raw audio at. */
        const val SAMPLE_RATE = 48_000
        const val EMBEDDING_DIM = 512
    }
}
