/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.CRC32
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Times the decoding that audio indexing does, on the device's own music, without touching the app.
 *
 * Needs the audio permission granted to this test package (`adb shell pm grant
 * io.github.nikitasud.latentjam.smart.test android.permission.READ_MEDIA_AUDIO`); skipped without it or
 * without music. Takes `tracks` (default 40) evenly spaced tracks from MediaStore and decodes the three
 * windows the embedding backend asks for (20/50/80 % of the track, 10 s at 32 kHz).
 *
 * Output, one logcat line per path: `LJ_DECODE path=… tracks=… windows=… ms_per_track=…` and each stage's
 * milliseconds per window.
 */
class AudioDecodeBenchmarkDeviceTest {

    @Test
    fun decodeLikeIndexing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val count = InstrumentationRegistry.getArguments().getString("tracks")?.toIntOrNull() ?: 40
        val tracks = runCatching { musicTracks(context.contentResolver) }.getOrDefault(emptyList())
        assumeTrue("no readable music (grant READ_MEDIA_AUDIO)", tracks.isNotEmpty())
        val step = (tracks.size / count).coerceAtLeast(1)
        val sample = tracks.filterIndexed { index, _ -> index % step == 0 }.take(count)
        val decoder = AndroidAudioDecoder(context)

        // Round 0 warms the process and the codecs; round 1 is reported.
        repeat(2) { round ->
            val stages = DecodeStageTimes()
            val crc = CRC32()
            val started = System.nanoTime()
            var failed = 0
            for ((uri, durationMs) in sample) {
                for (startMs in windowStartsMs(durationMs)) {
                    val result = decoder.decodeWindowMono(
                        uri = uri, startMs = startMs, targetSampleRate = SAMPLE_RATE, targetSamples = WINDOW_SAMPLES,
                        isCancelled = { false }, stages = stages,
                    )
                    if (result is AudioDecodeResult.Success) crc.add(result.waveform) else failed++
                }
            }
            if (round == 1) report("per-window", sample.size, System.nanoTime() - started, stages, failed, crc.value)
        }

        // Tracks decoded ahead on background threads, as OnnxEmbeddingBackend.embedEach does, taken in order.
        for (workers in listOf(1, 2, 3, 4)) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(workers) { task ->
                Thread({
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                }, "bench-decode")
            }
            val crc = CRC32()
            var failed = 0
            val started = System.nanoTime()
            val futures = sample.map { (uri, durationMs) ->
                pool.submit<List<AudioDecodeResult>> {
                    windowStartsMs(durationMs).map { startMs ->
                        decoder.decodeWindowMono(
                            uri = uri, startMs = startMs, targetSampleRate = SAMPLE_RATE,
                            targetSamples = WINDOW_SAMPLES, isCancelled = { false },
                        )
                    }
                }
            }
            for (future in futures) {
                for (result in future.get()) {
                    if (result is AudioDecodeResult.Success) crc.add(result.waveform) else failed++
                }
            }
            val elapsed = System.nanoTime() - started
            pool.shutdown()
            println(
                "LJ_DECODE path=parallel workers=$workers tracks=${sample.size} failed=$failed crc=${crc.value.toString(16)} " +
                    "ms_per_track=${"%.0f".format(elapsed / 1e6 / sample.size)} cores=${Runtime.getRuntime().availableProcessors()}",
            )
        }
    }

    /** The decoded samples' raw bits, so two decoders can be shown to produce the same audio. */
    private fun CRC32.add(waveform: FloatArray) {
        val bytes = java.nio.ByteBuffer.allocate(waveform.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(waveform)
        update(bytes.array())
    }

    private fun report(path: String, tracks: Int, elapsedNanos: Long, s: DecodeStageTimes, failed: Int, crc: Long) {
        fun perWindow(nanos: Long) = "%.1f".format(nanos / 1e6 / s.windows.coerceAtLeast(1))
        println(
            "LJ_DECODE path=$path tracks=$tracks windows=${s.windows} failed=$failed crc=${crc.toString(16)} " +
                "ms_per_track=${"%.0f".format(elapsedNanos / 1e6 / tracks)} per_window_ms: open=${perWindow(s.open)} " +
                "extractor=${perWindow(s.extractor)} lookup=${perWindow(s.lookup)} start=${perWindow(s.start)} " +
                "input_wait=${perWindow(s.inputWait)} read=${perWindow(s.read)} output_wait=${perWindow(s.outputWait)} " +
                "convert=${perWindow(s.convert)} resample=${perWindow(s.resample)} release=${perWindow(s.release)} " +
                "sum=${perWindow(s.total)}",
        )
    }

    private fun musicTracks(resolver: android.content.ContentResolver): List<Pair<android.net.Uri, Long>> {
        val tracks = ArrayList<Pair<android.net.Uri, Long>>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                tracks += ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id) to cursor.getLong(1)
            }
        }
        return tracks
    }

    /** The embedding backend's windows (OnnxEmbeddingBackend.windowStartsMs). */
    private fun windowStartsMs(durationMs: Long): List<Long> {
        if (durationMs <= WINDOW_MS) return listOf(0L)
        val span = durationMs - WINDOW_MS
        return listOf(0.2, 0.5, 0.8).map { fraction -> (span * fraction).toLong() }
    }

    private companion object {
        const val SAMPLE_RATE = 32_000
        const val WINDOW_SAMPLES = 320_000
        const val WINDOW_MS = WINDOW_SAMPLES * 1000L / SAMPLE_RATE
    }
}
