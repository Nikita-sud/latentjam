/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LibraryLayoutRefreshTest {
    private val scale = MapScaleReplay()

    private fun rowsOf(ids: List<TrackId>, matrix: FloatArray, dim: Int, source: LibraryVectorSource = LibraryVectorSource.AUDIO) =
        LibraryVectorRows(ids, dim, source, fingerprint = matrix.contentHashCode().toLong(),
            readChunk = { start, count, out -> matrix.copyInto(out, 0, start * dim, (start + count) * dim) },
            verify = { true })

    private fun stateOf(snapshot: MapRefreshSnapshot): MapRefreshState =
        assertNotNull(MapRefreshStateCodec.read(ArrayMapRefreshBytes(ArrayMapRefreshSink().also { MapRefreshStateCodec.write(snapshot, it) }.toByteArray())))

    private fun positions(points: List<LayoutPoint>) = points.associate { it.trackId to floatArrayOf(it.x, it.y) }

    private class Fixture(val ids: List<TrackId>, val rows: FloatArray, val dim: Int)

    private fun fixture(n: Int, seed: Int): Fixture = scale.hierarchicalFixture(n, seed, dim = 96).let { Fixture(it.ids, it.rows, it.dim) }

    private fun subset(f: Fixture, keep: (Int) -> Boolean): Pair<List<TrackId>, FloatArray> {
        val rows = f.ids.indices.filter(keep)
        val m = FloatArray(rows.size * f.dim)
        rows.forEachIndexed { slot, r -> f.rows.copyInto(m, slot * f.dim, r * f.dim, (r + 1) * f.dim) }
        return rows.map(f.ids::get) to m
    }

    @Test
    fun `a full build through the refresh entry point draws today's map and stores its state`() = runBlocking {
        val f = fixture(2_100, 3)
        val rows = rowsOf(f.ids, f.rows, f.dim)
        val today = assertNotNull(LibraryLayout.compute(rowsOf(f.ids, f.rows, f.dim)))
        val built = assertNotNull(LibraryLayoutRefresh.build(rows, emptyMap(), "v"))
        assertEquals(today, built.points)
        val snapshot = assertNotNull(built.snapshot)
        assertEquals(f.ids, snapshot.ids)
        assertEquals(LibraryLayout.COMPONENTS, snapshot.components)
        assertEquals(BarnesHutTsne.NEIGHBORS, snapshot.neighbours)
        // The stored rows and lists are exactly the layout's own reduction and neighbour search.
        val reduced = LibraryLayout.reduceForEmbedding(f.rows.copyOf(), f.ids.size, f.dim)
        assertContentEquals(reduced, snapshot.reduced)
        val exact = assertNotNull(BarnesHutTsne.nearestNeighbours(reduced, f.ids.size, snapshot.components, snapshot.neighbours) { true })
        assertContentEquals(exact.index, snapshot.neighbourIds)
    }

    @Test
    fun `patched neighbour lists equal an exact search over random mixes of additions changes and removals`() {
        val random = Random(11)
        repeat(6) { trial ->
            val n0 = 220 + random.nextInt(60)
            val k = 8
            val kk = 20
            val old = FloatArray(n0 * k) { random.nextFloat() }
            val oldNb = assertNotNull(BarnesHutTsne.nearestNeighbours(old, n0, k, kk) { true })
            val oldIds = List(n0) { TrackId("t$it") }
            val state = stateOf(MapRefreshSnapshot("v", LibraryVectorSource.AUDIO, 1, oldIds, k, k, kk, 1f, FloatArray(k), FloatArray(k * k),
                old, oldNb.index, LongArray(n0)))
            // Current library: some old rows removed, some changed, some new, shuffled order.
            val removed = (0 until n0).filter { random.nextInt(100) < 3 + trial }.toSet()
            val changed = (0 until n0).filter { it !in removed && random.nextInt(100) < 2 + trial }.toSet()
            val kept = (0 until n0).filter { it !in removed }
            val added = 1 + random.nextInt(6 + 3 * trial)
            val order = (kept.map { it } + List(added) { -1 - it }).shuffled(random)
            val n = order.size
            val reduced = FloatArray(n * k)
            val flagged = BooleanArray(n)
            val stateOfRow = IntArray(n) { -1 }
            order.forEachIndexed { i, src ->
                if (src >= 0 && src !in changed) { old.copyInto(reduced, i * k, src * k, (src + 1) * k); stateOfRow[i] = src }
                else { for (c in 0 until k) reduced[i * k + c] = random.nextFloat(); flagged[i] = true }
            }
            val currentOf = IntArray(n0) { -1 }
            for (i in 0 until n) if (stateOfRow[i] >= 0) currentOf[stateOfRow[i]] = i
            val patched = LibraryLayoutRefresh.patchNeighbours(reduced, n, k, kk, flagged, stateOfRow, currentOf, state) {}
            val exact = assertNotNull(BarnesHutTsne.nearestNeighbours(reduced, n, k, kk) { true })
            assertContentEquals(exact.index, patched.index, "trial $trial")
            assertContentEquals(exact.distance, patched.distance, "trial $trial")
        }
    }

    /** The refresh, computed independently with an exact neighbour search (what the patch must equal). */
    private fun reference(ids: List<TrackId>, matrix: FloatArray, dim: Int, state: MapRefreshState, previous: Map<TrackId, FloatArray>): List<LayoutPoint> {
        val n = ids.size
        val k = state.components
        val kk = minOf(BarnesHutTsne.NEIGHBORS, n - 1)
        val stored = state.ids.withIndex().associate { it.value to it.index }
        val reduced = FloatArray(n * k)
        val flagged = BooleanArray(n)
        val row = FloatArray(dim)
        for (i in 0 until n) {
            matrix.copyInto(row, 0, i * dim, (i + 1) * dim)
            val s = stored[ids[i]]
            if (s != null && state.hash(s) == MapRefreshRows.hash(row, dim)) state.reducedRow(s, reduced, i * k)
            else { flagged[i] = true; SampledTsne.centerAndUnitize(row, state.mean, dim); Pca.project(row, dim, state.basis, k, reduced, i * k); SampledTsne.unitize(reduced, i * k, k) }
        }
        val nb = assertNotNull(BarnesHutTsne.nearestNeighbours(reduced, n, k, kk) { true })
        val y = FloatArray(n * 2)
        var cx = 0f; var cy = 0f; var c = 0
        for (i in 0 until n) previous[ids[i]]?.let { cx += it[0]; cy += it[1]; c++ }
        cx /= c; cy /= c
        for (i in 0 until n) { val p = previous[ids[i]]; if (p != null && !flagged[i]) { y[i * 2] = p[0]; y[i * 2 + 1] = p[1] } else { y[i * 2] = cx; y[i * 2 + 1] = cy } }
        val factor = state.rawSpread / MapRefreshRows.spread(y)
        for (i in y.indices) y[i] *= factor
        val start = y.copyOf()
        for (i in 0 until n) if (flagged[i]) {
            var x = 0f; var z = 0f; var t = 0
            for (s in 0 until kk) { val j = nb.index[i * kk + s]; if (flagged[j]) continue; x += start[j * 2]; z += start[j * 2 + 1]; if (++t == 5) break }
            if (t > 0) { y[i * 2] = x / t; y[i * 2 + 1] = z / t }
        }
        val p = assertNotNull(BarnesHutTsne.jointProbabilities(nb, n, kk) { true })
        val e = Tsne.initialPositions(n, 0x1A7E27, y)
        BarnesHutTsne.optimize(p, n, e, { true }, 150, 0, Tsne.EXAGGERATION_ITERATIONS)
        return LibraryLayout.normalize(ids, LibraryLayout.alignToPrevious(ids, e, previous, n), n)
    }

    @Test
    fun `a refresh equals the reference refresh bit for bit and marks small changes clean`() = runBlocking {
        val f = fixture(2_200, 5)
        val (oldIds, oldRows) = subset(f) { it % 100 != 7 }
        val built = assertNotNull(LibraryLayoutRefresh.build(rowsOf(oldIds, oldRows, f.dim), emptyMap(), "v"))
        val state = stateOf(assertNotNull(built.snapshot))
        val previous = positions(built.points)
        val refreshed = assertNotNull(LibraryLayoutRefresh.refresh(rowsOf(f.ids, f.rows, f.dim), previous, state, "v"))
        assertTrue(refreshed.refreshed)
        assertEquals(22, refreshed.changedSinceFull)
        assertFalse(refreshed.stale, "a 1 % change is not stale")
        assertEquals(reference(f.ids, f.rows, f.dim, state, previous), refreshed.points)
        // The new state's lists are the exact lists of the refreshed rows.
        val snap = assertNotNull(refreshed.snapshot)
        val exact = assertNotNull(BarnesHutTsne.nearestNeighbours(snap.reduced, f.ids.size, snap.components, snap.neighbours) { true })
        assertContentEquals(exact.index, snap.neighbourIds)
    }

    @Test
    fun `refresh declines what it cannot do and marks larger changes stale`() = runBlocking {
        val f = fixture(2_300, 9)
        val built = assertNotNull(LibraryLayoutRefresh.build(rowsOf(f.ids, f.rows, f.dim), emptyMap(), "v"))
        val state = stateOf(assertNotNull(built.snapshot))
        val previous = positions(built.points)
        val rows = { rowsOf(f.ids, f.rows, f.dim) }
        assertNull(LibraryLayoutRefresh.refresh(rows(), previous, state, "other-version"))
        assertNull(LibraryLayoutRefresh.refresh(rowsOf(f.ids, f.rows, f.dim, LibraryVectorSource.AUDIO_AND_METADATA), previous, state, "v"))
        // No change: refreshed, clean, nothing counted.
        val same = assertNotNull(LibraryLayoutRefresh.refresh(rows(), previous, state, "v"))
        assertEquals(0, same.changedSinceFull)
        assertFalse(same.stale)
        // 3 % changed: refreshed but stale; 30 % changed: declined.
        fun changed(share: Double): FloatArray {
            val m = f.rows.copyOf()
            val random = Random(1)
            for (i in f.ids.indices) if (random.nextDouble() < share) for (d in 0 until f.dim) m[i * f.dim + d] = random.nextFloat()
            return m
        }
        val three = assertNotNull(LibraryLayoutRefresh.refresh(rowsOf(f.ids, changed(0.03), f.dim), previous, state, "v"))
        assertTrue(three.stale)
        assertNull(LibraryLayoutRefresh.refresh(rowsOf(f.ids, changed(0.30), f.dim), previous, state, "v"))
        // Removals count too, and the count accumulates through the stored state.
        val (fewerIds, fewerRows) = subset(Fixture(f.ids, f.rows, f.dim)) { it % 200 != 0 }
        val fewer = assertNotNull(LibraryLayoutRefresh.refresh(rowsOf(fewerIds, fewerRows, f.dim), previous, state, "v"))
        assertEquals(12, fewer.changedSinceFull)
        val again = assertNotNull(LibraryLayoutRefresh.refresh(rowsOf(f.ids, f.rows, f.dim), positions(fewer.points), stateOf(assertNotNull(fewer.snapshot)), "v"))
        assertEquals(24, again.changedSinceFull)
        // Small libraries keep today's exact path: no state, no refresh.
        val small = fixture(1_500, 2)
        assertNull(assertNotNull(LibraryLayoutRefresh.build(rowsOf(small.ids, small.rows, small.dim), emptyMap(), "v")).snapshot)
        assertNull(LibraryLayoutRefresh.refresh(rowsOf(small.ids, small.rows, small.dim), previous, state, "v"))
    }

    @Test
    fun `a cancelled refresh throws instead of returning a partial map`() {
        val f = fixture(2_100, 4)
        runBlocking {
            val built = assertNotNull(LibraryLayoutRefresh.build(rowsOf(f.ids, f.rows, f.dim), emptyMap(), "v"))
            val state = stateOf(assertNotNull(built.snapshot))
            var polls = 0
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                LibraryLayoutRefresh.refresh(rowsOf(f.ids, f.rows, f.dim), positions(built.points), state, "v", isActive = { ++polls < 200 })
            }
        }
    }
}
