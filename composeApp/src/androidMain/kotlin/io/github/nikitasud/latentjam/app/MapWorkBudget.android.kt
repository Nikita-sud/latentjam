// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.app

import android.os.Debug
internal actual fun availableMapWorkBytes(): Long {
    val runtime = Runtime.getRuntime()
    val javaUsed = runtime.totalMemory() - runtime.freeMemory()
    val reserve = maxOf(16L * 1024 * 1024, runtime.maxMemory() / 8)
    // Deliberately charge native allocations against the ordinary heap-sized work allowance too.
    return (runtime.maxMemory() - javaUsed - Debug.getNativeHeapAllocatedSize() - reserve).coerceAtLeast(0)
}

