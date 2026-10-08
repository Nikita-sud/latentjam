/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The retention of the durable log, pinned down on a real file.
 *
 * Each of the three tests about a trim fails under the policy the log used to have — erase the file
 * outright once it passes its size bound — because each one asks for history the trim has to leave
 * behind: the lines older than the newest one, the unterminated tail, and the log itself.
 */
internal class MediaBlackBoxTest {

    /** A directory of its own per test: the log is a plain file, and so is everything it writes. */
    private val filesDir: File = Files.createTempDirectory("media-black-box").toFile()

    @AfterTest
    fun removeFilesDir() {
        filesDir.deleteRecursively()
    }

    @Test
    fun eachRecordAddsOneLineInTheOrderItArrived() {
        MediaBlackBox.record(filesDir, "first")
        MediaBlackBox.record(filesDir, "second")

        assertEquals(listOf("first", "second"), logFile().readLines().map(::bodyOf))
    }

    @Test
    fun pastItsBoundTheLogKeepsTheNewestLinesInsteadOfBeingErased() {
        val (beforeTrim, trimmedIndex) = fillUntilTrimmed()

        val kept = logFile().readText().trimEnd('\n').lines()
        // The line that arrived with the trim is the newest one, and it survives it...
        assertEquals(line(trimmedIndex), bodyOf(kept.last()))
        // ...and so does the run of lines before it. Erasing the log and starting over from that
        // one line is exactly the retention a report cannot afford.
        assertTrue(kept.size > 1, "the trim kept only the newest line")
        // The survivors are the newest whole lines, in order: what went is the oldest half.
        val keptIndices = kept.map(::indexOfLine)
        assertEquals((keptIndices.first()..trimmedIndex).toList(), keptIndices)
        assertTrue(keptIndices.first() > 0, "the trim kept the oldest line of the log")
        // Half the history, plus the line that arrived with the trim — never more than that.
        assertTrue(
            logFile().length() <= beforeTrim / 2 + line(trimmedIndex).length + LINE_OVERHEAD,
            "the trim kept more than the newest half of a $beforeTrim-byte log",
        )
    }

    @Test
    fun aNewestHalfWithoutALineBoundaryKeepsItsTail() {
        MediaBlackBox.record(filesDir, "first")
        val file = logFile()
        // A log whose newest line never got its terminator — the shape a write that died midway
        // leaves behind — and which is far past the bound, so the next record trims it.
        file.appendBytes(UNTERMINATED_LINE.toByteArray())
        val beforeTrim = file.length()

        MediaBlackBox.record(filesDir, "newest")

        val text = file.readText()
        // Nothing in the newest half is a line boundary, so the tail is kept as it stands instead
        // of being dropped with the rest of the line: the newest bytes are the history a report is
        // about, whole line or not.
        assertTrue(text.startsWith("y"), "the tail of the unterminated line went with the trim")
        assertTrue(
            text.length > UNTERMINATED_CHARS / 4,
            "the trim kept ${text.length} bytes where the tail alone is about ${UNTERMINATED_CHARS / 2}",
        )
        assertTrue(text.endsWith("newest\n"), "the newest line was lost")
        assertTrue(file.length() < beforeTrim, "the trim kept the whole log")
    }

    @Test
    fun aStagingWriteThatFailsLeavesTheLogAsItWas() {
        MediaBlackBox.record(filesDir, "recorded-before-the-failure")
        MediaBlackBox.record(filesDir, OVERSIZED_LINE)
        val file = logFile()
        val intact = file.readText()

        // A trim stages its result beside the log and moves it over. Here that path is a directory,
        // so the staging write fails: the trim itself is best-effort, but the history the log
        // already holds is not. It has to come through untouched, with only the line that could not
        // be recorded lost.
        val staging = File(filesDir, file.name + TRIM_SUFFIX).apply { mkdirs() }
        MediaBlackBox.record(filesDir, "must-not-be-lost")

        assertEquals(intact, file.readText(), "a failed trim lost or rewrote the log")

        // The log was past its bound all along, and this is the proof: with the staging path usable
        // again the very next record trims it.
        staging.delete()
        val pastBound = file.length()
        MediaBlackBox.record(filesDir, "after-the-failure")
        assertTrue(
            file.length() < pastBound,
            "the log never passed its bound, so the failed staging write was never reached",
        )
    }

    /** The one durable log the black box keeps in [filesDir]. */
    private fun logFile(): File = filesDir.listFiles().orEmpty().single { it.isFile }

    /**
     * Records numbered lines until one of them makes the log shorter — the trim its size bound
     * triggers — and answers how large the log was just before that line, and which line it was.
     */
    private fun fillUntilTrimmed(): Pair<Long, Int> {
        MediaBlackBox.record(filesDir, line(0))
        val file = logFile()
        var index = 1
        while (index < MAX_LINES) {
            val before = file.length()
            MediaBlackBox.record(filesDir, line(index))
            if (file.length() < before) return before to index
            index++
        }
        fail("the log never passed its size bound in $MAX_LINES lines")
    }

    /** A line body of the fixed width the size arithmetic of these tests relies on. */
    private fun line(index: Int): String =
        "line-" + index.toString().padStart(4, '0') + "-" + "x".repeat(BODY_CHARS)

    /** The body of a recorded line, without the millisecond stamp in front of it. */
    private fun bodyOf(recorded: String): String = recorded.substringAfter(' ')

    /** The number [line] put into a recorded line, so the survivors can be read back in order. */
    private fun indexOfLine(recorded: String): Int =
        recorded.substringAfter("line-").substringBefore('-').toInt()

    private companion object {
        /** Width of a test line body; equal widths keep a trim's cut on a line boundary. */
        const val BODY_CHARS = 100

        /** Ceiling on the fill loop; a trim arrives at about a thousand lines of this width. */
        const val MAX_LINES = 4_000

        /** Bytes [MediaBlackBox.record] writes around a body: stamp, space and newline. */
        const val LINE_OVERHEAD = 15

        /** Longer than the log's whole bound on its own, so one record call crosses it. */
        const val UNTERMINATED_CHARS = 400_000

        /** A line with no terminator: half of it is what a trim of the newest half has to keep. */
        val UNTERMINATED_LINE = "y".repeat(UNTERMINATED_CHARS)

        /** A line written through [MediaBlackBox.record], which does terminate it. */
        val OVERSIZED_LINE = "q".repeat(UNTERMINATED_CHARS)

        /** Suffix the log stages a trim under; a directory there makes that write fail. */
        const val TRIM_SUFFIX = ".trim"
    }
}
