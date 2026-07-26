/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.ScoredTrack
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HybridSearchTest {

    private val exact = track("exact", "Rock", "Different Artist")
    private val prefix = track("prefix", "Rockstar", "Different Artist")
    private val semantic = track("semantic", "Unrelated title", "Guitar Band")
    private val weak = track("weak", "Another title", "Someone")

    /**
     * Pad a head of scored rows into a full, score-descending candidate list with a low background
     * band, so the absolute confidence gate has the >= [SemanticGate.BG_HI] rows it needs. Padding
     * ids are fake and never resolve to a track, so only the head can appear in results.
     */
    private fun ranked(
        head: List<ScoredTrack>,
        background: Float = 0.05f,
        total: Int = 160,
    ): List<ScoredTrack> {
        val list = head.toMutableList()
        var i = 0
        while (list.size < total) {
            list.add(ScoredTrack(TrackId("bg-$i"), background))
            i++
        }
        return list.sortedByDescending { it.score }
    }

    @Test
    fun `lexical matches lead semantic expansion`() {
        val result = hybridSearch(
            songs = listOf(weak, prefix, semantic, exact),
            query = "Rock",
            semantic = ranked(
                listOf(
                    ScoredTrack(semantic.id, 0.70f),
                    ScoredTrack(exact.id, 0.68f),
                ),
            ),
        )

        assertEquals(listOf(exact.id, prefix.id, semantic.id), result.map { it.id })
    }

    @Test
    fun `a confident semantic cluster expands beyond literal search`() {
        val result = hybridSearch(
            songs = listOf(semantic),
            query = "energetic guitars",
            semantic = ranked(listOf(ScoredTrack(semantic.id, 0.61f))),
        )

        assertEquals(listOf(semantic.id), result.map { it.id })
    }

    @Test
    fun `a diffuse semantic section is gated by margin`() {
        // Top-1 clears 0.45 but the whole band sits just below it — a diffuse blob, not a cluster.
        val result = hybridSearch(
            songs = listOf(semantic, weak),
            query = "energetic guitars",
            semantic = ranked(listOf(ScoredTrack(semantic.id, 0.60f)), background = 0.50f),
        )

        assertEquals(emptyList(), result.map { it.id })
    }

    @Test
    fun `a weak top-1 semantic section is gated by threshold`() {
        val result = hybridSearch(
            songs = listOf(semantic),
            query = "energetic guitars",
            semantic = ranked(listOf(ScoredTrack(semantic.id, 0.40f))),
        )

        assertEquals(emptyList(), result.map { it.id })
    }

    @Test
    fun `too few semantic candidates decline`() {
        val result = hybridSearch(
            songs = listOf(semantic),
            query = "energetic guitars",
            semantic = listOf(ScoredTrack(semantic.id, 0.90f)), // one row, no background band
        )

        assertEquals(emptyList(), result.map { it.id })
    }

    @Test
    fun `multilingual semantic result expands Cyrillic morphology without a word rule`() {
        val girl = track(
            id = "girl",
            title = "О том, как девочка Алёна стала женщиной",
            artist = "GSPD",
        )
        val kino = track("kino", "Перемен", "КИНО")

        val result = hybridSearch(
            songs = listOf(kino, girl),
            query = "девушки",
            // Kino sits below the background floor, so a fired section never reaches it.
            semantic = ranked(
                listOf(
                    ScoredTrack(girl.id, 0.82f),
                    ScoredTrack(kino.id, 0.02f),
                ),
            ),
        )

        assertEquals(listOf(girl.id), result.map { it.id })
        assertFalse(result.any { it.id == kino.id })
    }

    @Test
    fun `Cyrillic query without evidence returns no script based guess`() {
        val kino = track("kino", "Перемен", "КИНО")

        val result = hybridSearch(
            songs = listOf(kino),
            query = "девушки",
            semantic = emptyList(),
        )

        assertEquals(emptyList(), result)
    }

    @Test
    fun `script alone never implies a language or genre`() {
        val kino = track("kino", "Группа крови", "КИНО", genre = "Post-Punk")
        val ukrainian = track("uk", "Пісня", "Український гурт", genre = "Pop")

        assertEquals(
            emptyList(),
            hybridSearch(listOf(ukrainian, kino), "русский", semantic = emptyList()),
        )
    }

    @Test
    fun `a folded Latin query reaches a Cyrillic name`() {
        val kino = track("kino", "Перемен", "КИНО")
        val other = track("other", "Hello", "World")

        val result = hybridSearch(listOf(other, kino), "kino", semantic = emptyList())

        assertEquals(listOf(kino.id), result.map { it.id })
    }

    @Test
    fun `a one-edit typo still reaches the artist via fuzzy`() {
        val eminem = track("eminem", "Stan", "Eminem")
        val other = track("other", "Hello", "World")

        val result = hybridSearch(listOf(other, eminem), "eminm", semantic = emptyList())

        assertEquals(listOf(eminem.id), result.map { it.id })
    }

    @Test
    fun `within a lexical tier a played match outranks a rarely played one and prefix stays pinned`() {
        val now = 1_000L * 24 * 60 * 60 * 1000
        val pinned = track("pin", "Rock Anthem", "A") // prefix match — highest tier
        val played = track("played", "Prock Loud", "B") // substring tier, played often
        val rare = track("rare", "Prock Quiet", "C") // substring tier, played once, long ago
        val stats = mapOf(
            played.id to TrackStats(plays = 50, completions = 50, skips = 0, totalPlayedMs = 0, lastPlayedAtMs = now),
            rare.id to TrackStats(
                plays = 1, completions = 1, skips = 0, totalPlayedMs = 0,
                lastPlayedAtMs = now - 365L * 24 * 60 * 60 * 1000,
            ),
        )

        val result = hybridSearch(
            songs = listOf(rare, played, pinned),
            query = "rock",
            semantic = emptyList(),
            stats = stats,
            nowMs = now,
        )

        assertEquals(listOf(pinned.id, played.id, rare.id), result.map { it.id })
    }

    private fun track(
        id: String,
        title: String,
        artist: String,
        genre: String? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = artist,
        genre = genre,
    )
}
