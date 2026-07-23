/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryVectorFusionTest {

    @Test
    fun `fusion preserves the configured share of each cosine space`() {
        val ids = (1..5).map { TrackId(it.toString()) }
        val audio = ids.associateWith { id ->
            if (id.value == "1") floatArrayOf(3f, 4f) else floatArrayOf(1f, 0f)
        }
        val metadata = ids.associateWith { id ->
            if (id.value == "1") floatArrayOf(0f, 2f) else floatArrayOf(0f, 1f)
        }

        val result = assertNotNull(
            LibraryVectorFusion.build(ids, audio, metadata, audioDim = 2, metadataDim = 2),
        )

        assertEquals(LibraryVectorSource.AUDIO_AND_METADATA, result.source)
        assertEquals(4, result.dim)
        val fused = assertNotNull(result.vector(ids.first()))
        assertTrue(abs(fused[0] - 0.3f) < 1e-6f)
        assertTrue(abs(fused[1] - 0.4f) < 1e-6f)
        assertEquals(0f, fused[2])
        assertTrue(abs(fused[3] - kotlin.math.sqrt(0.75f)) < 1e-6f)
    }

    @Test
    fun `metadata covers a cold audio index`() {
        val ids = (1..10).map { TrackId(it.toString()) }
        val metadata = ids.associateWith { floatArrayOf(1f, 0f, 0f) }

        val result = assertNotNull(
            LibraryVectorFusion.build(ids, emptyMap(), metadata, audioDim = 2, metadataDim = 3),
        )

        assertEquals(LibraryVectorSource.METADATA, result.source)
        assertEquals(3, result.dim)
        assertEquals(ids, result.trackIds)
    }

    @Test
    fun `hybrid waits for eighty percent shared coverage`() {
        val ids = (1..10).map { TrackId(it.toString()) }
        val metadata = ids.associateWith { floatArrayOf(1f, 0f) }
        val sevenAudio = ids.take(7).associateWith { floatArrayOf(0f, 1f) }
        val eightAudio = ids.take(8).associateWith { floatArrayOf(0f, 1f) }

        val cold = assertNotNull(
            LibraryVectorFusion.build(ids, sevenAudio, metadata, audioDim = 2, metadataDim = 2),
        )
        val ready = assertNotNull(
            LibraryVectorFusion.build(ids, eightAudio, metadata, audioDim = 2, metadataDim = 2),
        )

        assertEquals(LibraryVectorSource.METADATA, cold.source)
        assertEquals(LibraryVectorSource.AUDIO_AND_METADATA, ready.source)
        assertEquals(10, ready.size, "metadata-only tail tracks must remain in My Mixes")
        assertTrue(
            assertNotNull(ready.vector(ids.last())).contentEquals(floatArrayOf(0f, 0f, 1f, 0f)),
        )
    }

    @Test
    fun `audio carries metadata-poor libraries after analysis`() {
        val ids = (1..10).map { TrackId(it.toString()) }
        val audio = ids.associateWith { floatArrayOf(1f, 0f) }
        val metadata = ids.take(2).associateWith { floatArrayOf(0f, 1f, 0f) }

        val result = assertNotNull(
            LibraryVectorFusion.build(ids, audio, metadata, audioDim = 2, metadataDim = 3),
        )

        assertEquals(LibraryVectorSource.AUDIO, result.source)
        assertEquals(ids, result.trackIds)
    }

    @Test
    fun `invalid rows do not create a fake space`() {
        val id = TrackId("bad")
        val result = LibraryVectorFusion.build(
            ids = listOf(id),
            audio = mapOf(id to floatArrayOf(Float.NaN, 1f)),
            metadata = mapOf(id to floatArrayOf(0f, 0f)),
            audioDim = 2,
            metadataDim = 2,
        )

        assertNull(result)
    }
}
