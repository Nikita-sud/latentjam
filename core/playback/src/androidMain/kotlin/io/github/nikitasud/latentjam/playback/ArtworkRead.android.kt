/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
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

/**
 * Whether a cover can be read, as [probe] finds it. The probe runs in the controller's own coroutine at every track
 * change, where anything thrown ends the process, and the track that threw is the one the next launch restores as
 * current. So a cover no reader can handle (a provider that answers with an unchecked exception, a damaged picture, one
 * too large for the heap) is no readable cover, as a missing one is: the track gets the generated tile. Only
 * cancellation is passed on.
 */
internal suspend fun readableCover(probe: suspend () -> Boolean): Boolean = try {
    probe()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
} catch (_: OutOfMemoryError) {
    false
}
