/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the guard swallows, pinned to the crash seen on API 29 and 30 from
 * `cmd media_session dispatch`. Only that failure may be dropped. Anything that merely resembles
 * it has to crash as before.
 */
internal class NamelessControllerGuardTest {

    @Test
    fun media3RefusingAFrameworkCommandWithoutAPackageIsRecognised() {
        assertTrue(NamelessControllerGuard.isNamelessControllerCommand(observedCrash()))
    }

    @Test
    fun theRecognitionSurvivesR8RenamingMedia3() {
        val obfuscated = observedCrash().apply {
            stackTrace = stackTrace.map {
                if (it.className.startsWith("androidx.")) StackTraceElement("a.b.c", "d", null, -1) else it
            }.toTypedArray()
        }

        assertTrue(NamelessControllerGuard.isNamelessControllerCommand(obfuscated))
    }

    @Test
    fun theSameMessageOutsideTheSessionDispatcherStillCrashes() {
        val elsewhere = IllegalArgumentException("packageName should be nonempty").apply {
            stackTrace = arrayOf(
                StackTraceElement("io.github.nikitasud.latentjam.app.Foo", "bar", "Foo.kt", 1),
                StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 107),
            )
        }

        assertFalse(NamelessControllerGuard.isNamelessControllerCommand(elsewhere))
    }

    @Test
    fun anotherFailureInsideTheSessionDispatcherStillCrashes() {
        val other = IllegalArgumentException("index out of range").apply {
            stackTrace = observedCrash().stackTrace
        }

        assertFalse(NamelessControllerGuard.isNamelessControllerCommand(other))
    }

    @Test
    fun aDifferentExceptionTypeStillCrashes() {
        val other = IllegalStateException("packageName should be nonempty").apply {
            stackTrace = observedCrash().stackTrace
        }

        assertFalse(NamelessControllerGuard.isNamelessControllerCommand(other))
    }

    private fun observedCrash() = IllegalArgumentException("packageName should be nonempty").apply {
        stackTrace = arrayOf(
            StackTraceElement("androidx.media3.session.legacy.MediaSessionManager\$RemoteUserInfo", "<init>", "MediaSessionManager.java", 159),
            StackTraceElement("androidx.media3.session.legacy.MediaSessionCompat\$MediaSessionImplApi28", "getCurrentControllerInfo", "MediaSessionCompat.java", 2259),
            StackTraceElement("androidx.media3.session.MediaSessionLegacyStub", "onMediaButtonEvent", "MediaSessionLegacyStub.java", 528),
            StackTraceElement("android.media.session.MediaSession\$CallbackMessageHandler", "handleMessage", "MediaSession.java", 1578),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 107),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 214),
        )
    }
}
