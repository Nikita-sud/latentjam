/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parity with proto_ranker2.py (6e2fd6ce) `Query.queue` for CORE+LFM, p = 2.129, on 6 FIT queries
 * (tools/research/ranker/export_golden2.py): the shipped asset's weights and standardisation, the 2 : 1 seed / previous
 * mix, the artist-run penalty and the track-key tie rule give the same 20-row queue from the reference's own feature
 * values. Checked hop by hop with the reference's earlier picks forced, so one tie cannot hide the rest: the app's
 * pick must be the reference's, unless the two rows score the same in the app (identical feature rows, which the
 * reference's BLAS dot product can split by one ulp — seen at 2.2e-16) or the reference's own margin is under 1e-6.
 */
class RankerQueueGoldenTest {
    private fun d(v: String) = if (v == "nan") Double.NaN else v.toDouble()

    @Test
    fun queuesMatchTheReference() {
        val model = assertNotNull(RankerModel.parse(asset()))
        assertEquals(RankerModel.Anchor.MIX, model.anchor)
        val lines = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/ranker2_golden.tsv.gz")))
            .bufferedReader().readLines().filter(String::isNotEmpty)
        var at = 0
        var queries = 0
        var tolerated = 0
        while (at < lines.size) {
            val head = lines[at++].split('\t')
            val n = head[2].toInt()
            fun base(f: List<String>) = RankerRow(
                family = f[0].toInt().takeIf { it >= 0 }?.toString(),
                language = f[1].toInt().takeIf { it > 0 }?.toString(),
                decade = f[2].toInt().takeIf { it > 0 },
                artistKey = f[3].toInt().takeIf { it >= 0 }?.toString().orEmpty(),
            )
            val seedKey = head[3].toULong()
            val seed = base(head.drop(4))
            val rows = (0 until n).map { lines[at++].split('\t') }
            val keys = rows.map { it[1].toULong() }
            val pool = rows.map { base(it.drop(2)) }
            val logPop = rows.map { it[6].toDouble() }
            val anchors = HashMap<ULong, List<List<Double>>>()
            while (lines[at].startsWith("anchor\t")) {
                val f = lines[at++].split('\t')
                anchors[f[1].toULong()] = (2..4).map { c -> f[c].split(',').map(::d) }
            }
            val want = lines[at++].removePrefix("queue\t").split(',').map(String::toULong)
            val gaps = lines[at++].removePrefix("gaps\t").split(',').map(String::toDouble)
            val order = keys.indices.sortedBy { keys[it] }
            val tie = IntArray(n).also { t -> order.forEachIndexed { rank, i -> t[i] = rank } }
            val picks = RankerQueue.build(
                model = model,
                poolSize = n,
                artistKeys = pool.map { it.artistKey },
                seedArtist = seed.artistKey,
                tieOrder = tie,
                length = 20,
                take = { hop, best, score ->
                    val reference = keys.indexOf(want[hop])
                    if (best != reference) {
                        // Identical rows: the reference's BLAS dot product can still split them by one ulp.
                        val sameScore = abs(score(best) - score(reference)) <= 1e-9 * maxOf(1.0, abs(score(best)))
                        assertTrue(
                            sameScore || gaps[hop] <= 1e-6,
                            "${head[1]} hop ${hop + 1}: ${keys[best]} instead of ${want[hop]} (margin ${gaps[hop]})",
                        )
                        tolerated++
                    }
                    reference
                },
            ) { anchor ->
                val anchorKey = if (anchor == RankerQueue.SEED) seedKey else keys[anchor]
                val (cos, co, lfm) = checkNotNull(anchors[anchorKey]) { "${head[1]}: no golden anchor $anchorKey" }
                val anchorRow = if (anchor == RankerQueue.SEED) seed else pool[anchor]
                RankerFeatures.of(
                    anchorRow,
                    pool.indices.map { i ->
                        RankerRow(
                            cosine = cos[i], family = pool[i].family, language = pool[i].language, decade = pool[i].decade,
                            artistKey = pool[i].artistKey, colisten = co[i], logPopularity = logPop[i], lfm = lfm[i],
                        )
                    },
                )
            }
            assertEquals(want, picks.map { keys[it] }, head[1])
            queries++
        }
        assertEquals(6, queries)
        println("ranker queue golden: $queries queries, ${queries * 20} hops, $tolerated taken differently on an equal score or a margin under 1e-6")
    }

    /**
     * The shipped weights with proto_ranker2's own decoder: ε = 0, p = 2.129, no third-in-a-row cap (the asset now
     * carries PLAN_FINAL §8's D0 parameters; DecoderD0GoldenTest pins those).
     */
    private fun asset(): String = File("../../androidApp/src/main/assets/ml/smart_ranker.json").readText()
        .replace(Regex("\"epsilon\":\\s*[0-9.eE+-]+"), "\"epsilon\": 0")
        .replace(Regex("\"runPenalty\":\\s*[0-9.eE+-]+"), "\"runPenalty\": 2.129")
        .replace(Regex("\"thirdInRowCap\":\\s*[0-9.eE+-]+"), "\"thirdInRowCap\": 0")
}
