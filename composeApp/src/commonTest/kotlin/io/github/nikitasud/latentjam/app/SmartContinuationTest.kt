/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartContinuationTest {

    private fun track(id: String, artist: String? = null) =
        TrackDescriptor(id = TrackId(id), title = id, artist = artist)

    @Test
    fun `a track the listener has never heard comes before anything they have`() {
        val heardLongAgo = track("heard")
        val neverHeard = track("never")
        assertEquals(
            neverHeard,
            smartContinuationTrack(
                candidates = listOf(heardLongAgo, neverHeard),
                lastPlayedAtMs = mapOf(heardLongAgo.id to 1L),
                avoidArtists = emptySet(),
            ),
        )
    }

    @Test
    fun `among heard tracks the one heard longest ago continues the queue`() {
        val recent = track("recent")
        val older = track("older")
        assertEquals(
            older,
            smartContinuationTrack(
                candidates = listOf(recent, older),
                lastPlayedAtMs = mapOf(recent.id to 9_000L, older.id to 1_000L),
                avoidArtists = emptySet(),
            ),
        )
    }

    @Test
    fun `an artist just continued with is skipped, so an album cannot dump itself`() {
        val sameArtist = track("a2", artist = "Aphex Twin")
        val other = track("b1", artist = "Boards of Canada")
        assertEquals(
            other,
            smartContinuationTrack(
                candidates = listOf(sameArtist, other),
                lastPlayedAtMs = emptyMap(),
                avoidArtists = setOf("aphex twin"),
            ),
        )
    }

    @Test
    fun `spacing yields rather than stopping when every candidate shares that artist`() {
        val first = track("a1", artist = "Aphex Twin")
        val second = track("a2", artist = "aphex twin")
        assertEquals(
            first,
            smartContinuationTrack(
                candidates = listOf(first, second),
                lastPlayedAtMs = emptyMap(),
                avoidArtists = setOf("aphex twin"),
            ),
        )
    }

    @Test
    fun `an unknown artist is not treated as one artist everybody shares`() {
        val nameless = track("n1")
        val other = track("n2")
        assertEquals(
            nameless,
            smartContinuationTrack(
                candidates = listOf(nameless, other),
                lastPlayedAtMs = emptyMap(),
                avoidArtists = setOf(""),
            ),
        )
    }

    @Test
    fun `an exhausted candidate pool ends the queue rather than inventing a row`() {
        assertNull(
            smartContinuationTrack(
                candidates = emptyList(),
                lastPlayedAtMs = emptyMap(),
                avoidArtists = emptySet(),
            ),
        )
    }

    @Test
    fun `equally unheard candidates keep library order, so the pick is reproducible`() {
        val first = track("1", artist = "One")
        val second = track("2", artist = "Two")
        val pick = {
            smartContinuationTrack(
                candidates = listOf(first, second),
                lastPlayedAtMs = emptyMap(),
                avoidArtists = emptySet(),
            )
        }
        assertEquals(first, pick())
        assertEquals(pick(), pick())
    }
}
