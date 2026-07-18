/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.Id3TestTags.artFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.commentFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.latin1Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.mp3Payload
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.utf16Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.utf8Body
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class Id3TagsTest {

    private val versions = listOf(3, 4)

    // ---------------------------------------------------------------- preservation

    @Test
    fun noOpRoundTripIsByteIdentical() {
        for (major in versions) {
            val file = Id3TestTags.build(
                major = major,
                frames = listOf(
                    TestFrame("TIT2", latin1Body("Original")),
                    artFrame(size = 600),
                    commentFrame("ripped 2004"),
                    TestFrame("TXXX", latin1Body("replaygain_track_gain") + latin1Body("-6.34 dB")),
                ),
                padding = 64,
            ) + mp3Payload()

            val out = assertNotNull(updateId3Tag(file, TagEdits()), "v2.$major")
            assertContentEquals(file, out, "no-op rewrite must not touch a single byte (v2.$major)")
        }
    }

    @Test
    fun editingTitleLeavesEveryOtherFrameByteIdentical() {
        for (major in versions) {
            val art = artFrame(size = 5000)
            val comment = commentFrame("do not lose me")
            val file = Id3TestTags.build(
                major = major,
                frames = listOf(TestFrame("TIT2", latin1Body("Old")), art, comment),
            ) + mp3Payload()

            val out = assertNotNull(updateId3Tag(file, TagEdits(title = "New")), "v2.$major")

            assertContentEquals(art.body, Id3TestTags.frameBody(out, "APIC"), "album art (v2.$major)")
            assertContentEquals(comment.body, Id3TestTags.frameBody(out, "COMM"), "comment (v2.$major)")
            assertEquals("New", assertNotNull(Id3Tags.read(out)).title)
        }
    }

    @Test
    fun unknownFrameOrderIsPreserved() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(
                commentFrame("first"),
                TestFrame("TIT2", latin1Body("Old")),
                artFrame(size = 200),
                TestFrame("WXXX", latin1Body("http://example.invalid")),
            ),
        ) + mp3Payload()

        val out = assertNotNull(updateId3Tag(file, TagEdits(title = "New")))
        assertEquals(listOf("COMM", "TIT2", "APIC", "WXXX"), assertNotNull(Id3Tags.read(out)).frameIds)
    }

    @Test
    fun duplicateManagedFramesCollapseIntoOne() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(
                TestFrame("TIT2", latin1Body("First")),
                artFrame(size = 100),
                TestFrame("TIT2", latin1Body("Second")),
            ),
        ) + mp3Payload()

        val info = assertNotNull(Id3Tags.read(assertNotNull(updateId3Tag(file, TagEdits(title = "Only")))))
        assertEquals(listOf("TIT2", "APIC"), info.frameIds)
        assertEquals("Only", info.title)
    }

    // ---------------------------------------------------------------- text encoding

    @Test
    fun cyrillicAndJapaneseSurviveAWriteReadCycleInBothVersions() {
        val titles = listOf(
            "Кино — Группа крови",
            "君の名は。",
            "初音ミク",
            "Ce seară",
            "Пикник",
        )
        for (major in versions) {
            for (title in titles) {
                val file = Id3TestTags.build(
                    major = major,
                    frames = listOf(TestFrame("TIT2", latin1Body("placeholder")), artFrame(size = 300)),
                ) + mp3Payload()

                val out = assertNotNull(
                    updateId3Tag(file, TagEdits(title = title, artist = title, album = title)),
                    "v2.$major $title",
                )
                val info = assertNotNull(Id3Tags.read(out))
                assertEquals(title, info.title, "title v2.$major")
                assertEquals(title, info.artist, "artist v2.$major")
                assertEquals(title, info.album, "album v2.$major")
            }
        }
    }

    @Test
    fun v23NeverWritesUtf8AndV24Does() {
        val title = "Группа крови"

        val v23 = assertNotNull(
            updateId3Tag(Id3TestTags.build(3, emptyList()) + mp3Payload(), TagEdits(title = title)),
        )
        // Encoding 3 (UTF-8) is not a legal ID3v2.3 encoding: a conforming reader
        // decodes those bytes as ISO-8859-1 and the title turns to mojibake.
        assertEquals(
            Id3Text.UTF_16_WITH_BOM,
            assertNotNull(Id3TestTags.frameBody(v23, "TIT2"))[0].toInt(),
            "v2.3 non-Latin text must go out as UTF-16",
        )

        val v24 = assertNotNull(
            updateId3Tag(Id3TestTags.build(4, emptyList()) + mp3Payload(), TagEdits(title = title)),
        )
        assertEquals(
            Id3Text.UTF_8,
            assertNotNull(Id3TestTags.frameBody(v24, "TIT2"))[0].toInt(),
            "v2.4 non-Latin text should go out as UTF-8",
        )
    }

    @Test
    fun asciiTextUsesTheCompactLatin1Encoding() {
        for (major in versions) {
            val out = assertNotNull(
                updateId3Tag(Id3TestTags.build(major, emptyList()) + mp3Payload(), TagEdits(title = "Plain Title")),
            )
            val body = assertNotNull(Id3TestTags.frameBody(out, "TIT2"))
            assertEquals(Id3Text.ISO_8859_1, body[0].toInt(), "v2.$major")
            assertEquals("Plain Title", assertNotNull(Id3Tags.read(out)).title)
        }
    }

    @Test
    fun latin1AccentsRoundTripThroughTheSingleByteEncoding() {
        val title = "Björk — Jóga"
        for (major in versions) {
            val out = assertNotNull(
                updateId3Tag(Id3TestTags.build(major, emptyList()) + mp3Payload(), TagEdits(title = title)),
            )
            assertEquals(title, assertNotNull(Id3Tags.read(out)).title, "v2.$major")
        }
    }

    @Test
    fun existingUtf16AndUtf8FramesAreReadCorrectly() {
        val title = "Всё идёт по плану"
        assertEquals(
            title,
            assertNotNull(Id3Tags.read(Id3TestTags.build(3, listOf(TestFrame("TIT2", utf16Body(title)))))).title,
        )
        assertEquals(
            title,
            assertNotNull(Id3Tags.read(Id3TestTags.build(4, listOf(TestFrame("TIT2", utf8Body(title)))))).title,
        )
        assertEquals(
            title,
            assertNotNull(
                Id3Tags.read(Id3TestTags.build(4, listOf(TestFrame("TIT2", Id3TestTags.utf16beBody(title))))),
            ).title,
        )
    }

    @Test
    fun emojiAndSurrogatePairsSurvive() {
        val title = "Song 🎵 Two"
        for (major in versions) {
            val out = assertNotNull(
                updateId3Tag(Id3TestTags.build(major, emptyList()) + mp3Payload(), TagEdits(title = title)),
            )
            assertEquals(title, assertNotNull(Id3Tags.read(out)).title, "v2.$major")
        }
    }

    // ---------------------------------------------------------------- frame sizes

    @Test
    fun framesLongerThan127BytesParseUnderBothSizeConventions() {
        // Under 128 the two encodings coincide, so only a longer frame can tell
        // a syncsafe reader from a plain one.
        for (major in versions) {
            val file = Id3TestTags.build(
                major = major,
                frames = listOf(artFrame(size = 300), TestFrame("TIT2", latin1Body("After the art"))),
            ) + mp3Payload()

            val info = assertNotNull(Id3Tags.read(file), "v2.$major")
            assertEquals(listOf("APIC", "TIT2"), info.frameIds, "v2.$major")
            assertEquals("After the art", info.title, "v2.$major")
        }
    }

    @Test
    fun v24TagWrittenWithPlainSizesIsStillRecovered() {
        // A large family of taggers writes plain big-endian sizes into 2.4 tags.
        // 300 bytes is the nasty case: read as syncsafe it yields 172, which
        // parses without error and lands in the middle of the frame body.
        val file = Id3TestTags.build(
            major = 4,
            frames = listOf(
                TestFrame("APIC", ByteArray(300) { 1 }),
                TestFrame("TIT2", latin1Body("Recovered")),
            ),
            sizes = FrameSizes.PLAIN,
        ) + mp3Payload()

        val info = assertNotNull(Id3Tags.read(file), "plain-sized 2.4 tag should still be readable")
        assertEquals(listOf("APIC", "TIT2"), info.frameIds)
        assertEquals("Recovered", info.title)
    }

    @Test
    fun aRewrittenV24TagUsesSyncsafeFrameSizes() {
        val file = Id3TestTags.build(
            major = 4,
            frames = listOf(TestFrame("APIC", ByteArray(300) { 1 }), TestFrame("TIT2", latin1Body("x"))),
            sizes = FrameSizes.PLAIN,
        ) + mp3Payload()

        val out = assertNotNull(updateId3Tag(file, TagEdits(title = "y")))
        assertContentEquals(
            Id3TestTags.syncsafe(300),
            Id3TestTags.rawSizeFieldOf(out, "APIC"),
            "the rewrite must repair the size encoding, not copy the wrong one",
        )
        assertNotEquals(
            Id3TestTags.plain(300).toList(),
            Id3TestTags.rawSizeFieldOf(out, "APIC").toList(),
        )
    }

    @Test
    fun aRewrittenV23TagUsesPlainFrameSizes() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("APIC", ByteArray(300) { 1 }), TestFrame("TIT2", latin1Body("x"))),
            sizes = FrameSizes.SYNCSAFE,
        ) + mp3Payload()

        val out = assertNotNull(updateId3Tag(file, TagEdits(title = "y")))
        assertContentEquals(Id3TestTags.plain(300), Id3TestTags.rawSizeFieldOf(out, "APIC"))
    }

    @Test
    fun tagSizeFieldIsAlwaysSyncsafe() {
        for (major in versions) {
            val out = assertNotNull(
                updateId3Tag(
                    Id3TestTags.build(major, listOf(artFrame(size = 40_000))) + mp3Payload(),
                    TagEdits(title = "Big"),
                ),
            )
            // Every byte of the tag size field has its high bit clear, in both
            // versions — unlike frame sizes, this one is never plain.
            for (i in 6 until 10) {
                assertEquals(0, out[i].toInt() and 0x80, "tag size byte $i (v2.$major)")
            }
            assertEquals(out.size - mp3Payload().size, assertNotNull(Id3Tags.tagLength(out)))
        }
    }

    // ---------------------------------------------------------------- growth

    @Test
    fun growingWithinPaddingKeepsTheTagFootprint() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TIT2", latin1Body("ab"))),
            padding = 512,
        ) + mp3Payload()
        val originalTagLength = assertNotNull(Id3Tags.tagLength(file))

        val update = assertNotNull(Id3Tags.buildUpdate(file, TagEdits(title = "a considerably longer title")))
        assertTrue(update.isSameLength, "padding should absorb the growth")
        assertEquals(originalTagLength, update.tag.size)

        val out = assertNotNull(updateId3Tag(file, TagEdits(title = "a considerably longer title")))
        assertEquals(file.size, out.size)
        assertContentEquals(mp3Payload(), out.copyOfRange(originalTagLength, out.size), "audio must not move")
    }

    @Test
    fun growingBeyondPaddingGrowsTheTagAndKeepsTheAudio() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TIT2", latin1Body("ab")), artFrame(size = 2000)),
            padding = 0,
        ) + mp3Payload()
        val originalTagLength = assertNotNull(Id3Tags.tagLength(file))

        val title = "T".repeat(5000)
        val update = assertNotNull(Id3Tags.buildUpdate(file, TagEdits(title = title)))
        assertTrue(update.tag.size > originalTagLength, "tag should have grown")
        assertEquals(originalTagLength, update.replacedLength)

        val out = assertNotNull(updateId3Tag(file, TagEdits(title = title)))
        val info = assertNotNull(Id3Tags.read(out))
        assertEquals(title, info.title)
        assertContentEquals(mp3Payload(), out.copyOfRange(info.totalLength, out.size), "audio must survive intact")
        assertContentEquals(artFrame(size = 2000).body, Id3TestTags.frameBody(out, "APIC"))
    }

    @Test
    fun shrinkingKeepsTheFootprintAndPadsTheRemainder() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TIT2", latin1Body("a very long title indeed"))),
            padding = 0,
        ) + mp3Payload()
        val originalTagLength = assertNotNull(Id3Tags.tagLength(file))

        val out = assertNotNull(updateId3Tag(file, TagEdits(title = "s")))
        assertEquals(file.size, out.size, "a shrunk tag keeps its footprint so the audio never moves")
        assertEquals("s", assertNotNull(Id3Tags.read(out)).title)
        assertContentEquals(mp3Payload(), out.copyOfRange(originalTagLength, out.size))
    }

    @Test
    fun aNewTagIsCreatedWhenTheFileHasNone() {
        val audio = mp3Payload()
        val out = assertNotNull(
            updateId3Tag(audio, TagEdits(title = "Заголовок", artist = "Artist", album = "Album", year = "2001")),
        )

        val info = assertNotNull(Id3Tags.read(out))
        assertEquals(Id3Version.V2_3, info.version)
        assertEquals("Заголовок", info.title)
        assertEquals("Artist", info.artist)
        assertEquals("2001", info.year)
        assertContentEquals(audio, out.copyOfRange(info.totalLength, out.size), "audio must be untouched")
        assertEquals(0, assertNotNull(Id3Tags.buildUpdate(audio, TagEdits(title = "x"))).replacedLength)
    }

    @Test
    fun aNewTagCanBeCreatedAtV24OnRequest() {
        val out = assertNotNull(
            updateId3Tag(mp3Payload(), TagEdits(title = "君の名は。"), newTagVersion = Id3Version.V2_4),
        )
        val info = assertNotNull(Id3Tags.read(out))
        assertEquals(Id3Version.V2_4, info.version)
        assertEquals("君の名は。", info.title)
    }

    // ---------------------------------------------------------------- years

    @Test
    fun yearGoesToTyerOnV23AndTdrcOnV24() {
        val v23 = assertNotNull(updateId3Tag(Id3TestTags.build(3, emptyList()) + mp3Payload(), TagEdits(year = "2001")))
        assertEquals(listOf("TYER"), assertNotNull(Id3Tags.read(v23)).frameIds)

        val v24 = assertNotNull(updateId3Tag(Id3TestTags.build(4, emptyList()) + mp3Payload(), TagEdits(year = "2001")))
        assertEquals(listOf("TDRC"), assertNotNull(Id3Tags.read(v24)).frameIds)

        assertEquals("2001", assertNotNull(Id3Tags.read(v23)).year)
        assertEquals("2001", assertNotNull(Id3Tags.read(v24)).year)
    }

    @Test
    fun theOtherVersionsYearFrameIsRemovedSoTheyCannotDisagree() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TYER", latin1Body("1999")), TestFrame("TDRC", latin1Body("1999-01-01"))),
        ) + mp3Payload()

        val info = assertNotNull(Id3Tags.read(assertNotNull(updateId3Tag(file, TagEdits(year = "2001")))))
        assertEquals(listOf("TYER"), info.frameIds)
        assertEquals("2001", info.year)
    }

    @Test
    fun aFullTimestampIsNarrowedForTyerButKeptForTdrc() {
        val v23 = assertNotNull(
            updateId3Tag(Id3TestTags.build(3, emptyList()) + mp3Payload(), TagEdits(year = "2001-05-03")),
        )
        assertEquals("2001", assertNotNull(Id3Tags.read(v23)).year, "TYER is defined as exactly four characters")

        val v24 = assertNotNull(
            updateId3Tag(Id3TestTags.build(4, emptyList()) + mp3Payload(), TagEdits(year = "2001-05-03")),
        )
        assertEquals("2001-05-03", assertNotNull(Id3Tags.read(v24)).year)
    }

    // ---------------------------------------------------------------- removal

    @Test
    fun anEmptyStringRemovesAFrameAndNullLeavesItAlone() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(
                TestFrame("TIT2", latin1Body("Title")),
                TestFrame("TPE1", latin1Body("Artist")),
                artFrame(size = 100),
            ),
        ) + mp3Payload()

        val cleared = assertNotNull(Id3Tags.read(assertNotNull(updateId3Tag(file, TagEdits(title = "")))))
        assertEquals(listOf("TPE1", "APIC"), cleared.frameIds, "an empty edit removes the frame")
        assertNull(cleared.title)
        assertEquals("Artist", cleared.artist, "an untouched field must survive")

        val untouched = assertNotNull(Id3Tags.read(assertNotNull(updateId3Tag(file, TagEdits()))))
        assertEquals(listOf("TIT2", "TPE1", "APIC"), untouched.frameIds)
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun aTruncatedTagIsRefusedRatherThanGuessedAt() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(artFrame(size = 4000)),
        ) + mp3Payload()

        val short = file.copyOfRange(0, 200)
        assertNull(updateId3Tag(short, TagEdits(title = "x")))
        assertEquals(Id3Refusal.TRUNCATED, Id3Tags.refusalOf(short))
        assertNull(Id3Tags.read(short))
    }

    @Test
    fun aTagWhoseFramesDoNotAddUpIsRefusedWholesale() {
        // Garbage where a frame header should be. Stopping here and keeping the
        // frames read so far would silently delete the album art on write.
        val junk = ByteArray(40) { 0x7F }
        val body = ArrayList<Byte>()
        body.addAll("TIT2".map { it.code.toByte() })
        body.addAll(Id3TestTags.plain(4).toList())
        body.add(0)
        body.add(0)
        body.addAll(latin1Body("ok").toList().take(4))
        body.addAll(junk.toList())
        val tag = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
        ) + Id3TestTags.syncsafe(body.size) + body.toByteArray()

        assertNull(updateId3Tag(tag + mp3Payload(), TagEdits(title = "x")))
        assertEquals(Id3Refusal.MALFORMED_FRAMES, Id3Tags.refusalOf(tag + mp3Payload()))
    }

    @Test
    fun nonZeroBytesAfterTheFramesAreRefusedNotTreatedAsPadding() {
        val frames = listOf(TestFrame("TIT2", latin1Body("Title")))
        val good = Id3TestTags.build(3, frames, padding = 32)
        val poisoned = good.copyOf()
        poisoned[poisoned.size - 5] = 0x41 // a stray byte inside what claims to be padding
        assertEquals(Id3Refusal.MALFORMED_FRAMES, Id3Tags.refusalOf(poisoned + mp3Payload()))
        assertNull(updateId3Tag(poisoned + mp3Payload(), TagEdits(title = "x")))
    }

    @Test
    fun aTagBodyThatStartsWithPaddingButIsNotCleanIsRefused() {
        // The frame walk's very first step has no preceding frame to bound it,
        // so this is the only path where the padding check itself is what stands
        // between a damaged tag and a rewrite that drops whatever follows.
        val poisoned = Id3TestTags.build(3, emptyList(), padding = 32).copyOf()
        poisoned[poisoned.size - 5] = 0x41
        assertEquals(Id3Refusal.MALFORMED_FRAMES, Id3Tags.refusalOf(poisoned + mp3Payload()))
        assertNull(updateId3Tag(poisoned + mp3Payload(), TagEdits(title = "x")))

        // Clean padding with no frames at all is perfectly legal, though.
        val clean = Id3TestTags.build(3, emptyList(), padding = 32)
        assertNull(Id3Tags.refusalOf(clean + mp3Payload()))
        assertEquals("x", assertNotNull(Id3Tags.read(assertNotNull(updateId3Tag(clean + mp3Payload(), TagEdits(title = "x"))))).title)
    }

    @Test
    fun aTagBodyTooShortToHoldAFrameHeaderMustStillBeClean() {
        // Fewer than ten bytes left and none of them zero: not a frame, not
        // padding, so the tag is not something this codec can account for.
        val junk = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) +
            Id3TestTags.syncsafe(junk.size) + junk
        assertEquals(Id3Refusal.MALFORMED_FRAMES, Id3Tags.refusalOf(tag + mp3Payload()))
        assertNull(updateId3Tag(tag + mp3Payload(), TagEdits(title = "x")))
    }

    @Test
    fun unsynchronisedTagsAreRefusedRatherThanMangled() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TIT2", latin1Body("Title")), artFrame(size = 300)),
            headerFlags = 0x80,
        ) + mp3Payload()

        assertEquals(Id3Refusal.UNSYNCHRONISED, Id3Tags.refusalOf(file))
        assertNull(updateId3Tag(file, TagEdits(title = "x")), "refusing is correct; mangling is not")
    }

    @Test
    fun id3v22AndFutureVersionsAreRefused() {
        for (major in listOf(2, 5)) {
            val file = Id3TestTags.build(major, listOf(TestFrame("TIT2", latin1Body("x")))) + mp3Payload()
            assertEquals(Id3Refusal.UNSUPPORTED_VERSION, Id3Tags.refusalOf(file), "v2.$major")
            assertNull(updateId3Tag(file, TagEdits(title = "y")), "v2.$major")
        }
    }

    @Test
    fun unknownHeaderFlagsAreRefused() {
        val file = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("x"))), headerFlags = 0x08) + mp3Payload()
        assertEquals(Id3Refusal.UNKNOWN_HEADER_FLAGS, Id3Tags.refusalOf(file))
        assertNull(updateId3Tag(file, TagEdits(title = "y")))
    }

    @Test
    fun foreignContainersNeverGetAnId3TagStapledOn() {
        val flac = "fLaC".encodeToByteArray() + ByteArray(500) { 7 }
        val ogg = "OggS".encodeToByteArray() + ByteArray(500) { 7 }
        val wav = "RIFF".encodeToByteArray() + ByteArray(500) { 7 }
        val m4a = ByteArray(4) { 0 } + "ftyp".encodeToByteArray() + ByteArray(500) { 7 }
        for (data in listOf(flac, ogg, wav, m4a)) {
            assertNull(updateId3Tag(data, TagEdits(title = "x")), data.decodeToString(0, 4))
        }
    }

    @Test
    fun emptyAndTinyInputsAreRefusedNotCrashed() {
        for (data in listOf(ByteArray(0), ByteArray(1), ByteArray(3) { 'I'.code.toByte() })) {
            assertNull(updateId3Tag(data, TagEdits(title = "x")))
        }
    }

    @Test
    fun everyTruncationOfARealTagIsHandledWithoutThrowing() {
        // The reader must survive arbitrary garbage: a file can be truncated
        // anywhere, and a tag editor that throws mid-scan is a crashed app.
        val file = Id3TestTags.build(
            major = 4,
            frames = listOf(TestFrame("TIT2", utf8Body("Название")), artFrame(size = 900), commentFrame("hi")),
            padding = 16,
        ) + mp3Payload(64)

        for (length in 0..file.size) {
            val prefix = file.copyOfRange(0, length)
            Id3Tags.read(prefix)
            Id3Tags.tagLength(prefix)
            Id3Tags.refusalOf(prefix)
            val out = updateId3Tag(prefix, TagEdits(title = "T"))
            // Whatever comes back must itself be a readable tag.
            if (out != null) assertNotNull(Id3Tags.read(out), "output at truncation $length")
        }
    }

    @Test
    fun corruptedBytesAnywhereNeverProduceGarbageOutput() {
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TIT2", latin1Body("Title")), artFrame(size = 400)),
            padding = 8,
        ) + mp3Payload(32)

        for (index in file.indices step 7) {
            for (value in listOf(0x00, 0x01, 0x7F, 0x80, 0xFF)) {
                val mutated = file.copyOf()
                mutated[index] = value.toByte()
                val out = updateId3Tag(mutated, TagEdits(title = "T"))
                if (out != null) {
                    val info = assertNotNull(Id3Tags.read(out), "index $index value $value")
                    assertEquals("T", info.title, "index $index value $value")
                }
            }
        }
    }

    // ---------------------------------------------------------------- exotic headers

    @Test
    fun aV23ExtendedHeaderIsSkippedAndDropped() {
        // 2.3: a plain big-endian size that excludes its own four bytes.
        val ext = Id3TestTags.plain(6) + byteArrayOf(0, 0) + Id3TestTags.plain(0)
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TIT2", latin1Body("Old")), artFrame(size = 300)),
            headerFlags = 0x40,
            extendedHeader = ext,
        ) + mp3Payload()

        val info = assertNotNull(Id3Tags.read(file), "extended header should not defeat the parse")
        assertEquals(listOf("TIT2", "APIC"), info.frameIds)

        val out = assertNotNull(updateId3Tag(file, TagEdits(title = "New")))
        assertEquals(0, out[5].toInt() and 0x40, "the rewritten tag declares no extended header")
        assertEquals("New", assertNotNull(Id3Tags.read(out)).title)
        assertContentEquals(artFrame(size = 300).body, Id3TestTags.frameBody(out, "APIC"))
    }

    @Test
    fun aV24ExtendedHeaderIsSkippedAndDropped() {
        // 2.4: a syncsafe size that includes its own four bytes.
        val ext = Id3TestTags.syncsafe(6) + byteArrayOf(1, 0)
        val file = Id3TestTags.build(
            major = 4,
            frames = listOf(TestFrame("TIT2", utf8Body("Old")), artFrame(size = 300)),
            headerFlags = 0x40,
            extendedHeader = ext,
        ) + mp3Payload()

        assertEquals(listOf("TIT2", "APIC"), assertNotNull(Id3Tags.read(file)).frameIds)
        val out = assertNotNull(updateId3Tag(file, TagEdits(title = "New")))
        assertEquals(0, out[5].toInt() and 0x40)
        assertContentEquals(artFrame(size = 300).body, Id3TestTags.frameBody(out, "APIC"))
    }

    @Test
    fun anImplausibleExtendedHeaderIsRefused() {
        val ext = Id3TestTags.plain(9999) + byteArrayOf(0, 0)
        val file = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TIT2", latin1Body("x"))),
            headerFlags = 0x40,
            extendedHeader = ext,
        ) + mp3Payload()

        assertEquals(Id3Refusal.BAD_EXTENDED_HEADER, Id3Tags.refusalOf(file))
        assertNull(updateId3Tag(file, TagEdits(title = "y")))
    }

    @Test
    fun aV24FooterIsAccountedForSoTheAudioIsNotClipped() {
        val audio = mp3Payload()
        val file = Id3TestTags.build(
            major = 4,
            frames = listOf(TestFrame("TIT2", utf8Body("Old")), artFrame(size = 200)),
            withFooter = true,
        ) + audio

        val tagLength = assertNotNull(Id3Tags.tagLength(file))
        assertEquals(file.size - audio.size, tagLength, "the footer counts towards the tag length")

        val out = assertNotNull(updateId3Tag(file, TagEdits(title = "New")))
        val info = assertNotNull(Id3Tags.read(out))
        assertEquals("New", info.title)
        assertContentEquals(audio, out.copyOfRange(info.totalLength, out.size), "audio must not be clipped or doubled")
        assertEquals(0, out[5].toInt() and 0x10, "the rewritten tag declares no footer")
    }

    // ---------------------------------------------------------------- api shape

    @Test
    fun buildUpdateWorksFromTheLeadingBytesAlone() {
        val audio = mp3Payload()
        val file = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("Old")), artFrame(size = 300))) + audio

        // What a streaming caller does: read the header, ask how long the tag
        // is, read exactly that much, and never load the audio at all.
        val header = file.copyOfRange(0, Id3Codec.HEADER_SIZE)
        val tagLength = assertNotNull(Id3Tags.tagLength(header))
        val prefix = file.copyOfRange(0, tagLength)

        val update = assertNotNull(Id3Tags.buildUpdate(prefix, TagEdits(title = "New")))
        assertEquals(tagLength, update.replacedLength)

        val rebuilt = update.tag + file.copyOfRange(update.replacedLength, file.size)
        assertContentEquals(updateId3Tag(file, TagEdits(title = "New")), rebuilt)
        assertContentEquals(audio, rebuilt.copyOfRange(update.tag.size, rebuilt.size))
    }

    @Test
    fun tagLengthReportsZeroForAnUntaggedFileAndNullWhenTooShort() {
        assertEquals(0, Id3Tags.tagLength(mp3Payload()))
        assertNull(Id3Tags.tagLength(ByteArray(2)))
        assertNull(Id3Tags.tagLength("ID3".encodeToByteArray()))
    }

    @Test
    fun refusalIsNullForHealthyAndUntaggedInput() {
        assertNull(Id3Tags.refusalOf(mp3Payload()))
        assertNull(Id3Tags.refusalOf(Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("x")))) + mp3Payload()))
    }

    @Test
    fun tagEditsKnowsWhenItIsEmpty() {
        assertTrue(TagEdits().isEmpty)
        assertTrue(!TagEdits(title = "").isEmpty)
    }
}
