// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.sync.Mutex

/** A dispatcher can interleave suspended jobs. This gate keeps large map stages disjoint. */
internal val mapWorkGate = Mutex()
internal expect fun availableMapWorkBytes(): Long

