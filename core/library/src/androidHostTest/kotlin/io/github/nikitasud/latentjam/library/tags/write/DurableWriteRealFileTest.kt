/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteArraySource
import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.ImageProbe
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagVerification
import io.github.nikitasud.latentjam.library.tags.TestImages
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The durable writer against ACTUAL music. Point `TAG_REAL_FILES` at a directory (searched
 * recursively, never modified) and `TAG_REAL_FILES_OUT` at a scratch directory; absent either, this
 * reports as skipped. Each file is copied to `<index>.<ext>` (with the original kept as
 * `<index>.orig.<ext>` for the external PCM check), saved with the full edit of
 * [io.github.nikitasud.latentjam.library.tags.TagCodecRealFileTest], and verified. A file that is
 * refused or unchanged leaves no copy behind.
 */
class DurableWriteRealFileTest {

    private val extensions = setOf("mp3", "flac", "opus", "ogg", "oga", "m4a", "mp4", "aac")

    private fun files(): List<File>? =
        System.getenv("TAG_REAL_FILES")?.let(::File)?.takeIf { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile && it.extension.lowercase() in extensions }
            ?.sortedBy { it.path }?.toList()?.takeIf { it.isNotEmpty() }

    private val cover = CoverEdit.Replace(TestImages.jpeg(600, 600, filler = 40_000), ImageProbe.JPEG)

    private val fullEdit = TagEdits(
        title = "Проверка テスト",
        artist = "Первый; Second",
        genre = "Rock",
        year = "2001-05-03",
        albumArtist = "Various Artists",
        trackNumber = "7",
        trackTotal = "12",
        discNumber = "1",
        lyrics = "Первая строка\nSecond line\n三行目",
        cover = cover,
    )

    @Test
    fun everyFileIsSavedDurably() {
        val files = files() ?: run {
            println("SKIP: set TAG_REAL_FILES to a directory of music files")
            return
        }
        val out = System.getenv("TAG_REAL_FILES_OUT")?.let(::File)?.also { it.mkdirs() } ?: run {
            println("SKIP: set TAG_REAL_FILES_OUT to a scratch directory")
            return
        }
        val store = FileRecoveryDirectory(File(out, "store")) {}
        var writes = 0
        val writer = DurableWriter(store, { "w${++writes}" })

        val failures = ArrayList<String>()
        val counts = sortedMapOf<String, Int>()
        val refusals = sortedMapOf<String, MutableList<String>>()
        val inPlaceNanos = ArrayList<Long>()
        val rewriteNanos = ArrayList<Long>()

        files.forEachIndexed { index, file ->
            val original = file.readBytes()
            val copy = File(out, "$index.${file.extension}").apply { writeBytes(original) }
            val start = System.nanoTime()
            val result = try {
                ChannelTargetFile.open(copy).use { writer.write(copy.path, it, fullEdit, out.usableSpace) }
            } catch (e: Exception) {
                failures += "${file.name}: the writer threw $e"
                return@forEachIndexed
            }
            val nanos = System.nanoTime() - start
            var keep = false
            when (result) {
                is WriteResult.Saved -> {
                    counts.merge(if (result.rewritten) "Saved (rewrite)" else "Saved (in place)", 1, Int::plus)
                    (if (result.rewritten) rewriteNanos else inPlaceNanos) += nanos
                    val codec = TagCodecs.forSource(ByteArraySource(original))
                    if (codec == null) {
                        failures += "${file.name}: saved, yet no codec reads the original"
                    } else {
                        ChannelTargetFile.open(copy).use { after ->
                            TagVerification.verify(codec, ByteArraySource(original), after, fullEdit).forEach {
                                failures += "${file.name}: ${it.check}\n  ${it.detail}"
                            }
                            if (after.length != result.newLength) failures += "${file.name}: newLength ${result.newLength} but the file is ${after.length}"
                        }
                        keep = true
                    }
                    if (store.names().isNotEmpty()) failures += "${file.name}: the store is not empty after a save: ${store.names()}"
                }
                is WriteResult.Refused -> {
                    counts.merge("Refused", 1, Int::plus)
                    refusals.getOrPut(result.reason.name) { ArrayList() } += file.name
                    if (!copy.readBytes().contentEquals(original)) failures += "${file.name}: refused, yet the file changed"
                }
                WriteResult.NoChange -> {
                    counts.merge("NoChange", 1, Int::plus)
                    if (!copy.readBytes().contentEquals(original)) failures += "${file.name}: no change, yet the file changed"
                }
                else -> failures += "${file.name}: $result"
            }
            if (keep) File(out, "$index.orig.${file.extension}").writeBytes(original) else copy.delete()
        }
        if (store.names().isEmpty()) File(out, "store").delete()

        println("DURABLE CORPUS: ${files.size} files")
        counts.forEach { (outcome, count) -> println("  $outcome: $count") }
        refusals.forEach { (reason, names) ->
            println("  REFUSED $reason: ${names.size}")
            names.take(20).forEach { println("    $it") }
        }
        println("  save time in place: ${describe(inPlaceNanos)}")
        println("  save time rewrite:  ${describe(rewriteNanos)}")
        println("  failures: ${failures.size}")
        if (failures.isNotEmpty()) fail("${failures.size} failures:\n" + failures.joinToString("\n"))
    }

    private fun describe(nanos: List<Long>): String {
        if (nanos.isEmpty()) return "none"
        val sorted = nanos.sorted()
        fun at(fraction: Double) = sorted[minOf(sorted.size - 1, (sorted.size * fraction).toInt())] / 1e6
        return "n=${sorted.size} median ${"%.1f".format(at(0.5))} ms, p95 ${"%.1f".format(at(0.95))} ms, max ${"%.1f".format(sorted.last() / 1e6)} ms"
    }
}
