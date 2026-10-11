/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class FolderTreeTest {

    private val primary = FolderTree.PRIMARY_VOLUME
    private val card = "1a2b-3c4d"

    private fun track(
        id: String,
        folder: String?,
        volume: String? = FolderTree.PRIMARY_VOLUME,
        title: String? = id,
    ) = TrackDescriptor(id = TrackId(id), title = title, folderPath = folder, storageVolume = volume)

    private fun FolderNode.folderNames(direction: SongSortDirection = SongSortDirection.ASCENDING) =
        folders(direction).map { it.name }

    private fun List<TrackDescriptor>.ids() = map { it.id.value }

    /** Music/Genre/Artist/Album, the layout a Musicolet library typically has. */
    private val genreArtistAlbum = listOf(
        track("hells-bells", "Music/Rock/AC DC/Back in Black", title = "Hells Bells"),
        track("shoot", "Music/Rock/AC DC/Back in Black", title = "Shoot to Thrill"),
        track("come-together", "Music/Rock/The Beatles/Abbey Road", title = "Come Together"),
        track("something", "Music/Rock/The Beatles/Abbey Road", title = "Something"),
        track("help", "Music/Rock/The Beatles/Help", title = "Help"),
        track("so-what", "Music/Jazz/Miles Davis/Kind of Blue", title = "So What"),
        track("intro", "Music", title = "Intro"),
        track("voice-note", "Recordings", title = "Voice note"),
    )

    @Test
    fun `one volume makes its own root and nested folders keep their hierarchy`() {
        val tree = FolderTree.build(genreArtistAlbum)

        assertFalse(tree.spansVolumes)
        assertTrue(tree.root.isVolumeRoot)
        assertEquals(primary, tree.root.volume)
        assertEquals(listOf("Music", "Recordings"), tree.root.folderNames())
        val music = tree.node(FolderTree.keyOf(primary, "Music"))!!
        assertEquals(listOf("Jazz", "Rock"), music.folderNames())
        assertEquals(listOf("intro"), music.tracks.ids())
        val rock = tree.node(FolderTree.keyOf(primary, "Music/Rock"))!!
        // "The Beatles" shelves under B as in the flat folder list.
        assertEquals(listOf("AC DC", "The Beatles"), rock.folderNames())
        val beatles = tree.node(FolderTree.keyOf(primary, "Music/Rock/The Beatles"))!!
        assertEquals(listOf("Abbey Road", "Help"), beatles.folderNames())
        assertTrue(beatles.tracks.isEmpty())
        val abbeyRoad = tree.node(FolderTree.keyOf(primary, "Music/Rock/The Beatles/Abbey Road"))!!
        assertEquals("Abbey Road", abbeyRoad.name)
        assertEquals("Music/Rock/The Beatles/Abbey Road", abbeyRoad.path)
        assertEquals(listOf("come-together", "something"), abbeyRoad.tracks.ids())
    }

    @Test
    fun `counts include every track below a folder`() {
        val tree = FolderTree.build(genreArtistAlbum)

        assertEquals(8, tree.root.trackCount)
        assertEquals(7, tree.node(FolderTree.keyOf(primary, "Music"))!!.trackCount)
        assertEquals(5, tree.node(FolderTree.keyOf(primary, "Music/Rock"))!!.trackCount)
        assertEquals(3, tree.node(FolderTree.keyOf(primary, "Music/Rock/The Beatles"))!!.trackCount)
        assertEquals(1, tree.node(FolderTree.keyOf(primary, "Recordings"))!!.trackCount)
    }

    @Test
    fun `folders sort by name with the direction applied to subfolders only`() {
        val tree = FolderTree.build(
            listOf(
                track("b", "Music/beta"),
                track("a", "Music/Alpha"),
                track("c", "Music/(Gamma)"),
                track("loose-z", "Music", title = "Zed"),
                track("loose-a", "Music", title = "Ant"),
            ),
        )
        val music = tree.node(FolderTree.keyOf(primary, "Music"))!!

        assertEquals(listOf("Alpha", "beta", "(Gamma)"), music.folderNames())
        assertEquals(listOf("(Gamma)", "beta", "Alpha"), music.folderNames(SongSortDirection.DESCENDING))
        // A folder's own tracks keep title order whichever way its subfolders run.
        assertEquals(listOf("loose-a", "loose-z"), music.tracks.ids())
    }

    @Test
    fun `a folder plays its own tracks first and then each subfolder by path`() {
        val music = FolderTree.build(genreArtistAlbum).node(FolderTree.keyOf(primary, "Music"))!!

        assertEquals(
            listOf("intro", "so-what", "hells-bells", "shoot", "come-together", "something", "help"),
            music.allTracks().ids(),
        )
        assertEquals(
            listOf("intro", "help", "come-together", "something", "hells-bells", "shoot", "so-what"),
            music.allTracks(SongSortDirection.DESCENDING).ids(),
        )
    }

    @Test
    fun `play order of any set of tracks matches the order their folders play in`() {
        val shuffled = genreArtistAlbum.shuffled(kotlin.random.Random(7)) +
            track("sd-song", "Music/Rock", volume = card) +
            track("loose-root", null)
        val tree = FolderTree.build(shuffled)

        for (direction in SongSortDirection.entries) {
            assertEquals(
                tree.root.allTracks(direction).ids(),
                FolderTree.inPlayOrder(shuffled, direction).ids(),
                "direction $direction",
            )
        }
        val picked = shuffled.filter { it.id.value in setOf("help", "intro", "shoot", "sd-song") }
        assertEquals(listOf("intro", "shoot", "help", "sd-song"), FolderTree.inPlayOrder(picked).ids())
    }

    @Test
    fun `several volumes each get a root with the device storage first`() {
        val tree = FolderTree.build(
            listOf(
                track("card-rock", "Music/Rock", volume = card),
                track("phone-rock", "Music/Rock", volume = primary),
                track("usb", "Audio", volume = "0000-1111"),
            ),
        )

        assertTrue(tree.spansVolumes)
        assertTrue(tree.root.isLibraryRoot)
        assertEquals(FolderTree.LIBRARY_ROOT_KEY, tree.root.key)
        assertEquals(listOf(primary, "0000-1111", card), tree.root.folders.map { it.volume })
        assertTrue(tree.root.folders.all { it.isVolumeRoot })
        // The direction never reorders volumes, which are not named folders.
        assertEquals(tree.root.folders, tree.root.folders(SongSortDirection.DESCENDING))
        // The same relative path on two volumes is two folders.
        val phoneRock = tree.node(FolderTree.keyOf(primary, "Music/Rock"))!!
        val cardRock = tree.node(FolderTree.keyOf(card, "Music/Rock"))!!
        assertEquals(listOf("phone-rock"), phoneRock.tracks.ids())
        assertEquals(listOf("card-rock"), cardRock.tracks.ids())
        assertEquals(3, tree.root.trackCount)
    }

    @Test
    fun `an absolute path loses its mount point and lands on its volume`() {
        val tree = FolderTree.build(
            listOf(
                track("emulated", "/storage/emulated/0/Music/Rock", volume = null),
                track("self", "storage/self/primary/Music/Rock/", volume = null),
                track("legacy", "/sdcard/Music", volume = null),
                track("mnt", "/mnt/sdcard/Music", volume = null),
                track("card", "/storage/1A2B-3C4D/Music", volume = null),
                track("media-rw", "/mnt/media_rw/1A2B-3C4D/Music", volume = null),
                track("backslashes", "\\storage\\emulated\\0\\Music\\\\Rock", volume = null),
            ),
        )

        assertTrue(tree.spansVolumes)
        assertEquals(listOf(primary, card), tree.root.folders.map { it.volume })
        val phone = tree.root.folders[0]
        assertEquals(listOf("Music"), phone.folderNames())
        assertEquals(
            setOf("emulated", "self", "backslashes"),
            tree.node(FolderTree.keyOf(primary, "Music/Rock"))!!.tracks.ids().toSet(),
        )
        assertEquals(setOf("legacy", "mnt"), tree.node(FolderTree.keyOf(primary, "Music"))!!.tracks.ids().toSet())
        assertEquals(setOf("card", "media-rw"), tree.node(FolderTree.keyOf(card, "Music"))!!.tracks.ids().toSet())
    }

    @Test
    fun `a known volume wins and keeps a folder that is merely named sdcard`() {
        assertEquals(FolderPlace(primary, "sdcard/Music"), FolderTree.locate(primary, "sdcard/Music"))
        assertEquals(FolderPlace(card, "Music"), FolderTree.locate(card, "storage/1A2B-3C4D/Music"))
        assertEquals(FolderPlace(primary, "storage/Music"), FolderTree.locate(primary, "storage/Music"))
        assertEquals(FolderPlace(null, "Music/Rock"), FolderTree.locate(null, "Music//Rock/"))
        assertEquals(FolderPlace(primary, ""), FolderTree.locate(primary, null))
        assertEquals(FolderPlace(primary, ""), FolderTree.locate(null, "/storage/emulated/0"))
    }

    @Test
    fun `a file in the volume root is a track of the root itself`() {
        val tree = FolderTree.build(listOf(track("loose", null), track("in-music", "Music")))

        assertEquals(listOf("loose"), tree.root.tracks.ids())
        assertEquals(listOf("Music"), tree.root.folderNames())
        assertEquals(2, tree.root.trackCount)
    }

    @Test
    fun `folders the library filtered empty disappear while their parents stay`() {
        // A disabled folder is exactly one folder path: the library drops its own tracks before the
        // tree sees them, as the flat Folders tab never sees them either.
        val excluded = setOf("Music/Rock", "Music/Rock/AC DC/Back in Black")
        val visible = genreArtistAlbum.filterNot { it.folderPath in excluded }
        val tree = FolderTree.build(visible)
        val rock = tree.node(FolderTree.keyOf(primary, "Music/Rock"))!!

        assertNull(tree.node(FolderTree.keyOf(primary, "Music/Rock/AC DC/Back in Black")))
        assertNull(tree.node(FolderTree.keyOf(primary, "Music/Rock/AC DC")))
        assertEquals(listOf("The Beatles"), rock.folderNames())
        assertEquals(3, rock.trackCount)
        assertEquals(6, tree.root.trackCount)
        assertTrue(rock.allTracks().none { it.folderPath in excluded })
    }

    @Test
    fun `no folder in the tree is empty`() {
        val tree = FolderTree.build(genreArtistAlbum)
        fun check(node: FolderNode) {
            assertTrue(node.trackCount > 0, node.key)
            node.folders.forEach(::check)
        }
        check(tree.root)
        assertEquals(0, FolderTree.build(emptyList()).root.trackCount)
        assertTrue(FolderTree.build(emptyList()).root.folders.isEmpty())
    }

    @Test
    fun `iOS paths without a volume make one root`() {
        val tree = FolderTree.build(
            listOf(
                // Music.app's tracks carry a pseudo folder; imported files their Documents path.
                track("music-app", "Music", volume = null),
                track("imported", "Imported/Album", volume = null),
                track("documents-root", null, volume = null),
            ),
        )

        assertFalse(tree.spansVolumes)
        assertNull(tree.root.volume)
        assertEquals(":", tree.root.key)
        assertEquals(listOf("Imported", "Music"), tree.root.folderNames())
        assertEquals(listOf("documents-root"), tree.root.tracks.ids())
        assertEquals(":Imported/Album", tree.node(":Imported/Album")!!.key)
        assertEquals(3, tree.root.trackCount)
    }

    @Test
    fun `a remembered folder that emptied out falls back to its nearest ancestor`() {
        val tree = FolderTree.build(genreArtistAlbum)
        val rock = tree.node(FolderTree.keyOf(primary, "Music/Rock"))!!

        assertSame(rock, tree.nearest(FolderTree.keyOf(primary, "Music/Rock")))
        assertSame(rock, tree.nearest(FolderTree.keyOf(primary, "Music/Rock/Deleted/Deeper")))
        assertSame(tree.root, tree.nearest(FolderTree.keyOf(card, "Music/Rock")))
        assertSame(tree.root, tree.nearest(FolderTree.LIBRARY_ROOT_KEY))
        assertSame(tree.root, tree.nearest(null))
        assertSame(tree.root, tree.nearest("no separator"))
    }

    @Test
    fun `the trail runs from the root down to the folder`() {
        val oneVolume = FolderTree.build(genreArtistAlbum)
        val abbeyRoad = oneVolume.node(FolderTree.keyOf(primary, "Music/Rock/The Beatles/Abbey Road"))!!

        assertEquals(
            listOf("", "Music", "Rock", "The Beatles", "Abbey Road"),
            oneVolume.trail(abbeyRoad).map { it.name },
        )
        assertEquals(listOf(oneVolume.root), oneVolume.trail(oneVolume.root))

        val twoVolumes = FolderTree.build(genreArtistAlbum + track("sd", "Music", volume = card))
        val cardMusic = twoVolumes.node(FolderTree.keyOf(card, "Music"))!!
        assertEquals(
            listOf(FolderTree.LIBRARY_ROOT_KEY, FolderTree.keyOf(card, ""), FolderTree.keyOf(card, "Music")),
            twoVolumes.trail(cardMusic).map { it.key },
        )
    }
}
