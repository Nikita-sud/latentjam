/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.ChainTuning
import io.github.nikitasud.latentjam.smart.chain.SmartChain
import io.github.nikitasud.latentjam.smart.chain.SmartSnapshot
import io.github.nikitasud.latentjam.smart.chain.SmartTrack
import io.github.nikitasud.latentjam.smart.chain.TrackMeta
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * The half vectors measured on a 10,000-song library: file size, the index's heap, a snapshot's build and a queue's
 * planning time, float32 against halves, and how many picks change. A report (printed), not a gate.
 */
class HalfVectorsMeasureTest {
    @Test
    fun tenThousandSongs() = runBlocking {
        val n = 10_000
        val dim = 960
        val random = Random(9)
        // Clustered unit vectors, as an audio encoder gives: 200 centres, each song near one.
        val centres = Array(200) { FloatArray(dim) { (random.nextDouble() * 2 - 1).toFloat() } }
        val vectors = LinkedHashMap<TrackId, FloatArray>()
        repeat(n) { i ->
            val c = centres[random.nextInt(centres.size)]
            val v = FloatArray(dim) { d -> c[d] + 0.6f * (random.nextDouble() * 2 - 1).toFloat() }
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            for (d in 0 until dim) v[d] /= norm
            vectors[TrackId("t$i")] = v
        }
        val dir = Files.createTempDirectory("half-measure").toFile()
        try {
            FileIndexStore(dir, "floats.bin", maximumSnapshotBytes = Long.MAX_VALUE - 1).save("m", vectors)
            FileIndexStore(dir, "halves.bin", maximumSnapshotBytes = Long.MAX_VALUE - 1, half = true).save("m", vectors)
            println("HALF file: floats ${File(dir, "floats.bin").length() / 1024} KiB, halves ${File(dir, "halves.bin").length() / 1024} KiB")
        } finally {
            dir.deleteRecursively()
        }
        fun used(): Long {
            repeat(3) { System.gc(); Thread.sleep(100) }
            val rt = Runtime.getRuntime()
            return rt.totalMemory() - rt.freeMemory()
        }
        for (half in listOf(false, true)) {
            val before = used()
            val index = InMemoryVectorIndex(dim, if (half) HalfRowStore(dim) else HeapRowStore(dim))
            for ((id, v) in vectors) index.upsert(id, v)
            val after = used()
            val tracks = vectors.keys.mapIndexed { i, id ->
                SmartTrack(id, index.storedRow(id)!!, meta = TrackMeta("s$i", "a${i / 10}", null, "Pop", 2000))
            }
            var started = System.nanoTime()
            val snapshot = requireNotNull(SmartSnapshot.build(tracks))
            val buildMs = (System.nanoTime() - started) / 1_000_000
            val queues = ArrayList<List<Int>>()
            started = System.nanoTime()
            for (s in 0 until 20) {
                queues += SmartChain(snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true))
                    .build(TrackId("t${s * 499}"), 12, FloatArray(5)).rows
            }
            val planMs = (System.nanoTime() - started) / 1_000_000 / 20
            println("HALF ${if (half) "halves" else "floats"}: index heap ${(after - before) / 1024 / 1024} MiB, snapshot build $buildMs ms, plan $planMs ms/queue")
            if (half) halfQueues = queues else floatQueues = queues
        }
        val picks = floatQueues.sumOf { it.size }
        val changed = floatQueues.indices.sumOf { q -> floatQueues[q].indices.count { it >= halfQueues[q].size || floatQueues[q][it] != halfQueues[q][it] } }
        println("HALF picks changed: $changed of $picks")
    }

    private var floatQueues: List<List<Int>> = emptyList()
    private var halfQueues: List<List<Int>> = emptyList()
}
