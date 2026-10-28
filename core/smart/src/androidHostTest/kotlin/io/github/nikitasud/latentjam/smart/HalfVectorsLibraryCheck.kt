/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.cluster.LibraryWorlds
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Real libraries (LJ_HALF_LIBS: colon-separated stand library dirs; skipped without it): For You's neighbour lists and
 * the Map's worlds from float32 vectors against the same vectors as halves. A report, printed per library.
 */
class HalfVectorsLibraryCheck {
    @Test
    fun neighboursAndWorlds() = runBlocking {
        val dirs = System.getenv("LJ_HALF_LIBS")?.split(':')?.filter(String::isNotEmpty)?.map(::File) ?: return@runBlocking
        for (dir in dirs) {
            val files = File(dir, "files")
            val file = File(files, VersionedIndexStore.fileNameFor("smart_index", AUDIO_MODEL_VERSION)).takeIf { it.isFile } ?: File(files, "smart_index.bin")
            val snapshot = readIndexSnapshotFile(file, AUDIO_MODEL_VERSION) ?: continue
            val floats = InMemoryVectorIndex(960)
            val halves = InMemoryVectorIndex(960, HalfRowStore(960))
            for ((id, v) in snapshot.entries) {
                floats.upsert(id, v)
                halves.upsert(id, v)
            }
            // For You's raw material: the ten nearest neighbours of every song.
            var overlap = 0
            var total = 0
            var identical = 0
            for (id in snapshot.entries.keys) {
                val a = floats.nearest(floats.vector(id)!!, 10, setOf(id)).map { it.trackId }
                val b = halves.nearest(halves.vector(id)!!, 10, setOf(id)).map { it.trackId }
                overlap += a.intersect(b.toSet()).size
                total += a.size
                if (a == b) identical++
            }
            // The Map's worlds: the same k-means on both vector sets.
            val library = snapshot.entries.keys.map { TrackDescriptor(id = it, title = it.value) }
            val worldsF = LibraryWorlds.discover(library, floats.entries(), 960)
            val worldsH = LibraryWorlds.discover(library, halves.entries(), 960)
            val worldOf = HashMap<TrackId, Int>()
            worldsF.forEachIndexed { w, world -> world.tracks.forEach { worldOf[it.id] = w } }
            // Each half world matched to the float world it shares most songs with.
            var agree = 0
            var placed = 0
            for (world in worldsH) {
                val votes = world.tracks.mapNotNull { worldOf[it.id] }.groupingBy { it }.eachCount()
                agree += votes.values.maxOrNull() ?: 0
                placed += world.tracks.size
            }
            println("HALF ${dir.name}: songs ${snapshot.entries.size}, neighbours top-10 overlap ${"%.4f".format(overlap.toDouble() / total)}, " +
                "identical lists ${"%.4f".format(identical.toDouble() / snapshot.entries.size)}, worlds ${worldsF.size} vs ${worldsH.size}, " +
                "same world ${"%.4f".format(agree.toDouble() / placed.coerceAtLeast(1))}")
        }
    }
}
