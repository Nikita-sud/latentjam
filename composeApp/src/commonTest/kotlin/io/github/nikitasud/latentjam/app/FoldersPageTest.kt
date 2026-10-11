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
