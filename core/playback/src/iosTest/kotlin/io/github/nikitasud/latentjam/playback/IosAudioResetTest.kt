/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosAudioResetTest {
    private fun withWave(rate: Int = 44100, block: (NSURL) -> Unit) {
        val bytes = ByteArray(44 + rate * 2)
        fun word(offset: Int, value: Int, count: Int = 4) {
            repeat(count) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
        }
        fun text(offset: Int, value: String) { value.encodeToByteArray().copyInto(bytes, offset) }
        text(0, "RIFF"); word(4, bytes.size - 8); text(8, "WAVE")
        text(12, "fmt "); word(16, 16); word(20, 1, 2); word(22, 1, 2)
        word(24, rate); word(28, rate * 2); word(32, 2, 2); word(34, 16, 2)
        text(36, "data"); word(40, bytes.size - 44)
        val path = NSTemporaryDirectory() + "latentjam-audio-reset-${NSUUID().UUIDString}.wav"
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, data, null))
        try { block(NSURL.fileURLWithPath(path)) } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun anIdleGraphCanBeRebuiltBeforeTheNextLocalFileLoads() = withWave { url ->
        val engine = IosAudioEngine()
        try {
            assertFalse(engine.rebuildAfterMediaServicesReset(null))
            assertTrue(engine.load(url, autoPlay = false) {})
            assertEquals(1000L, engine.durationMs())
            assertFalse(engine.playing)
        } finally { engine.stop() }
    }

    @Test
    fun aResetReopensTheFileAtItsPlayheadWithoutStartingPlayback() = withWave { url ->
        val engine = IosAudioEngine()
        try {
            assertTrue(engine.load(url, autoPlay = false) {})
            assertTrue(engine.rebuildAfterMediaServicesReset(250L))
            assertEquals(250L, engine.positionMs())
            assertFalse(engine.playing)
        } finally { engine.stop() }
    }

    @Test
    fun aFileRemovedBeforeTheResetDoesNotSurviveThroughItsOldHandle() = withWave { url ->
        val engine = IosAudioEngine()
        try {
            assertTrue(engine.load(url, autoPlay = false) {})
            assertTrue(NSFileManager.defaultManager.removeItemAtPath(checkNotNull(url.path), null))
            assertFalse(engine.rebuildAfterMediaServicesReset(250L))
            assertEquals(null, engine.durationMs())
            assertFalse(engine.play())
        } finally { engine.stop() }
    }

    @Test
    fun anInactiveBackendDoesNotRecueItsPreviousFile() = withWave { url ->
        val engine = IosAudioEngine()
        try {
            assertTrue(engine.load(url, autoPlay = false) {})
            assertFalse(engine.rebuildAfterMediaServicesReset(null))
            assertEquals(null, engine.durationMs())
            assertFalse(engine.play())
        } finally { engine.stop() }
    }
    @Test
    fun normalizationComposesWithTransportAndSurvivesGraphRecreation() = withWave { url ->
        val engine = IosAudioEngine()
        try {
            engine.setDeckGains(0.5f)
            engine.setTransportGain(0.4f)
            assertEquals(0.2f, engine.effectiveOutputVolume, 0.00001f)
            assertTrue(engine.load(url, autoPlay = false) {})
            assertTrue(engine.rebuildAfterMediaServicesReset(250L))
            assertEquals(0.2f, engine.effectiveOutputVolume, 0.00001f)
            engine.setTransportGain(1f)
            assertEquals(0.5f, engine.effectiveOutputVolume, 0.00001f)
            engine.setDeckGains(1f)
            assertEquals(1f, engine.effectiveOutputVolume, 0.00001f)
        } finally { engine.stop() }
    }

    @Test
    fun aSecondTrackJoinsUnderTheFirstAndCarriesOnAsTheCurrentOne() = withWave { first ->
        withWave(rate = 48000) { second ->
            val engine = IosAudioEngine()
            try {
                // The simulator's test host cannot start an engine; this part runs on a device.
                if (engine.load(first, autoPlay = true) {} && engine.playing) {
                    assertEquals(1000L, engine.prepareOverlap(second))
                    assertFalse(engine.overlapping)
                    assertTrue(engine.startOverlap())
                    assertTrue(engine.overlapping)
                    engine.setDeckGains(1f, 0.5f)
                    assertTrue(engine.promoteOverlap {})
                    assertFalse(engine.overlapping)
                    assertTrue(engine.playing)
                    assertEquals(1000L, engine.durationMs())
                    // The promoted deck keeps the gain it was sounding at.
                    assertEquals(0.5f, engine.effectiveOutputVolume, 0.00001f)
                    assertFalse(engine.promoteOverlap {})
                }
            } finally { engine.stop() }
        }
    }

    @Test
    fun aCancelledOverlapLeavesTheCurrentTrackAlone() = withWave { first ->
        withWave { second ->
            val engine = IosAudioEngine()
            try {
                if (engine.load(first, autoPlay = true) {} && engine.playing) {
                    assertEquals(1000L, engine.prepareOverlap(second))
                    assertTrue(engine.startOverlap())
                    engine.cancelOverlap()
                    assertFalse(engine.overlapping)
                    assertFalse(engine.promoteOverlap {})
                    assertEquals(1000L, engine.durationMs())
                }
                // Nothing is playing: no overlap can be cued.
                engine.stop()
                assertEquals(null, engine.prepareOverlap(second))
            } finally { engine.stop() }
        }
    }

    private fun withSavedTonePreferences(block: (NSUserDefaults) -> Unit) {
        val preferences = NSUserDefaults.standardUserDefaults
        val keys = List(10) { "ios_equalizer_band_$it" } + listOf("ios_equalizer_enabled", "ios_equalizer_preset", "ios_equalizer_software_bass", "ios_equalizer_software_version")
        val saved = keys.associateWith { preferences.objectForKey(it) }
        try { block(preferences) } finally {
            saved.forEach { (key, value) -> if (value == null) preferences.removeObjectForKey(key) else preferences.setObject(value, key) }
        }
    }

    @Test fun bassAndPresetsUseTheSameIndependentSettingsAsAndroid() = withSavedTonePreferences {
        val engine = IosAudioEngine()
        try {
            runBlocking {
                engine.applyPreset(3)
                val bands = engine.state.value.bands
                engine.setBassBoost(730)
                assertEquals(bands, engine.state.value.bands)
                assertEquals(730, engine.state.value.bassBoostStrength)
                assertTrue(engine.state.value.supportsFrequencyResponse)
                engine.applyPreset(2)
                assertEquals(0, engine.state.value.bassBoostStrength)
                assertEquals(SoftwareEqualizerSettings.PRESETS[2], engine.state.value.bands.map { it.levelMillibels })
            }
        } finally { engine.stop() }
    }

    @Test fun migrationPreservesTheOldNativeBassCurveWithoutAddingTheShelfTwice() = withSavedTonePreferences { preferences ->
        val old = listOf(1200, 984, 660, 300, 0, 0, 0, 0, 0, 0)
        preferences.setInteger(0, "ios_equalizer_software_version")
        preferences.setBool(true, "ios_equalizer_enabled")
        preferences.setInteger(-1, "ios_equalizer_preset")
        old.forEachIndexed { index, value -> preferences.setInteger(value.toLong(), "ios_equalizer_band_$index") }
        val engine = IosAudioEngine()
        try {
            assertEquals(old, engine.state.value.bands.map { it.levelMillibels })
            assertEquals(0, engine.state.value.bassBoostStrength)
            runBlocking { engine.setBassBoost(400) }
            assertEquals(old, engine.state.value.bands.map { it.levelMillibels })
            val reopened = IosAudioEngine()
            try {
                assertEquals(400, reopened.state.value.bassBoostStrength)
                assertEquals(old, reopened.state.value.bands.map { it.levelMillibels })
            } finally { reopened.stop() }
        } finally { engine.stop() }
    }

}
