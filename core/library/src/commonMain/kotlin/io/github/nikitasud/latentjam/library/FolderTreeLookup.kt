/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Whether the tree of [tracks] has a folder with this [FolderNode.key], answered in one pass over the tracks and
 * without building the tree: [FolderTree.build] sorts the whole library, which a tap on the main thread cannot afford.
 * True exactly when `FolderTree.build(tracks).node(key)` is not null.
 */
public fun FolderTree.Companion.holdsFolder(tracks: List<TrackDescriptor>, key: String): Boolean {
    if (key == LIBRARY_ROOT_KEY) {
        // The root lists volumes rather than folders unless every track is on one volume.
        val volumes = HashSet<String?>()
        for (track in tracks) volumes += locate(track.storageVolume, track.folderPath).volume
        return volumes.size != 1
    }
    return tracks.any { track ->
        val place = locate(track.storageVolume, track.folderPath)
        val volumeRoot = keyOf(place.volume, "")
        if (!key.startsWith(volumeRoot)) return@any false
        // Every folder above a track's own is a node too, the volume's root included.
        val path = key.substring(volumeRoot.length)
        path.isEmpty() || place.path == path || place.path.startsWith("$path/")
    }
}

/**
 * Whether the folder with this [FolderNode.key] holds tracks of its own, not only in folders below it: true exactly
 * when `FolderTree.build(tracks).node(key)?.tracks` is not empty. See [holdsFolder].
 */
public fun FolderTree.Companion.holdsTracksIn(tracks: List<TrackDescriptor>, key: String): Boolean =
    tracks.any { track ->
        val place = locate(track.storageVolume, track.folderPath)
        keyOf(place.volume, place.path) == key
    }
