/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class RegionIdentityTest {
    // Three directions in 3-d; a track is its axis plus a small offset on another axis.
    private val rows = HashMap<TrackId, FloatArray>()

    private fun track(name: String, axis: Int, tilt: Float = 0f, tiltAxis: Int = (axis + 1) % 3): TrackDescriptor {
        val v = FloatArray(3); v[axis] = 1f; v[tiltAxis] += tilt
        rows[TrackId(name)] = v
        order += TrackId(name)
        return TrackDescriptor(TrackId(name))
    }

    private fun world(name: String, tracks: List<TrackDescriptor>) = LibraryWorld(name, tracks)
    /** Rows for every track made so far, in creation order. */
    private val order = ArrayList<TrackId>()
    private val rowOf: LibraryVectorRows
        get() {
            val ids = order.toList()
            val m = FloatArray(ids.size * 3).also { out -> ids.forEachIndexed { i, id -> rows.getValue(id).copyInto(out, i * 3) } }
            return LibraryVectorRows(ids, 3, LibraryVectorSource.AUDIO, 0L, { start, count, out -> m.copyInto(out, 0, start * 3, (start + count) * 3) }, { true })
        }
    private fun ids(r: IdentityRegion) = r.world.tracks.map { it.id.value }.toSet()

    @Test
    fun initialKeepsWorldOrderAsIdsAndColours() = runTest {
        val a = world("A", (0 until 5).map { track("a$it", 0, 0.05f * it) })
        val b = world("B", (0 until 5).map { track("b$it", 1, 0.05f * it) })
        val regions = RegionIdentity.initial(listOf(a, b), rowOf)
        assertEquals(listOf(0, 1), regions.map { it.id })
        assertEquals(listOf(0, 1), regions.map { it.colour })
        assertTrue(regions[0].floor > 0.9f)
    }

    @Test
    fun joinAddsNearTracksAndLeavesFarOnesOut() = runTest {
        val a = world("A", (0 until 5).map { track("a$it", 0, 0.05f * it) })
        val b = world("B", (0 until 5).map { track("b$it", 1, 0.05f * it) })
        val regions = RegionIdentity.initial(listOf(a, b), rowOf)
        val near = track("near", 0, 0.02f)
        val far = track("far", 2)
        val present = (a.tracks + b.tracks + near + far).map { it.id }.toSet()
        val joined = RegionIdentity.join(regions, listOf(near, far), present, rowOf)
        assertTrue("near" in ids(joined[0]))
        assertTrue(joined.none { "far" in ids(it) })
        // A removed track leaves; centroids stay as they were.
        val gone = RegionIdentity.join(joined, emptyList(), present - TrackId("a0"), rowOf)
        assertTrue("a0" !in ids(gone[0]))
        assertTrue(gone[0].centroid.contentEquals(regions[0].centroid))
    }

    @Test
    fun rediscoverKeepsIdsColoursAndNamesOfMatchedWorlds() = runTest {
        val a = (0 until 6).map { track("a$it", 0, 0.05f * it) }
        val b = (0 until 6).map { track("b$it", 1, 0.05f * it) }
        val old = RegionIdentity.initial(listOf(world("A", a), world("B", b)), rowOf)
        // The rediscovery lists the worlds the other way round and renames them.
        val fresh = listOf(world("B2", b), world("A2", a))
        val settled = (a + b).map { it.id }.toSet()
        val out = RegionIdentity.rediscover(old, fresh, settled, rowOf, delta = 0.05f)
        assertEquals(listOf(0, 1), out.map { it.id })
        assertEquals(listOf("A", "B"), out.map { it.world.name })
        assertEquals(a.map { it.id.value }.toSet(), ids(out[0]))
    }

    @Test
    fun settledTrackMovesOnlyWhenClearlyBetter() = runTest {
        val a = (0 until 6).map { track("a$it", 0, 0.02f * it) }
        val b = (0 until 6).map { track("b$it", 1, 0.02f * it) }
        // Borderline: slightly closer to B than A; clear: on B's axis.
        val edge = track("edge", 0, 1.6f, tiltAxis = 1)
        val clear = track("clear", 1, 0.01f)
        val old = RegionIdentity.initial(listOf(world("A", a + edge + clear), world("B", b)), rowOf)
        val fresh = listOf(world("A", a), world("B", b + edge + clear))
        val settled = (a + b + edge + clear).map { it.id }.toSet()
        val sticky = RegionIdentity.rediscover(old, fresh, settled, rowOf, delta = 0.2f)
        assertTrue("edge" in ids(sticky[0]))
        assertTrue("clear" in ids(sticky[1]))
        val loose = RegionIdentity.rediscover(old, fresh, settled, rowOf, delta = 0f)
        assertTrue("edge" in ids(loose[1]))
    }

    @Test
    fun unmatchedWorldBecomesNewRegionOnlyWhenLargeEnough() = runTest {
        val a = (0 until 6).map { track("a$it", 0, 0.05f * it) }
        val old = RegionIdentity.initial(listOf(world("A", a)), rowOf)
        val big = (0 until 4).map { track("c$it", 2, 0.05f * it) }
        val small = (0 until 3).map { track("d$it", 1, 0.05f * it) }
        val settled = a.map { it.id }.toSet()
        val out = RegionIdentity.rediscover(old, listOf(world("A", a), world("C", big), world("D", small)), settled, rowOf, delta = 0.05f)
        assertEquals(listOf(0, 1), out.map { it.id })
        assertEquals("C", out[1].world.name)
        assertEquals(1, out[1].colour)
    }

    @Test
    fun settledTrackLeftOutOfEveryWorldKeepsItsRegion() = runTest {
        val a = (0 until 6).map { track("a$it", 0, 0.05f * it) }
        val old = RegionIdentity.initial(listOf(world("A", a)), rowOf)
        val out = RegionIdentity.rediscover(old, listOf(world("A", a.drop(1))), a.map { it.id }.toSet(), rowOf, delta = 0.05f)
        assertEquals(a.map { it.id.value }.toSet(), ids(out[0]))
    }

    @Test
    fun cancelledRediscoveryThrows() = runTest {
        val a = (0 until 6).map { track("a$it", 0, 0.05f * it) }
        val old = RegionIdentity.initial(listOf(world("A", a)), rowOf)
        assertFailsWith<CancellationException> {
            RegionIdentity.rediscover(old, listOf(world("A", a)), a.map { it.id }.toSet(), rowOf, delta = 0.05f, isActive = { false })
        }
    }

    @Test
    fun codecRoundTripsAndRejectsCorruption() = runTest {
        val a = (0 until 6).map { track("a$it", 0, 0.05f * it) }
        val b = (0 until 5).map { track("b$it", 1, 0.05f * it) }
        val worlds = listOf(
            LibraryWorld("Ambient", a, LibraryWorldNameSource.LEADING_STYLE, LibraryWorldContent.MUSIC, null, setOf("ambient", "drone")),
            LibraryWorld("Spoken", b, LibraryWorldNameSource.SEMANTIC, LibraryWorldContent.SPOKEN, LibraryWorldSemanticTitle.SPOKEN_AUDIO),
        )
        val regions = RegionIdentity.initial(worlds, rowOf)
        val ids = order.toList()
        val snapshot = RegionIdentitySnapshot("v1", ids, LongArray(ids.size) { it * 31L }, BooleanArray(ids.size) { it % 3 == 0 }, regions)
        val bytes = RegionIdentityCodec.encode(snapshot)
        val back = assertNotNull(RegionIdentityCodec.decode(bytes))
        assertEquals("v1", back.inputVersion)
        assertEquals(ids, back.ids)
        assertTrue(snapshot.hashes.contentEquals(back.hashes))
        assertTrue(snapshot.pending.contentEquals(back.pending))
        assertEquals(regions.map { it.id to it.colour }, back.regions.map { it.id to it.colour })
        assertEquals(worlds.map { it.copy(tracks = it.tracks.map { t -> TrackDescriptor(t.id) }) }, back.regions.map { it.world })
        assertTrue(regions[0].centroid.contentEquals(back.regions[0].centroid))
        assertEquals(regions[1].floor, back.regions[1].floor)
        // Any flipped byte fails the checksum; a truncated file is rejected.
        assertNull(RegionIdentityCodec.decode(bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2] + 1).toByte() }))
        assertNull(RegionIdentityCodec.decode(bytes.copyOf(bytes.size - 3)))
    }
}
