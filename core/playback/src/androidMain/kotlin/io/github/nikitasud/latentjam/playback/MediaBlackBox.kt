/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import java.io.File

/**
 * The durable local black box shared by the transport surface and SMART.
 *
 * One line per event, in app-private storage, bounded and best-effort: a report that arrives days
 * later ("my headphones did not pause it", "SMART stopped again") becomes attributable instead of
 * folklore. Nothing here leaves the device, and nothing here is load-bearing — every failure is
 * swallowed, because a diagnostic must never take playback down with it.
 */
internal object MediaBlackBox {

    private const val FILE_NAME = "media_commands.log"

    /** Dropped whole rather than rotated: the recent past is what a report is ever about. */
    private const val MAX_BYTES = 128_000L

    fun record(filesDir: File, body: String) {
        runCatching {
            val file = File(filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText("${System.currentTimeMillis()} $body\n")
        }
    }
}

/** Convenience for callers holding a [Context] rather than a service's own `filesDir`. */
internal fun mediaBlackBox(context: Context, body: String) {
    MediaBlackBox.record(context.filesDir, body)
}
