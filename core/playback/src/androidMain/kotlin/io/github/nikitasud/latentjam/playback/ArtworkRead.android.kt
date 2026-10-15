/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import java.io.InterruptedIOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** Stream interruption must propagate as cancellation when the coroutine stopped the read. */
internal suspend fun <T> readPlaybackArtworkInterruptibly(read: () -> T): T = try {
    runInterruptible(Dispatchers.IO, read)
} catch (failure: InterruptedIOException) {
    // runInterruptible translates InterruptedException, but streams use InterruptedIOException.
    // Keep an unrelated I/O failure intact so the caller can apply its normal fallback policy.
    currentCoroutineContext().ensureActive()
    throw failure
}
