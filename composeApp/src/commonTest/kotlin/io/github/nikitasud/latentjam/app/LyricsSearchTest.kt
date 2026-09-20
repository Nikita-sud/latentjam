/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.LyricLine
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsSearchTest {
    @Test fun phraseMatchesAcrossLinesPunctuationAndCyrillicFold() {
        val document = LyricSearchDocument.build("Тихий свет,\nза окном — ещё не вечер")!!
        assertNotNull(document.snippet("СВЕТ за окном"))
        assertNotNull(document.snippet("za oknom eshche"))
        assertNotNull(document.snippet("окном еще не веч"))
        assertNull(document.snippet("окном тихий"))
        assertNull(document.snippet("за"))
    }

    @Test fun contractionsAccentsAndPartialLastWordsWork() {
        val document = LyricSearchDocument.build("Café lights—we can’t stop singing")!!
        assertNotNull(document.snippet("cafe lights we"))
        assertNotNull(document.snippet("cant stop sing"))
        assertNull(document.snippet("top sing"))
    }

    @Test fun japanesePhrasesCanStartInsideAnUnspacedLine() {
        val document = LyricSearchDocument.build("遠くの空に光が見える")!!
        assertNotNull(document.snippet("空に光"))
        assertNotNull(document.snippet("光が"))
        assertNull(document.snippet("月明かり"))
    }

    @Test fun snippetShowsMatchDeepInsideLongText() {
        val document = LyricSearchDocument.build("before ".repeat(200) + "a hidden silver moon " + "after ".repeat(200))!!
        val snippet = document.snippet("silver moon")!!
        assertTrue("silver moon" in snippet)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length < 180)
    }

    @Test fun lyricMatchesBeatWeakMetadataAndCannotDuplicateTracks() {
        val exact = track("exact").copy(title = "Moon")
        val lyric = track("lyric").copy(title = "Honeymoon")
        val prefix = track("prefix").copy(title = "Moonlight")
        val fuzzy = track("fuzzy").copy(title = "Mood")
        val result = hybridSearch(
            listOf(fuzzy, lyric, prefix, exact), "moon", emptyList(), lyricMatches = setOf(lyric.id, exact.id),
        )
        assertEquals(listOf(exact.id, prefix.id, lyric.id, fuzzy.id), result.map { it.id })
    }

    @Test fun lyricsFindSongsWhoseMetadataDoesNotContainQuery() {
        val song = track("one")
        val snippets = searchLyrics(mapOf(song.id to LyricSearchDocument.build("distant silver moon")!!), "silver moon")
        assertEquals(listOf(song), hybridSearch(listOf(song), "silver moon", emptyList(), lyricMatches = snippets.keys))
    }

    @Test fun reopeningAndRestartingReusePositiveAndNegativeCache() = runTest {
        val songs = listOf(track("with"), track("without"))
        val storage = MemoryStorage()
        var reads = 0
        val reader: suspend (TrackDescriptor) -> Lyrics? = {
            reads++
            if (it.id.value == "with") lyrics("silver moon") else null
        }
        val cache = LyricsSearchCache()
        repeat(2) { cache.load(songs, storage, reader) {} }
        var restored = emptyMap<TrackId, LyricSearchDocument>()
        LyricsSearchCache().load(songs, storage, reader) { restored = it }
        assertEquals(2, reads)
        assertEquals(setOf(songs.first().id), restored.keys)
        assertNotNull(restored[songs.first().id]?.snippet("silver moon"))
    }

    @Test fun revisionAndUriChangesInvalidateLyricsAndRemovedTracksArePruned() = runTest {
        val cache = LyricsSearchCache()
        val storage = MemoryStorage()
        val first = track("one")
        var reads = 0
        val reader: suspend (TrackDescriptor) -> Lyrics? = { reads++; lyrics("version $reads") }
        cache.load(listOf(first, track("removed")), storage, reader) {}
        val changed = first.copy(sourceRevision = "2")
        var result = emptyMap<TrackId, LyricSearchDocument>()
        val snapshots = mutableListOf<Map<TrackId, LyricSearchDocument>>()
        cache.load(listOf(changed), storage, reader) { snapshots += it; result = it }
        assertTrue(snapshots.first().isEmpty())
        assertEquals(setOf(first.id), result.keys)
        assertNotNull(result[first.id]?.snippet("version 3"))
        cache.load(listOf(changed.copy(audioUri = "file:///new.mp3")), storage, reader) {}
        assertEquals(4, reads)
    }

    @Test fun failedReadIsRetriedInsteadOfCachedAsMissing() = runTest {
        val cache = LyricsSearchCache()
        val storage = MemoryStorage()
        val song = track("one")
        cache.load(listOf(song), storage, { error("temporarily unreadable") }) {}
        var result = emptyMap<TrackId, LyricSearchDocument>()
        cache.load(listOf(song), storage, { lyrics("silver moon") }) { result = it }
        assertNotNull(result[song.id]?.snippet("silver moon"))
    }

    @Test fun cancellationPreservesCompletedWorkWithoutCachingTheInterruptedRead() = runTest {
        val cache = LyricsSearchCache()
        val storage = MemoryStorage()
        val songs = listOf(track("first"), track("second"))
        try {
            cache.load(songs, storage, {
                if (it.id == songs.last().id) throw CancellationException()
                lyrics("first words")
            }) {}
            error("Expected cancellation")
        } catch (_: CancellationException) { }
        val read = mutableListOf<TrackId>()
        cache.load(songs, storage, { read += it.id; lyrics("second words") }) {}
        assertEquals(listOf(songs.last().id), read)
    }

    @Test fun failedCacheWriteIsRetriedWithoutRereadingAudio() = runTest {
        val cache = LyricsSearchCache()
        val storage = MemoryStorage().apply { failWrite = true }
        var reads = 0
        val reader: suspend (TrackDescriptor) -> Lyrics? = { reads++; lyrics("silver moon") }
        cache.load(listOf(track("one")), storage, reader) {}
        assertNull(storage.payload)
        storage.failWrite = false
        cache.load(listOf(track("one")), storage, reader) {}
        assertNotNull(storage.payload)
        assertEquals(1, reads)
    }

    @Test fun corruptedCacheIsRebuilt() = runTest {
        val storage = MemoryStorage().apply { payload = "lyrics-v1\ninvalid\t!!!\tbroken\n" }
        var result = emptyMap<TrackId, LyricSearchDocument>()
        LyricsSearchCache().load(listOf(track("one")), storage, { lyrics("silver moon") }) { result = it }
        assertEquals(1, result.size)
        assertFalse(storage.payload!!.contains("!!!"))
    }

    private fun track(id: String) = TrackDescriptor(TrackId(id), title = "Song $id", audioUri = "file:///$id.mp3", sourceRevision = "1")
    private fun lyrics(text: String) = Lyrics(listOf(LyricLine(null, text)))
    private class MemoryStorage : LyricsSearchStorage {
        var payload: String? = null
        var failWrite = false
        override suspend fun read() = payload
        override suspend fun write(payload: String) {
            check(!failWrite)
            this.payload = payload
        }
    }
}
