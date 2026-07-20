/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

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

    @Test
    fun `lexical matches lead semantic expansion`() {
        val result = hybridSearch(
            songs = listOf(weak, prefix, semantic, exact),
            query = "Rock",
            semantic = listOf(
                ScoredTrack(semantic.id, 0.70f),
                ScoredTrack(exact.id, 0.68f),
            ),
        )

        assertEquals(listOf(exact.id, prefix.id, semantic.id), result.map { it.id })
    }

    @Test
    fun `weak semantic tail is not shown as a recommendation`() {
        val result = hybridSearch(
            songs = listOf(semantic, weak),
            query = "energetic guitars",
            semantic = listOf(
                ScoredTrack(semantic.id, 0.62f),
                ScoredTrack(weak.id, 0.36f),
            ),
        )

        assertEquals(listOf(semantic.id), result.map { it.id })
    }

    @Test
    fun `cosine can answer when literal search cannot`() {
        val result = hybridSearch(
            songs = listOf(semantic),
            query = "energetic guitars",
            semantic = listOf(ScoredTrack(semantic.id, 0.61f)),
        )

        assertEquals(listOf(semantic.id), result.map { it.id })
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
            semantic = listOf(
                ScoredTrack(girl.id, 0.82f),
                ScoredTrack(kino.id, 0.50f),
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
