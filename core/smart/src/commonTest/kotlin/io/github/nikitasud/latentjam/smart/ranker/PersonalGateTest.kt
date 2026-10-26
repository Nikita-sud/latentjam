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

/**
 * PLAN_093: personalNudge.minTransitions and queueArtistCap.ownExtra parse, and their defaults change nothing. Their
 * effects are measured on the stand (PLAN_093 personal checks), where real histories and sound neighbours exist.
 */
class PersonalGateTest {
    private fun model(extra: String) = RankerModel.parse(
        """{"name": "pg", "features": ["same_family", "same_artist"], "mean": [0, 0], "sd": [1, 1], "weights": [1.0, 0.3],
           "runPenalty": 1.0, "anchor": "mix", "epsilon": 0.0,
           "artistReentry": {"lambda": 5.0, "k": 10, "gamma": 0.7, "credit": 0.5, "creditRun": true}$extra}""",
    )!!

    private val library = (0 until 40).map {
        TrackDescriptor(TrackId("t$it"), title = "Song $it", artist = "Artist ${it % 13}", genre = if (it < 32) "Rock" else "Jazz", durationMs = 200_000, fileName = "t$it.mp3")
    }
    private val seed = library[0]
    private val seedMs = 10_000_000_000L
    private val history = PersonalHistory(
        (0 until 3).flatMap { k -> listOf(HabitListen(seed.id, seedMs - 86_400_000L * (k + 2)), HabitListen(library[35].id, seedMs - 86_400_000L * (k + 2) + 210_000)) }
            .sortedBy { it.startedAtMs },
        seedMs,
    ) { id -> library.first { it.id == id }.artist.orEmpty() }

    private val nudge = """, "personalNudge": {"delta": 1.0, "track": 1.0, "artist": 0.5, "playlist": 0.4, "favourite": 0.3, "frequency": 0.15"""

    private suspend fun plan(m: RankerModel, audio: AudioSource = AudioSource.NONE) =
        SmartRanker(m, null, null, audio = audio, fullModel = m).plan(PlanRequest(seed, library, library, 20, personal = true, history = history, seedMs = seedMs)).ids

    @Test
    fun theDefaultsChangeNothing() = runTest {
        assertEquals(plan(model("$nudge}")), plan(model("$nudge, \"minTransitions\": 1}")))
        val cap = """, "queueArtistCap": {"cap": 3, "window": 10, "lambda": 8.0"""
        val near = object : AudioSource {
            override suspend fun vector(id: TrackId): FloatArray? = null
            override suspend fun neighbours(id: TrackId, k: Int): List<TrackId> = library.filter { it.artist == seed.artist && it.id != seed.id }.map { it.id }.take(k)
        }
        assertEquals(plan(model("$cap}"), near), plan(model("$cap, \"ownExtra\": 0}"), near))
    }

    @Test
    fun theKeysParse() {
        assertEquals(2, model("$nudge, \"minTransitions\": 2}").personalNudge!!.minTransitions)
        assertEquals(1, model("$nudge}").personalNudge!!.minTransitions)
        assertEquals(2, model(""", "queueArtistCap": {"cap": 3, "window": 10, "lambda": 8.0, "ownExtra": 2}""").queueArtistCap!!.ownExtra)
        assertEquals(0, model(""", "queueArtistCap": {"cap": 3, "window": 10, "lambda": 8.0}""").queueArtistCap!!.ownExtra)
    }
}
