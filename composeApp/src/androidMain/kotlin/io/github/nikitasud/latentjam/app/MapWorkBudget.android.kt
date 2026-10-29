// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.app

import android.os.Debug
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout

internal actual fun availableMapWorkBytes(): Long {
    val runtime = Runtime.getRuntime()
    val javaUsed = runtime.totalMemory() - runtime.freeMemory()
    val reserve = maxOf(16L * 1024 * 1024, runtime.maxMemory() / 8)
    // The Kotlin map paths allocate only Java arrays, which the Java limit alone bounds. Native
    // allocations (ONNX models, decoders) do not shrink it, so charging them would refuse a map
    // that fits: at 10,000 tracks during analysis it did.
    // The experimental native backend works in native memory, so it keeps the native charge.
    val native = if (LibraryLayout.experimentalBackend) Debug.getNativeHeapAllocatedSize() else 0L
    return (runtime.maxMemory() - javaUsed - native - reserve).coerceAtLeast(0)
}

internal actual fun collectMapGarbage() {
    Runtime.getRuntime().gc()
}
