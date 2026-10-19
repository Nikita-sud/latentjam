/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PLAN_FINAL §8's D0 decoder against proto_ranker4.py's own decode loop (RQ.rollout4, b4117980) on 12 synthetic
 * queries (tools/research/ranker/export_decoder_golden.py): song-version dedup over min(50, n / 2), the run penalty
 * (λ_run 1.75, sd of the raw scores left after dedup), the −8 third-in-a-row cap, the ε = 0.01 near-best set (sd of the
 * penalised scores) and the uniform draw from SHA-256(salt|query|press|0) → SplitMix64, h-th output. Scores feed the
 * model directly (one "cos" column, weight 1), so this pins the decoder alone.
 */
class DecoderD0GoldenTest {
    @Test
    fun queuesMatchProtoRanker4() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/decoder_d0_golden.jsonl")).reader().readLines().filter(String::isNotEmpty)
        val cos = RankerFeatures.NAMES.indexOf("cos")
        for (line in lines) {
            @Suppress("UNCHECKED_CAST")
            val d = MiniJson(line).parse() as Map<String, Any?>
            fun ints(k: String) = (d.getValue(k) as List<*>).map { (it as Number).toInt() }
            val n = (d.getValue("n") as Number).toInt()
            val aid = ints("aid")
            val song = ints("song")
            val tidr = ints("tidr").toIntArray()
            @Suppress("UNCHECKED_CAST")
            val scores = (d.getValue("scores") as Map<String, List<Number>>).mapKeys { it.key.toInt() }.mapValues { e -> e.value.map { it.toDouble() } }
            val model = RankerModel.parse(
                """{"features": ["cos"], "mean": [0], "sd": [1], "weights": [1], "audioWeight": 0, "runPenalty": 1.75,
                   "anchor": "mix", "seedWeight": 2, "previousWeight": 1, "epsilon": 0.01, "thirdInRowCap": ${d["cap"]}}""",
            )!!
            val key = d.getValue("key") as String
            val press = (d.getValue("press") as Number).toLong()
            val draws = HopDraws(SaltedKey.seed(d.getValue("salt") as String, key, press, 0))
            val window = maxOf(1, minOf(50, (n + 1) / 2))
            // Library rows: 0 is the seed, candidate i is row i + 1.
            val picks = RankerQueue.build(
                model = model,
                poolSize = n,
                artistKeys = (0 until n).map { aid[it + 1].let { a -> if (a < 0) "" else a.toString() } },
                seedArtist = aid[0].let { a -> if (a < 0) "" else a.toString() },
                tieOrder = tidr,
                length = (d.getValue("K") as Number).toInt(),
                choose = { hop, nearBest -> nearBest[draws.pick(hop + 1, nearBest.size)] },
                blocked = { row, taken ->
                    val queue = listOf(0) + taken.map { it + 1 }
                    song[row + 1] in queue.takeLast(window).map { song[it] }
                },
            ) { anchor ->
                val s = scores.getValue(if (anchor == RankerQueue.SEED) 0 else anchor + 1)
                Array(n) { i -> DoubleArray(RankerFeatures.NAMES.size).also { it[cos] = s[i] } }
            }
            assertEquals(ints("picks"), picks.map { it + 1 }, key)
        }
        assertEquals(12, lines.size)
    }
}
