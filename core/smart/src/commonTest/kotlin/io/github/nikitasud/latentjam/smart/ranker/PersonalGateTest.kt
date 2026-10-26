/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.HabitListen
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLAN_093: the habit nudge needs minTransitions A → B; the seed's own artist may keep a run when it dominates its sound. */
class PersonalGateTest {
    private val credit = """, "artistReentry": {"lambda": 5.0, "k": 10, "gamma": 0.7, "credit": 0.5, "creditRun": true}"""

    private fun model(extra: String) = RankerModel.parse(
        """{"name": "pg", "features": ["same_family", "same_artist"], "mean": [0, 0], "sd": [1, 1], "weights": [1.0, 0.3],
           "runPenalty": 1.0, "anchor": "mix", "epsilon": 0.0$credit$extra}""",
    )!!

    private val library = (0 until 40).map {
        TrackDescriptor(TrackId("t$it"), title = "Song $it", artist = "Artist ${it % 13}", genre = if (it < 32) "Rock" else "Jazz", durationMs = 200_000, fileName = "t$it.mp3")
    }
    private val seed = library[0]
    private val b = library[35].id   // the seed's family, another artist: one of many near-equal rows; only a nudge lifts it
    private val seedMs = 10_000_000_000L

    /** [times] listens of the seed, each followed by b. */
    private fun history(times: Int): PersonalHistory {
        val listens = (0 until times).flatMap { k ->
            val at = seedMs - 86_400_000L * (k + 2)
            listOf(HabitListen(seed.id, at), HabitListen(b, at + 210_000))
        }.sortedBy { it.startedAtMs }
        return PersonalHistory(listens, seedMs) { id -> library.first { it.id == id }.artist.orEmpty() }
    }

    private suspend fun rank(m: RankerModel, times: Int): Int =
        SmartRanker(m, null, null, fullModel = m).plan(
            PlanRequest(seed, library, library, 20, personal = true, history = history(times), seedMs = seedMs),
        ).ids.indexOf(b).let { if (it < 0) Int.MAX_VALUE else it }

    private val nudge = """, "personalNudge": {"delta": 3.0, "track": 1.0, "artist": 0.5, "playlist": 0.0, "favourite": 0.0, "frequency": 0.0"""

    @Test
    fun oneTransitionNoLongerNudgesWithMinTwo() = runTest {
        val any = model("$nudge}")
        val two = model("$nudge, \"minTransitions\": 2}")
        val none = rank(model("$nudge, \"minTransitions\": 99}"), 1)
        println("DEBUG jazz any1=${rank(any, 1)} any5=${rank(any, 5)} noNudge0=${rank(model(""), 0)} any0=${rank(any, 0)}")
        println("DEBUG nudge none=$none any1=${rank(any, 1)} two1=${rank(two, 1)} two2=${rank(two, 2)} noNudge=${rank(model(""), 1)}")
        assertTrue(rank(any, 1) < none, "today one transition nudges b forward")
        assertEquals(none, rank(two, 1), "minTransitions 2: one transition is noise")
        assertTrue(rank(two, 2) < none, "two transitions still nudge")
    }

    /** Audio neighbours: the seed's own artist (every 13th track) for the seed, nothing else. */
    private val ownNear = object : AudioSource {
        override suspend fun vector(id: TrackId): FloatArray? = null
        override suspend fun neighbours(id: TrackId, k: Int): List<TrackId> =
            if (id == seed.id) library.filter { it.artist == seed.artist && it.id != seed.id }.map { it.id }.take(k) else emptyList()
    }

    @Test
    fun theSeedsOwnArtistKeepsARunOnlyWhenItDominatesItsSound() = runTest {
        val cap = """, "queueArtistCap": {"cap": 2, "window": 10, "lambda": 8.0"""
        suspend fun count(m: RankerModel, audio: AudioSource) =
            SmartRanker(m, null, null, audio = audio).plan(PlanRequest(seed, library, library, 10)).ids.count { id -> library.first { it.id == id }.artist == seed.artist }
        // Only the cap binds here: a strong same-artist weight, no run or re-entry terms.
        fun capModel(x: String) = RankerModel.parse(
            """{"name": "cap", "features": ["same_family", "same_artist"], "mean": [0, 0], "sd": [1, 1], "weights": [0.5, 3.0],
               "runPenalty": 0.0, "anchor": "mix", "epsilon": 0.0$x}""",
        )!!
        val plain = capModel("$cap}")
        val extra = capModel("$cap, \"ownExtra\": 2}")
        println("DEBUG cap plain=${count(plain, ownNear)} extra=${count(extra, ownNear)} extraNone=${count(extra, AudioSource.NONE)}")
        assertEquals(count(plain, ownNear), count(extra, AudioSource.NONE), "not dominant (no neighbours): unchanged")
        assertTrue(count(extra, ownNear) > count(plain, ownNear), "dominant: the seed's artist keeps more of the first 10")
        assertTrue(count(extra, ownNear) <= 4, "bounded: cap + ownExtra (the seed counts)")
        assertEquals(2, extra.queueArtistCap!!.ownExtra)
        assertEquals(2, model("$nudge, \"minTransitions\": 2}").personalNudge!!.minTransitions)
    }
}
