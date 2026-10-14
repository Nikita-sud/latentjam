/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

/** One native stream, owned by the audio adapter. Float buffers include its fixed delay. */
internal expect class NativeEqualizer(sampleRate: Int, channels: Int, initial: SoftwareEqualizerSettings) {
    val latencyFrames: Int
    fun update(settings: SoftwareEqualizerSettings)
    fun process(interleaved: FloatArray, frames: Int)
    fun takeMeterGain(): Float
    fun close()
}
internal data class NativeEqualizerResponse(val headroomDb: Float, val gainsDb: FloatArray)
internal expect fun nativeEqualizerResponse(sampleRate: Int, settings: SoftwareEqualizerSettings, frequencies: FloatArray): NativeEqualizerResponse
