/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parity with tools/research/listenbrainz_eval/proto_ranker.py (CORE, a5747d63) on 8 FIT queries
 * (tools/research/ranker/export_golden.py): every candidate's 13 CORE and 9 PERSONAL (FULL) features from the same
 * primitives,
 * and the top 10 by the v1 CORE weights (ranker_core_v1.json, the first test build's asset), ties by the track key as
 * proto_ranker.ranked().
 */
class RankerGoldenTest {
    private class Prim(val key: ULong, val row: RankerRow)

    private fun prim(f: List<String>): Prim {
        fun opt(v: String) = v.toInt().takeIf { it >= 0 }
        return Prim(
            key = f[0].toULong(),
            row = RankerRow(
                cosine = if (f[1] == "nan") Double.NaN else f[1].toDouble(),
                family = opt(f[2])?.toString(),
                language = f[3].toInt().takeIf { it > 0 }?.toString(),
                decade = f[4].toInt().takeIf { it > 0 },
                artistKey = opt(f[5])?.toString().orEmpty(),
                colisten = f[6].toDouble(),
                logPopularity = f[7].toDouble(),
            ),
        )
    }

    @Test
    fun featuresAndTopTenMatchTheReference() {
        val model = assertNotNull(RankerModel.parse(checkNotNull(javaClass.getResourceAsStream("/ranker_core_v1.json")).reader().readText()))
        val lines = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/ranker_golden.tsv.gz")))
            .bufferedReader().readLines().filter(String::isNotEmpty)
        var at = 0
        var queries = 0
        var colistenRows = 0
        var maxError = 0.0
        while (at < lines.size) {
            val head = lines[at++].split('\t')
            assertEquals("case", head[0])
            val n = head[2].toInt()
            val seed = prim(head.drop(3))
            val rows = (0 until n).map { lines[at++].split('\t') }
            val cands = rows.map { prim(it.drop(1)) }
            val expected = rows.map { r -> r[9].split(',').map(String::toDouble) }
            val personal = rows.map { r ->
                val f = r[10].split(',')
                PersonalRow(
                    heard = f[0] == "1", count = f[1].toInt(), ageSeconds = if (f[2] == "inf") Double.POSITIVE_INFINITY else f[2].toDouble(),
                    activation = if (f[3] == "nan") Double.NaN else f[3].toDouble(), transTrack = f[4].toInt(), transArtist = f[5].toInt(),
                )
            }
            val expectedPersonal = rows.map { r -> r[11].split(',').map(String::toDouble) }
            val features = RankerFeatures.of(seed.row, cands.map { it.row }, personal)
            val offset = RankerFeatures.NAMES.indexOf("heard")
            for (i in 0 until n) for (j in expectedPersonal[i].indices) {
                val e = abs(features[i][offset + j] - expectedPersonal[i][j])
                maxError = maxOf(maxError, e)
                assertTrue(e <= 1e-5 * maxOf(1.0, abs(expectedPersonal[i][j])), "${head[1]} row $i ${RankerFeatures.PERSONAL[j]}")
            }
            for (i in 0 until n) for (j in expected[i].indices) {
                val e = abs(features[i][j] - expected[i][j])
                maxError = maxOf(maxError, e)
                assertTrue(e <= 1e-5 * maxOf(1.0, abs(expected[i][j])), "${head[1]} row $i ${RankerFeatures.NAMES[j]}")
            }
            colistenRows += cands.count { it.row.colisten > 0 }
            val scores = features.map(model::score)
            val top = cands.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { cands[it].key }).take(10)
            val want = lines[at++].removePrefix("top\t").split(',').map(String::toULong)
            assertEquals(want, top.map { cands[it].key }, head[1])
            queries++
        }
        assertEquals(8, queries)
        assertTrue(colistenRows > 0, "the golden cases exercise co-listening")
        println("ranker golden: $queries queries, max feature error $maxError, co-listening rows $colistenRows")
    }
}
