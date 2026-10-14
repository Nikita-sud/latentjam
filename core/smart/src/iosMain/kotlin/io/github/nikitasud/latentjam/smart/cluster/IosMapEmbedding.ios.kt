// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.smart.cluster

/** The Swift shell owns the static native map library, separately from ONNX Runtime. */
public interface IosMapEmbeddingProvider {
    public fun embed(rows: FloatArray, n: Int, dim: Int, initial: FloatArray?, output: FloatArray, cancellation: MapCancellation): Int
    public fun availableMemoryBytes(): Long
}
public object IosMapEmbeddingRegistry {
    public var provider: IosMapEmbeddingProvider? = null
}
internal actual fun nativeMapEmbed(
    rows: FloatArray, n: Int, dim: Int, initial: FloatArray?, output: FloatArray, cancellation: MapCancellation,
): Int = IosMapEmbeddingRegistry.provider?.embed(rows, n, dim, initial, output, cancellation) ?: 3

