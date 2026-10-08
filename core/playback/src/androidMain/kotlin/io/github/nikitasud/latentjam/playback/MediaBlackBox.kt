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
 *
 * Retention degrades in proportion to the overflow. The recent past is what a report is ever about,
 * so past [MAX_BYTES] the oldest half of the file goes and the newest lines stay: one burst costs
 * half of the history, not all of it. A writer that floods without pause therefore shortens the log
 * instead of erasing it — the current fault is always still in there, and so is whatever preceded
 * it. Two bounds hold together here: at most [MAX_BYTES] is ever appended without a trim, so a
 * single oversized line cannot grow the file past roughly [MAX_BYTES] plus itself.
 */
internal object MediaBlackBox {

    private const val FILE_NAME = "media_commands.log"

    /** Size ceiling checked before every append; crossing it costs the oldest half of the file. */
    private const val MAX_BYTES = 128_000L

    /** Suffix of the staging file [trimToNewestHalf] swaps in; never read, never appended to. */
    private const val TRIM_SUFFIX = ".trim"

    /** Line terminator the trim cuts after; a kept line is never a fragment of a dropped one. */
    private const val NEWLINE_BYTE = '\n'.code.toByte()

    fun record(filesDir: File, body: String) {
        runCatching {
            val file = File(filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) trimToNewestHalf(file)
            file.appendText("${System.currentTimeMillis()} $body\n")
        }
    }

    /**
     * Drops the oldest half of [file], cutting on a line boundary, and leaves the rest in place.
     *
     * Written to a staging file and moved over the original: a crash midway leaves either the whole
     * old log or the whole trimmed one, never a truncated file. The newest line survives a trim
     * whenever the file is large enough to hold one. When the newest half holds no line boundary
     * at all — one unfinished or oversized line — its tail is kept just as it stands, because the
     * newest bytes are the history a report is about even where they are not a whole line.
     */
    private fun trimToNewestHalf(file: File) {
        val bytes = file.readBytes()
        val keepFrom = bytes.size / 2
        // Cut after the first line boundary in the newest half, so no line is kept as a fragment.
        val boundary = bytes.copyOfRange(keepFrom, bytes.size).indexOf(NEWLINE_BYTE)
        val kept = if (boundary >= 0) {
            bytes.copyOfRange(keepFrom + boundary + 1, bytes.size)
        } else {
            // No line boundary left: the tail is one unfinished or oversized line, and it stays.
            bytes.copyOfRange(keepFrom, bytes.size)
        }
        val staged = File(file.parentFile, file.name + TRIM_SUFFIX)
        try {
            staged.writeBytes(kept)
            if (!staged.renameTo(file)) {
                file.writeBytes(kept)
                staged.delete()
            }
        } catch (failure: Throwable) {
            staged.delete()
            throw failure
        }
    }
}

/** Convenience for callers holding a [Context] rather than a service's own `filesDir`. */
internal fun mediaBlackBox(context: Context, body: String) {
    MediaBlackBox.record(context.filesDir, body)
}
