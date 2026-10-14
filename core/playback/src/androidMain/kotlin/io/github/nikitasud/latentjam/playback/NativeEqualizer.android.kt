/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import java.nio.ByteBuffer

internal actual class NativeEqualizer actual constructor(sampleRate: Int, private val channels: Int, initial: SoftwareEqualizerSettings) {
    private var handle = NativeEqualizerJni.create(sampleRate, channels)
    private var settings = initial.sanitized()
    init { NativeEqualizerJni.update(handle, settings.enabled, settings.levels.toIntArray(), settings.bass); NativeEqualizerJni.reset(handle) }
    actual val latencyFrames: Int get() = NativeEqualizerJni.latency(requireHandle())
    actual fun update(settings: SoftwareEqualizerSettings) {
        if (settings == this.settings) return
        val value = settings.sanitized()
        NativeEqualizerJni.update(requireHandle(), value.enabled, value.levels.toIntArray(), value.bass)
        this.settings = value
    }
    actual fun process(interleaved: FloatArray, frames: Int) {
        NativeEqualizerJni.processFloat(requireHandle(), interleaved, frames, channels)
    }
    fun processPcm16(input: ByteBuffer, output: ByteBuffer): Int {
        require(input.remaining() % (channels * 2) == 0)
        val bytes = input.remaining()
        val frames = NativeEqualizerJni.processPcm16(requireHandle(), input, input.position(), output, output.position(), bytes / (channels * 2), channels)
        input.position(input.position() + bytes)
        output.position(output.position() + frames * channels * 2)
        return frames
    }
    val pendingFrames: Int get() = NativeEqualizerJni.pending(requireHandle())
    fun drainPcm16(output: ByteBuffer): Int {
        val frames = NativeEqualizerJni.drain(requireHandle(), output, output.position(), output.remaining() / (channels * 2), channels)
        output.position(output.position() + frames * channels * 2)
        return frames
    }
    actual fun takeMeterGain(): Float = NativeEqualizerJni.takeGain(requireHandle())
    actual fun close() { if (handle != 0L) { NativeEqualizerJni.destroy(handle); handle = 0L } }
    private fun requireHandle(): Long = handle.also { check(it != 0L) { "EQ stream is closed" } }
}

internal actual fun nativeEqualizerResponse(sampleRate: Int, settings: SoftwareEqualizerSettings, frequencies: FloatArray): NativeEqualizerResponse {
    val gains = FloatArray(frequencies.size)
    val headroom = NativeEqualizerJni.response(sampleRate, settings.enabled, settings.levels.toIntArray(), settings.bass, frequencies, gains)
    return NativeEqualizerResponse(headroom, gains)
}

/** Native method names are preserved by audio-dsp's consumer ProGuard rules. */
internal object NativeEqualizerJni {
    init {
        val host = System.getProperty("latentjam.eq.hostLibrary")
        if (host != null) System.load(host) else System.loadLibrary("lj_equalizer")
    }
    external fun create(rate: Int, channels: Int): Long
    external fun destroy(handle: Long)
    external fun update(handle: Long, enabled: Boolean, levels: IntArray, bass: Int)
    external fun reset(handle: Long)
    external fun latency(handle: Long): Int
    external fun processFloat(handle: Long, samples: FloatArray, frames: Int, channels: Int)
    external fun processPcm16(handle: Long, input: ByteBuffer, inputOffset: Int, output: ByteBuffer, outputOffset: Int, frames: Int, channels: Int): Int
    external fun pending(handle: Long): Int
    external fun drain(handle: Long, output: ByteBuffer, offset: Int, capacity: Int, channels: Int): Int
    external fun takeGain(handle: Long): Float
    external fun response(rate: Int, enabled: Boolean, levels: IntArray, bass: Int, frequencies: FloatArray, output: FloatArray): Float
}
