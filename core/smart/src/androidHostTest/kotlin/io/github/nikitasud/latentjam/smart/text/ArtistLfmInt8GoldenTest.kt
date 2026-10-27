/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLAN_SIZE: the shipped LightFM table, whatever its element width, keeps every vector's direction against the float32
 * original (tools/research/ranker/export_size_golden.py); only artists below a listener floor may be gone.
 */
class ArtistLfmInt8GoldenTest {
    private val rows by lazy {
        GZIPInputStream(javaClass.getResourceAsStream("/artist_lfm_int8_golden.tsv.gz")!!).bufferedReader().readLines().map { it.split('\t') }
    }

    @Test
    fun `lightfm vectors keep their direction`() {
        val table = assertNotNull(ArtistVectors.parse(File("../../androidApp/src/main/assets/ml/artist_lfm.bin").readBytes()))
        val withVector = rows.filter { it[0] == "v" && it[3].isNotEmpty() }
        val missing = withVector.filter { !table.has(it[1].toInt()) }
        // Either every vector is there, or exactly the ones below the 50-listener floor are gone.
        assertTrue(missing.isEmpty() || missing.toSet() == withVector.filter { it[2] == "0" }.toSet(), "${missing.size} vectors missing")
        var worst = 1.0
        for (r in withVector) {
            val got = table.vector(r[1].toInt()) ?: continue
            val want = r[3].split(' ').map(String::toDouble)
            var dot = 0.0
            var a = 0.0
            var b = 0.0
            for (i in want.indices) { dot += got[i] * want[i]; a += got[i].toDouble() * got[i]; b += want[i] * want[i] }
            worst = minOf(worst, dot / sqrt(a * b))
        }
        assertTrue(worst >= 0.9999, "worst cosine $worst")
        for (r in rows.filter { it[0] == "v" && it[3].isEmpty() }) assertTrue(!table.has(r[1].toInt()))
    }
}
