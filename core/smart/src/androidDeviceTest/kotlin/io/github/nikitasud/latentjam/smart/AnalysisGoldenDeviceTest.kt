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
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The analysis goldens (docs/analysis-094-plan.md §2.2, G-a and the track rows of G-c) on an explicit test corpus,
 * never the device's music library.
 *
 * The corpus is pushed into this test package's files (`adb push corpus /data/local/tmp/ljcorpus`, then
 * `adb shell run-as io.github.nikitasud.latentjam.smart.test cp -r /data/local/tmp/ljcorpus files/corpus`); the test is
 * skipped without it. It writes `files/golden-<label>.tsv`, one line per decoded window (`W file startMs crc`) and per
 * track vector as analysis computes it in chunks of eight (`V file crc` or `F file error`), and logs the pass's time
 * per track. Two runs of the same build must give the same file; a change that claims identical bytes must too.
 */
class AnalysisGoldenDeviceTest {

    @Test
    fun corpusGoldens() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.targetContext
        val corpus = File(testContext.filesDir, "corpus")
        val files = corpus.listFiles()?.filter { it.isFile && it.name != "LICENSES.tsv" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no corpus in ${corpus.path}", files.isNotEmpty())
        val label = InstrumentationRegistry.getArguments().getString("label") ?: "run"
        val appContext = testContext.createPackageContext(APP_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        val context = object : ContextWrapper(testContext) {
            override fun getAssets() = appContext.assets
        }
        val tracks = files.map { file ->
            TrackDescriptor(id = TrackId(file.name), durationMs = durationMs(file), audioUri = Uri.fromFile(file).toString())
        }
        val out = StringBuilder()

        val decoder = AndroidAudioDecoder(context)
        val decodeStarted = System.nanoTime()
        for (track in tracks) {
            for (startMs in windowStartsMs(track.durationMs)) {
                val result = decoder.decodeWindowMono(
                    uri = Uri.parse(track.audioUri), startMs = startMs, targetSampleRate = SAMPLE_RATE,
                    targetSamples = WINDOW_SAMPLES, isCancelled = { false },
                )
                val value = when (result) {
                    is AudioDecodeResult.Success -> crcOf(result.waveform).toString(16) + " " + result.validSamples
                    is AudioDecodeResult.InvalidAudio -> "invalid"
                    is AudioDecodeResult.Unavailable -> "unavailable"
                }
                out.append("W\t").append(track.id.value).append('\t').append(startMs).append('\t').append(value).append('\n')
            }
        }
        val decodeMs = (System.nanoTime() - decodeStarted) / 1e6

        val backend = OnnxEmbeddingBackend(
            context,
            SmartEngineConfig(embeddingDim = 960, modelLocator = "ml/mnv4_audio.onnx", modelVersion = AUDIO_MODEL_VERSION),
        )
        backend.loadModel().getOrThrow()
        // As the engine runs it: the backend takes the inference lock per window ("lock" = false: no lock, 0.9.3's path).
        if (InstrumentationRegistry.getArguments().getString("lock") != "false") backend.takeInferenceLock(Mutex())
        val vectors = HashMap<String, String>()
        val passStarted = System.nanoTime()
        // As the pass hands them over: each chunk with the next one as its upcoming tracks ("carry" = false keeps
        // 0.9.3's chunk-by-chunk decoding for comparison).
        val carry = InstrumentationRegistry.getArguments().getString("carry") != "false"
        val chunks = tracks.chunked(CHUNK)
        for ((position, chunk) in chunks.withIndex()) {
            val upcoming = if (carry) chunks.getOrNull(position + 1).orEmpty() else emptyList()
            backend.embedEach(chunk, upcoming) { track, result ->
                vectors[track.id.value] = result.fold(
                    onSuccess = { "V\t${crcOf(it).toString(16)}" },
                    onFailure = { "F\t${(it as? SmartEngineException)?.error?.let { e -> e::class.simpleName } ?: it::class.simpleName}" },
                )
            }
        }
        val passMs = (System.nanoTime() - passStarted) / 1e6
        // A press's seed embed (its own decode lane) gives the analysis pass's vector.
        for (track in tracks.take(SEED_CHECKS)) {
            val seed = backend.embed(track).fold(
                onSuccess = { "V\t${crcOf(it).toString(16)}" },
                onFailure = { "F\t${(it as? SmartEngineException)?.error?.let { e -> e::class.simpleName } ?: it::class.simpleName}" },
            )
            check(seed == vectors.getValue(track.id.value)) { "seed embed of ${track.id.value}: $seed, pass ${vectors[track.id.value]}" }
        }
        backend.close()
        for (track in tracks) {
            val (kind, value) = vectors.getValue(track.id.value).split('\t', limit = 2)
            out.append(kind).append('\t').append(track.id.value).append('\t').append(value).append('\n')
        }
        File(testContext.filesDir, "golden-$label.tsv").writeText(out.toString())
        println(
            "LJ_GOLDEN label=$label carry=$carry tracks=${tracks.size} decode_ms_per_track=${"%.0f".format(decodeMs / tracks.size)} " +
                "pass_ms_per_track=${"%.0f".format(passMs / tracks.size)} cores=${Runtime.getRuntime().availableProcessors()}",
        )
    }

    private fun durationMs(file: File): Long? = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.path)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                release()
            }
        }
    }.getOrNull()

    /** The samples' raw bits, so two builds can be shown to produce the same floats. */
    private fun crcOf(values: FloatArray): Long {
        val bytes = java.nio.ByteBuffer.allocate(values.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values)
        return CRC32().apply { update(bytes.array()) }.value
    }

    /** OnnxEmbeddingBackend.windowStartsMs. */
    private fun windowStartsMs(durationMs: Long?): List<Long> {
        if (durationMs == null || durationMs <= WINDOW_MS) return listOf(0L)
        val span = durationMs - WINDOW_MS
        return listOf(0.2, 0.5, 0.8).map { fraction -> (span * fraction).toLong() }
    }

    private companion object {
        const val APP_PACKAGE = "io.github.nikitasud.latentjam.kmp"
        const val SAMPLE_RATE = 32_000
        const val WINDOW_SAMPLES = 320_000
        const val WINDOW_MS = WINDOW_SAMPLES * 1000L / SAMPLE_RATE
        const val CHUNK = 8
        const val SEED_CHECKS = 24
    }
}
