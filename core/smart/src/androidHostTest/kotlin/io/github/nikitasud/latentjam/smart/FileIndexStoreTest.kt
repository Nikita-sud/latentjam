/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FileIndexStoreTest {
    private val directory = Files.createTempDirectory("latentjam-index-test")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun finiteSnapshotRoundTrips() = runTest {
        val store = FileIndexStore(directory.toFile())
        val entries = linkedMapOf(
            TrackId("one") to floatArrayOf(1f, 2f),
            TrackId("two") to floatArrayOf(3f, 4f),
        )

        store.save("model", entries)

        val loaded = checkNotNull(store.load("model"))
        assertEquals(entries.keys, loaded.keys)
        entries.forEach { (id, vector) -> assertContentEquals(vector, loaded.getValue(id)) }
    }

    @Test
    fun identitiesAndOpaqueLongIdsRoundTripWithoutDelimiterCollisions() = runTest {
        val store = FileIndexStore(directory.toFile())
        val id = TrackId("opaque|,\u0000:" + "x".repeat(70_000))
        val failedId = TrackId("failed|,\u0000:" + "y".repeat(70_000))
        val snapshot = StoredIndexSnapshot(
            entries = mapOf(id to floatArrayOf(1f, 2f)),
            identities = mapOf(id to "audio-v1|5:a|b:c|3:x,y"),
            failedIdentities = mapOf(failedId to "audio-v1|failure-content-identity"),
        )

        store.saveSnapshot("model", snapshot)

        val loaded = checkNotNull(store.loadSnapshot("model"))
        assertEquals(setOf(id), loaded.entries.keys)
        assertContentEquals(floatArrayOf(1f, 2f), loaded.entries.getValue(id))
        assertEquals(snapshot.identities, loaded.identities)
        assertEquals(snapshot.failedIdentities, loaded.failedIdentities)
    }

    @Test
    fun v2IdentitySnapshotRemainsReadableWithoutFailureMetadata() = runTest {
        val file = directory.resolve(FileIndexStore.FILE_NAME).toFile()
        DataOutputStream(file.outputStream().buffered()).use { output ->
            writeHeader(
                output,
                dim = 2,
                count = 1,
                formatVersion = FileIndexStore.IDENTITY_FORMAT_VERSION,
            )
            writeV2String(output, "v2")
            output.writeFloat(1f)
            output.writeFloat(0f)
            writeV2String(output, "audio-v1|old-identity")
        }

        val loaded = checkNotNull(FileIndexStore(directory.toFile()).loadSnapshot("model"))
        assertEquals("audio-v1|old-identity", loaded.identities[TrackId("v2")])
        assertTrue(loaded.failedIdentities.isEmpty())
    }

    @Test
    fun legacyVectorOnlySnapshotLoadsWithoutIdentityMetadata() = runTest {
        val file = directory.resolve(FileIndexStore.FILE_NAME).toFile()
        DataOutputStream(file.outputStream().buffered()).use { output ->
            writeHeader(output, dim = 2, count = 1, formatVersion = FileIndexStore.LEGACY_FORMAT_VERSION)
            output.writeUTF("legacy")
            output.writeFloat(1f)
            output.writeFloat(0f)
        }

        val loaded = checkNotNull(FileIndexStore(directory.toFile()).loadSnapshot("model"))
        assertEquals(setOf(TrackId("legacy")), loaded.entries.keys)
        assertTrue(loaded.identities.isEmpty())
    }

    @Test
    fun oversizedDimensionIsRejectedBeforeAllocation() = runTest {
        writeSnapshotHeader(dim = FileIndexStore.MAX_DIMENSION + 1, count = 1)

        assertNull(FileIndexStore(directory.toFile()).load("model"))
    }

    @Test
    fun nonFiniteRowsAreNotRestored() = runTest {
        val file = directory.resolve(FileIndexStore.FILE_NAME).toFile()
        DataOutputStream(file.outputStream().buffered()).use { output ->
            writeHeader(output, dim = 2, count = 2)
            writeV2String(output, "bad")
            output.writeFloat(Float.NaN)
            output.writeFloat(0f)
            output.writeInt(-1)
            writeV2String(output, "good")
            output.writeFloat(1f)
            output.writeFloat(0f)
            output.writeInt(-1)
        }

        val loaded = checkNotNull(FileIndexStore(directory.toFile()).load("model"))
        assertEquals(setOf(TrackId("good")), loaded.keys)
    }

    @Test
    fun trailingBytesInvalidateSnapshot() = runTest {
        val file = directory.resolve(FileIndexStore.FILE_NAME).toFile()
        DataOutputStream(file.outputStream().buffered()).use { output ->
            writeHeader(output, dim = 2, count = 0)
            output.writeByte(1)
        }

        assertNull(FileIndexStore(directory.toFile()).load("model"))
    }

    @Test
    fun saveRejectsNonFiniteVectors() = runTest {
        val store = FileIndexStore(directory.toFile())
        assertFailsWith<IllegalArgumentException> {
            store.save("model", mapOf(TrackId("bad") to floatArrayOf(Float.NEGATIVE_INFINITY)))
        }
    }

    @Test
    fun snapshotSizeEnvelopeMatchesThe64MiBBoundary() {
        assertFalse(isLoadableAndroidIndexSnapshotSize(-1L))
        assertTrue(isLoadableAndroidIndexSnapshotSize(MAX_ANDROID_INDEX_SNAPSHOT_BYTES))
        assertFalse(isLoadableAndroidIndexSnapshotSize(MAX_ANDROID_INDEX_SNAPSHOT_BYTES + 1L))
    }

    @Test
    fun fileLargerThanTheSnapshotEnvelopeIsRejectedBeforeDecoding() = runTest {
        val file = directory.resolve(FileIndexStore.FILE_NAME).toFile()
        RandomAccessFile(file, "rw").use { sparse ->
            sparse.setLength(MAX_ANDROID_INDEX_SNAPSHOT_BYTES + 1L)
        }

        assertNull(FileIndexStore(directory.toFile()).load("model"))
    }

    @Test
    fun rowHeaderMustFitTheDeclaredFileBeforeAnyVectorLoop() = runTest {
        writeSnapshotHeader(dim = FileIndexStore.MAX_DIMENSION, count = 5_000)

        assertNull(FileIndexStore(directory.toFile()).load("model"))
    }

    @Test
    fun saveUsesTheSameSizeEnvelopeAndPreservesThePreviousSnapshotOnRejection() = runTest {
        val fileName = "small-envelope.bin"
        val store = FileIndexStore(
            directory = directory.toFile(),
            fileName = fileName,
            maximumSnapshotBytes = 48L,
        )
        val id = TrackId("one")
        store.save("model", mapOf(id to floatArrayOf(1f)))

        assertFailsWith<IllegalArgumentException> {
            store.saveSnapshot(
                "model",
                StoredIndexSnapshot(
                    entries = mapOf(id to floatArrayOf(1f)),
                    identities = mapOf(id to "x".repeat(20)),
                ),
            )
        }

        val loaded = checkNotNull(store.load("model"))
        assertEquals(setOf(id), loaded.keys)
        assertContentEquals(floatArrayOf(1f), loaded.getValue(id))
    }

    private fun writeSnapshotHeader(dim: Int, count: Int) {
        val file = directory.resolve(FileIndexStore.FILE_NAME).toFile()
        DataOutputStream(file.outputStream().buffered()).use { output ->
            writeHeader(output, dim, count)
        }
    }

    private fun writeHeader(
        output: DataOutputStream,
        dim: Int,
        count: Int,
        formatVersion: Int = FileIndexStore.IDENTITY_FORMAT_VERSION,
    ) {
        output.writeInt(FileIndexStore.MAGIC)
        output.writeInt(formatVersion)
        if (formatVersion == FileIndexStore.LEGACY_FORMAT_VERSION) {
            output.writeUTF("model")
        } else {
            val model = "model".encodeToByteArray()
            output.writeInt(model.size)
            output.write(model)
        }
        output.writeInt(dim)
        output.writeInt(count)
    }

    private fun writeV2String(output: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray()
        output.writeInt(bytes.size)
        output.write(bytes)
    }
}
