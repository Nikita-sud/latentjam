/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class IosFileIndexStoreTest {

    @Test
    fun snapshotSizeEnvelopeMatchesThe64MiBBoundary() {
        assertFalse(isLoadableIosIndexSnapshotSize(-1L))
        assertTrue(isLoadableIosIndexSnapshotSize(MAX_IOS_INDEX_SNAPSHOT_BYTES))
        assertFalse(isLoadableIosIndexSnapshotSize(MAX_IOS_INDEX_SNAPSHOT_BYTES + 1L))
    }

    @Test
    fun encoderUsesTheSameEnvelopeBeforeAllocatingItsOutput() {
        val id = TrackId("one")
        val failedId = TrackId("failed")
        val snapshot = StoredIndexSnapshot(
            entries = mapOf(id to floatArrayOf(1f, 2f)),
            identities = mapOf(id to "audio-v1|source"),
            failedIdentities = mapOf(failedId to "audio-v1|failed-source"),
        )
        val encoded = encodeIosIndexSnapshot(
            modelVersion = "model",
            snapshot = snapshot,
            maximumSnapshotBytes = Int.MAX_VALUE.toLong(),
        )

        assertContentEquals(
            encoded,
            encodeIosIndexSnapshot(
                modelVersion = "model",
                snapshot = snapshot,
                maximumSnapshotBytes = encoded.size.toLong(),
            ),
        )
        val decoded = assertNotNull(decodeIosIndexSnapshot(encoded, "model"))
        assertContentEquals(snapshot.entries.getValue(id), decoded.entries.getValue(id))
        assertEquals(snapshot.identities, decoded.identities)
        assertEquals(snapshot.failedIdentities, decoded.failedIdentities)
        assertFailsWith<IllegalArgumentException> {
            encodeIosIndexSnapshot(
                modelVersion = "model",
                snapshot = snapshot,
                maximumSnapshotBytes = encoded.size.toLong() - 1L,
            )
        }
    }

    /** The fixed snapshot both platforms encode as halves (HalfIndexStoreTest pins Android's bytes to the same hash). */
    private val halfFixture = StoredIndexSnapshot(
        entries = linkedMapOf(TrackId("a") to floatArrayOf(0.1f, -0.25f, 1f / 3f), TrackId("b") to floatArrayOf(1f, 0f, -0.5f)),
        identities = mapOf(TrackId("a") to "audio|a"),
        failedIdentities = mapOf(TrackId("c") to "audio|c"),
    )

    @Test
    fun halvesEncodeToTheSameBytesAsAndroidAndDecodeRounded() {
        val encoded = encodeIosIndexSnapshot("model", halfFixture, half = true)
        var hash = -0x340d631b7bdddcdbL
        for (b in encoded) hash = (hash xor (b.toLong() and 0xff)) * 0x100000001b3L
        assertEquals(HALF_FIXTURE_BYTES_HASH, hash, "v4 bytes")
        val decoded = assertNotNull(decodeIosIndexSnapshot(encoded, "model"))
        for ((id, v) in halfFixture.entries) {
            assertContentEquals(FloatArray(v.size) { HalfFloat.round(v[it]) }, decoded.entries.getValue(id))
        }
        assertEquals(halfFixture.identities, decoded.identities)
        assertEquals(halfFixture.failedIdentities, decoded.failedIdentities)
    }

    @Test
    fun aFloatFileIsRewrittenAsHalvesOnItsFirstLoad() = kotlinx.coroutines.test.runTest {
        val name = "half-migration-test.bin"
        val floats = IosFileIndexStore(name)
        try {
            floats.saveSnapshot("model", halfFixture)
            val half = IosFileIndexStore(name, half = true)
            val first = assertNotNull(half.loadSnapshot("model"))
            assertEquals(halfFixture.entries.keys, first.entries.keys)
            // Reread: the file now holds halves, so even the float store reads the rounded floats back.
            val again = assertNotNull(floats.loadSnapshot("model"))
            for ((id, v) in halfFixture.entries) {
                assertContentEquals(FloatArray(v.size) { HalfFloat.round(v[it]) }, again.entries.getValue(id))
            }
        } finally {
            floats.clear()
        }
    }

    /** Several rows wider than any buffer, ids and identities of many lengths, and failures. */
    private fun bigSnapshot(): StoredIndexSnapshot {
        val random = kotlin.random.Random(94)
        val entries = LinkedHashMap<TrackId, FloatArray>()
        val identities = LinkedHashMap<TrackId, String>()
        repeat(300) { row ->
            val id = TrackId("track-$row-" + "x".repeat(row % 37))
            entries[id] = FloatArray(960) { random.nextFloat() * 2f - 1f }
            if (row % 3 != 0) identities[id] = "audio|" + "y".repeat(row)
        }
        val failures = (0 until 40).associate { TrackId("failed-$it") to "audio|f".repeat(it + 1) }
        return StoredIndexSnapshot(entries, identities, failures)
    }

    @Test
    fun streamedBytesEqualTheWholeEncodingForEveryBufferSize() {
        val snapshot = bigSnapshot()
        for (half in listOf(false, true)) {
            val whole = encodeIosIndexSnapshot("model", snapshot, half = half)
            for (buffer in listOf(7, 4096, 65_536)) {
                val streamed = ArrayList<Byte>(whole.size)
                writeIosIndexSnapshot("model", snapshot, half = half) { total ->
                    SnapshotWriter(ByteArray(buffer), expected = total) { bytes, count ->
                        for (i in 0 until count) streamed += bytes[i]
                    }
                }
                assertContentEquals(whole, streamed.toByteArray(), "half=$half buffer=$buffer")
            }
        }
    }

    @Test
    fun theDurableWriterRoundTripsAndLeavesNoTemporaryFile() = kotlinx.coroutines.test.runTest {
        val name = "smart-test-durable-${kotlin.random.Random.nextLong()}.bin"
        val store = IosFileIndexStore(name, half = true)
        try {
            val snapshot = bigSnapshot()
            store.saveSnapshot("model", snapshot)
            store.saveSnapshot("model", snapshot)
            val loaded = assertNotNull(store.loadSnapshot("model"))
            assertEquals(snapshot.entries.keys.toList(), loaded.entries.keys.toList())
            for ((id, v) in snapshot.entries) {
                assertContentEquals(FloatArray(v.size) { HalfFloat.round(v[it]) }, loaded.entries.getValue(id))
            }
            assertEquals(snapshot.identities, loaded.identities)
            assertEquals(snapshot.failedIdentities, loaded.failedIdentities)
            val caches = platform.Foundation.NSSearchPathForDirectoriesInDomains(
                platform.Foundation.NSCachesDirectory,
                platform.Foundation.NSUserDomainMask,
                true,
            ).first() as String
            assertFalse(platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath("$caches/$name.tmp"))
        } finally {
            store.clear()
        }
        assertEquals(null, store.loadSnapshot("model"))
    }

    private companion object {
        const val HALF_FIXTURE_BYTES_HASH = 8796708692808073593L
    }
}
