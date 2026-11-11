/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * Apple Lossless (ALAC) for Android, which has no platform decoder for it (issue #34).
 *
 * Without one, Media3 selects no audio renderer for an ALAC track and plays the file silently with
 * its clock running. Rather than adding a second audio renderer beside the stock one, which carries
 * the crossfade and loudness bookkeeping, ALAC is decoded as it is read: each extractor's ALAC track
 * reaches the player as raw PCM, which the stock renderer plays like any other track.
 */
@OptIn(UnstableApi::class)
internal class AlacPcmExtractorsFactory(
    private val inner: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = inner.createExtractors().wrap()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        inner.createExtractors(uri, responseHeaders).wrap()

    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory =
        AlacPcmExtractorsFactory(inner.experimentalSetTextTrackTranscodingEnabled(enabled))

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory =
        AlacPcmExtractorsFactory(inner.setSubtitleParserFactory(factory))

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): ExtractorsFactory =
        AlacPcmExtractorsFactory(inner.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs))

    private fun Array<Extractor>.wrap(): Array<Extractor> = Array(size) { AlacPcmExtractor(this[it]) }
}

/** [inner], with every ALAC track it outputs decoded to PCM. */
@OptIn(UnstableApi::class)
internal class AlacPcmExtractor(private val inner: Extractor) : Extractor {
    private val tracks = ArrayList<AlacPcmTrackOutput>()

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
    override fun getSniffFailureDetails(): List<SniffFailure> = inner.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_UNKNOWN) return track
                return AlacPcmTrackOutput(track).also(tracks::add)
            }

            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        // Bytes of a packet cut off by the seek never get their metadata; drop them.
        tracks.forEach(AlacPcmTrackOutput::discardPending)
        inner.seek(position, timeUs)
    }

    override fun release() {
        tracks.forEach(AlacPcmTrackOutput::release)
        tracks.clear()
        inner.release()
    }

    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

/**
 * Passes everything through, except that an ALAC format becomes raw PCM and each ALAC packet is decoded
 * when its metadata arrives. Packet bytes are held until then: the extractor names a packet's size only
 * in [sampleMetadata], after its data.
 */
@OptIn(UnstableApi::class)
internal class AlacPcmTrackOutput(private val inner: TrackOutput) : TrackOutput {
    private var decoder: AlacPacketDecoder? = null
    private var pending = ByteArray(0)
    private var pendingLength = 0
    private val scratch = ParsableByteArray()

    override fun format(format: Format) {
        decoder?.close()
        decoder = null
        if (format.sampleMimeType != MimeTypes.AUDIO_ALAC) {
            inner.format(format)
            return
        }
        val cookie = format.initializationData.firstOrNull()
        val opened = cookie?.let(AlacPacketDecoder::open)
        if (opened == null) {
            // Unreadable configuration: pass it on unchanged, and the player reports no decoder for it.
            inner.format(format)
            return
        }
        decoder = opened
        inner.format(
            format.buildUpon()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setCodecs(null)
                .setInitializationData(null)
                .setPcmEncoding(opened.pcmEncoding)
                .setChannelCount(opened.channels)
                .setSampleRate(opened.sampleRate)
                .setMaxInputSize(opened.maxOutputBytes)
                .build(),
        )
    }

    override fun durationUs(durationUs: Long) = inner.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (decoder == null) return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
        ensureRoom(length)
        val read = input.read(pending, pendingLength, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw java.io.EOFException()
        }
        pendingLength += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (decoder == null) return inner.sampleData(data, length, sampleDataPart)
        ensureRoom(length)
        data.readBytes(pending, pendingLength, length)
        pendingLength += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val decoder = decoder ?: return inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        // The packet is the [size] bytes that end [offset] bytes before the newest data.
        val end = pendingLength - offset
        val start = end - size
        if (start < 0 || end < start || cryptoData != null) throw ParserException.createForMalformedContainer("ALAC packet", null)
        val pcm = decoder.decode(pending, start, size)
            ?: throw ParserException.createForMalformedContainer("Undecodable ALAC packet", null)
        scratch.reset(pcm.bytes, pcm.length)
        inner.sampleData(scratch, pcm.length, TrackOutput.SAMPLE_DATA_PART_MAIN)
        inner.sampleMetadata(timeUs, flags or C.BUFFER_FLAG_KEY_FRAME, pcm.length, 0, null)
        // Keep only the bytes after the packet: they belong to the next one.
        System.arraycopy(pending, end, pending, 0, pendingLength - end)
        pendingLength -= end
    }

    fun discardPending() {
        pendingLength = 0
    }

    fun release() {
        decoder?.close()
        decoder = null
        pendingLength = 0
    }

    private fun ensureRoom(extra: Int) {
        val needed = pendingLength + extra
        if (needed > pending.size) pending = pending.copyOf(maxOf(needed, pending.size * 2, 8192))
    }
}

/** One track's native ALAC decoder; [decode] returns interleaved little-endian PCM. */
internal class AlacPacketDecoder private constructor(private var handle: Long) : AutoCloseable {
    val frameLength: Int
    val bitDepth: Int
    val channels: Int
    val sampleRate: Int

    init {
        val config = checkNotNull(NativeAlacJni.config(handle))
        frameLength = config[0]
        bitDepth = config[1]
        channels = config[2]
        sampleRate = config[3]
    }

    /** 16-bit stays 16-bit; Apple's decoder packs 20- and 24-bit into three bytes, and 32-bit into four. */
    val pcmEncoding: Int = when (bitDepth) {
        16 -> C.ENCODING_PCM_16BIT
        20, 24 -> C.ENCODING_PCM_24BIT
        else -> C.ENCODING_PCM_32BIT
    }

    private val bytesPerSample = when (bitDepth) {
        16 -> 2
        20, 24 -> 3
        else -> 4
    }

    val maxOutputBytes: Int = frameLength * channels * bytesPerSample
    private val output = ByteArray(maxOutputBytes)
    private var input = ByteArray(0)

    class Pcm(val bytes: ByteArray, val length: Int)

    /** The PCM of the packet at [offset] in [source], or null when it cannot be decoded. */
    fun decode(source: ByteArray, offset: Int, length: Int): Pcm? {
        check(handle != 0L) { "ALAC decoder closed" }
        if (length < 1) return null
        if (input.size < length) input = ByteArray(length)
        System.arraycopy(source, offset, input, 0, length)
        val written = NativeAlacJni.decode(handle, input, length, output)
        return if (written < 0) null else Pcm(output, written)
    }

    override fun close() {
        if (handle != 0L) NativeAlacJni.destroy(handle)
        handle = 0
    }

    companion object {
        /** A decoder for [cookie] (the track's ALAC configuration, as the container stores it), or null. */
        fun open(cookie: ByteArray): AlacPacketDecoder? {
            val handle = NativeAlacJni.create(cookie)
            return if (handle == 0L) null else AlacPacketDecoder(handle)
        }
    }
}

internal object NativeAlacJni {
    init {
        val host = System.getProperty("latentjam.eq.hostLibrary")
        if (host != null) System.load(host) else System.loadLibrary("lj_equalizer")
    }
    external fun create(cookie: ByteArray): Long
    external fun config(handle: Long): IntArray?
    external fun decode(handle: Long, input: ByteArray, length: Int, output: ByteArray): Int
    external fun destroy(handle: Long)
}
