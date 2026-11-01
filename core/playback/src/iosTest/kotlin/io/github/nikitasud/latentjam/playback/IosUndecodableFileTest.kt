/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.playback.nativeeq.lj_eq_ios_file_decodes
import io.github.nikitasud.latentjam.playback.nativeeq.lj_eq_ios_read_at
import io.github.nikitasud.latentjam.playback.nativeeq.lj_eq_ios_read_next
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file AVAudioFile opens but cannot decode: 32-bit FLAC in MP4 raises an Objective-C exception from framePosition
 * and every read, which ends the app where it reaches Kotlin. Nothing here starts playback, and the fixture is 0.1 s
 * of digital silence. Should a later iOS decode such files, the "cannot decode" expectations here fail and can go.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
class IosUndecodableFileTest {

    private fun withFile(bytes: ByteArray, extension: String, block: (NSURL) -> Unit) {
        val path = NSTemporaryDirectory() + "latentjam-undecodable-${NSUUID().UUIDString}.$extension"
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, data, null))
        try { block(NSURL.fileURLWithPath(path)) } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun aFileThatOpensButCannotDecodeIsUnplayableNotACrash() = withFile(FLAC32_IN_MP4, "m4a") { url ->
        val file = assertNotNull(AVAudioFile(forReading = url, error = null), "AVAudioFile opens it")
        assertEquals(0, lj_eq_ios_file_decodes(file))
        val buffer = assertNotNull(AVAudioPCMBuffer(pCMFormat = file.processingFormat, frameCapacity = 512u))
        assertEquals(0, lj_eq_ios_read_at(file, buffer, 0L, 512u))
        assertEquals(-1, lj_eq_ios_read_next(file, buffer))
        assertNull(runBlocking { IosIntegratedLoudnessMeter().measure(url.absoluteString!!, 100L) })
        val engine = IosAudioEngine()
        try {
            assertFalse(engine.load(url, autoPlay = false) {})
            assertFalse(engine.playing)
        } finally { engine.stop() }
    }

    @Test
    fun aPlainWaveStillDecodes() = withFile(silentWave(), "wav") { url ->
        val file = assertNotNull(AVAudioFile(forReading = url, error = null))
        assertEquals(1, lj_eq_ios_file_decodes(file))
        assertEquals(0L, file.framePosition, "the probe rewinds")
        val buffer = assertNotNull(AVAudioPCMBuffer(pCMFormat = file.processingFormat, frameCapacity = 512u))
        assertEquals(1, lj_eq_ios_read_at(file, buffer, 0L, 512u))
        assertEquals(0, lj_eq_ios_read_at(file, buffer, file.length, 512u), "no frames is no read")
        val engine = IosAudioEngine()
        try {
            assertTrue(engine.load(url, autoPlay = false) {})
            assertFalse(engine.playing)
        } finally { engine.stop() }
    }

    private fun silentWave(rate: Int = 8000): ByteArray {
        val bytes = ByteArray(44 + rate * 2)
        fun word(offset: Int, value: Int, count: Int = 4) {
            repeat(count) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
        }
        fun text(offset: Int, value: String) { value.encodeToByteArray().copyInto(bytes, offset) }
        text(0, "RIFF"); word(4, bytes.size - 8); text(8, "WAVE")
        text(12, "fmt "); word(16, 16); word(20, 1, 2); word(22, 1, 2)
        word(24, rate); word(28, rate * 2); word(32, 2, 2); word(34, 16, 2)
        text(36, "data"); word(40, bytes.size - 44)
        return bytes
    }

    private companion object {
        /** `ffmpeg -f lavfi -i anullsrc=r=8000:cl=mono -t 0.1 -c:a flac -sample_fmt s32 -bits_per_raw_sample 32 -strict experimental -f mp4` */
        val FLAC32_IN_MP4: ByteArray = Base64.decode(
            "AAAAHGZ0eXBpc29tAAACAGlzb21pc28ybXA0MQAAAAhmcmVlAAAAI21kYXT/+CQOALQAAAAAABVX//hkDgHfmAAAAAAA7wsAAALc" +
            "bW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAAGQAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAA" +
            "AAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAgd0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAA" +
            "AGQAAAAAAAAAAAAAAAEBAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAkZWR0cwAAABxl" +
            "bHN0AAAAAAAAAAEAAABkAAAAAAABAAAAAAF/bWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAfQAAAAyBVxAAAAAAALWhkbHIAAAAA" +
            "AAAAAHNvdW4AAAAAAAAAAAAAAABTb3VuZEhhbmRsZXIAAAABKm1pbmYAAAAQc21oZAAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAA" +
            "AAAAAAABAAAADHVybCAAAAABAAAA7nN0YmwAAAB6c3RzZAAAAAAAAAABAAAAamZMYUMAAAAAAAAAAQAAAAAAAAAAAAEAIAAAAAAf" +
            "QAAAAAAAMmRmTGEAAAAAgAAAIgJAAkAAAA0AAA4B9AHwAAADIJ8K39vnQMuc2BiKNXYeJHYAAAAUYnRydAAAAAAAAfQAAAAIcAAA" +
            "ACBzdHRzAAAAAAAAAAIAAAABAAACQAAAAAEAAADgAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAACAAAAAQAAABxzdHN6AAAAAAAAAAAA" +
            "AAACAAAADQAAAA4AAAAUc3RjbwAAAAAAAAABAAAALAAAAGF1ZHRhAAAAWW1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBs" +
            "AAAAAAAAAAAAAAAALGlsc3QAAAAkqXRvbwAAABxkYXRhAAAAAQAAAABMYXZmNjIuMy4xMDA=",
        )
    }
}
