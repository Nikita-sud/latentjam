/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import io.github.nikitasud.latentjam.smart.text.MusicEntityResolver
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** A 10k-track library with the shipped assets and pack: the first press reads the library's knowledge once. */
class RankerLargeLibraryTest {
    private val ml = File("../../androidApp/src/main/assets/ml")
    private fun read(name: String) = File(ml, name).takeIf { it.isFile }?.readBytes()?.let(AssetBytes::of)

    @Test
    fun tenThousandTracksPlanWithinBudget() = runTest {
        val knowledge = ArtistKnowledge(MusicEntityResolver { read("music_entities_250k.bin")!! }) { read("artist_knowledge.bin") }
        val ranker = SmartRankerAssets(knowledge, ::read).ranker
        val artists = listOf("ABBA", "Eminem", "Кино", "Daft Punk", "Ramin Djawadi", "Beyoncé feat. JAY-Z", "Unknown Local Band", "米津玄師")
        val library = (0 until 10_000).map { i ->
            TrackDescriptor(TrackId("t$i"), title = "Song $i", artist = artists[i % artists.size] + if (i % 3 == 0) "" else " $i", genre = listOf("Pop", "Rock", null)[i % 3], durationMs = 180_000L + i)
        }
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }
        // Assets load first (on device they are mapped, not on the heap): warm the ranker on a tiny library.
        ranker.plan(PlanRequest(library[0], library.take(5), library.take(5), 3))
        val before = used()
        val first = TimeSource.Monotonic.markNow()
        val report = ranker.plan(PlanRequest(library[0], library, library, 20, press = 0))
        val firstMs = first.elapsedNow().inWholeMilliseconds
        val second = TimeSource.Monotonic.markNow()
        val again = ranker.plan(PlanRequest(library[1], library, library, 20, press = 0))
        val secondMs = second.elapsedNow().inWholeMilliseconds
        val retained = used() - before
        println("ranker 10k: retained by the ranker after two presses ${retained / 1024} KB (${retained / library.size} B per track)")
        println("ranker 10k: first press ${firstMs} ms (pool ${report.poolSize}, ${report.fromModel} model, ${report.fromLadder} ladder), next ${secondMs} ms; coverage ${ranker.entityCoverage}")
        assertEquals(20, report.ids.size)
        assertEquals(20, again.ids.size)
        assertTrue(secondMs < 3_000, "a press on a known library stays within 20 hops × 150 ms")
    }
}
