/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SmartExclusionsTest {
    @Test
    fun exclusionsRoundTripArbitraryUnicode() = runTest {
        val store = FakeStore()
        val first = SmartExclusions(store)
        first.excludeTrack(TrackId("folder/трек:1"))
        first.excludeArtist("КИНО")

        val restored = SmartExclusions(store).load()

        assertEquals(setOf(TrackId("folder/трек:1")), restored.trackIds)
        assertEquals(setOf("КИНО"), restored.artists)
    }

    @Test
    fun matcherAsksTheArtistTestOnceAndKeepsTrackExclusions() {
        val asked = mutableListOf<Collection<String>>()
        val state = SmartExclusionState(trackIds = setOf(TrackId("t1")), artists = setOf("Queen"))
        val hides = state.matcher { names -> asked += names; { track -> track.artist == "Queen & David Bowie" } }
        assertEquals(true, hides(TrackDescriptor(TrackId("t1"), artist = "Anyone")))
        assertEquals(true, hides(TrackDescriptor(TrackId("t2"), artist = "Queen & David Bowie")))
        assertEquals(false, hides(TrackDescriptor(TrackId("t3"), artist = "ABBA")))
        assertEquals(listOf<Collection<String>>(setOf("Queen")), asked)
    }

    @Test
    fun noExcludedArtistsNeverAsksTheArtistTest() {
        val hides = SmartExclusionState(trackIds = setOf(TrackId("t1"))).matcher { error("not asked") }
        assertEquals(false, hides(TrackDescriptor(TrackId("t2"), artist = "Queen")))
    }

    @Test
    fun failedWriteDoesNotPublishOptimisticState() = runTest {
        val store = FakeStore(failWrites = true)
        val exclusions = SmartExclusions(store)
        exclusions.load()

        assertFails { exclusions.excludeTrack(TrackId("1")) }
        assertEquals(SmartExclusionState(), exclusions.state.value)
    }

    @Test
    fun artistMatchingIsTrimmedCaseInsensitiveAndDeduplicated() = runTest {
        val exclusions = SmartExclusions(FakeStore())
        exclusions.excludeArtist("  КИНО  ")
        exclusions.excludeArtist("кино")

        val state = exclusions.state.value
        assertEquals(setOf("КИНО"), state.artists)
        assertEquals(
            true,
            state.excludes(TrackDescriptor(TrackId("song"), artist = " кино ")),
        )

        exclusions.includeArtist("  кино ")
        assertEquals(false, exclusions.state.value.excludesArtist("КИНО"))
    }

    @Test
    fun trackAndArtistExclusionsShareOneRecommendationPredicate() {
        val state = SmartExclusionState(
            trackIds = setOf(TrackId("blocked-track")),
            artists = setOf("Blocked Artist"),
        )

        assertEquals(
            true,
            state.excludes(TrackDescriptor(TrackId("blocked-track"), artist = "Allowed Artist")),
        )
        assertEquals(
            true,
            state.excludes(TrackDescriptor(TrackId("other"), artist = "blocked artist")),
        )
        assertEquals(
            false,
            state.excludes(TrackDescriptor(TrackId("allowed"), artist = "Allowed Artist")),
        )
    }

    private class FakeStore(
        private var lines: List<String> = emptyList(),
        private val failWrites: Boolean = false,
    ) : SmartExclusionStore {
        override suspend fun read(): List<String> = lines

        override suspend fun write(lines: List<String>) {
            if (failWrites) error("disk full")
            this.lines = lines
        }
    }
}
