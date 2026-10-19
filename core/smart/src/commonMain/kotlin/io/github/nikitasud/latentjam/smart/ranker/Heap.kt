/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

/** Bytes the managed heap can still grow by (maximum − used); Long.MAX_VALUE where the platform has no such limit. */
public expect fun heapHeadroomBytes(): Long

/**
 * PLAN_FINAL §7.2b's up-front memory rule: a press scores C candidates × F features of 8 bytes; it runs the ranker
 * only when the headroom is at least twice that plus 8 MB, else the rule-only path. Memory is bounded before the
 * press, never by catching an out-of-memory error.
 */
public fun rankerFitsInHeap(candidates: Int, headroom: Long = heapHeadroomBytes()): Boolean {
    val press = candidates.toLong() * RankerFeatures.NAMES.size * 8L * 3L + FIXED_BYTES
    return headroom >= 2 * press + 8L * 1024 * 1024
}

/** The press's fixed arrays: the hop's scores, the near-best set and the knowledge it touches. */
private const val FIXED_BYTES: Long = 1L * 1024 * 1024
