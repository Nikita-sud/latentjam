/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class MapRefreshStateTest {
    private fun snapshot(n: Int = 40, dim: Int = 12, k: Int = 5, kk: Int = 7, seed: Int = 1): MapRefreshSnapshot {
        val random = Random(seed)
        return MapRefreshSnapshot(
            inputVersion = "layout-v|audio-v|text-v", source = LibraryVectorSource.AUDIO_AND_METADATA,
            layoutFingerprint = random.nextLong(), ids = List(n) { TrackId("track-$it-ü") }, dim = dim, components = k,
            neighbours = kk, rawSpread = 47.5f,
            mean = FloatArray(dim) { random.nextFloat() }, basis = FloatArray(dim * k) { random.nextFloat() - 0.5f },
            reduced = FloatArray(n * k) { random.nextFloat() }, neighbourIds = IntArray(n * kk) { random.nextInt(n) },
            hashes = LongArray(n) { random.nextLong() },
        )
    }

    private fun assertSame(expected: MapRefreshSnapshot, actual: MapRefreshState) {
        assertEquals(expected.inputVersion, actual.inputVersion)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.layoutFingerprint, actual.layoutFingerprint)
        assertEquals(expected.ids, actual.ids)
        assertEquals(expected.rawSpread, actual.rawSpread)
        assertContentEquals(expected.mean, actual.mean)
        assertContentEquals(expected.basis, actual.basis)
        val row = FloatArray(expected.components)
        for (i in expected.ids.indices) {
            actual.reducedRow(i, row)
            assertContentEquals(expected.reduced.copyOfRange(i * expected.components, (i + 1) * expected.components), row)
            for (s in 0 until expected.neighbours) assertEquals(expected.neighbourIds[i * expected.neighbours + s], actual.neighbour(i, s))
            assertEquals(expected.hashes[i], actual.hash(i))
        }
    }

    private fun encode(s: MapRefreshSnapshot) = ArrayMapRefreshSink().also { MapRefreshStateCodec.write(s, it) }.toByteArray()

    @Test
    fun `codec round trip keeps every field`() {
        val s = snapshot()
        assertSame(s, assertNotNull(MapRefreshStateCodec.read(ArrayMapRefreshBytes(encode(s)))))
    }

    @Test
    fun `any flipped byte or truncation is rejected`() {
        val bytes = encode(snapshot())
        for (at in listOf(0, 9, 40, bytes.size / 2, bytes.size - 9, bytes.size - 1)) {
            val damaged = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x10).toByte() }
            assertNull(MapRefreshStateCodec.read(ArrayMapRefreshBytes(damaged)), "flip at $at")
        }
        for (cut in listOf(0, 8, 15, bytes.size / 3, bytes.size - 1)) {
            assertNull(MapRefreshStateCodec.read(ArrayMapRefreshBytes(bytes.copyOf(cut))), "cut at $cut")
        }
    }

    @Test
    fun `out of range neighbour ids are rejected`() {
        val s = snapshot()
        s.neighbourIds[3] = s.ids.size
        assertNull(MapRefreshStateCodec.read(ArrayMapRefreshBytes(encode(s))))
    }

    @Test
    fun `invalid snapshots cannot be built`() {
        assertFailsWith<IllegalArgumentException> { snapshot().let { MapRefreshSnapshot(it.inputVersion, it.source, 0, it.ids, it.dim, it.components, it.neighbours, 0f, it.mean, it.basis, it.reduced, it.neighbourIds, it.hashes) } }
    }

    @Test
    fun `file store maps a saved state and survives a failed write`() = runBlocking {
        val dir = Files.createTempDirectory("map-refresh").toFile()
        try {
            val store = FileMapRefreshStateStore(dir)
            assertNull(store.load())
            val first = snapshot(seed = 1)
            store.save(first)
            assertSame(first, assertNotNull(store.load()))
            // A write that dies part-way leaves only the temp file; the saved state stays readable.
            File(dir, FileMapRefreshStateStore.FILE_NAME + ".tmp").writeBytes(encode(snapshot(seed = 2)).copyOf(100))
            assertSame(first, assertNotNull(store.load()))
            val second = snapshot(n = 60, seed = 3)
            store.save(second)
            assertSame(second, assertNotNull(store.load()))
            File(dir, FileMapRefreshStateStore.FILE_NAME).writeBytes(ByteArray(64))
            assertNull(store.load())
            store.clear()
            assertNull(store.load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
