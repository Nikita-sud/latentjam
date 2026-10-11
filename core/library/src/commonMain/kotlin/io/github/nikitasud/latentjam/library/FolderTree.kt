/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Where a track's folder is: the storage volume it is on and its path under that volume's root,
 * `/`-separated without leading or trailing slashes. The path is empty for a file in the volume's
 * root itself.
 */
public data class FolderPlace(
    public val volume: String?,
    public val path: String,
)

/**
 * One folder of the [FolderTree]: the folders directly inside it and the tracks directly inside it.
 * Only folders that hold a track somewhere below them exist, so an empty folder is never a node.
 *
 * @property key Stable identity, `volume:path` (the library root above several volumes is ""). It
 *   outlives a rescan, so a remembered place can be found again; see [FolderTree.nearest].
 * @property volume The storage volume, as [TrackDescriptor.storageVolume] names it.
 * @property path The folder's path under the volume, "" for a volume's root and for the library root.
 *   For any other folder it is the [TrackDescriptor.folderPath] its tracks carry.
 * @property folders Subfolders in ascending name order; see [folders] for the other direction.
 * @property tracks The tracks in this folder itself, by title as a folder's page lists them.
 * @property isLibraryRoot True only for the root that lists storage volumes rather than folders.
 */
public class FolderNode internal constructor(
    public val key: String,
    public val volume: String?,
    public val path: String,
    public val folders: List<FolderNode>,
    public val tracks: List<TrackDescriptor>,
    public val isLibraryRoot: Boolean = false,
) {
    /** The folder's own name, its last path segment; "" for a volume's root and the library root. */
    public val name: String get() = path.substringAfterLast('/')

    /** True for the root of one storage volume. */
    public val isVolumeRoot: Boolean get() = !isLibraryRoot && path.isEmpty()

    /** Tracks in this folder and every folder below it. */
    public val trackCount: Int = tracks.size + folders.sumOf { it.trackCount }

    /**
     * Subfolders in the folder tab's [direction]. Storage volumes are not named folders: the library
     * root keeps them in their fixed order (the device's own storage first) either way.
     */
    public fun folders(direction: SongSortDirection): List<FolderNode> =
        if (direction == SongSortDirection.ASCENDING || isLibraryRoot) folders else folders.asReversed()

    /**
     * Every track in this folder and below it, in the order a folder plays: by path, a folder's own
     * tracks before its subfolders' and the subfolders in [direction], then each folder's tracks in
     * title order. [FolderTree.inPlayOrder] puts any set of tracks in this same order.
     */
    public fun allTracks(direction: SongSortDirection = SongSortDirection.ASCENDING): List<TrackDescriptor> {
        val result = ArrayList<TrackDescriptor>(trackCount)
        fun visit(node: FolderNode) {
            result.addAll(node.tracks)
            for (folder in node.folders(direction)) visit(folder)
        }
        visit(this)
        return result
    }

    override fun toString(): String = "FolderNode($key, folders=${folders.size}, tracks=${tracks.size})"
}

/**
 * The library as a file explorer shows it: storage volumes, the folders inside them and the tracks
 * inside each folder, built from each track's [TrackDescriptor.storageVolume] and
 * [TrackDescriptor.folderPath]. Pure and platform-free.
 *
 * The [root] is the top of the music: one volume's root when every track is on one volume, or a
 * library root listing the volumes (internal storage, an SD card) when they are on several. A path
 * that still names its mount point (`/storage/emulated/0/…`, `/storage/1A2B-3C4D/…`,
 * `/mnt/media_rw/…`) is placed under that volume instead of under folders named `storage` and `0`.
 *
 * Folders are ordered by name as the flat folder list orders them (leading punctuation and English
 * articles aside, see [SongSorting.sortKey]); tracks within a folder by title, as a folder's page
 * lists them. Tracks the library does not show (hidden ones, those of a disabled folder) are simply
 * not in the input, so a folder holding nothing else disappears and its parent's counts drop them.
 */
public class FolderTree private constructor(
    public val root: FolderNode,
    private val nodes: Map<String, FolderNode>,
) {
    /** True when the root lists several storage volumes rather than one volume's folders. */
    public val spansVolumes: Boolean get() = root.isLibraryRoot && root.folders.isNotEmpty()

    /** The folder with this [FolderNode.key], or null when the library holds no such folder now. */
    public fun node(key: String): FolderNode? = nodes[key]

    /**
     * The folder at [key], or else its nearest enclosing folder still in the tree, or else [root]: a
     * place remembered across a rescan stays as close as it can when its folder emptied out.
     */
    public fun nearest(key: String?): FolderNode {
        if (key == null) return root
        nodes[key]?.let { return it }
        val separator = key.indexOf(KEY_SEPARATOR)
        if (separator < 0) return root
        val volume = key.substring(0, separator)
        var path = key.substring(separator + 1)
        while (path.isNotEmpty()) {
            path = path.substringBeforeLast('/', missingDelimiterValue = "")
            nodes["$volume$KEY_SEPARATOR$path"]?.let { return it }
        }
        return root
    }

    /** The folders from [root] down to [node], both included: what a breadcrumb shows. */
    public fun trail(node: FolderNode): List<FolderNode> {
        if (node === root) return listOf(root)
        val trail = ArrayList<FolderNode>()
        if (root.isLibraryRoot) trail += root
        val volume = node.volume.orEmpty()
        nodes["$volume$KEY_SEPARATOR"]?.let(trail::add)
        if (node.path.isNotEmpty()) {
            val segments = node.path.split('/')
            for (end in 1..segments.size) {
                nodes["$volume$KEY_SEPARATOR${segments.subList(0, end).joinToString("/")}"]?.let(trail::add)
            }
        }
        return trail
    }

    public companion object {
        /** Android's name for the device's own shared storage, which comes first among volumes. */
        public const val PRIMARY_VOLUME: String = "external_primary"

        /** [FolderNode.key] of the library root above several volumes. */
        public const val LIBRARY_ROOT_KEY: String = ""

        private const val KEY_SEPARATOR = ':'

        /** [FolderNode.key] of the folder at [path] on [volume]. */
        public fun keyOf(volume: String?, path: String): String = "${volume.orEmpty()}$KEY_SEPARATOR$path"

        public fun build(tracks: List<TrackDescriptor>): FolderTree {
            // volume -> folder path -> the tracks directly in it, in input order.
            val byVolume = LinkedHashMap<String?, LinkedHashMap<String, MutableList<TrackDescriptor>>>()
            for (track in tracks) {
                val place = locate(track.storageVolume, track.folderPath)
                byVolume.getOrPut(place.volume) { LinkedHashMap() }
                    .getOrPut(place.path) { ArrayList() }
                    .add(track)
            }
            val nodes = HashMap<String, FolderNode>()
            val volumeRoots = byVolume.entries
                .sortedWith(compareBy<Map.Entry<String?, *>>({ volumeRank(it.key) }, { it.key.orEmpty() }))
                .map { (volume, folders) -> volumeRoot(volume, folders, nodes) }
            val root = if (volumeRoots.size == 1) {
                volumeRoots.single()
            } else {
                FolderNode(
                    key = LIBRARY_ROOT_KEY,
                    volume = null,
                    path = "",
                    folders = volumeRoots,
                    tracks = emptyList(),
                    isLibraryRoot = true,
                ).also { nodes[LIBRARY_ROOT_KEY] = it }
            }
            return FolderTree(root, nodes)
        }

        /**
         * [tracks] in the order their folders play them ([FolderNode.allTracks]): by volume, then
         * by folder path with a folder's own tracks before its subfolders' and folder names in
         * [direction], then by title within a folder. A selection made in the tree plays this way.
         */
        public fun inPlayOrder(
            tracks: List<TrackDescriptor>,
            direction: SongSortDirection = SongSortDirection.ASCENDING,
        ): List<TrackDescriptor> {
            val segmentKeys = HashMap<String, FolderNameKey>()
            fun nameKey(name: String) = segmentKeys.getOrPut(name) { FolderNameKey(name) }
            val keyed = tracks.map { track ->
                val place = locate(track.storageVolume, track.folderPath)
                PlayPlace(
                    track = track,
                    volumeRank = volumeRank(place.volume),
                    volume = place.volume.orEmpty(),
                    segments = if (place.path.isEmpty()) emptyList() else place.path.split('/').map(::nameKey),
                    title = SongSorting.sortKey(track.title),
                )
            }
            val folderOrder = Comparator<PlayPlace> { left, right ->
                compareValues(left.volumeRank, right.volumeRank).takeIf { it != 0 }
                    ?: left.volume.compareTo(right.volume).takeIf { it != 0 }
                    ?: compareSegments(left.segments, right.segments, direction)
            }
            return keyed
                .sortedWith(folderOrder.thenBy { it.title })
                .map { it.track }
        }

        /**
         * The volume and path a track's folder is at. A [folderPath] that still begins with the
         * mount point of a volume (an absolute legacy path) loses it: `storage/emulated/0`,
         * `storage/self/primary` and `mnt/sdcard` are the device's own storage, `storage/<id>` and
         * `mnt/media_rw/<id>` the volume with that id. A bare leading `sdcard` is read as a mount
         * point only when [volume] is unknown, since on a known volume it is more likely a folder
         * someone named so. A known [volume] always wins over the one a prefix names.
         */
        public fun locate(volume: String?, folderPath: String?): FolderPlace {
            val segments = folderPath.orEmpty()
                .replace('\\', '/')
                .split('/')
                .filter { it.isNotEmpty() }
            val mount = mountOf(segments, volumeKnown = volume != null)
                ?: return FolderPlace(volume, segments.joinToString("/"))
            return FolderPlace(
                volume = volume ?: mount.first,
                path = segments.subList(mount.second, segments.size).joinToString("/"),
            )
        }

        /** The volume a leading mount point names and how many segments it takes, or null. */
        private fun mountOf(segments: List<String>, volumeKnown: Boolean): Pair<String, Int>? {
            val first = segments.getOrNull(0) ?: return null
            val second = segments.getOrNull(1)
            val third = segments.getOrNull(2)
            return when {
                first == "storage" && second == "emulated" && third != null && third.all(Char::isDigit) ->
                    PRIMARY_VOLUME to 3
                first == "storage" && second == "self" && third == "primary" -> PRIMARY_VOLUME to 3
                first == "mnt" && second == "sdcard" -> PRIMARY_VOLUME to 2
                first == "mnt" && second == "media_rw" && third != null -> third.lowercase() to 3
                first == "storage" && second != null && second != "emulated" && second != "self" &&
                    (!volumeKnown || VOLUME_ID.matches(second)) -> second.lowercase() to 2
                first == "sdcard" && !volumeKnown -> PRIMARY_VOLUME to 1
                else -> null
            }
        }

        /** A FAT/exFAT volume serial as Android mounts it: `1A2B-3C4D`. */
        private val VOLUME_ID = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")

        /** The device's own storage first, every other volume next, a volume nobody named last. */
        private fun volumeRank(volume: String?): Int = when (volume) {
            PRIMARY_VOLUME -> 0
            null -> 2
            else -> 1
        }

        private fun volumeRoot(
            volume: String?,
            folders: Map<String, List<TrackDescriptor>>,
            nodes: MutableMap<String, FolderNode>,
        ): FolderNode {
            // Every folder that holds a track, and every folder above one, is a node.
            val children = HashMap<String, MutableSet<String>>()
            for (path in folders.keys) {
                var child = path
                while (child.isNotEmpty()) {
                    val parent = child.substringBeforeLast('/', missingDelimiterValue = "")
                    if (!children.getOrPut(parent) { LinkedHashSet() }.add(child)) break
                    child = parent
                }
            }
            val nameKeys = HashMap<String, FolderNameKey>()
            fun node(path: String): FolderNode {
                val subfolders = children[path].orEmpty()
                    .map { it to nameKeys.getOrPut(it) { FolderNameKey(it.substringAfterLast('/')) } }
                    .sortedWith { left, right -> left.second.compareTo(right.second) }
                    .map { node(it.first) }
                val own = folders[path].orEmpty()
                return FolderNode(
                    key = keyOf(volume, path),
                    volume = volume,
                    path = path,
                    folders = subfolders,
                    tracks = if (own.isEmpty()) emptyList() else SongSorting.sort(own, SongSort.TITLE, SongSortDirection.ASCENDING),
                ).also { nodes[it.key] = it }
            }
            return node("")
        }

        private fun compareSegments(
            left: List<FolderNameKey>,
            right: List<FolderNameKey>,
            direction: SongSortDirection,
        ): Int {
            for (index in 0 until minOf(left.size, right.size)) {
                val compared = left[index].compareTo(right[index])
                if (compared != 0) return if (direction == SongSortDirection.ASCENDING) compared else -compared
            }
            // A folder's own tracks come before anything in its subfolders, in either direction.
            return left.size.compareTo(right.size)
        }
    }
}

/** A folder name keyed once: the flat folder list's name order, the raw name breaking ties. */
private class FolderNameKey(val name: String) : Comparable<FolderNameKey> {
    val sortKey: String = SongSorting.sortKey(name)

    override fun compareTo(other: FolderNameKey): Int {
        if (this === other) return 0
        val bySortKey = sortKey.compareTo(other.sortKey)
        return if (bySortKey != 0) bySortKey else name.compareTo(other.name)
    }
}

private class PlayPlace(
    val track: TrackDescriptor,
    val volumeRank: Int,
    val volume: String,
    val segments: List<FolderNameKey>,
    val title: String,
)
