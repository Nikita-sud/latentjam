/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        val snapshot = StoredIndexSnapshot(
            entries = mapOf(id to floatArrayOf(1f, 2f)),
            identities = mapOf(id to "audio-v1|source"),
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
        assertFailsWith<IllegalArgumentException> {
            encodeIosIndexSnapshot(
                modelVersion = "model",
                snapshot = snapshot,
                maximumSnapshotBytes = encoded.size.toLong() - 1L,
            )
        }
    }
}
