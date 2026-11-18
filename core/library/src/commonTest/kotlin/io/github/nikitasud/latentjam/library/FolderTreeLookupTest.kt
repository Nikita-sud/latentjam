/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

internal class FolderTreeLookupTest {

    private val card = "1a2b-3c4d"

    private fun track(id: String, folder: String?, volume: String? = FolderTree.PRIMARY_VOLUME) =
        TrackDescriptor(id = TrackId(id), title = id, folderPath = folder, storageVolume = volume)

    private val oneVolume = listOf(
        track("a", "Music/Rock/AC DC"),
        track("b", "Music/Rock"),
        track("c", "Music/Jazz/Miles Davis"),
        track("d", "Podcasts"),
        track("e", "/storage/emulated/0/Download/"),
    )
    private val twoVolumes = oneVolume + listOf(track("f", "Music", card), track("g", "", card))
    private val unnamedVolume = listOf(track("h", "Music/Live", volume = null), track("i", "sdcard/Old", volume = null))

    /** Keys that are in the trees above, keys beside them, and keys that only look like them. */
    private val keys = listOf(
        "", ":", "external_primary:", "external_primary:Music", "external_primary:Music/Rock",
        "external_primary:Music/Rock/AC DC", "external_primary:Music/Ro", "external_primary:Music/Rock/",
        "external_primary:Music/Jazz", "external_primary:Music/Jazz/Miles Davis", "external_primary:Podcasts",
        "external_primary:Download", "external_primary:Gone", "external_primary:Rock", "external_primary",
        "$card:", "$card:Music", "$card:Music/Rock", "$card:Gone", ":Music", ":Music/Live", ":Old",
        "external_primary:Old", "other:", "Music",
    )

    @Test
    fun `a folder is found without building the tree exactly when the built tree has it`() {
        for (library in listOf(oneVolume, twoVolumes, unnamedVolume, emptyList())) {
            val tree = FolderTree.build(library)
            for (key in keys) {
                assertEquals(tree.node(key) != null, FolderTree.holdsFolder(library, key), "folder $key of ${library.size}")
            }
        }
    }

    @Test
    fun `a folder's own tracks are found without building the tree exactly when the built tree has them`() {
        for (library in listOf(oneVolume, twoVolumes, unnamedVolume, emptyList())) {
            val tree = FolderTree.build(library)
            for (key in keys) {
                assertEquals(
                    tree.node(key)?.tracks?.isNotEmpty() == true,
                    FolderTree.holdsTracksIn(library, key),
                    "tracks of $key of ${library.size}",
                )
            }
        }
    }
}
