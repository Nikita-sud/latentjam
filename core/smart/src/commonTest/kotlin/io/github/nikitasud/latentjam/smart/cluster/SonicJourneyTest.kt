/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonicJourneyTest {

    /**
     * A little arc of points in 3-D: track i sits at angle i·10° on the unit circle, so the
     * geometry has an unambiguous "between" and the interpolation has real interior points to
     * find. The third dimension stays zero — centering keeps everything in the plane.
     */
    private fun arcSpace(count: Int): LibraryVectorSpace {
        val dim = 3
        val rows = FloatArray(count * dim)
        for (i in 0 until count) {
            val angle = i * (kotlin.math.PI / 18)
            rows[i * dim] = kotlin.math.cos(angle).toFloat()
            rows[i * dim + 1] = kotlin.math.sin(angle).toFloat()
        }
        return LibraryVectorSpace(
            trackIds = (0 until count).map { TrackId("t$it") },
            rows = rows,
            dim = dim,
            source = LibraryVectorSource.AUDIO,
        )
    }

    @Test
    fun aJourneyKeepsItsContract() {
        // Exact stop ORDER on synthetic geometry is centering-sensitive and is pinned by the
        // replay harness on real vectors instead (mean adjacent cos 0.345 on the exported
        // library); what a unit test can honestly pin is the contract: real endpoints, no
        // repeats, enough interior to be a trip, and a beginning that leaves the anchor's
        // neighbourhood before the end arrives.
        val journey = SonicJourney.session(arcSpace(12))
        val path = journey.plot(TrackId("t0"), TrackId("t11"), artistKeyOf = { null })
        assertNotNull(path)
        assertEquals(TrackId("t0"), path.first())
        assertEquals(TrackId("t11"), path.last())
        assertEquals(path.size, path.distinct().size)
        assertTrue(path.size >= 5)
        val indices = path.map { it.value.removePrefix("t").toInt() }
        assertTrue(indices[1] <= 4, "the trip must depart through the anchor's own region: $indices")
    }

    @Test
    fun adjacentStopsNeverShareAnArtist() {
        val journey = SonicJourney.session(arcSpace(12))
        // Every even track is by the same artist; the path must interleave around them.
        val path = journey.plot(
            from = TrackId("t0"),
            to = TrackId("t11"),
            artistKeyOf = { id -> if (id.value.removePrefix("t").toInt() % 2 == 0) "even" else null },
        )
        assertNotNull(path)
        path.zipWithNext().forEach { (a, b) ->
            val bothEven = a.value.removePrefix("t").toInt() % 2 == 0 &&
                b.value.removePrefix("t").toInt() % 2 == 0
            assertTrue(!bothEven, "adjacent same-artist stops in $path")
        }
    }

    @Test
    fun aJourneyWithNoRoomToTravelDeclinesTheCard() {
        val journey = SonicJourney.session(arcSpace(3))
        // Endpoints plus a single interior candidate cannot reach the minimum length.
        assertNull(journey.plot(TrackId("t0"), TrackId("t2"), artistKeyOf = { null }))
    }

    @Test
    fun theFarthestCandidateIsTheOppositeDirection() {
        // Explicit coordinates chosen to survive centering: the mean is near the origin, so
        // "opposite" keeps its sign after normalization.
        val dim = 3
        val points = listOf(
            "from" to floatArrayOf(1f, 0f, 0f),
            "near" to floatArrayOf(0.9f, 0.1f, 0f),
            "far" to floatArrayOf(-1f, 0f, 0f),
            "up" to floatArrayOf(0f, 1f, 0f),
            "down" to floatArrayOf(0f, -1f, 0f),
        )
        val rows = FloatArray(points.size * dim)
        points.forEachIndexed { i, (_, v) -> v.copyInto(rows, i * dim) }
        val journey = SonicJourney.session(
            LibraryVectorSpace(
                trackIds = points.map { TrackId(it.first) },
                rows = rows,
                dim = dim,
                source = LibraryVectorSource.AUDIO,
            ),
        )
        val far = journey.farthestFrom(
            from = TrackId("from"),
            candidates = listOf(TrackId("near"), TrackId("far"), TrackId("up")),
        )
        assertEquals(TrackId("far"), far)
    }
}
