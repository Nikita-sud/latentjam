/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

public actual fun heapHeadroomBytes(): Long {
    val r = Runtime.getRuntime()
    return r.maxMemory() - (r.totalMemory() - r.freeMemory())
}

public actual fun heapHeadroomAfterCollectBytes(): Long {
    Runtime.getRuntime().gc()
    return heapHeadroomBytes()
}
