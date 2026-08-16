/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.Loudness
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

internal class LoudnessNormalizationTest {

    @Test
    fun loudnessMapRoundTripsThroughItsPayloadIncludingHostileIds() {
        val original = mapOf(
            "42" to StoredTrackLoudness(-8.5f, "media-v1|42"),
            "path|with|pipes\nand lines 🎧" to StoredTrackLoudness(
                -17.25f,
                "identity|with|pipes\nand lines",
            ),
        )
        val decoded = decodeTrackLoudness(encodeTrackLoudness(original))
        assertEquals(original.keys, decoded.keys)
        original.forEach { (id, measurement) ->
            assertTrue(
                abs(decoded.getValue(id).db - measurement.db) < 0.011f,
                "$id: ${decoded[id]}",
            )
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
            "nonsense\nzz|$identity|-850\n$good|-850\n$good|$identity|-850\n" +
                "$good|$identity|notanumber\n$good|$identity|100\n",
        )
        assertEquals(mapOf("42" to StoredTrackLoudness(-8.5f, "source")), decoded)
    }

    @Test
    fun volumesAreEmptyWhenTheFeatureIsOff() {
        val loudness = mapOf(
            "a" to StoredTrackLoudness(-8f, "a-v1"),
            "b" to StoredTrackLoudness(-20f, "b-v1"),
        )
        assertEquals(emptyMap(), normalizationVolumes(loudness, enabled = false))
        val enabled = normalizationVolumes(loudness, enabled = true)
        assertEquals(Loudness.normalizationVolume(-8f), enabled.getValue("a"))
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
            override suspend fun measureDb(track: TrackDescriptor): Float {
                measuredIds += track.id.value
                return -8f
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
        override suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int) = Unit
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
        ) = Unit
        override suspend fun cycleRepeatMode(): RepeatMode = RepeatMode.OFF
        override suspend fun retainQueue(trackIds: Set<TrackId>) = Unit
        override suspend fun playNext(track: TrackDescriptor) = Unit
        override suspend fun addToQueue(track: TrackDescriptor) = Unit
        override suspend fun moveQueueItem(from: Int, to: Int) = Unit
        override suspend fun removeQueueItem(index: Int) = Unit
    }
}
