/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The audio index as halves on disk (LJIX v4): round trip, the float file's migration, and its safety. */
internal class HalfIndexStoreTest {
    private val directory = Files.createTempDirectory("latentjam-half-index").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun vectors(n: Int, dim: Int = 960, seed: Int = 1): LinkedHashMap<TrackId, FloatArray> {
        val random = Random(seed)
        val out = LinkedHashMap<TrackId, FloatArray>()
        repeat(n) { out[TrackId("t$it")] = FloatArray(dim) { (random.nextDouble() * 2 - 1).toFloat() * 0.06f } }
        return out
    }

    private fun snapshot(n: Int) = vectors(n).let { v ->
        StoredIndexSnapshot(v, v.keys.associateWith { "audio|${it.value}" }, mapOf(TrackId("failed") to "audio|failed"))
    }

    private fun versionOf(file: File) = DataInputStream(file.inputStream()).use { it.readInt(); it.readInt() }

    private fun rounded(v: FloatArray) = FloatArray(v.size) { HalfFloat.round(v[it]) }

    @Test
    fun halvesRoundTripAtHalfTheSize() = runTest {
        val original = snapshot(50)
        FileIndexStore(directory, "floats.bin").saveSnapshot("model", original)
        val half = FileIndexStore(directory, "halves.bin", half = true)
        half.saveSnapshot("model", original)
        val floats = File(directory, "floats.bin").length()
        val halves = File(directory, "halves.bin").length()
        assertEquals(4, versionOf(File(directory, "halves.bin")))
        assertTrue(halves < floats * 0.55, "halves $halves vs floats $floats")
        val loaded = checkNotNull(half.loadSnapshot("model"))
        for ((id, v) in original.entries) assertContentEquals(rounded(v), loaded.entries.getValue(id))
        assertEquals(original.identities, loaded.identities)
        assertEquals(original.failedIdentities, loaded.failedIdentities)
    }

    @Test
    fun aFloatFileIsRewrittenAsHalvesOnItsFirstStreamedLoad() = runTest {
        val original = snapshot(120)
        FileIndexStore(directory, "index.bin").saveSnapshot("model", original)
        val before = File(directory, "index.bin").length()
        val store = FileIndexStore(directory, "index.bin", half = true)
        val rows = LinkedHashMap<TrackId, FloatArray>()
        val failed = checkNotNull(store.loadRows("model") { id, v, _ -> rows[id] = v })
        // The first load delivers the file's floats (the half index rounds them); the file is halves afterwards.
        for ((id, v) in original.entries) assertContentEquals(v, rows.getValue(id))
        assertEquals(original.failedIdentities, failed)
        assertEquals(4, versionOf(File(directory, "index.bin")))
        assertTrue(File(directory, "index.bin").length() < before * 0.55)
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".half") }, "no staged file left")
        val again = checkNotNull(store.loadSnapshot("model"))
        for ((id, v) in original.entries) assertContentEquals(rounded(v), again.entries.getValue(id))
        assertEquals(original.identities, again.identities)
    }

    @Test
    fun aFloatFileIsRewrittenOnASnapshotLoadToo() = runTest {
        FileIndexStore(directory, "index.bin").saveSnapshot("model", snapshot(10))
        checkNotNull(FileIndexStore(directory, "index.bin", half = true).loadSnapshot("model"))
        assertEquals(4, versionOf(File(directory, "index.bin")))
    }

    @Test
    fun aMigrationThatCannotBeStagedLeavesTheFloatFileAsItWas() = runTest {
        val original = snapshot(20)
        FileIndexStore(directory, "index.bin").saveSnapshot("model", original)
        val bytes = File(directory, "index.bin").readBytes()
        // The staging path is taken by a directory: the half file cannot be written.
        File(directory, "index.bin.half").mkdirs()
        val loaded = checkNotNull(FileIndexStore(directory, "index.bin", half = true).loadSnapshot("model"))
        assertContentEquals(bytes, File(directory, "index.bin").readBytes(), "the float file is untouched")
        assertEquals(original.entries.keys, loaded.entries.keys)
    }

    @Test
    fun aFloatStoreKeepsWritingFloats() = runTest {
        val store = FileIndexStore(directory, "text.bin")
        store.saveSnapshot("model", snapshot(5))
        checkNotNull(store.loadSnapshot("model"))
        assertEquals(3, versionOf(File(directory, "text.bin")))
    }

    @Test
    fun aHalfFileIsLeftAlone() = runTest {
        val store = FileIndexStore(directory, "index.bin", half = true)
        store.saveSnapshot("model", snapshot(5))
        val bytes = File(directory, "index.bin").readBytes()
        checkNotNull(store.loadSnapshot("model"))
        assertContentEquals(bytes, File(directory, "index.bin").readBytes())
    }

    /** The fixed snapshot both platforms encode as halves (IosFileIndexStoreTest pins its bytes to the same hash). */
    @Test
    fun halvesAreTheSameBytesAsOnIos() = runTest {
        val fixture = StoredIndexSnapshot(
            entries = linkedMapOf(TrackId("a") to floatArrayOf(0.1f, -0.25f, 1f / 3f), TrackId("b") to floatArrayOf(1f, 0f, -0.5f)),
            identities = mapOf(TrackId("a") to "audio|a"),
            failedIdentities = mapOf(TrackId("c") to "audio|c"),
        )
        FileIndexStore(directory, "fixture.bin", half = true).saveSnapshot("model", fixture)
        var hash = -0x340d631b7bdddcdbL
        for (b in File(directory, "fixture.bin").readBytes()) hash = (hash xor (b.toLong() and 0xff)) * 0x100000001b3L
        println("HALF fixture bytes hash $hash")
        assertEquals(HALF_FIXTURE_BYTES_HASH, hash)
    }

    private companion object {
        const val HALF_FIXTURE_BYTES_HASH = 8796708692808073593L
    }
}
