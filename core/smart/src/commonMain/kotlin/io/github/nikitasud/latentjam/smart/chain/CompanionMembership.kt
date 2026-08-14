/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Snapshot-local representation of the listener's marked playlist memberships.
 *
 * Group count is deliberately unbounded by a machine word. A listener can create more than 64
 * playlists, and reordering those playlists must never decide which ones SMART honors. Empty and
 * duplicate groups are collapsed because neither can add a distinct preference.
 */
internal class CompanionMembership private constructor(
    private val groupsByRow: Array<IntArray>,
    private val rowsByGroup: List<IntArray>,
) {
    val groupCount: Int get() = rowsByGroup.size
    val populationSize: Int get() = groupsByRow.size

    fun groupsOf(row: Int): IntArray = groupsByRow[row]

    fun rowsOf(group: Int): IntArray = rowsByGroup[group]

    /**
     * The groups quota rotation and pool injection may act for on behalf of [row]: its groups
     * minus every (near-)superset of another, smaller group it is also in.
     *
     * "JoJo inside Anime" is one preference stated twice at different zoom levels, not two
     * preferences. Rotating over both would hand the vacuous outer statement half of the
     * guaranteed hops, so the nested claim absorbs its super-group's turns — the listener can
     * mark playlists at any granularity without owing the engine an explanation. Genuinely
     * distinct groups that merely intersect keep their own turns; see
     * [ChainConfig.COMPANION_NESTING_CONTAINMENT]. The bonus term needs no such filter: it
     * already scores by the smallest shared group.
     */
    fun quotaGroupsOf(row: Int): IntArray {
        val groups = groupsByRow[row]
        if (groups.size <= 1) return groups
        val kept = groups.filter { outer ->
            groups.none { inner -> inner != outer && absorbsQuotaOf(inner, outer) }
        }
        return kept.toIntArray()
    }

    /** True when the strictly smaller [inner] sits (almost) entirely inside [outer]. */
    private fun absorbsQuotaOf(inner: Int, outer: Int): Boolean {
        val innerRows = rowsByGroup[inner]
        val outerRows = rowsByGroup[outer]
        if (innerRows.size >= outerRows.size) return false
        var leftIndex = 0
        var rightIndex = 0
        var shared = 0
        while (leftIndex < innerRows.size && rightIndex < outerRows.size) {
            when {
                innerRows[leftIndex] < outerRows[rightIndex] -> leftIndex++
                innerRows[leftIndex] > outerRows[rightIndex] -> rightIndex++
                else -> {
                    shared++
                    leftIndex++
                    rightIndex++
                }
            }
        }
        return shared.toFloat() / innerRows.size >= ChainConfig.COMPANION_NESTING_CONTAINMENT
    }

    fun sharesGroup(rowA: Int, rowB: Int): Boolean =
        firstSharedGroup(rowA, rowB) >= 0

    /** Specificity of the smallest group shared by two rows. */
    fun weight(rowA: Int, rowB: Int): Float {
        val left = groupsByRow[rowA]
        val right = groupsByRow[rowB]
        var leftIndex = 0
        var rightIndex = 0
        var smallestGroup = Int.MAX_VALUE
        while (leftIndex < left.size && rightIndex < right.size) {
            when {
                left[leftIndex] < right[rightIndex] -> leftIndex++
                left[leftIndex] > right[rightIndex] -> rightIndex++
                else -> {
                    smallestGroup = minOf(smallestGroup, rowsByGroup[left[leftIndex]].size)
                    leftIndex++
                    rightIndex++
                }
            }
        }
        if (smallestGroup == Int.MAX_VALUE || populationSize == 0) return 0f
        return (1f - smallestGroup.toFloat() / populationSize)
            .coerceIn(ChainConfig.COMPANION_SPECIFICITY_FLOOR, 1f)
    }

    private fun firstSharedGroup(rowA: Int, rowB: Int): Int {
        val left = groupsByRow[rowA]
        val right = groupsByRow[rowB]
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.size && rightIndex < right.size) {
            when {
                left[leftIndex] < right[rightIndex] -> leftIndex++
                left[leftIndex] > right[rightIndex] -> rightIndex++
                else -> return left[leftIndex]
            }
        }
        return -1
    }

    companion object {
        fun build(
            rowIds: List<TrackId>,
            groups: List<Set<TrackId>>,
        ): CompanionMembership {
            val rowById = HashMap<TrackId, Int>(rowIds.size)
            rowIds.forEachIndexed { row, id ->
                if (id !in rowById) rowById[id] = row
            }

            val normalized = ArrayList<IntArray>()
            val seen = HashSet<List<Int>>()
            for (group in groups) {
                val rows = group.asSequence()
                    .mapNotNull(rowById::get)
                    .distinct()
                    .sorted()
                    .toList()
                // Zero- and one-row groups cannot relate two tracks in this snapshot. Discarding
                // them also keeps a marked playlist whose other members are unavailable from
                // consuming fair-quota turns that can never yield a candidate.
                if (rows.size >= 2 && seen.add(rows)) normalized += rows.toIntArray()
            }
            // Playlist drag order is presentation, not recommendation policy. Quota rotation uses
            // group indices, so canonicalize the effective memberships or passing the same marked
            // playlists in another order changes the queue (and makes UI reordering substantive).
            normalized.sortWith { left, right ->
                for (index in 0 until minOf(left.size, right.size)) {
                    val comparison = left[index].compareTo(right[index])
                    if (comparison != 0) return@sortWith comparison
                }
                left.size.compareTo(right.size)
            }

            val mutableByRow = Array(rowIds.size) { ArrayList<Int>() }
            normalized.forEachIndexed { group, rows ->
                for (row in rows) mutableByRow[row] += group
            }
            return CompanionMembership(
                groupsByRow = Array(rowIds.size) { row -> mutableByRow[row].toIntArray() },
                rowsByGroup = normalized,
            )
        }
    }
}
