/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.FakeIndexStore
import io.github.nikitasud.latentjam.smart.HeapRowStore
import io.github.nikitasud.latentjam.smart.RowStore
import io.github.nikitasud.latentjam.smart.RowStoreFactory
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The kept tagger output reads back bit-identical to the head's own output (audio_tagger_golden.tsv vectors). */
class ImputationCacheTest {
    private val tagger = assertNotNull(
        AudioTagger.parse(
            File("../../androidApp/src/main/assets/ml/audio_tagger.json").readText(),
            AssetBytes.of(File("../../androidApp/src/main/assets/ml/audio_tagger.bin").readBytes()),
        ),
    )
    private val vectors = checkNotNull(javaClass.getResourceAsStream("/audio_tagger_golden.tsv")).reader().readLines()
        .filter(String::isNotEmpty).map { l -> l.split('\t')[0].split(',').map(String::toFloat).toFloatArray() }

    @Test
    fun aRestoredCacheGivesTheHeadsExactOutput() = runTest {
        val store = FakeIndexStore()
        val ids = vectors.indices.map { TrackId("t$it") }
        assertEquals(vectors.size, ImputationCache(tagger, store, "v").backfill(ids) { vectors[it.value.drop(1).toInt()] })
        val restored = ImputationCache(tagger, store, "v")
        for ((i, v) in vectors.withIndex()) {
            val direct = assertNotNull(tagger.impute(v))
            val hit = restored.cached(ids[i], v)
            assertTrue(hit.hit)
            val got = assertNotNull(hit.imputed)
            assertContentEquals(direct.probabilities.map { it.toRawBits() }, got.probabilities.map { it.toRawBits() })
            assertContentEquals(direct.descriptor.map { it.toRawBits() }, got.descriptor.map { it.toRawBits() })
            assertEquals(direct.family, got.family)
            assertEquals(direct.familyProbabilities, got.familyProbabilities)
        }
        // In the same process (kept in stored form, decoded per lookup): the head's exact output too.
        val live = ImputationCache(tagger, null, "v")
        for ((i, v) in vectors.withIndex()) {
            val computed = assertNotNull(live.compute(ids[i], v))
            val got = assertNotNull(live.cached(ids[i], v).imputed)
            assertContentEquals(computed.probabilities.map { it.toRawBits() }, got.probabilities.map { it.toRawBits() })
            assertContentEquals(computed.descriptor.map { it.toRawBits() }, got.descriptor.map { it.toRawBits() })
            assertEquals(computed.family, got.family)
            assertEquals(computed.familyProbabilities, got.familyProbabilities)
        }
        // Another vector for the same track (re-analysed) is a miss; another version starts empty.
        assertFalse(restored.cached(ids[0], vectors[1]).hit)
        assertEquals(0, ImputationCache(tagger, store, "other").size())
    }

    /** Rows read only through [RowStore.row] (as from Android's mapped file): never the arrays themselves. */
    private val offHeap = RowStoreFactory { dim, _ ->
        object : RowStore by HeapRowStore(dim) {
            override fun heapArray(row: Int): FloatArray? = null
        }
    }

    @Test
    fun rowsKeptOffTheHeapReadBackTheSame() = runTest {
        val store = FakeIndexStore()
        val ids = vectors.indices.map { TrackId("t$it") }
        val first = ImputationCache(tagger, store, "v", offHeap)
        assertEquals(vectors.size, first.backfill(ids) { vectors[it.value.drop(1).toInt()] })
        for (cache in listOf(first, ImputationCache(tagger, store, "v", offHeap))) {
            for ((i, v) in vectors.withIndex()) {
                val direct = assertNotNull(tagger.impute(v))
                val got = assertNotNull(cache.cached(ids[i], v).imputed)
                assertContentEquals(direct.probabilities.map { it.toRawBits() }, got.probabilities.map { it.toRawBits() })
                assertContentEquals(direct.descriptor.map { it.toRawBits() }, got.descriptor.map { it.toRawBits() })
                assertEquals(direct.familyProbabilities, got.familyProbabilities)
            }
        }
    }
}
