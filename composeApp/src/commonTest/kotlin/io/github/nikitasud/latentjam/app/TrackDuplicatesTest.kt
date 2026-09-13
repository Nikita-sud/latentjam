/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultFavorites
import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.FavoritesStore
import io.github.nikitasud.latentjam.history.TrackStats
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
    fun dismissedPairsNeverRegroupAndProgressReachesTheTotal() {
        val a = TrackId("a")
        val b = TrackId("b")
        val c = TrackId("c")
        val vectors = mapOf(a to unit(1f, 0f), b to unit(1f, 0.005f), c to unit(1f, 0.01f))
        val progress = mutableListOf<Pair<Int, Int>>()

        val groups = audioDuplicateGroups(
            vectors = vectors,
            dismissed = setOf(DuplicatePair.of(c, a)),
            onProgress = { done, total -> progress += done to total },
        )

        // a and c may never share a group; b joins whichever complete-link group it fits first.
        assertTrue(groups.none { a in it && c in it })
        assertEquals(listOf(listOf(a, b)), groups)
        assertEquals(3 to 3, progress.last())
        assertEquals(listOf(listOf(a, b, c)), audioDuplicateGroups(vectors))
    }

    @Test
    fun dismissalsRoundTripThroughTheirPayloadAndStayBounded() {
        val pairs = DuplicateDismissals.pairsOf(
            listOf(TrackId("42"), TrackId("Imported/Ночь|с трубой.flac"), TrackId("7")),
        )
        assertEquals(3, pairs.size)
        assertEquals(pairs, DuplicateDismissals.decode(DuplicateDismissals.encode(pairs)))
        assertEquals(emptySet(), DuplicateDismissals.decode(null))
        assertEquals(emptySet(), DuplicateDismissals.decode("v1|zz|00\nnonsense"))

        val many = (0 until 2_500).map { DuplicatePair.of(TrackId("x$it"), TrackId("y$it")) }
        val kept = DuplicateDismissals.decode(DuplicateDismissals.encode(many))
        assertEquals(2_000, kept.size)
        assertTrue(many.last() in kept)
        assertTrue(many.first() !in kept)
    }

    @Test
    fun copyFactsComeFromFileNameSizeAndDuration() {
        val flac = TrackDescriptor(
            id = TrackId("1"),
            durationMs = 240_000,
            sizeBytes = 30_000_000,
            fileName = "05. Dirty Harry.flac",
        )
        assertEquals("FLAC", copyFormat(flac))
        assertEquals(1_000, estimatedBitrateKbps(flac))
        assertEquals(
            "OPUS",
            copyFormat(TrackDescriptor(id = TrackId("2"), audioUri = "file:///Documents/a/b.opus")),
        )
        assertNull(copyFormat(TrackDescriptor(id = TrackId("3"), audioUri = "content://media/3")))
        assertNull(copyFormat(TrackDescriptor(id = TrackId("4"), fileName = "no-extension")))
        assertNull(estimatedBitrateKbps(TrackDescriptor(id = TrackId("5"), sizeBytes = 10)))
        assertEquals("34.2", megabytesLabel(34_200_000))
        assertEquals("120", megabytesLabel(120_400_000))
        assertEquals("0.0", megabytesLabel(0))
    }

    @Test
    fun recommendationPrefersLosslessThenBitrateThenWhatTheListenerLoves() {
        fun copy(
            id: String,
            format: String?,
            sizeBytes: Long?,
            durationMs: Long? = 200_000,
            plays: Int = 0,
            favorite: Boolean = false,
            playlists: Int = 0,
            addedAtMs: Long? = null,
        ) = describeDuplicateGroup(
            group = listOf(
                TrackDescriptor(
                    id = TrackId(id),
                    durationMs = durationMs,
                    sizeBytes = sizeBytes,
                    fileName = format?.let { "$id.$it" },
                    addedAtMs = addedAtMs,
                ),
            ),
            stats = if (plays > 0) mapOf(TrackId(id) to TrackStats(plays, plays, 0, 0, 0)) else emptyMap(),
            favorites = if (favorite) setOf(TrackId(id)) else emptySet(),
            playlistCounts = mapOf(TrackId(id) to playlists),
        ).copies.single()

        val flacSmall = copy("flac", "flac", sizeBytes = 20_000_000)
        val mp3Big = copy("mp3", "mp3", sizeBytes = 8_000_000, plays = 50, favorite = true)
        val mp3Small = copy("mp3-128", "mp3", sizeBytes = 3_200_000)
        assertEquals("flac", recommendedCopy(listOf(mp3Big, mp3Small, flacSmall)).track.id.value)
        assertEquals("mp3", recommendedCopy(listOf(mp3Small, mp3Big)).track.id.value)

        // Same quality: the loved copy, then the older file.
        val loved = copy("loved", "mp3", sizeBytes = 8_000_000, favorite = true, addedAtMs = 2_000)
        val older = copy("older", "mp3", sizeBytes = 8_000_000, addedAtMs = 1_000)
        assertEquals("loved", recommendedCopy(listOf(older, loved)).track.id.value)
        assertEquals("older", recommendedCopy(listOf(copy("newer", "mp3", 8_000_000, addedAtMs = 3_000), older)).track.id.value)

        // A 128 kbps MP3 transcoded from a 135 kbps Opus must not beat its source: the codec
        // scale puts Opus ahead. A 320 kbps MP3 still beats a 96 kbps Opus.
        val opus = copy("opus", "opus", sizeBytes = 3_410_910, durationMs = 202_000)
        val mp3FromOpus = copy("mp3-transcode", "mp3", sizeBytes = 3_711_857, durationMs = 202_000)
        assertEquals("opus", recommendedCopy(listOf(mp3FromOpus, opus)).track.id.value)
        val mp3High = copy("mp3-320", "mp3", sizeBytes = 8_000_000)
        val opusLow = copy("opus-96", "opus", sizeBytes = 2_400_000)
        assertEquals("mp3-320", recommendedCopy(listOf(opusLow, mp3High)).track.id.value)

        // Exact copies with numeric ids: the row scanned first wins, as a number, not as text.
        val first = copy("987", "mp3", sizeBytes = 8_000_000)
        val later = copy("1234", "mp3", sizeBytes = 8_000_000)
        assertEquals("987", recommendedCopy(listOf(later, first)).track.id.value)

        // Facts unknown everywhere: deterministic by id, never a crash.
        val blankA = copy("a", null, null, durationMs = null)
        val blankB = copy("b", null, null, durationMs = null)
        assertEquals("a", recommendedCopy(listOf(blankB, blankA)).track.id.value)

        val group = DuplicateGroup(listOf(flacSmall, mp3Big, mp3Small), flacSmall)
        assertEquals(11_200_000, group.reclaimableBytes)
        assertEquals(23_200_000, group.reclaimableBytes(mp3Big.track.id))
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
