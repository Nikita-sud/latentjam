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

    private companion object {
        const val HALF_FIXTURE_BYTES_HASH = 8796708692808073593L
    }
}
