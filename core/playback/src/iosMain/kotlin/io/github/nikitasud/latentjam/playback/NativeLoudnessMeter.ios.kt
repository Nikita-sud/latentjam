/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.*
import io.github.nikitasud.latentjam.playback.nativeeq.*

@OptIn(ExperimentalForeignApi::class)
internal actual class NativeLoudnessMeter actual constructor(sampleRate: Int, private val channels: Int) {
    private var handle = lj_loudness_create(sampleRate, channels)
    actual fun add(samples: FloatArray, frames: Int): Boolean {
        require(frames in 1..LoudnessScanLimits.CHUNK_FRAMES && samples.size >= frames * channels)
        return samples.usePinned { lj_loudness_add(handle, it.addressOf(0), frames) != 0 }
    }
    actual fun finish(): IntegratedLoudness? = memScoped {
        val lufs = alloc<DoubleVar>(); val peak = alloc<DoubleVar>()
        if (lj_loudness_finish(handle, lufs.ptr, peak.ptr) == 0) null else
            IntegratedLoudness(lufs.value.toFloat(), peak.value.toFloat()).takeIf { it.isValid() }
    }
    actual fun close() { lj_loudness_destroy(handle); handle = null }
}
