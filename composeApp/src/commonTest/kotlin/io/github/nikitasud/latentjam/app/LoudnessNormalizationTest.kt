/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.playback.IntegratedLoudness
import kotlin.test.assertFalse
import kotlinx.coroutines.awaitCancellation
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

internal class LoudnessNormalizationTest {

    @Test
    fun loudnessMapRoundTripsThroughItsPayloadIncludingHostileIds() {
        val original = mapOf(
            "42" to StoredTrackLoudness(IntegratedLoudness(-8.5f, -2f), "media-v1|42"),
            "path|with|pipes\nand lines 🎧" to StoredTrackLoudness(
                IntegratedLoudness(-17.25f, -3.75f),
                "identity|with|pipes\nand lines",
            ),
        )
        val decoded = decodeTrackLoudness(encodeTrackLoudness(original))
        assertEquals(original.keys, decoded.keys)
        original.forEach { (id, measurement) ->
            assertTrue(
                abs(decoded.getValue(id).measurement.lufs - measurement.measurement.lufs) < 0.011f,
                "$id: ${decoded[id]}",
            )
            assertEquals(measurement.measurement.truePeakDbtp, decoded.getValue(id).measurement.truePeakDbtp)
            assertEquals(measurement.audioIdentity, decoded.getValue(id).audioIdentity)
        }
    }

    @Test
    fun corruptPayloadLinesDropIndividually() {
        val good = "42".encodeToByteArray().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val identity = "source".encodeToByteArray().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val decoded = decodeTrackLoudness(
            "nonsense\nr128-v1|zz|$identity|-850|-200\n$good|-850\n$good|$identity|-850\n" +
                "r128-v1|$good|$identity|-850|-200\nr128-v1|$good|$identity|notanumber|-200\n" +
                "r128-v1|$good|$identity|10000|-200\nr128-v1|$good|$identity|-850|10000\n",
        )
        assertEquals(mapOf("42" to StoredTrackLoudness(IntegratedLoudness(-8.5f, -2f), "source")), decoded)
    }

    @Test
    fun volumesAreEmptyWhenTheFeatureIsOff() {
        val loudness = mapOf(
            "a" to StoredTrackLoudness(IntegratedLoudness(-8f, -2f), "a-v1"),
            "b" to StoredTrackLoudness(IntegratedLoudness(-20f, -2f), "b-v1"),
        )
        assertEquals(emptyMap(), normalizationVolumes(loudness, enabled = false))
        val enabled = normalizationVolumes(loudness, enabled = true)
        assertEquals(IntegratedLoudness(-8f, -2f).normalizationVolume(), enabled.getValue("a"))
        assertEquals(1f, enabled.getValue("b"))
    }

    @Test
    fun audioIdentityChangesWhenUnderlyingBytesChangeAtTheSameTrackId() {
        val original = TrackDescriptor(
            id = TrackId("42"),
            audioUri = "content://media/42",
            durationMs = 180_000,
            sourceRevision = "size:1|generation:7",
        )

        assertTrue(
            original.loudnessAudioIdentity() !=
                original.copy(sourceRevision = "size:2|generation:8").loudnessAudioIdentity(),
        )
        assertEquals(
            original.loudnessAudioIdentity(),
            original.copy(title = "Retagged").loudnessAudioIdentity(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistenceFailureDoesNotStopLaterTracksBeingMeasured() = runTest {
        val settings = ThrowingSettings()
        val playback = FakePlayback()
        val measuredIds = mutableListOf<String>()
        val meter = object : TrackLoudnessMeter {
            override suspend fun measure(track: TrackDescriptor): IntegratedLoudness {
                measuredIds += track.id.value
                return IntegratedLoudness(-8f, -2f)
            }
        }
        val normalizer = LoudnessNormalizer(settings, playback, meter)
        normalizer.start(backgroundScope)
        runCurrent()

        playback.nowPlaying.value = NowPlaying(track = audioTrack("first", "revision-1"))
        runCurrent()
        playback.nowPlaying.value = NowPlaying(track = audioTrack("second", "revision-1"))
        runCurrent()

        assertEquals(listOf("first", "second"), measuredIds)
        assertEquals(2, settings.writeAttempts)
        assertEquals(setOf("first", "second"), playback.volumeUpdates.last().keys)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun statusDistinguishesMeasuringQuietLoudAndFailedTracks() = runTest {
        val settings = ThrowingSettings()
        val playback = FakePlayback()
        val pending = CompletableDeferred<IntegratedLoudness?>()
        val statuses = mutableListOf<NormalizationStatus>()
        val meter = object : TrackLoudnessMeter {
            override suspend fun measure(track: TrackDescriptor): IntegratedLoudness? = when (track.id.value) {
                "loud" -> pending.await()
                "quiet" -> IntegratedLoudness(-22f, -2f)
                else -> null
            }
        }
        LoudnessNormalizer(settings, playback, meter, statuses::add).start(backgroundScope)
        runCurrent()
        assertEquals(NormalizationPhase.WAITING, statuses.last().phase)
        playback.nowPlaying.value = NowPlaying(track = audioTrack("loud", "one"))
        runCurrent()
        assertEquals(NormalizationPhase.MEASURING, statuses.last().phase)
        pending.complete(IntegratedLoudness(-8f, -2f))
        runCurrent()
        assertEquals(NormalizationPhase.READY, statuses.last().phase)
        assertTrue(abs(statuses.last().reductionDb - 6f) < 0.01f)
        playback.nowPlaying.value = NowPlaying(track = audioTrack("quiet", "one"))
        runCurrent()
        assertEquals(NormalizationStatus(NormalizationPhase.READY), statuses.last())
        playback.nowPlaying.value = NowPlaying(track = audioTrack("broken", "one"))
        runCurrent()
        assertEquals(NormalizationPhase.UNAVAILABLE, statuses.last().phase)
        settings.normalizeVolume.value = false
        runCurrent()
        assertEquals(NormalizationPhase.OFF, statuses.last().phase)
        assertEquals(emptyMap(), playback.volumeUpdates.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchingOffDuringMeasurementCannotPublishAStaleReadyStatus() = runTest {
        val settings = ThrowingSettings()
        val playback = FakePlayback()
        val pending = CompletableDeferred<IntegratedLoudness?>()
        val statuses = mutableListOf<NormalizationStatus>()
        val meter = object : TrackLoudnessMeter {
            override suspend fun measure(track: TrackDescriptor): IntegratedLoudness? = pending.await()
        }
        LoudnessNormalizer(settings, playback, meter, statuses::add).start(backgroundScope)
        playback.nowPlaying.value = NowPlaying(track = audioTrack("loud", "one"))
        runCurrent()
        settings.normalizeVolume.value = false
        runCurrent()
        pending.complete(IntegratedLoudness(-8f, -2f))
        runCurrent()
        assertEquals(NormalizationPhase.OFF, statuses.last().phase)
        assertEquals(emptyMap(), playback.volumeUpdates.last())
    }

    @Test
    fun sampledRmsCacheIsNeverReinterpretedAsLufs() {
        assertEquals(emptyMap(), decodeTrackLoudness("3432|736f75726365|-850\n3432|-850"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changingTracksCancelsMeasurementAndDoesNotStoreItsPartialResult() = runTest {
        val settings = ThrowingSettings()
        val playback = FakePlayback()
        var cancelled = false
        val meter = object : TrackLoudnessMeter {
            override suspend fun measure(track: TrackDescriptor): IntegratedLoudness? {
                if (track.id.value == "first") {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                return IntegratedLoudness(-8f, -2f)
            }
        }
        LoudnessNormalizer(settings, playback, meter).start(backgroundScope)
        playback.nowPlaying.value = NowPlaying(track = audioTrack("first", "one"))
        runCurrent()
        playback.nowPlaying.value = NowPlaying(track = audioTrack("second", "one"))
        runCurrent()
        assertTrue(cancelled)
        assertFalse(playback.volumeUpdates.any { "first" in it })
        assertEquals(setOf("second"), playback.volumeUpdates.last().keys)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theNextQueuedTrackIsMeasuredBeforeItPlays() = runTest {
        val settings = ThrowingSettings()
        val playback = FakePlayback()
        val measuredIds = mutableListOf<String>()
        val statuses = mutableListOf<NormalizationStatus>()
        val meter = object : TrackLoudnessMeter {
            override suspend fun measure(track: TrackDescriptor): IntegratedLoudness {
                measuredIds += track.id.value
                return IntegratedLoudness(if (track.id.value == "second") -8f else -20f, -2f)
            }
        }
        LoudnessNormalizer(settings, playback, meter, statuses::add).start(backgroundScope)
        val first = audioTrack("first", "one")
        val second = audioTrack("second", "one")
        playback.nowPlaying.value = NowPlaying(track = first, queue = listOf(first, second), queueIndex = 0)
        runCurrent()
        assertEquals(listOf("first", "second"), measuredIds)
        assertTrue("second" in playback.volumeUpdates.last(), "the next track's gain is ready before it starts")
        assertEquals(NormalizationStatus(NormalizationPhase.READY), statuses.last(), "the lookahead does not overwrite the playing track's status")

        playback.nowPlaying.value = NowPlaying(track = second, queue = listOf(first, second), queueIndex = 1)
        runCurrent()
        assertEquals(listOf("first", "second"), measuredIds, "no second scan when it starts")
        assertTrue(abs(statuses.last().reductionDb - 6f) < 0.01f)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aLateNextRowDoesNotRestartThePlayingTracksScan() = runTest {
        val settings = ThrowingSettings()
        val playback = FakePlayback()
        val pending = CompletableDeferred<IntegratedLoudness?>()
        var firstScans = 0
        val measuredIds = mutableListOf<String>()
        val meter = object : TrackLoudnessMeter {
            override suspend fun measure(track: TrackDescriptor): IntegratedLoudness? {
                measuredIds += track.id.value
                if (track.id.value == "first") { firstScans++; return pending.await() }
                return IntegratedLoudness(-8f, -2f)
            }
        }
        LoudnessNormalizer(settings, playback, meter).start(backgroundScope)
        val first = audioTrack("first", "one")
        val appended = audioTrack("appended", "one")
        playback.nowPlaying.value = NowPlaying(track = first, queue = listOf(first), queueIndex = 0)
        runCurrent()
        playback.nowPlaying.value = NowPlaying(track = first, queue = listOf(first, appended), queueIndex = 0)
        runCurrent()
        assertEquals(1, firstScans)
        pending.complete(IntegratedLoudness(-8f, -2f))
        runCurrent()
        assertEquals(listOf("first", "appended"), measuredIds)
    }

    private fun audioTrack(id: String, revision: String): TrackDescriptor = TrackDescriptor(
        id = TrackId(id),
        audioUri = "content://media/$id",
        durationMs = 120_000,
        sourceRevision = revision,
    )

    private class ThrowingSettings : AppSettings {
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
        var writeAttempts = 0
        override fun writeTrackLoudnessPayload(payload: String) {
            writeAttempts++
            error("disk unavailable")
        }

        private var trackGenresPayload: String? = null
        override fun readTrackGenresPayload(): String? = trackGenresPayload
        override fun writeTrackGenresPayload(payload: String) {
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

    private class FakePlayback : PlaybackController {
        val nowPlaying = MutableStateFlow(NowPlaying())
        override val state = nowPlaying
        val volumeUpdates = mutableListOf<Map<String, Float>>()

        override suspend fun setTrackVolumes(volumes: Map<String, Float>) {
            volumeUpdates += volumes
        }

        override suspend fun setSmartLibrary(tracks: List<TrackDescriptor>) = Unit
        override suspend fun setSmartQueueLength(length: Int) = Unit
        override suspend fun invalidateSmartFuture() = Unit
        override suspend fun play(
            tracks: List<TrackDescriptor>,
            startIndex: Int,
            smartPlanPositions: Map<TrackId, Int>,
        ) = Unit
        override suspend fun togglePlayPause() = Unit
        override suspend fun pause() = Unit
        override suspend fun next() = Unit
        override suspend fun previous() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun playAt(queueIndex: Int) = Unit
        override suspend fun cycleShuffleMode(): ShuffleMode = ShuffleMode.OFF
        override suspend fun setShuffleMode(mode: ShuffleMode) = Unit
        override suspend fun restoreQueue(
            tracks: List<TrackDescriptor>,
            startIndex: Int,
            positionMs: Long,
            sourceTracks: List<TrackDescriptor>?,
            smartContinuationIds: Set<TrackId>,
            smartPlanPositions: Map<TrackId, Int>,
        ) = Unit
        override suspend fun cycleRepeatMode(): RepeatMode = RepeatMode.OFF
        override suspend fun retainQueue(trackIds: Set<TrackId>) = Unit
        override suspend fun refreshTracks(tracks: List<TrackDescriptor>) = Unit
        override suspend fun playNext(track: TrackDescriptor) = Unit
        override suspend fun addToQueue(track: TrackDescriptor) = Unit
        override suspend fun moveQueueItem(from: Int, to: Int) = Unit
        override suspend fun removeQueueItem(index: Int) = Unit
    }
}
