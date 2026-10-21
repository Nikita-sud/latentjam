// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.sync.Mutex

/** A dispatcher can interleave suspended jobs. This gate keeps large map stages disjoint. */
internal val mapWorkGate = Mutex()
internal expect fun availableMapWorkBytes(): Long

/**
 * Whether [required] bytes of map work fit. Used heap includes garbage not yet collected, such as
 * the matrix region discovery has just released (54 MB at 10,000 tracks), so a reading that falls
 * short is retried once after a collection. Call only from the background map lane.
 */
internal fun mapWorkFits(required: Long): Boolean = mapWorkBytes(required) >= required

/**
 * The free map work bytes, read as [mapWorkFits] reads them: once more after a collection when
 * the first reading falls short of [required]. For a caller that checks [required] now and hands
 * the same figure to work that would otherwise measure again after its own buffers exist.
 */
internal fun mapWorkBytes(
    required: Long,
    available: () -> Long = ::availableMapWorkBytes,
    collect: () -> Unit = ::collectMapGarbage,
): Long {
    val first = available()
    if (first >= required) return first
    collect()
    return available()
}

internal expect fun collectMapGarbage()
