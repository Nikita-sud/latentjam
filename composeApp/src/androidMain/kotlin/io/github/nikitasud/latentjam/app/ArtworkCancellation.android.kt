/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.InterruptedIOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** Stream reads report interruption differently from Semaphore.acquire/Thread.sleep. */
internal suspend fun <T> readArtworkInterruptibly(read: () -> T?): T? = try {
    runInterruptible(Dispatchers.IO, read)
} catch (_: InterruptedIOException) {
    // runInterruptible only translates InterruptedException. Preserve coroutine cancellation so
    // scrolling away neither crashes the Activity nor caches an interrupted read as missing art.
    currentCoroutineContext().ensureActive()
    null
}
