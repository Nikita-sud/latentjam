/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class SessionRecencyTest {

    @Test
    fun `tracks played in the current session stay out of the queue`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 12).map(::track)))

        val result = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 4,
            timeFeatures = FloatArray(5),
            historyEvents = listOf(
                event(1, 0, 1f),
                event(2, 60_000, 1f),
                event(3, 120_000, 1f),
                event(0, 180_000, 1f),
            ),
        )

        assertTrue(
            result.pool.none { it == 1 || it == 2 || it == 3 },
            "session rows must not consume pool slots: ${result.pool}",
        )
        assertTrue(result.rows.none { it == 1 || it == 2 || it == 3 })
        assertEquals(4, result.rows.size)
    }

    private fun track(row: Int): SmartTrack = SmartTrack(
        id = TrackId(row.toString()),
        audio = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { it[row] = 1f },
        text = FloatArray(SmartSnapshot.TEXT_DIM).also { it[row] = 1f },
        meta = TrackMeta(
            title = "title$row",
            artist = "artist$row",
            album = null,
            genre = null,
            year = null,
        ),
    )

    private fun event(
        row: Int,
        timestamp: Long,
        played: Float,
        skipped: Boolean = false,
    ) = SmartHistoryEvent(
        trackId = TrackId(row.toString()),
        startedAtMs = timestamp,
        playedFraction = played,
        completed = played >= 0.8f,
        skipped = skipped,
    )
}
