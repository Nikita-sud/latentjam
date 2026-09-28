# Tag editing, brought up to shape — design

Date: 2026-09-28 · Status: draft for review · Target release: 0.7.0 · Origin: issue #4

## 1. Goal

Editing a song's tags inside LatentJam should make a second app (Flaq R and the like) unnecessary.

Today the app edits five fields (title, artist, album, genre, year) of **one MP3 at a time**, on
**Android 11+ only**. This design extends that to:

- every format the library actually holds: **MP3, FLAC, Opus/Vorbis (Ogg), M4A**;
- six more fields: **album artist, track number/total, disc number/total, lyrics, front cover**;
- **editing many tracks at once**: a whole album, a whole artist, or any selection;
- **iOS** (files imported into the app) and **Android 7–10**.

The user's own library, as measured on its emulator copy: 550 MP3, 189 Opus, 71 FLAC, 43 M4A.

### Priorities, in order

1. **Never damage a file.** A refused edit is a correct outcome; a corrupted file is not. Audio
   stays bit-identical through every edit.
2. **Fast and cheap on the phone.** Touch as few bytes as possible. The common case writes a few
   kilobytes at the head of the file and never reads or writes the audio.
3. Implementation effort is not a constraint. Everything is written from scratch where needed.

The release ships only after the testing in §8 has passed in full.

### Out of scope

- Composer and comment fields: the app shows neither, so users would be editing what they cannot see.
- The Apple Music library on iOS: Apple lets no app write those files.
- Writing tags that MediaStore derives (the approach tried once and removed; see `TrackTagWriter.kt`).
- Third-party tagging libraries: copyleft licences, native builds that complicate F-Droid, and
  no control over the refuse-rather-than-damage rule.

## 2. Architecture

```
UI: single-track editor · N-track editor
        │  TagEdits (per field: keep / set / remove)
        ▼
TagWriteSession (commonMain)          for each file:
   1. read the head (or the needed parts) of the file
   2. TagCodecs.forContent(magic bytes) → Id3 | Flac | Ogg | Mp4 | Unsupported
   3. codec.read(...)  → TagSnapshot
      codec.plan(..., edits) → InPlacePatch | StreamingRewrite | Refused(reason)
        ▼
Platform (Android / iOS): consent → journal → write → verify → rescan
```

- **Codecs live in `core/library` (`library.tags`)**, pure Kotlin over byte arrays and a small
  random-access source interface. No IO, no platform types: identical on Android and iOS and
  testable on the host JVM against real files. This is how the existing ID3 writer is built; it is
  generalized, not replaced.
- **Platforms know nothing about formats.** They execute a plan: permissions, file IO, the
  journal, verification and the media rescan.
- One interface for all four formats:

```kotlin
interface TagCodec {
    fun read(source: RandomAccessSource): TagSnapshot?          // null = not this format / unreadable
    fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan
}

sealed interface WritePlan {
    /** Byte ranges to overwrite and, optionally, a new file length. Audio is never read or written. */
    data class InPlacePatch(val writes: List<ByteWrite>, val newLength: Long?) : WritePlan
    /** New head, then the rest of the file streamed through [transform] (page renumbering, offset fixes). */
    data class StreamingRewrite(val head: ByteArray, val audioStart: Long, val transform: StreamTransform) : WritePlan
    data class Refused(val reason: TagRefusal) : WritePlan
}
```

The exact signatures are settled in the implementation plan; the shape above is the contract.

## 3. Tag model

### 3.1 `TagEdits` (extended)

The existing rule stays: `null` = leave the field alone, `""` = remove it.

| Field | Type |
|---|---|
| title, artist, album, albumArtist, genre, year, lyrics | `String?` |
| trackNumber, trackTotal, discNumber, discTotal | `String?` (digits; each half editable alone) |
| cover | `CoverEdit` = `Keep` · `Remove` · `Replace(bytes, mime)` |

### 3.2 `TagSnapshot`

Everything the editor and the verifier need, read from the file itself (not from MediaStore): all
the fields above, cover presence/size/mime, container and tag version, and `editable` plus a
`TagRefusal` reason when it is not. The editor is filled from it, and verification compares
"before — expected — after" through it.

### 3.3 Field mapping

| Field | MP3 (ID3v2.3/2.4) | FLAC / Opus / Vorbis | M4A |
|---|---|---|---|
| Title / Artist / Album | TIT2 / TPE1 / TALB | TITLE / ARTIST / ALBUM | ©nam / ©ART / ©alb |
| Album artist | TPE2 | ALBUMARTIST | aART |
| Genre | TCON | GENRE | ©gen (numeric `gnre` removed) |
| Year | TDRC (2.4) / TYER (2.3) | DATE | ©day |
| Track / total | TRCK `3/12` | TRACKNUMBER + TRACKTOTAL | `trkn` binary pair |
| Disc / total | TPOS `1/2` | DISCNUMBER + DISCTOTAL | `disk` binary pair |
| Lyrics | USLT | LYRICS | ©lyr |
| Front cover | APIC type 3 | FLAC: PICTURE block type 3 · Ogg: METADATA_BLOCK_PICTURE | `covr` |

### 3.4 Rules

- **Preservation.** Everything the editor does not manage is kept byte for byte, in its original
  order: ReplayGain, comments, MusicBrainz IDs, other pictures (back cover, booklet), the Vorbis
  vendor string, iTunes `----` atoms, unknown ID3 frames.
- **Tag version is kept**: ID3v2.3 stays 2.3. An MP3 with no ID3v2 tag gets a new **ID3v2.3** tag,
  the version the widest range of software reads.
- **Aliases are read, one canonical name is written.** TOTALTRACKS, TOTALDISCS, UNSYNCEDLYRICS
  and similar are accepted on read. On write, the canonical field is set and duplicates of the same
  field are removed, so a file never carries two disagreeing values.
- **Credited artists.** When the artist is edited and the file carries an `ARTISTS` list (Vorbis
  `ARTISTS`, ID3 `TXXX:ARTISTS`, which the app groups by), that list is rewritten from the new value:
  split on `;` when it names several artists, otherwise removed. Otherwise renaming an artist would
  appear not to work, because grouping would keep following the old list.
- **Year.** An untouched year keeps its full stored value (`2001-05-03` stays as it is).
- **Lyrics.** Written as unsynchronised text, exactly as entered (LRC timestamps included). The ID3
  USLT language keeps the existing frame's code, or `XXX` when new.
- **Cover.** A picked image larger than 1000×1000 is downscaled to fit and encoded as JPEG (quality
  90). A PNG already within 1000×1000 and under 500 KB is kept as PNG. Only the front cover is
  replaced or removed. An album edit writes the same bytes to every track.

### 3.5 Effects on the rest of the app

- **Album artist is read and used**: MediaStore `ALBUM_ARTIST` on Android, `TPE2`/`ALBUMARTIST`/`aART`
  on iOS. The album subtitle shows it, and album grouping uses it as the album's identity, so
  compilations with per-track artists stop splitting into several albums.
- Track and disc numbers are already read and sorted on since #2.

## 4. Codecs

Every codec rebuilds only the structure it owns and plans the smallest write that achieves the
edit. **Spare space is 16 KiB** wherever a codec creates it (about 0.3 % of a typical file), so
that after one rewrite, later edits of text, and usually a similar-size cover, take the fast path.

### 4.1 MP3 / ID3v2

- Existing writer extended with TPE2, TRCK, TPOS, USLT, APIC.
- New tag fits the old footprint → **in place**, the remainder becomes padding.
- Otherwise → **streaming rewrite** with 16 KiB padding (the existing `PADDING = 1024` becomes 16 KiB).
- Refusals as today: ID3v2.2, tag-level unsynchronisation, compressed or encrypted frames, unknown
  flags, frames that do not add up.
- ID3v1 trailers are removed on edit (existing behaviour); an APE tag at the end is left untouched.

### 4.2 FLAC

- Layout: `fLaC`, STREAMINFO (always first), metadata blocks, audio frames.
- The whole metadata region is rebuilt to **exactly its current size**: new VORBIS_COMMENT, new
  front PICTURE, every other block byte for byte in its original order, and the remainder handed to
  a PADDING block. Audio does not move. The last-block flag is set on the final block.
- Does not fit (remainder negative, or 1–3 bytes, too small for a block header) → **streaming
  rewrite** with a 16 KiB PADDING block.
- Refusals: STREAMINFO not first, block lengths past the end of the file, a block over 16 MiB, file
  ends inside the metadata.
- An ID3v2 tag before `fLaC`: counted on the real library during implementation. Refused by default,
  so a file never ends up with two disagreeing tags; revisited only if the count says it matters.

### 4.3 Opus / Vorbis (Ogg)

- Layout: identification page, the comment packet (Vorbis: plus the setup packet), then audio, which
  by specification starts on a fresh page.
- The comment packet is rebuilt and laced into **the same number of pages with the same total byte
  length**. The difference is absorbed by the trailing space both formats allow after the comment
  list: Opus padding after the user comments, Vorbis bytes after the framing bit (ignored by
  decoders). The Vorbis setup packet is copied byte for byte. CRCs of the rewritten pages are
  recomputed. Page sequence numbers and all audio pages are untouched.
- The page count must change (for example a new cover of hundreds of KB in base64) → **streaming
  rewrite**: every following page gets its sequence number shifted and its CRC recomputed. One pass,
  no re-encoding.
- Refusals: several logical streams or chained streams, bad CRCs in the header pages, an unknown codec.

### 4.4 M4A / MP4

- Tags live in `moov/udta/meta/ilst`; audio in `mdat`; `stco`/`co64` hold **absolute** audio offsets.
- New `ilst` fits in the old one plus adjacent `free` atoms → **in place**, parent sizes (`meta`,
  `udta`, `moov`) updated.
- `moov` stored **after** `mdat` → `moov` rewritten whole at the end of the file and the length
  adjusted. Audio offsets do not change, so this is still a fast path.
- `moov` **before** `mdat` and no room → **streaming rewrite**: audio shifted by a known delta, every
  `stco`/`co64` entry in every track corrected by that delta, a 16 KiB `free` placed after `ilst`.
- No tags at all → `udta/meta(hdlr mdir)/ilst` created.
- Both `meta` variants are handled: ISO (4 bytes of version/flags) and QuickTime (none).
- Freeform iTunes atoms (`----`) are preserved.
- Refusals: fragmented MP4 (`moof`), DRM-protected `.m4p`, `stco` overflow past 4 GiB, an atom tree
  whose sizes do not add up.

## 5. Writing

### 5.1 Paths

- **In-place patch** (the common case): write the listed byte ranges, set the new length if any. The
  audio is never opened for writing.
- **Streaming rewrite** (only when spare space runs out): the new file is built in the app's cache
  in 1 MiB blocks (`FileChannel.transferTo` on Android), the file is never held in memory, and a
  checksum of the audio is computed on the fly. The original is replaced only by a verified copy.

### 5.2 Journal

An in-place patch is not atomic. Before patching, the original bytes of the affected ranges
(kilobytes) and the original length are written to an app-private journal and fsynced. The journal
is cleared after verification. At launch, an unfinished journal entry is rolled back. Power loss
mid-write therefore cannot leave a damaged header.

### 5.3 Verification (every file, every time)

- **In place**: read back exactly the written ranges and compare byte for byte with the plan, then
  re-read the tags with the codec and compare every field with the expected snapshot. The audio was
  never opened for writing, so it is not re-read.
- **Streaming rewrite**: the staged copy's tags are re-read and compared the same way, and its audio
  checksum must equal the checksum taken while copying from the original (defined per format:
  MP3 frames, FLAC frames, Ogg packet data and granule positions, MP4 `mdat` payload). Only then is
  the original replaced.
- Any mismatch: the original is restored (from the journal or left untouched), and the file is
  reported as not changed.

### 5.4 Platforms

- **Android 11+**: one `MediaStore.createWriteRequest` for all files of an edit.
- **Android 10**: `RecoverableSecurityException` consent per file, the same mechanism as deletion.
  A 12-track album means 12 dialogs; accepted.
- **Android 7–9**: `WRITE_EXTERNAL_STORAGE` (already declared with `maxSdkVersion=28`), requested
  once, the same mechanism as deletion.
- **iOS**: files imported into the app's Documents are written with `FileManager`. A streaming
  rewrite can be atomic here (write a sibling file, then replace). Tracks from the Apple Music
  library are shown as not editable, with the reason.
- **Batch**: files are written with a concurrency of 2–4. **One** media rescan for the whole list
  at the end (`MediaScannerConnection.scanFile` with all paths on Android).

## 6. Interface

### 6.1 Single-track editor

The existing edit mode of the track sheet, extended:

- Fields in groups: title, artist, album, album artist, genre, year; track and total, disc and total
  (pairs on one row); lyrics (multi-line, expandable); cover (thumbnail with Replace and Remove).
- Filled from `TagSnapshot`, so it shows what is actually in the file, lyrics and album artist included.
- Editability is known **before** typing: when a file will be refused (unsupported format, DRM,
  broken structure), the editor shows the reason instead of the fields.

### 6.2 N-track editor

A new sheet with every field except the per-track ones (title, track number, lyrics): artist, album,
album artist, genre, year, track total, disc number and total, cover.

- A field whose value differs across the tracks is empty with the hint "Different values" and is
  left alone unless something is typed into it. Shared values are shown as usual.
- The save button states the scope: "Change 3 fields in 12 files".
- Entry points: album screen → menu → "Edit album"; artist screen → menu → "Edit artist" (artist
  field first); selection mode → action bar → "Edit tags". Genre and folder screens are covered by
  selection.

### 6.3 Saving a batch

- Progress in the sheet: "Saved 5 of 12".
- "Stop" stops between files. Files already written stay changed, the rest stay untouched, and the
  sheet says so.
- The result is exact: "Saved 11 of 12. 1 file not changed: <name> — <reason>".

### 6.4 Cover picker

The system photo picker (Android Photo Picker, no permission; PHPicker on iOS), reusing
`rememberPlaylistCoverPicker`, plus the downscaling and encoding in §3.4.

### 6.5 After saving

- **Library**: one rescan, then a library reload, so album order, album-artist grouping and sorting
  reflect the new tags at once.
- **Artwork caches**: the Android album-art URI does not change after an edit, so the image loader
  would keep the old picture. The file's revision (`sourceRevision`: size, mtime, MediaStore
  generation) becomes part of the artwork cache key. iOS caches artwork by content, so it gets a new
  URI by itself.
- **Now playing**: when the edited track is playing, its title, artist and artwork update in the
  player, the notification and the lock screen without interrupting playback.
- **SMART**: its text index depends on artist, genre and year. The changed revision makes the
  existing invalidation re-index exactly the edited files.

### 6.6 Strings

All new strings translated into the app's 17 locales, respecting the known locale traps
(literal `%`, `values-in` = `values-id`, sort keys never reused as titles).

## 7. Performance targets

- In-place edit: tens of milliseconds per file, excluding the system dialog.
- A 12-track album edit: well under a second of writing.
- Opening the editor: reads only the tag region (kilobytes), never the whole file.
- Streaming rewrite: one read and one write of the file; Ogg adds only page CRCs (milliseconds per MB).

Measured on the phone (§8.4); the release notes state the measured numbers, not the targets.

## 8. Testing and release criteria

### 8.1 Codec unit tests (host JVM; also compiled for iOS)

- Per format, synthetic files built inside the test: field change, growth and shrink, cover
  add/replace/remove, lyrics, numbers. Each case also asserts **which path was chosen** (in place vs
  rewrite), which pins the fast path.
- Every refusal in §4, each asserting the input is left byte-identical.
- A no-op edit leaves the file byte-identical; a second edit after a rewrite goes in place.
- Journal: a simulated failure at every byte of the write; after recovery the file equals the original.
- Test names without the characters Kotlin/Native rejects (compile `compileTestKotlinIosSimulatorArm64`).

### 8.2 Real-library corpus

- Copies of every file from the emulator's library copy (read-only there): ~550 MP3, 189 Opus,
  71 FLAC, 43 M4A. Kept outside the repository; the test self-skips without its directory, as the
  existing ID3 real-file test does.
- Every file goes through a set of edit scenarios. For each: (1) our reader returns exactly the
  expected values; (2) everything untouched is byte-identical; (3) the audio checksum matches;
  (4) **independent tools agree**: ffmpeg decodes before and after to identical PCM (`-f md5`),
  `flac -t` and Ogg CRC validation pass, and a third-party tag reader (mutagen) sees the same values
  as ours. These tools run on the desktop only; they ship in neither the app nor the repository.
- Every refusal on a real file is reviewed by hand: a correct refusal, or a codec gap to close.

### 8.3 Devices

- Android emulators at API 24, 28, 29, 30 and 36: consent on each version, single and batch edits,
  rescan, screen and artwork refresh, playback of edited files in all four formats, stopping a batch
  midway.
- The user's phone: **copies** of files in a separate folder only, never the real library.
- iOS simulator: editing imported files; Apple Music tracks show "can't be edited".

### 8.4 Speed

Per format on the phone: one in-place edit, one rewrite, one 12-track album edit.

### 8.5 Process

- Work on branch `feat/tag-editing`, in logical commits that each pass the full test suite.
- A code review pass before merging.
- Nothing is pushed or released until the user has checked it.

### 8.6 Release criteria (all required)

1. All unit tests and the full corpus run pass.
2. **Zero** audio checksum and PCM mismatches across the corpus.
3. Every refusal on real files explained and correct.
4. Device checklist passed (all five Android versions and iOS).
5. The user has verified it on the phone.
6. Then: version **0.7.0**, changelog, APKs, IPA, and a reply on issue #4.
