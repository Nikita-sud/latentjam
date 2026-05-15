/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.audio

/**
 * Lightweight pure-Kotlin linear resampler.
 *
 * Linear interpolation is good-enough for embedding pipelines: the encoder is robust to
 * minor resampling artifacts (the SSL training pipeline uses resampy's higher-quality
 * sinc filter, but the recall@k delta on FMA-medium between linear and Kaiser-best is
 * within noise — verified offline). Avoids pulling in libsamplerate JNI which would add
 * a native dep just for one short hot path.
 */
internal object Resampler {

    fun linearResample(input: ShortArray, fromHz: Int, toHz: Int): ShortArray {
        if (fromHz == toHz || input.isEmpty()) return input
        val ratio = fromHz.toDouble() / toHz.toDouble()
        val outSize = (input.size / ratio).toInt()
        val out = ShortArray(outSize)
        var i = 0
        while (i < outSize) {
            val srcPos = i * ratio
            val s0 = srcPos.toInt()
            val s1 = (s0 + 1).coerceAtMost(input.size - 1)
            val t = srcPos - s0
            val v = input[s0] * (1.0 - t) + input[s1] * t
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i++
        }
        return out
    }
}
