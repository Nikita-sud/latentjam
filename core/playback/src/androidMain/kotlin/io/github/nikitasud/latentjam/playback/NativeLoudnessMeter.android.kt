/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

internal actual class NativeLoudnessMeter actual constructor(sampleRate: Int, private val channels: Int) {
    private var handle = NativeLoudnessJni.create(sampleRate, channels)
    actual fun add(samples: FloatArray, frames: Int): Boolean {
        require(frames in 1..LoudnessScanLimits.CHUNK_FRAMES && samples.size >= frames * channels)
        return handle != 0L && NativeLoudnessJni.add(handle, samples, frames, channels)
    }
    actual fun finish(): IntegratedLoudness? = if (handle == 0L) null else
        NativeLoudnessJni.finish(handle)?.let { IntegratedLoudness(it[0].toFloat(), it[1].toFloat()) }
            ?.takeIf { it.isValid() }
    actual fun close() { if (handle != 0L) NativeLoudnessJni.destroy(handle); handle = 0 }
}

internal object NativeLoudnessJni {
    init {
        val host = System.getProperty("latentjam.eq.hostLibrary")
        if (host != null) System.load(host) else System.loadLibrary("lj_equalizer")
    }
    external fun create(rate: Int, channels: Int): Long
    external fun add(handle: Long, samples: FloatArray, frames: Int, channels: Int): Boolean
    external fun finish(handle: Long): DoubleArray?
    external fun destroy(handle: Long)
}
