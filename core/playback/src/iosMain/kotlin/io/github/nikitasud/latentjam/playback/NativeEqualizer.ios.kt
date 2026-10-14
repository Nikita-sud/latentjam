/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.playback.nativeeq.*
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

internal actual class NativeEqualizer actual constructor(sampleRate: Int, private val channels: Int, initial: SoftwareEqualizerSettings) {
    private var handle = checkNotNull(lj_eq_create(sampleRate, channels)) { "Unsupported EQ format or allocation failure" }
    private var closed = false
    private var settings = initial.sanitized()
    init { apply(settings); lj_eq_reset(handle) }
    actual val latencyFrames: Int get() { check(!closed); return lj_eq_latency_frames(handle) }
    actual fun update(settings: SoftwareEqualizerSettings) {
        if (settings == this.settings) return
        val value = settings.sanitized(); apply(value); this.settings = value
    }
    private fun apply(value: SoftwareEqualizerSettings) {
        check(!closed)
        value.levels.toIntArray().usePinned { lj_eq_set_settings(handle, if (value.enabled) 1 else 0, it.addressOf(0), value.bass) }
    }
    actual fun process(interleaved: FloatArray, frames: Int) {
        check(!closed); require(frames >= 0 && frames.toLong() * channels <= interleaved.size)
        if (frames > 0) interleaved.usePinned { lj_eq_process_f32(handle, it.addressOf(0), frames) }
    }
    actual fun takeMeterGain(): Float { check(!closed); return lj_eq_take_meter_gain(handle) }
    actual fun close() { if (!closed) { lj_eq_destroy(handle); closed = true } }
}
internal actual fun nativeEqualizerResponse(sampleRate: Int, settings: SoftwareEqualizerSettings, frequencies: FloatArray): NativeEqualizerResponse {
    val output = FloatArray(frequencies.size)
    if (frequencies.isEmpty()) return NativeEqualizerResponse(0f, output)
    val headroom = settings.levels.toIntArray().usePinned { levels -> frequencies.usePinned { points -> output.usePinned { gains ->
        lj_eq_frequency_response(sampleRate, if (settings.enabled) 1 else 0, levels.addressOf(0), settings.bass,
            points.addressOf(0), gains.addressOf(0), frequencies.size)
    } } }
    return NativeEqualizerResponse(headroom, output)
}
