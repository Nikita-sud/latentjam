/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.ByteArraySource
import io.github.nikitasud.latentjam.library.tags.RandomAccessSource
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class AndroidTrackArtworkTest {
    private val song = "content://media/external/audio/media/42/albumart?album=7&v=abc"
    private val audio = "content://media/external/audio/media/42"
    private val album = "content://media/external/audio/albumart/7?v=abc"

    @Test
    fun aDecodableProviderCoverDoesNotReadTheAudioFile() {
        assertEquals("own", loadArtworkWithFallback(song, { "own" }, { error("unnecessary audio read") }))
    }

    @Test
    fun aMissingProviderCoverUsesEmbeddedBeforeTheAlbumsPicture() {
        val reads = mutableListOf<String>()
        assertEquals("embedded", loadArtworkWithFallback(song, {
            reads += it
            throw FileNotFoundException()
        }, {
            reads += it
            "embedded"
        }))
        assertEquals(listOf(song, audio), reads)
    }

    @Test
    fun anEmptyOrUndecodableProviderImageUsesTheEmbeddedPicture() {
        assertEquals("embedded", loadArtworkWithFallback(song, { null }, { "embedded" }))
    }

    @Test
    fun noEmbeddedPictureUsesTheAlbumAtTheSameRevision() {
        val reads = mutableListOf<String>()
        assertEquals("album", loadArtworkWithFallback(song, {
            reads += it
            if (it == album) "album" else null
        }, {
            reads += it
            null
        }))
        assertEquals(listOf(song, audio, album), reads)
    }

    @Test
    fun missingEmbeddedAudioStillAllowsAnAlbumCover() {
        assertEquals("album", loadArtworkWithFallback(song, { if (it == album) "album" else null }, {
            throw FileNotFoundException()
        }))
    }

    @Test
    fun noAlbumIdStillAllowsTheEmbeddedPicture() {
        assertEquals("embedded", loadArtworkWithFallback(song.substringBefore('?'), { null }, { "embedded" }))
    }

    @Test
    fun savedOverridesAndOtherUrisNeverReadEmbeddedArt() {
        for (uri in listOf("file:///app/files/track-covers/abcd.jpg", album, "content://other/image/42")) {
            assertNull(loadArtworkWithFallback<String>(uri, { null }, { error("override must not fall back") }))
        }
    }

    @Test
    fun permissionAndIoFailuresAreNotTreatedAsMissingCovers() {
        assertFailsWith<SecurityException> {
            loadArtworkWithFallback<String>(song, { throw SecurityException("revoked") }, { error("no fallback") })
        }
        assertFailsWith<IOException> {
            loadArtworkWithFallback<String>(song, { null }, { throw IOException("volume disappeared") })
        }
        assertFailsWith<CancellationException> {
            loadArtworkWithFallback<String>(song, { throw CancellationException() }, { error("no fallback") })
        }
    }

    @Test
    fun compressedPicturesHaveAnInclusiveByteLimitAndCannotBeEmpty() {
        assertNull(readArtworkBytes(ByteArrayInputStream(byteArrayOf()), maxBytes = 3))
        assertContentEquals(byteArrayOf(1, 2, 3), readArtworkBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3)), maxBytes = 3))
        assertNull(readArtworkBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 3))
    }

    @Test
    fun aLargerProviderStreamStopsAtTheLimitPlusOneByte() {
        var read = 0
        val input = object : InputStream() {
            override fun read(): Int { read++; return 1 }
        }
        assertNull(readArtworkBytes(input, maxBytes = 20))
        assertEquals(21, read)
    }

    @Test
    fun metadataReadLimitsApplyBeforeTheSourceAllocates() {
        var reads = 0
        val source = object : RandomAccessSource {
            override val length = Long.MAX_VALUE
            override fun read(offset: Long, count: Int): ByteArray { reads++; return ByteArray(count) }
        }
        val bounded = BoundedArtworkSource(source, maxReadBytes = 8, maxTotalBytes = 12)
        assertNull(bounded.read(0, Int.MAX_VALUE))
        assertEquals(0, reads)
        assertEquals(8, bounded.read(0, 8)?.size)
        assertNull(bounded.read(0, 5))
        assertEquals(4, bounded.read(100, 4)?.size)
        assertNull(bounded.read(0, 1))
        assertEquals(2, reads)
    }

    @Test
    fun invalidRangesCannotOverflowOrReachTheUnderlyingSource() {
        val bounded = BoundedArtworkSource(ByteArraySource(ByteArray(8)))
        assertNull(bounded.read(Long.MAX_VALUE, 2))
        assertNull(bounded.read(-1, 2))
        assertNull(bounded.read(0, -1))
        assertNull(bounded.read(7, 2))
    }

    @Test
    fun interruptionStopsReadsWithoutBeingConvertedToMissingArt() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedIOException> { readArtworkBytes(ByteArrayInputStream(byteArrayOf(1))) }
            assertFailsWith<InterruptedIOException> {
                loadArtworkWithFallback(song, { "image" }, { "embedded" })
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun corruptDimensionsAreRefusedAndSamplingKeepsBothSidesBounded() {
        assertNull(artworkSampleSize(0, 1, 64))
        assertNull(artworkSampleSize(1, -1, 64))
        assertNull(artworkSampleSize(Int.MAX_VALUE, 1, 64))
        assertNull(artworkSampleSize(1, 1, 0))
        for (side in listOf(1, 64, 129, 3000, 100_000)) {
            val sample = artworkSampleSize(side, 1, 64)!!
            assertTrue((side.toLong() + sample - 1) / sample <= 64)
            assertTrue(sample > 0 && sample and (sample - 1) == 0)
        }
    }
    @Test
    fun exifTransformsPreserveRotationAndReflectionIncludingTransposedPictures() {
        assertEquals(ArtworkTransform(), artworkTransform(0))
        assertEquals(ArtworkTransform(), artworkTransform(1))
        assertEquals(ArtworkTransform(flipped = true), artworkTransform(2))
        assertEquals(ArtworkTransform(rotation = 180), artworkTransform(3))
        assertEquals(ArtworkTransform(rotation = 180, flipped = true), artworkTransform(4))
        assertEquals(ArtworkTransform(rotation = 270, flipped = true), artworkTransform(5))
        assertEquals(ArtworkTransform(rotation = 90), artworkTransform(6))
        assertEquals(ArtworkTransform(rotation = 90, flipped = true), artworkTransform(7))
        assertEquals(ArtworkTransform(rotation = 270), artworkTransform(8))
        assertEquals(ArtworkTransform(), artworkTransform(999))
    }

}
