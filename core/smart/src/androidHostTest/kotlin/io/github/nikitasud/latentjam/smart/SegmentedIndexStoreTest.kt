/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.oracle093.FileIndexStore093
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The segmented audio snapshot (docs/analysis-094-segments.md) against the frozen 0.9.3 single-file store (G-e), its
 * migration from that file, and a damaged segment.
 */
internal class SegmentedIndexStoreTest {
    private val directory: File = Files.createTempDirectory("lj-segments").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun segmented(dir: File = directory, rows: Int = 5) = SegmentedIndexStore(
        fileName = "smart_index.v.bin",
        segment = { name -> FileIndexStore(dir, name, half = true) },
        names = { dir.list()?.toList().orEmpty() },
        segmentRows = rows,
    )

    /** The index's order semantics (InMemoryVectorIndex): a replaced id keeps its place, a new one goes last. */
    private class Model {
        val rows = LinkedHashMap<TrackId, FloatArray>()
        val identities = HashMap<TrackId, String>()
        val failures = LinkedHashMap<TrackId, String>()
        fun snapshot() = StoredIndexSnapshot(
            LinkedHashMap(rows),
            rows.keys.associateWith { identities.getValue(it) },
            failures.filterKeys { it !in rows },
        )
    }

    /** What an engine adopts from a load: the first row of each id, in delivery order, and the failures not adopted. */
    private class Restored(val ids: List<TrackId>, val halves: List<List<Short>>, val identities: List<String?>, val failures: Map<TrackId, String>)

    private suspend fun restore(store: IndexStore): Restored? {
        val ids = ArrayList<TrackId>()
        val halves = ArrayList<List<Short>>()
        val identities = ArrayList<String?>()
        val seen = HashSet<TrackId>()
        val failed = store.loadRows(VERSION) { id, vector, identity ->
            if (seen.add(id)) {
                ids += id
                halves += vector.map(HalfFloat::toHalf)
                identities += identity
            }
        } ?: return null
        return Restored(ids, halves, identities, failed.filterKeys { it !in seen })
    }

    private fun assertSame(oracle: Restored?, actual: Restored?, step: String) {
        if (oracle == null) {
            assertTrue(actual == null || actual.ids.isEmpty(), "$step: oracle has no snapshot")
            return
        }
        if (actual == null) {
            // No files at all is how an empty snapshot is kept; the engine restores nothing from either.
            assertTrue(oracle.ids.isEmpty() && oracle.failures.isEmpty(), "$step: segmented store has no snapshot")
            return
        }
        assertEquals(oracle.ids, actual.ids, "$step: ids and order")
        assertEquals(oracle.halves, actual.halves, "$step: vectors")
        assertEquals(oracle.identities, actual.identities, "$step: identities")
        assertEquals(oracle.failures, actual.failures, "$step: failures")
    }

    @Test
    fun randomSequencesRestoreWhatTheFrozen093StoreRestores() = runTest {
        val dim = 16
        repeat(SEQUENCES) { sequence ->
            val random = Random(sequence)
            val dir = File(directory, "seq$sequence").apply { mkdirs() }
            val oracleDir = File(directory, "oracle$sequence").apply { mkdirs() }
            val oracle = FileIndexStore093(oracleDir, "smart_index.v.bin", half = true)
            var store = segmented(dir, rows = 1 + random.nextInt(6))
            val model = Model()
            val pool = (0 until 40).map { TrackId("t$it-" + "x".repeat(random.nextInt(3))) }
            fun vector() = FloatArray(dim) { random.nextFloat() * 2f - 1f }
            repeat(OPS) { step ->
                val label = "sequence $sequence step $step"
                when (random.nextInt(12)) {
                    11 -> pool.random(random).takeIf { it !in model.rows }?.let { id ->
                        // A row ahead of every stored one (a seed restored before the rest): the order is rewritten.
                        val rest = LinkedHashMap(model.rows)
                        model.rows.clear()
                        model.rows[id] = vector()
                        model.rows.putAll(rest)
                        model.failures.remove(id)
                        model.identities[id] = "#d1:" + random.nextLong().toULong().toString(16)
                    }
                    0, 1, 2 -> pool.random(random).takeIf { it !in model.rows }?.let { id ->
                        model.failures.remove(id)
                        model.rows[id] = vector()
                        model.identities[id] = "#d1:" + random.nextLong().toULong().toString(16)
                    }
                    3 -> model.rows.keys.randomOrNull(random)?.let { id ->
                        model.rows[id] = vector() // in place, as InMemoryVectorIndex.put keeps the slot
                        if (random.nextBoolean()) model.identities[id] = "#d1:" + random.nextLong().toULong().toString(16)
                    }
                    4 -> model.rows.keys.randomOrNull(random)?.let { model.rows.remove(it) }
                    5 -> pool.random(random).takeIf { it !in model.rows }?.let { model.failures[it] = "failure-${random.nextInt(9)}" }
                    6 -> model.failures.keys.randomOrNull(random)?.let { model.failures.remove(it) }
                    7, 8 -> {
                        val snapshot = model.snapshot()
                        oracle.saveSnapshot(VERSION, snapshot)
                        store.saveSnapshot(VERSION, snapshot)
                    }
                    9 -> {
                        // A cold start: a new store on the same files, restored the way the engine restores.
                        store = segmented(dir, rows = 1 + random.nextInt(6))
                        val actual = restore(store)
                        assertSame(restore(oracle), actual, label)
                        // The engine continues from what it restored.
                        if (actual != null) {
                            model.rows.clear()
                            for ((i, id) in actual.ids.withIndex()) {
                                model.rows[id] = FloatArray(dim) { HalfFloat.toFloat(actual.halves[i][it]) }
                                model.identities[id] = checkNotNull(actual.identities[i])
                            }
                            model.failures.clear()
                            model.failures.putAll(actual.failures)
                        }
                    }
                    10 -> if (random.nextInt(8) == 0) {
                        oracle.clear()
                        store.clear()
                        model.rows.clear()
                        model.failures.clear()
                        assertEquals(emptyList(), dir.list()!!.filter { it.endsWith(".bin") }, "$label: clear")
                    }
                }
            }
            val snapshot = model.snapshot()
            oracle.saveSnapshot(VERSION, snapshot)
            store.saveSnapshot(VERSION, snapshot)
            assertSame(restore(oracle), restore(segmented(dir)), "sequence $sequence final")
        }
    }

    @Test
    fun onlyChangedSegmentsAreWritten() = runTest {
        val store = segmented(rows = 4)
        val rows = LinkedHashMap<TrackId, FloatArray>()
        repeat(10) { rows[TrackId("t$it")] = FloatArray(8) { v -> (it * 8 + v) / 100f } }
        val identities = rows.keys.associateWith { "#d1:${it.value}" }
        store.saveSnapshot(VERSION, StoredIndexSnapshot(rows, identities))
        val stamps = directory.listFiles()!!.filter { it.name.contains(".s") }.associate { it.name to it.lastModified() }
        assertEquals(3, stamps.size)
        Thread.sleep(1_100)
        rows[TrackId("t10")] = FloatArray(8) { 0.5f }
        store.saveSnapshot(VERSION, StoredIndexSnapshot(rows, identities + (TrackId("t10") to "#d1:t10")))
        val after = directory.listFiles()!!.filter { it.name.contains(".s") }.associate { it.name to it.lastModified() }
        // The two full segments are untouched; the last one, which took the new row, is written under a new number.
        assertEquals(
            mapOf("smart_index.v.s000000.bin" to stamps["smart_index.v.s000000.bin"], "smart_index.v.s000001.bin" to stamps["smart_index.v.s000001.bin"]),
            after.filterKeys { it != "smart_index.v.s000003.bin" },
        )
        assertTrue("smart_index.v.s000003.bin" in after, "the changed segment under a new number")
    }

    @Test
    fun aSingleFileOfAnEarlierBuildIsReadThenSegmented() = runTest {
        val old = FileIndexStore093(directory, "smart_index.v.bin", half = true)
        val rows = LinkedHashMap<TrackId, FloatArray>()
        repeat(12) { rows[TrackId("t$it")] = FloatArray(8) { v -> (it - v) / 50f } }
        val snapshot = StoredIndexSnapshot(rows, rows.keys.associateWith { "#d1:${it.value}" }, mapOf(TrackId("bad") to "f"))
        old.saveSnapshot(VERSION, snapshot)
        val expected = restore(old)

        val store = segmented(rows = 5)
        assertSame(expected, restore(store), "migration read")
        store.saveSnapshot(VERSION, snapshot)
        assertTrue(!File(directory, "smart_index.v.bin").exists(), "the single file goes after the first save")
        // Three segments of rows and one holding the failure.
        assertEquals(4, directory.list()!!.count { it.matches(Regex("smart_index\\.v\\.s\\d{6}\\.bin")) })
        assertSame(expected, restore(segmented(rows = 5)), "after migration")
    }

    @Test
    fun aMigrationCutShortStillRestoresEveryRow() = runTest {
        val old = FileIndexStore093(directory, "smart_index.v.bin", half = true)
        val rows = LinkedHashMap<TrackId, FloatArray>()
        repeat(12) { rows[TrackId("t$it")] = FloatArray(8) { v -> (it + v) / 70f } }
        val snapshot = StoredIndexSnapshot(rows, rows.keys.associateWith { "#d1:${it.value}" })
        old.saveSnapshot(VERSION, snapshot)
        // The first segment written, then the process died: the single file is still there.
        FileIndexStore(directory, "smart_index.v.s000000.bin", half = true)
            .saveSnapshot(VERSION, StoredIndexSnapshot(LinkedHashMap(rows.entries.take(5).associate { it.key to it.value }), snapshot.identities))
        val restored = assertNotNull(restore(segmented(rows = 5)))
        assertEquals(rows.keys.toList(), restored.ids, "every row, the segment's first, then the rest of the single file")
    }

    @Test
    fun aDamagedSegmentLosesOnlyItsRows() = runTest {
        for (damage in listOf("truncated", "zeroed")) {
            val dir = File(directory, damage).apply { mkdirs() }
            val store = segmented(dir, rows = 4)
            val rows = LinkedHashMap<TrackId, FloatArray>()
            repeat(12) { rows[TrackId("t$it")] = FloatArray(8) { v -> (it + v) / 30f } }
            store.saveSnapshot(VERSION, StoredIndexSnapshot(rows, rows.keys.associateWith { "#d1:${it.value}" }))
            val middle = File(dir, "smart_index.v.s000001.bin")
            RandomAccessFile(middle, "rw").use { file ->
                if (damage == "truncated") file.setLength(file.length() / 2) else {
                    // Inside the first row's vector (header 21 bytes, id "t4" 6): zero halves still parse.
                    file.seek(29)
                    file.write(ByteArray(6))
                }
            }
            if (damage == "zeroed") {
                assertNotNull(FileIndexStore(dir, middle.name, half = true).loadRows(VERSION) { _, _, _ -> }, "the damage must pass the structural checks")
            }
            val restored = assertNotNull(restore(segmented(dir, rows = 4)), damage)
            assertEquals((0 until 4).map { TrackId("t$it") } + (8 until 12).map { TrackId("t$it") }, restored.ids, damage)
        }
    }

    /** A file store whose writes and deletes stop working after [budget] of them: the process dies there. */
    private class Dying(private val inner: IndexStore, private val budget: IntArray) : IndexStore by inner {
        private fun spend() {
            if (budget[0]-- <= 0) throw IllegalStateException("killed")
        }
        override suspend fun saveSnapshot(modelVersion: String, snapshot: StoredIndexSnapshot) {
            spend()
            inner.saveSnapshot(modelVersion, snapshot)
        }
        override suspend fun clear() {
            spend()
            inner.clear()
        }
    }

    @Test
    fun aSaveCutAtAnyFileRestoresExactlyTheOldOrTheNewSnapshot() = runTest {
        fun rows(range: IntRange, salt: Int) = LinkedHashMap<TrackId, FloatArray>().apply {
            for (i in range) this[TrackId("t$i")] = FloatArray(8) { v -> ((i * 31 + v * 7 + salt) % 97) / 97f }
        }
        val before = rows(0..13, 0)
        // Removals, an in-place change, appends past a segment and a new failure: every kind of file a save touches.
        val after = LinkedHashMap(before).apply {
            remove(TrackId("t2")); remove(TrackId("t9"))
            this[TrackId("t5")] = FloatArray(8) { 0.25f }
            putAll(rows(14..22, 1))
        }
        fun snapshot(rows: Map<TrackId, FloatArray>, failed: Map<TrackId, String>) =
            StoredIndexSnapshot(LinkedHashMap(rows), rows.keys.associateWith { "#d1:${it.value}" + if (it.value == "t5" && rows === after) "x" else "" }, failed)
        val old = snapshot(before, mapOf(TrackId("f1") to "a"))
        val new = snapshot(after, mapOf(TrackId("f1") to "a", TrackId("f2") to "b"))
        val oracleOld = File(directory, "o-old").apply { mkdirs() }
        val oracleNew = File(directory, "o-new").apply { mkdirs() }
        FileIndexStore093(oracleOld, "x.bin", half = true).saveSnapshot(VERSION, old)
        FileIndexStore093(oracleNew, "x.bin", half = true).saveSnapshot(VERSION, new)
        val expectedOld = restore(FileIndexStore093(oracleOld, "x.bin", half = true))
        val expectedNew = restore(FileIndexStore093(oracleNew, "x.bin", half = true))
        var cuts = 0
        for (budget in 0..40) {
            val dir = File(directory, "cut$budget").apply { mkdirs() }
            segmented(dir, rows = 4).apply {
                loadRows(VERSION) { _, _, _ -> }
                saveSnapshot(VERSION, old)
            }
            val left = intArrayOf(budget)
            val dying = SegmentedIndexStore("smart_index.v.bin", { Dying(FileIndexStore(dir, it, half = true), left) }, { dir.list()!!.toList() }, 4)
            dying.loadRows(VERSION) { _, _, _ -> }
            val completed = runCatching { dying.saveSnapshot(VERSION, new) }.isSuccess
            val restored = restore(segmented(dir, rows = 4))
            if (completed) {
                assertSame(expectedNew, restored, "budget $budget (completed)")
                break
            }
            cuts++
            val matchesOld = runCatching { assertSame(expectedOld, restored, "old") }.isSuccess
            val matchesNew = runCatching { assertSame(expectedNew, restored, "new") }.isSuccess
            assertTrue(matchesOld || matchesNew, "budget $budget: a mix of old and new")
        }
        assertTrue(cuts >= 4, "the save must touch several files (cut at $cuts points)")
    }

    @Test
    fun segmentsCutAtTheirByteSizeRestoreInOrder() = runTest {
        val dir = File(directory, "bytes").apply { mkdirs() }
        val store = SegmentedIndexStore("smart_index.v.bin", { FileIndexStore(dir, it, half = true) }, { dir.list()!!.toList() }, segmentRows = 1_000, segmentBytes = 300)
        val rows = LinkedHashMap<TrackId, FloatArray>()
        repeat(20) { rows[TrackId("t$it")] = FloatArray(32) { v -> (it + v) / 90f } }
        val snapshot = StoredIndexSnapshot(rows, rows.keys.associateWith { "#d1:" + "x".repeat(40) + it.value })
        store.saveSnapshot(VERSION, snapshot)
        assertTrue(dir.list()!!.count { it.matches(Regex("smart_index\\.v\\.s\\d{6}\\.bin")) } >= 5, "cut by bytes, not rows")
        val oracle = FileIndexStore093(File(directory, "bytes-o").apply { mkdirs() }, "x.bin", half = true)
        oracle.saveSnapshot(VERSION, snapshot)
        assertSame(restore(oracle), restore(SegmentedIndexStore("smart_index.v.bin", { FileIndexStore(dir, it, half = true) }, { dir.list()!!.toList() }, 1_000, 300)), "byte cut")
    }

    /**
     * Cuts [action] after each of its file writes and deletes in turn; every cut restores exactly [old] or [new]
     * (null: nothing). [prepare] makes the files before it; [action] runs on a store whose files stop working.
     */
    private suspend fun cutEverywhere(
        name: String,
        old: Restored?,
        new: Restored?,
        prepare: suspend (File) -> Unit,
        action: suspend (SegmentedIndexStore) -> Unit,
    ) {
        var cuts = 0
        for (budget in 0..60) {
            val dir = File(directory, "$name$budget").apply { mkdirs() }
            prepare(dir)
            val left = intArrayOf(budget)
            val dying = SegmentedIndexStore("smart_index.v.bin", { Dying(FileIndexStore(dir, it, half = true), left) }, { dir.list()!!.toList() }, 4)
            dying.loadRows(VERSION) { _, _, _ -> }
            val completed = runCatching { action(dying) }.isSuccess && left[0] >= 0
            val restored = restore(segmented(dir, rows = 4))
            fun same(expected: Restored?) = runCatching { assertSame(expected, restored, name) }.isSuccess
            if (completed) {
                assertTrue(same(new), "$name budget $budget (completed)")
                break
            }
            cuts++
            assertTrue(same(old) || same(new), "$name budget $budget: a mix of old and new")
        }
        assertTrue(cuts >= 2, "$name must touch several files (cut at $cuts points)")
    }

    private fun rowsOf(range: IntRange, salt: Int) = LinkedHashMap<TrackId, FloatArray>().apply {
        for (i in range) this[TrackId("t$i")] = FloatArray(8) { v -> ((i * 31 + v * 7 + salt) % 97) / 97f }
    }

    private suspend fun expected(snapshot: StoredIndexSnapshot, tag: String): Restored? {
        val dir = File(directory, "expect-$tag").apply { mkdirs() }
        val oracle = FileIndexStore093(dir, "x.bin", half = true)
        oracle.saveSnapshot(VERSION, snapshot)
        return restore(oracle)
    }

    @Test
    fun aMigrationAFreshOrderAndAClearCutAnywhereRestoreOldOrNew() = runTest {
        val before = rowsOf(0..13, 0)
        val old = StoredIndexSnapshot(before, before.keys.associateWith { "#d1:${it.value}" }, mapOf(TrackId("f1") to "a"))
        val expectedOld = expected(old, "old")
        // Migration: the 0.9.3 single file, segmented by the first save.
        cutEverywhere("migration", expectedOld, expectedOld, prepare = { dir ->
            FileIndexStore093(dir, "smart_index.v.bin", half = true).saveSnapshot(VERSION, old)
        }) { it.saveSnapshot(VERSION, old) }
        // A row ahead of every stored one: everything is written afresh over the committed set.
        val reordered = LinkedHashMap<TrackId, FloatArray>().apply { this[TrackId("seed")] = FloatArray(8) { 0.5f }; putAll(before) }
        val fresh = StoredIndexSnapshot(reordered, reordered.keys.associateWith { "#d1:${it.value}" }, mapOf(TrackId("f1") to "a"))
        cutEverywhere("fresh", expectedOld, expected(fresh, "fresh"), prepare = { dir ->
            segmented(dir, rows = 4).saveSnapshot(VERSION, old)
        }) { it.saveSnapshot(VERSION, fresh) }
        // A clear: the old snapshot or nothing, never part of it.
        cutEverywhere("clear", expectedOld, null, prepare = { dir ->
            segmented(dir, rows = 4).saveSnapshot(VERSION, old)
        }) { it.clear() }
    }

    @Test
    fun failedSavesLeaveNoUnnamedSegmentFiles() = runTest {
        var allowed = Int.MAX_VALUE
        fun store() = SegmentedIndexStore(
            fileName = "smart_index.v.bin",
            segment = { name ->
                val real = FileIndexStore(directory, name, half = true)
                object : IndexStore by real {
                    override suspend fun saveSnapshot(modelVersion: String, snapshot: StoredIndexSnapshot) {
                        if (".s" in name.removePrefix("smart_index.v") && allowed-- <= 0) throw java.io.IOException("No space left on device")
                        real.saveSnapshot(modelVersion, snapshot)
                    }
                }
            },
            names = { directory.list()?.toList().orEmpty() },
            segmentRows = 5,
        )
        val rows = LinkedHashMap<TrackId, FloatArray>()
        for (i in 0 until 20) rows[TrackId("t$i")] = floatArrayOf(1f, i.toFloat(), 0f, 0f)
        val snapshot = StoredIndexSnapshot(rows, rows.keys.associateWith { "id-${it.value}" })
        fun segmentFiles() = directory.list().orEmpty().filter { it.startsWith("smart_index.v.s") && it.endsWith(".bin") }
        repeat(3) { attempt ->
            allowed = 2 // the disk takes two more segments, then is full
            kotlin.test.assertFailsWith<java.io.IOException> { store().saveSnapshot(VERSION, snapshot) }
            kotlin.test.assertNull(store().loadRows(VERSION) { _, _, _ -> })
            assertEquals(0, segmentFiles().size, "attempt $attempt left unnamed segments")
        }
    }

    private companion object {
        const val VERSION = "v"
        const val SEQUENCES = 200
        const val OPS = 60
    }
}
