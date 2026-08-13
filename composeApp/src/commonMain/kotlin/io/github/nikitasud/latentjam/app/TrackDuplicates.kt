/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Two files closer than this in audio space are treated as the same recording. The audio
 * embeddings are L2-normalized, so different encodes of one recording land at ~0.995+, while
 * covers, remixes and live versions stay clearly below.
 */
internal const val DUPLICATE_SIMILARITY = 0.99f

/**
 * Groups tracks whose stored audio embeddings are near-identical — the tag-blind duplicate
 * finder. Connected components rather than pairs: three encodes of one song are one group.
 * Biggest group first; singletons are not duplicates and are dropped.
 *
 * O(n²) dot products over the whole library — a deliberate, background-thread cost: 1500
 * tracks is ~1M small dot products, well under a phone's second.
 */
internal fun audioDuplicateGroups(
    vectors: Map<TrackId, FloatArray>,
    threshold: Float = DUPLICATE_SIMILARITY,
): List<List<TrackId>> {
    val ids = vectors.keys.toList()
    val rows = ids.map { vectors.getValue(it) }
    val parent = IntArray(ids.size) { it }

    fun find(x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root]
        var walk = x
        while (parent[walk] != root) {
            val next = parent[walk]
            parent[walk] = root
            walk = next
        }
        return root
    }

    fun union(a: Int, b: Int) {
        val rootA = find(a)
        val rootB = find(b)
        if (rootA != rootB) parent[rootB] = rootA
    }

    for (i in ids.indices) {
        val a = rows[i]
        for (j in i + 1 until ids.size) {
            val b = rows[j]
            if (a.size != b.size) continue
            var dot = 0f
            for (d in a.indices) dot += a[d] * b[d]
            if (dot >= threshold) union(i, j)
        }
    }

    return ids.indices
        .groupBy { find(it) }
        .values
        .filter { it.size > 1 }
        .map { group -> group.map { ids[it] } }
        .sortedByDescending { it.size }
}
