/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.ChainTuning
import io.github.nikitasud.latentjam.smart.chain.JourneySequencer
import io.github.nikitasud.latentjam.smart.chain.NEIGHBOURS
import io.github.nikitasud.latentjam.smart.chain.Rerank
import io.github.nikitasud.latentjam.smart.chain.SATELLITE_ROWS
import io.github.nikitasud.latentjam.smart.chain.SmartChain
import io.github.nikitasud.latentjam.smart.chain.SmartSnapshot
import io.github.nikitasud.latentjam.smart.chain.SmartTrack
import io.github.nikitasud.latentjam.smart.chain.TrackMeta
import io.github.nikitasud.latentjam.smart.chain.satelliteVectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app asks for SMART in plans (EngineNextTrackChooser tops the queue up 12 tracks at a time,
 * each request seeded with the queue's last track). In the continuation mode the engine must carry
 * the walk across those requests, or every plan starts a new neighbourhood around its seed.
 */
internal class SmartContinuationEngineTest {
    private val tracks = satelliteVectors().indices.map { row ->
        TrackDescriptor(
            id = TrackId("walk-$row"), title = "Song $row", artist = "Artist $row",
            audioUri = "test://walk-$row",
        )
    }
    private val vectors = tracks.zip(satelliteVectors()).associateTo(mutableMapOf()) { (track, v) -> track.id to v }
    private val neighbours = NEIGHBOURS.map { tracks[it].id }.toSet()
    private val satellites = SATELLITE_ROWS.map { tracks[it].id }.toSet()

    @Test
    fun `the engine carries a continuation walk from one plan to the next`() = runTest {
        val engine = engine(continuation = true)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 3)
        assertTrue(first.all { it in neighbours }, "first plan: $first")
        val queued = first.toSet() + tracks.first().id
        val tail = tracks.first { it.id == first.last() }
        val rest = tracks.filter { it.id !in queued }
        val fresh = journey(rest, tail, continuation = true)
        assertTrue(fresh.any { it in satellites }, "the fixture must reproduce the departure")

        val second = engine.smartQueue(tail, rest, 3)
        assertEquals(3, second.size)
        assertTrue(second.all { it in neighbours && it !in queued }, "second plan left early: $second")

        // Exactly the engine's path: the first plan's walk resumed from its last track, and the plan
        // ordered from that track, since a continued plan has no first recommendation to keep.
        val snapshot = snapshot()
        val on = ChainTuning(continueAfterExhaustion = true)
        val firstChain = SmartChain(snapshot, null, eligible(snapshot, tracks.drop(1)), tuning = on)
            .build(tracks.first().id, 3, FloatArray(5))
        assertEquals(JourneySequencer.order(snapshot, firstChain.rows).map { snapshot.tracks[it].id }, first)
        val resumed = SmartChain(snapshot, null, eligible(snapshot, rest), tuning = on)
            .build(tail.id, 3, FloatArray(5), resume = firstChain.walk)
        assertEquals(
            JourneySequencer.order(snapshot, resumed.rows, from = snapshot.rowOf(tail.id)).map { snapshot.tracks[it].id },
            second,
        )
    }

    @Test
    fun `a request that does not continue a plan starts afresh`() = runTest {
        val engine = engine(continuation = true)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 3)
        // Seeded with a queued track that is not the plan's last: the listener went elsewhere.
        val elsewhere = tracks.first { it.id == first.first() }
        val rest = tracks.filter { it.id !in first.toSet() + tracks.first().id }
        assertEquals(journey(rest, elsewhere, continuation = true), engine.smartQueue(elsewhere, rest, 3))
    }

    @Test
    fun `without the continuation mode the next plan is exactly the default chain`() = runTest {
        val engine = engine(continuation = false)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 3)
        val tail = tracks.first { it.id == first.last() }
        val rest = tracks.filter { it.id !in first.toSet() + tracks.first().id }
        assertEquals(journey(rest, tail, continuation = false), engine.smartQueue(tail, rest, 3))
    }

    @Test
    fun `judged scoring plans with the learned correction and the listener's run penalty`() = runTest {
        val engine = engine(continuation = true, judged = true, runPenalty = 0.5f)
        assertEquals(judgedJourney(tracks.drop(1), tracks.first(), 0.5f), engine.smartQueue(tracks.first(), tracks.drop(1), 5))
        engine.setArtistRunPenalty(2f)
        assertEquals(judgedJourney(tracks.drop(1), tracks.first(), 2f), engine.smartQueue(tracks.first(), tracks.drop(1), 5))
    }

    @Test
    fun `marked playlists keep the shipped chain under judged scoring`() = runTest {
        val groups = listOf(setOf(tracks[1].id, tracks[2].id, tracks[3].id))
        val judged = engine(continuation = true, judged = true, runPenalty = 0.5f)
        val plain = engine(continuation = true)
        assertEquals(
            plain.smartQueue(tracks.first(), tracks.drop(1), 5, companionGroups = groups),
            judged.smartQueue(tracks.first(), tracks.drop(1), 5, companionGroups = groups),
        )
    }

    private fun judgedJourney(library: List<TrackDescriptor>, seed: TrackDescriptor, runPenalty: Float): List<TrackId> {
        val snapshot = snapshot()
        val tuning = ChainTuning(
            continueAfterExhaustion = true, neighbourhoodBonus = 0f, rerankWeights = Rerank.JUDGED_WEIGHTS,
            soundFloor = Rerank.SOUND_FLOOR, artistRunPenalty = runPenalty,
        )
        val rows = SmartChain(snapshot, null, eligible(snapshot, library), tuning = tuning).build(seed.id, 5, FloatArray(5)).rows
        return JourneySequencer.order(snapshot, rows, sameArtistCost = runPenalty).map { snapshot.tracks[it].id }
    }

    private suspend fun engine(continuation: Boolean, judged: Boolean = false, runPenalty: Float = 0f) = DefaultSimilarityEngine(
        backend = FakeEmbeddingBackend(vectors), index = InMemoryVectorIndex(960),
        store = FakeIndexStore(),
        config = SmartEngineConfig(
            embeddingDim = 960, modelVersion = "walk-test", continueAfterExhaustion = continuation,
            judgedScoring = judged, artistRunPenalty = runPenalty,
        ),
        dispatcher = Dispatchers.Default.limitedParallelism(1, "walk-test"),
    ).also {
        it.initialize()
        it.indexLibrary(tracks)
    }

    private fun snapshot() = requireNotNull(SmartSnapshot.build(tracks.map { track ->
        SmartTrack(track.id, vectors.getValue(track.id), meta = TrackMeta(track.title, track.artist, null, null, null))
    }))

    private fun eligible(snapshot: SmartSnapshot, library: List<TrackDescriptor>): BooleanArray {
        val allowed = library.map { it.id }.toSet()
        return BooleanArray(snapshot.size) { snapshot.tracks[it].id in allowed }
    }

    /** What a fresh chain seeded with [seed] plays: the engine's own path without a carried walk. */
    private fun journey(library: List<TrackDescriptor>, seed: TrackDescriptor, continuation: Boolean): List<TrackId> {
        val snapshot = snapshot()
        val rows = SmartChain(
            snapshot, null, eligible(snapshot, library), tuning = ChainTuning(continueAfterExhaustion = continuation),
        ).build(seed.id, 3, FloatArray(5)).rows
        return JourneySequencer.order(snapshot, rows).map { snapshot.tracks[it].id }
    }
}
