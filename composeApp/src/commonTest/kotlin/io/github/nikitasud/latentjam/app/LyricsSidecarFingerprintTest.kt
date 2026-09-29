/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.LyricLine
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `.lrc` beside a song changes without changing the song's file. The search index notices it
 * through each song's sidecar fingerprint, and re-reads that song alone.
 */
internal class LyricsSidecarFingerprintTest {

    /** A disk with some songs and the `.lrc` files beside them, seen through a fingerprint. */
    private class Disk(val songs: List<TrackDescriptor>) {
        val sidecars = mutableMapOf<TrackId, Pair<String, String>>() // text, size:modified
        val reads = mutableListOf<TrackId>()
        val storage = object : LyricsSearchStorage {
            var payload: String? = null
            override suspend fun read() = payload
            override suspend fun write(payload: String) {
                this.payload = payload
            }
        }
        val reader: suspend (TrackDescriptor) -> Lyrics? = { track ->
            reads += track.id
            sidecars[track.id]?.let { Lyrics(listOf(LyricLine(null, it.first))) }
        }
        val fingerprints: suspend (List<TrackDescriptor>) -> Map<TrackId, String> = { tracks ->
            tracks.mapNotNull { track -> sidecars[track.id]?.let { track.id to it.second } }.toMap()
        }

        /** One pass of the index, as opening Search runs it; a fresh [cache] is a new launch. */
        suspend fun open(cache: LyricsSearchCache = LyricsSearchCache()): Map<TrackId, LyricSearchDocument> {
            var result = emptyMap<TrackId, LyricSearchDocument>()
            cache.load(songs, storage, reader, LYRICS_SOURCES_VERSION, fingerprints) { result = it }
            return result
        }
    }

    private fun songs() = (1..4).map {
        TrackDescriptor(TrackId("t$it"), title = "Song $it", audioUri = "file:///t$it.mp3", sourceRevision = "1")
    }

    @Test
    fun anUnchangedLibraryIsNotReadAgainOnTheNextLaunchOrTheNextOpen() = runTest {
        val disk = Disk(songs())
        disk.sidecars[TrackId("t2")] = "silver moon" to "11:100"
        disk.open()
        assertEquals(4, disk.reads.size)
        disk.reads.clear()
        val cache = LyricsSearchCache()
        val result = disk.open(cache)
        disk.open(cache)
        assertEquals(emptyList(), disk.reads)
        assertTrue(result[TrackId("t2")]!!.snippet("silver moon") != null)
    }

    @Test
    fun anAddedSidecarRereadsOnlyItsSong() = runTest {
        val disk = Disk(songs())
        disk.open()
        disk.reads.clear()
        disk.sidecars[TrackId("t3")] = "silver moon" to "11:100"
        val result = disk.open()
        assertEquals(listOf(TrackId("t3")), disk.reads)
        assertTrue(result[TrackId("t3")]!!.snippet("silver moon") != null)
    }

    @Test
    fun aChangedSidecarRereadsOnlyItsSongEvenWithinOneLaunch() = runTest {
        val disk = Disk(songs())
        disk.sidecars[TrackId("t1")] = "silver moon" to "11:100"
        val cache = LyricsSearchCache()
        disk.open(cache)
        disk.reads.clear()
        disk.sidecars[TrackId("t1")] = "golden sun" to "10:200"
        val result = disk.open(cache)
        assertEquals(listOf(TrackId("t1")), disk.reads)
        assertTrue(result[TrackId("t1")]!!.snippet("golden sun") != null)
        assertNull(result[TrackId("t1")]!!.snippet("silver moon"))
    }

    @Test
    fun aRemovedSidecarRereadsOnlyItsSongAndItsLyricsLeaveTheIndex() = runTest {
        val disk = Disk(songs())
        disk.sidecars[TrackId("t4")] = "silver moon" to "11:100"
        disk.open()
        disk.reads.clear()
        disk.sidecars.remove(TrackId("t4"))
        val result = disk.open()
        assertEquals(listOf(TrackId("t4")), disk.reads)
        assertNull(result[TrackId("t4")])
    }

    @Test
    fun aFingerprintThatCannotBeTakenRereadsNothingAndKeepsTheIndex() = runTest {
        val disk = Disk(songs())
        disk.sidecars[TrackId("t2")] = "silver moon" to "11:100"
        disk.open()
        disk.reads.clear()
        var result = emptyMap<TrackId, LyricSearchDocument>()
        LyricsSearchCache().load(disk.songs, disk.storage, disk.reader, LYRICS_SOURCES_VERSION, { error("provider gone") }) {
            result = it
        }
        assertEquals(emptyList(), disk.reads)
        assertTrue(result[TrackId("t2")]!!.snippet("silver moon") != null)
        // The next pass that can see the disk still compares against what was stored.
        disk.sidecars.remove(TrackId("t2"))
        disk.open()
        assertEquals(listOf(TrackId("t2")), disk.reads)
    }
}
