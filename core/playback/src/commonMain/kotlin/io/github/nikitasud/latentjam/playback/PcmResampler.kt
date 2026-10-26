/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts interleaved PCM16 from [inputRate] to [outputRate] with a Kaiser-windowed sinc filter.
 *
 * Output frame n sits exactly at input position n × inputRate / outputRate (integer arithmetic), so
 * the result does not depend on how the input is cut into buffers: two players feeding the same
 * track in different chunks produce the same frames, which an overlap's join relies on. The first
 * output frame is at input frame 0 (no delay), and after [end] the output has
 * ceil(inputFrames × outputRate / inputRate) frames.
 */
internal class PcmResampler(
    private val inputRate: Int,
    val outputRate: Int,
    private val channels: Int,
) {
    private val step: Long
    private val phases: Long

    /** Half the filter's length, in input frames. */
    private val halfWidth: Int

    /** The kernel from distance 0 to [halfWidth] input frames, [TABLE_STEPS] points per frame. */
    private val kernel: FloatArray
    private val weights: FloatArray

    /** Input history, channel-planar; [history] frame 0 is absolute input frame [base]. */
    private var history = Array(channels) { FloatArray(INITIAL_CAPACITY) }
    private var base: Long
    private var stored: Int
    private var received = 0L
    private var ended = false
    private var nextOutput = 0L

    init {
        require(inputRate > 0 && outputRate > 0 && channels > 0)
        val divisor = gcd(inputRate.toLong(), outputRate.toLong())
        step = inputRate / divisor
        phases = outputRate / divisor
        // Downsampling narrows the passband to the output's Nyquist and widens the kernel to match.
        val scale = minOf(1.0, outputRate.toDouble() / inputRate)
        halfWidth = ceil(ZERO_CROSSINGS / scale).toInt()
        val cutoff = scale * PASSBAND
        kernel = FloatArray(halfWidth * TABLE_STEPS + 2) { index ->
            val distance = index.toDouble() / TABLE_STEPS
            if (distance >= halfWidth) 0f else (cutoff * sinc(cutoff * distance) * kaiser(distance / halfWidth)).toFloat()
        }
        weights = FloatArray(2 * halfWidth)
        // The frames before the track are silence.
        base = -halfWidth.toLong()
        stored = halfWidth
        ensureCapacity(stored)
    }

    /** Appends [frames] interleaved frames from [input] starting at [offset] samples. */
    fun push(input: ShortArray, offset: Int, frames: Int) {
        check(!ended)
        compact()
        ensureCapacity(stored + frames)
        for (frame in 0 until frames) {
            val at = offset + frame * channels
            for (channel in 0 until channels) history[channel][stored + frame] = input[at + channel] / PCM16_SCALE
        }
        stored += frames
        received += frames
    }

    /** No more input: the last frames' outputs become available, the silence after them assumed. */
    fun end() {
        ended = true
    }

    /** Output frames [pull] can give now. */
    fun available(): Int {
        val last = if (ended) {
            // Outputs whose position lies inside the input.
            ceilDiv(received * phases, step) - 1
        } else {
            // Outputs whose whole kernel has arrived.
            floorDiv((received - halfWidth - 1) * phases, step)
        }
        return (last - nextOutput + 1).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Writes up to [maxFrames] interleaved output frames into [output]; returns how many. */
    fun pull(output: ShortArray, maxFrames: Int): Int {
        val frames = minOf(maxFrames, available())
        for (frame in 0 until frames) {
            val position = nextOutput * step
            val index = floorDiv(position, phases)
            val fraction = (position - index * phases).toDouble() / phases
            // Taps index - halfWidth + 1 .. index + halfWidth.
            val first = index - halfWidth + 1
            var sum = 0.0
            for (tap in 0 until 2 * halfWidth) {
                val distance = kotlin.math.abs((index - (first + tap)) + fraction)
                val weight = kernelAt(distance)
                weights[tap] = weight
                sum += weight
            }
            // Unity gain at every phase: a constant stays exactly constant.
            val norm = if (sum > 0.0) (1.0 / sum).toFloat() else 0f
            val offset = (first - base).toInt()
            for (channel in 0 until channels) {
                val samples = history[channel]
                var value = 0f
                for (tap in 0 until 2 * halfWidth) {
                    val at = offset + tap
                    if (at < stored) value += samples[at] * weights[tap]
                }
                val scaled = (value * norm * PCM16_SCALE).roundToInt()
                output[frame * channels + channel] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            nextOutput++
        }
        return frames
    }

    private fun kernelAt(distance: Double): Float {
        val position = distance * TABLE_STEPS
        val index = position.toInt()
        if (index >= kernel.size - 1) return 0f
        val fraction = (position - index).toFloat()
        return kernel[index] + (kernel[index + 1] - kernel[index]) * fraction
    }

    /** Drops history no output still needs. */
    private fun compact() {
        val needed = floorDiv(nextOutput * step, phases) - halfWidth + 1
        val drop = (needed - base).coerceIn(0L, stored.toLong()).toInt()
        if (drop < COMPACT_FRAMES) return
        for (channel in 0 until channels) history[channel].copyInto(history[channel], 0, drop, stored)
        base += drop
        stored -= drop
    }

    private fun ensureCapacity(frames: Int) {
        if (frames <= history[0].size) return
        val size = maxOf(frames, history[0].size * 2)
        history = Array(channels) { history[it].copyOf(size) }
    }

    private companion object {
        const val PCM16_SCALE = 32768f

        /** The kernel's zero crossings each side at full bandwidth. */
        const val ZERO_CROSSINGS = 16

        /** The passband's share of the narrower Nyquist; the rest is the transition band. */
        const val PASSBAND = 0.95

        /** Kaiser β: stopband about −80 dB. */
        const val KAISER_BETA = 8.0
        const val TABLE_STEPS = 512
        const val INITIAL_CAPACITY = 8192
        const val COMPACT_FRAMES = 4096

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        fun kaiser(x: Double): Double {
            val inside = 1.0 - x * x
            if (inside <= 0.0) return 0.0
            return besselI0(KAISER_BETA * sqrt(inside)) / besselI0(KAISER_BETA)
        }

        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                val half = x / (2.0 * k)
                term *= half * half
                sum += term
                k++
            }
            return sum
        }

        fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        fun floorDiv(a: Long, b: Long): Long = a.floorDiv(b)
        fun ceilDiv(a: Long, b: Long): Long = -floorDiv(-a, b)
    }
}
