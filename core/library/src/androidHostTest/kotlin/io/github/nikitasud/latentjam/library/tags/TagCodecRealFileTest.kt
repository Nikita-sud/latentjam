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

    private companion object {
        /** A year no file in a real library is likely to carry already, so every matching original moves. */
        const val YEAR_EDIT = "1901"
    }

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
        // Reported, not failed: saving every field exactly as it reads should write nothing.
        val restateNotNoOp = ArrayList<String>()
        // Where the first MPEG/ADTS frame sits after an ID3v2 tag: at its end, after zeros, or nowhere near.
        val afterTag = HashMap<String, Int>()
        // Reported, not failed: the ID3v1 fields a real edit carries into the ID3v2 tag before dropping the trailer.
        val legacy = HashMap<String, Int>()
        val migratedFiles = ArrayList<String>()
        // Reported, not failed: files whose original-release date said the same year as the year, and
        // the year-only edit that moved it (OriginalDates).
        var originalWithDates = 0
        var originalSameYear = 0
        var originalMoved = 0

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
                TagVerification.verify(codec, inputSource, resultSource, edits).forEach {
                    failures += "${file.name} [$scenario]: ${it.check}\n  ${it.detail}"
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

            if (codec === Id3TagCodec) {
                afterTag.merge(audioAfterTag(original), 1, Int::plus)
                val migrated = migratedFrames(original)
                migrated.forEach { legacy.merge(it, 1, Int::plus) }
                if (migrated.isNotEmpty()) migratedFiles += "${file.name}: ${migrated.joinToString()}"
            }
            if (codec.plan(source, TagEdits()) !is WritePlan.NoChange) failures += "${file.name}: empty edit is not a no-op"
            before.title?.let {
                if (codec.plan(source, TagEdits(title = it)) !is WritePlan.NoChange) {
                    failures += "${file.name}: restating the title is not a no-op"
                }
            }
            val restated = TagEdits(
                title = before.title, artist = before.artist, album = before.album, genre = before.genre,
                year = before.year, albumArtist = before.albumArtist, lyrics = before.lyrics,
                trackNumber = before.trackNumber?.toString(), trackTotal = before.trackTotal?.toString(),
                discNumber = before.discNumber?.toString(), discTotal = before.discTotal?.toString(),
            )
            if (codec.plan(source, restated) !is WritePlan.NoChange) restateNotNoOp += "${file.name} (${before.format})"
            val edited = check("full", original, fullEdit)
            check("remove-cover", original, TagEdits(cover = CoverEdit.Remove))
            check("clear-fields", original, TagEdits(album = "", genre = "", year = ""))
            check("totals-only", original, TagEdits(trackTotal = "12", discTotal = "2"))
            if (before.originalDates.isNotEmpty()) originalWithDates++
            val year = before.year?.let(TagFacts::parseYear)
            if (year != null && before.originalDates.any { TagFacts.parseYear(it) == year }) originalSameYear++
            check("year", original, TagEdits(year = YEAR_EDIT))?.let { written ->
                if (codec.read(ByteArraySource(written)).originalDates != before.originalDates) originalMoved++
            }
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
        println("  restating every field is not a no-op: ${restateNotNoOp.size}")
        restateNotNoOp.take(20).forEach { println("    $it") }
        afterTag.toSortedMap().forEach { (where, count) -> println("  ID3 audio after tag: $where: $count") }
        println("  original date present: $originalWithDates, same year as the year: $originalSameYear, moved by year=$YEAR_EDIT: $originalMoved")
        println("  ID3v1 fields migrated: ${migratedFiles.size} files")
        legacy.toSortedMap().forEach { (id, count) -> println("    $id: $count") }
        migratedFiles.take(20).forEach { println("    $it") }
        paths.toSortedMap().forEach { (path, count) -> println("  $path: $count") }
        refusals.toSortedMap().forEach { (reason, names) ->
            println("  REFUSED $reason: ${names.size}")
            names.take(20).forEach { println("    $it") }
        }
        if (failures.isNotEmpty()) fail("${failures.size} failures:\n" + failures.joinToString("\n"))
    }

    /** The frames [Id3v1.migrate] adds to the file's own ID3v2 frames (none when it has no usable trailer). */
    private fun migratedFrames(file: ByteArray): List<String> {
        val tagLength = Id3Tags.tagLength(file) ?: return emptyList()
        val tag = (Id3Codec.parse(file) as? Id3Parse.Parsed)?.tag
        val frames = tag?.frames.orEmpty()
        val tail = file.copyOfRange(maxOf(tagLength, file.size - Id3v1.MAX_TRAILER_SIZE).coerceAtMost(file.size), file.size)
        val migrated = Id3v1.migrate(tail, tag?.version ?: Id3Version.V2_3, frames) ?: return emptyList()
        return migrated.drop(frames.size).map { it.id }
    }

    private fun audioAfterTag(file: ByteArray): String {
        val tag = Id3Tags.tagLength(file) ?: return "unreadable tag"
        if (tag == 0) return "untagged"
        val first = (tag until minOf(file.size, tag + 4096)).firstOrNull { file[it] != 0.toByte() } ?: return "only zeros within 4 KiB"
        val sync = Id3Tags.canPrependTag(file.copyOfRange(first, minOf(file.size, first + 7)))
        return when {
            !sync -> "no MPEG/ADTS frame"
            first == tag -> "directly"
            else -> "after zeros"
        }
    }
}
