/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

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
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Apple Lossless through the extractor wrapper and Apple's decoder (issue #34). The expected PCM is
 * ffmpeg's own decode of the same files: two independent decoders must agree bit for bit.
 */
class AlacPcmTest {
    @Test
    fun sixteenBitAlacDecodesToTheSamePcmAsAnIndependentDecoder() {
        val result = extract("alac16.m4a")
        assertEquals(MimeTypes.AUDIO_RAW, result.audioFormat.sampleMimeType)
        assertEquals(C.ENCODING_PCM_16BIT, result.audioFormat.pcmEncoding)
        assertEquals(2, result.audioFormat.channelCount)
        assertEquals(44100, result.audioFormat.sampleRate)
        assertContentEquals(resource("alac16.pcm"), result.pcm)
    }

    @Test
    fun twentyFourBitAlacDecodesToPacked24BitPcm() {
        val result = extract("alac24.m4a")
        assertEquals(C.ENCODING_PCM_24BIT, result.audioFormat.pcmEncoding)
        assertContentEquals(resource("alac24.pcm"), result.pcm)
    }

    @Test
    fun aCoverStoredAsAVideoTrackLeavesTheAudioIntact() {
        // The file in issue #34 carried its cover as a second, video track and its index at the end.
        val result = extract("alac16_cover.m4a")
        assertContentEquals(resource("alac16.pcm"), result.pcm)
    }

    @Test
    fun damagedPacketsFailCleanlyInsteadOfCrashing() {
        val result = extract("alac16.m4a")
        val decoder = assertNotNull(AlacPacketDecoder.open(result.cookie))
        val random = Random(34)
        decoder.use {
            repeat(2_000) {
                val packet = result.packets[random.nextInt(result.packets.size)].copyOf()
                when (random.nextInt(3)) {
                    0 -> repeat(1 + random.nextInt(8)) { packet[random.nextInt(packet.size)] = random.nextInt(256).toByte() }
                    1 -> random.nextBytes(packet)
                    else -> packet.fill(0xFF.toByte())
                }
                val length = 1 + random.nextInt(packet.size)
                val pcm = decoder.decode(packet, 0, length)
                if (pcm != null) assertTrue(pcm.length in 0..decoder.maxOutputBytes)
            }
        }
    }

    @Test
    fun anUnreadableConfigurationOpensNoDecoder() {
        assertNull(AlacPacketDecoder.open(ByteArray(10)))
        assertNull(AlacPacketDecoder.open(ByteArray(24))) // frame length 0, bit depth 0
        val badDepth = ByteArray(24).also { it[3] = 16; it[5] = 7; it[9] = 2; it[23] = 0x44 }
        assertNull(AlacPacketDecoder.open(badDepth))
    }

    @Test
    fun aFrameLengthShorterThanThePredictorsWarmUpOpensNoDecoder() {
        // The decoder's predictor writes its warm-up samples (up to 31 of them, a count the packet gives) whatever the
        // frame length, into buffers of one frame: a shorter frame is a write past them.
        val real = extract("alac16.m4a").cookie
        fun withFrameLength(frames: Int) = real.copyOf().also { it[0] = 0; it[1] = 0; it[2] = 0; it[3] = frames.toByte() }
        for (frames in listOf(1, 4, 31)) assertNull(AlacPacketDecoder.open(withFrameLength(frames)), "frame length $frames")
        assertNotNull(AlacPacketDecoder.open(withFrameLength(32))).close()
    }

    @Test
    fun surroundAlacIsLeftToThePlatform() {
        // Apple orders 5.1 as C L R Ls Rs LFE, Android as FL FR FC LFE BL BR: decoded here it would play on the wrong
        // speakers, so more than two channels pass through untouched.
        val result = extract("alac16.m4a")
        val surround = result.cookie.copyOf().also { it[9] = 6 }
        val alac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_ALAC).setChannelCount(6).setSampleRate(44100)
            .setInitializationData(listOf(surround)).build()
        val inner = Track()
        AlacPcmTrackOutput(inner).format(alac)
        assertEquals(MimeTypes.AUDIO_ALAC, inner.format?.sampleMimeType)
        val stereo = Track()
        AlacPcmTrackOutput(stereo).format(alac.buildUpon().setChannelCount(2).setInitializationData(listOf(result.cookie)).build())
        assertEquals(MimeTypes.AUDIO_RAW, stereo.format?.sampleMimeType)
    }

    @Test
    fun aSampleSizeNoAlacFrameCanHaveIsRefusedBeforeItIsAllocated() {
        val result = extract("alac16.m4a")
        val output = AlacPcmTrackOutput(Track())
        output.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_ALAC).setChannelCount(2).setSampleRate(44100)
            .setInitializationData(listOf(result.cookie)).build())
        val claimed = 512 * 1024 * 1024
        val failure = runCatching { output.sampleData(ParsableByteArray(ByteArray(16)), claimed, TrackOutput.SAMPLE_DATA_PART_MAIN) }
        assertTrue(failure.exceptionOrNull() is androidx.media3.common.ParserException, "${failure.exceptionOrNull()}")
    }

    private class Extracted(
        val audioFormat: Format,
        val pcm: ByteArray,
        val cookie: ByteArray,
        val packets: List<ByteArray>,
    )

    /** Runs [name] through Mp4Extractor twice: wrapped (the PCM) and bare (the ALAC packets). */
    private fun extract(name: String): Extracted {
        val bytes = resource(name)
        val wrapped = Capture().also { run(AlacPcmExtractor(Mp4Extractor()), bytes, it) }
        val bare = Capture().also { run(Mp4Extractor(), bytes, it) }
        val audio = wrapped.tracks.values.single { it.format?.let { f -> MimeTypes.isAudio(f.sampleMimeType) } == true }
        val bareAudio = bare.tracks.values.single { it.format?.sampleMimeType == MimeTypes.AUDIO_ALAC }
        return Extracted(
            audioFormat = checkNotNull(audio.format),
            pcm = audio.data.toByteArray(),
            cookie = checkNotNull(bareAudio.format).initializationData.first(),
            packets = bareAudio.samples(),
        )
    }

    private fun run(extractor: Extractor, bytes: ByteArray, output: Capture) {
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
    }

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
        private val sizes = ArrayList<Int>()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val read = input.read(buffer, 0, length)
            if (read == C.RESULT_END_OF_INPUT) return read
            data.write(buffer, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length)
            data.readBytes(buffer, 0, length)
            this.data.write(buffer)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertEquals(0, offset)
            sizes += size
        }

        /** The samples, cut from the written bytes by their sizes. */
        fun samples(): List<ByteArray> {
            val all = data.toByteArray()
            var at = 0
            return sizes.map { size -> all.copyOfRange(at, at + size).also { at += size } }
        }
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream("alac/$name")) { "missing alac/$name" }.use { it.readBytes() }
}
