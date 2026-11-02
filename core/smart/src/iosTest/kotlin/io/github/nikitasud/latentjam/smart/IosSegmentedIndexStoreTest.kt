/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.convert
import kotlinx.coroutines.runBlocking

/** SegmentedIndexStore over the iOS file store (docs/analysis-094-segments.md, parity): the same rule as on Android. */
@OptIn(ExperimentalForeignApi::class)
internal class IosSegmentedIndexStoreTest {
    private val stem = "smart_index.segtest${Random.nextInt(1_000_000)}"
    private val name = "$stem.bin"

    private fun store() = SegmentedIndexStore(name, { IosFileIndexStore(fileName = it, half = true) }, ::iosIndexFileNames, 4, readsWhole = true)

    @AfterTest
    fun cleanUp() = runBlocking {
        store().clear()
    }

    private fun rows(range: IntRange) = LinkedHashMap<TrackId, FloatArray>().apply {
        for (i in range) this[TrackId("t$i")] = FloatArray(8) { v -> ((i * 13 + v * 5) % 41) / 41f - 0.3f }
    }

    private suspend fun restore(store: IndexStore): List<Pair<String, List<Short>>> {
        val out = ArrayList<Pair<String, List<Short>>>()
        store.loadRows("v") { id, vector, _ -> out += id.value to vector.map(HalfFloat::toHalf) }
        return out
    }

    @Test
    fun segmentsRoundTripInOrderAndOnlyADamagedOneIsLost() = runBlocking {
        val rows = rows(0 until 11)
        store().saveSnapshot("v", StoredIndexSnapshot(rows, rows.keys.associateWith { "#d1:${it.value}" }, mapOf(TrackId("f") to "x")))
        val expected = rows.map { (id, v) -> id.value to v.map(HalfFloat::toHalf) }
        assertEquals(expected, restore(store()))
        assertEquals(mapOf(TrackId("f") to "x"), store().loadRows("v") { _, _, _ -> })
        // Zeros inside a vector still parse as halves; only the manifest's digest finds them.
        val first = assertNotNull(iosIndexFilePath("$stem.s000000.bin"))
        val fd = platform.posix.open(first, platform.posix.O_WRONLY)
        ByteArray(6).usePinned { zeros -> platform.posix.pwrite(fd, zeros.addressOf(0), 6.convert(), 29) }
        platform.posix.close(fd)
        assertNotNull(IosFileIndexStore(fileName = "$stem.s000000.bin", half = true).loadSnapshot("v"), "the damage must parse")
        assertEquals(expected.drop(4), restore(store()))
        val middle = assertNotNull(iosIndexFilePath("$stem.s000001.bin"))
        platform.posix.truncate(middle, 40)
        assertEquals(expected.drop(8), restore(store()))
    }

    @Test
    fun aSingleFileOfAnEarlierBuildIsReadThenSegmented() = runBlocking {
        val rows = rows(0 until 9)
        val snapshot = StoredIndexSnapshot(rows, rows.keys.associateWith { "#d1:${it.value}" })
        IosFileIndexStore(fileName = name, half = true).saveSnapshot("v", snapshot)
        val expected = rows.map { (id, v) -> id.value to v.map(HalfFloat::toHalf) }
        val first = store()
        assertEquals(expected, restore(first))
        first.saveSnapshot("v", snapshot)
        assertFalse(name in iosIndexFileNames(), "the single file goes after the first save")
        assertEquals(3, iosIndexFileNames().count { it.startsWith("$stem.s") })
        assertEquals(expected, restore(store()))
    }
}
