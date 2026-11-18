/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import java.io.InterruptedIOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class PlaybackArtworkCancellationTest {
    @Test
    fun cancelledStreamReadRemainsCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        var published = false
        val read = async {
            readPlaybackArtworkInterruptibly {
                entered.complete(Unit)
                try { Thread.sleep(60_000) } catch (_: InterruptedException) {
                    throw InterruptedIOException("Artwork read interrupted")
                }
                "cover"
            }
            published = true
        }
        entered.await()
        read.cancelAndJoin()
        assertTrue(read.isCancelled)
        assertTrue(!published)
    }

    @Test
    fun anUnrelatedIoFailureIsPreservedForTheCaller() = runTest {
        val failure = InterruptedIOException("provider timeout")
        assertSame(failure, assertFailsWith<InterruptedIOException> {
            readPlaybackArtworkInterruptibly<String> { throw failure }
        })
    }

    @Test
    fun aCoverNoReaderCanHandleIsNoReadableCoverAndEndsNothing() = runTest {
        assertFalse(readableCover { throw IllegalArgumentException("Unknown URL") })
        assertFalse(readableCover { throw IllegalStateException("damaged tag") })
        assertFalse(readableCover { throw ArrayIndexOutOfBoundsException(7) })
        assertFalse(readableCover { throw OutOfMemoryError("a 16 MiB cover on a 48 MB heap") })
        assertFalse(readableCover { throw java.io.IOException("volume away") })
        assertFalse(readableCover { throw SecurityException("grant revoked") })
        assertFalse(readableCover { readPlaybackArtworkInterruptibly<Boolean> { throw OutOfMemoryError("on the reader's thread") } })
        assertTrue(readableCover { true })
    }

    @Test
    fun aCancelledCoverProbeStaysACancellation() = runTest {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            readableCover { throw kotlinx.coroutines.CancellationException("the track changed") }
        }
    }
}
