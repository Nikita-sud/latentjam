/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

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
    )
    private val enriched = track.copy(
        genre = "Rock; Pop",
        artists = facts.artists,
        originalYear = facts.originalYear,
        language = facts.language,
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
            .replaceFirst("v3|", "v2|").substringBeforeLast('|')
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
    fun corruptLanguageFieldRetriesOnlyTheDamagedCacheEntry() = runTest {
        for (corrupt in listOf("zz", "a", "ff")) {
            val settings = MemorySettings()
            val other = track.copy(id = TrackId("other"))
            GenreEnrichment(settings) { facts }.backfill(listOf(track, other))
            val lines = settings.trackGenresPayload!!.lines()
            // Keep the second entry intact, so corruption cannot flush unrelated cached facts.
            val damagedId = lines.first().split('|')[1]
            settings.trackGenresPayload =
                lines.first().substringBeforeLast('|') + "|" + corrupt + "\n" + lines.last()
            val reads = mutableListOf<TrackDescriptor>()
            val restarted = GenreEnrichment(settings) { reads += it; facts }
            assertTrue(restarted.backfill(listOf(track, other)))
            assertEquals(1, reads.size)
            assertEquals(damagedId, reads.single().id.value.hex())
            assertEquals(listOf(enriched, enriched.copy(id = other.id)), restarted.apply(listOf(track, other)))
        }
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
