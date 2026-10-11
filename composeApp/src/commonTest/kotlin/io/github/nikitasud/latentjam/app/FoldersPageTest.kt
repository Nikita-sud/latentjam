/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.FolderTree
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class FoldersPageTest {

    private fun track(id: String, folder: String?, volume: String = FolderTree.PRIMARY_VOLUME) =
        TrackDescriptor(id = TrackId(id), title = id, folderPath = folder, storageVolume = volume)

    private val library = listOf(
        track("b-solo", "Music/Rock/Beatles"),
        track("a-intro", "Music"),
        track("c-ac", "Music/Rock/AC DC"),
        track("d-jazz", "Music/Jazz"),
    )
    private val catalog = LibraryCatalog.build(library)

    private fun List<TrackDescriptor>.ids() = map { it.id.value }

    @Test
    fun `the tree is the default and an unknown stored view falls back to it`() {
        assertEquals(FolderView.TREE, folderViewFromPersisted(null))
        assertEquals(FolderView.TREE, folderViewFromPersisted("GRID"))
        assertEquals(FolderView.LIST, folderViewFromPersisted("LIST"))
        assertEquals(FolderView.TREE, DEFAULT_FOLDER_VIEW.value)
    }

    @Test
    fun `the Folders tab counts the folders that hold tracks as the list shows them`() {
        // Music Rock holds no tracks of its own, so the list has no row for it and the count skips it.
        assertEquals(catalog.folders.size, folderTabCount(catalog))
        assertEquals(4, folderTabCount(catalog))
        // The two volumes' Music folders are one row of the list: Music, Music Live and the card root.
        assertEquals(3, folderTabCount(LibraryCatalog.build(twoVolumes)))
    }

    @Test
    fun `a selection in the tree plays by path and one in the list folder by folder`() {
        val everything = library.mapTo(LinkedHashSet()) { it.id }

        assertEquals(
            listOf("a-intro", "d-jazz", "c-ac", "b-solo"),
            folderTabSelectionOrder(catalog, SongSortDirection.ASCENDING, everything, FolderView.TREE).ids(),
        )
        assertEquals(
            listOf("a-intro", "b-solo", "c-ac", "d-jazz"),
            folderTabSelectionOrder(catalog, SongSortDirection.DESCENDING, everything, FolderView.TREE).ids(),
        )
        // The list view keeps the flat catalog's folder order: AC DC, Beatles, Jazz, Music.
        assertEquals(
            catalog.folders.flatMap { it.tracks }.ids(),
            folderTabSelectionOrder(catalog, SongSortDirection.ASCENDING, everything, FolderView.LIST).ids(),
        )
    }

    @Test
    fun `a folder played from the tree is a source that reopens that folder with everything below it`() {
        val music = FolderTree.build(library).node(FolderTree.keyOf(FolderTree.PRIMARY_VOLUME, "Music"))!!
        val source = QueueSource(QueueSourceKind.LIBRARY_GROUP, "Music", FOLDER_TREE_ROUTE_PREFIX + music.key)

        val resolved = source.resolveGroup(catalog)
        assertEquals(music.key, (resolved as QueueSourceGroup.FolderBranch).node.key)
        assertEquals("foldertree:external_primary:Music", source.parentCollectionId())
        assertNull(
            QueueSource(QueueSourceKind.LIBRARY_GROUP, "Gone", "${FOLDER_TREE_ROUTE_PREFIX}external_primary:Gone")
                .resolveGroup(catalog),
        )
        // The page that source opens plays from the same source again.
        val page = CollectionSelection("Music", null, null, emptyList(), routeId = FOLDER_TREE_ROUTE_PREFIX + music.key)
        assertEquals(source.copy(name = "Music"), page.queueSource())
    }

    private val sdCard = "1a2b-3c4d"

    /** Internal storage and an SD card both hold a Music folder; the card has a track at its root too. */
    private val twoVolumes = listOf(
        track("int-1", "Music"),
        track("sd-2", "Music", sdCard),
        track("int-2", "Music"),
        track("sd-1", "Music", sdCard),
        track("sd-live", "Music/Live", sdCard),
        track("sd-root", "", sdCard),
    )

    /** The tracks "Playing from" opens for a resolved source. */
    private fun QueueSourceGroup.tracks(): List<TrackDescriptor>? = when (this) {
        is QueueSourceGroup.Folder -> group.tracks
        is QueueSourceGroup.FolderBranch -> node.allTracks()
        is QueueSourceGroup.FolderTracks -> node.tracks
        else -> null
    }

    @Test
    fun `a track played in a folder of the tree reopens that folder on its own volume and only its own tracks`() {
        val catalog = LibraryCatalog.build(twoVolumes)
        val sdMusic = FolderTree.build(twoVolumes).node(FolderTree.keyOf(sdCard, "Music"))!!

        val source = folderTrackSource(sdMusic, "Music")

        // Not the internal storage's Music beside it, and not the Live folder below it.
        assertEquals(listOf("sd-1", "sd-2"), source.resolveGroup(catalog)?.tracks()?.ids())
        // The page that source opens plays from the same source again.
        val page = CollectionSelection("Music", null, null, emptyList(), routeId = source.reference!!)
        assertEquals(source, page.queueSource())
        // Once the folder holds no tracks of its own, the source falls back to the queue.
        val emptied = twoVolumes.filterNot { it.id.value == "sd-1" || it.id.value == "sd-2" }
        assertNull(source.resolveGroup(LibraryCatalog.build(emptied)))
    }

    @Test
    fun `a track played at the root of a volume has a source`() {
        val sdRoot = FolderTree.build(twoVolumes).node(FolderTree.keyOf(sdCard, ""))!!

        val source = folderTrackSource(sdRoot, "SD card")

        assertEquals(listOf("sd-root"), source.resolveGroup(LibraryCatalog.build(twoVolumes))?.tracks()?.ids())
    }

    @Test
    fun `a folder source saved by an earlier build still opens the folder list's folder`() {
        val catalog = LibraryCatalog.build(twoVolumes)
        val saved = QueueSource(QueueSourceKind.LIBRARY_GROUP, "Music", "folder:Music")

        val resolved = saved.resolveGroup(catalog)

        assertEquals("Music", (resolved as QueueSourceGroup.Folder).group.path)
        assertEquals(listOf("int-1", "int-2", "sd-1", "sd-2"), resolved.tracks()?.ids())
        assertEquals("folder:Music", saved.parentCollectionId())
    }

    @Test
    fun `a folder page has a section per folder that holds tracks in play order`() {
        val music = FolderTree.build(library).node(FolderTree.keyOf(FolderTree.PRIMARY_VOLUME, "Music"))!!

        val sections = folderBranchSections(music, SongSortDirection.ASCENDING, ownTitle = "Music")

        assertEquals(listOf("Music", "Jazz", "Rock/AC DC", "Rock/Beatles"), sections.map { it.title })
        assertEquals(music.allTracks().ids(), sections.flatMap { it.tracks }.ids())
        assertEquals(
            listOf("Music", "Rock/Beatles", "Rock/AC DC", "Jazz"),
            folderBranchSections(music, SongSortDirection.DESCENDING, ownTitle = "Music").map { it.title },
        )
    }
}
