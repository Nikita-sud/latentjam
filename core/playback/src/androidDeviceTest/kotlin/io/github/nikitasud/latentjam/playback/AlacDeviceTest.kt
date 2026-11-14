/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Apple Lossless on the device's own CPU (issue #34). The host tests run on Apple hosts, whose headers
 * hid the decoder's byte-order bug that broke every stream on Android's ARM targets; this runs where the
 * app does. The expected PCM is ffmpeg's decode of the same files.
 */
class AlacDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun sixteenBitAlacMatchesAnIndependentDecoderBitForBit() {
        val (format, pcm) = extract("alac16.m4a")
        assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
        assertEquals(C.ENCODING_PCM_16BIT, format.pcmEncoding)
        assertArrayEquals(asset("alac16.pcm"), pcm)
    }

    @Test
    fun twentyFourBitAlacMatchesAnIndependentDecoderBitForBit() {
        val (format, pcm) = extract("alac24.m4a")
        assertEquals(C.ENCODING_PCM_24BIT, format.pcmEncoding)
        assertArrayEquals(asset("alac24.pcm"), pcm)
    }

    @Test
    fun theLoudnessScanMeasuresAlacThroughTheAppsDecoder(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "tone3s.m4a")
        try {
            file.writeBytes(asset("tone3s.m4a"))
            // Only where the platform demuxer exposes ALAC (the scan reads through it); the decoder is the app's.
            val exposed = android.media.MediaExtractor().run {
                try {
                    setDataSource(file.path)
                    (0 until trackCount).any { getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) == MimeTypes.AUDIO_ALAC }
                } finally { release() }
            }
            assumeTrue("platform demuxer does not expose ALAC", exposed)
            val result = withTimeout(30_000) { AndroidIntegratedLoudnessMeter(context).measure(Uri.fromFile(file).toString(), 3000) }
            // ffmpeg's ebur128 filter reads this file at -33.1 LUFS.
            assertEquals(-33.1f, checkNotNull(result) { "no measurement" }.lufs, 0.3f)
        } finally {
            file.delete()
        }
    }

    private fun extract(name: String): Pair<Format, ByteArray> {
        val bytes = asset(name)
        val output = Capture()
        val extractor = AlacPcmExtractor(Mp4Extractor())
        extractor.init(output)
        var position = 0L
        val holder = PositionHolder()
        while (true) {
            val input = DefaultExtractorInput(ArrayReader(bytes, position.toInt()), position, bytes.size.toLong())
            var result: Int
            do {
                result = extractor.read(input, holder)
            } while (result == Extractor.RESULT_CONTINUE)
            if (result == Extractor.RESULT_END_OF_INPUT) break
            position = holder.position
        }
        extractor.release()
        val audio = output.tracks.values.single { it.format?.let { f -> MimeTypes.isAudio(f.sampleMimeType) } == true }
        return checkNotNull(audio.format) to audio.data.toByteArray()
    }

    private fun asset(name: String): ByteArray = assets.open("alac/$name").use { it.readBytes() }

    private class ArrayReader(private val bytes: ByteArray, private var position: Int) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            System.arraycopy(bytes, position, buffer, offset, count)
            position += count
            return count
        }
    }

    private class Capture : ExtractorOutput {
        val tracks = LinkedHashMap<Int, Track>()
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track() }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class Track : TrackOutput {
        var format: Format? = null
        val data = ByteArrayOutputStream()
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val read = input.read(buffer, 0, length)
            if (read != C.RESULT_END_OF_INPUT) data.write(buffer, 0, read)
            return read
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length)
            data.readBytes(buffer, 0, length)
            this.data.write(buffer)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
    }
}
