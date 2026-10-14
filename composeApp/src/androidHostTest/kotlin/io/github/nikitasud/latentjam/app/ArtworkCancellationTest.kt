/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.InterruptedIOException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class ArtworkCancellationTest {
    @Test fun cancellingAStreamReadDoesNotEscapeAsAnIoCrash() = runTest {
        val entered = CompletableDeferred<Unit>()
        var published = false
        val read = async {
            readArtworkInterruptibly {
                entered.complete(Unit)
                try { Thread.sleep(60000) } catch (_: InterruptedException) {
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

    @Test fun anIoTimeoutWithoutCoroutineCancellationIsMissingArtwork() = runTest {
        assertNull(readArtworkInterruptibly<String> { throw InterruptedIOException("timeout") })
    }
}
