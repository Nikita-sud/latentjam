/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class SmartHistoryContextTest {

    @Test
    fun `first open seed-only context is exactly the cold-start contract`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 5).map(::track)))
        val cold = CapturingRuntime()
        val firstOpen = CapturingRuntime()

        SmartChain(snapshot, cold).build(
            seedId = TrackId("2"),
            length = 1,
            timeFeatures = FloatArray(5),
        )
        SmartChain(snapshot, firstOpen).build(
            seedId = TrackId("2"),
            length = 1,
            timeFeatures = FloatArray(5),
            historyEvents = listOf(event(2, 123_000, 1f)),
        )

        assertTrue(requireNotNull(cold.history).contentEquals(requireNotNull(firstOpen.history)))
        assertTrue(requireNotNull(cold.medium).contentEquals(requireNotNull(firstOpen.medium)))
        assertTrue(requireNotNull(cold.large).contentEquals(requireNotNull(firstOpen.large)))
        assertTrue(requireNotNull(cold.session).contentEquals(requireNotNull(firstOpen.session)))
        assertTrue(requireNotNull(cold.centroid).contentEquals(requireNotNull(firstOpen.centroid)))
    }

    @Test
    fun `history-only tracks inform state but never enter the candidate pool`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 5).map(::track)))
        val eligible = booleanArrayOf(false, false, false, true, true)
        val runtime = CapturingRuntime()

        val result = SmartChain(snapshot, runtime, eligible).build(
            seedId = TrackId("2"),
            length = 1,
            timeFeatures = FloatArray(5),
            historyEvents = listOf(
                event(0, 0, 0.5f),
                event(1, 60_000, 1f),
                event(2, 120_000, 1f),
            ),
        )

        assertTrue(result.pool.isNotEmpty())
        assertTrue(result.pool.all { it == 3 || it == 4 })
        assertToken(requireNotNull(runtime.history), 2, row = 1, played = 1f)
    }

    @Test
    fun `seed may lead into one same-artist neighbour before artist spacing applies`() {
        val tracks = listOf(
            relatedTrack(0, artist = "Band", first = 1f),
            relatedTrack(1, artist = "Band", first = 0.99f, second = 0.10f),
            relatedTrack(2, artist = "Band", first = 0.98f, second = -0.12f),
            relatedTrack(3, artist = "Other A", first = -0.2f, second = 1f),
            relatedTrack(4, artist = "Other B", first = -0.3f, second = -1f),
        )
        val snapshot = requireNotNull(SmartSnapshot.build(tracks))

        val result = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 2,
            timeFeatures = FloatArray(5),
        )

        assertEquals("Band", snapshot.tracks[result.rows.first()].meta.artist)
        assertTrue(
            snapshot.tracks[result.rows[1]].meta.artist != "Band",
            "the related first hop must not turn into an uninterrupted artist run",
        )
    }

    @Test
    fun `real local history feeds short medium long session and text inputs`() {
        val snapshot = requireNotNull(SmartSnapshot.build((0 until 5).map(::track)))
        val runtime = CapturingRuntime()
        val events = listOf(
            event(0, 0, 0.25f),
            event(1, 60_000, 0.5f, skipped = true),
            event(2, 120_000, 1f),
        )

        SmartChain(snapshot, runtime).build(
            seedId = TrackId("2"),
            length = 1,
            timeFeatures = FloatArray(5),
            historyEvents = events,
        )

        val history = requireNotNull(runtime.history)
        assertToken(history, 0, row = 0, played = 0.25f)
        assertToken(history, 1, row = 0, played = 0.25f)
        assertToken(history, 2, row = 1, played = 0.5f)
        assertToken(history, 3, row = 2, played = 1f)

        val medium = requireNotNull(runtime.medium)
        val large = requireNotNull(runtime.large)
        assertTrue(medium[2] > medium[1] && medium[1] > medium[0])
        assertTrue(large[2] > large[1] && large[1] > large[0])
        assertUnit(medium)
        assertUnit(large)

        val session = requireNotNull(runtime.session)
        assertNear(ln(4f), session[0])
        assertEquals(0f, session[1])
        assertNear(ln(3f), session[2])
        assertNear(1f / 3f, session[3])
        assertNear((0.25f + 0.5f + 1f) / 3f, session[4])

        // Session-text-centroid is the unit-norm, DE-DUPLICATED, unweighted mean of the context
        // tracks' text (A HEAD `_text_centroid`). Context rows [0,0,1,2] collapse to {0,1,2}; the
        // three one-hot text rows mean to (1/3,1/3,1/3,…) → 1/√3 on each of dims 0,1,2.
        val text = requireNotNull(runtime.centroid)
        val expected = 1f / sqrt(3f)
        assertNear(expected, text[0])
        assertNear(expected, text[1])
        assertNear(expected, text[2])
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

    private fun relatedTrack(
        row: Int,
        artist: String,
        first: Float,
        second: Float = 0f,
    ): SmartTrack = SmartTrack(
        id = TrackId(row.toString()),
        audio = FloatArray(PredictorRuntime.EMBEDDING_DIM).also {
            it[0] = first
            it[1] = second
            it[row + 2] = 0.01f
        },
        meta = TrackMeta(
            title = "related$row",
            artist = artist,
            album = if (artist == "Band") "Album" else null,
            genre = "Rock",
            year = 1990,
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

    private fun assertToken(history: FloatArray, slot: Int, row: Int, played: Float) {
        val offset = slot * PredictorRuntime.TOKEN_DIM
        assertNear(1f, history[offset + row])
        assertNear(played, history[offset + PredictorRuntime.EMBEDDING_DIM])
    }

    private fun assertUnit(values: FloatArray) {
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        assertNear(1f, norm)
    }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 1e-4f) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "$actual != $expected")
    }

    private class CapturingRuntime : PredictorRuntime {
        var history: FloatArray? = null
        var medium: FloatArray? = null
        var large: FloatArray? = null
        var session: FloatArray? = null
        var centroid: FloatArray? = null

        override suspend fun load(): Result<Unit> = Result.success(Unit)

        override fun encodeState(
            historySmall: FloatArray,
            historyMedium: FloatArray,
            historyLarge: FloatArray,
            timeFeatures: FloatArray,
            sessionFeatures: FloatArray,
        ): FloatArray {
            history = historySmall.copyOf()
            medium = historyMedium.copyOf()
            large = historyLarge.copyOf()
            session = sessionFeatures.copyOf()
            val newest = (PredictorRuntime.CONTEXT_K - 1) * PredictorRuntime.TOKEN_DIM
            return historySmall.copyOfRange(newest, newest + PredictorRuntime.EMBEDDING_DIM)
        }

        override fun score(
            state: FloatArray,
            candidates: FloatArray,
        ): FloatArray {
            // The fused scorer state is [960 encoder ⊕ 384 session-text-centroid]; capture the tail.
            this.centroid = state.copyOfRange(
                PredictorRuntime.EMBEDDING_DIM,
                PredictorRuntime.SCORER_INPUT_DIM,
            )
            return FloatArray(PredictorRuntime.POOL_SIZE)
        }

        override fun close(): Unit = Unit
    }
}
