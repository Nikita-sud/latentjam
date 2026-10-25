/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.os.Handler
import android.os.Looper

/**
 * Keeps a command from a controller without a package name from taking the process down.
 *
 * On API 28+ Media3's legacy session reads the framework's current controller and wraps it in
 * `RemoteUserInfo`, whose constructor throws `IllegalArgumentException("packageName should be
 * nonempty")` when the framework hands it an empty name. The framework does that for the shell's
 * `cmd media_session dispatch` (and `media dispatch` before API 30). Media3 throws before any
 * [androidx.media3.session.MediaSession.Callback] runs, so the app has no callback where it could
 * refuse such a caller. The exception escapes the session's looper and the process dies.
 *
 * The guard runs the looper one level down and catches exactly that failure:
 * - Media3's message.
 * - A frame of the framework's session dispatcher. The platform class keeps its name under R8,
 *   while Media3's own frames are renamed.
 *
 * Any other throwable is rethrown untouched and crashes exactly as it would without the guard. The
 * matched command is dropped, which is the clean rejection. A real controller cannot get here:
 * Bluetooth keys, Android Auto, the Google app and System UI all reach the session under their own
 * package, so their trust is still decided in `onConnect` as before.
 */
internal object NamelessControllerGuard {

    private const val MEDIA3_EMPTY_PACKAGE_MESSAGE = "packageName should be nonempty"
    private const val FRAMEWORK_SESSION_DISPATCHER =
        "android.media.session.MediaSession\$CallbackMessageHandler"

    private val guardedLoopers = HashSet<Looper>()

    /** Whether [error] is Media3 refusing a framework session command that carries no package. */
    fun isNamelessControllerCommand(error: Throwable): Boolean =
        error is IllegalArgumentException &&
            error.message == MEDIA3_EMPTY_PACKAGE_MESSAGE &&
            error.stackTrace.any { it.className == FRAMEWORK_SESSION_DISPATCHER }

    /**
     * Guards [looper] for the rest of its life. Installing on the same looper again does nothing.
     * [onRejected] runs on that looper once for every dropped command.
     */
    fun install(looper: Looper, onRejected: (IllegalArgumentException) -> Unit) {
        synchronized(guardedLoopers) { if (!guardedLoopers.add(looper)) return }
        Handler(looper).post {
            // API 30+ logs "Loop again would have the queued messages be executed before this one
            // completed" once here. That warning is the expected result of re-entering on purpose.
            while (true) {
                try {
                    Looper.loop()
                    return@post // The looper quit.
                } catch (error: IllegalArgumentException) {
                    if (!isNamelessControllerCommand(error)) throw error
                    onRejected(error)
                }
            }
        }
    }
}
