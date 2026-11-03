/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Don't recommend artist" by artist keys and whole credit segments (X v5), through the engine. */
internal class ArtistExclusionTest {
    private fun engine(artistSets: Boolean) = DefaultSimilarityEngine(
        backend = FakeEmbeddingBackend(),
        index = InMemoryVectorIndex(dim = 3),
        store = FakeIndexStore(),
        config = SmartEngineConfig(embeddingDim = 3, modelVersion = "test-model", artistSets = artistSets),
        dispatcher = Dispatchers.Default,
    )

    private fun track(artist: String?, title: String = "Song", artists: List<String> = emptyList()) =
        TrackDescriptor(TrackId("$artist|$title"), title = title, artist = artist, artists = artists)

    @Test
    fun `an excluded artist hides the tracks that credit it`() {
        val hides = engine(artistSets = true).artistExclusion(listOf("Queen"))
        assertTrue(hides(track("Queen")))
        assertTrue(hides(track("queen ")))
        assertTrue(hides(track("Queen & David Bowie")))
        assertTrue(hides(track("David Bowie", title = "Under Pressure (feat. Queen)")))
        assertTrue(hides(track("Freddie Mercury", artists = listOf("Freddie Mercury", "Queen"))))
        assertFalse(hides(track("Queensrÿche")))
        assertFalse(hides(track("ABBA", title = "Queen")))
    }

    @Test
    fun `a name of several artists needs all of them`() {
        val hides = engine(artistSets = true).artistExclusion(listOf("Queen & David Bowie"))
        assertTrue(hides(track("Queen & David Bowie")))
        assertFalse(hides(track("Queen")))
    }

    @Test
    fun `without artist lists only the display credit matches`() {
        val hides = engine(artistSets = false).artistExclusion(listOf("Queen"))
        assertTrue(hides(track("Queen")))
        assertFalse(hides(track("Queen & David Bowie")))
    }

    @Test
    fun `no names hide nothing`() {
        val hides = engine(artistSets = true).artistExclusion(listOf(" "))
        assertFalse(hides(track("Queen")))
        assertFalse(hides(track(null)))
    }

    @Test
    fun `untagged files of one name in two folders are two songs`() {
        val engine = engine(artistSets = true)
        fun untagged(folder: String) = TrackDescriptor(
            TrackId("$folder/01.mp3"), title = "01", fileName = "01.mp3", folderPath = folder,
        )
        val a = engine.placeholderDedupKey(untagged("Documents/A"), emptyList())
        val b = engine.placeholderDedupKey(untagged("Documents/B"), emptyList())
        kotlin.test.assertNotNull(a)
        kotlin.test.assertNotEquals(a, b)
        val sameAlbum = TrackDescriptor(TrackId("x"), title = "Intro", album = "Live 1999")
        kotlin.test.assertEquals(
            engine.placeholderDedupKey(sameAlbum, emptyList()),
            engine.placeholderDedupKey(TrackDescriptor(TrackId("y"), title = "Intro", album = "Live 1999"), emptyList()),
            "an album still names one identity",
        )
        kotlin.test.assertNull(engine.placeholderDedupKey(TrackDescriptor(TrackId("z"), title = "Song", artist = "Someone"), listOf("k")))
    }
}
