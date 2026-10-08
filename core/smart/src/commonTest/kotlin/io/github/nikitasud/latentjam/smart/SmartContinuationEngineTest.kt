/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.ChainConfig
import io.github.nikitasud.latentjam.smart.chain.ChainTuning
import io.github.nikitasud.latentjam.smart.chain.CompanionMembership
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
import kotlin.test.assertFalse
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

        val second = engine.continueSmartQueue(tail, rest, 3, precedingTrackIds = first.dropLast(1))
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
            .build(tail.id, 3, FloatArray(5), resume = firstChain.walk?.followingOrder(first))
        assertEquals(
            JourneySequencer.order(snapshot, resumed.rows, from = snapshot.rowOf(tail.id)).map { snapshot.tracks[it].id },
            second,
        )
    }

    @Test
    fun `a fresh start at a former plan tail keeps the full library available`() = runTest {
        // These 20 tracks are only planned, never played. Starting from the old tail is a new
        // request, so it must not inherit their exclusions and collapse to the seven other rows.
        for (judged in listOf(false, true)) {
            val engine = engine(continuation = true, judged = judged, runPenalty = 0.5f)
            val first = engine.smartQueue(tracks.first(), tracks, 20)
            assertEquals(20, first.size)
            val tail = tracks.first { it.id == first.last() }
            val expected = engine(continuation = true, judged = judged, runPenalty = 0.5f)
                .smartQueue(tail, tracks, 20)
            val restarted = engine.smartQueue(tail, tracks, 20)

            assertEquals(20, restarted.size, "judged=$judged")
            assertEquals(expected, restarted, "a fresh request must ignore earlier plans")
            assertTrue(restarted.any { it in first }, "unplayed planned tracks must remain available")
        }
    }

    @Test
    fun `an old plan tail without matching queued predecessors starts afresh`() = runTest {
        for (preceding in listOf(emptyList(), listOf(tracks.last().id))) {
            val engine = engine(continuation = true)
            val first = engine.smartQueue(tracks.first(), tracks.drop(1), 3)
            val tail = tracks.first { it.id == first.last() }
            val rest = tracks.filter { it.id !in first.toSet() + tracks.first().id }
            val expected = journey(rest, tail, continuation = true)
            assertTrue(expected.any { it in satellites }, "the fixture must distinguish a fresh seed")

            assertEquals(
                expected,
                engine.continueSmartQueue(tail, rest, 3, precedingTrackIds = preceding),
                "a stale tail is not enough to identify the playing queue",
            )
        }
    }

    @Test
    fun `a one-pick cached plan also requires its preceding planning seed`() = runTest {
        for (preceding in listOf(emptyList(), listOf(tracks.last().id))) {
            val engine = engine(continuation = true)
            val first = engine.smartQueue(tracks.first(), tracks, 1).single()
            val tail = tracks.first { it.id == first }
            val expected = engine(continuation = true).smartQueue(tail, tracks, 3)
            assertTrue(tracks.first().id in expected, "a fresh seed may recommend the original intent again")

            assertEquals(expected, engine.continueSmartQueue(tail, tracks, 3, precedingTrackIds = preceding))
        }
    }

    @Test
    fun `single-track topups validate the planning seed rather than the older walk intent`() = runTest {
        val engine = engine(continuation = true)
        val firstId = engine.smartQueue(tracks.first(), tracks, 1).single()
        val firstTail = tracks.first { it.id == firstId }
        val secondId = engine.continueSmartQueue(
            firstTail, tracks, 1, precedingTrackIds = listOf(tracks.first().id),
        ).single()
        val secondTail = tracks.first { it.id == secondId }
        // The walk still follows the original intent, but the second plan followed firstTail.
        val continued = engine.continueSmartQueue(
            secondTail, tracks, 3, precedingTrackIds = listOf(firstTail.id),
        )
        assertEquals(3, continued.size)
        assertTrue(continued.all { it in neighbours })
        assertTrue(tracks.first().id !in continued, "the original intent remains excluded in this walk")

        val fresh = engine(continuation = true).smartQueue(secondTail, tracks, 3)
        assertTrue(tracks.first().id in fresh)
        assertEquals(
            fresh,
            engine.continueSmartQueue(secondTail, tracks, 3, precedingTrackIds = listOf(tracks.first().id)),
            "matching the old intent alone cannot resume the later one-pick plan",
        )
    }

    @Test
    fun `a continuation request without a matching plan starts afresh`() = runTest {
        val engine = engine(continuation = true)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 3)
        // Seeded with a queued track that is not the plan's last: the listener went elsewhere.
        val elsewhere = tracks.first { it.id == first.first() }
        val rest = tracks.filter { it.id !in first.toSet() + tracks.first().id }
        assertEquals(journey(rest, elsewhere, continuation = true), engine.continueSmartQueue(elsewhere, rest, 3))
    }

    @Test
    fun `removing an upcoming track keeps the walk`() = runTest {
        assertTopUpResumes { queue, plan -> queue.remove(plan[1]) }
    }

    @Test
    fun `moving an upcoming track keeps the walk`() = runTest {
        assertTopUpResumes { queue, plan ->
            queue.remove(plan[2])
            queue.add(1, plan[2])
        }
    }

    @Test
    fun `a Play next row inside the queue's window keeps the walk`() = runTest {
        // Play next lands right after the playing seed; a queue of ten keeps that row in the window.
        assertTopUpResumes { queue, _ -> queue.add(1, tracks.last().id) }
    }

    @Test
    fun `a planned slot skipped as ineligible keeps the walk`() = runTest {
        // The app's chooser passes over a planned track that left the eligible library meanwhile.
        assertTopUpResumes(ineligible = { plan -> setOf(plan[1]) }) { queue, plan -> queue.remove(plan[1]) }
    }

    @Test
    fun `removing the plan's last track resumes the walk at the track before it`() = runTest {
        assertTailEditResumes { queue -> queue.removeAt(queue.lastIndex) }
    }

    @Test
    fun `moving the plan's last track up resumes the walk at the track before it`() = runTest {
        assertTailEditResumes { queue -> queue.add(1, queue.removeAt(queue.lastIndex)) }
    }

    @Test
    fun `removing the plan's last two tracks resumes the walk at the track before them`() = runTest {
        assertTailEditResumes { queue -> repeat(2) { queue.removeAt(queue.lastIndex) } }
    }

    @Test
    fun `a track removed from the end of a plan stays out of the walk's later plans`() = runTest {
        val engine = engine(continuation = true)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 4)
        val removed = tracks.first { it.id == first.last() }
        val queue = (listOf(tracks.first().id) + first).dropLast(1).toMutableList()
        // The controllers keep the removed track itself out of the candidates (recordSmartRemoval and
        // smartTopUpCandidates, see SmartQueueEligibilityTest). Another release of the same song,
        // sounding just like it, is a candidate all the same; only the walk remembers the song.
        val again = removed.copy(id = TrackId("walk-again"), audioUri = "test://walk-again")
        vectors[again.id] = vectors.getValue(removed.id)
        engine.indexLibrary(listOf(again))
        // Two top-ups of three spend the seed's other neighbours; neither release is among them.
        repeat(2) { topUp ->
            val tail = tracks.first { it.id == queue.last() }
            val rest = tracks.filter { it.id !in queue && it.id != removed.id } + again
            val next = engine.continueSmartQueue(tail, rest, 3, precedingTrackIds = queue.dropLast(1).takeLast(10))
            assertEquals(3, next.size, "top-up ${topUp + 1}")
            assertTrue(again.id !in next, "top-up ${topUp + 1} brought the removed song back: $next")
            queue += next
        }
        assertEquals(neighbours - removed.id, queue.filter { it in neighbours }.toSet())
    }

    @Test
    fun `a future the app discarded to replan is free for the replan`() = runTest {
        // The listener changed the artist variety or marked a playlist while the third track played:
        // the app cut the queue back to it and tops it up again. The two tracks it discarded were
        // never removed, so the replan may take them, and the walk spends its neighbourhood on them.
        val engine = engine(continuation = true)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 4)
        val queue = (listOf(tracks.first().id) + first).dropLast(2)
        val tail = tracks.first { it.id == queue.last() }
        val rest = tracks.filter { it.id !in queue }
        val replan = engine.continueSmartQueue(
            tail, rest, neighbours.size + 1 - queue.size, precedingTrackIds = queue.dropLast(1),
        )
        assertTrue(replan.containsAll(first.takeLast(2)), "the discarded tracks stayed spent: $replan")
        assertEquals(neighbours, (queue + replan).filter { it in neighbours }.toSet())
    }

    @Test
    fun `a planned track moved above the last queued track still counts as played`() = runTest {
        // One band, so the artist cap counts exactly the tracks the walk takes as played.
        val band = tracks.map { it.copy(artist = "The Band") }
        suspend fun topUp(
            precedingRows: Int = 10,
            keepRemovedOut: Boolean = true,
            edit: (List<TrackId>) -> List<TrackId>,
        ): List<TrackId> {
            val engine = engine(continuation = true, library = band)
            val first = engine.smartQueue(band.first(), band.drop(1), 4)
            val queue = edit(listOf(band.first().id) + first)
            // As the controllers pass them: nothing queued, nothing the listener removed.
            val removed = if (keepRemovedOut) first.filter { it !in queue }.toSet() else emptySet()
            val rest = band.filter { it.id !in queue && it.id !in removed }
            val tail = band.first { it.id == queue.last() }
            return engine.continueSmartQueue(
                tail, rest, 3, precedingTrackIds = queue.dropLast(1).takeLast(precedingRows),
            )
        }
        // Dragged above the track before it, the plan's last track still plays before the top-up:
        // the walk counts four of its picks as played, and the cap leaves room for two more.
        val moved = topUp { queue -> queue.dropLast(2) + queue.last() + queue[queue.lastIndex - 1] }
        assertEquals(ChainConfig.CHAIN_ARTIST_QUEUE_CAP - 4, moved.size, "moved: $moved")
        // Dragged up past the rows a top-up sends before its seed, it still plays: the walk does
        // not go by those rows but by what the request no longer offers.
        val farUp = topUp(precedingRows = 2) { queue ->
            queue.take(1) + queue.last() + queue.subList(1, queue.lastIndex)
        }
        assertEquals(ChainConfig.CHAIN_ARTIST_QUEUE_CAP - 4, farUp.size, "moved far up: $farUp")
        // Removed by the listener, it is not offered either and counts the same way: the walk errs
        // toward keeping a removed song, and its other releases, out of the plan.
        val removed = topUp { queue -> queue.dropLast(1) }
        assertEquals(ChainConfig.CHAIN_ARTIST_QUEUE_CAP - 4, removed.size, "removed: $removed")
        // Discarded to replan, it is offered again: the walk counts three, and the top-up fills all
        // three slots.
        val discarded = topUp(keepRemovedOut = false) { queue -> queue.dropLast(1) }
        assertEquals(3, discarded.size, "discarded: $discarded")
    }

    @Test
    fun `a new SMART start at an earlier track of a plan still starts afresh`() = runTest {
        // Nothing before it is a new start, which leaves the track alone in a new queue; other
        // tracks before it are another queue.
        for (preceding in listOf(emptyList(), listOf(tracks.last().id))) {
            val engine = engine(continuation = true)
            val first = engine.smartQueue(tracks.first(), tracks.drop(1), 4)
            val tail = tracks.first { it.id == first[2] }
            val rest = tracks.filter { it.id != tail.id && it.id !in preceding }
            val fresh = journey(rest, tail, continuation = true)
            assertTrue(fresh != resumedJourney(first, rest, tail), "the fixture must tell a new walk from the old one")
            assertEquals(fresh, engine.continueSmartQueue(tail, rest, 3, precedingTrackIds = preceding))
        }
    }

    @Test
    fun `a new SMART start at a former plan tail still starts afresh`() = runTest {
        val engine = engine(continuation = true)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 4)
        val tail = tracks.first { it.id == first.last() }
        // Playing a track in SMART leaves it alone in a new queue: nothing precedes it.
        val rest = tracks.filter { it.id != tail.id }
        val fresh = journey(rest, tail, continuation = true)
        assertTrue(fresh != resumedJourney(first, rest, tail), "the fixture must tell a new walk from the old one")
        assertEquals(fresh, engine.continueSmartQueue(tail, rest, 3, precedingTrackIds = emptyList()))
    }

    @Test
    fun `a queue continues a plan while it holds any track that preceded the plan's tail`() {
        val (seed, a, b, c) = listOf("seed", "a", "b", "c").map(::TrackId)
        val planned = listOf(seed, a, b, c)
        assertTrue(continuesSmartPlan(planned, listOf(seed, a, c)), "removed")
        assertTrue(continuesSmartPlan(planned, listOf(seed, c, a, b)), "moved")
        assertTrue(continuesSmartPlan(planned, listOf(TrackId("next"), a, b, c)), "inserted")
        assertTrue(continuesSmartPlan(planned, listOf(TrackId("older"), c)), "mostly removed")
        assertFalse(continuesSmartPlan(planned, emptyList()), "a new start has nothing before its seed")
        assertFalse(continuesSmartPlan(planned, listOf(TrackId("elsewhere"))), "another queue")
        assertFalse(continuesSmartPlan(emptyList(), planned), "nothing was planned")
    }

    @Test
    fun `without the continuation mode the next plan is exactly the default chain`() = runTest {
        val engine = engine(continuation = false)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 3)
        val tail = tracks.first { it.id == first.last() }
        val rest = tracks.filter { it.id !in first.toSet() + tracks.first().id }
        assertEquals(journey(rest, tail, continuation = false), engine.continueSmartQueue(tail, rest, 3))
    }

    @Test
    fun `judged scoring plans with the learned correction and the listener's run penalty`() = runTest {
        val engine = engine(continuation = true, judged = true, runPenalty = 0.5f)
        assertEquals(judgedJourney(tracks.drop(1), tracks.first(), 0.5f), engine.smartQueue(tracks.first(), tracks.drop(1), 5))
        engine.setArtistRunPenalty(2f)
        assertEquals(judgedJourney(tracks.drop(1), tracks.first(), 2f), engine.smartQueue(tracks.first(), tracks.drop(1), 5))
    }

    @Test
    fun `a library of one artist keeps getting SMART plans after the walk spends the artist`() = runTest {
        // A single band: the artist cap ends every walk at its sixth pick, and the next plan resumes it.
        val band = tracks.map { it.copy(artist = "The Band") }
        val index = InMemoryVectorIndex(960)
        val engine = engine(continuation = true, library = band, index = index)
        // The reference walks read the vectors the engine indexed: the fixture's satellites tie exactly,
        // so a rounding difference would break those ties differently.
        val indexed = band.associate { it.id to requireNotNull(index.vector(it.id)) }
        val first = engine.smartQueue(band.first(), band.drop(1), 12)
        assertEquals(ChainConfig.CHAIN_ARTIST_QUEUE_CAP, first.size)
        var queued = first.toSet() + band.first().id
        var tail = band.first { it.id == first.last() }
        var lastPlan = first
        // Each top-up is seeded with the queue's last track, as the app asks; each must still be a chain plan.
        repeat(2) { topUp ->
            val rest = band.filter { it.id !in queued }
            val next = engine.continueSmartQueue(tail, rest, 12, precedingTrackIds = lastPlan.dropLast(1))
            assertEquals(freshPlan(snapshot(band, indexed), rest, tail, 12), next, "top-up ${topUp + 1}")
            assertEquals(ChainConfig.CHAIN_ARTIST_QUEUE_CAP, next.size, "top-up ${topUp + 1}")
            queued = queued + next
            tail = band.first { it.id == next.last() }
            lastPlan = next
        }
    }

    @Test
    fun `under judged scoring marked playlists are points and the run penalty still counts`() = runTest {
        val groups = listOf(setOf(tracks[1].id, tracks[2].id, tracks[3].id))
        val engine = engine(continuation = true, judged = true, runPenalty = 0.5f)
        assertEquals(
            judgedJourney(tracks.drop(1), tracks.first(), 0.5f, groups),
            engine.smartQueue(tracks.first(), tracks.drop(1), 5, companionGroups = groups),
        )
        engine.setArtistRunPenalty(2f)
        assertEquals(
            judgedJourney(tracks.drop(1), tracks.first(), 2f, groups),
            engine.smartQueue(tracks.first(), tracks.drop(1), 5, companionGroups = groups),
        )
    }

    @Test
    fun `without judged scoring marked playlists keep the shipped chain in its planned order`() = runTest {
        val groups = listOf(setOf(tracks[1].id, tracks[2].id, tracks[3].id))
        val snapshot = snapshot()
        val planned = SmartChain(
            snapshot, null, eligible(snapshot, tracks.drop(1)), companionGroups = groups,
            tuning = ChainTuning(continueAfterExhaustion = true),
        ).build(tracks.first().id, 5, FloatArray(5)).rows
        assertEquals(
            planned.map { snapshot.tracks[it].id },
            engine(continuation = true).smartQueue(tracks.first(), tracks.drop(1), 5, companionGroups = groups),
        )
    }

    private fun judgedJourney(
        library: List<TrackDescriptor>,
        seed: TrackDescriptor,
        runPenalty: Float,
        groups: List<Set<TrackId>> = emptyList(),
    ): List<TrackId> {
        val snapshot = snapshot()
        val tuning = ChainTuning(
            continueAfterExhaustion = true, neighbourhoodBonus = 0f, rerankWeights = Rerank.JUDGED_WEIGHTS,
            soundFloor = Rerank.SOUND_FLOOR, artistRunPenalty = runPenalty, companionPoints = Rerank.COMPANION_POINTS,
        )
        val rows = SmartChain(snapshot, null, eligible(snapshot, library), companionGroups = groups, tuning = tuning)
            .build(seed.id, 5, FloatArray(5)).rows
        val companions = if (groups.isEmpty()) null else CompanionMembership.build(snapshot.tracks.map { it.id }, groups)
        return JourneySequencer.order(
            snapshot, rows, sameArtistCost = runPenalty, companions = companions,
            togetherCost = if (companions == null) 0f else Rerank.COMPANION_POINTS.together,
        ).map { snapshot.tracks[it].id }
    }

    /**
     * Plans four tracks from the seed, lets [edit] change that queue the way a listener can without
     * touching its last row, then tops it up from that row as the app asks: the ten rows before it,
     * nothing queued or [ineligible] available. The first plan's walk must carry on there, exactly
     * as an unedited queue continues it, instead of a new walk starting around the tail.
     */
    private suspend fun assertTopUpResumes(
        ineligible: (List<TrackId>) -> Set<TrackId> = { emptySet() },
        edit: (MutableList<TrackId>, List<TrackId>) -> Unit,
    ) {
        val engine = engine(continuation = true)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 4)
        val queue = (listOf(tracks.first().id) + first).toMutableList()
        edit(queue, first)
        assertEquals(first.last(), queue.last(), "the edit must leave the tail in place")
        val tail = tracks.first { it.id == first.last() }
        val unavailable = queue.toSet() + ineligible(first)
        val rest = tracks.filter { it.id !in unavailable }
        val continued = resumedJourney(first, rest, tail)
        assertTrue(continued.all { it in neighbours }, "a resumed walk keeps to the seed's neighbourhood: $continued")
        assertTrue(journey(rest, tail, continuation = true).any { it in satellites }, "a new walk would leave it")

        assertEquals(
            continued,
            engine.continueSmartQueue(tail, rest, 3, precedingTrackIds = queue.dropLast(1).takeLast(10)),
        )
    }

    /**
     * Plans four tracks from the seed, lets [edit] remove or move the queue's last rows, then tops it
     * up from its new last row as the app asks. The first plan's walk must resume at that row, with
     * every track it put after the row still spent, instead of a new walk starting around it.
     */
    private suspend fun assertTailEditResumes(edit: (MutableList<TrackId>) -> Unit) {
        val engine = engine(continuation = true)
        val first = engine.smartQueue(tracks.first(), tracks.drop(1), 4)
        val queue = (listOf(tracks.first().id) + first).toMutableList()
        edit(queue)
        val tail = tracks.first { it.id == queue.last() }
        assertTrue(tail.id in first.dropLast(1), "the edit must end the queue inside the plan")
        // The controllers leave out what is queued and what the listener removed.
        val removed = first.filter { it !in queue }.toSet()
        val rest = tracks.filter { it.id !in queue && it.id !in removed }
        val preceding = queue.dropLast(1).takeLast(10)
        val continued = resumedJourney(first, rest, tail, queuedBefore = preceding)
        assertTrue(continued.all { it in neighbours }, "a resumed walk keeps to the seed's neighbourhood: $continued")
        assertTrue(continued.none { it in first }, "a removed track must not come straight back: $continued")
        assertTrue(journey(rest, tail, continuation = true).any { it in satellites }, "a new walk would leave")

        assertEquals(continued, engine.continueSmartQueue(tail, rest, 3, precedingTrackIds = preceding))
    }

    /**
     * The engine's continuation path: the walk of [plan], planned from the seed, resumed at [tail] with
     * [queuedBefore] before it, and ordered from [tail].
     */
    private fun resumedJourney(
        plan: List<TrackId>,
        library: List<TrackDescriptor>,
        tail: TrackDescriptor,
        queuedBefore: List<TrackId> = emptyList(),
    ): List<TrackId> {
        val snapshot = snapshot()
        val on = ChainTuning(continueAfterExhaustion = true)
        val planned = SmartChain(snapshot, null, eligible(snapshot, tracks.drop(1)), tuning = on)
            .build(tracks.first().id, plan.size, FloatArray(5))
        assertEquals(plan, JourneySequencer.order(snapshot, planned.rows).map { snapshot.tracks[it].id })
        val offered = library.mapTo(HashSet()) { it.id }
        val walk = planned.walk?.followingOrder(plan)?.resumedAt(tail.id, queuedBefore, offered::contains)
        val resumed = SmartChain(snapshot, null, eligible(snapshot, library), tuning = on)
            .build(tail.id, 3, FloatArray(5), resume = walk)
        return JourneySequencer.order(snapshot, resumed.rows, from = snapshot.rowOf(tail.id)).map { snapshot.tracks[it].id }
    }

    /** A walk started afresh from [seed], [library] still eligible: the engine's path without a carried walk. */
    private fun freshPlan(snapshot: SmartSnapshot, library: List<TrackDescriptor>, seed: TrackDescriptor, length: Int): List<TrackId> {
        val rows = SmartChain(
            snapshot, null, eligible(snapshot, library), tuning = ChainTuning(continueAfterExhaustion = true),
        ).build(seed.id, length, FloatArray(5)).rows
        return JourneySequencer.order(snapshot, rows).map { snapshot.tracks[it].id }
    }

    private suspend fun engine(
        continuation: Boolean,
        judged: Boolean = false,
        runPenalty: Float = 0f,
        library: List<TrackDescriptor> = tracks,
        index: InMemoryVectorIndex = InMemoryVectorIndex(960),
    ) = DefaultSimilarityEngine(
        backend = FakeEmbeddingBackend(vectors), index = index,
        store = FakeIndexStore(),
        config = SmartEngineConfig(
            embeddingDim = 960, modelVersion = "walk-test", continueAfterExhaustion = continuation,
            judgedScoring = judged, artistRunPenalty = runPenalty,
        ),
        dispatcher = Dispatchers.Default.limitedParallelism(1, "walk-test"),
    ).also {
        it.initialize()
        it.indexLibrary(library)
    }

    private fun snapshot(library: List<TrackDescriptor> = tracks, audio: Map<TrackId, FloatArray> = vectors) =
        requireNotNull(SmartSnapshot.build(library.map { track ->
            SmartTrack(track.id, audio.getValue(track.id), meta = TrackMeta(track.title, track.artist, null, null, null))
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
