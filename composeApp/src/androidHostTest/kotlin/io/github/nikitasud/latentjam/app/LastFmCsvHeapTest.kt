/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.HistoryStore
import io.github.nikitasud.latentjam.library.LibrarySource
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Last.fm CSV of 150,000 scrobbles imports on the smallest heap the app runs on (48 MB): the whole import — reading,
 * matching against a 3,000-song library and merging into the history — runs in a child JVM started with -Xmx48m.
 */
class LastFmCsvHeapTest {
    @Test
    fun aHundredFiftyThousandRowImportFitsA48MbHeap() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-Xmx48m", "-cp", System.getProperty("java.class.path"), LastFmCsvHeapMain::class.java.name,
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(finished, "timed out: $output")
        assertEquals(0, process.exitValue(), output)
        assertTrue("read=150000" in output, output)
        assertTrue("imported=150000" in output, output)
        // The child really runs on a small heap: at 8 MB the same import does not fit.
        val tiny = ProcessBuilder(
            java, "-Xmx8m", "-cp", System.getProperty("java.class.path"), LastFmCsvHeapMain::class.java.name,
        ).redirectErrorStream(true).start()
        val tinyOutput = tiny.inputStream.bufferedReader().readText()
        assertTrue(tiny.waitFor(5, TimeUnit.MINUTES))
        assertTrue(tiny.exitValue() != 0 && "OutOfMemoryError" in tinyOutput, tinyOutput)
    }
}

/** The child JVM's import. Prints the report; a heap too small for it exits with OutOfMemoryError. */
object LastFmCsvHeapMain {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val library = (0 until 3_001).map { i ->
            TrackDescriptor(id = TrackId("t$i"), artist = "Artist ${i % 997}, the Band", title = "Song $i", durationMs = 200_000L)
        }
        val history = DefaultListeningHistory(DiscardingStore())
        val report = ListenBrainzImporter(
            history,
            object : MusicLibrary {
                override suspend fun tracks(): List<TrackDescriptor> = library
                override suspend fun allKnownTracks(): List<TrackDescriptor> = library
                override suspend fun hide(trackId: TrackId) = Unit
                override suspend fun unhide(trackId: TrackId) = Unit
                override suspend fun hiddenTracks(): List<TrackDescriptor> = emptyList()
                override suspend fun hasHiddenTracks(): Boolean = false
                override suspend fun unhideAll() = Unit
                override suspend fun sources(): List<LibrarySource> = emptyList()
                override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = Unit
            },
            now = { 1_800_000_000_000L },
        ).import(source = { LastFmCsvTest.GeneratedCsv(150_000) })
        println("read=${report.listensRead} imported=${report.imported} notInLibrary=${report.notInLibrary}")
        val runtime = Runtime.getRuntime()
        println("heapUsedMb=${(runtime.totalMemory() - runtime.freeMemory()) shr 20} maxMb=${runtime.maxMemory() shr 20}")
    }

    /** Keeps nothing: the store's own copy is the history file on a device, not the import's memory. */
    private class DiscardingStore : HistoryStore {
        override suspend fun append(line: String) = Unit
        override suspend fun readAll(): List<String> = emptyList()
        override suspend fun replaceAll(lines: List<String>) = Unit
        override suspend fun clear() = Unit
    }
}
