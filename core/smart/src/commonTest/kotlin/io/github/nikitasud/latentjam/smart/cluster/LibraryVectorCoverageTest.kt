/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.InMemoryVectorIndex
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Map asks which tracks a layout would cover, not what they sound like.
 *
 * On the common path — a cached layout that still covers the library — it reads only
 * `trackIds`, yet building the space to get them allocates the whole fused matrix:
 * 877 tracks × (960 + 384) floats is 4.7 MB per visit, normalised row by row, thrown away
 * unread. [LibraryVectorFusion.coverage] answers the same question without the rows.
 *
 * The invariant that matters is that it selects the SAME population. `LibraryLayout.covers`
 * compares stored positions against this id list; if coverage and build ever disagreed, a
 * library would either recompute t-SNE on every visit or keep a layout that no longer covers
 * it. Both functions therefore share one selection routine, and these tests pin the agreement
 * across every branch of it.
 */
class LibraryVectorCoverageTest {

    private fun ids(count: Int) = (1..count).map { TrackId(it.toString()) }

    @Test
    fun `coverage matches a hybrid build`() {
        val ids = ids(10)
        val audio = ids.associateWith { floatArrayOf(0f, 1f) }
        val metadata = ids.associateWith { floatArrayOf(1f, 0f) }

        val built = assertNotNull(
            LibraryVectorFusion.build(ids, audio, metadata, audioDim = 2, metadataDim = 2),
        )
        val covered = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, metadata, audioDim = 2, metadataDim = 2),
        )

        assertEquals(built.trackIds, covered.trackIds)
        assertEquals(built.source, covered.source)
        assertEquals(built.fingerprint, covered.fingerprint)
        assertEquals(LibraryVectorSource.AUDIO_AND_METADATA, covered.source)
    }

    @Test
    fun `coverage matches a build that falls back to metadata below the hybrid threshold`() {
        val ids = ids(10)
        val metadata = ids.associateWith { floatArrayOf(1f, 0f) }
        val audio = ids.take(7).associateWith { floatArrayOf(0f, 1f) }

        val built = assertNotNull(
            LibraryVectorFusion.build(ids, audio, metadata, audioDim = 2, metadataDim = 2),
        )
        val covered = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, metadata, audioDim = 2, metadataDim = 2),
        )

        assertEquals(LibraryVectorSource.METADATA, built.source, "fixture must exercise the fallback")
        assertEquals(built.trackIds, covered.trackIds)
        assertEquals(built.source, covered.source)
        assertEquals(built.fingerprint, covered.fingerprint)
    }

    @Test
    fun `coverage matches a build carried by audio alone`() {
        val ids = ids(10)
        val audio = ids.associateWith { floatArrayOf(0f, 1f) }

        val built = assertNotNull(
            LibraryVectorFusion.build(ids, audio, emptyMap(), audioDim = 2, metadataDim = 2),
        )
        val covered = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, emptyMap(), audioDim = 2, metadataDim = 2),
        )

        assertEquals(LibraryVectorSource.AUDIO, built.source, "fixture must exercise the audio path")
        assertEquals(built.trackIds, covered.trackIds)
        assertEquals(built.source, covered.source)
        assertEquals(built.fingerprint, covered.fingerprint)
    }

    @Test
    fun `a malformed row is dropped by coverage exactly as the build drops it`() {
        val ids = ids(5)
        // Row 3 is the wrong dimension and row 4 is all zeros: neither is usable, and a space
        // that counted them would hand LibraryLayout ids it has no positions for.
        val audio = mapOf(
            ids[0] to floatArrayOf(0f, 1f),
            ids[1] to floatArrayOf(0f, 1f),
            ids[2] to floatArrayOf(0f, 1f, 5f),
            ids[3] to floatArrayOf(0f, 0f),
            ids[4] to floatArrayOf(1f, 0f),
        )

        val built = assertNotNull(
            LibraryVectorFusion.build(ids, audio, emptyMap(), audioDim = 2, metadataDim = 2),
        )
        val covered = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, emptyMap(), audioDim = 2, metadataDim = 2),
        )

        assertEquals(built.trackIds, covered.trackIds)
        assertEquals(built.fingerprint, covered.fingerprint)
        assertEquals(3, covered.trackIds.size, "two unusable rows must be gone")
    }

    @Test
    fun `coverage is null exactly when there is nothing to build`() {
        assertNull(LibraryVectorFusion.coverage(emptyList(), emptyMap(), emptyMap(), 2, 2))
        assertNull(LibraryVectorFusion.build(emptyList(), emptyMap(), emptyMap(), 2, 2))

        val ids = ids(3)
        assertNull(LibraryVectorFusion.coverage(ids, emptyMap(), emptyMap(), 2, 2))
        assertNull(LibraryVectorFusion.build(ids, emptyMap(), emptyMap(), 2, 2))
    }

    @Test
    fun `the index-backed path agrees with the index-backed build`() {
        val ids = ids(6)
        val audioIndex = InMemoryVectorIndex(dim = 2)
        val metadataIndex = InMemoryVectorIndex(dim = 2)
        ids.forEach { audioIndex.upsert(it, floatArrayOf(0f, 1f)) }
        ids.take(5).forEach { metadataIndex.upsert(it, floatArrayOf(1f, 0f)) }

        val built = assertNotNull(
            LibraryVectorFusion.buildFromIndexes(ids, audioIndex, metadataIndex, 2, 2),
        )
        val covered = assertNotNull(
            LibraryVectorFusion.coverageFromIndexes(ids, audioIndex, metadataIndex, 2, 2),
        )

        assertEquals(built.trackIds, covered.trackIds)
        assertEquals(built.source, covered.source)
        assertEquals(built.fingerprint, covered.fingerprint)
    }

    @Test
    fun `hybrid materialization ignores malformed modalities excluded by coverage`() {
        val ids = ids(10)
        val audio = ids.associateWith { floatArrayOf(0f, 1f) }
        val metadata = buildMap {
            ids.take(8).forEach { put(it, floatArrayOf(1f, 0f)) }
            put(ids[8], floatArrayOf(1f, 0f, 3f))
            put(ids[9], floatArrayOf(Float.NaN, 1f))
        }

        val built = assertNotNull(
            LibraryVectorFusion.build(ids, audio, metadata, audioDim = 2, metadataDim = 2),
        )
        val covered = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, metadata, audioDim = 2, metadataDim = 2),
        )

        assertEquals(LibraryVectorSource.AUDIO_AND_METADATA, built.source)
        assertEquals(built.fingerprint, covered.fingerprint)
        assertEquals(listOf(0f, 1f, 0f, 0f), assertNotNull(built.vector(ids[8])).toList())
        assertTrue(assertNotNull(built.vector(ids[9])).all(Float::isFinite))
    }

    @Test
    fun `raw vector changes invalidate the fingerprint even when normalization is unchanged`() {
        val ids = ids(4)
        val firstAudio = ids.associateWith { floatArrayOf(0f, 1f) }
        val secondAudio = firstAudio + (ids.first() to floatArrayOf(0f, 2f))

        val first = assertNotNull(
            LibraryVectorFusion.coverage(ids, firstAudio, emptyMap(), 2, 2),
        )
        val second = assertNotNull(
            LibraryVectorFusion.coverage(ids, secondAudio, emptyMap(), 2, 2),
        )

        assertEquals(first.trackIds, second.trackIds)
        assertEquals(first.source, second.source)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `library scan order does not change the content fingerprint`() {
        val ids = ids(5)
        val audio = ids.associateWith { id -> floatArrayOf(id.value.toFloat(), 1f) }

        val first = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, emptyMap(), 2, 2),
        )
        val reordered = assertNotNull(
            LibraryVectorFusion.coverage(ids.reversed(), audio, emptyMap(), 2, 2),
        )

        assertEquals(first.trackIds.reversed(), reordered.trackIds)
        assertEquals(first.fingerprint, reordered.fingerprint)
    }

    @Test
    fun `selected source is part of the fingerprint`() {
        val ids = ids(10)
        val audio = ids.associateWith { floatArrayOf(0f, 1f) }
        val metadata = ids.take(8).associateWith { floatArrayOf(1f, 0f) }

        val hybrid = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, metadata, 2, 2, minHybridCoverage = 0.8f),
        )
        val audioOnly = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, metadata, 2, 2, minHybridCoverage = 0.9f),
        )

        assertEquals(hybrid.trackIds, audioOnly.trackIds)
        assertNotEquals(hybrid.source, audioOnly.source)
        assertNotEquals(hybrid.fingerprint, audioOnly.fingerprint)
    }

    @Test
    fun `coverage can be asked repeatedly unlike the one-shot space`() {
        val ids = ids(4)
        val audio = ids.associateWith { floatArrayOf(0f, 1f) }

        val first = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, emptyMap(), audioDim = 2, metadataDim = 2),
        )
        val second = assertNotNull(
            LibraryVectorFusion.coverage(ids, audio, emptyMap(), audioDim = 2, metadataDim = 2),
        )

        assertEquals(first.trackIds, second.trackIds)
        assertEquals(first.source, second.source)
    }
}
