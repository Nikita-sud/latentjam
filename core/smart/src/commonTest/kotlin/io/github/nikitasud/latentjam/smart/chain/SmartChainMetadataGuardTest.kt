/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartChainMetadataGuardTest {

    @Test
    fun `unknown artists neither share a spacing window nor an artist cap`() {
        val tracks = (0..12).map { row -> track(row, "Track $row", null) }
        assertEquals(12, build(tracks).rows.size)
    }

    @Test
    fun `a focused three-artist collection can fill a queue without repeating recordings`() {
        val tracks = (0..26).map { row -> track(row, "Song $row", "Artist ${row % 3}") }
        val snapshot = requireNotNull(SmartSnapshot.build(tracks))
        val result = SmartChain(snapshot, runtime = null).build(tracks.first().id, 18, FloatArray(5))

        assertEquals(18, result.rows.size)
        assertEquals(18, result.rows.toSet().size)
        assertTrue(0 !in result.rows)
        val artists = result.rows.map { snapshot.tracks[it].meta.artist }
        assertTrue(artists.zipWithNext().all { (a, b) -> a != b })
    }

    @Test
    fun `a one-artist pool continues to the cap instead of stopping after its first song`() {
        val tracks = (0..12).map { row -> track(row, "Song $row", "Band") }
        val result = build(tracks)
        assertEquals(6, result.rows.size)
        assertEquals(6, result.rows.toSet().size)
        assertTrue(0 !in result.rows)
    }

    @Test
    fun `matching titles by different or unknown artists remain distinct songs`() {
        for (unknownArtist in listOf(false, true)) {
            val tracks = (0..12).map { row ->
                track(row, "Intro (Version $row)", if (unknownArtist) null else "Artist $row")
            }
            assertEquals(12, build(tracks).rows.size)
        }
    }

    @Test
    fun `alternate releases of the seed do not hide an eligible song beyond the retrieval limit`() {
        val copies = (0..100).map { row ->
            track(row, "Intro (Version $row)", "Same Artist").copy(
                audio = FloatArray(960).also { it[0] = 1f; it[row + 1] = 0.01f },
            )
        }
        val other = track(500, "Other Song", "Other Artist")
        val tracks = copies + other
        val snapshot = requireNotNull(SmartSnapshot.build(tracks))
        val result = SmartChain(
            snapshot, runtime = null,
            companionGroups = listOf(copies.mapTo(HashSet()) { it.id }),
        ).build(copies.first().id, 12, FloatArray(5))

        assertEquals(listOf(snapshot.rowOf(other.id)), result.pool)
        assertEquals(listOf(snapshot.rowOf(other.id)), result.rows)
        assertTrue(build(copies).rows.isEmpty(), "Actual repeats remain excluded")
    }

    @Test
    fun `productive retrieval keeps its original pool even when it includes a rejected repeat`() {
        val tracks = listOf(
            track(0, "Intro", "Artist"),
            track(1, "Intro (Remaster)", "Artist"),
            track(2, "Other", "Other Artist"),
        )
        val result = build(tracks)
        assertEquals(setOf(1, 2), result.pool.toSet())
        assertEquals(listOf(2), result.rows)
    }

    private fun track(row: Int, title: String, artist: String?) = SmartTrack(
        id = TrackId(row.toString()),
        audio = FloatArray(960).also { it[row] = 1f },
        meta = TrackMeta(title, artist, null, "Rock", null),
    )

    private fun build(tracks: List<SmartTrack>) = SmartChain(
        requireNotNull(SmartSnapshot.build(tracks)), runtime = null,
    ).build(tracks.first().id, 12, FloatArray(5))

    @Test
    fun `missing titles do not make every untitled candidate look duplicated`() {
        val tracks = (0 until 4).map { row ->
            SmartTrack(
                id = TrackId(row.toString()),
                audio = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { it[row] = 1f },
                meta = TrackMeta(
                    title = if (row % 2 == 0) null else "   ",
                    artist = "artist$row",
                    album = null,
                    genre = null,
                    year = null,
                ),
            )
        }
        val snapshot = requireNotNull(SmartSnapshot.build(tracks))

        val result = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 3,
            timeFeatures = FloatArray(5),
        )

        assertEquals(3, result.rows.size)
    }
}
