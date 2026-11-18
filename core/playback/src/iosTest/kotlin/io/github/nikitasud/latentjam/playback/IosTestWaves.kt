/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes

/**
 * 16-bit PCM WAV files made for a test, every frame holding an integer the test chooses (the same in each channel):
 * rendered and multiplied by 32768 again, a sample says which frame of which file it is.
 */
internal object IosTestWaves {
    fun mono(rate: Int, frames: Int, value: (Int) -> Int): NSURL = wave(rate, 1, frames, value)

    fun stereo(rate: Int, frames: Int, value: (Int) -> Int): NSURL = wave(rate, 2, frames, value)

    private fun wave(rate: Int, channels: Int, frames: Int, value: (Int) -> Int): NSURL {
        val bytes = ByteArray(44 + frames * 2 * channels)
        fun word(offset: Int, v: Int, count: Int = 4) { repeat(count) { bytes[offset + it] = (v ushr (8 * it)).toByte() } }
        fun text(offset: Int, v: String) { v.encodeToByteArray().copyInto(bytes, offset) }
        text(0, "RIFF"); word(4, bytes.size - 8); text(8, "WAVE")
        text(12, "fmt "); word(16, 16); word(20, 1, 2); word(22, channels, 2)
        word(24, rate); word(28, rate * 2 * channels); word(32, 2 * channels, 2); word(34, 16, 2)
        text(36, "data"); word(40, frames * 2 * channels)
        for (k in 0 until frames) for (c in 0 until channels) word(44 + (k * channels + c) * 2, value(k) and 0xFFFF, 2)
        val path = NSTemporaryDirectory() + "lj-test-wave-${NSUUID().UUIDString}.wav"
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        check(NSFileManager.defaultManager.createFileAtPath(path, data, null))
        return NSURL.fileURLWithPath(path)
    }
}
