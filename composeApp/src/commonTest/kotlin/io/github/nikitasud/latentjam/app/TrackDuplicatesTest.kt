/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultFavorites
import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.FavoritesStore
import io.github.nikitasud.latentjam.library.DefaultPlaylists
import io.github.nikitasud.latentjam.library.PlaylistStore
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrackDuplicatesTest {

    private fun unit(x: Float, y: Float): FloatArray {
        val norm = kotlin.math.sqrt(x * x + y * y)
        return floatArrayOf(x / norm, y / norm)
    }

    @Test
    fun nearIdenticalVectorsGroupTogether() {
        val groups = audioDuplicateGroups(
            vectors = mapOf(
                TrackId("a") to unit(1f, 0f),
                TrackId("a-copy") to unit(1f, 0.01f),
                TrackId("far") to unit(0f, 1f),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(setOf(TrackId("a"), TrackId("a-copy")), groups.single().toSet())
    }

    @Test
    fun transitiveNeighborsFormOneGroupNotTwoPairs() {
        // b sits between a and c: one cluster of three, not overlapping pairs.
        val groups = audioDuplicateGroups(
            vectors = mapOf(
                TrackId("a") to unit(1f, 0f),
                TrackId("b") to unit(1f, 0.008f),
                TrackId("c") to unit(1f, 0.016f),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().size)
    }

    @Test
    fun bridgeChainNeverGroupsEndpointsThatMissTheThreshold() {
        // a≈b and b≈c, but a is not a duplicate of c. A connected component would make it
        // possible to keep a and hide c; complete-link grouping must never do that.
        val a = TrackId("a")
        val c = TrackId("c")
        val groups = audioDuplicateGroups(
            vectors = mapOf(
                a to unit(1f, 0f),
                TrackId("b") to unit(1f, 0.12f),
                c to unit(1f, 0.24f),
            ),
        )

        assertTrue(groups.none { a in it && c in it })
        assertEquals(listOf(listOf(a, TrackId("b"))), groups)
    }

    @Test
    fun distinctTracksProduceNoGroups() {
        val groups = audioDuplicateGroups(
            vectors = mapOf(
                TrackId("a") to unit(1f, 0f),
                TrackId("b") to unit(0.5f, 1f),
                TrackId("c") to unit(0f, 1f),
            ),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun biggestGroupComesFirst() {
        val groups = audioDuplicateGroups(
            vectors = mapOf(
                TrackId("p1") to unit(0f, 1f),
                TrackId("p2") to unit(0.005f, 1f),
                TrackId("t1") to unit(1f, 0f),
                TrackId("t2") to unit(1f, 0.005f),
                TrackId("t3") to unit(1f, 0.01f),
            ),
        )
        assertEquals(listOf(3, 2), groups.map { it.size })
    }

    @Test
    fun knownDurationsExcludeRecordingsThatCannotBeTheSameCopy() {
        val a = TrackId("a")
        val b = TrackId("b")
        val groups = audioDuplicateGroups(
            vectors = mapOf(a to unit(1f, 0f), b to unit(1f, 0f)),
            durationsMs = mapOf(a to 60_000L, b to 90_000L),
        )
        assertTrue(groups.isEmpty())
    }
}

internal class DuplicateMergeTest {

    private class MemoryPlaylistStore : PlaylistStore {
        var lines: List<String> = emptyList()
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) { this.lines = lines }
    }

    private class MemoryFavoritesStore : FavoritesStore {
        var ids: List<String> = emptyList()
        override suspend fun read(): List<String> = ids
        override suspend fun write(ids: List<String>) { this.ids = ids }
    }

    private fun track(id: String) = TrackDescriptor(id = TrackId(id), title = id)

    @Test
    fun rejectedPlaylistCasAbortsBeforeFavoritesOrTracksAreRemoved() = runTest {
        val backingPlaylists = DefaultPlaylists(MemoryPlaylistStore())
        backingPlaylists.create("Mix", listOf(TrackId("duplicate")))
        val playlists = object : Playlists by backingPlaylists {
            override suspend fun replaceTracksIfUnchanged(
                id: String,
                expected: List<TrackId>,
                replacement: List<TrackId>,
            ): Boolean = false
        }
        val favorites = DefaultFavorites(MemoryFavoritesStore()).also {
            it.replace(listOf(TrackId("duplicate")))
        }
        val hidden = mutableListOf<TrackId>()
        val survivor = track("survivor")
        var failure: Throwable? = null

        try {
            mergeDuplicateGroup(
                group = listOf(survivor, track("duplicate")),
                survivor = survivor,
                playlists = playlists,
                favorites = favorites,
                onHideTrack = { hidden += it.id },
            )
        } catch (problem: Throwable) {
            failure = problem
        }

        assertIs<IllegalStateException>(failure)
        assertEquals(listOf(TrackId("duplicate")), favorites.all())
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun rejectedFavoritesCasAbortsBeforeTracksAreRemoved() = runTest {
        val playlists = DefaultPlaylists(MemoryPlaylistStore())
        val created = playlists.create("Mix", listOf(TrackId("duplicate")))
        val backingFavorites = DefaultFavorites(MemoryFavoritesStore()).also {
            it.replace(listOf(TrackId("duplicate")))
        }
        val favorites = object : Favorites by backingFavorites {
            override suspend fun replaceIfUnchanged(
                expected: List<TrackId>,
                replacement: List<TrackId>,
            ): Boolean = false
        }
        val hidden = mutableListOf<TrackId>()
        val survivor = track("survivor")
        var failure: Throwable? = null

        try {
            mergeDuplicateGroup(
                group = listOf(survivor, track("duplicate")),
                survivor = survivor,
                playlists = playlists,
                favorites = favorites,
                onHideTrack = { hidden += it.id },
            )
        } catch (problem: Throwable) {
            failure = problem
        }

        assertIs<IllegalStateException>(failure)
        assertEquals(listOf("survivor"), playlists.all().single { it.id == created.id }.trackIds)
        assertEquals(listOf(TrackId("duplicate")), backingFavorites.all())
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun successfulMergeRewritesReferencesBeforeHidingLosers() = runTest {
        val playlists = DefaultPlaylists(MemoryPlaylistStore())
        val created = playlists.create(
            "Mix",
            listOf(TrackId("before"), TrackId("duplicate"), TrackId("survivor")),
        )
        val favorites = DefaultFavorites(MemoryFavoritesStore()).also {
            it.replace(listOf(TrackId("duplicate")))
        }
        val survivor = track("survivor")
        val hidden = mutableListOf<TrackId>()

        mergeDuplicateGroup(
            group = listOf(survivor, track("duplicate")),
            survivor = survivor,
            playlists = playlists,
            favorites = favorites,
            onHideTrack = { loser ->
                assertEquals(listOf("before", "survivor"), playlists.all().single { it.id == created.id }.trackIds)
                assertEquals(listOf(TrackId("survivor")), favorites.all())
                hidden += loser.id
            },
        )

        assertEquals(listOf(TrackId("duplicate")), hidden)
    }
}

internal class MergedMembershipTest {

    private fun id(value: String) = io.github.nikitasud.latentjam.smart.TrackId(value)

    @kotlin.test.Test
    fun duplicatesBecomeTheSurvivorInPlaceAndRepeatsCollapse() {
        val merged = mergedMembership(
            current = listOf(id("a"), id("dup1"), id("b"), id("dup2"), id("keep")),
            duplicates = setOf(id("dup1"), id("dup2")),
            survivor = id("keep"),
        )
        // dup1 becomes the surviving row at its original position; the later copies collapse.
        assertEquals(listOf(id("a"), id("keep"), id("b")), merged)
    }

    @kotlin.test.Test
    fun listsWithoutTheGroupNeedNoWrite() {
        assertNull(
            mergedMembership(
                current = listOf(id("a"), id("b")),
                duplicates = setOf(id("dup")),
                survivor = id("keep"),
            ),
        )
    }

    @kotlin.test.Test
    fun survivorAlreadyFirstMeansTheDuplicateRowSimplyDrops() {
        val merged = mergedMembership(
            current = listOf(id("keep"), id("a"), id("dup")),
            duplicates = setOf(id("dup")),
            survivor = id("keep"),
        )
        assertEquals(listOf(id("keep"), id("a")), merged)
    }
}
