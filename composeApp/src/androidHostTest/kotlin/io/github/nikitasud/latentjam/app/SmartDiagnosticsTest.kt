/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.SmartImpression
import io.github.nikitasud.latentjam.history.SmartImpressionLog
import io.github.nikitasud.latentjam.history.SmartLogStore
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.ranker.PersonalHistory
import io.github.nikitasud.latentjam.smart.ranker.PlanRequest
import io.github.nikitasud.latentjam.smart.ranker.RankerModel
import io.github.nikitasud.latentjam.smart.ranker.SaltedKey
import io.github.nikitasud.latentjam.smart.ranker.SmartRanker
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The debug replay reproduces a logged plan from its seed, press, start time and model, with every hop traced. */
class SmartDiagnosticsTest {
    private class Lines : SmartLogStore {
        val text = StringBuilder()
        override suspend fun append(text: String) { this.text.append(text) }
        override suspend fun size(): Long = text.length.toLong()
        override suspend fun keepTail(maxBytes: Long) = Unit
        override suspend fun forEachLine(line: (String) -> Unit) = text.lines().filter(String::isNotEmpty).forEach(line)
        override suspend fun clear() { text.clear() }
    }

    private fun model(name: String, personal: Boolean) = RankerModel.parse(
        """{"name": "$name", "features": ["same_artist", "same_family"${if (personal) ", \"heard\", \"is_favourite\"" else ""}],
           "mean": [0, 0${if (personal) ", 0, 0" else ""}], "sd": [1, 1${if (personal) ", 1, 1" else ""}],
           "weights": [0.5, 1${if (personal) ", 2, 1" else ""}], "runPenalty": 1, "anchor": "mix", "epsilon": 0.01, "thirdInRowCap": 8}""",
    )!!

    @Test
    fun aLoggedPlanReplaysExactly() = runTest {
        val library = (0 until 40).map {
            TrackDescriptor(TrackId("t$it"), title = "Song $it", artist = "Artist ${it % 5}", genre = listOf("Rock", "Pop")[it % 2],
                folderPath = "Music", fileName = "t$it.mp3", sizeBytes = 1000L + it, durationMs = 200_000)
        }
        val ranker = SmartRanker(model("CORE", false), null, null, fullModel = model("FULL", true))
        val startMs = 50_000_000L
        val events = (0 until 30).map { i ->
            ListenEvent(TrackId("t${(i * 7) % 40}"), 1_000_000L + i * 300_000L, 200_000, 200_000, completed = true, skipped = false,
                listenedMs = 200_000, origin = ListenOrigin(if (i % 3 == 0) ListenStart.USER_PICK else ListenStart.AUTO_ADVANCE))
        }
        val hearts = mapOf(TrackId("t9") to 2_000_000L)
        val log = SmartImpressionLog(Lines())
        for ((personal, press) in listOf(true to 2L, false to 3L)) {
            val seed = library[if (personal) 3 else 8]
            val listens = habitListens(events, library.mapTo(HashSet()) { it.id }, startMs)
            val report = ranker.plan(PlanRequest(
                seed, library, library.filter { it.id != seed.id }, 12, exclude = HabitSlots.novelty(listens, startMs), press = press,
                history = PersonalHistory(listens, startMs, if (personal) hearts else emptyMap()) { "" }, personal = personal,
            ))
            val queue = "${SaltedKey.contentHash(SaltedKey.contentKey(seed)).take(12)}.$press.$startMs"
            log.recordImpressions(report.ids.mapIndexed { i, id ->
                val t = library.first { it.id == id }
                SmartImpression(startMs, queue, 0, i + 1, SaltedKey.contentHash(SaltedKey.contentKey(t)), i + 1, 0.0, 1.0, false, "x", report.model)
            })
        }
        val text = diagnoseRecentSmartStarts(log, ranker, library, events, hearts, useFavourites = true, fullModelName = "FULL")
        val lines = text.lines().filter(String::isNotEmpty)
        assertEquals(2, lines.size)
        for (line in lines) {
            assertTrue("\"replayMatches\":true" in line, line.take(300))
            assertTrue("\"hops\":[{\"hop\":1," in line)
            assertTrue("\"contrib\":[" in line && "\"smartPlays\":" in line)
        }
        assertTrue("\"personal\":true" in lines[0] && "\"model\":\"FULL\"" in lines[0])
        assertTrue("\"personal\":false" in lines[1] && "\"model\":\"CORE\"" in lines[1])
    }
}
