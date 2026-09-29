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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * A `.lrc` beside a song changes without changing the song's file. The search index notices it
 * through each song's sidecar fingerprint, and re-reads that song alone.
 */
internal class LyricsSidecarFingerprintTest {

    private class MemoryStorage : LyricsSearchStorage {
        var payload: String? = null
        override suspend fun read() = payload
        override suspend fun write(payload: String) {
            this.payload = payload
        }
    }

    /** A disk with some songs and the `.lrc` files beside them, seen through a fingerprint. */
    private class Disk(val songs: List<TrackDescriptor>) {
        val clock = TestTimeSource()
        val sidecars = mutableMapOf<TrackId, Pair<String, String>>() // text, size:modified
        val reads = mutableListOf<TrackId>()
        var passes = 0
        val events = mutableListOf<String>()
        val storage = MemoryStorage()
        val reader: suspend (TrackDescriptor) -> Lyrics? = { track ->
            reads += track.id
            events += "read ${track.id.value}"
            sidecars[track.id]?.let { Lyrics(listOf(LyricLine(null, it.first))) }
        }

        /** Every song checked: "" is a song known to have no sidecar. */
        val fingerprints: suspend (List<TrackDescriptor>) -> Map<TrackId, String> = { tracks ->
            passes++
            events += "fingerprints ${tracks.size}"
            tracks.associate { track -> track.id to sidecars[track.id]?.second.orEmpty() }
        }

        fun newLaunch() = LyricsSearchCache(clock)

        /** One pass of the index, as opening Search runs it; a fresh [cache] is a new launch. */
        suspend fun open(
            cache: LyricsSearchCache = newLaunch(),
            fingerprints: suspend (List<TrackDescriptor>) -> Map<TrackId, String> = this.fingerprints,
        ): Map<TrackId, LyricSearchDocument> {
            var result = emptyMap<TrackId, LyricSearchDocument>()
            cache.load(
                songs,
                storage,
                reader,
                LYRICS_SOURCES_VERSION,
                fingerprints,
                reading = { events += "reading $it" },
            ) {
                events += "publish ${it.size}"
                result = it
            }
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
        val cache = disk.newLaunch()
        val result = disk.open(cache)
        disk.clock += 10.minutes
        disk.open(cache)
        assertEquals(emptyList(), disk.reads)
        assertNotNull(result[TrackId("t2")]?.snippet("silver moon"))
    }

    @Test
    fun anAddedSidecarRereadsOnlyItsSong() = runTest {
        val disk = Disk(songs())
        disk.open()
        disk.reads.clear()
        disk.sidecars[TrackId("t3")] = "silver moon" to "11:100"
        val result = disk.open()
        assertEquals(listOf(TrackId("t3")), disk.reads)
        assertNotNull(result[TrackId("t3")]?.snippet("silver moon"))
    }

    @Test
    fun aChangedSidecarRereadsOnlyItsSongOnAnOpenAfterTheRecheckInterval() = runTest {
        val disk = Disk(songs())
        disk.sidecars[TrackId("t1")] = "silver moon" to "11:100"
        val cache = disk.newLaunch()
        disk.open(cache)
        disk.reads.clear()
        disk.sidecars[TrackId("t1")] = "golden sun" to "10:200"
        disk.clock += 5.minutes
        val result = disk.open(cache)
        assertEquals(listOf(TrackId("t1")), disk.reads)
        assertNotNull(result[TrackId("t1")]?.snippet("golden sun"))
        assertNull(result[TrackId("t1")]?.snippet("silver moon"))
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
        val result = disk.open(fingerprints = { error("provider gone") })
        assertEquals(emptyList(), disk.reads)
        assertNotNull(result[TrackId("t2")]?.snippet("silver moon"))
        // The next pass that can see the disk still compares against what was stored.
        disk.sidecars.remove(TrackId("t2"))
        disk.open()
        assertEquals(listOf(TrackId("t2")), disk.reads)
    }

    @Test
    fun aSongWhoseFingerprintIsUnknownKeepsItsEntry() = runTest {
        val disk = Disk(songs())
        disk.sidecars[TrackId("t2")] = "silver moon" to "11:100"
        disk.open()
        disk.reads.clear()
        // Its folder could not be listed this pass: that says nothing about its .lrc.
        val result = disk.open(fingerprints = { tracks -> disk.fingerprints(tracks) - TrackId("t2") })
        assertEquals(emptyList(), disk.reads)
        assertNotNull(result[TrackId("t2")]?.snippet("silver moon"))
    }

    @Test
    fun cachedResultsAreReadyBeforeTheFingerprintPassAndOnlyRereadsCountAsIndexing() = runTest {
        val disk = Disk(songs())
        disk.sidecars[TrackId("t2")] = "silver moon" to "11:100"
        disk.open()
        disk.events.clear()
        disk.open()
        assertEquals(listOf("publish 1", "reading false", "fingerprints 4"), disk.events)
        disk.events.clear()
        disk.sidecars[TrackId("t3")] = "golden sun" to "10:200"
        disk.open()
        assertEquals(
            listOf("publish 1", "reading false", "fingerprints 4", "reading true", "read t3", "publish 2", "reading false"),
            disk.events,
        )
    }

    @Test
    fun songsNotYetReadKeepIndexingOnThroughTheFingerprintPass() = runTest {
        val disk = Disk(songs())
        disk.open()
        // A cold index never reports "not reading" before its first read: that would flash "No matches".
        assertEquals(listOf("reading true", "fingerprints 4"), disk.events.drop(1).take(2))
        assertEquals(disk.events.indexOf("reading false"), disk.events.lastIndex)
    }

    @Test
    fun aRecentFingerprintPassIsNotRepeatedOnTheNextOpen() = runTest {
        val disk = Disk(songs())
        val cache = disk.newLaunch()
        disk.open(cache)
        disk.clock += 4.minutes
        disk.open(cache)
        assertEquals(1, disk.passes)
        disk.clock += 1.minutes
        disk.open(cache)
        assertEquals(2, disk.passes)
    }

    @Test
    fun aSongReadBetweenPassesStillRecordsItsSidecar() = runTest {
        val disk = Disk(songs().take(2))
        val cache = disk.newLaunch()
        disk.open(cache)
        // A rescan adds a song with a .lrc while the last pass is still recent: the new songs alone
        // are fingerprinted as they are read, so the next full pass does not read them again.
        val grown = Disk(songs()).also { it.sidecars[TrackId("t4")] = "silver moon" to "11:100" }
        var result = emptyMap<TrackId, LyricSearchDocument>()
        cache.load(grown.songs, grown.storage, grown.reader, LYRICS_SOURCES_VERSION, grown.fingerprints) { result = it }
        assertEquals(listOf("fingerprints 2", "read t3", "read t4"), grown.events)
        assertNotNull(result[TrackId("t4")]?.snippet("silver moon"))
        grown.reads.clear()
        disk.clock += 5.minutes
        cache.load(grown.songs, grown.storage, grown.reader, LYRICS_SOURCES_VERSION, grown.fingerprints) {}
        assertEquals(emptyList(), grown.reads)
    }

    @Test
    fun anIndexWrittenBeforeFingerprintsRereadsOnlyTheSongsWithASidecar() = runTest {
        val disk = Disk(songs())
        disk.sidecars[TrackId("t1")] = "silver moon" to "11:100"
        disk.open()
        // The same index as the previous build wrote it: header v1, no fingerprint column.
        val v2 = assertNotNull(disk.storage.payload)
        assertTrue(v2.startsWith("lyrics-v2\n"))
        disk.storage.payload = "lyrics-v1\n" + v2.lines().drop(1).filter { it.isNotEmpty() }
            .joinToString("") { it.substringBeforeLast('\t') + "\n" }
        disk.reads.clear()
        val result = disk.open()
        assertEquals(listOf(TrackId("t1")), disk.reads)
        assertNotNull(result[TrackId("t1")]?.snippet("silver moon"))
        assertEquals(1, result.size)
    }
}
