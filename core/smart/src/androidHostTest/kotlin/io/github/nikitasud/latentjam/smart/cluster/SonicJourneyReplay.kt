/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

/**
 * Runs the SHIPPING journey geometry over the exported library and prints the numbers that keep
 * it honest: adjacent-hop cosine and the actual stops. The inline prototype measured 0.345 mean
 * hop; this replay exists so a regression in the real code cannot hide behind that memory.
 */
class SonicJourneyReplay {

    @Test
    fun `replay a journey over the exported library`() {
        val fixture = System.getenv("SMART_PARITY_FIXTURE")?.let(::File)
            ?.takeIf(File::isDirectory) ?: run {
            println("SKIP journey replay: set SMART_PARITY_FIXTURE")
            return
        }
        val metaFile = File(fixture, "meta.tsv")
        val audioFile = File(fixture, "audio.f32")
        if (!metaFile.isFile || !audioFile.isFile) {
            println("SKIP journey replay: fixture incomplete")
            return
        }
        val meta = metaFile.readLines().map { it.split('\t') }
        val bytes = audioFile.readBytes()
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val rows = FloatArray(floats.remaining()).also { floats.get(it) }
        val dim = 960
        val ids = meta.indices.map { TrackId("row$it") }
        val titles = meta.mapIndexed { i, m -> ids[i] to "${m[0]} — ${m[1]}" }.toMap()
        val artists = meta.mapIndexed { i, m -> ids[i] to m[1] }.toMap()

        val session = SonicJourney.session(
            LibraryVectorSpace(
                trackIds = ids,
                rows = rows.copyOf(ids.size * dim),
                dim = dim,
                source = LibraryVectorSource.AUDIO,
            ),
        )
        // Anchor: a fixed known row when this is the fresh export (keeps the printed journey
        // comparable run to run), else any titled row — the fixture has no stats to consult.
        val anchor = ids.firstOrNull { titles.getValue(it).startsWith("FUNK TONTO") }
            ?: ids.first { !artists[it].isNullOrBlank() }
        val destination = session.farthestFrom(anchor, ids - anchor) ?: return
        val path = session.plot(anchor, destination) { artists[it] } ?: run {
            println("journey declined")
            return
        }

        // Independent hop measurement, centered like the session's own geometry.
        val mean = FloatArray(dim)
        for (r in ids.indices) for (d in 0 until dim) mean[d] += rows[r * dim + d] / ids.size
        fun unit(id: TrackId): FloatArray {
            val r = ids.indexOf(id)
            val v = FloatArray(dim) { d -> rows[r * dim + d] - mean[d] }
            val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(dim) { d -> v[d] / n }
        }
        val hops = path.zipWithNext().map { (a, b) ->
            val x = unit(a); val y = unit(b)
            var s = 0.0
            for (d in 0 until dim) s += (x[d] * y[d]).toDouble()
            s
        }
        println(
            "journey ${path.size} stops, meanHop=%.3f minHop=%.3f".format(
                hops.average(),
                hops.min(),
            ),
        )
        path.forEach { println("  ${titles[it]}") }
        check(hops.average() > 0.15) { "journey coherence collapsed: ${hops.average()}" }
    }
}
