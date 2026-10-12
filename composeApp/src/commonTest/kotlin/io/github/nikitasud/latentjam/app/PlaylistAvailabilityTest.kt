/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.DefaultPlaylists
import io.github.nikitasud.latentjam.library.PlaylistStore
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistAvailabilityTest {

    private class PlaylistMemory : PlaylistStore {
        var lines = emptyList<String>()
        override suspend fun read() = lines
        override suspend fun write(lines: List<String>) { this.lines = lines }
    }

    /** A device holding [onDevice]; it records every set of ids it is asked about. */
    private class Device(vararg onDevice: String) {
        val present = onDevice.mapTo(HashSet(), ::TrackId)
        val asked = mutableListOf<Set<TrackId>>()
        suspend fun missing(ids: Collection<TrackId>): Set<TrackId> {
            asked += ids.toSet()
            return ids.filterTo(LinkedHashSet()) { it !in present }
        }
    }

    private fun ids(vararg values: String) = values.map(::TrackId)

    private fun library(vararg visible: String, authoritative: Boolean = true) = AutomaticIndexingRequest(
        tracks = visible.map { TrackDescriptor(id = TrackId(it)) },
        librarySnapshotAuthoritative = authoritative,
    )

    @Test
    fun onlyEntriesTheDeviceProvesGoneAreUnavailable() = runTest {
        // "hidden" is on the device but not in the visible library: hidden, or in a disabled source.
        val device = Device("1", "2", "hidden")

        val unavailable = unavailablePlaylistEntries(
            members = listOf("1", "gone-a", "2", "hidden", "gone-b"),
            library = library("1", "2"),
            missing = device::missing,
        )

        assertEquals(ids("gone-a", "gone-b"), unavailable.toList())
    }

    @Test
    fun onlyEntriesTheVisibleLibraryLacksAreAskedAbout() = runTest {
        val device = Device("1", "2")

        unavailablePlaylistEntries(listOf("1", "gone", "2"), library("1", "2"), device::missing)
        unavailablePlaylistEntries(listOf("1", "2"), library("1", "2"), device::missing)

        assertEquals(listOf(setOf(TrackId("gone"))), device.asked)
    }

    @Test
    fun aLibraryThatCannotVouchForItselfOffersNothing() = runTest {
        val device = Device("1")

        val partial = unavailablePlaylistEntries(
            listOf("1", "gone"), library("1", authoritative = false), device::missing,
        )
        // A complete scan that found nothing: a provider glitch looks exactly like this.
        val empty = unavailablePlaylistEntries(listOf("1", "gone"), library(), device::missing)

        assertTrue(partial.isEmpty())
        assertTrue(empty.isEmpty())
        assertTrue(device.asked.isEmpty())
    }

    @Test
    fun whatTheDeviceReportsBeyondThePlaylistIsIgnored() = runTest {
        val unavailable = unavailablePlaylistEntries(listOf("1", "gone"), library("1")) {
            setOf(TrackId("gone"), TrackId("unrelated"))
        }

        assertEquals(setOf(TrackId("gone")), unavailable)
    }

    @Test
    fun removalForgetsWhatIsStillGoneAndUndoRestoresTheExactOrder() = runTest {
        val playlists = DefaultPlaylists(PlaylistMemory())
        val playlist = playlists.create("Anime", ids("1", "gone-a", "2", "back", "3"))
        // The notice named two entries; one of them came back before the tap.
        val device = Device("1", "2", "3", "back")

        val change = removeUnavailablePlaylistEntries(
            playlists, playlist.id, shown = setOf(TrackId("gone-a"), TrackId("back")),
            library = library("1", "2", "3"), missing = device::missing,
        )

        assertEquals(ids("1", "gone-a", "2", "back", "3"), change?.before)
        assertEquals(ids("1", "2", "back", "3"), change?.after)
        assertEquals(listOf("1", "2", "back", "3"), playlists.all().single().trackIds)

        val applied = checkNotNull(change)
        assertTrue(playlists.restoreTracksIfUnchanged(playlist.id, applied))
        assertEquals(listOf("1", "gone-a", "2", "back", "3"), playlists.all().single().trackIds)
    }

    @Test
    fun removalLeavesEntriesTheListenerWasNotToldAbout() = runTest {
        val playlists = DefaultPlaylists(PlaylistMemory())
        val playlist = playlists.create("Gym", ids("1", "gone-a", "gone-b"))

        val change = removeUnavailablePlaylistEntries(
            playlists, playlist.id, shown = setOf(TrackId("gone-a")),
            library = library("1"), missing = Device("1")::missing,
        )

        assertEquals(ids("1", "gone-b"), change?.after)
    }

    @Test
    fun removalChangesNothingWhenEveryEntryCameBack() = runTest {
        val playlists = DefaultPlaylists(PlaylistMemory())
        val playlist = playlists.create("Dance", ids("1", "card"))

        val change = removeUnavailablePlaylistEntries(
            playlists, playlist.id, shown = setOf(TrackId("card")),
            library = library("1"), missing = Device("1", "card")::missing,
        )

        assertNull(change)
        assertEquals(listOf("1", "card"), playlists.all().single().trackIds)
    }

    @Test
    fun removalChangesNothingWhileTheLibraryCannotVouchForItself() = runTest {
        val playlists = DefaultPlaylists(PlaylistMemory())
        val playlist = playlists.create("Think", ids("1", "gone"))
        val device = Device("1")

        val partial = removeUnavailablePlaylistEntries(
            playlists, playlist.id, setOf(TrackId("gone")), library("1", authoritative = false), device::missing,
        )
        val empty = removeUnavailablePlaylistEntries(
            playlists, playlist.id, setOf(TrackId("gone")), library(), device::missing,
        )

        assertNull(partial)
        assertNull(empty)
        assertEquals(listOf("1", "gone"), playlists.all().single().trackIds)
    }

    @Test
    fun undoAfterANewerEditOfThePlaylistIsRefused() = runTest {
        val playlists = DefaultPlaylists(PlaylistMemory())
        val playlist = playlists.create("JoJo", ids("1", "gone"))
        val change = checkNotNull(
            removeUnavailablePlaylistEntries(
                playlists, playlist.id, setOf(TrackId("gone")), library("1", "2"), Device("1", "2")::missing,
            ),
        )

        playlists.addTracks(playlist.id, ids("2"))

        assertFalse(playlists.restoreTracksIfUnchanged(playlist.id, change))
        assertEquals(listOf("1", "2"), playlists.all().single().trackIds)
    }

    @Test
    fun removalFromAPlaylistThatIsGoneChangesNothing() = runTest {
        val playlists = DefaultPlaylists(PlaylistMemory())

        val change = removeUnavailablePlaylistEntries(
            playlists, "deleted", setOf(TrackId("gone")), library("1"), Device("1")::missing,
        )

        assertNull(change)
    }
}
