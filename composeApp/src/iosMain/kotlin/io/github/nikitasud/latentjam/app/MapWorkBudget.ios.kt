// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.cluster.IosMapEmbeddingRegistry
internal actual fun availableMapWorkBytes(): Long =
    IosMapEmbeddingRegistry.provider?.availableMemoryBytes() ?: 0L

