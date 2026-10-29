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
        val exact = LibraryLayout.EXACT_MAX_TRACKS
        assertTrue(MapMemoryBudget.projectionBytes(exact, 1344, false) > 4L * exact * (exact - 1) + 4L * exact * 1344)
        val n = AverageLinkageClustering.MAX_TRACKS
        // The centered source and the compacted usable rows can both remain live while
        // the linkage matrix is allocated; reserve both without relying on GC timing.
        val rowMatrices = 2L * Float.SIZE_BYTES * n * 1344
        val linkageMatrix = Double.SIZE_BYTES.toLong() * n * (n - 1) / 2
        assertTrue(MapMemoryBudget.regionBytes(n, 1344) > rowMatrices + linkageMatrix)
        // The quadratic exact paths stay within their reviewed sizes.
        assertEquals(2000, LibraryLayout.EXACT_MAX_TRACKS)
        assertEquals(3000, AverageLinkageClustering.MAX_TRACKS)
    }

    @Test fun largeLibraryLimitsArePinnedToTheMeasuredSize() {
        // Raised from 3,000 only after a 10,000-track run on a 256 MB-heap device. Raising it again needs a new measurement.
        assertEquals(10_000, LibraryLayout.MAX_TRACKS)
        assertEquals(LibraryLayout.MAX_TRACKS, AverageLinkageClustering.MAX_LIBRARY_TRACKS)
        assertIs<ExactMapEmbedding>(LibraryLayout.backendFor(LibraryLayout.EXACT_MAX_TRACKS))
        assertIs<ApproximateMapEmbedding>(LibraryLayout.backendFor(LibraryLayout.EXACT_MAX_TRACKS + 1))
        assertIs<ApproximateMapEmbedding>(LibraryLayout.backendFor(LibraryLayout.MAX_TRACKS))
        val mib = 1024L * 1024
        // Sampled heap peaks at 10,000 on a 256 MB heap, 51 MiB fused input included:
        // layout 80.7 MiB, regions 111.3 MiB. Estimates must cover them, and stay bounded.
        assertTrue(MapMemoryBudget.projectionBytes(10_000, 1344, false) in 81 * mib..100 * mib)
        assertTrue(MapMemoryBudget.regionBytes(10_000, 1344) in 112 * mib..140 * mib)
        // A check made after the space exists must not count its input twice.
        val input = 4L * 10_000 * 1344
        assertEquals(MapMemoryBudget.projectionBytes(10_000, 1344, false) - input, MapMemoryBudget.projectionWorkBytes(10_000, 1344, false))
        assertEquals(MapMemoryBudget.regionBytes(10_000, 1344) - input, MapMemoryBudget.regionWorkBytes(10_000, 1344))
        // The streamed layout never holds the input, only one chunk of it: 39.4 MiB at 10,000
        // against 89.3 MiB held. Measured streamed peak there: 31 MB on a 256 MB heap.
        assertEquals(93_672_704L, MapMemoryBudget.projectionBytes(10_000, 1344, false))
        assertEquals(41_288_960L, MapMemoryBudget.projectionStreamedBytes(10_000, 1344, false))
        assertEquals(
            MapMemoryBudget.projectionBytes(10_000, 1344, false) - input + 4L * LibraryVectorRows.CHUNK_ROWS * 1344,
            MapMemoryBudget.projectionStreamedBytes(10_000, 1344, false),
        )
        // Below one chunk the buffer is only as large as the library.
        assertEquals(
            MapMemoryBudget.projectionBytes(100, 1344, false) - 4L * 100 * 1344 + 4L * 100 * 1344,
            MapMemoryBudget.projectionStreamedBytes(100, 1344, false),
        )
        // Linear above the exact limit: no quadratic term survives.
        val boundary = LibraryLayout.EXACT_MAX_TRACKS
        assertTrue(MapMemoryBudget.projectionBytes(boundary + 1, 1344, false) < MapMemoryBudget.projectionBytes(boundary, 1344, false))
        assertTrue(MapMemoryBudget.regionBytes(3001, 1344) < MapMemoryBudget.regionBytes(3000, 1344) + 40 * mib)
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
        for ((n, budget) in listOf(60 to 0L, (AverageLinkageClustering.MAX_LIBRARY_TRACKS + 1) to Long.MAX_VALUE)) {
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

