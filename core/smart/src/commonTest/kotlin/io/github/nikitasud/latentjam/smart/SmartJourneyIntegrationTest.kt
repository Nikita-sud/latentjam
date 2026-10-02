/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.JourneySequencer
import io.github.nikitasud.latentjam.smart.chain.SmartChain
import io.github.nikitasud.latentjam.smart.chain.SmartSnapshot
import io.github.nikitasud.latentjam.smart.chain.SmartTrack
import io.github.nikitasud.latentjam.smart.chain.TrackMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

internal class SmartJourneyIntegrationTest {
    @Test
    fun `engine sequences audio recommendations but preserves marked playlist quota order`() = runTest {
        val random = Random(810)
        val tracks = (0 until 40).map { row ->
            TrackDescriptor(
                id = TrackId("route-$row"), title = "Song $row", artist = "Artist ${row % 3}",
                audioUri = "test://route-$row",
            )
        }
        val vectors = tracks.associateTo(mutableMapOf()) { track ->
            val vector = FloatArray(960) { if (it < 8) (random.nextFloat() - 0.5f) * 0.05f else 0f }
            vector[track.id.value.removePrefix("route-").toInt() % 3] += 1f
            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
            for (i in vector.indices) vector[i] /= norm
            track.id to vector
        }
        val snapshot = requireNotNull(SmartSnapshot.build(tracks.map { track ->
            SmartTrack(
                track.id, vectors.getValue(track.id),
                meta = TrackMeta(track.title, track.artist, null, null, null),
            )
        }))
        val engine = DefaultSimilarityEngine(
            backend = FakeEmbeddingBackend(vectors), index = InMemoryVectorIndex(960),
            store = FakeIndexStore(),
            config = SmartEngineConfig(embeddingDim = 960, modelVersion = "journey-test"),
            dispatcher = Dispatchers.Default.limitedParallelism(1, "journey-test"),
        )
        engine.initialize()
        engine.indexLibrary(tracks)
        val raw = SmartChain(snapshot, null).build(tracks.first().id, 12, FloatArray(5)).rows
        val ordered = JourneySequencer.order(snapshot, raw)
        assertNotEquals(raw, ordered, "fixture must exercise a real order change")
        assertEquals(
            ordered.map { snapshot.tracks[it].id },
            engine.smartQueue(tracks.first(), tracks.drop(1), 12),
        )

        val groups = listOf(setOf(tracks.first().id, tracks[8].id, tracks[19].id, tracks[31].id))
        val grouped = SmartChain(snapshot, null, companionGroups = groups)
            .build(tracks.first().id, 12, FloatArray(5)).rows
        assertEquals(
            grouped.map { snapshot.tracks[it].id },
            engine.smartQueue(tracks.first(), tracks.drop(1), 12, companionGroups = groups),
            "keep-together quota turns must retain the exact planned order",
        )
    }
}
