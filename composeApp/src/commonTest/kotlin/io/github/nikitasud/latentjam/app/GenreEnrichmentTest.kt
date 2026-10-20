/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.library.tags.TagFacts
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class GenreEnrichmentTest {
    private val track = TrackDescriptor(
        TrackId("track|with\nseparators 🎧"),
        genre = "Rock",
        year = 2012,
        sourceRevision = "revision|1",
    )
    private val facts = EmbeddedTagFacts(
        genres = listOf("Rock", "Pop"),
        artists = listOf("First artist", "Second artist"),
        originalYear = 1987,
        language = "русский",
        albumArtist = "Various Artists",
        // MediaStore said 2012 for [track]; its year wins, so [enriched] keeps 2012.
        year = 1999,
    )
    private val enriched = track.copy(
        genre = "Rock; Pop",
        artists = facts.artists,
        originalYear = facts.originalYear,
        language = facts.language,
        albumArtist = facts.albumArtist,
    )

    @Test
    fun failedCacheWriteKeepsFactsAndRetriesWithoutReadingFilesAgain() = runTest {
        val settings = MemorySettings().apply { failWrites = true }
        var reads = 0
        val enrichment = GenreEnrichment(settings) { reads++; facts }

        assertTrue(enrichment.backfill(listOf(track)))
        assertEquals(listOf(enriched), enrichment.apply(listOf(track)))
        assertNull(settings.trackGenresPayload)
        assertEquals(1, settings.writeAttempts)

        settings.failWrites = false
        assertFalse(enrichment.backfill(listOf(enriched)))
        assertEquals(1, reads)
        assertEquals(2, settings.writeAttempts)
        val restarted = GenreEnrichment(settings) { error("a persisted file must stay warm") }
        assertEquals(listOf(enriched), restarted.apply(listOf(track)))
        assertFalse(restarted.backfill(listOf(enriched)))
        assertEquals(2, settings.writeAttempts)
    }

    @Test
    fun aLargePassSavesAsItGoesAndALaterPassReadsNothing() = runTest {
        val settings = MemorySettings()
        var reads = 0
        val enrichment = GenreEnrichment(settings) { reads++; facts }
        val library = (0 until 9_000).map { TrackDescriptor(TrackId("t$it"), genre = "Rock", sourceRevision = "r$it") }

        assertTrue(enrichment.backfill(library))
        assertEquals(9_000, reads)
        // Saved in chunks along the way (nine: eight of 1,024 + the rest), every song in the end.
        assertEquals(9, settings.writeAttempts)
        val restarted = GenreEnrichment(settings) { error("every file was saved") }
        assertFalse(restarted.backfill(library))
        assertEquals(library.map { it.id }, restarted.apply(library).map { it.id })
        assertEquals("Rock; Pop", restarted.apply(library).last().genre)

        // A changed revision is read again; the rest stay warm.
        val changed = library.mapIndexed { index, track -> if (index == 4_500) track.copy(sourceRevision = "new") else track }
        val again = GenreEnrichment(settings) { reads++; facts }
        again.backfill(changed)
        assertEquals(9_001, reads)
    }

    @Test
    fun cancellationIsNeverSwallowedByCachePersistence() = runTest {
        val settings = MemorySettings().apply { cancelWrites = true }
        val enrichment = GenreEnrichment(settings) { facts }
        assertFailsWith<CancellationException> { enrichment.backfill(listOf(track)) }
        settings.cancelWrites = false
        enrichment.backfill(listOf(track))
        assertEquals(2, settings.writeAttempts)
    }

    @Test
    fun languageSurvivesRestartAndRemovedTagsDisappearAfterRevisionChange() = runTest {
        val settings = MemorySettings()
        GenreEnrichment(settings) { facts }.backfill(listOf(track))
        var reads = 0
        val restarted = GenreEnrichment(settings) { reads++; EmbeddedTagFacts() }
        assertEquals(listOf(enriched), restarted.apply(listOf(track)))
        assertFalse(restarted.backfill(listOf(enriched)))
        assertEquals(0, reads)

        val retagged = track.copy(sourceRevision = "revision|2")
        assertEquals(listOf(retagged), restarted.apply(listOf(retagged)))
        assertFalse(restarted.backfill(listOf(retagged)))
        assertEquals(1, reads)
        val afterRemoval = GenreEnrichment(settings) { error("empty facts also stay warm") }
        assertEquals(listOf(retagged), afterRemoval.apply(listOf(retagged)))
        assertFalse(afterRemoval.backfill(listOf(retagged)))
    }

    @Test
    fun v2CacheIsReadAgainOnceToLearnLanguage() = runTest {
        val settings = MemorySettings()
        GenreEnrichment(settings) { facts }.backfill(listOf(track))
        settings.trackGenresPayload = settings.trackGenresPayload!!
            .replaceFirst("v6|", "v2|").substringBeforeLast('|').substringBeforeLast('|').substringBeforeLast('|').substringBeforeLast('|')
        var reads = 0
        val upgraded = GenreEnrichment(settings) { reads++; facts }
        assertEquals(listOf(track), upgraded.apply(listOf(track)))
        assertTrue(upgraded.backfill(listOf(track)))
        assertEquals(listOf(enriched), upgraded.apply(listOf(track)))
        assertEquals(1, reads)
        val restarted = GenreEnrichment(settings) { error("upgrade already persisted") }
        assertFalse(restarted.backfill(listOf(enriched)))
    }

    @Test
    fun aV3CacheIsReadAgainOnceToLearnTheAlbumArtist() = runTest {
        val settings = MemorySettings()
        GenreEnrichment(settings) { facts }.backfill(listOf(track))
        settings.trackGenresPayload = settings.trackGenresPayload!!
            .replaceFirst("v6|", "v3|").substringBeforeLast('|').substringBeforeLast('|').substringBeforeLast('|')
        var reads = 0
        val upgraded = GenreEnrichment(settings) { reads++; facts }
        assertTrue(upgraded.backfill(listOf(track)))
        assertEquals(listOf(enriched), upgraded.apply(listOf(track)))
        assertEquals(1, reads)
    }

    @Test
    fun aSongWhoseFileNamesNoAlbumBorrowsNoAlbumCover() = runTest {
        val settings = MemorySettings()
        val inFolder = track.copy(album = "TAMIL", artworkUri = "song-cover+album")
        val enrichment = GenreEnrichment(settings, ownCover = { it.removeSuffix("+album") }) { EmbeddedTagFacts() }
        assertEquals(listOf(inFolder), enrichment.apply(listOf(inFolder)))
        assertTrue(enrichment.backfill(listOf(inFolder)))
        // Only the cover changes: the scanner's folder-named album still lists the song.
        val ownCover = inFolder.copy(artworkUri = "song-cover")
        assertEquals(listOf(ownCover), enrichment.apply(listOf(inFolder)))

        val restarted = GenreEnrichment(settings, ownCover = { it.removeSuffix("+album") }) { error("a persisted file must stay warm") }
        assertEquals(listOf(ownCover), restarted.apply(listOf(inFolder)))
    }

    @Test
    fun aSongWhoseFileNamesItsAlbumKeepsTheAlbumCoverFallback() = runTest {
        val settings = MemorySettings()
        val tagged = track.copy(album = "Abbey Road", artworkUri = "song-cover+album")
        val enrichment = GenreEnrichment(settings, ownCover = { error("never stripped") }) { EmbeddedTagFacts(album = "Abbey Road") }
        assertFalse(enrichment.backfill(listOf(tagged)))
        assertEquals(listOf(tagged), enrichment.apply(listOf(tagged)))
        val restarted = GenreEnrichment(settings, ownCover = { error("never stripped") }) { error("a persisted file must stay warm") }
        assertEquals(listOf(tagged), restarted.apply(listOf(tagged)))
    }

    @Test
    fun aTagReadInPartKeepsTheAlbumCoverFallback() = runTest {
        val settings = MemorySettings()
        val partial = track.copy(artworkUri = "song-cover+album")
        val enrichment = GenreEnrichment(settings, ownCover = { error("never stripped") }) { EmbeddedTagFacts(albumKnown = false) }
        enrichment.backfill(listOf(partial))
        assertEquals(listOf(partial), enrichment.apply(listOf(partial)))
    }

    @Test
    fun anUnreadableFileKeepsTheAlbumCoverFallback() = runTest {
        val settings = MemorySettings()
        val unknown = track.copy(artworkUri = "song-cover+album")
        val enrichment = GenreEnrichment(settings, ownCover = { error("never stripped") }) { null }
        assertFalse(enrichment.backfill(listOf(unknown)))
        assertEquals(listOf(unknown), enrichment.apply(listOf(unknown)))
    }

    @Test
    fun aV5CacheStillAppliesWhileItsFilesAreReadAgain() = runTest {
        val settings = MemorySettings()
        val inFolder = track.copy(artworkUri = "song-cover+album")
        GenreEnrichment(settings, ownCover = { it.removeSuffix("+album") }) { facts }.backfill(listOf(inFolder))
        settings.trackGenresPayload = settings.trackGenresPayload!!
            .replaceFirst("v6|", "v5|").substringBeforeLast('|')
        var reads = 0
        val upgraded = GenreEnrichment(settings, ownCover = { it.removeSuffix("+album") }) { reads++; facts }
        // Before the re-read: the v5 facts apply, and the album fallback is kept.
        assertEquals(listOf(enriched.copy(artworkUri = "song-cover+album")), upgraded.apply(listOf(inFolder)))
        assertTrue(upgraded.backfill(listOf(inFolder)))
        assertEquals(listOf(enriched.copy(artworkUri = "song-cover")), upgraded.apply(listOf(inFolder)))
        assertEquals(1, reads)
        val restarted = GenreEnrichment(settings, ownCover = { it.removeSuffix("+album") }) { error("re-read once only") }
        assertFalse(restarted.backfill(listOf(inFolder)))
    }

    @Test
    fun aSaveKeepsTheV5LinesItDoesNotReplace() = runTest {
        val settings = MemorySettings()
        val other = track.copy(id = TrackId("other"), sourceRevision = "r2")
        GenreEnrichment(settings) { facts }.backfill(listOf(track, other))
        // Both lines become v5; then only [other] is read again and saved.
        settings.trackGenresPayload = settings.trackGenresPayload!!.lines()
            .joinToString("\n") { it.replaceFirst("v6|", "v5|").substringBeforeLast('|') }
        GenreEnrichment(settings) { facts }.backfill(listOf(other))
        val lines = settings.trackGenresPayload!!.lines()
        assertEquals(1, lines.count { it.startsWith("v5|") }, "the untouched song's v5 line stays")
        assertEquals(1, lines.count { it.startsWith("v6|") })
    }

    @Test
    fun aPassCutShortKeepsWhatItSaved() = runTest {
        val settings = MemorySettings()
        val library = List(1030) { track.copy(id = TrackId("t$it")) }
        var reads = 0
        assertFailsWith<CancellationException> {
            GenreEnrichment(settings) {
                if (reads++ == 1027) throw CancellationException("closed")
                facts
            }.backfill(library)
        }
        var rereads = 0
        GenreEnrichment(settings) { rereads++; facts }.backfill(library)
        // Saved every 1,024 files: only what came after the last save is read again.
        assertEquals(1030 - 1024, rereads)
    }

    @Test
    fun aV4CacheIsReadAgainOnceToLearnTheFilesYear() = runTest {
        val settings = MemorySettings()
        val undated = track.copy(year = null)
        GenreEnrichment(settings) { facts }.backfill(listOf(undated))
        settings.trackGenresPayload = settings.trackGenresPayload!!
            .replaceFirst("v6|", "v4|").substringBeforeLast('|').substringBeforeLast('|')
        var reads = 0
        val upgraded = GenreEnrichment(settings) { reads++; facts }
        assertEquals(listOf(undated), upgraded.apply(listOf(undated)))
        assertTrue(upgraded.backfill(listOf(undated)))
        assertEquals(listOf(enriched.copy(year = 1999)), upgraded.apply(listOf(undated)))
        assertEquals(1, reads)
        val restarted = GenreEnrichment(settings) { error("upgrade already persisted") }
        assertEquals(listOf(enriched.copy(year = 1999)), restarted.apply(listOf(undated)))
        assertFalse(restarted.backfill(listOf(enriched.copy(year = 1999))))
    }

    @Test
    fun theFilesYearFillsInWhenTheSystemScannerHasNone() = runTest {
        // Android 16's MediaStore leaves YEAR empty for every MP3, FLAC and Opus file.
        val settings = MemorySettings()
        val undated = track.copy(year = null)
        val yearOnly = EmbeddedTagFacts(year = 1997)
        val enrichment = GenreEnrichment(settings) { yearOnly }
        assertTrue(enrichment.backfill(listOf(undated)))
        assertEquals(listOf(undated.copy(year = 1997)), enrichment.apply(listOf(undated)))
        val restarted = GenreEnrichment(settings) { error("the year is remembered") }
        assertEquals(listOf(undated.copy(year = 1997)), restarted.apply(listOf(undated)))
        assertFalse(restarted.backfill(listOf(undated.copy(year = 1997))))
    }

    @Test
    fun theSystemScannersYearWinsOverTheFilesYear() = runTest {
        val settings = MemorySettings()
        val enrichment = GenreEnrichment(settings) { EmbeddedTagFacts(year = 1997) }
        // Nothing new is learned, so no reload is requested.
        assertFalse(enrichment.backfill(listOf(track)))
        assertEquals(listOf(track), enrichment.apply(listOf(track)))
        assertEquals(2012, enrichment.apply(listOf(track)).single().year)
    }

    @Test
    fun corruptLanguageFieldRetriesOnlyTheDamagedCacheEntry() = runTest {
        for (corrupt in listOf("zz", "a", "ff")) {
            val settings = MemorySettings()
            val other = track.copy(id = TrackId("other"))
            GenreEnrichment(settings) { facts }.backfill(listOf(track, other))
            val lines = settings.trackGenresPayload!!.lines()
            // Keep the second entry intact, so corruption cannot flush unrelated cached facts.
            val damagedId = lines.first().split('|')[1]
            val fields = lines.first().split('|').toMutableList()
            fields[6] = corrupt
            settings.trackGenresPayload = fields.joinToString("|") + "\n" + lines.last()
            val reads = mutableListOf<TrackDescriptor>()
            val restarted = GenreEnrichment(settings) { reads += it; facts }
            assertTrue(restarted.backfill(listOf(track, other)))
            assertEquals(1, reads.size)
            assertEquals(damagedId, reads.single().id.value.hex())
            assertEquals(listOf(enriched, enriched.copy(id = other.id)), restarted.apply(listOf(track, other)))
        }
    }

    @Test
    fun namesGarbledByTheOldTagReaderAreRepairedFromTheCacheWithoutReadingFiles() = runTest {
        // 0.6.0 decoded UTF-8 in Latin-1 ID3 frames as Latin-1 and cached what it got.
        val settings = MemorySettings()
        GenreEnrichment(settings) {
            EmbeddedTagFacts(
                genres = listOf("HÃ¶rspiel", "Rock"),
                artists = listOf("MÃ¶tley CrÃ¼e", "Plain"),
                language = "FranÃ§ais",
            )
        }.backfill(listOf(track))
        val scanned = track.copy(genre = "Hörspiel")
        val restarted = GenreEnrichment(settings) { error("the cache must be repaired, not re-read") }
        assertEquals(
            listOf(scanned.copy(genre = "Hörspiel; Rock", artists = listOf("Mötley Crüe", "Plain"), language = "Français")),
            restarted.apply(listOf(scanned)),
        )
        assertFalse(restarted.backfill(listOf(scanned)))
    }

    @Test
    fun unreadableFilesAreRetriedWhileSuccessfullyReadEmptyTagsAreCached() = runTest {
        val settings = MemorySettings()
        var result: EmbeddedTagFacts? = null
        var reads = 0
        val enrichment = GenreEnrichment(settings) { reads++; result }
        assertFalse(enrichment.backfill(listOf(track)))
        assertNull(settings.trackGenresPayload)
        result = EmbeddedTagFacts()
        assertFalse(enrichment.backfill(listOf(track)))
        assertFalse(enrichment.backfill(listOf(track)))
        assertEquals(2, reads)
        assertEquals(1, settings.writeAttempts)
    }

    private fun albumTrack(id: String, revision: String, year: Int? = null) = TrackDescriptor(
        TrackId(id),
        title = "Song $id",
        artist = "O4 Artist",
        album = "O4 Album",
        artworkUri = "art:o4",
        year = year,
        sourceRevision = revision,
    )

    /**
     * After a save the rescan holds the saved files at new revisions, so what only the files know
     * waits for the enrichment's re-read. An open album page refreshed from the rescan alone shows
     * another year than the album card; refreshed from the enrichment's republish, the same one.
     */
    @Test
    fun anAlbumPageShowsTheCardsYearOnceTheEnrichmentRepublishesAfterASave() = runTest {
        // Android 16: the year is only in the files' DATE, and the scanner reports none.
        val enrichment = GenreEnrichment(MemorySettings()) { EmbeddedTagFacts(year = 2004, originalYear = 2004) }
        val pageIds = setOf(TrackId("1"), TrackId("2"))
        enrichment.backfill(listOf(albumTrack("1", "r1"), albumTrack("2", "r1")))
        val rescanned = enrichment.apply(listOf(albumTrack("1", "r2"), albumTrack("2", "r2")))
        assertNull(albumYearLabel(refreshedAlbum(LibraryCatalog.build(rescanned), pageIds)!!.tracks))

        assertTrue(enrichment.backfill(rescanned), "anything learned is republished")
        val catalog = LibraryCatalog.build(enrichment.apply(rescanned))
        assertEquals("2004", albumYearLabel(catalog.albums.single().tracks))
        assertEquals("2004", albumYearLabel(refreshedAlbum(catalog, pageIds)!!.tracks))
    }

    @Test
    fun aRemasterPageShowsTheOriginalYearOnceTheEnrichmentRepublishesAfterASave() = runTest {
        // A 2012 remaster of a 1973 album: the scan says 2012, only the file's TDOR says 1973.
        val enrichment = GenreEnrichment(MemorySettings()) { EmbeddedTagFacts(originalYear = 1973) }
        val pageIds = setOf(TrackId("1"))
        val rescanned = enrichment.apply(listOf(albumTrack("1", "r2", year = 2012)))
        assertEquals("2012", albumYearLabel(refreshedAlbum(LibraryCatalog.build(rescanned), pageIds)!!.tracks))

        assertTrue(enrichment.backfill(rescanned))
        val catalog = LibraryCatalog.build(enrichment.apply(rescanned))
        assertEquals("1973", albumYearLabel(catalog.albums.single().tracks))
        assertEquals("1973", albumYearLabel(refreshedAlbum(catalog, pageIds)!!.tracks))
    }

    /** An ID3v2.3 tag whose frames are declared ISO-8859-1 (encoding 0) and hold [text]'s UTF-8 bytes. */
    private fun latin1DeclaredTag(vararg frames: Pair<String, String>): ByteArray {
        fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        val body = frames.fold(ByteArray(0)) { all, (id, text) ->
            val content = byteArrayOf(0) + text.encodeToByteArray()
            all + id.encodeToByteArray() + be32(content.size) + byteArrayOf(0, 0) + content
        }
        val size = body.size
        val syncsafe = byteArrayOf((size ushr 21 and 0x7F).toByte(), (size ushr 14 and 0x7F).toByte(), (size ushr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
        return "ID3".encodeToByteArray() + byteArrayOf(3, 0, 0) + syncsafe + body + TEST_MPEG_FRAME + ByteArray(512)
    }

    private fun factsOf(file: ByteArray): EmbeddedTagFacts? = TagFacts.embedded(object : GenreTags.ByteSource {
        var at = 0
        override fun read(count: Int): ByteArray? =
            if (at + count > file.size) null else file.copyOfRange(at, at + count).also { at += count }
        override fun readUpTo(count: Int): ByteArray = file.copyOfRange(at, minOf(file.size, at + count)).also { at += it.size }
        override fun skip(count: Long): Boolean {
            if (at + count > file.size) return false
            at += count.toInt()
            return true
        }
    })

    /** A file an older tagger wrote: text already mangled once ("é" read as cp1252), then stored as UTF-8. */
    private val mangledTag = latin1DeclaredTag(
        "TPE2" to "BeyoncÃ© LumiÃ¨re",
        "TCON" to "CafÃ© Pop",
        "TPE1" to "BeyoncÃ©\u0000LumiÃ¨re",
    )

    @Test
    fun textReadFromTagsIsRepairedAsMediaStoresIsFromTheFirstRead() = runTest {
        // Where MediaStore has no album artist (below Android 11) the tag's is all there is.
        val track = TrackDescriptor(TrackId("1"), title = "Café Société", sourceRevision = "r1")
        val enrichment = GenreEnrichment(MemorySettings()) { factsOf(mangledTag) }
        assertTrue(enrichment.backfill(listOf(track)))
        val applied = enrichment.apply(listOf(track)).single()
        assertEquals("Beyoncé Lumière", applied.albumArtist)
        assertEquals("Café Pop", applied.genre)
        assertEquals(listOf("Beyoncé", "Lumière"), applied.artists)
    }

    @Test
    fun aTagValueThatIsMediaStoresOwnMangledNeverReplacesIt() = runTest {
        // Short mojibake through Windows-1250 that repair must leave alone; MediaStore read it right.
        val track = TrackDescriptor(TrackId("1"), albumArtist = "Sé", genre = "Pé", sourceRevision = "r1")
        val enrichment = GenreEnrichment(MemorySettings()) {
            factsOf(latin1DeclaredTag("TPE2" to "S\u0102\u00A9", "TCON" to "P\u0102\u00A9"))
        }
        enrichment.backfill(listOf(track))
        val applied = enrichment.apply(listOf(track)).single()
        assertEquals("Sé", applied.albumArtist)
        assertEquals("Pé", applied.genre)
    }

    @Test
    fun aTagValueThatDiffersFromMediaStoresStillWins() = runTest {
        val track = TrackDescriptor(TrackId("1"), albumArtist = "Various", sourceRevision = "r1")
        val enrichment = GenreEnrichment(MemorySettings()) { EmbeddedTagFacts(albumArtist = "Beyoncé Lumière") }
        enrichment.backfill(listOf(track))
        assertEquals("Beyoncé Lumière", enrichment.apply(listOf(track)).single().albumArtist)
    }

    @Test
    fun theCacheStaysOnStorageInsteadOfInTheHeap() = runTest {
        val settings = MemorySettings()
        val enrichment = GenreEnrichment(settings) { facts }
        enrichment.backfill(listOf(track))
        assertEquals(listOf(enriched), enrichment.apply(listOf(track)))
        // A store emptied behind its back is seen at once: nothing was kept resident.
        settings.trackGenresPayload = ""
        assertEquals(listOf(track), enrichment.apply(listOf(track)))
    }

    @Test
    fun anAlbumsSongsShareOneInstanceOfEachLearnedName() = runTest {
        val songs = (1..3).map { TrackDescriptor(TrackId("song $it"), genre = "Rock", sourceRevision = "r$it") }
        val settings = MemorySettings()
        // Each read returns fresh strings, as decoding a file does.
        GenreEnrichment(settings) { facts.copy(genres = listOf("Rock", "Pop").map { it.toCharArray().concatToString() },
            artists = facts.artists.map { it.toCharArray().concatToString() }) }.backfill(songs)
        val applied = GenreEnrichment(settings) { error("warm") }.apply(songs)
        assertEquals("Rock; Pop", applied[0].genre)
        assertTrue(applied.all { it.genre === applied[0].genre })
        assertTrue(applied.all { it.artists[1] === applied[0].artists[1] && it.albumArtist === applied[0].albumArtist })
    }

    private fun String.hex(): String = encodeToByteArray().joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private class MemorySettings : AppSettings {
        override val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
        override fun setThemeMode(mode: ThemeMode) { themeMode.value = mode }
        override val startPage = MutableStateFlow(StartPage.TRACKS)
        override fun setStartPage(page: StartPage) { startPage.value = page }
        override val pageLayout = MutableStateFlow(PageLayout())
        override fun setPageLayout(layout: PageLayout) { pageLayout.value = layout.normalized() }
        override val trackColorMode = MutableStateFlow(TrackColorMode.DYNAMIC)
        override fun setTrackColorMode(mode: TrackColorMode) { trackColorMode.value = mode }
        override val pageHeaderStyle = MutableStateFlow(PageHeaderStyle.DOTS)
        override fun setPageHeaderStyle(style: PageHeaderStyle) { pageHeaderStyle.value = style }
        override val smartQueueLength = MutableStateFlow(DEFAULT_SMART_QUEUE_LENGTH)
        override fun setSmartQueueLength(length: Int) { smartQueueLength.value = length }
        override val artistVariety = MutableStateFlow(DEFAULT_ARTIST_VARIETY)
        override fun setArtistVariety(level: Int) { artistVariety.value = level }
        override val includeNoveltyMixes = MutableStateFlow(false)
        override fun setIncludeNoveltyMixes(enabled: Boolean) { includeNoveltyMixes.value = enabled }
        override val normalizeVolume = MutableStateFlow(true)
        override fun setNormalizeVolume(enabled: Boolean) { normalizeVolume.value = enabled }
        override val crossfadeSeconds = MutableStateFlow(0)
        override fun setCrossfadeSeconds(seconds: Int) { crossfadeSeconds.value = seconds }
        override val relativePlaylistPaths = MutableStateFlow(false)
        override fun setRelativePlaylistPaths(enabled: Boolean) { relativePlaylistPaths.value = enabled }
        override val songSort = MutableStateFlow(DEFAULT_SONG_SORT)
        override fun setSongSort(choice: SortChoice<SongSort>) { songSort.value = choice }
        override val albumSort = MutableStateFlow(DEFAULT_ALBUM_SORT)
        override fun setAlbumSort(choice: SortChoice<AlbumSort>) { albumSort.value = choice }
        override val artistAlbumSort = MutableStateFlow(DEFAULT_ARTIST_ALBUM_SORT)
        override val groupSortOrders = MutableStateFlow(GroupSortOrders())
        override fun setGroupSortOrders(orders: GroupSortOrders) { groupSortOrders.value = orders }
        override fun setArtistAlbumSort(choice: SortChoice<AlbumSort>) { artistAlbumSort.value = choice }
        override val playlistSortOrders = MutableStateFlow(PlaylistSortOrders())
        override fun setPlaylistSortOrders(orders: PlaylistSortOrders) { playlistSortOrders.value = orders }
        override fun readTrackLoudnessPayload(): String? = null
        override fun writeTrackLoudnessPayload(payload: String) = Unit

        var trackGenresPayload: String? = null
        var writeAttempts = 0
        var failWrites = false
        var cancelWrites = false
        override fun readTrackGenresPayload(): String? = trackGenresPayload
        override fun writeTrackGenresPayload(payload: String) {
            writeAttempts++
            if (cancelWrites) throw CancellationException("cancelled")
            check(!failWrites) { "disk unavailable" }
            trackGenresPayload = payload
        }
        override fun readAudioCarryOversPayload(): String? = null
        override fun writeAudioCarryOversPayload(payload: String) = Unit
        private var duplicateDismissalsPayload: String? = null
        override fun readDuplicateDismissalsPayload(): String? = duplicateDismissalsPayload
        override fun writeDuplicateDismissalsPayload(payload: String) {
            duplicateDismissalsPayload = payload
        }
        override val saveListeningHistory = MutableStateFlow(true)
        override suspend fun setSaveListeningHistory(enabled: Boolean): Result<Unit> =
            Result.success(Unit)
        override val rememberSearches = MutableStateFlow(true)
        override suspend fun setRememberSearches(enabled: Boolean): Result<Unit> =
            Result.success(Unit)
        override val resumePlayback = MutableStateFlow<ResumePlayback?>(null)
        override fun setResumePlayback(state: ResumePlayback?) { resumePlayback.value = state }
    }

}
