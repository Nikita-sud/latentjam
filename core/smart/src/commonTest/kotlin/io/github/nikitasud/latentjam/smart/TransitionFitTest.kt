/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitionFitTest {

    @Test
    fun closenessIsTheShareOfTheLibraryFurtherAway() {
        val others = FloatArray(20) { it / 20f }
        assertEquals(1f, closenessPercentile(2f, others))
        assertEquals(0f, closenessPercentile(-1f, others))
        assertEquals(0.5f, closenessPercentile(0.5f, others)!!, 0.03f)
        // Too few tracks to place a pair in the library.
        assertNull(closenessPercentile(0.5f, FloatArray(5)))
    }

    @Test
    fun energyGapIsMeasuredInTheLibrarysOwnSpread() {
        assertEquals(0f, energyGap(0.2, 0.2, 0.5))
        assertEquals(0.5f, energyGap(0.0, 0.5, 0.5)!!, 1e-6f)
        assertEquals(1f, energyGap(-3.0, 3.0, 0.5))
        assertNull(energyGap(null, 0.2, 0.5))
        assertNull(energyGap(0.1, 0.2, null))
        assertNull(interquartileRange(DoubleArray(4) { it.toDouble() }))
        assertEquals(50.0, interquartileRange(DoubleArray(101) { it.toDouble() })!!, 1e-9)
    }

    /** A track at [angle] radians on a circle, so cosine falls as the angle grows. */
    private fun at(angle: Double) = floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat(), 0f)

    @Test
    fun aNeighbourFitsAndAnOppositeDoesNot() = runTest {
        val backend = FakeEmbeddingBackend()
        val tracks = List(40) { i -> TrackDescriptor(id = TrackId("t$i"), audioUri = "test://t$i") }
        tracks.forEachIndexed { i, track -> backend.vectors[track.id] = at(i * kotlin.math.PI / 40) }
        val engine = DefaultSimilarityEngine(
            inferenceDispatcher = Dispatchers.Default.limitedParallelism(1, "fit-inference"),
            backend = backend,
            index = InMemoryVectorIndex(dim = 3),
            store = FakeIndexStore(),
            config = SmartEngineConfig(embeddingDim = 3, modelVersion = "test-model"),
            dispatcher = Dispatchers.Default.limitedParallelism(1, "fit-engine"),
        )
        engine.initialize().getOrThrow()
        engine.indexLibrary(tracks)
        val neighbour = assertNotNull(engine.transitionFit(tracks[0].id, tracks[1].id))
        val opposite = assertNotNull(engine.transitionFit(tracks[0].id, tracks[39].id))
        assertTrue(neighbour.closeness > 0.9f, "neighbour $neighbour")
        assertTrue(opposite.closeness < 0.1f, "opposite $opposite")
        // No energies were computed: the gap is unknown rather than zero.
        assertNull(neighbour.energyGap)
        // A track that is not analysed has no fit.
        assertNull(engine.transitionFit(tracks[0].id, TrackId("missing")))
    }
}
