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
internal fun mapWorkFits(required: Long): Boolean {
    if (availableMapWorkBytes() >= required) return true
    collectMapGarbage()
    return availableMapWorkBytes() >= required
}

internal expect fun collectMapGarbage()
