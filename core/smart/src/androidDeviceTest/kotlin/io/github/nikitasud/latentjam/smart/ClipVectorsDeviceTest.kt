/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.Context
import android.content.ContextWrapper
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Writes the analysis vector of every file in `files/clips` to `files/clips-<label>.f32` (little endian, 960 floats a
 * row, in name order; a failed row is all zeros) and the names to `files/clips-<label>.txt`, for the AUD-02 cluster
 * check (review_ext/aud02). Skipped without clips.
 */
class ClipVectorsDeviceTest {
    @Test
    fun clipVectors() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(testContext.filesDir, "clips").listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no clips", files.isNotEmpty())
        val label = InstrumentationRegistry.getArguments().getString("label") ?: "run"
        val appContext = testContext.createPackageContext("io.github.nikitasud.latentjam.kmp", Context.CONTEXT_IGNORE_SECURITY)
        val context = object : ContextWrapper(testContext) {
            override fun getAssets() = appContext.assets
        }
        val tracks = files.map { file ->
            val duration = runCatching {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.path)
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        release()
                    }
                }
            }.getOrNull()
            TrackDescriptor(id = TrackId(file.name), durationMs = duration, audioUri = Uri.fromFile(file).toString())
        }
        val backend = OnnxEmbeddingBackend(
            context,
            SmartEngineConfig(embeddingDim = 960, modelLocator = "ml/mnv4_audio.onnx", modelVersion = AUDIO_MODEL_VERSION),
        )
        backend.loadModel().getOrThrow()
        backend.takeInferenceLock(Mutex())
        val vectors = HashMap<String, FloatArray>()
        for (chunk in tracks.chunked(8)) {
            backend.embedEach(chunk, emptyList()) { track, result -> vectors[track.id.value] = result.getOrNull() ?: FloatArray(960) }
        }
        backend.close()
        val bytes = ByteBuffer.allocate(tracks.size * 960 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (track in tracks) for (v in vectors.getValue(track.id.value)) bytes.putFloat(v)
        File(testContext.filesDir, "clips-$label.f32").writeBytes(bytes.array())
        File(testContext.filesDir, "clips-$label.txt").writeText(tracks.joinToString("\n") { "${it.id.value}\t${it.durationMs}" })
        println("LJ_CLIPS label=$label rows=${tracks.size}")
    }
}
