/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the tag writer against ACTUAL music files.
 *
 * The synthetic fixtures elsewhere prove the codec agrees with the author's reading of the spec.
 * They cannot prove it agrees with what encoders in the wild actually emit — and tags in the wild
 * are full of oddities no specification predicts. This is the test that stands between the writer
 * and someone's library.
 *
 * Point `ID3_REAL_FILES` at a directory of .mp3 files; absent, this reports as skipped rather than
 * failing, since real music cannot be committed to the repository.
 */
class Id3RealFileTest {

    private fun files(): List<File>? =
        System.getenv("ID3_REAL_FILES")
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.takeIf { it.isNotEmpty() }

    @Test
    fun `real files round-trip without losing anything`() {
        val files = files() ?: run {
            println("SKIP: set ID3_REAL_FILES to a directory of .mp3 files")
            return
        }

        val failures = mutableListOf<String>()
        for (file in files) {
            val original = file.readBytes()
            val before = Id3Tags.read(original)
            if (before == null) {
                // A refusal is a valid outcome, but we want to know which files hit it and why.
                println("${file.name}: no readable tag (${Id3Tags.refusalOf(original)})")
                continue
            }

            // 1. No-op edit must not disturb the frames that are already there.
            val untouched = updateId3Tag(original, TagEdits())
            if (untouched != null) {
                val after = Id3Tags.read(untouched)
                if (after?.frameIds?.toSet() != before.frameIds.toSet()) {
                    failures += "${file.name}: no-op edit changed frames " +
                        "${before.frameIds} -> ${after?.frameIds}"
                }
            }

            // 2. A real edit must land, and must not cost any other frame — album art above all.
            val edited = updateId3Tag(original, TagEdits(title = "Проверка テスト"))
            if (edited == null) {
                failures += "${file.name}: refused a plain title edit (${Id3Tags.refusalOf(original)})"
                continue
            }
            val after = Id3Tags.read(edited)
            if (after == null) {
                failures += "${file.name}: tag unreadable after edit"
                continue
            }
            if (after.title != "Проверка テスト") {
                failures += "${file.name}: title did not survive: ${after.title}"
            }
            val lost = before.frameIds.toSet() - after.frameIds.toSet()
            if (lost.isNotEmpty()) failures += "${file.name}: LOST FRAMES $lost"

            if (before.artist != null && after.artist != before.artist) {
                failures += "${file.name}: artist changed by a title-only edit"
            }

            // 3. The audio itself must be untouched. This is the one that matters most: a writer
            //    that corrupts the stream is worse than one that refuses. The ID3v1 trailer is
            //    deliberately not part of "the audio" — see Id3v1 for why an edit drops it.
            val trailer = Id3v1.trailerLength(original)
            val audioBefore = original.copyOfRange(before.totalLength, original.size - trailer)
            val audioAfter = edited.copyOfRange(after.totalLength, edited.size)
            if (!audioBefore.contentEquals(audioAfter)) {
                failures += "${file.name}: AUDIO STREAM ALTERED"
            }
        }

        assertTrue(failures.isEmpty(), "real-file failures:\n" + failures.joinToString("\n"))
        println("id3: ${files.size} real files round-tripped with frames and audio intact")
    }

    @Test
    fun `a real file is never silently truncated`() {
        val files = files() ?: return
        for (file in files) {
            val original = file.readBytes()
            val edited = updateId3Tag(original, TagEdits(title = "x")) ?: continue
            val before = Id3Tags.read(original) ?: continue
            val after = Id3Tags.read(edited) ?: continue
            assertEquals(
                original.size - before.totalLength - Id3v1.trailerLength(original),
                edited.size - after.totalLength,
                "${file.name}: audio length changed",
            )
        }
    }

    /**
     * The ID3v1 trailer is the one thing an edit deliberately deletes, so it gets its own real-file
     * check: an edit must remove it, and a no-op must not — the no-op is what makes it safe to run
     * this writer over a library speculatively.
     */
    @Test
    fun `the id3v1 trailer is dropped by an edit and left alone by a no-op`() {
        val files = files() ?: run {
            println("SKIP: set ID3_REAL_FILES to a directory of .mp3 files")
            return
        }

        val failures = mutableListOf<String>()
        var withTrailer = 0
        var mojibake = 0
        for (file in files) {
            val original = file.readBytes()
            val trailer = Id3v1.trailerLength(original)
            if (trailer == 0) continue
            withTrailer++
            // How often the trailer is already a lie, which is the whole argument for removing it.
            val v1Title = original
                .copyOfRange(original.size - trailer + 3, original.size - trailer + 33)
                .toString(Charsets.ISO_8859_1)
                .trimEnd('\u0000', ' ')
            if (v1Title.contains('?')) mojibake++

            val edited = updateId3Tag(original, TagEdits(title = "Проверка テスト"))
            if (edited == null) {
                // Refusing is a valid outcome; it is reported by the round-trip test above.
                continue
            }
            if (Id3v1.trailerLength(edited) != 0) failures += "${file.name}: trailer survived an edit"
            if (edited.size != original.size - trailer + tagGrowth(original, edited)) {
                // Guards against dropping more (or less) than the trailer.
                failures += "${file.name}: unexpected size change"
            }

            val untouched = updateId3Tag(original, TagEdits())
            if (untouched != null && !untouched.contentEquals(original)) {
                failures += "${file.name}: a no-op edit was not byte-identical"
            }
        }

        assertTrue(failures.isEmpty(), "id3v1 failures:\n" + failures.joinToString("\n"))
        println("id3v1: $withTrailer of ${files.size} real files carried a trailer, $mojibake already mojibake")
    }

    /** How much the ID3v2 tag itself grew, so the size check isolates the trailer. */
    private fun tagGrowth(original: ByteArray, edited: ByteArray): Int {
        val before = Id3Tags.read(original)?.totalLength ?: 0
        val after = Id3Tags.read(edited)?.totalLength ?: 0
        return after - before
    }
}
