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

    @Test
    fun `a gap longer than the session window ends the session`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 12).map(::track)))
        val gap = 40L * 60 * 1000

        val result = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 4,
            timeFeatures = FloatArray(5),
            historyEvents = listOf(
                event(1, 0, 1f),
                event(2, gap, 1f),
                event(3, gap + 60_000, 1f),
                event(0, gap + 120_000, 1f),
            ),
        )

        assertTrue(
            result.pool.contains(1),
            "row 1 sits on the far side of a 40-minute gap and is a different session",
        )
        assertTrue(result.pool.none { it == 2 || it == 3 })
    }

    @Test
    fun `consecutive gaps define the session, not distance from the newest play`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 12).map(::track)))
        val minute = 60L * 1000

        val result = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 4,
            timeFeatures = FloatArray(5),
            historyEvents = listOf(
                event(1, 0, 1f),
                event(2, 25 * minute, 1f),
                event(3, 50 * minute, 1f),
                event(0, 75 * minute, 1f),
            ),
        )

        // 75 minutes of listening, but no adjacent pair is more than 30 apart: one session.
        assertTrue(
            result.pool.none { it == 1 || it == 2 || it == 3 },
            "no adjacent gap exceeds the window, so all three are the same session",
        )
    }

    @Test
    fun `a track skipped seconds ago is excluded like one played to the end`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 12).map(::track)))

        val result = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 4,
            timeFeatures = FloatArray(5),
            historyEvents = listOf(
                event(1, 0, 0.02f, skipped = true),
                event(0, 30_000, 1f),
            ),
        )

        assertTrue(result.pool.none { it == 1 })
    }

    @Test
    fun `no history excludes nothing so recorded fixtures stay byte-identical`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 12).map(::track)))

        val cold = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 5,
            timeFeatures = FloatArray(5),
        )

        assertEquals(5, cold.rows.size)
        assertEquals(11, cold.pool.size, "every non-seed row stays a candidate on the cold path")
    }

    @Test
    fun `a session larger than the library re-admits the oldest plays first when none were skipped`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 6).map(::track)))

        val result = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 3,
            timeFeatures = FloatArray(5),
            historyEvents = listOf(
                event(1, 0, 1f),
                event(2, 60_000, 1f),
                event(3, 120_000, 1f),
                event(4, 180_000, 1f),
                event(5, 240_000, 1f),
                event(0, 300_000, 1f),
            ),
        )

        // Six tracks: the seed plus five session rows leaves nothing selectable, so three of the
        // five must come back — the three heard longest ago. None were skipped, so the skip/listen
        // grouping is a no-op here and this reduces to plain oldest-first.
        assertEquals(3, result.rows.size, "the queue must still reach the requested length")
        assertTrue(
            result.pool.containsAll(listOf(1, 2, 3)),
            "the three oldest plays are re-admitted: ${result.pool}",
        )
        assertTrue(
            result.pool.none { it == 4 || it == 5 },
            "the two most recent plays stay excluded: ${result.pool}",
        )
    }

    @Test
    fun `re-admission returns listened tracks before skipped ones, even when the skips are older`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 6).map(::track)))

        // Session rows 1 and 2 were SKIPPED early in the session; rows 3 and 4 were LISTENED TO
        // later. A timestamp-only guard would re-admit the skipped tracks first, since they are
        // the oldest session rows — exactly the "hunting for a mood" pattern the spec calls out.
        // Library size 6: seed + 4 session rows leaves only row 5 selectable (available = 1), so
        // with length = 3 the guard must re-admit 2 rows.
        val result = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 3,
            timeFeatures = FloatArray(5),
            historyEvents = listOf(
                event(1, 0, 0.02f, skipped = true),
                event(2, 60_000, 0.05f, skipped = true),
                event(3, 120_000, 1f),
                event(4, 180_000, 1f),
                event(0, 240_000, 1f),
            ),
        )

        assertTrue(
            result.pool.containsAll(listOf(3, 4)),
            "the listened-to tracks are re-admitted ahead of the skipped ones: ${result.pool}",
        )
        assertTrue(
            result.pool.none { it == 1 || it == 2 },
            "the skipped tracks stay excluded even though they are older: ${result.pool}",
        )
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
