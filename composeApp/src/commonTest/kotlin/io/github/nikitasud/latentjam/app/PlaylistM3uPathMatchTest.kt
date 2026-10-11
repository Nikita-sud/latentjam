/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** Import matching by file path: playlists written by other players, phones and computers. */
internal class PlaylistM3uPathMatchTest {

    private fun song(
        id: String,
        folder: String?,
        file: String,
        title: String? = null,
        artist: String? = null,
        seconds: Int? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = artist,
        durationMs = seconds?.let { it * 1_000L + 400 },
        audioUri = "content://media/external/audio/media/$id",
        folderPath = folder,
        fileName = file,
    )

    private fun match(
        m3u: String,
        library: List<TrackDescriptor>,
        filePaths: Map<TrackId, String> = emptyMap(),
    ): List<String?> = matchM3uEntries(parseM3u(m3u), library, filePaths).map { it?.id?.value }

    // Tags that say nothing about the file name, the way most real libraries are tagged. Two
    // albums share song titles, and two files share a name.
    private val library = listOf(
        song("1", "Music/Test Tones/Disc One", "01 Opening Tone.mp3", "Opening", "Tone Lab", 31),
        song("2", "Music/Test Tones/Disc One", "02 - Second Tone.mp3", "Second", "Tone Lab", 32),
        song("3", "Music/Test Tones/Disc Two", "01 Opening Tone.mp3", "Opening", "Tone Lab", 41),
        song("4", "Music/Test Tones/Disc Two", "Intro.flac", "Intro", "Tone Lab", 42),
        song("5", "Music/Other Band/Live", "Intro.flac", "Intro", "Other Band", 43),
        song("6", "Download/Telegram", "tone_a440 (1).mp3", "A440", null, 50),
        song("7", "Music/Symbols", "100% Pure #1.ogg", "Pure", "Tone Lab", 60),
        song("8", null, "root level.mp3", "Root", "Tone Lab", 70),
    )

    /**
     * A Musicolet-style export: the phone's own absolute paths, one per line, under a bare
     * `#EXTM3U` header with no EXTINF rows, written on the same phone or an older one. Nothing in
     * it names a tag, so the old title-only fallback matched only files named exactly like their
     * title.
     */
    private val musicoletExport = """
        #EXTM3U
        /storage/emulated/0/Music/Test Tones/Disc One/01 Opening Tone.mp3
        /storage/emulated/0/Music/Test Tones/Disc One/02 - Second Tone.mp3
        /storage/emulated/0/Music/Test Tones/Disc Two/01 Opening Tone.mp3
        /storage/emulated/0/Music/Test Tones/Disc Two/Intro.flac
        /storage/emulated/0/Music/Other Band/Live/Intro.flac
        /storage/emulated/0/Download/Telegram/tone_a440 (1).mp3
        /storage/emulated/0/Music/Symbols/100% Pure #1.ogg
        /storage/emulated/0/root level.mp3
    """.trimIndent()

    @Test
    fun aMusicoletExportFindsEveryTrackByItsPath() {
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8"),
            match(musicoletExport, library),
        )
    }

    @Test
    fun windowsLineEndingsAndAByteOrderMarkChangeNothing() {
        val windows = "\uFEFF" + musicoletExport.replace("\n", "\r\n") + "\r\n"
        assertEquals(match(musicoletExport, library), match(windows, library))
    }

    @Test
    fun anyRootAboveTheLibraryFoldersMatches() {
        val m3u = """
            /sdcard/Music/Test Tones/Disc One/01 Opening Tone.mp3
            /storage/1A2B-3C4D/Music/Test Tones/Disc Two/01 Opening Tone.mp3
            /mnt/media_rw/1A2B-3C4D/Music/Other Band/Live/Intro.flac
            /home/someone/Music/Test Tones/Disc Two/Intro.flac
            /Users/someone/Music/Test Tones/Disc One/02 - Second Tone.mp3
        """.trimIndent()
        assertEquals(listOf("1", "3", "5", "4", "2"), match(m3u, library))
    }

    @Test
    fun windowsPathsUseBackslashesAndDriveLetters() {
        val m3u = """
            D:\Music\Test Tones\Disc Two\Intro.flac
            C:\Users\someone\Music\Other Band\Live\Intro.flac
            \\nas\share\Music\Test Tones\Disc One\01 Opening Tone.mp3
            Music\Test Tones\Disc Two\01 Opening Tone.mp3
        """.trimIndent()
        assertEquals(listOf("4", "5", "1", "3"), match(m3u, library))
    }

    @Test
    fun relativePathsMatchWhateverFolderThePlaylistSatIn() {
        val m3u = """
            ../Test Tones/Disc One/01 Opening Tone.mp3
            ../../Music/./Test Tones/Disc Two/Intro.flac
            Live/Intro.flac
            ./Disc Two/01 Opening Tone.mp3
            Test Tones/Disc One/../Disc One/02 - Second Tone.mp3
        """.trimIndent()
        assertEquals(listOf("1", "4", "5", "3", "2"), match(m3u, library))
    }

    @Test
    fun aBareFileNameMatchesWhenOnlyOneFileHasIt() {
        assertEquals(listOf("6", null), match("tone_a440 (1).mp3\nIntro.flac", library))
    }

    @Test
    fun fileUrisAndEscapedPathsAreDecoded() {
        val m3u = """
            file:///storage/emulated/0/Music/Test%20Tones/Disc%20One/01%20Opening%20Tone.mp3
            file://localhost/storage/emulated/0/Music/Symbols/100%25%20Pure%20%231.ogg
            file:/storage/emulated/0/Download/Telegram/tone_a440%20(1).mp3
            /storage/emulated/0/Music/Test%20Tones/Disc%20Two/Intro.flac
        """.trimIndent()
        assertEquals(listOf("1", "7", "6", "4"), match(m3u, library))
    }

    @Test
    fun aPercentSignThatIsPartOfTheNameIsNotDecoded() {
        val literal = song("lit", "Music", "a%20b.mp3", "Literal", "X")
        val spaced = song("spc", "Music", "a b.mp3", "Spaced", "X")
        assertEquals(
            listOf("lit", "spc", "7"),
            match(
                "/sdcard/Music/a%20b.mp3\n/sdcard/Music/a b.mp3\n/sdcard/Music/Symbols/100% Pure #1.ogg",
                listOf(literal, spaced) + library,
            ),
        )
    }

    @Test
    fun aFileOnAnotherMachineOrAnotherSchemeIsNoPath() {
        assertNull(m3uPathNames("file://nas/share/Music/a.mp3"))
        assertNull(m3uPathNames("content://media/external/audio/media/42"))
        assertNull(m3uPathNames("https://example.com/Music/a.mp3"))
        assertNull(m3uPathNames("/"))
        assertNull(m3uPathNames("./.."))
    }

    @Test
    fun pathNamesAreNormalised() {
        assertEquals(
            listOf("storage", "emulated", "0", "Music", "New", "a.mp3"),
            m3uPathNames("  /sdcard//Music/Old/../New/./a.mp3  "),
        )
        assertEquals(listOf("storage", "emulated", "0", "a.mp3"), m3uPathNames("/storage/self/primary/a.mp3"))
        assertEquals(listOf("storage", "emulated", "0", "a.mp3"), m3uPathNames("/mnt/sdcard/a.mp3"))
        assertEquals(listOf("Music", "a.mp3"), m3uPathNames("../../Music/a.mp3"))
        assertEquals(listOf("C:", "Music", "a.mp3"), m3uPathNames("C:\\Music\\a.mp3"))
        assertEquals(listOf("Live: 2020", "a.mp3"), m3uPathNames("Live: 2020/a.mp3"))
        assertEquals(listOf("Music", "a%2"), m3uPathNames("Music/a%2"))
        assertNull(m3uPathNames("Music/a%2", decode = true))
        assertEquals(listOf("Música", "ä.mp3"), m3uPathNames("M%C3%BAsica/%C3%A4.mp3", decode = true))
    }

    @Test
    fun fileNamesThatDifferFromTheirTitlesStillMatch() {
        val renamed = song("r", "Music/Rips", "Track 07 (2019 remaster).mp3", "Completely Different", "Band")
        assertEquals(
            listOf("r"),
            match("/storage/emulated/0/Music/Rips/Track 07 (2019 remaster).mp3", listOf(renamed) + library),
        )
    }

    @Test
    fun duplicateTitlesAndFileNamesAreToldApartByTheirFolders() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Tone Lab - Opening
            /storage/emulated/0/Music/Test Tones/Disc Two/01 Opening Tone.mp3
            #EXTINF:-1,Intro
            /storage/emulated/0/Music/Other Band/Live/Intro.flac
            #EXTINF:-1,Tone Lab - Opening
            /storage/emulated/0/Music/Test Tones/Disc One/01 Opening Tone.mp3
        """.trimIndent()
        assertEquals(listOf("3", "5", "1"), match(m3u, library))
    }

    @Test
    fun theSameFolderOnTwoVolumesIsResolvedByTheVolumeWhenTheAbsolutePathIsKnown() {
        val internal = song("int", "Music/Album", "Song.mp3", "Song", "Band", 200)
        val sdCard = song("sd", "Music/Album", "Song.mp3", "Song", "Band", 200)
        val paths = mapOf(
            internal.id to "/storage/emulated/0/Music/Album/Song.mp3",
            sdCard.id to "/storage/1A2B-3C4D/Music/Album/Song.mp3",
        )
        assertEquals(
            listOf("sd", "int", "int"),
            match(
                """
                /storage/1A2B-3C4D/Music/Album/Song.mp3
                /storage/emulated/0/Music/Album/Song.mp3
                /sdcard/Music/Album/Song.mp3
                """.trimIndent(),
                listOf(internal, sdCard),
                paths,
            ),
        )
    }

    @Test
    fun identicalCopiesThatNothingTellsApartStayUnmatched() {
        val internal = song("int", "Music/Album", "Song.mp3", "Song", "Band", 200)
        val sdCard = song("sd", "Music/Album", "Song.mp3", "Song", "Band", 200)
        // No absolute paths: both copies are Music/Album/Song.mp3 on their own volume.
        assertEquals(
            listOf(null),
            match("#EXTINF:200,Band - Song\n/storage/1A2B-3C4D/Music/Album/Song.mp3", listOf(internal, sdCard)),
        )
    }

    @Test
    fun aTieOnThePathIsResolvedByExactDurationAsTagsAre() {
        val short = song("short", "Music/Album", "Song.mp3", "Song", "Band", 180)
        val long = song("long", "Music/Album", "Song.mp3", "Song", "Band", 245)
        assertEquals(
            listOf("long", null),
            match(
                "#EXTINF:245,x\n/sdcard/Music/Album/Song.mp3\n#EXTINF:300,x\n/sdcard/Music/Album/Song.mp3",
                listOf(short, long),
            ),
        )
    }

    @Test
    fun aGenericFileNameInAFolderNoLibraryFileSharesIsAmbiguous() {
        // Disc One and Disc Two both hold "01 Opening Tone.mp3"; Disc Three is not on this phone.
        assertEquals(
            listOf(null),
            match("/storage/emulated/0/Music/Test Tones/Disc Three/01 Opening Tone.mp3", library),
        )
    }

    @Test
    fun aFileNameFoundOnlyInAnotherFolderYieldsToExtinfTags() {
        val other = song("tagged", "Music/Elsewhere", "opening-tone-final.mp3", "Real Opening", "Real Band", 99)
        val onlyCopy = song("name-only", "Music/Somewhere", "Unique Name.mp3", "Unrelated", "Someone", 100)
        assertEquals(
            listOf("tagged", "name-only"),
            match(
                """
                #EXTINF:99,Real Band - Real Opening
                /storage/emulated/0/Music/Gone/Unique Name.mp3
                #EXTINF:100,Not - In Library
                /storage/emulated/0/Music/Gone/Unique Name.mp3
                """.trimIndent(),
                listOf(other, onlyCopy),
            ),
        )
    }

    @Test
    fun aFileNameFoundOnlyInAnotherFolderIsRejectedByAContradictingDuration() {
        val onlyCopy = song("name-only", "Music/Somewhere", "01 Intro.mp3", "Intro", "Someone", 100)
        assertEquals(
            listOf(null, "name-only", "name-only"),
            match(
                """
                #EXTINF:240,Someone Else - Something
                /storage/emulated/0/Music/Other Album/01 Intro.mp3
                #EXTINF:101,Someone Else - Something
                /storage/emulated/0/Music/Other Album/01 Intro.mp3
                /storage/emulated/0/Music/Other Album/01 Intro.mp3
                """.trimIndent(),
                listOf(onlyCopy),
            ),
        )
    }

    @Test
    fun identicalTagsAreToldApartByTheFileNameEvenFromAnotherFolder() {
        val a = song("a", "Music/A", "live-a.mp3", "Live", "Band", 180)
        val b = song("b", "Music/B", "live-b.mp3", "Live", "Band", 180)
        assertEquals(
            listOf("b"),
            match("#EXTINF:180,Band - Live\n/old/phone/Moved/live-b.mp3", listOf(a, b)),
        )
    }

    @Test
    fun caseIsIgnoredAndExactCaseBreaksATie() {
        val upper = song("upper", "Music/Rock", "Song.mp3", "Song", "A", 100)
        val lower = song("lower", "Music/rock", "Song.mp3", "Song", "B", 100)
        // The third row matches neither folder's case, so it fits both equally well.
        assertEquals(
            listOf("upper", "lower", null),
            match(
                "/sdcard/Music/Rock/Song.mp3\n/sdcard/Music/rock/Song.mp3\nE:\\MUSIC\\ROCK\\SONG.MP3",
                listOf(lower, upper),
            ),
        )
        assertEquals(listOf("4"), match("/SDCARD/music/test tones/disc two/INTRO.FLAC", library))
    }

    @Test
    fun theLibrarysAbsolutePathsAreUsedWhenItHasThem() {
        val noFolder = TrackDescriptor(id = TrackId("abs"), title = "Unrelated", fileName = null)
        assertEquals(
            listOf("abs"),
            match(
                "/sdcard/Music/Album/x.mp3",
                listOf(noFolder),
                mapOf(noFolder.id to "/storage/emulated/0/Music/Album/x.mp3"),
            ),
        )
    }

    @Test
    fun tracksWithoutAFileNameKeepTheOldFallbacks() {
        // The iOS Music library has no file names: title matching is all there is.
        val musicApp = TrackDescriptor(id = TrackId("ios"), title = "Opening Tone", folderPath = "Music")
        assertEquals(listOf("ios"), match("/x/y/Opening Tone.m4a", listOf(musicApp)))
    }

    @Test
    fun aLatentJamExportRoundTripsThroughThePathEvenAfterARetag() {
        val track = song("1", "Music/Unsorted", "Freestyler.mp3", "Freestyler", "Bomfunk MC's", 306)
        val exported = encodeM3u(
            name = "Party",
            tracks = listOf(track),
            paths = mapOf(track.id to "/storage/emulated/0/Music/Unsorted/Freestyler.mp3"),
            relativeTo = "/storage/emulated/0/Music/Playlists",
        )
        val retagged = track.copy(title = "Freestyler (Radio Edit)", artist = "Bomfunk MCs")
        assertEquals(listOf("1"), match(exported, listOf(retagged)))
    }

    @Test
    fun importCountsRowsNotDistinctTracksAndKeepsEachTrackOnce() = runTest {
        val m3u = """
            #EXTM3U
            #PLAYLIST:Road trip
            /storage/emulated/0/Music/Test Tones/Disc One/01 Opening Tone.mp3
            /storage/emulated/0/Music/Gone/missing.mp3
            /storage/emulated/0/Music/Test Tones/Disc Two/Intro.flac
            /storage/emulated/0/Music/Test Tones/Disc One/01 Opening Tone.mp3
        """.trimIndent()
        var asked: List<TrackId>? = null
        val imported = importM3u(m3u, library) { ids -> asked = ids; emptyMap() }

        assertEquals("Road trip", imported.name)
        assertEquals(4, imported.entryCount)
        assertEquals(3, imported.matchedEntryCount)
        assertEquals(listOf("1", "4"), imported.tracks.map { it.id.value })
        assertEquals(library.map { it.id }, asked)
    }

    @Test
    fun importSurvivesAPathLookupThatFails() = runTest {
        val imported = importM3u(musicoletExport, library) { error("MediaStore went away") }
        assertEquals(8, imported.matchedEntryCount)
    }

    @Test
    fun importOfAFileWithoutRowsNeverAsksForPaths() = runTest {
        val imported = importM3u("#EXTM3U\n#PLAYLIST:Empty\n", library) { error("not asked") }
        assertEquals(0, imported.entryCount)
        assertEquals(emptyList(), imported.tracks)
    }
}
