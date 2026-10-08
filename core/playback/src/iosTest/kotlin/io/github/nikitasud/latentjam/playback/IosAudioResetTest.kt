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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosAudioResetTest {
    private fun withWave(block: (NSURL) -> Unit) {
        val bytes = ByteArray(44 + 44100 * 2)
        fun word(offset: Int, value: Int, count: Int = 4) {
            repeat(count) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
        }
        fun text(offset: Int, value: String) { value.encodeToByteArray().copyInto(bytes, offset) }
        text(0, "RIFF"); word(4, bytes.size - 8); text(8, "WAVE")
        text(12, "fmt "); word(16, 16); word(20, 1, 2); word(22, 1, 2)
        word(24, 44100); word(28, 88200); word(32, 2, 2); word(34, 16, 2)
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
}
