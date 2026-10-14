// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.test.Test
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
        assertTrue(MapMemoryBudget.regionBytes(n, 1344) > 8L * n * (n - 1) / 2)
        assertTrue(MapMemoryBudget.projectionBytes(n, 1344, true) < MapMemoryBudget.projectionBytes(n, 1344, false))
        assertEquals(3000, LibraryLayout.MAX_TRACKS)
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
}

