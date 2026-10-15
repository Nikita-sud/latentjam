// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MapEmbeddingBackendTest {
    @Test fun smallLibrariesUseExactEvenWithoutNativeLibrary() {
        val rows = FloatArray(12 * 3) { (it % 7).toFloat() }
        val exact = assertIs<MapEmbeddingResult.Complete>(ExactMapEmbedding.embed(rows, 12, 3, null) { true })
        val fallback = assertIs<MapEmbeddingResult.Complete>(BarnesHutMapEmbedding.embed(rows, 12, 3, null) { true })
        assertTrue(exact.coordinates.contentEquals(fallback.coordinates))
    }
    @Test fun cancellationHasNoCoordinatesToPersist() {
        assertIs<MapEmbeddingResult.Cancelled>(ExactMapEmbedding.embed(FloatArray(30), 10, 3, null) { false })
        assertIs<MapEmbeddingResult.Cancelled>(BarnesHutMapEmbedding.embed(FloatArray(61 * 3), 61, 3, null) { false })
    }
    @Test fun budgetsIncludeOptimizerAndRegionQuadraticBuffers() {
        val n = 3000
        assertTrue(MapMemoryBudget.projectionBytes(n, 1344, false) > 4L * n * (n - 1) + 4L * n * 1344)
        // The centered source and the compacted usable rows can both remain live while
        // the linkage matrix is allocated; reserve both without relying on GC timing.
        val rowMatrices = 2L * Float.SIZE_BYTES * n * 1344
        val linkageMatrix = Double.SIZE_BYTES.toLong() * n * (n - 1) / 2
        assertTrue(MapMemoryBudget.regionBytes(n, 1344) > rowMatrices + linkageMatrix)
        assertTrue(MapMemoryBudget.projectionBytes(n, 1344, true) < MapMemoryBudget.projectionBytes(n, 1344, false))
        // Raising either quadratic limit requires an explicit review of both algorithms.
        assertEquals(3000, LibraryLayout.MAX_TRACKS)
        assertEquals(3000, AverageLinkageClustering.MAX_TRACKS)
    }

    @Test fun lowMemoryRegionsUseExistingBoundedFallback() {
        val ids = List(60) { io.github.nikitasud.latentjam.smart.TrackId("budget-$it") }
        val tracks = ids.map { io.github.nikitasud.latentjam.smart.TrackDescriptor(id = it, title = it.value) }
        fun space() = LibraryVectorSpace(ids, FloatArray(60 * 8) { i ->
            if (i % 8 == (i / 8) / 20) 1f else 0.01f
        }, 8, LibraryVectorSource.AUDIO)
        val expected = LibraryWorlds.discover(tracks, space(), k = LibraryWorlds.recommendedK(60))
        val consumed = space()
        val actual = LibraryWorlds.discover(tracks, consumed, emptyMap(), { true }, workBudgetBytes = 0)
        assertEquals(expected, actual)
        kotlin.test.assertFailsWith<IllegalStateException> { consumed.takeRows() }
    }

    @Test fun fallbackRegionsHonorCancellationBeforeConsumingRows() {
        // Exercise both reasons production selects k-means: memory and population limits.
        for ((n, budget) in listOf(60 to 0L, (AverageLinkageClustering.MAX_TRACKS + 1) to Long.MAX_VALUE)) {
            val ids = List(n) { TrackId("cancel-$it") }
            val tracks = ids.map { TrackDescriptor(it) }
            val rows = FloatArray(n * 8) { if (it % 8 == (it / 8) % 8) 1f else 0.01f }
            val space = LibraryVectorSpace(ids, rows, 8, LibraryVectorSource.AUDIO)
            assertFailsWith<CancellationException> {
                LibraryWorlds.discover(tracks, space, emptyMap(), { false }, budget)
            }
            // A cancelled request did not claim the one-shot matrix.
            assertTrue(space.peekRows() === rows)
        }
    }

    @Test fun fallbackRegionsCanCancelAfterWorkHasStarted() {
        val ids = List(60) { TrackId("cancel-running-$it") }
        val tracks = ids.map { TrackDescriptor(it) }
        val space = LibraryVectorSpace(ids, FloatArray(60 * 8) { i ->
            if (i % 8 == (i / 8) % 8) 1f else 0.01f
        }, 8, LibraryVectorSource.AUDIO)
        var polls = 0
        assertFailsWith<CancellationException> {
            LibraryWorlds.discover(tracks, space, emptyMap(), { ++polls < 10 }, workBudgetBytes = 0)
        }
        assertTrue(polls > 1)
    }
}

