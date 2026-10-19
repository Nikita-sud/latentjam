/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.text.AssetBytes
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shipped tagger head against its own graph in numpy (tools/research/ranker/export_tagger_golden.py). */
class AudioTaggerTest {
    private val tagger by lazy {
        assertNotNull(
            AudioTagger.parse(
                File("../../androidApp/src/main/assets/ml/audio_tagger.json").readText(),
                AssetBytes.of(File("../../androidApp/src/main/assets/ml/audio_tagger.bin").readBytes()),
            ),
        )
    }

    private fun close(a: Double, b: Double) = abs(a - b) <= maxOf(1e-12, 1e-9 * abs(b))

    @Test
    fun imputationMatchesTheHeadGraph() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/audio_tagger_golden.tsv")).reader().readLines().filter(String::isNotEmpty)
        assertEquals(6, lines.size)
        for (line in lines) {
            val (x, p, d) = line.split('\t').map { f -> f.split(',').map(String::toDouble) }
            val out = assertNotNull(tagger.impute(FloatArray(x.size) { x[it].toFloat() }))
            p.indices.forEach { assertTrue(close(out.probabilities[it], p[it]), "p[$it] ${out.probabilities[it]} vs ${p[it]}") }
            d.indices.forEach { assertTrue(abs(out.descriptor[it] - d[it]) <= 1e-6, "d[$it] ${out.descriptor[it]} vs ${d[it]}") }
        }
    }

    @Test
    fun badInputsImputeNothing() {
        assertNull(tagger.impute(FloatArray(10)))
        assertNull(tagger.impute(FloatArray(tagger.inputDim)))
        assertNull(tagger.impute(FloatArray(tagger.inputDim) { Float.NaN }))
        assertNull(AudioTagger.parse("{}", AssetBytes.of(ByteArray(0))))
    }
}
