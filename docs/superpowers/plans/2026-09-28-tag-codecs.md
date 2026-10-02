# Tag codecs (plan 1 of 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pure-Kotlin tag codecs for MP3 (ID3v2), FLAC, Opus/Vorbis (Ogg) and M4A (MP4) that read every field the editor needs and plan the smallest safe write for any edit — verified against the user's real library.

**Architecture:** One `TagCodec` interface in `core/library` (`library.tags`), four implementations, and a registry that picks one by magic bytes. A codec never does IO beyond reading through `RandomAccessSource`; it returns a `WritePlan` (`NoChange`, `InPlacePatch`, `StreamingRewrite`, `Refused`) that a platform executes later (plan 2). A shared executor applies plans in memory and streams rewrites to a `ByteSink`, so tests exercise exactly the bytes a device would write.

**Tech Stack:** Kotlin Multiplatform 2.3.10, `kotlin.test`, Gradle (`./gradlew`), no third-party dependencies in `core/library`.

**Spec:** `docs/superpowers/specs/2026-09-28-tag-editing-design.md` (§2–§4, §8.1–§8.2). Plans 2–4 (durable writing, app integration, device verification and release) are written after this one lands, against its real interfaces.

## Global Constraints

- Every new source file starts with the project header: `/*\n * Copyright (c) 2026 LatentJam Project\n * SPDX-License-Identifier: Apache-2.0\n */`.
- `core/library` stays dependency-free: Kotlin stdlib only in `commonMain`. No tagging libraries, no `java.*` in `commonMain`.
- All codec code lives in package `io.github.nikitasud.latentjam.library.tags`, under `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/`.
- Test names are plain camelCase: Kotlin/Native rejects `, ? ( ) $ % @` and similar in backticked names.
- Spare space a codec creates: **16 KiB** (`TagSpace.SPARE_BYTES = 16 * 1024`).
- Never damage a file: when a structure cannot be fully accounted for, return `WritePlan.Refused`, never a partial rewrite.
- Everything not managed by the editor is preserved byte for byte, in its original order.
- Test command for this module: `./gradlew :core:library:testAndroidHostTest --offline -q` and `./gradlew :core:library:compileTestKotlinIosSimulatorArm64 --offline -q`. Read results from `core/library/build/test-results/testAndroidHostTest/*.xml` when `-q` hides them.
- Commits: on branch `feat/tag-editing`, author is the repository's configured git user, **no** `Co-Authored-By` or "Generated with" lines, nothing pushed.
- Real music never enters the repository; the corpus test reads `TAG_REAL_FILES` and self-skips without it.

## Review Focus

- A file whose edit sets values it already has (the user retypes the same title), even when the stored field uses another text encoding or key casing: must produce `WritePlan.NoChange`, never a rewrite. Tests: `settingTheSameValuesWritesNothing` in Tasks 6, 7, 9, 11; `restatingAUtf16TitleKeepsTheFrame` (Task 5); `restatingAValueKeepsTheOriginalEntry` (Task 4).
- Very long and non-Latin text (20 KB of Cyrillic/Japanese lyrics): must round-trip exactly in every format. Test: `longNonLatinLyricsRoundTrip` in Tasks 5, 7, 9, 11.
- A file whose only picture is type 0 ("other"): it is the cover; replacing it keeps every other picture. Test: `typeZeroPictureIsTheCoverWhenNoFrontCoverExists` in Tasks 5, 7, 9.
- An Ogg comment header that outgrows its discardable padding by a single byte (the same page count and byte total can no longer be kept): the plan must fall back to a correct rewrite that renumbers every later page. Test: `headerOneBytePastItsPaddingIsRewritten` in Task 9.
- An MP4 whose chunk offsets point both before and after `moov` (an `mdat` on each side): only the offsets after `moov` move. Test: `offsetsBeforeMoovStayPut` in Task 11.

## Not in this plan (spec sections owned by plans 2–4)

- §5 durability — journal, recovery store, forward/rollback recovery, fsync discipline, space checks, consent on every Android version, batch writing and the single rescan: **plan 2**, built on `WritePlan` and `WritePlans.stream`.
- §3.4 cover downscaling and JPEG encoding (platform image code), §3.5 album artist in the library and album grouping, §6 editors, artwork cache keys, now-playing refresh, SMART audio carry-over, translations: **plan 3**.
- §8.3–§8.6 device matrix, speed measurements, release 0.7.0: **plan 4**.
- The verifier in plan 2 must compare snapshots exactly as `CodecAssertions` does here: `expectedAfter`, the one allowed version change (`none` → `ID3v2.3`), and `nextCover` ignored after a cover removal.

## File map

| File (under `.../library/tags/`) | Responsibility |
|---|---|
| `TagModel.kt` (new; `TagEdits` moves here from `Id3Model.kt`) | `TagEdits`, `CoverEdit`, `TagFormat`, `TagRefusal`, `CoverInfo`, `TagSnapshot` and its `expectedAfter` |
| `TagIo.kt` | `RandomAccessSource`, `ByteArraySource`, `ByteSink`, `ByteArraySink` |
| `Checksums.kt` | `Crc32` (IEEE), `OggCrc` |
| `WritePlan.kt` | `WritePlan`, `ByteWrite`, `OutputSegment`, `StreamTransform`, `StreamRefusedException`, `TagSpace` |
| `WritePlans.kt` | executor: `stream`, `applyInMemory`; `ByteDiff` |
| `TagCodec.kt` | the `TagCodec` interface and the `TagCodecs` registry |
| `Pictures.kt` | `ImageProbe`, `FlacPicture`, cover-target rule |
| `VorbisComments.kt` | Vorbis comment block encode/decode and field mapping (FLAC + Ogg) |
| `Id3Tags.kt` (modify) | new frames: TPE2, TRCK, TPOS, USLT write, APIC, TXXX:ARTISTS; 16 KiB padding; `readFields` |
| `Id3TagCodec.kt` | `TagCodec` for MP3 |
| `FlacTagCodec.kt` | `TagCodec` for FLAC |
| `OggPages.kt` | Ogg page parse/serialize, packet reassembly, lacing layout, page renumbering transform |
| `OggTagCodec.kt` | `TagCodec` for Opus and Vorbis |
| `Mp4Boxes.kt` | MP4 box tree for `moov`, top-level walk, `ilst` items, offset tables |
| `Mp4TagCodec.kt` | `TagCodec` for M4A |

Tests go to `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/` except the real-file corpus test, which goes to `core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/tags/` (JVM file access).

---

### Task 1: Tag model

**Files:**
- Create: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagModel.kt`
- Modify: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Id3Model.kt` (delete the `TagEdits` class, lines 74–103 — the doc comment starting "The fields this writer understands" through the class's closing brace)
- Test: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/TagModelTest.kt`

**Interfaces:**
- Consumes: `Id3Refusal` (existing, `Id3Model.kt`).
- Produces: `CoverEdit` (`Keep`, `Remove`, `Replace(bytes, mime)`), `TagEdits(title, artist, album, genre, year, albumArtist, trackNumber, trackTotal, discNumber, discTotal, lyrics, cover)` with `isEmpty`, `numbersAreValid` and `internal fun normalized(): TagEdits` (lyrics trimmed); `TagFormat { MP3, FLAC, OPUS, VORBIS, MP4 }`; `TagRefusal` (enum, see code) with `TagRefusal.of(Id3Refusal)`; `CoverInfo(mime, size, crc32)` with `CoverInfo.of(bytes, mime)`; `TagSnapshot(format, version, title, artist, album, albumArtist, genre, year, trackNumber, trackTotal, discNumber, discTotal, lyrics, cover, otherPictures, nextCover, pictures, artists, refusal)` with `editable` and `expectedAfter(edits): TagSnapshot`; `internal object TagNumbers { fun strict(value: String): Int? }`; `internal object CreditedArtists { fun fromDisplay(value: String): List<String> }`. `CoverInfo.of` uses `Crc32` from Task 2 — so Task 1 also creates `Checksums.kt` with `Crc32` only (Task 2 adds `OggCrc` and its tests).

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TagModelTest {

    private val base = TagSnapshot(
        format = TagFormat.FLAC,
        version = "FLAC",
        title = "Old",
        artist = "Band",
        album = "Record",
        albumArtist = "Band",
        genre = "Rock",
        year = "2001-05-03",
        trackNumber = 3,
        trackTotal = 12,
        discNumber = 1,
        discTotal = 2,
        lyrics = "la la",
        cover = CoverInfo("image/jpeg", 10, 99L),
        otherPictures = 1,
        pictures = listOf(55L, 99L),
        artists = listOf("A", "B"),
    )

    @Test
    fun emptyEditsAreEmpty() {
        assertTrue(TagEdits().isEmpty)
    }

    @Test
    fun anyFieldMakesEditsNonEmpty() {
        assertFalse(TagEdits(albumArtist = "x").isEmpty)
        assertFalse(TagEdits(discTotal = "").isEmpty)
        assertFalse(TagEdits(lyrics = "").isEmpty)
        assertFalse(TagEdits(cover = CoverEdit.Remove).isEmpty)
    }

    @Test
    fun numbersMustBePlainPositiveDigits() {
        assertTrue(TagEdits(trackNumber = "3", trackTotal = "", discNumber = "9999").numbersAreValid)
        assertFalse(TagEdits(trackNumber = "0").numbersAreValid)
        assertFalse(TagEdits(trackNumber = "3/12").numbersAreValid)
        assertFalse(TagEdits(discTotal = "12345").numbersAreValid)
        assertFalse(TagEdits(discNumber = "a").numbersAreValid)
        assertFalse(TagEdits(trackTotal = " 3").numbersAreValid)
    }

    @Test
    fun expectedAfterAppliesSetRemoveAndKeep() {
        val after = base.expectedAfter(TagEdits(title = "New", album = "", trackNumber = "4", lyrics = ""))
        assertEquals("New", after.title)
        assertNull(after.album)
        assertEquals(4, after.trackNumber)
        assertEquals(12, after.trackTotal)
        assertNull(after.lyrics)
        assertEquals("Band", after.artist)
        assertEquals("2001-05-03", after.year)
        assertEquals(base.cover, after.cover)
        assertEquals(1, after.otherPictures)
    }

    @Test
    fun expectedAfterTrimsLyricsAndTreatsBlankAsRemoval() {
        assertEquals("la la la", base.expectedAfter(TagEdits(lyrics = "\nla la la  \n")).lyrics)
        assertNull(base.expectedAfter(TagEdits(lyrics = "  \n")).lyrics)
    }

    @Test
    fun expectedAfterKeepsLoneTotalsOutsideId3() {
        val after = base.expectedAfter(TagEdits(trackNumber = ""))
        assertNull(after.trackNumber)
        assertEquals(12, after.trackTotal)
    }

    @Test
    fun expectedAfterDropsTotalsWithoutNumberOnId3() {
        val id3 = base.copy(format = TagFormat.MP3, version = "ID3v2.4")
        val after = id3.expectedAfter(TagEdits(trackNumber = "", discNumber = ""))
        assertNull(after.trackNumber)
        assertNull(after.trackTotal)
        assertNull(after.discNumber)
        assertNull(after.discTotal)
    }

    @Test
    fun expectedAfterNarrowsYearOnId3v23Only() {
        val v23 = base.copy(format = TagFormat.MP3, version = "ID3v2.3")
        assertEquals("2004", v23.expectedAfter(TagEdits(year = "2004-01-02")).year)
        val v24 = base.copy(format = TagFormat.MP3, version = "ID3v2.4")
        assertEquals("2004-01-02", v24.expectedAfter(TagEdits(year = "2004-01-02")).year)
        assertEquals("2004-01-02", base.expectedAfter(TagEdits(year = "2004-01-02")).year)
    }

    @Test
    fun expectedAfterRewritesCreditedArtistsOnlyWhenTheFileHasThem() {
        assertEquals(listOf("X", "Y"), base.expectedAfter(TagEdits(artist = "X; Y")).artists)
        assertEquals(emptyList(), base.expectedAfter(TagEdits(artist = "Solo")).artists)
        assertEquals(emptyList(), base.expectedAfter(TagEdits(artist = "")).artists)
        val none = base.copy(artists = emptyList())
        assertEquals(emptyList(), none.expectedAfter(TagEdits(artist = "X; Y")).artists)
        assertEquals(listOf("A", "B"), base.expectedAfter(TagEdits(title = "t")).artists)
    }

    @Test
    fun expectedAfterReplacesAndRemovesTheCover() {
        val bytes = byteArrayOf(1, 2, 3)
        val replaced = base.expectedAfter(TagEdits(cover = CoverEdit.Replace(bytes, "image/png")))
        assertEquals(CoverInfo.of(bytes, "image/png"), replaced.cover)
        assertEquals(1, replaced.otherPictures)
        assertEquals(listOf(55L, Crc32.of(bytes)).sorted(), replaced.pictures)
        val removed = base.expectedAfter(TagEdits(cover = CoverEdit.Remove))
        assertNull(removed.cover)
        assertEquals(listOf(55L), removed.pictures)
        val added = base.copy(cover = null, pictures = listOf(55L)).expectedAfter(TagEdits(cover = CoverEdit.Replace(bytes, "image/png")))
        assertEquals(listOf(55L, Crc32.of(bytes)).sorted(), added.pictures)
    }

    @Test
    fun removingTheCoverPromotesTheNextPicture() {
        val next = CoverInfo("image/png", 5, 7L)
        val withNext = base.copy(nextCover = next)
        val after = withNext.expectedAfter(TagEdits(cover = CoverEdit.Remove))
        assertEquals(next, after.cover)
        assertEquals(0, after.otherPictures)
        assertNull(after.nextCover)
        val noCover = base.copy(cover = null)
        assertEquals(1, noCover.expectedAfter(TagEdits(cover = CoverEdit.Remove)).otherPictures)
    }

    @Test
    fun id3RefusalsMapOneToOne() {
        assertEquals(TagRefusal.ID3_UNSYNCHRONISED, TagRefusal.of(Id3Refusal.UNSYNCHRONISED))
        assertEquals(TagRefusal.TRUNCATED, TagRefusal.of(Id3Refusal.TRUNCATED))
        assertEquals(Id3Refusal.entries.size, Id3Refusal.entries.map { TagRefusal.of(it) }.toSet().size)
    }

    @Test
    fun crc32MatchesTheStandardCheckValue() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `TagSnapshot`, `CoverEdit`, `TagFormat`, `TagRefusal`, `CoverInfo`, `Crc32` unresolved, and `TagEdits` has no parameter `albumArtist`.

- [ ] **Step 3: Remove `TagEdits` from `Id3Model.kt`**

Delete from `Id3Model.kt` the block that begins with the KDoc line `/**` above ` * The fields this writer understands, and what to do with each.` and ends with the closing `}` of `public data class TagEdits(...)` (including its `isEmpty` property). Nothing else in that file changes; `Id3TagInfo`, `Id3TagUpdate`, `Id3Refusal`, `Id3Version` stay.

- [ ] **Step 4: Create `Checksums.kt` with `Crc32`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * CRC-32 (IEEE 802.3, reflected) — the checksum zip and PNG use. Streaming, so an audio region can
 * be checksummed in chunks without holding it in memory.
 */
public class Crc32 {
    private var crc: Int = -1

    public fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        var c = crc
        for (i in offset until offset + count) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        crc = c
    }

    /** The checksum of everything passed to [update] so far, as an unsigned 32-bit value. */
    public val value: Long get() = crc.inv().toLong() and 0xFFFFFFFFL

    public companion object {
        private val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        public fun of(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): Long =
            Crc32().apply { update(bytes, offset, count) }.value
    }
}
```

- [ ] **Step 5: Create `TagModel.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** What to do with a file's cover picture — see [TagSnapshot.cover] for which picture that is. */
public sealed interface CoverEdit {
    /** Leave every picture exactly as it is. */
    public data object Keep : CoverEdit

    /** Remove the cover picture; every other picture stays. */
    public data object Remove : CoverEdit

    /** Put [bytes] — a JPEG or PNG, named by [mime] — in place of the cover, or add one. */
    public class Replace(public val bytes: ByteArray, public val mime: String) : CoverEdit
}

/**
 * The fields the tag writers understand, and what to do with each.
 *
 * `null` leaves the field exactly as it is. A non-null value replaces it, and the **empty string
 * removes it** — that distinction is what lets a UI tell "user did not touch this box" apart from
 * "user cleared it". Number fields take plain digits (see [numbersAreValid]).
 */
public data class TagEdits(
    public val title: String? = null,
    public val artist: String? = null,
    public val album: String? = null,
    public val genre: String? = null,
    /**
     * TDRC on ID3v2.4, TYER on ID3v2.3 (narrowed to its leading year there, the frame is defined as
     * four characters), DATE in Vorbis comments, ©day in MP4.
     */
    public val year: String? = null,
    public val albumArtist: String? = null,
    public val trackNumber: String? = null,
    public val trackTotal: String? = null,
    public val discNumber: String? = null,
    public val discTotal: String? = null,
    public val lyrics: String? = null,
    public val cover: CoverEdit = CoverEdit.Keep,
) {
    /** True when applying these edits would change nothing. */
    public val isEmpty: Boolean
        get() = title == null && artist == null && album == null && genre == null && year == null &&
            albumArtist == null && trackNumber == null && trackTotal == null &&
            discNumber == null && discTotal == null && lyrics == null && cover == CoverEdit.Keep

    /** Every number field is absent, empty (remove), or plain digits from 1 to 9999. */
    public val numbersAreValid: Boolean
        get() = listOf(trackNumber, trackTotal, discNumber, discTotal)
            .all { it == null || it.isEmpty() || TagNumbers.strict(it) != null }

    /** Lyrics are stored without surrounding whitespace in every format; codecs write these. */
    internal fun normalized(): TagEdits = copy(lyrics = lyrics?.trim())
}

/** The containers the codecs handle. Opus and Vorbis share Ogg but are told apart for display. */
public enum class TagFormat { MP3, FLAC, OPUS, VORBIS, MP4 }

/**
 * Why a file's tags were left alone. Every value is a case where writing risked destroying data
 * the codec cannot faithfully reproduce — refusing is the correct outcome, never "write anyway".
 */
public enum class TagRefusal {
    UNSUPPORTED_FORMAT,
    TRUNCATED,

    ID3_UNSUPPORTED_VERSION,
    ID3_UNSYNCHRONISED,
    ID3_BAD_EXTENDED_HEADER,
    ID3_BAD_FOOTER,
    ID3_UNKNOWN_HEADER_FLAGS,
    ID3_MALFORMED_FRAMES,
    ID3_NOT_TAGGABLE,
    ID3_TAG_TOO_LARGE,

    /** An ID3v2 tag in front of FLAC, Ogg or MP4: two disagreeing tags would result. */
    ID3_BEFORE_OTHER_CONTAINER,

    FLAC_STREAMINFO_NOT_FIRST,
    FLAC_MALFORMED_METADATA,
    FLAC_BLOCK_TOO_LARGE,

    OGG_MULTIPLE_STREAMS,
    OGG_BAD_PAGE_CRC,
    OGG_UNKNOWN_CODEC,
    OGG_MALFORMED_PAGES,

    MP4_FRAGMENTED,
    MP4_DRM_PROTECTED,
    MP4_UNKNOWN_OFFSET_BOX,
    MP4_OFFSET_INSIDE_REWRITE,
    MP4_OFFSET_OVERFLOW,
    MP4_MALFORMED_ATOMS,
    MP4_TAGS_TOO_LARGE,

    /** A number field was not plain digits 1–9999. */
    INVALID_NUMBER,

    /** A new cover was not a readable JPEG or PNG. */
    UNSUPPORTED_IMAGE,
    ;

    public companion object {
        public fun of(reason: Id3Refusal): TagRefusal = when (reason) {
            Id3Refusal.TRUNCATED -> TRUNCATED
            Id3Refusal.UNSUPPORTED_VERSION -> ID3_UNSUPPORTED_VERSION
            Id3Refusal.UNSYNCHRONISED -> ID3_UNSYNCHRONISED
            Id3Refusal.BAD_EXTENDED_HEADER -> ID3_BAD_EXTENDED_HEADER
            Id3Refusal.BAD_FOOTER -> ID3_BAD_FOOTER
            Id3Refusal.UNKNOWN_HEADER_FLAGS -> ID3_UNKNOWN_HEADER_FLAGS
            Id3Refusal.MALFORMED_FRAMES -> ID3_MALFORMED_FRAMES
            Id3Refusal.NOT_TAGGABLE -> ID3_NOT_TAGGABLE
            Id3Refusal.TAG_TOO_LARGE -> ID3_TAG_TOO_LARGE
        }
    }
}

/** The cover as far as verification needs to know it: type, size and a checksum of its bytes. */
public data class CoverInfo(val mime: String, val size: Int, val crc32: Long) {
    public companion object {
        public fun of(bytes: ByteArray, mime: String): CoverInfo = CoverInfo(mime, bytes.size, Crc32.of(bytes))
    }
}

/**
 * Everything the editor shows and the verifier compares, read from the file itself.
 *
 * Text fields are exactly as stored (several Vorbis GENRE values joined with "; "). Numbers are the
 * leading number of their field; 0 and garbage read as null.
 */
public data class TagSnapshot(
    val format: TagFormat,
    /** "ID3v2.3", "ID3v2.4", "none" (an untagged MP3), "FLAC", "Opus", "Vorbis" or "MP4". */
    val version: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val lyrics: String? = null,
    /** The picture a cover edit acts on, or null when the file has none. */
    val cover: CoverInfo? = null,
    /** Pictures other than [cover]; every edit preserves them. */
    val otherPictures: Int = 0,
    /**
     * The picture that becomes the cover when [cover] is removed (the next front cover, or the first
     * "other" picture, or MP4's second `covr` image); null when none would. Not predicted by
     * [expectedAfter]: verifiers compare snapshots with this field cleared.
     */
    val nextCover: CoverInfo? = null,
    /**
     * CRC-32 of every picture's image data (the cover included), sorted. With [cover] this pins every
     * picture byte for byte: [expectedAfter] knows exactly which one an edit replaces or removes.
     */
    val pictures: List<Long> = emptyList(),
    /** The credited-artists list (`ARTISTS`), empty when the file carries none. */
    val artists: List<String> = emptyList(),
    /** Why this file cannot be edited; null when it can. */
    val refusal: TagRefusal? = null,
) {
    val editable: Boolean get() = refusal == null

    /** What a correct write of [edits] reads back as — the verifier's expectation. */
    public fun expectedAfter(edits: TagEdits): TagSnapshot {
        var newTrackTotal = number(trackTotal, edits.trackTotal)
        var newDiscTotal = number(discTotal, edits.discTotal)
        val newTrackNumber = number(trackNumber, edits.trackNumber)
        val newDiscNumber = number(discNumber, edits.discNumber)
        // ID3 stores "3/12" in one frame: without the number there is no frame to hold a total.
        if (format == TagFormat.MP3) {
            if (newTrackNumber == null) newTrackTotal = null
            if (newDiscNumber == null) newDiscTotal = null
        }
        // Removing the cover promotes the next picture; every other picture stays where it was.
        val (newCover, newOtherPictures) = when (val edit = edits.cover) {
            CoverEdit.Keep -> cover to otherPictures
            CoverEdit.Remove ->
                if (cover == null) null to otherPictures
                else nextCover to (otherPictures - (if (nextCover != null) 1 else 0))
            is CoverEdit.Replace -> CoverInfo.of(edit.bytes, edit.mime) to otherPictures
        }
        val newPictures = when (edits.cover) {
            CoverEdit.Keep -> pictures
            CoverEdit.Remove -> if (cover == null) pictures else pictures.minusOne(cover.crc32)
            is CoverEdit.Replace ->
                ((if (cover == null) pictures else pictures.minusOne(cover.crc32)) + newCover!!.crc32).sorted()
        }
        return copy(
            title = text(title, edits.title),
            artist = text(artist, edits.artist),
            album = text(album, edits.album),
            albumArtist = text(albumArtist, edits.albumArtist),
            genre = text(genre, edits.genre),
            year = expectedYear(edits.year),
            trackNumber = newTrackNumber,
            trackTotal = newTrackTotal,
            discNumber = newDiscNumber,
            discTotal = newDiscTotal,
            // Readers return lyrics trimmed in every format, and writers store them trimmed.
            lyrics = text(lyrics, edits.lyrics?.trim()),
            cover = newCover,
            otherPictures = newOtherPictures,
            // Which picture comes next after a removal is not predicted; after other edits it stays.
            nextCover = if (edits.cover == CoverEdit.Remove) null else nextCover,
            pictures = newPictures,
            artists = when {
                edits.artist == null || artists.isEmpty() -> artists
                else -> CreditedArtists.fromDisplay(edits.artist)
            },
        )
    }

    private fun List<Long>.minusOne(value: Long): List<Long> {
        val index = indexOf(value)
        return if (index < 0) this else filterIndexed { i, _ -> i != index }
    }

    private fun expectedYear(edit: String?): String? = when {
        edit == null -> year
        edit.isEmpty() -> null
        format == TagFormat.MP3 && version == "ID3v2.3" && edit.length > 4 &&
            edit.take(4).all { it in '0'..'9' } -> edit.take(4)
        else -> edit
    }

    private fun text(current: String?, edit: String?): String? = when {
        edit == null -> current
        edit.isEmpty() -> null
        else -> edit
    }

    private fun number(current: Int?, edit: String?): Int? = when {
        edit == null -> current
        edit.isEmpty() -> null
        else -> TagNumbers.strict(edit)
    }
}

internal object TagNumbers {
    /** Plain digits from 1 to 9999; anything else is not a number a tag should hold. */
    fun strict(value: String): Int? =
        value.takeIf { it.length in 1..4 && it.all { c -> c in '0'..'9' } }?.toInt()?.takeIf { it > 0 }
}

internal object CreditedArtists {
    /**
     * The credited-artists list a display credit implies: its ";"-separated names when there are
     * several, otherwise none — a single name needs no list, and "feat."-guessing is never done.
     */
    fun fromDisplay(value: String): List<String> {
        val names = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        return if (names.size > 1) names else emptyList()
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS — all `TagModelTest` tests and every pre-existing test (the existing ID3 tests still construct `TagEdits(title = ...)` by name and compile unchanged).

- [ ] **Step 7: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagModel.kt \
        core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Checksums.kt \
        core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Id3Model.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/TagModelTest.kt
git commit -m "feat(tags): one edit model for every format — album artist, numbers, lyrics and cover included"
```

---

### Task 2: IO, checksums, write plans and the executor

**Files:**
- Create: `.../library/tags/TagIo.kt`, `.../library/tags/WritePlan.kt`, `.../library/tags/WritePlans.kt`
- Modify: `.../library/tags/Checksums.kt` (add `OggCrc`)
- Test: `.../library/tags/WritePlansTest.kt` (commonTest)

**Interfaces:**
- Consumes: `TagRefusal`, `Crc32` (Task 1).
- Produces:
  - `interface RandomAccessSource { val length: Long; fun read(offset: Long, count: Int): ByteArray? }`, `class ByteArraySource(bytes: ByteArray)`.
  - `interface ByteSink { fun write(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) }`, `class ByteArraySink { val size: Int; fun toByteArray(): ByteArray }`.
  - `class ByteWrite(offset: Long, bytes: ByteArray)`.
  - `sealed interface OutputSegment { val length: Long }` with `Bytes(bytes)`, `Copy(sourceOffset, length)`, `Transformed(sourceOffset, length, transform: StreamTransform)` — for `Transformed`, `length` is the **source** length and the output length is the same (every transform in this plan preserves length).
  - `interface StreamTransform { fun start(): Pass; interface Pass { fun process(chunk: ByteArray, sink: ByteSink); fun finish(sink: ByteSink) } }`.
  - `class StreamRefusedException(reason: TagRefusal) : Exception`.
  - `sealed interface WritePlan` with `NoChange`, `InPlacePatch(writes: List<ByteWrite>, newLength: Long)`, `StreamingRewrite(segments: List<OutputSegment>)` (`newLength` = sum of segment lengths), `Refused(reason: TagRefusal)`.
  - `object TagSpace { const val SPARE_BYTES = 16 * 1024 }`.
  - `object WritePlans { const val COPY_CHUNK = 1 shl 20; fun stream(source, plan: StreamingRewrite, sink); fun applyInMemory(original: ByteArray, plan: WritePlan): ByteArray? }`.
  - `internal object ByteDiff { const val MERGE_GAP = 64; fun writes(base: Long, old: ByteArray, new: ByteArray): List<ByteWrite>; fun patchOrNoChange(base: Long, old: ByteArray, new: ByteArray, newLength: Long, oldLength: Long): WritePlan }`.
  - `internal object OggCrc { fun compute(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): Int }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class WritePlansTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun oggCrcMatchesItsDefinition() {
        // CRC-32 with polynomial 0x04C11DB7, initial value 0, no reflection and no final XOR.
        assertEquals(0x89A1897F.toInt(), OggCrc.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun crc32CanBeFedInChunks() {
        val data = ByteArray(10_000) { (it * 31).toByte() }
        val chunked = Crc32()
        chunked.update(data, 0, 4_000)
        chunked.update(data, 4_000, 6_000)
        assertEquals(Crc32.of(data), chunked.value)
    }

    @Test
    fun byteArraySourceRefusesOutOfRangeReads() {
        val source = ByteArraySource(bytes(1, 2, 3))
        assertContentEquals(bytes(2, 3), source.read(1, 2))
        assertNull(source.read(2, 2))
        assertNull(source.read(-1, 1))
        assertEquals(3L, source.length)
    }

    @Test
    fun diffOfIdenticalRegionsIsEmpty() {
        val region = ByteArray(500) { it.toByte() }
        assertTrue(ByteDiff.writes(100, region, region.copyOf()).isEmpty())
    }

    @Test
    fun diffWritesOnlyTheChangedBytesAndMergesCloseRuns() {
        val old = ByteArray(1000)
        val new = old.copyOf()
        new[10] = 1
        new[20] = 1 // 10 bytes later: merged with the first run
        new[500] = 1 // far away: its own write
        val writes = ByteDiff.writes(4, old, new)
        assertEquals(2, writes.size)
        assertEquals(14L, writes[0].offset)
        assertEquals(11, writes[0].bytes.size)
        assertEquals(504L, writes[1].offset)
        assertEquals(1, writes[1].bytes.size)
    }

    @Test
    fun diffAppendsBytesPastTheOldEnd() {
        val old = ByteArray(100)
        val new = ByteArray(150)
        new[149] = 7
        val writes = ByteDiff.writes(0, old, new)
        val applied = WritePlans.applyInMemory(old, WritePlan.InPlacePatch(writes, 150))
        assertContentEquals(new, applied)
    }

    @Test
    fun patchOrNoChangeReportsNoChangeForAnEmptyDiffAtTheSameLength() {
        val region = ByteArray(10)
        assertIs<WritePlan.NoChange>(ByteDiff.patchOrNoChange(0, region, region.copyOf(), 10, 10))
        assertIs<WritePlan.InPlacePatch>(ByteDiff.patchOrNoChange(0, region, region.copyOf(), 8, 10))
    }

    @Test
    fun inPlacePatchWritesRangesAndSetsTheLength() {
        val original = ByteArray(20) { it.toByte() }
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(2, bytes(9, 9))), newLength = 18)
        val out = assertNotNullBytes(WritePlans.applyInMemory(original, plan))
        assertEquals(18, out.size)
        assertEquals(9, out[2].toInt())
        assertEquals(4, out[4].toInt())
    }

    @Test
    fun streamingRewriteConcatenatesSegments() {
        val original = ByteArray(3 * WritePlans.COPY_CHUNK + 17) { (it % 251).toByte() }
        val plan = WritePlan.StreamingRewrite(
            listOf(
                OutputSegment.Bytes(bytes(7, 7, 7)),
                OutputSegment.Copy(sourceOffset = 5, length = original.size - 5L),
            ),
        )
        val out = assertNotNullBytes(WritePlans.applyInMemory(original, plan))
        assertEquals(plan.newLength, out.size.toLong())
        assertContentEquals(bytes(7, 7, 7), out.copyOfRange(0, 3))
        assertContentEquals(original.copyOfRange(5, original.size), out.copyOfRange(3, out.size))
    }

    @Test
    fun transformedSegmentsPassThroughTheirTransform() {
        val original = ByteArray(2 * WritePlans.COPY_CHUNK + 3) { 1 }
        val invert = object : StreamTransform {
            override fun start() = object : StreamTransform.Pass {
                override fun process(chunk: ByteArray, sink: ByteSink) =
                    sink.write(ByteArray(chunk.size) { (chunk[it] + 1).toByte() })
                override fun finish(sink: ByteSink) = Unit
            }
        }
        val plan = WritePlan.StreamingRewrite(
            listOf(OutputSegment.Transformed(0, original.size.toLong(), invert)),
        )
        val out = assertNotNullBytes(WritePlans.applyInMemory(original, plan))
        assertTrue(out.all { it == 2.toByte() })
    }

    @Test
    fun copyPastTheEndOfTheSourceIsRefused() {
        val plan = WritePlan.StreamingRewrite(listOf(OutputSegment.Copy(0, 10)))
        val error = assertFailsWith<StreamRefusedException> {
            WritePlans.applyInMemory(ByteArray(5), plan)
        }
        assertEquals(TagRefusal.TRUNCATED, error.reason)
    }

    @Test
    fun refusedAndNoChangePlansAreHandled() {
        val original = bytes(1, 2)
        assertNull(WritePlans.applyInMemory(original, WritePlan.Refused(TagRefusal.TRUNCATED)))
        assertContentEquals(original, WritePlans.applyInMemory(original, WritePlan.NoChange))
    }

    private fun assertNotNullBytes(value: ByteArray?): ByteArray {
        kotlin.test.assertNotNull(value)
        return value
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `OggCrc`, `ByteArraySource`, `ByteDiff`, `WritePlans`, `WritePlan`, `OutputSegment`, `StreamTransform`, `StreamRefusedException` unresolved.

- [ ] **Step 3: Add `OggCrc` to `Checksums.kt`**

Append to `Checksums.kt`:

```kotlin
/** The Ogg page checksum: polynomial 0x04C11DB7, initial value 0, no reflection, no final XOR. */
internal object OggCrc {
    private val TABLE = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    fun compute(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): Int {
        var crc = 0
        for (i in offset until offset + count) {
            crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (bytes[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }
}
```

- [ ] **Step 4: Create `TagIo.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Random read access to a file. Platforms implement it over a file handle; tests over an array.
 * Codecs read only through this, so the same code runs on Android, iOS and the host JVM.
 */
public interface RandomAccessSource {
    public val length: Long

    /** Exactly [count] bytes at [offset], or null when the range is negative or runs past [length]. */
    public fun read(offset: Long, count: Int): ByteArray?
}

public class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val length: Long get() = bytes.size.toLong()

    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || offset + count > bytes.size) return null
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
    }
}

/** Sequential output of a streaming rewrite. */
public interface ByteSink {
    public fun write(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset)
}

/** An in-memory [ByteSink], for tests and small files. */
public class ByteArraySink : ByteSink {
    private var buffer = ByteArray(1024)

    public var size: Int = 0
        private set

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(size + count, buffer.size * 2))
        bytes.copyInto(buffer, size, offset, offset + count)
        size += count
    }

    public fun toByteArray(): ByteArray = buffer.copyOf(size)
}
```

- [ ] **Step 5: Create `WritePlan.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** Space a codec leaves for later edits whenever it has to lay a structure out anew. */
public object TagSpace {
    public const val SPARE_BYTES: Int = 16 * 1024
}

/** Overwrite the file at [offset] with [bytes]. */
public class ByteWrite(public val offset: Long, public val bytes: ByteArray)

/** One piece of a rewritten file, in output order. */
public sealed interface OutputSegment {
    /** Bytes this segment contributes to the output. */
    public val length: Long

    public class Bytes(public val bytes: ByteArray) : OutputSegment {
        override val length: Long get() = bytes.size.toLong()
    }

    /** [length] bytes of the original, starting at [sourceOffset], unchanged. */
    public class Copy(public val sourceOffset: Long, override val length: Long) : OutputSegment

    /**
     * [length] bytes of the original passed through [transform], which emits exactly as many bytes
     * as it receives (e.g. Ogg pages with shifted sequence numbers and recomputed checksums).
     */
    public class Transformed(
        public val sourceOffset: Long,
        override val length: Long,
        public val transform: StreamTransform,
    ) : OutputSegment
}

/** A length-preserving, streaming rewrite of a run of source bytes. */
public interface StreamTransform {
    /** Fresh state for one pass. */
    public fun start(): Pass

    public interface Pass {
        /** The next chunk of input; may hold bytes back until a whole unit (a page) has arrived. */
        public fun process(chunk: ByteArray, sink: ByteSink)

        /** Emits what is held back; throws [StreamRefusedException] when input ended mid-unit. */
        public fun finish(sink: ByteSink)
    }
}

/** A stream turned out, mid-copy, to be something the plan cannot honour. Nothing is written over. */
public class StreamRefusedException(public val reason: TagRefusal) : Exception(reason.name)

/** What a codec decided to do with a file. */
public sealed interface WritePlan {
    /** The edit changes nothing; do not open the file for writing. */
    public data object NoChange : WritePlan

    /** Overwrite [writes] and set the length to [newLength]. The audio is never read or written. */
    public class InPlacePatch(public val writes: List<ByteWrite>, public val newLength: Long) : WritePlan

    /** Build a new file from [segments]; used only when spare space ran out. */
    public class StreamingRewrite(public val segments: List<OutputSegment>) : WritePlan {
        public val newLength: Long get() = segments.sumOf { it.length }
    }

    public class Refused(public val reason: TagRefusal) : WritePlan
}
```

- [ ] **Step 6: Create `WritePlans.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** Executes [WritePlan]s against byte sources and sinks; platforms reuse [stream] for staging. */
public object WritePlans {
    /** Copy granularity: large enough to amortise calls, small enough to never hold a file. */
    public const val COPY_CHUNK: Int = 1 shl 20

    /** Writes the output of [plan] to [sink], reading from [source]. */
    public fun stream(source: RandomAccessSource, plan: WritePlan.StreamingRewrite, sink: ByteSink) {
        for (segment in plan.segments) {
            when (segment) {
                is OutputSegment.Bytes -> sink.write(segment.bytes)
                is OutputSegment.Copy -> copy(source, segment.sourceOffset, segment.length) { sink.write(it) }
                is OutputSegment.Transformed -> {
                    val pass = segment.transform.start()
                    copy(source, segment.sourceOffset, segment.length) { pass.process(it, sink) }
                    pass.finish(sink)
                }
            }
        }
    }

    /** The complete resulting file, or null for [WritePlan.Refused]. For tests and small files. */
    public fun applyInMemory(original: ByteArray, plan: WritePlan): ByteArray? = when (plan) {
        WritePlan.NoChange -> original.copyOf()
        is WritePlan.Refused -> null
        is WritePlan.InPlacePatch -> {
            val out = original.copyOf(plan.newLength.toInt())
            for (write in plan.writes) write.bytes.copyInto(out, write.offset.toInt())
            out
        }
        is WritePlan.StreamingRewrite -> {
            val sink = ByteArraySink()
            stream(ByteArraySource(original), plan, sink)
            sink.toByteArray()
        }
    }

    private inline fun copy(source: RandomAccessSource, offset: Long, length: Long, emit: (ByteArray) -> Unit) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = minOf(COPY_CHUNK.toLong(), end - position).toInt()
            val chunk = source.read(position, count) ?: throw StreamRefusedException(TagRefusal.TRUNCATED)
            emit(chunk)
            position += count
        }
    }
}

/** The smallest set of overwrites that turns a region into its new content. */
internal object ByteDiff {
    /** Differing runs closer than this merge: a few larger writes beat many tiny ones. */
    const val MERGE_GAP = 64

    /** Overwrites turning [old] into [new], both starting at file offset [base]. */
    fun writes(base: Long, old: ByteArray, new: ByteArray): List<ByteWrite> {
        val out = ArrayList<ByteWrite>()
        val common = minOf(old.size, new.size)
        var start = -1
        var last = -1
        for (i in 0 until common) {
            if (old[i] == new[i]) continue
            if (start >= 0 && i - last > MERGE_GAP) {
                out += ByteWrite(base + start, new.copyOfRange(start, last + 1))
                start = -1
            }
            if (start < 0) start = i
            last = i
        }
        if (new.size > common) {
            // Bytes past the old end are always written; a pending run close to them joins in.
            if (start >= 0 && common - last <= MERGE_GAP) {
                out += ByteWrite(base + start, new.copyOfRange(start, new.size))
            } else {
                if (start >= 0) out += ByteWrite(base + start, new.copyOfRange(start, last + 1))
                out += ByteWrite(base + common, new.copyOfRange(common, new.size))
            }
        } else if (start >= 0) {
            out += ByteWrite(base + start, new.copyOfRange(start, last + 1))
        }
        return out
    }

    /** [writes] as a plan: [WritePlan.NoChange] when nothing differs and the length stays. */
    fun patchOrNoChange(base: Long, old: ByteArray, new: ByteArray, newLength: Long, oldLength: Long): WritePlan {
        val writes = writes(base, old, new)
        return if (writes.isEmpty() && newLength == oldLength) {
            WritePlan.NoChange
        } else {
            WritePlan.InPlacePatch(writes, newLength)
        }
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS (all `WritePlansTest` and earlier tests).

- [ ] **Step 8: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagIo.kt \
        core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/WritePlan.kt \
        core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/WritePlans.kt \
        core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Checksums.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/WritePlansTest.kt
git commit -m "feat(tags): write plans that touch only the bytes that changed, and an executor to run them"
```

---
### Task 3: Pictures — image probing, FLAC picture blocks, the cover rule

**Files:**
- Create: `.../library/tags/Pictures.kt`
- Test: `.../library/tags/PicturesTest.kt` (commonTest; also defines `TestImages` used by later tasks)

**Interfaces:**
- Consumes: `CoverEdit` (Task 1).
- Produces:
  - `data class ImageInfo(mime: String, width: Int, height: Int, bitsPerPixel: Int)`; `object ImageProbe { const val JPEG = "image/jpeg"; const val PNG = "image/png"; fun probe(bytes: ByteArray): ImageInfo? }`.
  - `internal class FlacPicture(type, mime, description, width, height, depth, colors, data)` with `encode(): ByteArray`, `companion { const val FRONT_COVER = 3; const val OTHER = 0; fun decode(body: ByteArray): FlacPicture?; fun frontCover(bytes: ByteArray, mime: String): FlacPicture? }`.
  - `internal object CoverTarget { fun index(types: List<Int>): Int?; fun replacement(edit: CoverEdit.Replace): ImageInfo? }` — `replacement` is null unless the bytes probe as the edit's own mime.
  - commonTest `internal object TestImages { fun png(width: Int, height: Int, filler: Int = 64): ByteArray; fun jpeg(width: Int, height: Int, filler: Int = 64): ByteArray }` — header-accurate images (probe-able), filler bytes after.

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Header-accurate test images: enough for [ImageProbe], filler after. Shared by the codec tests. */
internal object TestImages {
    fun png(width: Int, height: Int, filler: Int = 64): ByteArray {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            be32(13) + "IHDR".encodeToByteArray() + be32(width) + be32(height) +
            byteArrayOf(8, 6, 0, 0, 0) + be32(0)
        return header + ByteArray(filler) { (it % 97).toByte() }
    }

    fun jpeg(width: Int, height: Int, filler: Int = 64): ByteArray {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF".encodeToByteArray() +
            byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
        val sof0 = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8) + be16(height) + be16(width) +
            byteArrayOf(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sof0 +
            ByteArray(filler) { (it % 89).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}

internal class PicturesTest {

    @Test
    fun probeReadsPngDimensionsAndDepth() {
        assertEquals(ImageInfo(ImageProbe.PNG, 600, 400, 32), ImageProbe.probe(TestImages.png(600, 400)))
    }

    @Test
    fun probeReadsJpegDimensionsPastLeadingSegments() {
        assertEquals(ImageInfo(ImageProbe.JPEG, 1000, 750, 24), ImageProbe.probe(TestImages.jpeg(1000, 750)))
    }

    @Test
    fun probeRejectsEverythingElse() {
        assertNull(ImageProbe.probe(ByteArray(100)))
        assertNull(ImageProbe.probe("GIF89a".encodeToByteArray() + ByteArray(40)))
        assertNull(ImageProbe.probe(TestImages.png(600, 400).copyOf(20)))
    }

    @Test
    fun flacPictureRoundTripsByteExactly() {
        val picture = FlacPicture(3, "image/png", "Обложка", 600, 400, 32, 0, TestImages.png(600, 400))
        val encoded = picture.encode()
        val decoded = assertNotNull(FlacPicture.decode(encoded))
        assertEquals(3, decoded.type)
        assertEquals("image/png", decoded.mime)
        assertEquals("Обложка", decoded.description)
        assertEquals(600, decoded.width)
        assertContentEquals(picture.data, decoded.data)
        assertContentEquals(encoded, decoded.encode())
    }

    @Test
    fun flacPictureDecodeRejectsLengthsPastTheEnd() {
        val encoded = FlacPicture(3, "image/png", "", 1, 1, 32, 0, ByteArray(10)).encode()
        assertNull(FlacPicture.decode(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun frontCoverRequiresTheDeclaredFormat() {
        val png = TestImages.png(10, 20)
        val cover = assertNotNull(FlacPicture.frontCover(png, ImageProbe.PNG))
        assertEquals(FlacPicture.FRONT_COVER, cover.type)
        assertEquals(10, cover.width)
        assertEquals(20, cover.height)
        assertNull(FlacPicture.frontCover(png, ImageProbe.JPEG))
    }

    @Test
    fun coverTargetPrefersTheFrontCoverThenOther() {
        assertEquals(1, CoverTarget.index(listOf(4, 3, 3)))
        assertEquals(1, CoverTarget.index(listOf(4, 0, 5)))
        assertEquals(1, CoverTarget.index(listOf(0, 3)))
        assertNull(CoverTarget.index(listOf(4, 5)))
        assertNull(CoverTarget.index(emptyList()))
    }

    @Test
    fun replacementNeedsARealImageOfTheNamedType() {
        assertNotNull(CoverTarget.replacement(CoverEdit.Replace(TestImages.jpeg(5, 5), ImageProbe.JPEG)))
        assertNull(CoverTarget.replacement(CoverEdit.Replace(TestImages.jpeg(5, 5), ImageProbe.PNG)))
        assertNull(CoverTarget.replacement(CoverEdit.Replace(ByteArray(50), ImageProbe.JPEG)))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `ImageProbe`, `ImageInfo`, `FlacPicture`, `CoverTarget` unresolved.

- [ ] **Step 3: Create `Pictures.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** What a picture's header says about it. [bitsPerPixel] is FLAC's "colour depth". */
public data class ImageInfo(val mime: String, val width: Int, val height: Int, val bitsPerPixel: Int)

/** Reads just enough of a JPEG or PNG header to name and size it. */
public object ImageProbe {
    public const val JPEG: String = "image/jpeg"
    public const val PNG: String = "image/png"

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** The format and dimensions of a JPEG or PNG, or null for anything else or a damaged header. */
    public fun probe(bytes: ByteArray): ImageInfo? = png(bytes) ?: jpeg(bytes)

    private fun png(b: ByteArray): ImageInfo? {
        if (b.size < 26) return null
        for (i in PNG_SIGNATURE.indices) if (b[i] != PNG_SIGNATURE[i]) return null
        if (b.decodeToString(12, 16) != "IHDR") return null
        val width = be32(b, 16)
        val height = be32(b, 20)
        val bitDepth = b[24].toInt() and 0xFF
        val channels = when (b[25].toInt() and 0xFF) {
            0 -> 1
            2 -> 3
            3 -> 1
            4 -> 2
            6 -> 4
            else -> return null
        }
        if (width <= 0 || height <= 0) return null
        return ImageInfo(PNG, width, height, bitDepth * channels)
    }

    private fun jpeg(b: ByteArray): ImageInfo? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) {
                i += 1
                continue
            }
            if (marker == 0x01 || marker in 0xD0..0xD9) {
                i += 2
                continue
            }
            val length = be16(b, i + 2)
            if (length < 2) return null
            val isFrameHeader = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrameHeader) {
                if (i + 10 > b.size) return null
                val precision = b[i + 4].toInt() and 0xFF
                val height = be16(b, i + 5)
                val width = be16(b, i + 7)
                val components = b[i + 9].toInt() and 0xFF
                if (width <= 0 || height <= 0) return null
                return ImageInfo(JPEG, width, height, precision * components)
            }
            i += 2 + length
        }
        return null
    }

    private fun be16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}

/**
 * The FLAC `METADATA_BLOCK_PICTURE` structure — a FLAC PICTURE block's body, and (base64-encoded)
 * the Ogg `METADATA_BLOCK_PICTURE` comment. All integers big-endian.
 */
internal class FlacPicture(
    val type: Int,
    val mime: String,
    val description: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val colors: Int,
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        val mimeBytes = mime.encodeToByteArray()
        val descriptionBytes = description.encodeToByteArray()
        val out = ByteArray(32 + mimeBytes.size + descriptionBytes.size + data.size)
        var p = 0
        fun put32(value: Int) {
            out[p] = (value ushr 24).toByte()
            out[p + 1] = (value ushr 16).toByte()
            out[p + 2] = (value ushr 8).toByte()
            out[p + 3] = value.toByte()
            p += 4
        }
        fun put(bytes: ByteArray) {
            bytes.copyInto(out, p)
            p += bytes.size
        }
        put32(type)
        put32(mimeBytes.size)
        put(mimeBytes)
        put32(descriptionBytes.size)
        put(descriptionBytes)
        put32(width)
        put32(height)
        put32(depth)
        put32(colors)
        put32(data.size)
        put(data)
        return out
    }

    companion object {
        const val FRONT_COVER = 3
        const val OTHER = 0

        fun decode(body: ByteArray): FlacPicture? {
            var p = 0
            fun get32(): Long? {
                if (p + 4 > body.size) return null
                val value = ((body[p].toLong() and 0xFF) shl 24) or ((body[p + 1].toLong() and 0xFF) shl 16) or
                    ((body[p + 2].toLong() and 0xFF) shl 8) or (body[p + 3].toLong() and 0xFF)
                p += 4
                return value
            }
            fun take(length: Long): ByteArray? {
                if (length < 0 || length > body.size - p) return null
                val out = body.copyOfRange(p, p + length.toInt())
                p += length.toInt()
                return out
            }
            val type = get32() ?: return null
            val mime = take(get32() ?: return null)?.decodeToString() ?: return null
            val description = take(get32() ?: return null)?.decodeToString() ?: return null
            val width = get32() ?: return null
            val height = get32() ?: return null
            val depth = get32() ?: return null
            val colors = get32() ?: return null
            val data = take(get32() ?: return null) ?: return null
            if (p != body.size) return null
            return FlacPicture(type.toInt(), mime, description, width.toInt(), height.toInt(), depth.toInt(), colors.toInt(), data)
        }

        /** A front-cover picture for [bytes], or null unless they probe as a [mime] image. */
        fun frontCover(bytes: ByteArray, mime: String): FlacPicture? {
            val info = ImageProbe.probe(bytes)?.takeIf { it.mime == mime } ?: return null
            return FlacPicture(FRONT_COVER, mime, "", info.width, info.height, info.bitsPerPixel, 0, bytes)
        }
    }
}

/** Which picture is "the cover", in every format that types its pictures. */
internal object CoverTarget {
    /** Index of the first front cover, else of the first "other" picture, else null. */
    fun index(types: List<Int>): Int? =
        types.indexOf(FlacPicture.FRONT_COVER).takeIf { it >= 0 }
            ?: types.indexOf(FlacPicture.OTHER).takeIf { it >= 0 }

    /** The new cover's header facts, or null when its bytes are not the image its mime claims. */
    fun replacement(edit: CoverEdit.Replace): ImageInfo? =
        ImageProbe.probe(edit.bytes)?.takeIf { it.mime == edit.mime }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Pictures.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/PicturesTest.kt
git commit -m "feat(tags): know a cover when it sees one — JPEG and PNG headers, FLAC picture blocks"
```

---

### Task 4: Vorbis comments — byte-exact block codec and field mapping

**Files:**
- Create: `.../library/tags/VorbisComments.kt`
- Test: `.../library/tags/VorbisCommentsTest.kt` (commonTest)

**Interfaces:**
- Consumes: `TagEdits`, `CreditedArtists` (Task 1); `TrackNumbers.parse(value: String?): Int?` (existing, package `io.github.nikitasud.latentjam.library`); `TagFacts.splitArtists(value: String): List<String>` (existing, internal).
- Produces:
  - `internal class VorbisEntry(raw: ByteArray)` with `key: String` (upper-cased), `value: String`, `raw`; `companion fun of(key: String, value: String): VorbisEntry`.
  - `internal class VorbisComments(vendor: ByteArray, entries: List<VorbisEntry>)` with `encode(): ByteArray`; `companion fun decode(bytes: ByteArray, offset: Int): Pair<VorbisComments, Int>?` (the Int is the offset just past the block; null when any length runs past the end or any entry lacks `=`).
  - `internal class VorbisFieldValues(title, artist, album, albumArtist, genre, year: String?, trackNumber, trackTotal, discNumber, discTotal: Int?, lyrics: String?, artists: List<String>)`.
  - `internal object VorbisFields { val MANAGED: Set<String>; fun read(entries: List<VorbisEntry>): VorbisFieldValues; fun apply(entries: List<VorbisEntry>, edits: TagEdits): List<VorbisEntry> }` — `apply` handles every text and number field and `ARTISTS`; **not** pictures (containers own those).

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class VorbisCommentsTest {

    private fun entries(vararg pairs: Pair<String, String>) = pairs.map { VorbisEntry.of(it.first, it.second) }

    private fun keys(list: List<VorbisEntry>) = list.map { it.raw.decodeToString() }

    @Test
    fun blockRoundTripsByteExactlyEvenWithInvalidUtf8() {
        val odd = VorbisEntry("COMMENT=".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))
        val block = VorbisComments("vendor ✓".encodeToByteArray(), entries("TITLE" to "Заголовок") + odd)
        val encoded = block.encode()
        val (decoded, end) = assertNotNull(VorbisComments.decode(byteArrayOf(9, 9) + encoded, 2))
        assertEquals(encoded.size + 2, end)
        assertContentEquals(encoded, decoded.encode())
        assertEquals("Заголовок", decoded.entries[0].value)
    }

    @Test
    fun decodeRejectsTruncatedAndMalformedBlocks() {
        val encoded = VorbisComments("v".encodeToByteArray(), entries("TITLE" to "x")).encode()
        assertNull(VorbisComments.decode(encoded.copyOf(encoded.size - 1), 0))
        val noEquals = VorbisComments("v".encodeToByteArray(), listOf(VorbisEntry("TITLE".encodeToByteArray()))).encode()
        assertNull(VorbisComments.decode(noEquals, 0))
    }

    @Test
    fun readMapsFieldsAliasesAndEmbeddedTotals() {
        val values = VorbisFields.read(
            entries(
                "title" to "Song",
                "ARTIST" to "A",
                "ARTIST" to "B",
                "ALBUM ARTIST" to "Various",
                "GENRE" to "Rock",
                "GENRE" to "Pop",
                "YEAR" to "1999",
                "TRACKNUMBER" to "3/12",
                "DISCNUMBER" to "1",
                "TOTALDISCS" to "2",
                "UNSYNCEDLYRICS" to "  words \n",
                "ARTISTS" to "A",
                "ARTISTS" to "B",
            ),
        )
        assertEquals("Song", values.title)
        assertEquals("A; B", values.artist)
        assertEquals("Various", values.albumArtist)
        assertEquals("Rock; Pop", values.genre)
        assertEquals("1999", values.year)
        assertEquals(3, values.trackNumber)
        assertEquals(12, values.trackTotal)
        assertEquals(1, values.discNumber)
        assertEquals(2, values.discTotal)
        assertEquals("words", values.lyrics)
        assertEquals(listOf("A", "B"), values.artists)
    }

    @Test
    fun applySetsRemovesAndKeepsPositionAndUnmanagedEntries() {
        val before = entries("TITLE" to "Old", "REPLAYGAIN_TRACK_GAIN" to "-6.2 dB", "ALBUM" to "Rec")
        val after = VorbisFields.apply(before, TagEdits(title = "New", album = "", genre = "Jazz"))
        assertEquals(listOf("TITLE=New", "REPLAYGAIN_TRACK_GAIN=-6.2 dB", "GENRE=Jazz"), keys(after))
        assertContentEquals(before[1].raw, after[1].raw)
    }

    @Test
    fun applyCollapsesAliasesIntoTheCanonicalField() {
        val before = entries("ALBUM ARTIST" to "x", "ALBUMARTIST" to "y", "TOTALTRACKS" to "9")
        val after = VorbisFields.apply(before, TagEdits(albumArtist = "Various", trackTotal = "10"))
        assertEquals(listOf("ALBUMARTIST=Various", "TRACKTOTAL=10"), keys(after))
    }

    @Test
    fun genreAndArtistAreWrittenExactlyAsTyped() {
        val after = VorbisFields.apply(entries("GENRE" to "a", "GENRE" to "b"), TagEdits(genre = "Rock;Pop"))
        assertEquals(listOf("GENRE=Rock;Pop"), keys(after))
        assertEquals("Rock;Pop", VorbisFields.read(after).genre)
    }

    @Test
    fun creditedArtistsAreRewrittenOnlyWhenTheFileHasThem() {
        val with = entries("ARTIST" to "A; B", "ARTISTS" to "A", "ARTISTS" to "B")
        assertEquals(
            listOf("ARTIST=X; Y", "ARTISTS=X", "ARTISTS=Y"),
            keys(VorbisFields.apply(with, TagEdits(artist = "X; Y"))),
        )
        assertEquals(listOf("ARTIST=Solo"), keys(VorbisFields.apply(with, TagEdits(artist = "Solo"))))
        val without = entries("ARTIST" to "A")
        assertEquals(listOf("ARTIST=X; Y"), keys(VorbisFields.apply(without, TagEdits(artist = "X; Y"))))
    }

    @Test
    fun numberEditKeepsATotalThatWasEmbeddedInThePair() {
        val after = VorbisFields.apply(entries("TRACKNUMBER" to "3/12"), TagEdits(trackNumber = "4"))
        assertEquals(listOf("TRACKNUMBER=4", "TRACKTOTAL=12"), keys(after))
    }

    @Test
    fun totalEditSplitsAnEmbeddedPair() {
        val after = VorbisFields.apply(entries("TRACKNUMBER" to "3/12"), TagEdits(trackTotal = "10"))
        assertEquals(listOf("TRACKNUMBER=3", "TRACKTOTAL=10"), keys(after))
        val read = VorbisFields.read(after)
        assertEquals(3, read.trackNumber)
        assertEquals(10, read.trackTotal)
    }

    @Test
    fun restatingAValueKeepsTheOriginalEntry() {
        val before = entries("title" to "Song", "ALBUM" to "Rec")
        val after = VorbisFields.apply(before, TagEdits(title = "Song"))
        assertContentEquals(before[0].raw, after[0].raw)
        assertEquals(keys(before), keys(after))
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = ("Ночь, улица, фонарь, аптека. 夜の街を歩く。\n").repeat(400).trim()
        val after = VorbisFields.apply(emptyList(), TagEdits(lyrics = lyrics))
        val block = VorbisComments("v".encodeToByteArray(), after).encode()
        val (decoded, _) = assertNotNull(VorbisComments.decode(block, 0))
        assertEquals(lyrics, VorbisFields.read(decoded.entries).lyrics)
    }

    @Test
    fun managedKeysCoverEveryWrittenField() {
        val written = VorbisFields.apply(
            emptyList(),
            TagEdits(
                title = "t", artist = "a", album = "b", albumArtist = "c", genre = "g", year = "1",
                trackNumber = "1", trackTotal = "2", discNumber = "1", discTotal = "2", lyrics = "l",
            ),
        )
        assertEquals(emptyList(), written.map { it.key }.filterNot { it in VorbisFields.MANAGED })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `VorbisEntry`, `VorbisComments`, `VorbisFields` unresolved.

- [ ] **Step 3: Create `VorbisComments.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.TrackNumbers

/**
 * One `KEY=value` comment, kept as its original bytes. Entries the editor does not rewrite are
 * re-emitted from [raw], so invalid UTF-8 or odd casing in other people's tags survives exactly.
 */
internal class VorbisEntry(val raw: ByteArray) {
    private val separator = raw.indexOf('='.code.toByte())

    val isWellFormed: Boolean get() = separator > 0

    /** Field name, upper-cased: Vorbis field names are case-insensitive. */
    val key: String get() = raw.decodeToString(0, separator).uppercase()

    val value: String get() = raw.decodeToString(separator + 1, raw.size)

    companion object {
        fun of(key: String, value: String): VorbisEntry = VorbisEntry("$key=$value".encodeToByteArray())
    }
}

/** A Vorbis comment block: vendor string, then entries. No framing bit — containers add their own. */
internal class VorbisComments(val vendor: ByteArray, val entries: List<VorbisEntry>) {

    fun encode(): ByteArray {
        val out = ByteArray(8 + vendor.size + entries.sumOf { 4 + it.raw.size })
        var p = 0
        fun putLe32(value: Int) {
            out[p] = value.toByte()
            out[p + 1] = (value ushr 8).toByte()
            out[p + 2] = (value ushr 16).toByte()
            out[p + 3] = (value ushr 24).toByte()
            p += 4
        }
        putLe32(vendor.size)
        vendor.copyInto(out, p)
        p += vendor.size
        putLe32(entries.size)
        for (entry in entries) {
            putLe32(entry.raw.size)
            entry.raw.copyInto(out, p)
            p += entry.raw.size
        }
        return out
    }

    companion object {
        /** The block starting at [offset] and the offset just past it; null when it does not add up. */
        fun decode(bytes: ByteArray, offset: Int): Pair<VorbisComments, Int>? {
            var p = offset
            fun le32(): Long? {
                if (p < 0 || p + 4 > bytes.size) return null
                val value = (bytes[p].toLong() and 0xFF) or ((bytes[p + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[p + 2].toLong() and 0xFF) shl 16) or ((bytes[p + 3].toLong() and 0xFF) shl 24)
                p += 4
                return value
            }
            fun take(length: Long): ByteArray? {
                if (length > bytes.size - p) return null
                val out = bytes.copyOfRange(p, p + length.toInt())
                p += length.toInt()
                return out
            }
            val vendor = take(le32() ?: return null) ?: return null
            val count = le32() ?: return null
            if (count > (bytes.size - p) / 4L) return null
            val entries = ArrayList<VorbisEntry>(count.toInt())
            for (i in 0 until count.toInt()) {
                val entry = VorbisEntry(take(le32() ?: return null) ?: return null)
                if (!entry.isWellFormed) return null
                entries += entry
            }
            return VorbisComments(vendor, entries) to p
        }
    }
}

/** What a comment block says, in the editor's terms. */
internal class VorbisFieldValues(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: String?,
    val trackNumber: Int?,
    val trackTotal: Int?,
    val discNumber: Int?,
    val discTotal: Int?,
    val lyrics: String?,
    val artists: List<String>,
)

/** The editor's fields mapped onto Vorbis comment names, shared by FLAC, Opus and Vorbis. */
internal object VorbisFields {
    private const val TITLE = "TITLE"
    private const val ARTIST = "ARTIST"
    private const val ARTISTS = "ARTISTS"
    private const val ALBUM = "ALBUM"
    private const val ALBUM_ARTIST = "ALBUMARTIST"
    private const val GENRE = "GENRE"
    private const val DATE = "DATE"
    private const val TRACK_NUMBER = "TRACKNUMBER"
    private const val TRACK_TOTAL = "TRACKTOTAL"
    private const val DISC_NUMBER = "DISCNUMBER"
    private const val DISC_TOTAL = "DISCTOTAL"
    private const val LYRICS = "LYRICS"

    /** Other names the same field is found under; read as the field, removed when it is written. */
    private val ALIASES: Map<String, List<String>> = mapOf(
        ALBUM_ARTIST to listOf("ALBUM ARTIST", "ALBUM_ARTIST"),
        DATE to listOf("YEAR"),
        TRACK_TOTAL to listOf("TOTALTRACKS"),
        DISC_TOTAL to listOf("TOTALDISCS"),
        LYRICS to listOf("UNSYNCEDLYRICS"),
    )

    /** Every name whose value the editor owns; anything else is preserved untouched. */
    val MANAGED: Set<String> =
        setOf(TITLE, ARTIST, ARTISTS, ALBUM, ALBUM_ARTIST, GENRE, DATE, TRACK_NUMBER, TRACK_TOTAL, DISC_NUMBER, DISC_TOTAL, LYRICS) +
            ALIASES.values.flatten()

    private fun names(key: String): List<String> = listOf(key) + ALIASES[key].orEmpty()

    fun read(entries: List<VorbisEntry>): VorbisFieldValues {
        fun all(key: String): List<String> =
            names(key).flatMap { name -> entries.filter { it.key == name }.map { it.value } }.filter { it.isNotEmpty() }
        fun first(key: String): String? = all(key).firstOrNull()
        fun joined(key: String): String? = all(key).joinToString("; ").ifEmpty { null }
        val trackRaw = first(TRACK_NUMBER)
        val discRaw = first(DISC_NUMBER)
        return VorbisFieldValues(
            title = first(TITLE),
            artist = joined(ARTIST),
            album = first(ALBUM),
            albumArtist = first(ALBUM_ARTIST),
            genre = joined(GENRE),
            year = first(DATE),
            trackNumber = TrackNumbers.parse(trackRaw),
            trackTotal = TrackNumbers.parse(first(TRACK_TOTAL)) ?: embeddedTotal(trackRaw),
            discNumber = TrackNumbers.parse(discRaw),
            discTotal = TrackNumbers.parse(first(DISC_TOTAL)) ?: embeddedTotal(discRaw),
            lyrics = first(LYRICS)?.trim()?.ifEmpty { null },
            artists = all(ARTISTS).flatMap { TagFacts.splitArtists(it) },
        )
    }

    fun apply(entries: List<VorbisEntry>, edits: TagEdits): List<VorbisEntry> {
        var out = entries
        out = set(out, TITLE, edits.title)
        val hadArtists = out.any { it.key == ARTISTS }
        out = set(out, ARTIST, edits.artist)
        if (edits.artist != null && hadArtists) {
            out = setMany(out, ARTISTS, CreditedArtists.fromDisplay(edits.artist))
        }
        out = set(out, ALBUM, edits.album)
        out = set(out, ALBUM_ARTIST, edits.albumArtist)
        out = set(out, GENRE, edits.genre)
        out = set(out, DATE, edits.year)
        out = numbers(out, TRACK_NUMBER, TRACK_TOTAL, edits.trackNumber, edits.trackTotal)
        out = numbers(out, DISC_NUMBER, DISC_TOTAL, edits.discNumber, edits.discTotal)
        out = set(out, LYRICS, edits.lyrics?.trim())
        return out
    }

    private fun embeddedTotal(pair: String?): Int? =
        pair?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }?.let(TrackNumbers::parse)

    /** Null keeps; "" removes; anything else becomes the single value of [key]. */
    private fun set(entries: List<VorbisEntry>, key: String, value: String?): List<VorbisEntry> = when {
        value == null -> entries
        value.isEmpty() -> setMany(entries, key, emptyList())
        else -> setMany(entries, key, listOf(value))
    }

    /** Replaces every entry of [key] (and its aliases) with [values], at the first one's position. */
    private fun setMany(entries: List<VorbisEntry>, key: String, values: List<String>): List<VorbisEntry> {
        val doomed = names(key).toSet()
        val out = ArrayList<VorbisEntry>(entries.size + values.size)
        var placed = false
        for (entry in entries) {
            if (entry.key !in doomed) {
                out += entry
                continue
            }
            if (!placed) {
                values.forEach { out += reuse(entries, key, it) }
                placed = true
            }
        }
        if (!placed) values.forEach { out += reuse(entries, key, it) }
        return out
    }

    /** An existing [key] entry already saying [value] is kept byte for byte (its key's casing included). */
    private fun reuse(entries: List<VorbisEntry>, key: String, value: String): VorbisEntry =
        entries.firstOrNull { it.key == key && it.value == value } ?: VorbisEntry.of(key, value)

    private fun numbers(
        entries: List<VorbisEntry>,
        numberKey: String,
        totalKey: String,
        number: String?,
        total: String?,
    ): List<VorbisEntry> {
        if (number == null && total == null) return entries
        val currentRaw = entries.firstOrNull { it.key == numberKey }?.value
        val embedded = embeddedTotal(currentRaw)
        val hasTotalField = entries.any { it.key in names(totalKey) }
        var out = entries
        if (number != null) {
            out = set(out, numberKey, number)
            // "3/12" carried its total inside; keep it when the total itself was not edited.
            if (total == null && embedded != null && !hasTotalField) out = set(out, totalKey, embedded.toString())
        } else if (currentRaw != null && currentRaw.contains('/')) {
            // The total is about to live in its own field: the pair must not keep a stale copy.
            TrackNumbers.parse(currentRaw)?.let { out = set(out, numberKey, it.toString()) }
        }
        if (total != null) out = set(out, totalKey, total)
        return out
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/VorbisComments.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/VorbisCommentsTest.kt
git commit -m "feat(tags): Vorbis comments rewritten field by field, everything else kept byte for byte"
```

---
### Task 5: ID3 — album artist, numbers, lyrics, cover, credited artists, 16 KiB padding

**Files:**
- Modify: `.../library/tags/Id3Tags.kt`
- Test: `.../library/tags/Id3FieldsTest.kt` (commonTest)

**Interfaces:**
- Consumes: `TagEdits`, `CoverEdit`, `CoverInfo`, `CreditedArtists`, `TagNumbers` (Task 1); `Crc32` (Task 1); `TagSpace` (Task 2); `CoverTarget`, `FlacPicture` constants, `TestImages`, `ImageProbe` (Task 3); existing `Id3Codec`, `Id3Text`, `TagFacts.splitArtists`, `TrackNumbers.parse`.
- Produces (all in `object Id3Tags`):
  - `internal class Id3Fields(version, totalLength, title, artist, album, albumArtist, genre, year, track: String?, disc: String?, lyrics, cover: CoverInfo?, otherPictures: Int, nextCover: CoverInfo?, pictures: List<Long>, artists: List<String>, unmanaged: List<String>)` and `internal fun readFields(prefix: ByteArray): Id3Fields?`.
  - `internal fun wouldChange(prefix: ByteArray, edits: TagEdits): Boolean` — false when applying `edits` leaves every frame identical.
  - `internal fun canPrependTag(data: ByteArray): Boolean` (was private).
  - `buildUpdate`/`updateTag` now apply every `TagEdits` field; tags that grow get `TagSpace.SPARE_BYTES` of padding.

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.Id3TestTags.artFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.commentFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.latin1Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.mp3Payload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class Id3FieldsTest {

    private fun file(major: Int, vararg frames: TestFrame, padding: Int = 0): ByteArray =
        Id3TestTags.build(major, frames.toList(), padding) + mp3Payload()

    private fun edit(file: ByteArray, edits: TagEdits): ByteArray = assertNotNull(updateId3Tag(file, edits))

    private fun fields(bytes: ByteArray): Id3Tags.Id3Fields = assertNotNull(Id3Tags.readFields(bytes))

    /** Latin-1 bytes, matching the encoding byte 0 the fixtures declare. */
    private fun latin1(text: String) = ByteArray(text.length) { text[it].code.toByte() }

    private fun uslt(language: String, descriptor: String, text: String) = TestFrame(
        "USLT",
        byteArrayOf(0) + latin1(language) + latin1(descriptor) + byteArrayOf(0) + latin1(text),
    )

    private fun picture(type: Int, data: ByteArray, mime: String = "image/png") = TestFrame(
        "APIC",
        byteArrayOf(0) + mime.encodeToByteArray() + byteArrayOf(0, type.toByte(), 0) + data,
    )

    private fun txxx(description: String, value: String) = TestFrame(
        "TXXX",
        byteArrayOf(0) + description.encodeToByteArray() + byteArrayOf(0) + value.encodeToByteArray(),
    )

    @Test
    fun albumArtistNumbersAndLyricsRoundTripInBothVersions() {
        for (major in listOf(3, 4)) {
            val out = edit(
                file(major, TestFrame("TIT2", latin1Body("t"))),
                TagEdits(
                    albumArtist = "Various Artists",
                    trackNumber = "3",
                    trackTotal = "12",
                    discNumber = "1",
                    discTotal = "2",
                    lyrics = "Первая строка\nSecond line",
                ),
            )
            val read = fields(out)
            assertEquals("Various Artists", read.albumArtist)
            assertEquals("3/12", read.track)
            assertEquals("1/2", read.disc)
            assertEquals("Первая строка\nSecond line", read.lyrics)
            assertEquals("t", read.title)
        }
    }

    @Test
    fun numberAndTotalEditIndependently() {
        val base = file(3, TestFrame("TRCK", latin1Body("3/12")))
        assertEquals("4/12", fields(edit(base, TagEdits(trackNumber = "4"))).track)
        assertEquals("3/10", fields(edit(base, TagEdits(trackTotal = "10"))).track)
        assertEquals("3", fields(edit(base, TagEdits(trackTotal = ""))).track)
        assertNull(fields(edit(base, TagEdits(trackNumber = ""))).track)
    }

    @Test
    fun lyricsReplaceTheReadableFrameAndKeepOtherLanguages() {
        val empty = uslt("eng", "", "")
        val german = uslt("deu", "", "Deutsch")
        val out = edit(file(3, empty, german), TagEdits(lyrics = "New"))
        val frames = Id3TestTags.framesOf(out).filter { it.id == "USLT" }
        assertEquals(2, frames.size)
        assertContentEquals(empty.body, frames[0].body)
        assertEquals("deu", frames[1].body.copyOfRange(1, 4).decodeToString())
        assertEquals("New", fields(out).lyrics)
    }

    @Test
    fun removingLyricsRemovesOnlyTheReadableFrame() {
        val out = edit(file(3, uslt("eng", "", "Words"), uslt("deu", "", "Wörter")), TagEdits(lyrics = ""))
        val frames = Id3TestTags.framesOf(out).filter { it.id == "USLT" }
        assertEquals(1, frames.size)
        assertEquals("Wörter", fields(out).lyrics)
    }

    @Test
    fun coverReplaceActsOnTheFrontCoverAndKeepsOthers() {
        val back = picture(type = 4, data = byteArrayOf(1, 2, 3))
        val out = edit(
            file(3, back, artFrame(size = 100)),
            TagEdits(cover = CoverEdit.Replace(TestImages.jpeg(10, 10), ImageProbe.JPEG)),
        )
        val read = fields(out)
        assertEquals(CoverInfo.of(TestImages.jpeg(10, 10), ImageProbe.JPEG), read.cover)
        assertEquals(1, read.otherPictures)
        val pictures = Id3TestTags.framesOf(out).filter { it.id == "APIC" }
        assertEquals(2, pictures.size)
        assertContentEquals(back.body, pictures[0].body)
    }

    @Test
    fun typeZeroPictureIsTheCoverWhenNoFrontCoverExists() {
        val back = picture(type = 4, data = byteArrayOf(1))
        val other = picture(type = 0, data = byteArrayOf(2))
        val before = file(3, back, other)
        assertEquals(CoverInfo.of(byteArrayOf(2), "image/png"), fields(before).cover)
        val out = edit(before, TagEdits(cover = CoverEdit.Replace(TestImages.png(4, 4), ImageProbe.PNG)))
        val pictures = Id3TestTags.framesOf(out).filter { it.id == "APIC" }
        assertContentEquals(back.body, pictures[0].body)
        assertEquals(CoverInfo.of(TestImages.png(4, 4), ImageProbe.PNG), fields(out).cover)
        assertEquals(1, fields(out).otherPictures)
    }

    @Test
    fun coverRemoveDropsOnlyTheTarget() {
        val back = picture(type = 4, data = byteArrayOf(1))
        val out = edit(file(3, artFrame(size = 10), back), TagEdits(cover = CoverEdit.Remove))
        val pictures = Id3TestTags.framesOf(out).filter { it.id == "APIC" }
        assertEquals(1, pictures.size)
        assertContentEquals(back.body, pictures[0].body)
        assertNull(fields(out).cover)
    }

    @Test
    fun coverIsAddedWhenTheFileHasNone() {
        val jpeg = TestImages.jpeg(8, 8)
        val out = edit(file(4, TestFrame("TIT2", latin1Body("t"))), TagEdits(cover = CoverEdit.Replace(jpeg, ImageProbe.JPEG)))
        assertEquals(CoverInfo.of(jpeg, ImageProbe.JPEG), fields(out).cover)
        assertEquals(0, fields(out).otherPictures)
    }

    @Test
    fun creditedArtistsAreRewrittenOnArtistEdit() {
        val base = file(4, TestFrame("TPE1", latin1Body("A; B")), txxx("ARTISTS", "A\u0000B"))
        assertEquals(listOf("A", "B"), fields(base).artists)
        assertEquals(listOf("X", "Y"), fields(edit(base, TagEdits(artist = "X; Y"))).artists)
        val solo = edit(base, TagEdits(artist = "Solo"))
        assertEquals(emptyList(), fields(solo).artists)
        assertTrue(Id3TestTags.framesOf(solo).none { it.id == "TXXX" })
        val plain = file(3, TestFrame("TPE1", latin1Body("A")))
        assertEquals(emptyList(), fields(edit(plain, TagEdits(artist = "X; Y"))).artists)
    }

    @Test
    fun growingATagLeavesSixteenKibibytesOfPadding() {
        val base = file(3, TestFrame("TIT2", latin1Body("a")))
        val out = edit(base, TagEdits(title = "a".repeat(100)))
        val frameBytes = 10 + 1 + 100
        assertEquals(10 + frameBytes + TagSpace.SPARE_BYTES, Id3Tags.tagLength(out))
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = "Ночь, улица, фонарь, аптека. 夜の街を歩く。\n".repeat(400).trim()
        for (major in listOf(3, 4)) {
            assertEquals(lyrics, fields(edit(file(major), TagEdits(lyrics = lyrics))).lyrics)
        }
    }

    @Test
    fun unmanagedFramesSurviveEveryEdit() {
        val base = file(3, TestFrame("TIT2", latin1Body("t")), commentFrame("keep me"), TestFrame("PRIV", byteArrayOf(1, 2, 3)))
        val out = edit(
            base,
            TagEdits(title = "x", albumArtist = "y", trackNumber = "1", lyrics = "z", cover = CoverEdit.Replace(TestImages.png(2, 2), ImageProbe.PNG)),
        )
        assertEquals(fields(base).unmanaged, fields(out).unmanaged)
        assertEquals(2, fields(out).unmanaged.size)
    }

    @Test
    fun restatingAUtf16TitleKeepsTheFrame() {
        val title = TestFrame("TIT2", Id3TestTags.utf16Body("Song"))
        val base = file(3, title)
        assertFalse(Id3Tags.wouldChange(base, TagEdits(title = "Song")))
        val out = edit(base, TagEdits(album = "Record"))
        assertContentEquals(title.body, Id3TestTags.frameBody(out, "TIT2"))
    }

    @Test
    fun wouldChangeIsFalseForEditsThatRestateTheTag() {
        val base = file(3, TestFrame("TIT2", latin1Body("t")), TestFrame("TRCK", latin1Body("3/12")))
        assertFalse(Id3Tags.wouldChange(base, TagEdits(title = "t", trackNumber = "3")))
        assertTrue(Id3Tags.wouldChange(base, TagEdits(title = "u")))
        assertFalse(Id3Tags.wouldChange(mp3Payload(), TagEdits(title = "")))
        assertTrue(Id3Tags.wouldChange(mp3Payload(), TagEdits(title = "new")))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `Id3Tags.readFields`, `Id3Tags.Id3Fields`, `Id3Tags.wouldChange` unresolved.

- [ ] **Step 3: Extend `Id3Tags.kt`**

3a. Add these imports at the top of the file (after the `package` line):

```kotlin
import io.github.nikitasud.latentjam.library.TrackNumbers
```

3b. Replace `private const val PADDING = 1024` with:

```kotlin
    private const val PADDING = TagSpace.SPARE_BYTES
```

and add next to the other frame constants:

```kotlin
    private const val FRAME_ALBUM_ARTIST = "TPE2"
    private const val FRAME_TRACK = "TRCK"
    private const val FRAME_DISC = "TPOS"
    private const val FRAME_PICTURE = "APIC"
    private const val ARTISTS_DESCRIPTION = "ARTISTS"
```

3c. Replace the whole `private fun applyEdits(...)` with:

```kotlin
    private fun applyEdits(
        version: Id3Version,
        frames: List<Id3RawFrame>,
        edits: TagEdits,
    ): List<Id3RawFrame> {
        var result = frames
        result = setText(version, result, FRAME_TITLE, edits.title)
        result = setText(version, result, FRAME_ARTIST, edits.artist)
        if (edits.artist != null) result = setCreditedArtists(version, result, edits.artist)
        result = setText(version, result, FRAME_ALBUM, edits.album)
        result = setText(version, result, FRAME_ALBUM_ARTIST, edits.albumArtist)
        result = setText(version, result, FRAME_GENRE, edits.genre)

        val yearFrame = if (version == Id3Version.V2_4) FRAME_YEAR_V24 else FRAME_YEAR_V23
        val staleYear = if (version == Id3Version.V2_4) FRAME_YEAR_V23 else FRAME_YEAR_V24
        result = setText(
            version = version,
            frames = result,
            id = yearFrame,
            value = edits.year?.let { normaliseYear(it, version) },
            // Drop the other version's year frame so the file cannot end up
            // carrying two years that disagree.
            alsoRemove = listOf(staleYear),
        )
        result = setText(
            version,
            result,
            FRAME_TRACK,
            composeNumber(textIn(version, result, FRAME_TRACK), edits.trackNumber, edits.trackTotal),
        )
        result = setText(
            version,
            result,
            FRAME_DISC,
            composeNumber(textIn(version, result, FRAME_DISC), edits.discNumber, edits.discTotal),
        )
        edits.lyrics?.let { result = setLyrics(version, result, it.trim()) }
        result = setCover(version, result, edits.cover)
        return result
    }

    /**
     * The "n/total" text for TRCK/TPOS: null leaves the frame alone, "" removes it. Each half can
     * change alone; without a number there is nothing for a total to belong to, so the frame goes.
     */
    internal fun composeNumber(current: String?, number: String?, total: String?): String? {
        if (number == null && total == null) return null
        val currentNumber = TrackNumbers.parse(current)
        val currentTotal = current?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }?.let(TrackNumbers::parse)
        val n = if (number == null) currentNumber else TagNumbers.strict(number)
        val t = if (total == null) currentTotal else TagNumbers.strict(total)
        return when {
            n == null -> ""
            t == null -> "$n"
            else -> "$n/$t"
        }
    }

    /** Rewrites `TXXX:ARTISTS` from a new display credit — only when the file already has one. */
    private fun setCreditedArtists(version: Id3Version, frames: List<Id3RawFrame>, artist: String): List<Id3RawFrame> {
        val first = frames.indexOfFirst { isArtistsFrame(version, it) }
        if (first < 0) return frames
        val names = CreditedArtists.fromDisplay(artist)
        val out = ArrayList<Id3RawFrame>(frames.size)
        for ((index, frame) in frames.withIndex()) {
            when {
                index == first && names.isNotEmpty() -> out += userTextFrame(
                    version,
                    ARTISTS_DESCRIPTION,
                    names.joinToString(if (version == Id3Version.V2_4) "\u0000" else "; "),
                )
                isArtistsFrame(version, frame) -> Unit
                else -> out += frame
            }
        }
        return out
    }

    private fun isArtistsFrame(version: Id3Version, frame: Id3RawFrame): Boolean =
        frame.id == FRAME_USER_TEXT &&
            userTextParts(version, frame)?.first?.equals(ARTISTS_DESCRIPTION, ignoreCase = true) == true

    /** (description, value) of a TXXX frame, or null when its body is unreadable. */
    private fun userTextParts(version: Id3Version, frame: Id3RawFrame): Pair<String, String>? {
        val body = frameTextBody(frame, version) ?: return null
        if (body.size < 2) return null
        val encoding = body[0].toInt() and 0xFF
        val end = terminatorIndex(body, 1, encoding)
        val description = Id3Text.decode(encoding, body, 1, end)?.trim('\u0000')?.trim() ?: return null
        val valueStart = minOf(body.size, end + terminatorLength(encoding))
        val value = Id3Text.decode(encoding, body, valueStart, body.size)?.trim('\u0000') ?: return null
        return description to value
    }

    private fun userTextFrame(version: Id3Version, description: String, value: String): Id3RawFrame {
        val encoding = chooseEncoding(version, description, value)
        val body = byteArrayOf(encoding.toByte()) + encodeText(encoding, description) + terminator(encoding) +
            encodeText(encoding, value)
        return Id3RawFrame(FRAME_USER_TEXT, byteArrayOf(0, 0), body)
    }

    private class LyricsParts(val language: String, val descriptor: String, val text: String)

    private fun lyricsParts(version: Id3Version, frame: Id3RawFrame): LyricsParts? {
        val body = frameTextBody(frame, version) ?: return null
        if (body.size < 4) return null
        val encoding = body[0].toInt() and 0xFF
        val language = CharArray(3) { (body[1 + it].toInt() and 0xFF).toChar() }.concatToString()
        val end = terminatorIndex(body, 4, encoding)
        val descriptor = Id3Text.decode(encoding, body, 4, end)?.trim('\u0000') ?: return null
        val textStart = minOf(body.size, end + terminatorLength(encoding))
        val text = Id3Text.decode(encoding, body, textStart, body.size)?.trim('\u0000') ?: return null
        return LyricsParts(language, descriptor, text)
    }

    /**
     * Replaces the lyrics frame [lyrics] reads (the first non-blank USLT, else the first USLT),
     * keeping its language and descriptor. Other-language frames stay. "" removes that one frame.
     */
    private fun setLyrics(version: Id3Version, frames: List<Id3RawFrame>, lyrics: String): List<Id3RawFrame> {
        val candidates = frames.withIndex().filter { it.value.id == FRAME_LYRICS }
        val target = candidates.firstOrNull { lyricsParts(version, it.value)?.text?.isNotBlank() == true }
            ?: candidates.firstOrNull()
        if (lyrics.isEmpty()) {
            return if (target == null) frames else frames.filterIndexed { index, _ -> index != target.index }
        }
        val parts = target?.let { lyricsParts(version, it.value) }
        val language = parts?.language ?: "XXX"
        val descriptor = parts?.descriptor ?: ""
        val encoding = chooseEncoding(version, descriptor, lyrics)
        val body = byteArrayOf(encoding.toByte()) + ByteArray(3) { language[it].code.toByte() } +
            encodeText(encoding, descriptor) + terminator(encoding) + encodeText(encoding, lyrics)
        val frame = Id3RawFrame(FRAME_LYRICS, byteArrayOf(0, 0), body)
        return if (target == null) frames + frame else frames.toMutableList().also { it[target.index] = frame }
    }

    private class PictureFrame(
        val index: Int,
        val type: Int,
        val mime: String,
        /** Encoding byte, description and its terminator, exactly as stored. */
        val descriptionBytes: ByteArray,
        val data: ByteArray,
    )

    private fun pictures(version: Id3Version, frames: List<Id3RawFrame>): List<PictureFrame> =
        frames.withIndex()
            .filter { it.value.id == FRAME_PICTURE }
            .mapNotNull { (index, frame) -> parsePicture(version, index, frame) }

    private fun parsePicture(version: Id3Version, index: Int, frame: Id3RawFrame): PictureFrame? {
        val body = frameTextBody(frame, version) ?: return null
        if (body.isEmpty()) return null
        val encoding = body[0].toInt() and 0xFF
        var p = 1
        while (p < body.size && body[p] != 0.toByte()) p++
        if (p + 1 >= body.size) return null
        val mime = CharArray(p - 1) { (body[1 + it].toInt() and 0xFF).toChar() }.concatToString()
        p += 1
        val type = body[p].toInt() and 0xFF
        p += 1
        val descriptionEnd = terminatorIndex(body, p, encoding)
        val dataStart = descriptionEnd + terminatorLength(encoding)
        if (dataStart > body.size) return null
        return PictureFrame(
            index = index,
            type = type,
            mime = mime,
            descriptionBytes = byteArrayOf(encoding.toByte()) + body.copyOfRange(p, dataStart),
            data = body.copyOfRange(dataStart, body.size),
        )
    }

    private fun coverTarget(version: Id3Version, frames: List<Id3RawFrame>): PictureFrame? {
        val pictures = pictures(version, frames)
        return CoverTarget.index(pictures.map { it.type })?.let { pictures[it] }
    }

    private fun setCover(version: Id3Version, frames: List<Id3RawFrame>, edit: CoverEdit): List<Id3RawFrame> {
        if (edit == CoverEdit.Keep) return frames
        val target = coverTarget(version, frames)
        return when (edit) {
            CoverEdit.Keep -> frames
            CoverEdit.Remove ->
                if (target == null) frames else frames.filterIndexed { index, _ -> index != target.index }
            is CoverEdit.Replace -> {
                val description = target?.descriptionBytes ?: byteArrayOf(0, 0)
                val body = byteArrayOf(description[0]) +
                    ByteArray(edit.mime.length) { edit.mime[it].code.toByte() } +
                    byteArrayOf(0, FlacPicture.FRONT_COVER.toByte()) +
                    description.copyOfRange(1, description.size) +
                    edit.bytes
                val frame = Id3RawFrame(FRAME_PICTURE, byteArrayOf(0, 0), body)
                if (target == null) frames + frame else frames.toMutableList().also { it[target.index] = frame }
            }
        }
    }

    /** Latin-1 when every character fits; otherwise UTF-8 on 2.4 and UTF-16 with BOM on 2.3. */
    private fun chooseEncoding(version: Id3Version, vararg texts: String): Int = when {
        texts.all(Id3Text::isLatin1) -> 0
        version == Id3Version.V2_4 -> 3
        else -> 1
    }

    private fun encodeText(encoding: Int, text: String): ByteArray = when (encoding) {
        0 -> ByteArray(text.length) { text[it].code.toByte() }
        3 -> text.encodeToByteArray()
        1 -> {
            val out = ByteArray(2 + text.length * 2)
            out[0] = 0xFF.toByte()
            out[1] = 0xFE.toByte()
            for ((i, c) in text.withIndex()) {
                out[2 + i * 2] = (c.code and 0xFF).toByte()
                out[3 + i * 2] = (c.code shr 8).toByte()
            }
            out
        }
        else -> error("encoding $encoding is never chosen for writing")
    }

    private fun terminator(encoding: Int): ByteArray =
        if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)

    private fun terminatorLength(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    /** Index of the string terminator starting the search at [from]; [body].size when there is none. */
    private fun terminatorIndex(body: ByteArray, from: Int, encoding: Int): Int {
        var i = from
        if (encoding == 1 || encoding == 2) {
            while (i + 1 < body.size && !(body[i] == 0.toByte() && body[i + 1] == 0.toByte())) i += 2
            return minOf(i, body.size)
        }
        while (i < body.size && body[i] != 0.toByte()) i++
        return i
    }

    /** Decoded text of the first frame with [id] in [frames], NUL-separated values joined by "; ". */
    private fun textIn(version: Id3Version, frames: List<Id3RawFrame>, id: String): String? {
        val frame = frames.firstOrNull { it.id == id } ?: return null
        val body = frameTextBody(frame, version) ?: return null
        if (body.isEmpty()) return null
        val raw = Id3Text.decode(body[0].toInt() and 0xFF, body, 1, body.size) ?: return null
        return raw.split('\u0000').filter { it.isNotEmpty() }.joinToString("; ").ifEmpty { null }
    }
```

3d. Replace the body of the existing `private fun text(tag: Id3RawTag, id: String): String?` with a delegation, keeping its signature:

```kotlin
    private fun text(tag: Id3RawTag, id: String): String? = textIn(tag.version, tag.frames, id)
```

3e. Change `private fun canPrependTag(data: ByteArray): Boolean` to `internal fun canPrependTag(data: ByteArray): Boolean`.

3f. In the existing `private fun setText(...)`, keep a frame that already says exactly the new value (its encoding included) instead of re-encoding it. Replace its body with:

```kotlin
        if (value == null) return frames
        val doomed = alsoRemove + id
        // A frame that already says exactly this stays as it is — a UTF-16 "Song" is not rewritten
        // as Latin-1 "Song" just because the user saved without changing it.
        val same = frames.firstOrNull { it.id == id }
            ?.takeIf { value.isNotEmpty() && textIn(version, listOf(it), id) == value }
        val out = ArrayList<Id3RawFrame>(frames.size + 1)
        var placed = false
        for (frame in frames) {
            if (frame.id !in doomed) {
                out.add(frame)
                continue
            }
            if (frame.id == id && !placed && value.isNotEmpty()) {
                out.add(same ?: newTextFrame(version, id, value))
                placed = true
            }
            // Every other match is dropped: duplicates of the frame we just
            // wrote, and the other version's stale year frame.
        }
        if (!placed && value.isNotEmpty()) out.add(newTextFrame(version, id, value))
        return out
```

3g. Add `readFields` and `wouldChange` inside `object Id3Tags` (after `read`):

```kotlin
    /** Every field the editor shows, plus what verification needs, from one parse. */
    internal class Id3Fields(
        val version: Id3Version,
        val totalLength: Int,
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val genre: String?,
        val year: String?,
        /** Raw TRCK text, "3/12" style. */
        val track: String?,
        /** Raw TPOS text. */
        val disc: String?,
        val lyrics: String?,
        val cover: CoverInfo?,
        val otherPictures: Int,
        /** The picture that would become the cover if [cover] were removed. */
        val nextCover: CoverInfo?,
        /** CRC-32 of every APIC's image data (its whole body when unreadable), sorted. */
        val pictures: List<Long>,
        val artists: List<String>,
        /** "ID:crc32(body)" of every frame the editor does not own, in file order (pictures excluded). */
        val unmanaged: List<String>,
    )

    internal fun readFields(prefix: ByteArray): Id3Fields? {
        val tag = (Id3Codec.parse(prefix) as? Id3Parse.Parsed)?.tag ?: return null
        val version = tag.version
        val frames = tag.frames
        val cover = coverTarget(version, frames)
        val lyricsTarget = frames.withIndex()
            .filter { it.value.id == FRAME_LYRICS }
            .let { list -> list.firstOrNull { lyricsParts(version, it.value)?.text?.isNotBlank() == true } ?: list.firstOrNull() }
        val managedIds = setOf(
            FRAME_TITLE, FRAME_ARTIST, FRAME_ALBUM, FRAME_ALBUM_ARTIST, FRAME_GENRE,
            FRAME_YEAR_V23, FRAME_YEAR_V24, FRAME_TRACK, FRAME_DISC,
        )
        val unmanaged = frames.withIndex()
            .filterNot { (index, frame) ->
                frame.id in managedIds || frame.id == FRAME_PICTURE || index == lyricsTarget?.index ||
                    isArtistsFrame(version, frame)
            }
            .map { (_, frame) -> "${frame.id}:${Crc32.of(frame.body)}" }
        val year = when (version) {
            Id3Version.V2_4 -> textIn(version, frames, FRAME_YEAR_V24) ?: textIn(version, frames, FRAME_YEAR_V23)
            Id3Version.V2_3 -> textIn(version, frames, FRAME_YEAR_V23) ?: textIn(version, frames, FRAME_YEAR_V24)
        }
        return Id3Fields(
            version = version,
            totalLength = tag.totalLength,
            title = textIn(version, frames, FRAME_TITLE),
            artist = textIn(version, frames, FRAME_ARTIST),
            album = textIn(version, frames, FRAME_ALBUM),
            albumArtist = textIn(version, frames, FRAME_ALBUM_ARTIST),
            genre = textIn(version, frames, FRAME_GENRE),
            year = year,
            track = textIn(version, frames, FRAME_TRACK),
            disc = textIn(version, frames, FRAME_DISC),
            lyrics = lyricsTarget?.let { lyricsParts(version, it.value) }?.text?.trim()?.ifEmpty { null },
            cover = cover?.let { CoverInfo.of(it.data, it.mime) },
            otherPictures = frames.count { it.id == FRAME_PICTURE } - (if (cover != null) 1 else 0),
            nextCover = cover
                ?.let { target -> coverTarget(version, frames.filterIndexed { index, _ -> index != target.index }) }
                ?.let { CoverInfo.of(it.data, it.mime) },
            pictures = frames.withIndex()
                .filter { it.value.id == FRAME_PICTURE }
                .map { (index, frame) -> parsePicture(version, index, frame)?.let { Crc32.of(it.data) } ?: Crc32.of(frame.body) }
                .sorted(),
            artists = frames.filter { isArtistsFrame(version, it) }
                .flatMap { frame -> userTextParts(version, frame)?.second?.let(TagFacts::splitArtists).orEmpty() },
            unmanaged = unmanaged,
        )
    }

    /** False when [edits] would leave every frame exactly as it is (a "same values" save). */
    internal fun wouldChange(prefix: ByteArray, edits: TagEdits): Boolean = when (val parsed = Id3Codec.parse(prefix)) {
        is Id3Parse.Refused -> true
        Id3Parse.Absent -> applyEdits(Id3Version.V2_3, emptyList(), edits).isNotEmpty()
        is Id3Parse.Parsed -> {
            val before = parsed.tag.frames
            val after = applyEdits(parsed.tag.version, before, edits)
            before.size != after.size || before.indices.any { i ->
                before[i].id != after[i].id ||
                    !before[i].flags.contentEquals(after[i].flags) ||
                    !before[i].body.contentEquals(after[i].body)
            }
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS — `Id3FieldsTest` and all pre-existing ID3 tests (`Id3TagsTest`, `Id3LyricsTest`, `TagFactsTest`, `GenreTagsTest`), whose behaviour for the five original fields is unchanged.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Id3Tags.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/Id3FieldsTest.kt
git commit -m "feat(tags): ID3 learns album artist, track and disc numbers, lyrics and the cover"
```

---

### Task 6: The `TagCodec` interface and the MP3 codec

**Files:**
- Create: `.../library/tags/TagCodec.kt` (interface, `EditChecks`, `Digests`)
- Create: `.../library/tags/Id3TagCodec.kt`
- Test: `.../library/tags/Id3TagCodecTest.kt` (commonTest)

**Interfaces:**
- Consumes: everything from Tasks 1–5; `Id3Tags.readFields/wouldChange/canPrependTag/buildUpdate/droppedTrailerLength/refusalOf/tagLength/HEADER_SIZE`, `Id3Codec.looksLikeTag`, `Id3v1.trailerLength`, `Id3v1.MAX_TRAILER_SIZE`.
- Produces:
  - `interface TagCodec { fun recognizes(head: ByteArray): Boolean; fun read(source: RandomAccessSource): TagSnapshot; fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan; fun audioDigest(source: RandomAccessSource): Long?; fun inventory(source: RandomAccessSource): List<String>; companion { const val HEAD_BYTES = 64 } }`.
  - `internal object EditChecks { fun refusal(edits: TagEdits): TagRefusal? }`.
  - `internal object Digests { fun crc32(source: RandomAccessSource, offset: Long, length: Long): Long? }`.
  - `internal object Id3TagCodec : TagCodec`.
  - commonTest `internal object CodecAssertions { fun assertWriteMatchesExpectation(codec: TagCodec, original: ByteArray, edits: TagEdits): ByteArray }` — applies the plan, then asserts read-back equals `expectedAfter`, the audio digest and the inventory are unchanged; returns the new file. Reused by Tasks 7, 9, 11.

- [ ] **Step 1: Write the failing tests**

`CodecAssertions.kt` (commonTest):

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one property every codec must have, checked the same way for all of them. */
internal object CodecAssertions {

    /** Plans [edits], applies the plan, and checks the result against every guarantee. */
    fun assertWriteMatchesExpectation(codec: TagCodec, original: ByteArray, edits: TagEdits): ByteArray {
        val source = ByteArraySource(original)
        val before = codec.read(source)
        assertNull(before.refusal, "fixture must be editable")
        val plan = codec.plan(source, edits)
        val out = assertNotNull(WritePlans.applyInMemory(original, plan), "plan was refused: $plan")
        val after = codec.read(ByteArraySource(out))
        val expected = before.expectedAfter(edits)
        // The only version change allowed: an untagged MP3 that gains its first tag (always ID3v2.3).
        val versionOk = after.version == before.version || (before.version == "none" && after.version == "ID3v2.3")
        assertTrue(versionOk, "tag version changed from ${before.version} to ${after.version}")
        // After a removal, which picture comes next is not predicted (see TagSnapshot.nextCover).
        val nextCover = if (edits.cover == CoverEdit.Remove) null else after.nextCover
        assertEquals(
            expected,
            after.copy(version = expected.version, nextCover = nextCover),
            "read-back must equal the expectation",
        )
        assertEquals(codec.audioDigest(source), codec.audioDigest(ByteArraySource(out)), "audio must not change")
        assertEquals(codec.inventory(source), codec.inventory(ByteArraySource(out)), "unmanaged data must not change")
        return out
    }
}
```

`Id3TagCodecTest.kt` (commonTest):

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.Id3TestTags.artFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.commentFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.latin1Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.mp3Payload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class Id3TagCodecTest {

    private val codec = Id3TagCodec

    private fun tagged(padding: Int = 0, major: Int = 3) = Id3TestTags.build(
        major,
        listOf(
            TestFrame("TIT2", latin1Body("Title")),
            TestFrame("TPE1", latin1Body("Artist")),
            TestFrame("TRCK", latin1Body("3/12")),
            commentFrame("keep"),
            artFrame(size = 200),
        ),
        padding,
    ) + mp3Payload()

    @Test
    fun recognizesTaggedAndBareMpegButNotOtherContainers() {
        assertTrue(codec.recognizes(tagged().copyOf(TagCodec.HEAD_BYTES)))
        assertTrue(codec.recognizes(mp3Payload().copyOf(TagCodec.HEAD_BYTES)))
        assertFalse(codec.recognizes("fLaC".encodeToByteArray() + ByteArray(60)))
        assertFalse(codec.recognizes("OggS".encodeToByteArray() + ByteArray(60)))
    }

    @Test
    fun readReturnsEveryField() {
        val snapshot = codec.read(ByteArraySource(tagged()))
        assertEquals(TagFormat.MP3, snapshot.format)
        assertEquals("ID3v2.3", snapshot.version)
        assertEquals("Title", snapshot.title)
        assertEquals(3, snapshot.trackNumber)
        assertEquals(12, snapshot.trackTotal)
        assertEquals(200, snapshot.cover?.size)
        assertEquals(null, snapshot.refusal)
    }

    @Test
    fun untaggedMpegReadsAsVersionNone() {
        val snapshot = codec.read(ByteArraySource(mp3Payload()))
        assertEquals("none", snapshot.version)
        assertTrue(snapshot.editable)
    }

    @Test
    fun editInsidePaddingIsAnInPlacePatchOfTheTagOnly() {
        val file = tagged(padding = 4096)
        val tagLength = Id3Tags.tagLength(file)!!
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "A longer title than before"))
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size.toLong(), plan.newLength)
        assertTrue(plan.writes.all { it.offset + it.bytes.size <= tagLength })
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "A longer title than before"))
    }

    @Test
    fun editBeyondPaddingIsAStreamingRewriteThatKeepsTheAudio() {
        val edits = TagEdits(lyrics = "x".repeat(5000))
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(tagged()), edits))
        CodecAssertions.assertWriteMatchesExpectation(codec, tagged(), edits)
    }

    @Test
    fun secondEditAfterARewriteGoesInPlace() {
        val first = CodecAssertions.assertWriteMatchesExpectation(codec, tagged(), TagEdits(lyrics = "x".repeat(5000)))
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(first), TagEdits(title = "Again")))
    }

    @Test
    fun settingTheSameValuesWritesNothing() {
        val file = tagged() + Id3TestTags.v1Trailer()
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(title = "Title", trackNumber = "3")))
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits()))
    }

    @Test
    fun aRealEditDropsTheId3v1Trailer() {
        val file = tagged(padding = 512) + Id3TestTags.v1Trailer()
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "New"))
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size - 128L, plan.newLength)
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "New"))
    }

    @Test
    fun everyFieldAtOnceMatchesTheExpectation() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            tagged(major = 4),
            TagEdits(
                title = "Заголовок", artist = "X; Y", album = "Альбом", albumArtist = "Various", genre = "Rock",
                year = "2004-05-06", trackNumber = "1", trackTotal = "", discNumber = "2", discTotal = "2",
                lyrics = "строка", cover = CoverEdit.Replace(TestImages.jpeg(30, 30), ImageProbe.JPEG),
            ),
        )
    }

    @Test
    fun untaggedMpegGetsANewTag() {
        CodecAssertions.assertWriteMatchesExpectation(codec, mp3Payload(), TagEdits(title = "First"))
    }

    @Test
    fun id3InFrontOfFlacIsRefused() {
        val file = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t")))) + "fLaC".encodeToByteArray() + ByteArray(100)
        assertEquals(TagRefusal.ID3_BEFORE_OTHER_CONTAINER, codec.read(ByteArraySource(file)).refusal)
        assertEquals(
            TagRefusal.ID3_BEFORE_OTHER_CONTAINER,
            (codec.plan(ByteArraySource(file), TagEdits(title = "x")) as WritePlan.Refused).reason,
        )
    }

    @Test
    fun invalidNumbersAndImagesAreRefusedBeforeAnyWork() {
        val source = ByteArraySource(tagged())
        assertEquals(TagRefusal.INVALID_NUMBER, (codec.plan(source, TagEdits(trackNumber = "3/12")) as WritePlan.Refused).reason)
        assertEquals(
            TagRefusal.UNSUPPORTED_IMAGE,
            (codec.plan(source, TagEdits(cover = CoverEdit.Replace(ByteArray(10), ImageProbe.JPEG))) as WritePlan.Refused).reason,
        )
    }

    @Test
    fun unsynchronisedTagsAreRefused() {
        val file = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t"))), headerFlags = 0x80) + mp3Payload()
        assertEquals(TagRefusal.ID3_UNSYNCHRONISED, codec.read(ByteArraySource(file)).refusal)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `TagCodec`, `Id3TagCodec` unresolved.

- [ ] **Step 3: Create `TagCodec.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * One container format's tags: read them, and plan the smallest safe write of an edit.
 *
 * A codec never writes. It reads through [RandomAccessSource] and returns a [WritePlan] that a
 * platform executes — which is what lets the same code run, and be tested, everywhere.
 */
public interface TagCodec {
    /** True when [head] — the first [HEAD_BYTES] bytes, or fewer for a tiny file — is this container. */
    public fun recognizes(head: ByteArray): Boolean

    /** What the file says; a snapshot with [TagSnapshot.refusal] set when it cannot be edited. */
    public fun read(source: RandomAccessSource): TagSnapshot

    public fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan

    /** CRC-32 over exactly the bytes no edit may change (the audio); null when unreadable. */
    public fun audioDigest(source: RandomAccessSource): Long?

    /** An identity for everything the editor does not own, in file order; equal before and after any edit. */
    public fun inventory(source: RandomAccessSource): List<String>

    public companion object {
        public const val HEAD_BYTES: Int = 64
    }
}

/** Checks that do not depend on the format, run before any format work. */
internal object EditChecks {
    fun refusal(edits: TagEdits): TagRefusal? {
        val cover = edits.cover
        return when {
            !edits.numbersAreValid -> TagRefusal.INVALID_NUMBER
            cover is CoverEdit.Replace && CoverTarget.replacement(cover) == null -> TagRefusal.UNSUPPORTED_IMAGE
            else -> null
        }
    }
}

internal object Digests {
    /** CRC-32 of [length] bytes at [offset], read in chunks; null when the range is not readable. */
    fun crc32(source: RandomAccessSource, offset: Long, length: Long): Long? {
        val crc = Crc32()
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = minOf(WritePlans.COPY_CHUNK.toLong(), end - position).toInt()
            val chunk = source.read(position, count) ?: return null
            crc.update(chunk)
            position += count
        }
        return crc.value
    }
}
```

- [ ] **Step 4: Create `Id3TagCodec.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.TrackNumbers

/** MP3 (and ADTS AAC): an ID3v2 tag at the head of a raw audio stream. */
internal object Id3TagCodec : TagCodec {

    /** Bytes read past the tag, enough to see which container it fronts. */
    private const val AFTER_TAG = 16

    private class Head(val prefix: ByteArray, val tagLength: Int)

    override fun recognizes(head: ByteArray): Boolean =
        Id3Codec.looksLikeTag(head) || Id3Tags.canPrependTag(head)

    private fun head(source: RandomAccessSource): Head? {
        val header = source.read(0, minOf(source.length, Id3Tags.HEADER_SIZE.toLong()).toInt()) ?: return null
        val tagLength = Id3Tags.tagLength(header) ?: return Head(header, -1)
        val wanted = minOf(source.length, tagLength.toLong() + AFTER_TAG).toInt()
        return Head(source.read(0, wanted) ?: return null, tagLength)
    }

    private fun startsWith(data: ByteArray, at: Int, text: String): Boolean =
        at >= 0 && at + text.length <= data.size && text.indices.all { data[at + it] == text[it].code.toByte() }

    private fun refusal(head: Head): TagRefusal? {
        if (head.tagLength < 0) {
            return Id3Tags.refusalOf(head.prefix)?.let { TagRefusal.of(it) } ?: TagRefusal.TRUNCATED
        }
        val t = head.tagLength
        if (t > 0 && (startsWith(head.prefix, t, "fLaC") || startsWith(head.prefix, t, "OggS") ||
                startsWith(head.prefix, t + 4, "ftyp"))
        ) {
            return TagRefusal.ID3_BEFORE_OTHER_CONTAINER
        }
        Id3Tags.refusalOf(head.prefix)?.let { return TagRefusal.of(it) }
        if (t == 0 && !Id3Tags.canPrependTag(head.prefix)) return TagRefusal.ID3_NOT_TAGGABLE
        return null
    }

    override fun read(source: RandomAccessSource): TagSnapshot {
        val head = head(source) ?: return TagSnapshot(TagFormat.MP3, "none", refusal = TagRefusal.TRUNCATED)
        refusal(head)?.let { return TagSnapshot(TagFormat.MP3, "ID3", refusal = it) }
        if (head.tagLength == 0) return TagSnapshot(TagFormat.MP3, "none")
        val fields = Id3Tags.readFields(head.prefix)
            ?: return TagSnapshot(TagFormat.MP3, "ID3", refusal = TagRefusal.ID3_MALFORMED_FRAMES)
        return TagSnapshot(
            format = TagFormat.MP3,
            version = if (fields.version == Id3Version.V2_4) "ID3v2.4" else "ID3v2.3",
            title = fields.title,
            artist = fields.artist,
            album = fields.album,
            albumArtist = fields.albumArtist,
            genre = fields.genre,
            year = fields.year,
            trackNumber = TrackNumbers.parse(fields.track),
            trackTotal = total(fields.track),
            discNumber = TrackNumbers.parse(fields.disc),
            discTotal = total(fields.disc),
            lyrics = fields.lyrics,
            cover = fields.cover,
            otherPictures = fields.otherPictures,
            nextCover = fields.nextCover,
            pictures = fields.pictures,
            artists = fields.artists,
        )
    }

    private fun total(pair: String?): Int? =
        pair?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }?.let(TrackNumbers::parse)

    override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan {
        val normalized = edits.normalized()
        if (normalized.isEmpty) return WritePlan.NoChange
        EditChecks.refusal(normalized)?.let { return WritePlan.Refused(it) }
        val head = head(source) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
        refusal(head)?.let { return WritePlan.Refused(it) }
        val prefix = if (head.tagLength > 0) head.prefix.copyOf(head.tagLength) else head.prefix
        if (!Id3Tags.wouldChange(prefix, normalized)) return WritePlan.NoChange
        val update = Id3Tags.buildUpdate(prefix, normalized)
            ?: return WritePlan.Refused(Id3Tags.refusalOf(prefix)?.let { TagRefusal.of(it) } ?: TagRefusal.ID3_TAG_TOO_LARGE)

        val length = source.length
        val tailSize = minOf(length, Id3v1.MAX_TRAILER_SIZE.toLong()).toInt()
        val tail = source.read(length - tailSize, tailSize) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
        var trailer = Id3Tags.droppedTrailerLength(tail, normalized).toLong()
        // "TAG" 128 bytes from the end of a file that is almost all tag is a coincidence, not a trailer.
        if (length - trailer < update.replacedLength) trailer = 0
        val audioLength = length - trailer - update.replacedLength
        return if (update.isSameLength) {
            val old = source.read(0, update.replacedLength) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
            ByteDiff.patchOrNoChange(0, old, update.tag, update.tag.size + audioLength, length)
        } else {
            WritePlan.StreamingRewrite(
                listOf(
                    OutputSegment.Bytes(update.tag),
                    OutputSegment.Copy(update.replacedLength.toLong(), audioLength),
                ),
            )
        }
    }

    override fun audioDigest(source: RandomAccessSource): Long? {
        val head = head(source) ?: return null
        val start = maxOf(head.tagLength, 0).toLong()
        val length = source.length
        val tailSize = minOf(length, Id3v1.MAX_TRAILER_SIZE.toLong()).toInt()
        val tail = source.read(length - tailSize, tailSize) ?: return null
        val end = (length - Id3v1.trailerLength(tail)).takeIf { it >= start } ?: length
        return Digests.crc32(source, start, end - start)
    }

    override fun inventory(source: RandomAccessSource): List<String> {
        val head = head(source) ?: return emptyList()
        if (head.tagLength <= 0) return emptyList()
        return Id3Tags.readFields(head.prefix)?.unmanaged.orEmpty()
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagCodec.kt \
        core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Id3TagCodec.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/CodecAssertions.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/Id3TagCodecTest.kt
git commit -m "feat(tags): one codec interface, and MP3 behind it — patched in place whenever the padding allows"
```

---
### Task 7: The FLAC codec

**Files:**
- Create: `.../library/tags/FlacTagCodec.kt`
- Modify: `.../library/tags/VorbisComments.kt` (append `toSnapshot`)
- Test: `.../library/tags/FlacTagCodecTest.kt` (commonTest; defines `FlacFixtures`)

**Interfaces:**
- Consumes: Tasks 1–6 (`TagCodec`, `EditChecks`, `Digests`, `ByteDiff`, `VorbisComments`, `VorbisFields`, `FlacPicture`, `CoverTarget`, `CodecAssertions`, `TestImages`).
- Produces:
  - `internal fun VorbisFieldValues.toSnapshot(format: TagFormat, version: String, cover: CoverInfo?, otherPictures: Int, nextCover: CoverInfo?, pictures: List<Long>): TagSnapshot` (appended to `VorbisComments.kt`; Task 9 reuses it).
  - `internal object FlacTagCodec : TagCodec`.

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Builds FLAC files block by block. Shares no code with the codec's serializer. */
internal object FlacFixtures {
    const val STREAMINFO = 0
    const val PADDING = 1
    const val APPLICATION = 2
    const val SEEKTABLE = 3
    const val VORBIS_COMMENT = 4
    const val PICTURE = 6

    val streamInfo = ByteArray(34) { (it + 1).toByte() }
    val audio = byteArrayOf(0xFF.toByte(), 0xF8.toByte()) + ByteArray(3000) { (it * 7 % 256).toByte() }

    fun block(type: Int, body: ByteArray, last: Boolean): ByteArray =
        byteArrayOf(
            ((if (last) 0x80 else 0) or type).toByte(),
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte(),
        ) + body

    fun file(vararg blocks: Pair<Int, ByteArray>, first: Pair<Int, ByteArray> = STREAMINFO to streamInfo): ByteArray {
        val all = listOf(first) + blocks
        var out = "fLaC".encodeToByteArray()
        all.forEachIndexed { i, (type, body) -> out += block(type, body, last = i == all.lastIndex) }
        return out + audio
    }

    fun comments(vararg pairs: Pair<String, String>): ByteArray =
        VorbisComments("reference libFLAC".encodeToByteArray(), pairs.map { VorbisEntry.of(it.first, it.second) }).encode()

    fun picture(type: Int, data: ByteArray, mime: String = "image/png"): ByteArray =
        FlacPicture(type, mime, "", 1, 1, 24, 0, data).encode()

    fun audioStart(file: ByteArray): Int {
        var position = 4
        while (true) {
            val last = file[position].toInt() and 0x80 != 0
            val length = ((file[position + 1].toInt() and 0xFF) shl 16) or
                ((file[position + 2].toInt() and 0xFF) shl 8) or (file[position + 3].toInt() and 0xFF)
            position += 4 + length
            if (last) return position
        }
    }

    fun blockTypes(file: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var position = 4
        while (true) {
            val header = file[position].toInt() and 0xFF
            types += header and 0x7F
            val length = ((file[position + 1].toInt() and 0xFF) shl 16) or
                ((file[position + 2].toInt() and 0xFF) shl 8) or (file[position + 3].toInt() and 0xFF)
            position += 4 + length
            if (header and 0x80 != 0) return types
        }
    }
}

internal class FlacTagCodecTest {

    private val codec = FlacTagCodec

    private fun tagged(padding: Int = 1000) = FlacFixtures.file(
        FlacFixtures.SEEKTABLE to ByteArray(18) { 5 },
        FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments(
            "TITLE" to "Title",
            "ARTIST" to "Artist",
            "REPLAYGAIN_TRACK_GAIN" to "-7.1 dB",
            "TRACKNUMBER" to "3",
            "TRACKTOTAL" to "12",
        ),
        FlacFixtures.PICTURE to FlacFixtures.picture(4, byteArrayOf(9, 9)),
        FlacFixtures.PICTURE to FlacFixtures.picture(3, byteArrayOf(1, 2, 3)),
        FlacFixtures.APPLICATION to "riff".encodeToByteArray() + ByteArray(20) { 1 },
        FlacFixtures.PADDING to ByteArray(padding),
    )

    @Test
    fun recognizesOnlyFlac() {
        assertTrue(codec.recognizes(tagged().copyOf(TagCodec.HEAD_BYTES)))
        assertTrue(!codec.recognizes("ID3".encodeToByteArray() + ByteArray(61)))
    }

    @Test
    fun readReturnsFieldsCoverAndOtherPictures() {
        val snapshot = codec.read(ByteArraySource(tagged()))
        assertEquals(TagFormat.FLAC, snapshot.format)
        assertEquals("Title", snapshot.title)
        assertEquals(3, snapshot.trackNumber)
        assertEquals(12, snapshot.trackTotal)
        assertEquals(CoverInfo.of(byteArrayOf(1, 2, 3), "image/png"), snapshot.cover)
        assertEquals(1, snapshot.otherPictures)
    }

    @Test
    fun editWithinPaddingIsInPlaceAndTouchesOnlyMetadata() {
        val file = tagged()
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "A somewhat longer title"))
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size.toLong(), plan.newLength)
        val audioStart = FlacFixtures.audioStart(file)
        assertTrue(plan.writes.all { it.offset + it.bytes.size <= audioStart })
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "A somewhat longer title"))
    }

    @Test
    fun growthWithoutPaddingRewritesWithSpareSpaceAndTheNextEditGoesInPlace() {
        val file = tagged(padding = 0) // a padding block with an empty body: only its header is spare
        val edits = TagEdits(lyrics = "x".repeat(2000))
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), edits))
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
        assertTrue(FlacFixtures.audioStart(out) - FlacFixtures.audioStart(file) >= TagSpace.SPARE_BYTES)
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(out), TagEdits(title = "Again")))
        assertContentEquals(FlacFixtures.audio, out.copyOfRange(FlacFixtures.audioStart(out), out.size))
    }

    @Test
    fun aRemainderTooSmallForAPaddingHeaderForcesARewrite() {
        // Padding block: 4-byte header + 10 bytes = 14 spare. Growing the comment by 12 leaves 2.
        val file = tagged(padding = 10)
        val edits = TagEdits(title = "Title" + "123456789012")
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), edits))
        CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
    }

    @Test
    fun aRemainderOfExactlyZeroFitsWithoutPadding() {
        // Growing the comment by exactly the padding block's 14 bytes consumes it entirely.
        val file = tagged(padding = 10)
        val edits = TagEdits(title = "Title" + "12345678901234")
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), edits))
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
        assertTrue(FlacFixtures.PADDING !in FlacFixtures.blockTypes(out))
    }

    @Test
    fun settingTheSameValuesWritesNothing() {
        val file = FlacFixtures.file(
            FlacFixtures.PADDING to ByteArray(100),
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "Title"),
        )
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(title = "Title")))
    }

    @Test
    fun coverReplaceKeepsOtherPicturesInOrder() {
        val jpeg = TestImages.jpeg(40, 40)
        val out = CodecAssertions.assertWriteMatchesExpectation(
            codec,
            tagged(padding = 5000),
            TagEdits(cover = CoverEdit.Replace(jpeg, ImageProbe.JPEG)),
        )
        assertEquals(1, codec.read(ByteArraySource(out)).otherPictures)
    }

    @Test
    fun typeZeroPictureIsTheCoverWhenNoFrontCoverExists() {
        val file = FlacFixtures.file(
            FlacFixtures.PICTURE to FlacFixtures.picture(4, byteArrayOf(1)),
            FlacFixtures.PICTURE to FlacFixtures.picture(0, byteArrayOf(2)),
            FlacFixtures.PADDING to ByteArray(2000),
        )
        assertEquals(CoverInfo.of(byteArrayOf(2), "image/png"), codec.read(ByteArraySource(file)).cover)
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.png(3, 3), ImageProbe.PNG)),
        )
    }

    @Test
    fun coverIsAddedAfterTheCommentsAndRemovedAgain() {
        val file = FlacFixtures.file(
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "t"),
            FlacFixtures.PADDING to ByteArray(3000),
        )
        val added = CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.png(5, 5), ImageProbe.PNG)),
        )
        assertEquals(
            listOf(FlacFixtures.STREAMINFO, FlacFixtures.VORBIS_COMMENT, FlacFixtures.PICTURE, FlacFixtures.PADDING),
            FlacFixtures.blockTypes(added),
        )
        CodecAssertions.assertWriteMatchesExpectation(codec, added, TagEdits(cover = CoverEdit.Remove))
    }

    @Test
    fun aFileWithoutCommentsGetsABlock() {
        val file = FlacFixtures.file(FlacFixtures.PADDING to ByteArray(500))
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "Fresh", trackNumber = "1"))
    }

    @Test
    fun everyFieldAtOnceMatchesTheExpectation() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            tagged(),
            TagEdits(
                title = "Заголовок", artist = "X", album = "Альбом", albumArtist = "Various", genre = "Rock",
                year = "2004", trackNumber = "", trackTotal = "10", discNumber = "1", discTotal = "2",
                lyrics = "строка", cover = CoverEdit.Remove,
            ),
        )
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = "Ночь, улица, фонарь, аптека. 夜の街を歩く。\n".repeat(400).trim()
        CodecAssertions.assertWriteMatchesExpectation(codec, tagged(), TagEdits(lyrics = lyrics))
    }

    @Test
    fun theVendorStringIsPreserved() {
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, tagged(), TagEdits(title = "x"))
        val start = 4 + 4 + 34 + 4 + 18 // magic, STREAMINFO block, SEEKTABLE block
        val header = out[start].toInt() and 0x7F
        assertEquals(FlacFixtures.VORBIS_COMMENT, header)
        val body = out.copyOfRange(start + 4, out.size)
        val (comments, _) = kotlin.test.assertNotNull(VorbisComments.decode(body, 0))
        assertContentEquals("reference libFLAC".encodeToByteArray(), comments.vendor)
    }

    @Test
    fun streamInfoNotFirstIsRefused() {
        val file = FlacFixtures.file(first = FlacFixtures.PADDING to ByteArray(10))
        assertEquals(TagRefusal.FLAC_STREAMINFO_NOT_FIRST, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun truncatedMetadataIsRefused() {
        val file = tagged()
        val cut = file.copyOf(FlacFixtures.audioStart(file) - 5)
        assertEquals(TagRefusal.TRUNCATED, codec.read(ByteArraySource(cut)).refusal)
        assertIs<WritePlan.Refused>(codec.plan(ByteArraySource(cut), TagEdits(title = "x")))
    }

    @Test
    fun aSecondCommentBlockIsRefused() {
        val file = FlacFixtures.file(
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "a"),
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "b"),
        )
        assertEquals(TagRefusal.FLAC_MALFORMED_METADATA, codec.read(ByteArraySource(file)).refusal)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `FlacTagCodec` unresolved.

- [ ] **Step 3: Append `toSnapshot` to `VorbisComments.kt`**

```kotlin
internal fun VorbisFieldValues.toSnapshot(
    format: TagFormat,
    version: String,
    cover: CoverInfo?,
    otherPictures: Int,
    nextCover: CoverInfo?,
    pictures: List<Long>,
): TagSnapshot = TagSnapshot(
    format = format,
    version = version,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    genre = genre,
    year = year,
    trackNumber = trackNumber,
    trackTotal = trackTotal,
    discNumber = discNumber,
    discTotal = discTotal,
    lyrics = lyrics,
    cover = cover,
    otherPictures = otherPictures,
    nextCover = nextCover,
    pictures = pictures,
    artists = artists,
)
```

- [ ] **Step 4: Create `FlacTagCodec.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * FLAC: `fLaC`, STREAMINFO, metadata blocks, audio frames.
 *
 * The whole metadata region is rebuilt to its current size whenever it fits — comments and the
 * cover rewritten, every other block byte for byte, the rest handed to PADDING — so the audio never
 * moves. Only a region that cannot hold the edit is laid out anew, with [TagSpace.SPARE_BYTES] spare.
 */
internal object FlacTagCodec : TagCodec {
    private val MAGIC = "fLaC".encodeToByteArray()
    private const val STREAMINFO = 0
    private const val PADDING = 1
    private const val VORBIS_COMMENT = 4
    private const val PICTURE = 6
    private const val INVALID = 127
    private const val STREAMINFO_LENGTH = 34
    private const val MAX_BLOCK = 0xFFFFFF
    private const val MAX_BLOCKS = 10_000
    private val NEW_VENDOR = "LatentJam".encodeToByteArray()

    private class Block(val type: Int, val body: ByteArray)

    private class Layout(val blocks: List<Block>, val audioStart: Long)

    private sealed interface Parsed {
        class Ok(val layout: Layout) : Parsed
        class Bad(val reason: TagRefusal) : Parsed
    }

    /** The decoded comments and cover; [targetBlock] indexes [Layout.blocks]. */
    private class Parts(
        val comments: VorbisComments?,
        val target: FlacPicture?,
        val targetBlock: Int?,
        /** The picture that would become the cover if [target] were removed. */
        val next: FlacPicture?,
    )

    override fun recognizes(head: ByteArray): Boolean =
        head.size >= 4 && MAGIC.indices.all { head[it] == MAGIC[it] }

    private fun parse(source: RandomAccessSource): Parsed {
        val magic = source.read(0, 4) ?: return Parsed.Bad(TagRefusal.TRUNCATED)
        if (!recognizes(magic)) return Parsed.Bad(TagRefusal.UNSUPPORTED_FORMAT)
        val blocks = ArrayList<Block>()
        var position = 4L
        while (true) {
            val header = source.read(position, 4) ?: return Parsed.Bad(TagRefusal.TRUNCATED)
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (type == INVALID) return Parsed.Bad(TagRefusal.FLAC_MALFORMED_METADATA)
            if (blocks.isEmpty() && (type != STREAMINFO || length != STREAMINFO_LENGTH)) {
                return Parsed.Bad(TagRefusal.FLAC_STREAMINFO_NOT_FIRST)
            }
            if (blocks.isNotEmpty() && type == STREAMINFO) return Parsed.Bad(TagRefusal.FLAC_MALFORMED_METADATA)
            val body = source.read(position + 4, length) ?: return Parsed.Bad(TagRefusal.TRUNCATED)
            blocks += Block(type, body)
            position += 4 + length
            if (last) break
            if (blocks.size > MAX_BLOCKS) return Parsed.Bad(TagRefusal.FLAC_MALFORMED_METADATA)
        }
        if (blocks.count { it.type == VORBIS_COMMENT } > 1) return Parsed.Bad(TagRefusal.FLAC_MALFORMED_METADATA)
        return Parsed.Ok(Layout(blocks, position))
    }

    /** Null when the comment block does not decode to exactly its own length. */
    private fun parts(layout: Layout): Parts? {
        val commentBlock = layout.blocks.firstOrNull { it.type == VORBIS_COMMENT }
        val comments = if (commentBlock == null) {
            null
        } else {
            val (decoded, end) = VorbisComments.decode(commentBlock.body, 0) ?: return null
            if (end != commentBlock.body.size) return null
            decoded
        }
        val pictures = layout.blocks.withIndex()
            .filter { it.value.type == PICTURE }
            .mapNotNull { (index, block) -> FlacPicture.decode(block.body)?.let { index to it } }
        val chosen = CoverTarget.index(pictures.map { it.second.type })?.let { pictures[it] }
        val rest = pictures.filter { it.first != chosen?.first }
        val next = if (chosen == null) null else CoverTarget.index(rest.map { it.second.type })?.let { rest[it].second }
        return Parts(comments, chosen?.second, chosen?.first, next)
    }

    override fun read(source: RandomAccessSource): TagSnapshot {
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return TagSnapshot(TagFormat.FLAC, "FLAC", refusal = parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val parts = parts(layout)
            ?: return TagSnapshot(TagFormat.FLAC, "FLAC", refusal = TagRefusal.FLAC_MALFORMED_METADATA)
        val pictureCount = layout.blocks.count { it.type == PICTURE }
        return VorbisFields.read(parts.comments?.entries.orEmpty()).toSnapshot(
            format = TagFormat.FLAC,
            version = "FLAC",
            cover = parts.target?.let { CoverInfo.of(it.data, it.mime) },
            otherPictures = pictureCount - (if (parts.target != null) 1 else 0),
            nextCover = parts.next?.let { CoverInfo.of(it.data, it.mime) },
            pictures = layout.blocks.filter { it.type == PICTURE }
                .map { block -> FlacPicture.decode(block.body)?.let { Crc32.of(it.data) } ?: Crc32.of(block.body) }
                .sorted(),
        )
    }

    override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan {
        val normalized = edits.normalized()
        if (normalized.isEmpty) return WritePlan.NoChange
        EditChecks.refusal(normalized)?.let { return WritePlan.Refused(it) }
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return WritePlan.Refused(parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val parts = parts(layout) ?: return WritePlan.Refused(TagRefusal.FLAC_MALFORMED_METADATA)
        val blocks = rebuild(layout, parts, normalized)
        if (blocks.any { it.body.size > MAX_BLOCK }) return WritePlan.Refused(TagRefusal.FLAC_BLOCK_TOO_LARGE)

        val unchanged = layout.blocks.filter { it.type != PADDING }
        if (unchanged.size == blocks.size && unchanged.indices.all { i ->
                unchanged[i].type == blocks[i].type && unchanged[i].body.contentEquals(blocks[i].body)
            }
        ) {
            return WritePlan.NoChange
        }

        val region = layout.audioStart - 4
        val remainder = region - blocks.sumOf { 4L + it.body.size }
        if (remainder == 0L || remainder >= 4) {
            val oldRegion = source.read(4, region.toInt()) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
            val newRegion = serialize(if (remainder == 0L) blocks else blocks + paddingBlocks(remainder))
            return ByteDiff.patchOrNoChange(4, oldRegion, newRegion, source.length, source.length)
        }
        val newRegion = serialize(blocks + paddingBlocks(4L + TagSpace.SPARE_BYTES))
        return WritePlan.StreamingRewrite(
            listOf(
                OutputSegment.Bytes(MAGIC + newRegion),
                OutputSegment.Copy(layout.audioStart, source.length - layout.audioStart),
            ),
        )
    }

    /** Every non-padding block in order, with comments and the cover replaced as [edits] say. */
    private fun rebuild(layout: Layout, parts: Parts, edits: TagEdits): List<Block> {
        val entries = VorbisFields.apply(parts.comments?.entries.orEmpty(), edits)
        val commentBody = if (parts.comments != null || entries.isNotEmpty()) {
            VorbisComments(parts.comments?.vendor ?: NEW_VENDOR, entries).encode()
        } else {
            null
        }
        val cover = edits.cover
        val newPicture = (cover as? CoverEdit.Replace)?.let { replace ->
            FlacPicture.frontCover(replace.bytes, replace.mime)?.let { picture ->
                FlacPicture(
                    FlacPicture.FRONT_COVER, picture.mime, parts.target?.description ?: "",
                    picture.width, picture.height, picture.depth, 0, picture.data,
                )
            }
        }
        val out = ArrayList<Block>(layout.blocks.size + 2)
        var commentPlaced = false
        var picturePlaced = false
        for ((index, block) in layout.blocks.withIndex()) {
            when {
                block.type == PADDING -> Unit
                block.type == VORBIS_COMMENT -> {
                    commentBody?.let { out += Block(VORBIS_COMMENT, it) }
                    commentPlaced = true
                }
                index == parts.targetBlock && cover != CoverEdit.Keep -> {
                    newPicture?.let { out += Block(PICTURE, it.encode()) }
                    picturePlaced = true
                }
                else -> out += block
            }
        }
        if (!commentPlaced && commentBody != null) out.add(1, Block(VORBIS_COMMENT, commentBody))
        if (!picturePlaced && newPicture != null) {
            val afterComments = out.indexOfFirst { it.type == VORBIS_COMMENT }.let { if (it < 0) 1 else it + 1 }
            out.add(afterComments, Block(PICTURE, newPicture.encode()))
        }
        return out
    }

    /** PADDING blocks occupying exactly [bytes] (headers included); [bytes] is 0 or at least 4. */
    private fun paddingBlocks(bytes: Long): List<Block> {
        val out = ArrayList<Block>()
        var left = bytes
        while (left > 0) {
            val body = if (left - 4 <= MAX_BLOCK) left - 4 else minOf(MAX_BLOCK.toLong(), left - 8)
            out += Block(PADDING, ByteArray(body.toInt()))
            left -= 4 + body
        }
        return out
    }

    private fun serialize(blocks: List<Block>): ByteArray {
        val out = ByteArray(blocks.sumOf { 4 + it.body.size })
        var p = 0
        for ((i, block) in blocks.withIndex()) {
            val last = if (i == blocks.lastIndex) 0x80 else 0
            out[p] = (last or block.type).toByte()
            out[p + 1] = (block.body.size ushr 16).toByte()
            out[p + 2] = (block.body.size ushr 8).toByte()
            out[p + 3] = block.body.size.toByte()
            block.body.copyInto(out, p + 4)
            p += 4 + block.body.size
        }
        return out
    }

    override fun audioDigest(source: RandomAccessSource): Long? {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return null
        return Digests.crc32(source, layout.audioStart, source.length - layout.audioStart)
    }

    override fun inventory(source: RandomAccessSource): List<String> {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return emptyList()
        val parts = parts(layout) ?: return emptyList()
        val out = ArrayList<String>()
        // The vendor string is checked by its own test: a file without comments gains one on its first edit.
        parts.comments?.entries?.filter { it.key !in VorbisFields.MANAGED }?.forEach { out += "comment:${Crc32.of(it.raw)}" }
        // Pictures are pinned by TagSnapshot.pictures, which also knows which one an edit changes.
        layout.blocks
            .filter { it.type != PADDING && it.type != VORBIS_COMMENT && it.type != PICTURE }
            .forEach { out += "block${it.type}:${Crc32.of(it.body)}" }
        return out
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/FlacTagCodec.kt \
        core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/VorbisComments.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/FlacTagCodecTest.kt
git commit -m "feat(tags): FLAC tags edited inside the metadata region, the audio never moves while padding lasts"
```

---
### Task 8: Ogg pages — parse, serialize, reassemble, lay out, renumber

**Files:**
- Create: `.../library/tags/OggPages.kt`
- Test: `.../library/tags/OggPagesTest.kt` (commonTest)

**Interfaces:**
- Consumes: `OggCrc`, `RandomAccessSource`, `ByteSink`, `ByteArraySink`, `StreamTransform`, `StreamRefusedException`, `TagRefusal` (Tasks 1–2).
- Produces:
  - `internal class OggPage(offset: Long, headerType: Int, granule: Long, serial: Int, sequence: Int, lacing: IntArray, payload: ByteArray, crcValid: Boolean)` with `size`, `isBeginning`, `continues`.
  - `internal object OggPages { const val HEADER_SIZE = 27; const val MAX_SEGMENTS = 255; fun readAt(source, offset: Long): OggPage?; fun serialize(headerType: Int, granule: Long, serial: Int, sequence: Int, lacing: IntArray, payload: ByteArray): ByteArray; fun lacingOf(length: Int): IntArray; fun packets(pages: List<OggPage>): Pair<List<ByteArray>, Boolean>?; class PageContent(lacing: IntArray, payload: ByteArray, continues: Boolean, completesPacket: Boolean); fun layout(packets: List<ByteArray>, pageCount: Int): List<PageContent>?; fun minimumPages(packets: List<ByteArray>): Int; fun le32(b, at): Int; fun putLe32(b, at, v); fun le64(b, at): Long; fun putLe64(b, at, v) }`.
  - `internal class OggRenumberer(serial: Int, sequenceDelta: Int) : StreamTransform` — shifts every page's sequence number, recomputes its CRC; throws `StreamRefusedException` with `OGG_MULTIPLE_STREAMS` (foreign serial), `OGG_BAD_PAGE_CRC` (a source page already corrupt) or `OGG_MALFORMED_PAGES` (not a page / input ends mid-page).

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class OggPagesTest {

    private fun page(sequence: Int, payload: ByteArray, serial: Int = 9, headerType: Int = 0) =
        OggPages.serialize(headerType, 100L * sequence, serial, sequence, OggPages.lacingOf(payload.size), payload)

    @Test
    fun serializedPagesReadBackWithAValidChecksum() {
        val bytes = OggPages.serialize(0x02, 0, 7, 0, OggPages.lacingOf(10), ByteArray(10) { it.toByte() })
        val read = assertNotNull(OggPages.readAt(ByteArraySource(bytes), 0))
        assertTrue(read.crcValid)
        assertEquals(7, read.serial)
        assertEquals(0, read.sequence)
        assertTrue(read.isBeginning)
        assertFalse(read.continues)
        assertEquals(bytes.size, read.size)
    }

    @Test
    fun aFlippedPayloadByteBreaksTheChecksum() {
        val bytes = page(1, ByteArray(40) { 3 })
        bytes[35] = (bytes[35].toInt() xor 1).toByte()
        assertFalse(assertNotNull(OggPages.readAt(ByteArraySource(bytes), 0)).crcValid)
    }

    @Test
    fun readAtRejectsWhatIsNotAPage() {
        assertNull(OggPages.readAt(ByteArraySource(ByteArray(100)), 0))
        assertNull(OggPages.readAt(ByteArraySource(page(1, ByteArray(40)).copyOf(50)), 0))
    }

    @Test
    fun lacingSplitsPacketsIntoFullSegmentsAndAClosingOne() {
        assertContentEquals(intArrayOf(0), OggPages.lacingOf(0))
        assertContentEquals(intArrayOf(255, 0), OggPages.lacingOf(255))
        assertContentEquals(intArrayOf(255, 45), OggPages.lacingOf(300))
    }

    @Test
    fun packetsReassembleAcrossPages() {
        val big = ByteArray(600) { it.toByte() }
        val small = ByteArray(5) { 7 }
        val laid = assertNotNull(OggPages.layout(listOf(big, small), 2))
        val bytes = laid.mapIndexed { i, content ->
            OggPages.serialize(if (content.continues) 1 else 0, 0, 9, i, content.lacing, content.payload)
        }.reduce { a, b -> a + b }
        val source = ByteArraySource(bytes)
        val first = assertNotNull(OggPages.readAt(source, 0))
        val second = assertNotNull(OggPages.readAt(source, first.size.toLong()))
        assertTrue(second.continues)
        val (packets, closed) = assertNotNull(OggPages.packets(listOf(first, second)))
        assertTrue(closed)
        assertContentEquals(big, packets[0])
        assertContentEquals(small, packets[1])
    }

    @Test
    fun packetsRefuseAContinuationWithNothingToContinue() {
        val bytes = OggPages.serialize(1, 0, 9, 0, OggPages.lacingOf(3), ByteArray(3))
        assertNull(OggPages.packets(listOf(assertNotNull(OggPages.readAt(ByteArraySource(bytes), 0)))))
    }

    @Test
    fun layoutUsesExactlyThePageCountOrRefuses() {
        assertNull(OggPages.layout(listOf(ByteArray(10)), 2))
        val three = assertNotNull(OggPages.layout(listOf(ByteArray(600)), 3))
        assertEquals(listOf(1, 1, 1), three.map { it.lacing.size })
        assertEquals(listOf(false, true, true), three.map { it.continues })
        assertEquals(listOf(false, false, true), three.map { it.completesPacket })
        assertNull(OggPages.layout(listOf(ByteArray(70_000)), 1))
        assertEquals(2, OggPages.minimumPages(listOf(ByteArray(70_000))))
    }

    @Test
    fun renumbererShiftsSequencesAndRecomputesChecksumsAcrossChunks() {
        val input = page(5, ByteArray(300) { 1 }) + page(6, ByteArray(10) { 2 }) + page(7, ByteArray(0))
        val pass = OggRenumberer(serial = 9, sequenceDelta = 2).start()
        val sink = ByteArraySink()
        input.toList().chunked(7).forEach { pass.process(it.toByteArray(), sink) }
        pass.finish(sink)
        val out = sink.toByteArray()
        assertEquals(input.size, out.size)
        val source = ByteArraySource(out)
        var offset = 0L
        for (expected in listOf(7, 8, 9)) {
            val read = assertNotNull(OggPages.readAt(source, offset))
            assertTrue(read.crcValid)
            assertEquals(expected, read.sequence)
            offset += read.size
        }
    }

    @Test
    fun renumbererRefusesAForeignSerial() {
        val input = page(5, ByteArray(10)) + page(6, ByteArray(10), serial = 10)
        val pass = OggRenumberer(serial = 9, sequenceDelta = 1).start()
        val error = assertFailsWith<StreamRefusedException> { pass.process(input, ByteArraySink()) }
        assertEquals(TagRefusal.OGG_MULTIPLE_STREAMS, error.reason)
    }

    @Test
    fun renumbererRefusesACorruptSourcePage() {
        val input = page(5, ByteArray(10))
        input[30] = (input[30].toInt() xor 1).toByte()
        val error = assertFailsWith<StreamRefusedException> { OggRenumberer(9, 1).start().process(input, ByteArraySink()) }
        assertEquals(TagRefusal.OGG_BAD_PAGE_CRC, error.reason)
    }

    @Test
    fun renumbererRefusesInputThatEndsMidPage() {
        val input = page(5, ByteArray(10)).copyOf(20)
        val pass = OggRenumberer(9, 1).start()
        pass.process(input, ByteArraySink())
        val error = assertFailsWith<StreamRefusedException> { pass.finish(ByteArraySink()) }
        assertEquals(TagRefusal.OGG_MALFORMED_PAGES, error.reason)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `OggPages`, `OggRenumberer` unresolved.

- [ ] **Step 3: Create `OggPages.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** One Ogg page as found in a file. [crcValid] says whether its stored checksum matches its bytes. */
internal class OggPage(
    val offset: Long,
    val headerType: Int,
    val granule: Long,
    val serial: Int,
    val sequence: Int,
    val lacing: IntArray,
    val payload: ByteArray,
    val crcValid: Boolean,
) {
    val size: Int get() = OggPages.HEADER_SIZE + lacing.size + payload.size
    val isBeginning: Boolean get() = headerType and 0x02 != 0
    val continues: Boolean get() = headerType and 0x01 != 0
}

/** The Ogg page layer (RFC 3533): what the tag codec needs and nothing else. */
internal object OggPages {
    const val HEADER_SIZE = 27
    const val MAX_SEGMENTS = 255
    private val MAGIC = "OggS".encodeToByteArray()

    /** The page at [offset], or null when the bytes there are not a whole version-0 Ogg page. */
    fun readAt(source: RandomAccessSource, offset: Long): OggPage? {
        val header = source.read(offset, HEADER_SIZE) ?: return null
        if (!MAGIC.indices.all { header[it] == MAGIC[it] } || header[4] != 0.toByte()) return null
        val segments = header[26].toInt() and 0xFF
        val lacingBytes = source.read(offset + HEADER_SIZE, segments) ?: return null
        val lacing = IntArray(segments) { lacingBytes[it].toInt() and 0xFF }
        val payload = source.read(offset + HEADER_SIZE + segments, lacing.sum()) ?: return null
        val whole = header + lacingBytes + payload
        val stored = le32(whole, 22)
        putLe32(whole, 22, 0)
        return OggPage(
            offset = offset,
            headerType = header[5].toInt() and 0xFF,
            granule = le64(header, 6),
            serial = le32(header, 14),
            sequence = le32(header, 18),
            lacing = lacing,
            payload = payload,
            crcValid = OggCrc.compute(whole) == stored,
        )
    }

    fun serialize(headerType: Int, granule: Long, serial: Int, sequence: Int, lacing: IntArray, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + lacing.size + payload.size)
        MAGIC.copyInto(out)
        out[4] = 0
        out[5] = headerType.toByte()
        putLe64(out, 6, granule)
        putLe32(out, 14, serial)
        putLe32(out, 18, sequence)
        out[26] = lacing.size.toByte()
        for (i in lacing.indices) out[HEADER_SIZE + i] = lacing[i].toByte()
        payload.copyInto(out, HEADER_SIZE + lacing.size)
        putLe32(out, 22, OggCrc.compute(out))
        return out
    }

    /** Lacing for one packet: a 255 per full segment, then the remainder (0 for an exact multiple). */
    fun lacingOf(length: Int): IntArray {
        val full = length / 255
        return IntArray(full + 1) { if (it < full) 255 else length % 255 }
    }

    /**
     * The complete packets in [pages] (consecutive pages of one stream, the first starting on a
     * packet boundary) and whether the last page closes its last packet. Null when a page's
     * continuation flag disagrees with the packet state.
     */
    fun packets(pages: List<OggPage>): Pair<List<ByteArray>, Boolean>? {
        val packets = ArrayList<ByteArray>()
        val current = ByteArraySink()
        var open = false
        for (page in pages) {
            if (page.continues != open) return null
            var position = 0
            for (value in page.lacing) {
                current.write(page.payload, position, value)
                position += value
                open = true
                if (value < 255) {
                    packets += current.toByteArray()
                    current.reset()
                    open = false
                }
            }
        }
        return packets to !open
    }

    /** One laid-out page. */
    class PageContent(
        val lacing: IntArray,
        val payload: ByteArray,
        /** The page begins in the middle of a packet. */
        val continues: Boolean,
        /** At least one packet ends on this page. */
        val completesPacket: Boolean,
    )

    /**
     * [packets] laced into exactly [pageCount] pages of 1–255 segments each, earlier pages filled
     * first; null when that many pages cannot hold them.
     */
    fun layout(packets: List<ByteArray>, pageCount: Int): List<PageContent>? {
        // Each segment: which packet, where in it, how long, and whether it closes the packet.
        val packetOf = ArrayList<Int>()
        val offsetOf = ArrayList<Int>()
        val lengthOf = ArrayList<Int>()
        val closes = ArrayList<Boolean>()
        for ((index, packet) in packets.withIndex()) {
            val lacing = lacingOf(packet.size)
            var offset = 0
            for ((i, value) in lacing.withIndex()) {
                packetOf += index
                offsetOf += offset
                lengthOf += value
                closes += i == lacing.lastIndex
                offset += value
            }
        }
        val total = lengthOf.size
        if (pageCount <= 0 || total < pageCount || total > pageCount.toLong() * MAX_SEGMENTS) return null
        val pages = ArrayList<PageContent>(pageCount)
        var index = 0
        for (page in 0 until pageCount) {
            val pagesAfter = pageCount - page - 1
            val take = minOf(MAX_SEGMENTS, total - index - pagesAfter)
            val payload = ByteArray((index until index + take).sumOf { lengthOf[it] })
            var p = 0
            for (s in index until index + take) {
                packets[packetOf[s]].copyInto(payload, p, offsetOf[s], offsetOf[s] + lengthOf[s])
                p += lengthOf[s]
            }
            pages += PageContent(
                lacing = IntArray(take) { lengthOf[index + it] },
                payload = payload,
                continues = index > 0 && !closes[index - 1],
                completesPacket = (index until index + take).any { closes[it] },
            )
            index += take
        }
        return pages
    }

    fun minimumPages(packets: List<ByteArray>): Int {
        val segments = packets.sumOf { it.size / 255 + 1 }
        return (segments + MAX_SEGMENTS - 1) / MAX_SEGMENTS
    }

    fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    fun putLe32(b: ByteArray, at: Int, value: Int) {
        b[at] = value.toByte()
        b[at + 1] = (value ushr 8).toByte()
        b[at + 2] = (value ushr 16).toByte()
        b[at + 3] = (value ushr 24).toByte()
    }

    fun le64(b: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    fun putLe64(b: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) b[at + i] = (value ushr (8 * i)).toByte()
    }
}

/**
 * Streams pages through with sequence numbers shifted by [sequenceDelta] and checksums recomputed:
 * what every page after the header needs when the header gains or loses pages.
 */
internal class OggRenumberer(private val serial: Int, private val sequenceDelta: Int) : StreamTransform {

    override fun start(): StreamTransform.Pass = object : StreamTransform.Pass {
        private var buffer = ByteArray(1 shl 16)
        private var size = 0

        override fun process(chunk: ByteArray, sink: ByteSink) {
            if (size + chunk.size > buffer.size) buffer = buffer.copyOf(maxOf(size + chunk.size, buffer.size * 2))
            chunk.copyInto(buffer, size)
            size += chunk.size
            drain(sink)
        }

        override fun finish(sink: ByteSink) {
            drain(sink)
            if (size != 0) throw StreamRefusedException(TagRefusal.OGG_MALFORMED_PAGES)
        }

        private fun drain(sink: ByteSink) {
            var start = 0
            while (size - start >= OggPages.HEADER_SIZE) {
                if (buffer[start] != 'O'.code.toByte() || buffer[start + 1] != 'g'.code.toByte() ||
                    buffer[start + 2] != 'g'.code.toByte() || buffer[start + 3] != 'S'.code.toByte()
                ) {
                    throw StreamRefusedException(TagRefusal.OGG_MALFORMED_PAGES)
                }
                val segments = buffer[start + 26].toInt() and 0xFF
                if (size - start < OggPages.HEADER_SIZE + segments) break
                var payload = 0
                for (i in 0 until segments) payload += buffer[start + OggPages.HEADER_SIZE + i].toInt() and 0xFF
                val pageSize = OggPages.HEADER_SIZE + segments + payload
                if (size - start < pageSize) break
                val page = buffer.copyOfRange(start, start + pageSize)
                val stored = OggPages.le32(page, 22)
                OggPages.putLe32(page, 22, 0)
                if (OggCrc.compute(page) != stored) throw StreamRefusedException(TagRefusal.OGG_BAD_PAGE_CRC)
                if (OggPages.le32(page, 14) != serial) throw StreamRefusedException(TagRefusal.OGG_MULTIPLE_STREAMS)
                OggPages.putLe32(page, 18, OggPages.le32(page, 18) + sequenceDelta)
                OggPages.putLe32(page, 22, OggCrc.compute(page))
                sink.write(page)
                start += pageSize
            }
            if (start > 0) {
                buffer.copyInto(buffer, 0, start, size)
                size -= start
            }
        }
    }
}
```

`ByteArraySink.reset()` is used above; add it to `ByteArraySink` in `TagIo.kt`:

```kotlin
    /** Forgets everything written, keeping the buffer. */
    public fun reset() {
        size = 0
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/OggPages.kt \
        core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagIo.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/OggPagesTest.kt
git commit -m "feat(tags): Ogg pages read, laid out and renumbered, with every checksum checked"
```

---

### Task 9: The Opus and Vorbis codec

**Files:**
- Create: `.../library/tags/OggTagCodec.kt` (codec and `Base64Codec`)
- Test: `.../library/tags/OggTagCodecTest.kt` (commonTest; defines `OggFixtures`)

**Interfaces:**
- Consumes: Tasks 1–8, including `VorbisFieldValues.toSnapshot` (Task 7).
- Produces: `internal object OggTagCodec : TagCodec`; `internal object Base64Codec { fun encode(bytes: ByteArray): String; fun decode(text: String): ByteArray? }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds Opus and Vorbis streams: a BOS identification page, tightly laced headers, audio pages. */
internal object OggFixtures {
    const val SERIAL = 0x1234567

    fun opusHead(): ByteArray =
        "OpusHead".encodeToByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)

    fun opusTags(vararg pairs: Pair<String, String>, tail: ByteArray = ByteArray(0)): ByteArray =
        "OpusTags".encodeToByteArray() + comments(*pairs) + tail

    fun vorbisIdentification(): ByteArray = byteArrayOf(1) + "vorbis".encodeToByteArray() + ByteArray(23) { 1 }

    fun vorbisComment(vararg pairs: Pair<String, String>, tail: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(3) + "vorbis".encodeToByteArray() + comments(*pairs) + byteArrayOf(1) + tail

    fun vorbisSetup(): ByteArray = byteArrayOf(5) + "vorbis".encodeToByteArray() + ByteArray(300) { it.toByte() }

    fun picture(type: Int, data: ByteArray): Pair<String, String> =
        "METADATA_BLOCK_PICTURE" to Base64Codec.encode(FlacPicture(type, "image/png", "", 1, 1, 24, 0, data).encode())

    private fun comments(vararg pairs: Pair<String, String>): ByteArray =
        VorbisComments("libopus 1.5".encodeToByteArray(), pairs.map { VorbisEntry.of(it.first, it.second) }).encode()

    fun stream(first: ByteArray, headers: List<ByteArray>, audioPages: Int = 5, serial: Int = SERIAL): ByteArray {
        var out = OggPages.serialize(0x02, 0, serial, 0, OggPages.lacingOf(first.size), first)
        val laid = OggPages.layout(headers, OggPages.minimumPages(headers))!!
        var sequence = 1
        for (page in laid) {
            out += OggPages.serialize(
                if (page.continues) 1 else 0,
                if (page.completesPacket) 0L else -1L,
                serial,
                sequence++,
                page.lacing,
                page.payload,
            )
        }
        repeat(audioPages) { i ->
            val packet = ByteArray(200) { (it + i).toByte() }
            out += OggPages.serialize(
                if (i == audioPages - 1) 0x04 else 0,
                960L * (i + 1),
                serial,
                sequence++,
                OggPages.lacingOf(packet.size),
                packet,
            )
        }
        return out
    }

    fun opus(vararg pairs: Pair<String, String>, padding: Int = 500): ByteArray =
        stream(opusHead(), listOf(opusTags(*pairs, tail = ByteArray(padding))))

    fun vorbis(vararg pairs: Pair<String, String>, padding: Int = 200): ByteArray =
        stream(vorbisIdentification(), listOf(vorbisComment(*pairs, tail = ByteArray(padding)), vorbisSetup()))

    /** Every page in [file], checked: valid CRC and consecutive sequence numbers. */
    fun assertPagesAreSound(file: ByteArray) {
        val source = ByteArraySource(file)
        var offset = 0L
        var expected = 0
        while (offset < file.size) {
            val page = assertNotNull(OggPages.readAt(source, offset), "no page at $offset")
            assertTrue(page.crcValid, "bad CRC at $offset")
            assertEquals(expected++, page.sequence)
            offset += page.size
        }
    }
}

internal class OggTagCodecTest {

    private val codec = OggTagCodec

    @Test
    fun base64MatchesTheStandardAlphabet() {
        assertEquals("TWFu", Base64Codec.encode("Man".encodeToByteArray()))
        assertEquals("TWE=", Base64Codec.encode("Ma".encodeToByteArray()))
        assertEquals("TQ==", Base64Codec.encode("M".encodeToByteArray()))
        assertContentEquals("Ma".encodeToByteArray(), Base64Codec.decode("TWE"))
        assertNull(Base64Codec.decode("TW*u"))
        val bytes = ByteArray(1000) { (it * 13).toByte() }
        assertContentEquals(bytes, Base64Codec.decode(Base64Codec.encode(bytes)))
    }

    @Test
    fun readsOpusAndVorbis() {
        val opus = codec.read(ByteArraySource(OggFixtures.opus("TITLE" to "Song", "TRACKNUMBER" to "2")))
        assertEquals(TagFormat.OPUS, opus.format)
        assertEquals("Opus", opus.version)
        assertEquals("Song", opus.title)
        assertEquals(2, opus.trackNumber)
        val vorbis = codec.read(ByteArraySource(OggFixtures.vorbis("ARTIST" to "Band")))
        assertEquals(TagFormat.VORBIS, vorbis.format)
        assertEquals("Band", vorbis.artist)
    }

    @Test
    fun opusEditWithinPaddingIsInPlaceAndTouchesOnlyTheHeader() {
        val file = OggFixtures.opus("TITLE" to "Song")
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "A longer song title"))
        assertIs<WritePlan.InPlacePatch>(plan)
        val firstPageSize = assertNotNull(OggPages.readAt(ByteArraySource(file), 0)).size
        // Five 200-byte audio packets, one per page: 27 header bytes + 1 lacing byte + 200 each.
        assertTrue(plan.writes.all { it.offset >= firstPageSize && it.offset + it.bytes.size <= file.size - 5 * 228 })
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "A longer song title"))
        OggFixtures.assertPagesAreSound(out)
    }

    @Test
    fun vorbisEditWithinPaddingKeepsTheSetupPacket() {
        val file = OggFixtures.vorbis("TITLE" to "Song")
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), TagEdits(album = "Record")))
        OggFixtures.assertPagesAreSound(CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(album = "Record")))
    }

    @Test
    fun growthPastPaddingRewritesAndRenumbersEveryLaterPage() {
        val file = OggFixtures.opus("TITLE" to "Song")
        val edits = TagEdits(lyrics = "слово ".repeat(15_000).trim())
        val plan = codec.plan(ByteArraySource(file), edits)
        assertIs<WritePlan.StreamingRewrite>(plan)
        assertTrue(plan.segments.any { it is OutputSegment.Transformed })
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
        OggFixtures.assertPagesAreSound(out)
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(out), TagEdits(title = "Again")))
    }

    @Test
    fun headerOneBytePastItsPaddingIsRewritten() {
        val file = OggFixtures.opus("TITLE" to "Song", padding = 10)
        val fits = TagEdits(title = "Song" + "0123456789")
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), fits))
        val tooBig = TagEdits(title = "Song" + "01234567890")
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), tooBig))
        OggFixtures.assertPagesAreSound(CodecAssertions.assertWriteMatchesExpectation(codec, file, tooBig))
    }

    @Test
    fun opusBinaryTailIsPreservedNeverUsedAsPadding() {
        val file = OggFixtures.stream(
            OggFixtures.opusHead(),
            listOf(OggFixtures.opusTags("TITLE" to "Abc", tail = byteArrayOf(1, 7, 7))),
        )
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), TagEdits(title = "Xyz")))
        val grown = TagEdits(title = "Abcd")
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), grown))
        CodecAssertions.assertWriteMatchesExpectation(codec, file, grown)
    }

    @Test
    fun nonZeroVorbisTailIsPreserved() {
        val file = OggFixtures.stream(
            OggFixtures.vorbisIdentification(),
            listOf(OggFixtures.vorbisComment("TITLE" to "Abc", tail = byteArrayOf(0, 5)), OggFixtures.vorbisSetup()),
        )
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "Longer title"))
    }

    @Test
    fun settingTheSameValuesWritesNothing() {
        val file = OggFixtures.opus("TITLE" to "Song")
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(title = "Song")))
    }

    @Test
    fun typeZeroPictureIsTheCoverWhenNoFrontCoverExists() {
        val file = OggFixtures.opus(OggFixtures.picture(4, byteArrayOf(1)), OggFixtures.picture(0, byteArrayOf(2)), padding = 3000)
        assertEquals(CoverInfo.of(byteArrayOf(2), "image/png"), codec.read(ByteArraySource(file)).cover)
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.png(6, 6), ImageProbe.PNG)),
        )
    }

    @Test
    fun coverIsAddedAndRemoved() {
        val file = OggFixtures.opus("TITLE" to "t", padding = 3000)
        val added = CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.jpeg(9, 9), ImageProbe.JPEG)),
        )
        CodecAssertions.assertWriteMatchesExpectation(codec, added, TagEdits(cover = CoverEdit.Remove))
    }

    @Test
    fun everyFieldAtOnceMatchesTheExpectation() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            OggFixtures.vorbis("TITLE" to "t", "ARTIST" to "A; B", "ARTISTS" to "A", "ARTISTS" to "B"),
            TagEdits(
                title = "Заголовок", artist = "X; Y", album = "Альбом", albumArtist = "Various", genre = "Rock",
                year = "2004", trackNumber = "1", trackTotal = "9", discNumber = "", discTotal = "",
                lyrics = "строка",
            ),
        )
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = "Ночь, улица, фонарь, аптека. 夜の街を歩く。\n".repeat(400).trim()
        OggFixtures.assertPagesAreSound(
            CodecAssertions.assertWriteMatchesExpectation(codec, OggFixtures.opus("TITLE" to "t"), TagEdits(lyrics = lyrics)),
        )
    }

    @Test
    fun aSecondStreamInTheHeaderIsRefused() {
        val first = OggPages.serialize(0x02, 0, 1, 0, OggPages.lacingOf(19), OggFixtures.opusHead())
        val other = OggPages.serialize(0x02, 0, 2, 0, OggPages.lacingOf(19), OggFixtures.opusHead())
        assertEquals(TagRefusal.OGG_MULTIPLE_STREAMS, codec.read(ByteArraySource(first + other)).refusal)
    }

    @Test
    fun aCorruptHeaderPageIsRefused() {
        val file = OggFixtures.opus("TITLE" to "t")
        val firstPageSize = assertNotNull(OggPages.readAt(ByteArraySource(file), 0)).size
        file[firstPageSize + 40] = (file[firstPageSize + 40].toInt() xor 1).toByte()
        assertEquals(TagRefusal.OGG_BAD_PAGE_CRC, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun anUnknownCodecIsRefused() {
        val file = OggFixtures.stream(byteArrayOf(0x7F) + "FLAC".encodeToByteArray() + ByteArray(20), listOf(ByteArray(10)))
        assertEquals(TagRefusal.OGG_UNKNOWN_CODEC, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun aChainedStreamIsRefusedDuringARewriteBeforeAnythingIsReplaced() {
        val chained = OggFixtures.opus("TITLE" to "a") +
            OggFixtures.stream(OggFixtures.opusHead(), listOf(OggFixtures.opusTags("TITLE" to "b")), serial = 77)
        val plan = codec.plan(ByteArraySource(chained), TagEdits(lyrics = "x".repeat(70_000)))
        assertIs<WritePlan.StreamingRewrite>(plan)
        val error = assertFailsWith<StreamRefusedException> { WritePlans.applyInMemory(chained, plan) }
        assertEquals(TagRefusal.OGG_MULTIPLE_STREAMS, error.reason)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `OggTagCodec`, `Base64Codec` unresolved.

- [ ] **Step 3: Create `OggTagCodec.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Opus and Vorbis in Ogg: an identification page, then the comment packet (and, for Vorbis, the
 * setup packet), then audio pages.
 *
 * The comment packet is rebuilt and laced into the same pages with the same byte total whenever its
 * discardable padding allows — only those pages are rewritten and every audio page keeps its
 * sequence number. Otherwise the header is laid out anew and every later page is renumbered.
 */
internal object OggTagCodec : TagCodec {
    private val MAGIC = "OggS".encodeToByteArray()
    private val OPUS_HEAD = "OpusHead".encodeToByteArray()
    private val VORBIS_IDENTIFICATION = byteArrayOf(1) + "vorbis".encodeToByteArray()
    private const val PICTURE_KEY = "METADATA_BLOCK_PICTURE"
    private const val MAX_HEADER_PAGES = 4096
    private const val MAX_HEADER_BYTES = 64L shl 20

    private enum class Kind(val format: TagFormat, val label: String, val prefix: ByteArray, val headerPackets: Int) {
        OPUS(TagFormat.OPUS, "Opus", "OpusTags".encodeToByteArray(), 1),
        VORBIS(TagFormat.VORBIS, "Vorbis", byteArrayOf(3) + "vorbis".encodeToByteArray(), 2),
    }

    private class Layout(
        val kind: Kind,
        val first: OggPage,
        val headerPages: List<OggPage>,
        val comment: ByteArray,
        val setup: ByteArray?,
        val audioStart: Long,
    )

    private sealed interface Parsed {
        class Ok(val layout: Layout) : Parsed
        class Bad(val reason: TagRefusal) : Parsed
    }

    /** The decoded comment packet: the block, and what follows it. */
    private class Comment(val block: VorbisComments, val tail: ByteArray, val tailIsPadding: Boolean)

    override fun recognizes(head: ByteArray): Boolean =
        head.size >= 4 && MAGIC.indices.all { head[it] == MAGIC[it] }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean =
        data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

    private fun parse(source: RandomAccessSource): Parsed {
        val first = OggPages.readAt(source, 0) ?: return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
        if (!first.crcValid) return Parsed.Bad(TagRefusal.OGG_BAD_PAGE_CRC)
        if (!first.isBeginning || first.continues) return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
        val (identification, closed) = OggPages.packets(listOf(first)) ?: return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
        if (!closed || identification.size != 1) return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
        val kind = when {
            startsWith(identification[0], OPUS_HEAD) -> Kind.OPUS
            startsWith(identification[0], VORBIS_IDENTIFICATION) -> Kind.VORBIS
            else -> return Parsed.Bad(TagRefusal.OGG_UNKNOWN_CODEC)
        }
        val pages = ArrayList<OggPage>()
        var offset = first.size.toLong()
        while (true) {
            val page = OggPages.readAt(source, offset)
            if (page == null) {
                val magic = source.read(offset, 4)
                val cut = offset + OggPages.HEADER_SIZE > source.length ||
                    (magic != null && MAGIC.indices.all { magic[it] == MAGIC[it] })
                return Parsed.Bad(if (cut) TagRefusal.TRUNCATED else TagRefusal.OGG_MALFORMED_PAGES)
            }
            if (page.serial != first.serial || page.isBeginning) return Parsed.Bad(TagRefusal.OGG_MULTIPLE_STREAMS)
            if (!page.crcValid) return Parsed.Bad(TagRefusal.OGG_BAD_PAGE_CRC)
            pages += page
            offset += page.size
            val (packets, pagesClosed) = OggPages.packets(pages) ?: return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
            if (packets.size > kind.headerPackets) return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
            if (packets.size == kind.headerPackets) {
                // Audio must start on a fresh page; a header page that runs into audio is malformed.
                if (!pagesClosed) return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
                return Parsed.Ok(Layout(kind, first, pages, packets[0], packets.getOrNull(1), offset))
            }
            if (pages.size > MAX_HEADER_PAGES || offset > MAX_HEADER_BYTES) {
                return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
            }
        }
    }

    private fun comment(layout: Layout): Comment? {
        val packet = layout.comment
        if (!startsWith(packet, layout.kind.prefix)) return null
        val (block, end) = VorbisComments.decode(packet, layout.kind.prefix.size) ?: return null
        return when (layout.kind) {
            // RFC 7845 §5.2: data after the comments whose first byte has its low bit set must be kept.
            Kind.OPUS -> {
                val tail = packet.copyOfRange(end, packet.size)
                Comment(block, tail, tailIsPadding = tail.isEmpty() || (tail[0].toInt() and 1) == 0)
            }
            // Vorbis ends with a framing bit; anything after it is undefined, so only zeros are padding.
            Kind.VORBIS -> {
                if (end >= packet.size || (packet[end].toInt() and 1) == 0) return null
                val tail = packet.copyOfRange(end + 1, packet.size)
                Comment(block, tail, tailIsPadding = tail.all { it == 0.toByte() })
            }
        }
    }

    /** (entry index, picture) of the cover among the METADATA_BLOCK_PICTURE entries. */
    private fun coverEntry(entries: List<VorbisEntry>): Pair<Int, FlacPicture>? {
        val pictures = entries.withIndex()
            .filter { it.value.key == PICTURE_KEY }
            .mapNotNull { (index, entry) ->
                Base64Codec.decode(entry.value)?.let { FlacPicture.decode(it) }?.let { index to it }
            }
        return CoverTarget.index(pictures.map { it.second.type })?.let { pictures[it] }
    }

    private fun refused(source: RandomAccessSource, reason: TagRefusal): TagSnapshot {
        val opus = OggPages.readAt(source, 0)?.payload?.let { startsWith(it, OPUS_HEAD) } ?: true
        return if (opus) {
            TagSnapshot(TagFormat.OPUS, "Opus", refusal = reason)
        } else {
            TagSnapshot(TagFormat.VORBIS, "Vorbis", refusal = reason)
        }
    }

    override fun read(source: RandomAccessSource): TagSnapshot {
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return refused(source, parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val comment = comment(layout) ?: return refused(source, TagRefusal.OGG_MALFORMED_PAGES)
        val entries = comment.block.entries
        val cover = coverEntry(entries)
        val next = cover?.let { chosen -> coverEntry(entries.filterIndexed { index, _ -> index != chosen.first }) }
        return VorbisFields.read(entries).toSnapshot(
            format = layout.kind.format,
            version = layout.kind.label,
            cover = cover?.second?.let { CoverInfo.of(it.data, it.mime) },
            otherPictures = entries.count { it.key == PICTURE_KEY } - (if (cover != null) 1 else 0),
            nextCover = next?.second?.let { CoverInfo.of(it.data, it.mime) },
            pictures = entries.filter { it.key == PICTURE_KEY }
                .map { entry ->
                    Base64Codec.decode(entry.value)?.let { FlacPicture.decode(it) }?.let { Crc32.of(it.data) }
                        ?: Crc32.of(entry.raw)
                }
                .sorted(),
        )
    }

    override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan {
        val normalized = edits.normalized()
        if (normalized.isEmpty) return WritePlan.NoChange
        EditChecks.refusal(normalized)?.let { return WritePlan.Refused(it) }
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return WritePlan.Refused(parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val comment = comment(layout) ?: return WritePlan.Refused(TagRefusal.OGG_MALFORMED_PAGES)
        val before = comment.block.entries
        val after = applyCover(VorbisFields.apply(before, normalized), normalized.cover)
        if (before.size == after.size && before.indices.all { before[it].raw.contentEquals(after[it].raw) }) {
            return WritePlan.NoChange
        }

        val framing = if (layout.kind == Kind.VORBIS) byteArrayOf(1) else ByteArray(0)
        val body = layout.kind.prefix + VorbisComments(comment.block.vendor, after).encode() + framing
        val preserved = if (comment.tailIsPadding) null else comment.tail
        val minimum = body.size + (preserved?.size ?: 0)
        val pageCount = layout.headerPages.size
        val headerBytes = layout.headerPages.sumOf { it.size.toLong() }
        val setupCost = layout.setup?.let { cost(it.size) } ?: 0L
        val target = headerBytes - OggPages.HEADER_SIZE.toLong() * pageCount - setupCost

        val inPlaceLength = if (preserved == null) packetLengthFor(target, minimum) else minimum.takeIf { cost(it) == target }
        if (inPlaceLength != null) {
            val packet = if (preserved != null) body + preserved else body + ByteArray(inPlaceLength - body.size)
            val pages = OggPages.layout(listOfNotNull(packet, layout.setup), pageCount)
            if (pages != null) {
                val old = source.read(layout.first.size.toLong(), headerBytes.toInt())
                    ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
                return ByteDiff.patchOrNoChange(layout.first.size.toLong(), old, headerPages(layout, pages), source.length, source.length)
            }
        }

        val packet = if (preserved != null) body + preserved else body + ByteArray(TagSpace.SPARE_BYTES)
        val packets = listOfNotNull(packet, layout.setup)
        val count = OggPages.minimumPages(packets)
        val pages = OggPages.layout(packets, count) ?: return WritePlan.Refused(TagRefusal.OGG_MALFORMED_PAGES)
        val delta = count - pageCount
        val rest = source.length - layout.audioStart
        val audio = if (delta == 0) {
            OutputSegment.Copy(layout.audioStart, rest)
        } else {
            OutputSegment.Transformed(layout.audioStart, rest, OggRenumberer(layout.first.serial, delta))
        }
        return WritePlan.StreamingRewrite(
            listOf(
                OutputSegment.Copy(0, layout.first.size.toLong()),
                OutputSegment.Bytes(headerPages(layout, pages)),
                audio,
            ),
        )
    }

    /** Bytes a packet of [length] occupies in pages: its payload plus its lacing values. */
    private fun cost(length: Int): Long = length.toLong() + length / 255 + 1

    /** The packet length at least [minimum] whose [cost] is exactly [target], or null. */
    private fun packetLengthFor(target: Long, minimum: Int): Int? {
        if (target < cost(minimum)) return null
        val estimate = (target * 255 / 256).toInt()
        for (length in maxOf(minimum, estimate - 3)..estimate + 3) {
            if (cost(length) == target) return length
        }
        return null
    }

    private fun headerPages(layout: Layout, pages: List<OggPages.PageContent>): ByteArray {
        val sink = ByteArraySink()
        val firstSequence = layout.headerPages.first().sequence
        for ((i, content) in pages.withIndex()) {
            sink.write(
                OggPages.serialize(
                    headerType = if (content.continues) 1 else 0,
                    granule = if (content.completesPacket) 0L else -1L,
                    serial = layout.first.serial,
                    sequence = firstSequence + i,
                    lacing = content.lacing,
                    payload = content.payload,
                ),
            )
        }
        return sink.toByteArray()
    }

    private fun applyCover(entries: List<VorbisEntry>, cover: CoverEdit): List<VorbisEntry> {
        if (cover == CoverEdit.Keep) return entries
        val target = coverEntry(entries)
        return when (cover) {
            CoverEdit.Keep -> entries
            CoverEdit.Remove -> if (target == null) entries else entries.filterIndexed { i, _ -> i != target.first }
            is CoverEdit.Replace -> {
                val base = checkNotNull(FlacPicture.frontCover(cover.bytes, cover.mime)) { "validated by EditChecks" }
                val picture = FlacPicture(
                    FlacPicture.FRONT_COVER, base.mime, target?.second?.description ?: "",
                    base.width, base.height, base.depth, 0, base.data,
                )
                val entry = VorbisEntry.of(PICTURE_KEY, Base64Codec.encode(picture.encode()))
                if (target == null) entries + entry else entries.toMutableList().also { it[target.first] = entry }
            }
        }
    }

    override fun audioDigest(source: RandomAccessSource): Long? {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return null
        val crc = Crc32()
        val fields = ByteArray(13)
        var offset = layout.audioStart
        while (offset < source.length) {
            val page = OggPages.readAt(source, offset) ?: return null
            // Everything but the sequence number and the checksum, which a renumbering rewrite changes.
            fields[0] = page.headerType.toByte()
            OggPages.putLe64(fields, 1, page.granule)
            OggPages.putLe32(fields, 9, page.serial)
            crc.update(fields)
            crc.update(ByteArray(page.lacing.size) { page.lacing[it].toByte() })
            crc.update(page.payload)
            offset += page.size
        }
        return crc.value
    }

    override fun inventory(source: RandomAccessSource): List<String> {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return emptyList()
        val comment = comment(layout) ?: return emptyList()
        val out = ArrayList<String>()
        out += "identification:${Crc32.of(layout.first.payload)}"
        layout.setup?.let { out += "setup:${Crc32.of(it)}" }
        out += "vendor:${Crc32.of(comment.block.vendor)}"
        // Pictures are pinned by TagSnapshot.pictures, which also knows which one an edit changes.
        comment.block.entries
            .filter { it.key !in VorbisFields.MANAGED && it.key != PICTURE_KEY }
            .forEach { out += "comment:${Crc32.of(it.raw)}" }
        if (!comment.tailIsPadding) out += "tail:${Crc32.of(comment.tail)}"
        return out
    }
}

/** RFC 4648 base64 (standard alphabet), for METADATA_BLOCK_PICTURE. Padding optional on input. */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = (b(i) shl 16) or (b(i + 1) shl 8) or b(i + 2)
            out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                .append(ALPHABET[n ushr 6 and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = b(i) shl 16
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63]).append("==")
            }
            2 -> {
                val n = (b(i) shl 16) or (b(i + 1) shl 8)
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                    .append(ALPHABET[n ushr 6 and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** The decoded bytes, or null for any character outside the alphabet or an impossible length. */
    fun decode(text: String): ByteArray? {
        val clean = text.trimEnd('=')
        if (clean.length % 4 == 1) return null
        val out = ByteArray(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var p = 0
        for (c in clean) {
            val value = if (c.code < 128) DECODE[c.code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[p++] = (buffer ushr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/OggTagCodec.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/OggTagCodecTest.kt
git commit -m "feat(tags): Opus and Vorbis tags rewritten in their own pages, audio pages untouched"
```

---
### Task 10: MP4 boxes — top-level walk and an exact, editable `moov` tree

**Files:**
- Create: `.../library/tags/Mp4Boxes.kt`
- Test: `.../library/tags/Mp4BoxesTest.kt` (commonTest; defines `Mp4Fixtures`, reused by Task 11)

**Interfaces:**
- Consumes: `RandomAccessSource`, `ByteSink`, `ByteArraySink` (Task 2).
- Produces:
  - `internal class Mp4Atom(type: String, offset: Long, headerSize: Int, size: Long)` with `end`.
  - `internal class Mp4Box(type: String, payload: ByteArray, children: MutableList<Mp4Box>?, prefix: ByteArray = ByteArray(0))` with `size: Long`, `child(type): Mp4Box?`, `serialize(): ByteArray`, `write(sink)`; `companion { fun leaf(type, payload); fun container(type, children, prefix = ByteArray(0)) }`. `children == null` means a leaf.
  - `internal object Mp4Boxes { fun typeOf(b: ByteArray, at: Int): String; fun typeBytes(type: String): ByteArray; fun be16/be24/be32 (unsigned, Long for 32)/be64; fun putBe32(b, at, Long); fun putBe64(b, at, Long); fun topLevel(source): List<Mp4Atom>?; fun parse(bytes: ByteArray): Mp4Box?; fun walk(box): Sequence<Mp4Box> }`.
  - Box types are Latin-1 strings: `©` is byte `0xA9`, so the type `"©nam"` round-trips through `typeOf`/`typeBytes`.
  - Containers opened by `parse`: `moov trak mdia minf stbl udta edts dinf meta ilst`, and every child of `ilst` (an item). `meta` gets a 4-byte `prefix` when it is the ISO full-box form (its first child is not at offset 0 of its body); the QuickTime form has none.

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Builds MP4 files box by box. Shares no code with the codec's serializer. */
internal object Mp4Fixtures {
    fun be32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    fun type(type: String): ByteArray = ByteArray(4) { type[it].code.toByte() }

    fun leaf(type: String, payload: ByteArray): ByteArray = be32(8 + payload.size) + type(type) + payload

    fun box(type: String, vararg children: ByteArray): ByteArray =
        leaf(type, children.fold(ByteArray(0)) { all, child -> all + child })

    fun data(code: Int, value: ByteArray): ByteArray = leaf("data", byteArrayOf(0, 0, 0, code.toByte()) + ByteArray(4) + value)

    fun text(type: String, value: String): ByteArray = box(type, data(1, value.encodeToByteArray()))

    fun pair(type: String, number: Int, total: Int, length: Int = 8): ByteArray {
        val value = ByteArray(length)
        value[2] = (number ushr 8).toByte()
        value[3] = number.toByte()
        value[4] = (total ushr 8).toByte()
        value[5] = total.toByte()
        return box(type, data(0, value))
    }

    fun freeform(name: String, vararg values: String): ByteArray = box(
        "----",
        leaf("mean", ByteArray(4) + "com.apple.iTunes".encodeToByteArray()),
        leaf("name", ByteArray(4) + name.encodeToByteArray()),
        *values.map { data(1, it.encodeToByteArray()) }.toTypedArray(),
    )

    fun covers(vararg images: Pair<Int, ByteArray>): ByteArray =
        box("covr", *images.map { (code, bytes) -> data(code, bytes) }.toTypedArray())

    fun free(size: Int): ByteArray = leaf("free", ByteArray(size - 8))

    fun hdlr(): ByteArray = leaf("hdlr", ByteArray(8) + "mdirappl".encodeToByteArray() + ByteArray(9))

    fun meta(items: List<ByteArray>, freeAfterIlst: Int = 0, iso: Boolean = true): ByteArray = leaf(
        "meta",
        (if (iso) ByteArray(4) else ByteArray(0)) + hdlr() + box("ilst", *items.toTypedArray()) +
            (if (freeAfterIlst > 0) free(freeAfterIlst) else ByteArray(0)),
    )

    fun ftyp(): ByteArray = leaf("ftyp", "M4A ".encodeToByteArray() + ByteArray(4) + "M4A mp42isom".encodeToByteArray())

    val samples = ByteArray(2000) { (it * 3 + 1).toByte() }

    fun trak(offsets: List<Int>, sampleEntry: String = "mp4a", extraStbl: ByteArray = ByteArray(0)): ByteArray = box(
        "trak",
        leaf("tkhd", ByteArray(84)),
        box(
            "mdia",
            leaf("mdhd", ByteArray(24)),
            box(
                "minf",
                box(
                    "stbl",
                    leaf("stsd", ByteArray(4) + be32(1) + leaf(sampleEntry, ByteArray(28))),
                    leaf("stts", ByteArray(8)),
                    leaf("stsz", ByteArray(12)),
                    leaf("stco", ByteArray(4) + be32(offsets.size) + offsets.fold(ByteArray(0)) { all, o -> all + be32(o) }),
                    extraStbl,
                ),
            ),
        ),
    )

    fun moov(offsets: List<Int>, items: List<ByteArray>?, freeAfterIlst: Int = 0, iso: Boolean = true, sampleEntry: String = "mp4a", extraStbl: ByteArray = ByteArray(0)): ByteArray =
        box(
            "moov",
            leaf("mvhd", ByteArray(100)),
            trak(offsets, sampleEntry, extraStbl),
            if (items == null) ByteArray(0) else box("udta", meta(items, freeAfterIlst, iso)),
        )

    /**
     * ftyp, then moov and one mdat in either order, and optionally a top-level free after moov.
     * The two chunk offsets point at samples[0] and samples[1000] inside the mdat.
     */
    fun file(
        items: List<ByteArray>?,
        moovFirst: Boolean = true,
        freeAfterIlst: Int = 0,
        freeAfterMoov: Int = 0,
        iso: Boolean = true,
        sampleEntry: String = "mp4a",
        extraStbl: ByteArray = ByteArray(0),
    ): ByteArray {
        val ftyp = ftyp()
        val mdat = leaf("mdat", samples)
        val free = if (freeAfterMoov > 0) free(freeAfterMoov) else ByteArray(0)
        fun moovAt(mdatStart: Int) =
            moov(listOf(mdatStart + 8, mdatStart + 8 + 1000), items, freeAfterIlst, iso, sampleEntry, extraStbl)
        val moovSize = moovAt(0).size
        return if (moovFirst) {
            ftyp + moovAt(ftyp.size + moovSize + free.size) + free + mdat
        } else {
            ftyp + mdat + moovAt(ftyp.size) + free
        }
    }

    /** The chunk offsets of the first stco in [file]. */
    fun chunkOffsets(file: ByteArray): List<Long> {
        val source = ByteArraySource(file)
        val atom = Mp4Boxes.topLevel(source)!!.first { it.type == "moov" }
        val moov = Mp4Boxes.parse(source.read(atom.offset, atom.size.toInt())!!)!!
        val stco = Mp4Boxes.walk(moov).first { it.type == "stco" }
        val count = Mp4Boxes.be32(stco.payload, 4).toInt()
        return (0 until count).map { Mp4Boxes.be32(stco.payload, 8 + 4 * it) }
    }
}

internal class Mp4BoxesTest {

    private val items = listOf(
        Mp4Fixtures.text("©nam", "Song"),
        Mp4Fixtures.pair("trkn", 3, 12),
        Mp4Fixtures.freeform("ARTISTS", "A", "B"),
    )

    @Test
    fun topLevelTilesTheFile() {
        val file = Mp4Fixtures.file(items)
        val atoms = assertNotNull(Mp4Boxes.topLevel(ByteArraySource(file)))
        assertEquals(listOf("ftyp", "moov", "mdat"), atoms.map { it.type })
        assertEquals(file.size.toLong(), atoms.last().end)
    }

    @Test
    fun topLevelRejectsSizesPastTheEnd() {
        val file = Mp4Fixtures.file(items)
        assertNull(Mp4Boxes.topLevel(ByteArraySource(file.copyOf(file.size - 1))))
    }

    @Test
    fun moovParsesAndSerializesByteForByte() {
        for (iso in listOf(true, false)) {
            val moov = Mp4Fixtures.moov(listOf(1, 2), items, freeAfterIlst = 64, iso = iso)
            val tree = assertNotNull(Mp4Boxes.parse(moov))
            assertContentEquals(moov, tree.serialize())
            val meta = assertNotNull(tree.child("udta")?.child("meta"))
            assertEquals(if (iso) 4 else 0, meta.prefix.size)
            assertEquals(listOf("hdlr", "ilst", "free"), meta.children!!.map { it.type })
        }
    }

    @Test
    fun ilstItemsAreContainersOfDataAtoms() {
        val tree = assertNotNull(Mp4Boxes.parse(Mp4Fixtures.moov(listOf(1), items)))
        val ilst = assertNotNull(tree.child("udta")?.child("meta")?.child("ilst"))
        assertEquals(listOf("©nam", "trkn", "----"), ilst.children!!.map { it.type })
        assertEquals(listOf("mean", "name", "data", "data"), ilst.children!![2].children!!.map { it.type })
    }

    @Test
    fun typesRoundTripThroughLatin1() {
        assertContentEquals(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), Mp4Boxes.typeBytes("©nam"))
        assertEquals("©nam", Mp4Boxes.typeOf(Mp4Boxes.typeBytes("©nam"), 0))
    }

    @Test
    fun parseRejectsAChildRunningPastItsParent() {
        val bad = Mp4Fixtures.leaf("moov", Mp4Fixtures.be32(100) + Mp4Fixtures.type("trak") + ByteArray(10))
        assertNull(Mp4Boxes.parse(bad))
    }

    @Test
    fun chunkOffsetsPointAtTheSamples() {
        for (moovFirst in listOf(true, false)) {
            val file = Mp4Fixtures.file(items, moovFirst = moovFirst)
            val offsets = Mp4Fixtures.chunkOffsets(file)
            assertEquals(Mp4Fixtures.samples[0], file[offsets[0].toInt()])
            assertEquals(Mp4Fixtures.samples[1000], file[offsets[1].toInt()])
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `Mp4Boxes`, `Mp4Box`, `Mp4Atom` unresolved.

- [ ] **Step 3: Create `Mp4Boxes.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** A top-level box as it sits in the file. */
internal class Mp4Atom(val type: String, val offset: Long, val headerSize: Int, val size: Long) {
    val end: Long get() = offset + size
}

/**
 * A box held in memory: a leaf keeps its payload, a container its children (plus any bytes that
 * precede them, such as an ISO `meta` box's version and flags). Serialization always writes 32-bit
 * sizes, which is why a tree is only edited after it re-serializes to exactly the bytes it came from.
 */
internal class Mp4Box(
    val type: String,
    var payload: ByteArray,
    val children: MutableList<Mp4Box>?,
    var prefix: ByteArray = ByteArray(0),
) {
    val size: Long
        get() = 8L + if (children == null) payload.size.toLong() else prefix.size + children.sumOf { it.size }

    fun child(type: String): Mp4Box? = children?.firstOrNull { it.type == type }

    fun serialize(): ByteArray = ByteArraySink().also { write(it) }.toByteArray()

    fun write(sink: ByteSink) {
        val total = size
        require(total <= 0xFFFFFFFFL) { "box $type too large for a 32-bit size" }
        val header = ByteArray(8)
        Mp4Boxes.putBe32(header, 0, total)
        Mp4Boxes.typeBytes(type).copyInto(header, 4)
        sink.write(header)
        if (children == null) {
            sink.write(payload)
        } else {
            sink.write(prefix)
            children.forEach { it.write(sink) }
        }
    }

    companion object {
        fun leaf(type: String, payload: ByteArray): Mp4Box = Mp4Box(type, payload, null)

        fun container(type: String, children: List<Mp4Box>, prefix: ByteArray = ByteArray(0)): Mp4Box =
            Mp4Box(type, ByteArray(0), children.toMutableList(), prefix)
    }
}

internal object Mp4Boxes {
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "dinf", "meta", "ilst")
    private const val MAX_TOP_LEVEL = 100_000

    fun typeOf(b: ByteArray, at: Int): String = CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() }.concatToString()

    fun typeBytes(type: String): ByteArray = ByteArray(4) { type[it].code.toByte() }

    fun be16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    fun be24(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() and 0xFF)

    fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    fun be64(b: ByteArray, at: Int): Long = (be32(b, at) shl 32) or be32(b, at + 4)

    fun putBe32(b: ByteArray, at: Int, value: Long) {
        b[at] = (value ushr 24).toByte()
        b[at + 1] = (value ushr 16).toByte()
        b[at + 2] = (value ushr 8).toByte()
        b[at + 3] = value.toByte()
    }

    fun putBe64(b: ByteArray, at: Int, value: Long) {
        putBe32(b, at, value ushr 32)
        putBe32(b, at + 4, value)
    }

    /** The top-level boxes, which must tile the file exactly; null when they do not. */
    fun topLevel(source: RandomAccessSource): List<Mp4Atom>? {
        val atoms = ArrayList<Mp4Atom>()
        var offset = 0L
        while (offset < source.length) {
            val header = source.read(offset, 8) ?: return null
            val size32 = be32(header, 0)
            val (headerSize, size) = when (size32) {
                0L -> 8 to source.length - offset
                1L -> 16 to be64(source.read(offset + 8, 8) ?: return null, 0)
                else -> 8 to size32
            }
            if (size < headerSize || offset + size > source.length) return null
            atoms += Mp4Atom(typeOf(header, 4), offset, headerSize, size)
            offset += size
            if (atoms.size > MAX_TOP_LEVEL) return null
        }
        return atoms
    }

    /** One complete box as a tree, or null when any size does not add up. */
    fun parse(bytes: ByteArray): Mp4Box? {
        val (box, end) = parseAt(bytes, 0, bytes.size, parentType = "") ?: return null
        return box.takeIf { end == bytes.size }
    }

    fun walk(box: Mp4Box): Sequence<Mp4Box> = sequence {
        yield(box)
        box.children?.forEach { yieldAll(walk(it)) }
    }

    private fun parseAt(bytes: ByteArray, offset: Int, end: Int, parentType: String): Pair<Mp4Box, Int>? {
        if (offset + 8 > end) return null
        val size32 = be32(bytes, offset)
        val type = typeOf(bytes, offset + 4)
        val (header, size) = when (size32) {
            0L -> 8 to (end - offset).toLong()
            1L -> {
                if (offset + 16 > end) return null
                16 to be64(bytes, offset + 8)
            }
            else -> 8 to size32
        }
        if (size < header || offset + size > end) return null
        val bodyStart = offset + header
        val boxEnd = (offset + size).toInt()
        if (type !in CONTAINERS && parentType != "ilst") {
            return Mp4Box.leaf(type, bytes.copyOfRange(bodyStart, boxEnd)) to boxEnd
        }
        var childStart = bodyStart
        var prefix = ByteArray(0)
        if (type == "meta" && isoMeta(bytes, bodyStart, boxEnd)) {
            prefix = bytes.copyOfRange(bodyStart, bodyStart + 4)
            childStart += 4
        }
        val children = ArrayList<Mp4Box>()
        var p = childStart
        while (p < boxEnd) {
            val (child, next) = parseAt(bytes, p, boxEnd, type) ?: return null
            children += child
            p = next
        }
        return Mp4Box(type, ByteArray(0), children, prefix) to boxEnd
    }

    /** ISO `meta` is a full box: 4 bytes of version and flags before its first child. QuickTime's is not. */
    private fun isoMeta(bytes: ByteArray, bodyStart: Int, end: Int): Boolean {
        if (bodyStart + 8 <= end && typeOf(bytes, bodyStart + 4) == "hdlr") return false
        return bodyStart + 4 <= end
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Mp4Boxes.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/Mp4BoxesTest.kt
git commit -m "feat(tags): an MP4 box tree that reproduces moov byte for byte before anything is edited"
```

---

### Task 11: The M4A codec

**Files:**
- Create: `.../library/tags/Mp4TagCodec.kt`
- Test: `.../library/tags/Mp4TagCodecTest.kt` (commonTest)

**Interfaces:**
- Consumes: Tasks 1–10 (`Mp4Boxes`, `Mp4Box`, `Mp4Atom`, `Mp4Fixtures`, `GenreTags.split`, `TagFacts.splitArtists`, `CreditedArtists`, `TagNumbers`).
- Produces: `internal object Mp4TagCodec : TagCodec`.

Plan cases, tried in this order (spec §4.4):
1. **A — `ilst` fits** in its old size plus the `free` boxes right after it inside `meta`: rewrite inside `moov`, `moov` keeps its size, nothing moves.
2. **B1 — `moov` fits** in itself plus the top-level `free`/`skip` boxes right after it (first with 16 KiB spare inside `meta`, then without): nothing after them moves.
3. **B2 — `moov` is the last box** (only `free`/`skip` after it): write the new `moov` at its offset and set the file length.
4. **C — general**: streaming rewrite; every `stco`/`co64` entry at or past the old end of `moov` moves by the size change, entries before `moov` stay, an entry inside the old `moov` refuses the edit.

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class Mp4TagCodecTest {

    private val codec = Mp4TagCodec

    private val items = listOf(
        Mp4Fixtures.text("©nam", "Song"),
        Mp4Fixtures.text("©ART", "A; B"),
        Mp4Fixtures.pair("trkn", 3, 12),
        Mp4Fixtures.pair("disk", 1, 2, length = 6),
        Mp4Fixtures.freeform("ARTISTS", "A", "B"),
        Mp4Fixtures.freeform("MusicBrainz Track Id", "abc"),
        Mp4Fixtures.covers(13 to TestImages.jpeg(4, 4), 14 to TestImages.png(2, 2)),
    )

    private fun assertSamplesStillAddressed(file: ByteArray) {
        val offsets = Mp4Fixtures.chunkOffsets(file)
        assertEquals(Mp4Fixtures.samples[0], file[offsets[0].toInt()])
        assertEquals(Mp4Fixtures.samples[1000], file[offsets[1].toInt()])
    }

    @Test
    fun recognizesFtyp() {
        assertTrue(codec.recognizes(Mp4Fixtures.file(items).copyOf(TagCodec.HEAD_BYTES)))
        assertTrue(!codec.recognizes("fLaC".encodeToByteArray() + ByteArray(60)))
    }

    @Test
    fun readsTextNumbersCoverAndCreditedArtists() {
        val snapshot = codec.read(ByteArraySource(Mp4Fixtures.file(items)))
        assertEquals(TagFormat.MP4, snapshot.format)
        assertEquals("Song", snapshot.title)
        assertEquals(3, snapshot.trackNumber)
        assertEquals(12, snapshot.trackTotal)
        assertEquals(1, snapshot.discNumber)
        assertEquals(2, snapshot.discTotal)
        assertEquals(listOf("A", "B"), snapshot.artists)
        assertEquals(CoverInfo.of(TestImages.jpeg(4, 4), ImageProbe.JPEG), snapshot.cover)
        assertEquals(CoverInfo.of(TestImages.png(2, 2), ImageProbe.PNG), snapshot.nextCover)
        assertEquals(1, snapshot.otherPictures)
    }

    @Test
    fun editWithinIlstFreeIsInPlaceAndMovesNothing() {
        val file = Mp4Fixtures.file(items, freeAfterIlst = 2000)
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "A much longer song title"))
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size.toLong(), plan.newLength)
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "A much longer song title"))
        assertEquals(Mp4Fixtures.chunkOffsets(file), Mp4Fixtures.chunkOffsets(out))
    }

    @Test
    fun moovGrowsIntoTheFreeBoxAfterIt() {
        val file = Mp4Fixtures.file(items, freeAfterMoov = 20_000)
        val edits = TagEdits(lyrics = "x".repeat(1000))
        val plan = codec.plan(ByteArraySource(file), edits)
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size.toLong(), plan.newLength)
        assertSamplesStillAddressed(CodecAssertions.assertWriteMatchesExpectation(codec, file, edits))
    }

    @Test
    fun moovLastIsRewrittenAtTheEndWithoutTouchingTheAudio() {
        val file = Mp4Fixtures.file(items, moovFirst = false)
        val moovOffset = Mp4Boxes.topLevel(ByteArraySource(file))!!.first { it.type == "moov" }.offset
        val edits = TagEdits(lyrics = "x".repeat(1000))
        val plan = codec.plan(ByteArraySource(file), edits)
        assertIs<WritePlan.InPlacePatch>(plan)
        assertTrue(plan.newLength > file.size)
        assertTrue(plan.writes.all { it.offset >= moovOffset })
        assertSamplesStillAddressed(CodecAssertions.assertWriteMatchesExpectation(codec, file, edits))
    }

    @Test
    fun moovFirstWithoutRoomIsRewrittenAndChunkOffsetsFollowTheAudio() {
        val file = Mp4Fixtures.file(items)
        val edits = TagEdits(lyrics = "x".repeat(1000))
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), edits))
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
        assertSamplesStillAddressed(out)
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(out), TagEdits(title = "Again")))
    }

    @Test
    fun offsetsBeforeMoovStayPut() {
        val ftyp = Mp4Fixtures.ftyp()
        val first = Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        val second = Mp4Fixtures.leaf("mdat", ByteArray(500) { 42 })
        fun moov(after: Int) = Mp4Fixtures.moov(listOf(ftyp.size + 8, after + 8), items)
        val moovSize = moov(0).size
        val file = ftyp + first + moov(ftyp.size + first.size + moovSize) + second
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(lyrics = "y".repeat(1000)))
        val offsets = Mp4Fixtures.chunkOffsets(out)
        assertEquals((ftyp.size + 8).toLong(), offsets[0])
        assertEquals(Mp4Fixtures.samples[0], out[offsets[0].toInt()])
        assertEquals(42.toByte(), out[offsets[1].toInt()])
        assertTrue(offsets[1] > (ftyp.size + first.size + moovSize + 8).toLong())
    }

    @Test
    fun settingTheSameValuesWritesNothing() {
        val file = Mp4Fixtures.file(items)
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(title = "Song", trackNumber = "3")))
    }

    @Test
    fun aFileWithoutTagsGetsThem() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            Mp4Fixtures.file(null),
            TagEdits(title = "First", trackNumber = "1", cover = CoverEdit.Replace(TestImages.png(3, 3), ImageProbe.PNG)),
        )
    }

    @Test
    fun quickTimeStyleMetaIsEditedToo() {
        val file = Mp4Fixtures.file(items, freeAfterIlst = 1000, iso = false)
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), TagEdits(album = "Record")))
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(album = "Record"))
    }

    @Test
    fun coverReplaceAndRemoveActOnTheFirstImageOnly() {
        val file = Mp4Fixtures.file(items, freeAfterIlst = 5000)
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.png(6, 6), ImageProbe.PNG)),
        )
        val removed = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(cover = CoverEdit.Remove))
        assertEquals(CoverInfo.of(TestImages.png(2, 2), ImageProbe.PNG), codec.read(ByteArraySource(removed)).cover)
    }

    @Test
    fun aTextGenreReplacesTheNumericOne() {
        val file = Mp4Fixtures.file(listOf(Mp4Fixtures.box("gnre", Mp4Fixtures.data(0, byteArrayOf(0, 18)))), freeAfterIlst = 500)
        assertEquals("Rock", codec.read(ByteArraySource(file)).genre)
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(genre = "Jazz"))
        assertEquals("Jazz", codec.read(ByteArraySource(out)).genre)
    }

    @Test
    fun removingANumberKeepsTheTotal() {
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, Mp4Fixtures.file(items), TagEdits(trackNumber = ""))
        val snapshot = codec.read(ByteArraySource(out))
        assertEquals(null, snapshot.trackNumber)
        assertEquals(12, snapshot.trackTotal)
    }

    @Test
    fun everyFieldAtOnceMatchesTheExpectation() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            Mp4Fixtures.file(items),
            TagEdits(
                title = "Заголовок", artist = "X; Y", album = "Альбом", albumArtist = "Various", genre = "Rock",
                year = "2004-05-06", trackNumber = "1", trackTotal = "9", discNumber = "2", discTotal = "",
                lyrics = "строка", cover = CoverEdit.Replace(TestImages.jpeg(7, 7), ImageProbe.JPEG),
            ),
        )
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = "Ночь, улица, фонарь, аптека. 夜の街を歩く。\n".repeat(400).trim()
        assertSamplesStillAddressed(
            CodecAssertions.assertWriteMatchesExpectation(codec, Mp4Fixtures.file(items), TagEdits(lyrics = lyrics)),
        )
    }

    @Test
    fun fragmentedFilesAreRefused() {
        val file = Mp4Fixtures.file(items) + Mp4Fixtures.leaf("moof", ByteArray(16))
        assertEquals(TagRefusal.MP4_FRAGMENTED, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun protectedFilesAreRefused() {
        val file = Mp4Fixtures.file(items, sampleEntry = "drms")
        assertEquals(TagRefusal.MP4_DRM_PROTECTED, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun unknownOffsetBoxesAreRefused() {
        val file = Mp4Fixtures.file(items, extraStbl = Mp4Fixtures.leaf("saio", ByteArray(12)))
        assertEquals(TagRefusal.MP4_UNKNOWN_OFFSET_BOX, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun aChunkOffsetInsideMoovRefusesTheRewrite() {
        val ftyp = Mp4Fixtures.ftyp()
        val moov = Mp4Fixtures.moov(listOf(ftyp.size + 20, ftyp.size + 30), items)
        val file = ftyp + moov + Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        val plan = codec.plan(ByteArraySource(file), TagEdits(lyrics = "x".repeat(1000)))
        assertEquals(TagRefusal.MP4_OFFSET_INSIDE_REWRITE, (plan as WritePlan.Refused).reason)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `Mp4TagCodec` unresolved.

- [ ] **Step 3: Create `Mp4TagCodec.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * M4A: tags in `moov/udta/meta/ilst`, audio in `mdat`, chunk offsets (absolute file positions) in
 * `stco`/`co64`. Edits stay inside `moov` whenever free space allows; otherwise `moov` changes size
 * and exactly the chunk offsets that point past it move with the audio.
 */
internal object Mp4TagCodec : TagCodec {
    private const val MAX_MOOV = 64L shl 20
    private const val TEXT = 1
    private const val JPEG = 13
    private const val PNG = 14
    private const val BMP = 27
    private const val ARTISTS = "ARTISTS"
    private val MANAGED = setOf("©nam", "©ART", "©alb", "aART", "©gen", "gnre", "©day", "trkn", "disk", "©lyr", "covr")
    private val DRM_ENTRIES = setOf("drms", "drmi", "enca", "encv", "encs", "enct")
    private val OFFSET_BOXES = setOf("saio", "iloc")
    private val FREE = setOf("free", "skip")

    /** `hdlr` payload iTunes writes for a metadata `meta` box. */
    private val MDIR_HANDLER = ByteArray(8) + "mdirappl".encodeToByteArray() + ByteArray(9)

    private class Layout(val atoms: List<Mp4Atom>, val moovAtom: Mp4Atom, val moovBytes: ByteArray, val moov: Mp4Box)

    private sealed interface Parsed {
        class Ok(val layout: Layout) : Parsed
        class Bad(val reason: TagRefusal) : Parsed
    }

    private class Tags(val meta: Mp4Box, val ilst: Mp4Box)

    override fun recognizes(head: ByteArray): Boolean = head.size >= 8 && Mp4Boxes.typeOf(head, 4) == "ftyp"

    private fun parse(source: RandomAccessSource): Parsed {
        val atoms = Mp4Boxes.topLevel(source) ?: return Parsed.Bad(TagRefusal.MP4_MALFORMED_ATOMS)
        if (atoms.any { it.type == "moof" || it.type == "mfra" || it.type == "sidx" }) {
            return Parsed.Bad(TagRefusal.MP4_FRAGMENTED)
        }
        val moovs = atoms.filter { it.type == "moov" }
        if (moovs.size != 1) return Parsed.Bad(TagRefusal.MP4_MALFORMED_ATOMS)
        val atom = moovs[0]
        if (atom.size > MAX_MOOV) return Parsed.Bad(TagRefusal.MP4_TAGS_TOO_LARGE)
        val bytes = source.read(atom.offset, atom.size.toInt()) ?: return Parsed.Bad(TagRefusal.TRUNCATED)
        val tree = Mp4Boxes.parse(bytes) ?: return Parsed.Bad(TagRefusal.MP4_MALFORMED_ATOMS)
        // Only a tree that reproduces its own bytes exactly is safe to edit and write back.
        if (!tree.serialize().contentEquals(bytes)) return Parsed.Bad(TagRefusal.MP4_MALFORMED_ATOMS)
        val boxes = Mp4Boxes.walk(tree).toList()
        if (boxes.any { it.type == "stsd" && sampleEntryTypes(it).any { type -> type in DRM_ENTRIES } }) {
            return Parsed.Bad(TagRefusal.MP4_DRM_PROTECTED)
        }
        if (boxes.any { it.type in OFFSET_BOXES }) return Parsed.Bad(TagRefusal.MP4_UNKNOWN_OFFSET_BOX)
        return Parsed.Ok(Layout(atoms, atom, bytes, tree))
    }

    private fun sampleEntryTypes(stsd: Mp4Box): List<String> {
        val p = stsd.payload
        if (p.size < 8) return emptyList()
        val count = Mp4Boxes.be32(p, 4)
        val types = ArrayList<String>()
        var offset = 8
        for (i in 0 until minOf(count, 64L).toInt()) {
            if (offset + 8 > p.size) break
            types += Mp4Boxes.typeOf(p, offset + 4)
            val size = Mp4Boxes.be32(p, offset)
            if (size < 8) break
            offset += size.toInt()
        }
        return types
    }

    private fun findTags(moov: Mp4Box): Tags? {
        val meta = moov.child("udta")?.child("meta") ?: moov.child("meta") ?: return null
        val ilst = meta.child("ilst") ?: return null
        return Tags(meta, ilst)
    }

    private fun createTags(moov: Mp4Box): Tags {
        val udta = moov.child("udta") ?: Mp4Box.container("udta", emptyList()).also { moov.children!!.add(it) }
        val meta = moov.child("meta") ?: udta.child("meta")
            ?: Mp4Box.container("meta", listOf(Mp4Box.leaf("hdlr", MDIR_HANDLER)), prefix = ByteArray(4))
                .also { udta.children!!.add(it) }
        val ilst = Mp4Box.container("ilst", emptyList()).also { meta.children!!.add(it) }
        return Tags(meta, ilst)
    }

    // ------------------------------------------------------------------ items

    private fun dataBoxes(item: Mp4Box): List<Mp4Box> = item.children.orEmpty().filter { it.type == "data" }

    private fun value(data: Mp4Box): ByteArray? = data.payload.takeIf { it.size >= 8 }?.copyOfRange(8, data.payload.size)

    private fun code(data: Mp4Box): Int = if (data.payload.size >= 4) Mp4Boxes.be24(data.payload, 1) else -1

    private fun text(items: List<Mp4Box>, type: String): String? =
        items.firstOrNull { it.type == type }?.let(::dataBoxes)?.firstOrNull { code(it) == TEXT }
            ?.let(::value)?.decodeToString()?.takeIf { it.isNotEmpty() }

    private fun pair(items: List<Mp4Box>, type: String): Pair<Int?, Int?> {
        val bytes = items.firstOrNull { it.type == type }?.let(::dataBoxes)?.firstOrNull()?.let(::value)
        if (bytes == null || bytes.size < 6) return null to null
        return Mp4Boxes.be16(bytes, 2).takeIf { it > 0 } to Mp4Boxes.be16(bytes, 4).takeIf { it > 0 }
    }

    private fun freeformName(item: Mp4Box): String? =
        item.child("name")?.payload?.takeIf { it.size >= 4 }?.let { it.decodeToString(4, it.size) }

    private fun isArtists(item: Mp4Box): Boolean = item.type == "----" && freeformName(item)?.equals(ARTISTS, ignoreCase = true) == true

    private fun coverInfo(data: Mp4Box): CoverInfo? {
        val bytes = value(data) ?: return null
        val mime = when (code(data)) {
            JPEG -> ImageProbe.JPEG
            PNG -> ImageProbe.PNG
            BMP -> "image/bmp"
            else -> ImageProbe.probe(bytes)?.mime ?: "application/octet-stream"
        }
        return CoverInfo.of(bytes, mime)
    }

    private fun dataBox(code: Int, value: ByteArray): Mp4Box =
        Mp4Box.leaf("data", byteArrayOf(0, (code ushr 16).toByte(), (code ushr 8).toByte(), code.toByte()) + ByteArray(4) + value)

    // ------------------------------------------------------------------ read

    override fun read(source: RandomAccessSource): TagSnapshot {
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return TagSnapshot(TagFormat.MP4, "MP4", refusal = parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val items = findTags(layout.moov)?.ilst?.children.orEmpty()
        val covers = items.firstOrNull { it.type == "covr" }?.let(::dataBoxes).orEmpty()
        val (trackNumber, trackTotal) = pair(items, "trkn")
        val (discNumber, discTotal) = pair(items, "disk")
        return TagSnapshot(
            format = TagFormat.MP4,
            version = "MP4",
            title = text(items, "©nam"),
            artist = text(items, "©ART"),
            album = text(items, "©alb"),
            albumArtist = text(items, "aART"),
            genre = text(items, "©gen") ?: numericGenre(items),
            year = text(items, "©day"),
            trackNumber = trackNumber,
            trackTotal = trackTotal,
            discNumber = discNumber,
            discTotal = discTotal,
            lyrics = text(items, "©lyr")?.trim()?.ifEmpty { null },
            cover = covers.firstOrNull()?.let(::coverInfo),
            otherPictures = maxOf(0, covers.size - 1),
            nextCover = covers.getOrNull(1)?.let(::coverInfo),
            pictures = covers.mapNotNull { value(it)?.let { bytes -> Crc32.of(bytes) } }.sorted(),
            artists = items.filter(::isArtists)
                .flatMap { dataBoxes(it) }
                .mapNotNull { value(it)?.decodeToString() }
                .flatMap { TagFacts.splitArtists(it) },
        )
    }

    /** iTunes' old `gnre`: an ID3v1 genre index plus one. */
    private fun numericGenre(items: List<Mp4Box>): String? {
        val bytes = items.firstOrNull { it.type == "gnre" }?.let(::dataBoxes)?.firstOrNull()?.let(::value)
        if (bytes == null || bytes.size < 2) return null
        val index = Mp4Boxes.be16(bytes, 0) - 1
        return GenreTags.split("($index)").firstOrNull()
    }

    // ------------------------------------------------------------------ plan

    override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan {
        val normalized = edits.normalized()
        if (normalized.isEmpty) return WritePlan.NoChange
        EditChecks.refusal(normalized)?.let { return WritePlan.Refused(it) }
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return WritePlan.Refused(parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val moov = layout.moov
        val existing = findTags(moov)
        val tags = existing ?: createTags(moov)
        val oldItems = tags.ilst.children!!.toList()
        val oldIlstSize = tags.ilst.size
        val newItems = applyEdits(oldItems, normalized)
        if (oldItems.size == newItems.size &&
            oldItems.indices.all { oldItems[it].serialize().contentEquals(newItems[it].serialize()) }
        ) {
            return WritePlan.NoChange
        }
        tags.ilst.children.clear()
        tags.ilst.children.addAll(newItems)

        val siblings = tags.meta.children!!
        val at = siblings.indexOf(tags.ilst)
        var freeEnd = at + 1
        while (freeEnd < siblings.size && siblings[freeEnd].type == "free") freeEnd++
        val innerFree = (at + 1 until freeEnd).sumOf { siblings[it].size }
        val atom = layout.moovAtom
        val length = source.length

        // A: the new ilst fits where the old one and its free space were.
        if (existing != null) {
            val left = oldIlstSize + innerFree - tags.ilst.size
            if (left == 0L || left >= 8) {
                replaceRange(siblings, at + 1, freeEnd, if (left > 0) listOf(freeBox(left)) else emptyList())
                return ByteDiff.patchOrNoChange(atom.offset, layout.moovBytes, moov.serialize(), length, length)
            }
        }

        // The tags are laid out anew: fold the old free space in and leave spare room after ilst.
        replaceRange(siblings, at + 1, freeEnd, listOf(freeBox(TagSpace.SPARE_BYTES.toLong())))
        val withSpare = moov.serialize()
        replaceRange(siblings, at + 1, at + 2, emptyList())
        val withoutSpare = moov.serialize()

        // B1 / B2: moov keeps its offset and grows into — or shrinks away from — the free space after it.
        val index = layout.atoms.indexOf(atom)
        var topFreeEnd = index + 1
        while (topFreeEnd < layout.atoms.size && layout.atoms[topFreeEnd].type in FREE) topFreeEnd++
        val available = layout.atoms[topFreeEnd - 1].end - atom.offset
        val region = source.read(atom.offset, available.toInt()) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
        for (candidate in listOf(withSpare, withoutSpare)) {
            val left = available - candidate.size
            if (left == 0L || left >= 8) {
                val filled = if (left > 0) candidate + freeBox(left).serialize() else candidate
                return ByteDiff.patchOrNoChange(atom.offset, region, filled, length, length)
            }
        }
        if (topFreeEnd == layout.atoms.size) {
            return ByteDiff.patchOrNoChange(atom.offset, region, withSpare, atom.offset + withSpare.size, length)
        }

        // C: moov changes size where it is; everything after it moves, and so must its chunk offsets.
        val shifted = Mp4Boxes.parse(withSpare) ?: return WritePlan.Refused(TagRefusal.MP4_MALFORMED_ATOMS)
        shiftOffsets(shifted, atom.offset, atom.end, withSpare.size - atom.size)?.let { return WritePlan.Refused(it) }
        return WritePlan.StreamingRewrite(
            listOf(
                OutputSegment.Copy(0, atom.offset),
                OutputSegment.Bytes(shifted.serialize()),
                OutputSegment.Copy(atom.end, length - atom.end),
            ),
        )
    }

    private fun freeBox(size: Long): Mp4Box = Mp4Box.leaf("free", ByteArray((size - 8).toInt()))

    private fun replaceRange(list: MutableList<Mp4Box>, from: Int, to: Int, with: List<Mp4Box>) {
        repeat(to - from) { list.removeAt(from) }
        list.addAll(from, with)
    }

    /** Moves every chunk offset at or past [end] by [delta]; null when all of them could be moved. */
    private fun shiftOffsets(moov: Mp4Box, start: Long, end: Long, delta: Long): TagRefusal? {
        for (box in Mp4Boxes.walk(moov)) {
            val wide = when (box.type) {
                "stco" -> false
                "co64" -> true
                else -> continue
            }
            val p = box.payload
            if (p.size < 8) return TagRefusal.MP4_MALFORMED_ATOMS
            val count = Mp4Boxes.be32(p, 4)
            val width = if (wide) 8 else 4
            if (8 + count * width != p.size.toLong()) return TagRefusal.MP4_MALFORMED_ATOMS
            for (i in 0 until count.toInt()) {
                val at = 8 + i * width
                val offset = if (wide) Mp4Boxes.be64(p, at) else Mp4Boxes.be32(p, at)
                if (offset in start until end) return TagRefusal.MP4_OFFSET_INSIDE_REWRITE
                if (offset >= end) {
                    val moved = offset + delta
                    if (!wide && moved > 0xFFFFFFFFL) return TagRefusal.MP4_OFFSET_OVERFLOW
                    if (wide) Mp4Boxes.putBe64(p, at, moved) else Mp4Boxes.putBe32(p, at, moved)
                }
            }
        }
        return null
    }

    private fun applyEdits(items: List<Mp4Box>, edits: TagEdits): List<Mp4Box> {
        val out = items.toMutableList()
        setText(out, "©nam", edits.title)
        setText(out, "©ART", edits.artist)
        if (edits.artist != null) setArtists(out, edits.artist)
        setText(out, "©alb", edits.album)
        setText(out, "aART", edits.albumArtist)
        if (edits.genre != null) {
            setText(out, "©gen", edits.genre)
            out.removeAll { it.type == "gnre" }
        }
        setText(out, "©day", edits.year)
        setPair(out, "trkn", edits.trackNumber, edits.trackTotal, defaultLength = 8)
        setPair(out, "disk", edits.discNumber, edits.discTotal, defaultLength = 6)
        setText(out, "©lyr", edits.lyrics)
        setCover(out, edits.cover)
        return out
    }

    /** Null keeps; "" removes every item of [type]; a value replaces the first and drops duplicates. */
    private fun setText(items: MutableList<Mp4Box>, type: String, value: String?) {
        if (value == null) return
        // An item that already says exactly this is kept byte for byte.
        if (value.isNotEmpty() && items.count { it.type == type } == 1 && text(items, type) == value) return
        replaceItem(items, type, if (value.isEmpty()) null else Mp4Box.container(type, listOf(dataBox(TEXT, value.encodeToByteArray()))))
    }

    private fun replaceItem(items: MutableList<Mp4Box>, type: String, replacement: Mp4Box?) {
        val first = items.indexOfFirst { it.type == type }
        if (first < 0) {
            replacement?.let { items += it }
            return
        }
        if (replacement != null) items[first] = replacement
        val keep = if (replacement != null) first else -1
        val iterator = items.listIterator()
        var index = 0
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.type == type && index != keep) iterator.remove()
            index++
        }
    }

    private fun setPair(items: MutableList<Mp4Box>, type: String, number: String?, total: String?, defaultLength: Int) {
        if (number == null && total == null) return
        val (currentNumber, currentTotal) = pair(items, type)
        val n = if (number == null) currentNumber else TagNumbers.strict(number)
        val t = if (total == null) currentTotal else TagNumbers.strict(total)
        if (n == currentNumber && t == currentTotal && items.count { it.type == type } == 1) return
        if (n == null && t == null) {
            replaceItem(items, type, null)
            return
        }
        val existingLength = items.firstOrNull { it.type == type }?.let(::dataBoxes)?.firstOrNull()?.let(::value)?.size
        val value = ByteArray(maxOf(existingLength ?: defaultLength, 6))
        value[2] = ((n ?: 0) ushr 8).toByte()
        value[3] = (n ?: 0).toByte()
        value[4] = ((t ?: 0) ushr 8).toByte()
        value[5] = (t ?: 0).toByte()
        replaceItem(items, type, Mp4Box.container(type, listOf(dataBox(0, value))))
    }

    /** Rewrites the ARTISTS freeform item from a new display credit — only when the file has one. */
    private fun setArtists(items: MutableList<Mp4Box>, artist: String) {
        val first = items.indexOfFirst(::isArtists)
        if (first < 0) return
        val names = CreditedArtists.fromDisplay(artist)
        val original = items[first]
        val rebuilt = if (names.isEmpty()) {
            null
        } else {
            Mp4Box.container(
                "----",
                original.children.orEmpty().filter { it.type != "data" } + names.map { dataBox(TEXT, it.encodeToByteArray()) },
            )
        }
        var index = 0
        val iterator = items.listIterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (isArtists(item)) {
                if (index == first && rebuilt != null) iterator.set(rebuilt) else iterator.remove()
            }
            index++
        }
    }

    private fun setCover(items: MutableList<Mp4Box>, edit: CoverEdit) {
        if (edit == CoverEdit.Keep) return
        val covrIndex = items.indexOfFirst { it.type == "covr" }
        val covr = items.getOrNull(covrIndex)
        when (edit) {
            CoverEdit.Keep -> Unit
            CoverEdit.Remove -> {
                if (covr == null) return
                val children = covr.children!!
                val first = children.indexOfFirst { it.type == "data" }
                if (first >= 0) children.removeAt(first)
                if (children.none { it.type == "data" }) items.removeAt(covrIndex)
            }
            is CoverEdit.Replace -> {
                val data = dataBox(if (edit.mime == ImageProbe.PNG) PNG else JPEG, edit.bytes)
                if (covr == null) {
                    items += Mp4Box.container("covr", listOf(data))
                } else {
                    val children = covr.children!!
                    val first = children.indexOfFirst { it.type == "data" }
                    if (first >= 0) children[first] = data else children.add(0, data)
                }
            }
        }
    }

    // ------------------------------------------------------------------ verification

    override fun audioDigest(source: RandomAccessSource): Long? {
        val atoms = Mp4Boxes.topLevel(source) ?: return null
        val crc = Crc32()
        for (atom in atoms.filter { it.type == "mdat" }) {
            var position = atom.offset + atom.headerSize
            while (position < atom.end) {
                val count = minOf(WritePlans.COPY_CHUNK.toLong(), atom.end - position).toInt()
                crc.update(source.read(position, count) ?: return null)
                position += count
            }
        }
        return crc.value
    }

    override fun inventory(source: RandomAccessSource): List<String> {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return emptyList()
        val out = ArrayList<String>()
        for (atom in layout.atoms) {
            if (atom.type == "moov" || atom.type == "mdat" || atom.type in FREE) continue
            val bytes = if (atom.size <= (1 shl 20)) source.read(atom.offset, atom.size.toInt()) else null
            out += "top:${atom.type}:${atom.size}:${bytes?.let { Crc32.of(it) } ?: "-"}"
        }
        fun visit(box: Mp4Box, path: String) {
            when {
                box.type == "udta" || box.type == "meta" || box.type in FREE -> Unit
                box.children == null -> out += if (box.type == "stco" || box.type == "co64") {
                    "$path/${box.type}:count=${Mp4Boxes.be32(box.payload, 4)}"
                } else {
                    "$path/${box.type}:${Crc32.of(box.payload)}"
                }
                else -> box.children.forEach { visit(it, "$path/${box.type}") }
            }
        }
        layout.moov.children!!.forEach { visit(it, "moov") }
        layout.moov.child("udta")?.children
            ?.filter { it.type != "meta" && it.type !in FREE }
            ?.forEach { out += "udta/${it.type}:${Crc32.of(it.serialize())}" }
        val tags = findTags(layout.moov)
        tags?.meta?.children
            ?.filter { it.type != "ilst" && it.type != "hdlr" && it.type !in FREE }
            ?.forEach { out += "meta/${it.type}:${Crc32.of(it.serialize())}" }
        // Pictures are pinned by TagSnapshot.pictures, which also knows which one an edit changes.
        tags?.ilst?.children
            ?.filterNot { it.type in MANAGED || isArtists(it) }
            ?.forEach { out += "item:${Crc32.of(it.serialize())}" }
        return out
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Mp4TagCodec.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/Mp4TagCodecTest.kt
git commit -m "feat(tags): M4A tags edited inside moov, and only the chunk offsets past it move when moov must grow"
```

---
### Task 12: The codec registry

**Files:**
- Modify: `.../library/tags/TagCodec.kt` (append `TagCodecs`)
- Test: `.../library/tags/TagCodecsTest.kt` (commonTest)

**Interfaces:**
- Consumes: all four codecs and their fixtures (Tasks 6, 7, 9, 11).
- Produces: `public object TagCodecs { fun forSource(source: RandomAccessSource): TagCodec?; fun read(source: RandomAccessSource): TagSnapshot?; fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan }` — the only entry point plans 2–3 use.

- [ ] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.Id3TestTags.latin1Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.mp3Payload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

internal class TagCodecsTest {

    private val files = mapOf(
        TagFormat.MP3 to Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t")))) + mp3Payload(),
        TagFormat.FLAC to FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "t")),
        TagFormat.OPUS to OggFixtures.opus("TITLE" to "t"),
        TagFormat.VORBIS to OggFixtures.vorbis("TITLE" to "t"),
        TagFormat.MP4 to Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "t"))),
    )

    @Test
    fun eachFormatIsReadByItsOwnCodec() {
        for ((format, file) in files) {
            val snapshot = TagCodecs.read(ByteArraySource(file))
            assertEquals(format, snapshot?.format)
            assertEquals("t", snapshot?.title)
        }
    }

    @Test
    fun anEmptyEditIsNoChangeEverywhere() {
        for (file in files.values) assertIs<WritePlan.NoChange>(TagCodecs.plan(ByteArraySource(file), TagEdits()))
    }

    @Test
    fun anEditIsWrittenCorrectlyEverywhere() {
        for (file in files.values) {
            val codec = TagCodecs.forSource(ByteArraySource(file))!!
            CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "Новое", trackNumber = "2"))
        }
    }

    @Test
    fun unknownContainersAreRefused() {
        val wav = "RIFF".encodeToByteArray() + ByteArray(4) + "WAVEfmt ".encodeToByteArray() + ByteArray(100)
        assertNull(TagCodecs.read(ByteArraySource(wav)))
        assertEquals(TagRefusal.UNSUPPORTED_FORMAT, (TagCodecs.plan(ByteArraySource(wav), TagEdits(title = "x")) as WritePlan.Refused).reason)
        assertNull(TagCodecs.read(ByteArraySource(ByteArray(0))))
    }

    @Test
    fun id3InFrontOfFlacIsRefusedNotMisread() {
        val file = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t")))) + files.getValue(TagFormat.FLAC)
        assertEquals(TagRefusal.ID3_BEFORE_OTHER_CONTAINER, TagCodecs.read(ByteArraySource(file))?.refusal)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q`
Expected: compilation FAILS — `TagCodecs` unresolved.

- [ ] **Step 3: Append `TagCodecs` to `TagCodec.kt`**

```kotlin
/** Picks a file's codec from its first bytes. The single entry point for everything outside `tags`. */
public object TagCodecs {
    // ID3 last: an ID3v2 tag in front of FLAC, Ogg or MP4 must reach the ID3 codec, which refuses it.
    private val ALL: List<TagCodec> = listOf(FlacTagCodec, OggTagCodec, Mp4TagCodec, Id3TagCodec)

    public fun forSource(source: RandomAccessSource): TagCodec? {
        if (source.length <= 0) return null
        val head = source.read(0, minOf(source.length, TagCodec.HEAD_BYTES.toLong()).toInt()) ?: return null
        return ALL.firstOrNull { it.recognizes(head) }
    }

    public fun read(source: RandomAccessSource): TagSnapshot? = forSource(source)?.read(source)

    public fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan =
        forSource(source)?.plan(source, edits) ?: WritePlan.Refused(TagRefusal.UNSUPPORTED_FORMAT)
}
```

- [ ] **Step 4: Run the whole module's tests, and the iOS test compile**

Run: `./gradlew :core:library:testAndroidHostTest :core:library:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: PASS, and the iOS test compilation succeeds (no test name uses characters Kotlin/Native rejects).

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagCodec.kt \
        core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/TagCodecsTest.kt
git commit -m "feat(tags): one entry point that picks the right codec from a file's first bytes"
```

---

### Task 13: The real-library corpus run

This task is the release gate for plan 1 (spec §8.2): every file of the user's library, copied off the read-only emulator, through every edit scenario, checked by our own reader and by independent tools.

**Files:**
- Create: `core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/tags/TagCodecRealFileTest.kt`
- Outside the repository (never committed): `~/Documents/LJ/tag-corpus/` — `files/` (copies), `out/` (edited copies), `verify.py`, `.venv/`.

**Interfaces:**
- Consumes: `TagCodecs`, `WritePlans`, `TestImages` (commonTest is visible to androidHostTest), `CoverEdit`, `TagSnapshot`.
- Produces: a report on stdout, and edited copies in `TAG_REAL_FILES_OUT` for the external check.

- [ ] **Step 1: Write the corpus test**

```kotlin
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
```

- [ ] **Step 2: Run it without the corpus and confirm it skips**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*TagCodecRealFileTest*'`
Expected: PASS; the test's stdout (in `core/library/build/test-results/testAndroidHostTest/TEST-io.github.nikitasud.latentjam.library.tags.TagCodecRealFileTest.xml`) contains `SKIP: set TAG_REAL_FILES`.

- [ ] **Step 3: Commit the test**

```bash
git add core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/tags/TagCodecRealFileTest.kt
git commit -m "test(tags): every codec against a real library, when one is pointed at"
```

- [ ] **Step 4: Copy the library off the read-only emulator**

The emulator that holds a copy of the user's library is the AVD `Pixel_7_Pro`. **Never** use the `LJ-demo` emulator, and pin the serial — the user's phone may be plugged in.

```bash
adb devices
for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do echo "$s $(adb -s $s emu avd name 2>/dev/null | head -1)"; done
```

With `SERIAL` set to the serial printed next to `Pixel_7_Pro`:

```bash
mkdir -p ~/Documents/LJ/tag-corpus/files
adb -s "$SERIAL" shell 'content query --uri content://media/external/audio/media --projection _data --where "is_music!=0"' \
  | sed -n 's/.*_data=//p' > ~/Documents/LJ/tag-corpus/paths.txt
wc -l ~/Documents/LJ/tag-corpus/paths.txt
while IFS= read -r path; do adb -s "$SERIAL" pull "$path" ~/Documents/LJ/tag-corpus/files/ >/dev/null; done < ~/Documents/LJ/tag-corpus/paths.txt
ls ~/Documents/LJ/tag-corpus/files | sed 's/.*\.//' | sort | uniq -c
```

Expected: roughly 550 mp3, 189 opus, 71 flac, 43 m4a (the counts measured on 2026-09-28). `adb pull` only reads; nothing on the emulator changes.

- [ ] **Step 5: Run the corpus**

```bash
TAG_REAL_FILES=~/Documents/LJ/tag-corpus/files TAG_REAL_FILES_OUT=~/Documents/LJ/tag-corpus/out \
  ./gradlew :core:library:testAndroidHostTest --offline --rerun --tests '*TagCodecRealFileTest*'
```

Read the report from the test's XML (`system-out`). Expected: no failures. The paths table shows mostly `InPlacePatch` for `second`, and every format present. Every file listed under "second edit not in place" must be an Opus file whose header carries binary data to preserve (RFC 7845 §5.2); any other file there is a gap to fix like a failure.

If there are failures: stop. For each distinct failure, reproduce the file's structure as a synthetic unit test in the codec's own test class (it must fail first), fix the codec, run the module tests, then re-run the corpus. Commit each fix separately (`fix(tags): …`).

- [ ] **Step 6: Review every refusal by hand**

For each `REFUSED <reason>` group in the report, open two or three of the named files and decide:
- a **correct** refusal (the structure really cannot be rewritten without risk — e.g. an unsynchronised ID3 tag, a DRM file): note it in the task's final report;
- a **gap** (a common, well-formed layout the codec should handle): treat it like a failure in Step 5 — failing unit test, fix, re-run.

`ID3_BEFORE_OTHER_CONTAINER` gets a count in the report: spec §4.2 says the default refusal is revisited only if this count matters.

- [ ] **Step 7: Independent verification with ffmpeg and mutagen**

Tools live outside the repository:

```bash
which ffmpeg flac || brew install ffmpeg flac
python3 -m venv ~/Documents/LJ/tag-corpus/.venv
~/Documents/LJ/tag-corpus/.venv/bin/pip install --quiet mutagen
```

Write `~/Documents/LJ/tag-corpus/verify.py`:

```python
#!/usr/bin/env python3
"""Checks each out/<n>.<ext> against out/<n>.orig.<ext>: identical decoded audio, valid container, tags as edited."""
import pathlib, subprocess, sys
import mutagen

OUT = pathlib.Path(sys.argv[1])
EXPECTED = {
    "title": "Проверка テスト",
    "albumartist": "Various Artists",
    "tracknumber": "7",
}

def pcm_md5(path):
    result = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-map", "0:a:0", "-f", "md5", "-"],
        capture_output=True, text=True)
    if result.returncode != 0 or result.stderr.strip():
        return None, result.stderr.strip()
    return result.stdout.strip(), ""

def tags(path):
    f = mutagen.File(path, easy=True)
    if f is None:
        return {}
    out = {}
    for key in EXPECTED:
        value = f.get(key)
        if value:
            out[key] = str(value[0]).split("/")[0]
    return out

failures = 0
edited = sorted(p for p in OUT.iterdir() if ".orig." not in p.name)
for path in edited:
    orig = path.with_name(path.stem + ".orig" + path.suffix)
    a, a_err = pcm_md5(orig)
    b, b_err = pcm_md5(path)
    problems = []
    if b is None:
        problems.append(f"ffmpeg error: {b_err}")
    elif a != b:
        problems.append(f"PCM differs {a} vs {b}")
    if path.suffix == ".flac":
        if subprocess.run(["flac", "-t", "-s", str(path)], capture_output=True).returncode != 0:
            problems.append("flac -t failed")
    got = tags(path)
    for key, want in EXPECTED.items():
        if got.get(key) != want:
            problems.append(f"{key}: mutagen sees {got.get(key)!r}, expected {want!r}")
    if problems:
        failures += 1
        print(f"FAIL {path.name}: " + "; ".join(problems))
print(f"checked {len(edited)} files, {failures} failed")
sys.exit(1 if failures else 0)
```

Run:

```bash
~/Documents/LJ/tag-corpus/.venv/bin/python ~/Documents/LJ/tag-corpus/verify.py ~/Documents/LJ/tag-corpus/out
```

Expected: `checked N files, 0 failed`. A PCM difference or an `ffmpeg error` on an edited file whose original decodes cleanly is a release blocker: reproduce, fix, re-run Steps 5–7. A `mutagen` mismatch is investigated the same way (our reader and mutagen must agree on what was written).

- [ ] **Step 8: Final module check and report**

```bash
./gradlew :core:library:testAndroidHostTest :core:library:compileTestKotlinIosSimulatorArm64 --offline -q
./gradlew testAndroidHostTest :composeApp:compileKotlinIosSimulatorArm64 --offline -q
```

Expected: both succeed — the codecs change nothing the app compiles against yet (`TagEdits` gained fields with defaults; `Id3Tags` keeps its public API).

Report to the user: corpus counts per format and plan path, every refusal group with its verdict, the external check's result, and anything fixed along the way. Nothing is pushed.
