/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Every codec against ACTUAL music. Point `TAG_REAL_FILES` at a directory (searched recursively);
 * absent, this reports as skipped. With `TAG_REAL_FILES_OUT` set, the full-edit result of every
 * file is written there as `<index>.<ext>` next to `<index>.orig.<ext>` for the external checks.
 */
class TagCodecRealFileTest {

    private val extensions = setOf("mp3", "flac", "opus", "ogg", "oga", "m4a", "mp4", "aac")

    private fun files(): List<File>? =
        System.getenv("TAG_REAL_FILES")?.let(::File)?.takeIf { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile && it.extension.lowercase() in extensions }
            ?.sortedBy { it.path }?.toList()?.takeIf { it.isNotEmpty() }

    private val cover = CoverEdit.Replace(TestImages.jpeg(600, 600, filler = 40_000), ImageProbe.JPEG)

    private val fullEdit = TagEdits(
        title = "Проверка テスト",
        albumArtist = "Various Artists",
        trackNumber = "7",
        trackTotal = "12",
        discNumber = "1",
        lyrics = "Первая строка\nSecond line\n三行目",
        cover = cover,
    )

    private class Outcome(val file: File, val scenario: String, val result: String)

    @Test
    fun everyFileSurvivesEveryScenario() {
        val files = files() ?: run {
            println("SKIP: set TAG_REAL_FILES to a directory of music files")
            return
        }
        val out = System.getenv("TAG_REAL_FILES_OUT")?.let(::File)?.also { it.mkdirs() }
        val failures = ArrayList<String>()
        val refusals = HashMap<String, MutableList<String>>()
        val paths = HashMap<String, Int>()
        // Reported, not failed: an Opus header with binary data to preserve can never gain padding.
        val secondNotInPlace = ArrayList<String>()

        files.forEachIndexed { index, file ->
            val original = file.readBytes()
            val source = ByteArraySource(original)
            val codec = TagCodecs.forSource(source)
            if (codec == null) {
                refusals.getOrPut("UNSUPPORTED_FORMAT") { ArrayList() } += file.name
                return@forEachIndexed
            }
            val before = codec.read(source)
            before.refusal?.let {
                refusals.getOrPut(it.name) { ArrayList() } += file.name
                return@forEachIndexed
            }

            fun check(scenario: String, input: ByteArray, edits: TagEdits): ByteArray? {
                val inputSource = ByteArraySource(input)
                val start = codec.read(inputSource)
                val plan = codec.plan(inputSource, edits)
                paths.merge("${start.format}/$scenario/${plan::class.simpleName}", 1, Int::plus)
                if (plan is WritePlan.Refused) {
                    refusals.getOrPut(plan.reason.name) { ArrayList() } += "${file.name} [$scenario]"
                    return null
                }
                val result = try {
                    WritePlans.applyInMemory(input, plan)!!
                } catch (e: StreamRefusedException) {
                    refusals.getOrPut(e.reason.name) { ArrayList() } += "${file.name} [$scenario, mid-stream]"
                    return null
                }
                val resultSource = ByteArraySource(result)
                val after = codec.read(resultSource)
                val expected = start.expectedAfter(edits)
                val nextCover = if (edits.cover == CoverEdit.Remove) null else after.nextCover
                val versionOk = after.version == start.version || (start.version == "none" && after.version == "ID3v2.3")
                if (!versionOk || after.copy(version = expected.version, nextCover = nextCover) != expected) {
                    failures += "${file.name} [$scenario]: read-back\n  expected $expected\n  actual   $after"
                }
                if (codec.audioDigest(inputSource) != codec.audioDigest(resultSource)) {
                    failures += "${file.name} [$scenario]: AUDIO CHANGED"
                }
                if (codec.inventory(inputSource) != codec.inventory(resultSource)) {
                    failures += "${file.name} [$scenario]: unmanaged data changed\n  ${codec.inventory(inputSource)}\n  ${codec.inventory(resultSource)}"
                }
                if (codec.plan(resultSource, edits) !is WritePlan.NoChange) {
                    failures += "${file.name} [$scenario]: repeating the same edit is not a no-op"
                }
                if (start.format == TagFormat.OPUS || start.format == TagFormat.VORBIS) {
                    var offset = 0L
                    var nextSequence = -1
                    while (offset < result.size) {
                        val page = OggPages.readAt(resultSource, offset)
                        if (page == null || !page.crcValid) {
                            failures += "${file.name} [$scenario]: broken Ogg page at $offset"
                            break
                        }
                        if (!page.isBeginning && nextSequence >= 0 && page.sequence != nextSequence) {
                            failures += "${file.name} [$scenario]: Ogg sequence gap at $offset"
                            break
                        }
                        nextSequence = page.sequence + 1
                        offset += page.size
                    }
                }
                return result
            }

            if (codec.plan(source, TagEdits()) !is WritePlan.NoChange) failures += "${file.name}: empty edit is not a no-op"
            before.title?.let {
                if (codec.plan(source, TagEdits(title = it)) !is WritePlan.NoChange) {
                    failures += "${file.name}: restating the title is not a no-op"
                }
            }
            val edited = check("full", original, fullEdit)
            check("remove-cover", original, TagEdits(cover = CoverEdit.Remove))
            check("clear-fields", original, TagEdits(album = "", genre = "", year = ""))
            if (edited != null) {
                val second = check("second", edited, TagEdits(title = "Second"))
                if (second != null && codec.plan(ByteArraySource(edited), TagEdits(title = "Second")) !is WritePlan.InPlacePatch) {
                    secondNotInPlace += file.name
                }
                out?.let {
                    File(it, "$index.orig.${file.extension}").writeBytes(original)
                    File(it, "$index.${file.extension}").writeBytes(edited)
                }
            }
        }

        println("CORPUS: ${files.size} files")
        println("  second edit not in place: ${secondNotInPlace.size}")
        secondNotInPlace.take(20).forEach { println("    $it") }
        paths.toSortedMap().forEach { (path, count) -> println("  $path: $count") }
        refusals.toSortedMap().forEach { (reason, names) ->
            println("  REFUSED $reason: ${names.size}")
            names.take(20).forEach { println("    $it") }
        }
        if (failures.isNotEmpty()) fail("${failures.size} failures:\n" + failures.joinToString("\n"))
    }
}
