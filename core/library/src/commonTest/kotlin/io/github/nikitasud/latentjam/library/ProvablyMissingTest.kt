/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProvablyMissingTest {

    private fun ids(vararg values: String): List<TrackId> = values.map(::TrackId)

    @Test
    fun `an id no source holds is missing and one a source holds is not`() {
        val missing = provablyMissing(ids("1", "2", "3"), present = ids("2").toSet()) { true }

        assertEquals(ids("1", "3"), missing.toList())
    }

    @Test
    fun `an empty answer proves nothing however many ids are asked about`() {
        assertEquals(emptySet(), provablyMissing(ids("1", "2"), present = emptySet()) { true })
    }

    @Test
    fun `an id whose source did not answer is never reported`() {
        // Music-app ids while Music did not answer, with the imports listed in full.
        val missing = provablyMissing(
            ids = ids("ios-media:7", "Imports/a.flac", "Imports/gone.flac"),
            present = ids("Imports/a.flac").toSet(),
        ) { id -> !id.value.startsWith("ios-media:") }

        assertEquals(ids("Imports/gone.flac"), missing.toList())
    }

    @Test
    fun `the default library vouches for nothing`() = runTest {
        val library = object : MusicLibrary {
            override suspend fun tracks() = emptyList<TrackDescriptor>()
            override suspend fun hide(trackId: TrackId) = Unit
            override suspend fun unhide(trackId: TrackId) = Unit
            override suspend fun hiddenTracks() = emptyList<TrackDescriptor>()
            override suspend fun hasHiddenTracks() = false
            override suspend fun unhideAll() = Unit
            override suspend fun sources() = emptyList<LibrarySource>()
            override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = Unit
        }

        assertEquals(emptySet(), library.missingTrackIds(ids("1", "2")))
    }
}
