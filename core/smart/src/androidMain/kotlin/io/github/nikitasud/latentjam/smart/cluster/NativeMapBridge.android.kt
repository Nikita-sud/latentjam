// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.smart.cluster

internal object NativeMapBridge {
    init { System.loadLibrary("ljmap") }
    external fun embed(rows: FloatArray, n: Int, dim: Int, initial: FloatArray?, output: FloatArray, cancellation: MapCancellation): Int
}

internal actual fun nativeMapEmbed(
    rows: FloatArray, n: Int, dim: Int, initial: FloatArray?, output: FloatArray, cancellation: MapCancellation,
): Int = try {
    NativeMapBridge.embed(rows, n, dim, initial, output, cancellation)
} catch (_: LinkageError) {
    3
}

