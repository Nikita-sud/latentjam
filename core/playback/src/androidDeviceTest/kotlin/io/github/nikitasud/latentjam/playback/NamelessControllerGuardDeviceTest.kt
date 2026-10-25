/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on a real looper. A dropped command leaves the looper serving the next message, and
 * any other failure still reaches the thread's uncaught handler as the same throwable.
 */
class NamelessControllerGuardDeviceTest {

    @Test
    fun aNamelessCommandIsDroppedAndTheLooperKeepsServing() {
        val thread = HandlerThread("guarded").apply { start() }
        val rejected = AtomicInteger()
        NamelessControllerGuard.install(thread.looper) { rejected.incrementAndGet() }
        val handler = Handler(thread.looper)

        handler.post { throw namelessCommandFailure() }
        val served = CountDownLatch(1)
        handler.post { served.countDown() }

        assertTrue("looper stopped serving", served.await(5, TimeUnit.SECONDS))
        assertEquals(1, rejected.get())
        thread.quitSafely()
        thread.join(5_000)
        assertTrue("looper did not quit", !thread.isAlive)
    }

    @Test
    fun anyOtherFailureStillCrashesTheThread() {
        val crash = AtomicReference<Throwable>()
        val died = CountDownLatch(1)
        val thread = HandlerThread("guarded-crash").apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                crash.set(error)
                died.countDown()
            }
            start()
        }
        NamelessControllerGuard.install(thread.looper) { throw AssertionError("must not reject") }

        val other = IllegalArgumentException("unrelated")
        Handler(thread.looper).post { throw other }

        assertTrue("thread did not crash", died.await(5, TimeUnit.SECONDS))
        assertSame(other, crash.get())
    }

    private fun namelessCommandFailure() =
        IllegalArgumentException("packageName should be nonempty").apply {
            stackTrace = arrayOf(
                StackTraceElement("android.media.session.MediaSession\$CallbackMessageHandler", "handleMessage", "MediaSession.java", 1578),
            ) + stackTrace
        }
}
