/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
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
            .replaceFirst("v5|", "v2|").substringBeforeLast('|').substringBeforeLast('|').substringBeforeLast('|')
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
            .replaceFirst("v5|", "v3|").substringBeforeLast('|').substringBeforeLast('|')
        var reads = 0
        val upgraded = GenreEnrichment(settings) { reads++; facts }
        assertTrue(upgraded.backfill(listOf(track)))
        assertEquals(listOf(enriched), upgraded.apply(listOf(track)))
        assertEquals(1, reads)
    }

    @Test
    fun aV4CacheIsReadAgainOnceToLearnTheFilesYear() = runTest {
        val settings = MemorySettings()
        val undated = track.copy(year = null)
        GenreEnrichment(settings) { facts }.backfill(listOf(undated))
        settings.trackGenresPayload = settings.trackGenresPayload!!
            .replaceFirst("v5|", "v4|").substringBeforeLast('|')
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
        override val smartQueueLength = MutableStateFlow(DEFAULT_SMART_QUEUE_LENGTH)
        override fun setSmartQueueLength(length: Int) { smartQueueLength.value = length }
        override val includeNoveltyMixes = MutableStateFlow(false)
        override fun setIncludeNoveltyMixes(enabled: Boolean) { includeNoveltyMixes.value = enabled }
        override val normalizeVolume = MutableStateFlow(true)
        override fun setNormalizeVolume(enabled: Boolean) { normalizeVolume.value = enabled }
        override val crossfadeSeconds = MutableStateFlow(0)
        override fun setCrossfadeSeconds(seconds: Int) { crossfadeSeconds.value = seconds }
        override val songSort = MutableStateFlow(DEFAULT_SONG_SORT)
        override fun setSongSort(choice: SortChoice<SongSort>) { songSort.value = choice }
        override val albumSort = MutableStateFlow(DEFAULT_ALBUM_SORT)
        override fun setAlbumSort(choice: SortChoice<AlbumSort>) { albumSort.value = choice }
        override val artistAlbumSort = MutableStateFlow(DEFAULT_ARTIST_ALBUM_SORT)
        override fun setArtistAlbumSort(choice: SortChoice<AlbumSort>) { artistAlbumSort.value = choice }
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
