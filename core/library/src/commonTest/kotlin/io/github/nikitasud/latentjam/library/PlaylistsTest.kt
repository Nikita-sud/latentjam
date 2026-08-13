/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PlaylistsTest {

    private class FakeStore(initial: List<String> = emptyList()) : PlaylistStore {
        var lines: List<String> = initial
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) { this.lines = lines }
    }

    @Test
    fun createAddAndReadBack() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create("Workout")
        playlists.addTracks(created.id, listOf(TrackId("1"), TrackId("2")))

        val stored = playlists.all().single()
        assertEquals("Workout", stored.name)
        assertContentEquals(listOf("1", "2"), stored.trackIds)
    }

    @Test
    fun createWithInitialTracksUsesOneTransactionalWrite() = runTest {
        val store = object : PlaylistStore {
            var lines: List<String> = emptyList()
            var writes = 0
            override suspend fun read(): List<String> = lines
            override suspend fun write(lines: List<String>) {
                writes++
                this.lines = lines
            }
        }
        val playlists = DefaultPlaylists(store)

        val created = playlists.create(
            "Road trip",
            listOf(TrackId("one"), TrackId("one"), TrackId("two")),
        )

        assertEquals(1, store.writes)
        assertContentEquals(listOf("one", "two"), created.trackIds)
        assertContentEquals(created.trackIds, playlists.all().single().trackIds)
    }

    @Test
    fun addingTheSameTrackTwiceIsANoOp() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create("Mix")
        playlists.addTracks(created.id, listOf(TrackId("1")))
        playlists.addTracks(created.id, listOf(TrackId("1"), TrackId("2")))
        assertContentEquals(listOf("1", "2"), playlists.all().single().trackIds)
    }

    @Test
    fun duplicateTracksWithinOneAddAreStoredOnceInFirstSeenOrder() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create("Mix")

        playlists.addTracks(
            created.id,
            listOf(TrackId("1"), TrackId("1"), TrackId("2"), TrackId("1"), TrackId("2")),
        )

        assertContentEquals(listOf("1", "2"), playlists.all().single().trackIds)
    }

    @Test
    fun removeAndDelete() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create("Mix")
        playlists.addTracks(created.id, listOf(TrackId("1"), TrackId("2")))
        playlists.removeTrack(created.id, TrackId("1"))
        assertContentEquals(listOf("2"), playlists.all().single().trackIds)

        playlists.delete(created.id)
        assertTrue(playlists.all().isEmpty())
    }

    @Test
    fun removingASelectionUsesOneTransactionalWrite() = runTest {
        val store = object : PlaylistStore {
            var lines: List<String> = emptyList()
            var writes = 0
            override suspend fun read(): List<String> = lines
            override suspend fun write(lines: List<String>) {
                writes++
                this.lines = lines
            }
        }
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Mix")
        playlists.addTracks(created.id, listOf(TrackId("1"), TrackId("2"), TrackId("3")))
        val beforeRemoval = store.writes

        val change = requireNotNull(
            playlists.removeTracks(
                created.id,
                listOf(TrackId("1"), TrackId("3")),
            ),
        )

        assertEquals(beforeRemoval + 1, store.writes)
        assertContentEquals(listOf(TrackId("1"), TrackId("2"), TrackId("3")), change.before)
        assertContentEquals(listOf(TrackId("2")), change.after)
        assertContentEquals(listOf("2"), playlists.all().single().trackIds)
    }

    @Test
    fun orderedUndoUsesCompareAndSetAndCannotClobberANewerEdit() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create(
            "Mix",
            listOf(TrackId("1"), TrackId("2"), TrackId("3")),
        )
        val change = requireNotNull(
            playlists.removeTracks(created.id, listOf(TrackId("1"), TrackId("3"))),
        )

        assertTrue(
            playlists.replaceTracksIfUnchanged(created.id, change.after, change.before),
        )
        assertContentEquals(listOf("1", "2", "3"), playlists.all().single().trackIds)

        playlists.removeTracks(created.id, listOf(TrackId("1"), TrackId("3")))
        playlists.addTracks(created.id, listOf(TrackId("4")))
        assertTrue(
            !playlists.replaceTracksIfUnchanged(created.id, change.after, change.before),
        )
        assertContentEquals(listOf("2", "4"), playlists.all().single().trackIds)
    }

    @Test
    fun renameFallsBackForBlankNames() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create("Mix")
        playlists.rename(created.id, "   ")
        assertEquals("Untitled playlist", playlists.all().single().name)
    }

    @Test
    fun survivesReloadIncludingAwkwardNames() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Rock | Metal, vol.2")
        playlists.addTracks(created.id, listOf(TrackId("7")))

        val reloaded = DefaultPlaylists(store).all().single()
        assertEquals("Rock | Metal, vol.2", reloaded.name)
        assertContentEquals(listOf("7"), reloaded.trackIds)
    }

    @Test
    fun arbitraryTrackIdsRoundTripWithoutDelimiterCorruption() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Paths \u001f and Unicode 🎧")
        val ids = listOf("Earth, Wind & Fire.mp3", "folder/a\u001fb|c,曲.mp3")
        playlists.addTracks(created.id, ids.map(::TrackId))

        val reloaded = DefaultPlaylists(store).all().single()
        assertEquals("Paths \u001f and Unicode 🎧", reloaded.name)
        assertContentEquals(ids, reloaded.trackIds)
    }

    @Test
    fun duplicateTrackIdsFromLegacyStorageAreNormalizedOnLoad() = runTest {
        val duplicated = Playlist(
            id = "old",
            name = "Legacy",
            trackIds = listOf("one", "one", "two", "one"),
            createdAtMs = 1,
        )
        val store = FakeStore(listOf(PlaylistSerializer.serialize(duplicated)))

        assertContentEquals(
            listOf("one", "two"),
            DefaultPlaylists(store).all().single().trackIds,
        )
    }

    @Test
    fun duplicatePlaylistIdsFromStorageKeepFirstValidOccurrenceInStorageOrder() = runTest {
        val first = Playlist(
            id = "duplicate",
            name = "  First  ",
            trackIds = listOf("one", "one", "", "two", "one"),
            createdAtMs = 1,
        )
        val middle = Playlist(
            id = "middle",
            name = "Middle",
            trackIds = listOf("three", "three"),
            createdAtMs = 2,
        )
        val laterDuplicate = Playlist(
            id = first.id,
            name = "Later duplicate",
            trackIds = listOf("four"),
            createdAtMs = 3,
        )
        val store = FakeStore(
            listOf(first, middle, laterDuplicate).map(PlaylistSerializer::serialize),
        )
        val playlists = DefaultPlaylists(store)

        val restored = playlists.all()

        assertContentEquals(listOf("duplicate", "middle"), restored.map(Playlist::id))
        assertEquals("First", restored[0].name)
        assertContentEquals(listOf("one", "two"), restored[0].trackIds)
        assertContentEquals(listOf("three"), restored[1].trackIds)

        playlists.addTracks(first.id, listOf(TrackId("five")))
        val afterMutation = DefaultPlaylists(store).all()
        assertEquals(2, afterMutation.size)
        assertEquals("First", afterMutation[0].name)
        assertContentEquals(listOf("one", "two", "five"), afterMutation[0].trackIds)
    }

    @Test
    fun aFailedWriteDoesNotPublishAnInMemoryPlaylist() = runTest {
        val store = object : PlaylistStore {
            var fail = true
            var lines: List<String> = emptyList()
            override suspend fun read(): List<String> = lines
            override suspend fun write(lines: List<String>) {
                if (fail) error("disk full")
                this.lines = lines
            }
        }
        val playlists = DefaultPlaylists(store)

        assertFailsWith<IllegalStateException> { playlists.create("Lost") }
        assertTrue(playlists.all().isEmpty())
        store.fail = false
        assertEquals("Saved", playlists.create("Saved").name)
    }

    @Test
    fun aFailedInitialReadIsRetriedInsteadOfBecomingAnEmptyLoadedStore() = runTest {
        val original = Playlist("old", "Existing", listOf("track"), createdAtMs = 1)
        val store = object : PlaylistStore {
            var fail = true
            override suspend fun read(): List<String> {
                if (fail) error("transient read")
                return listOf(PlaylistSerializer.serialize(original))
            }
            override suspend fun write(lines: List<String>) = Unit
        }
        val playlists = DefaultPlaylists(store)

        assertFailsWith<IllegalStateException> { playlists.all() }
        store.fail = false
        assertEquals(original, playlists.all().single())
    }

    @Test
    fun corruptLinesAreSkipped() = runTest {
        val store = FakeStore(initial = listOf("nonsense", ""))
        assertTrue(DefaultPlaylists(store).all().isEmpty())
        assertNull(PlaylistSerializer.parse("a|b|c|d"))
    }

    // ------------------------------------------------------------ auto playlists

    private fun track(id: String, addedAtMs: Long? = null) =
        TrackDescriptor(id = TrackId(id), title = "t$id", addedAtMs = addedAtMs)

    @Test
    fun autoPlaylistsRankByRecencyAndCount() = runTest {
        val tracks = listOf(
            track("1", addedAtMs = 100),
            track("2", addedAtMs = 300),
            track("3", addedAtMs = 200),
        )
        val auto = AutoPlaylists.build(
            tracks = tracks,
            playCounts = mapOf(TrackId("1") to 5, TrackId("3") to 9),
            lastPlayedAtMs = mapOf(TrackId("1") to 50L, TrackId("3") to 90L),
        )
        assertEquals(4, auto.size)
        assertContentEquals(
            listOf("2", "3", "1"),
            auto.first { it.kind == AutoPlaylistKind.RECENTLY_ADDED }.tracks.map { it.id.value },
        )
        assertContentEquals(
            listOf("2"),
            auto.first { it.kind == AutoPlaylistKind.NEVER_PLAYED }.tracks.map { it.id.value },
        )
        assertContentEquals(
            listOf("3", "1"),
            auto.first { it.kind == AutoPlaylistKind.MOST_PLAYED }.tracks.map { it.id.value },
        )
        assertContentEquals(
            listOf("3", "1"),
            auto.first { it.kind == AutoPlaylistKind.RECENTLY_PLAYED }.tracks.map { it.id.value },
        )
    }

    @Test
    fun emptyAutoPlaylistsAreDropped() = runTest {
        val auto = AutoPlaylists.build(
            tracks = listOf(track("1")), // no addedAt, never played
            playCounts = emptyMap(),
            lastPlayedAtMs = emptyMap(),
        )
        // The unheard track is a genuine NEVER_PLAYED entry; every other kind is empty and drops.
        assertContentEquals(listOf(AutoPlaylistKind.NEVER_PLAYED), auto.map { it.kind })
        assertTrue(AutoPlaylists.build(emptyList(), emptyMap(), emptyMap()).isEmpty())
    }
}
