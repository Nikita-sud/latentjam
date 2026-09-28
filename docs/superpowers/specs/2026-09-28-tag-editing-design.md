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
  90). A PNG already within 1000×1000 and under 500 KB is kept as PNG. An album edit writes the same
  bytes to every track.
- **Which picture is "the cover".** Exactly one picture per file is the edit target; every other
  picture is preserved byte for byte, in order:
  - ID3 / FLAC / Ogg: the first picture of type 3 (front cover); if there is none, the first picture
    of type 0 (other), which many taggers use for the cover; if there is neither, Replace adds a new
    type 3 picture.
  - MP4 `covr` is a list of images with no front/back marker. The **first** image is the cover (the
    one iTunes and most players display); Replace and Remove act on it alone, and the rest of the list
    is kept in order.
  - When a file holds more pictures than the target, the editor says so ("+2 more images in the
    file, kept"), so removing the cover and seeing another picture take its place is not a surprise.

### 3.5 Effects on the rest of the app

- **Album artist is read and used.** The album subtitle shows it, and album grouping uses it as the
  album's identity, so compilations with per-track artists stop splitting into several albums.
  - Android 11+: MediaStore `ALBUM_ARTIST` (public since API 30).
  - Android 7–10: the constant is not public there. The scanner's `album_artist` column is probed at
    runtime (used only when the cursor actually has it); otherwise the value comes from the existing
    tag-enrichment pass that already reads files for credited artists and original year
    (`EmbeddedTagFacts` gains `albumArtist`). Which of the two applies is verified on the API 24,
    28 and 29 emulators.
  - iOS: `TPE2` / `ALBUMARTIST` / `aART` from the file, `albumArtist` from the Music library.
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
  length**. The difference is absorbed by **padding we are allowed to discard**, and only that:
  - Opus (RFC 7845 §5.2): data after the user comments whose first byte has its least significant
    bit **set** is binary data editors should preserve: it is kept byte for byte and never used as
    padding. Only when that bit is **clear** is the data padding, free to shrink or grow.
  - Vorbis: bytes after the framing bit are not defined by the specification. Existing non-zero
    bytes there are treated as unknown data and preserved exactly; only zero bytes (including our
    own padding) are resizable.
  - When no discardable padding can absorb the difference, the plan falls through to the streaming
    rewrite below.
- The Vorbis setup packet is copied byte for byte. CRCs of the rewritten pages are recomputed. Page
  sequence numbers and all audio pages are untouched.
- The page count must change (for example a new cover of hundreds of KB in base64) → **streaming
  rewrite**: every following page gets its sequence number shifted and its CRC recomputed. One pass,
  no re-encoding.
- Refusals: several logical streams or chained streams, bad CRCs in the header pages, an unknown codec.

### 4.4 M4A / MP4

- Tags live in `moov/udta/meta/ilst`; audio in `mdat`; `stco`/`co64` hold **absolute** audio offsets.
- New `ilst` fits in the old one plus adjacent `free` atoms → **in place**, parent sizes (`meta`,
  `udta`, `moov`) updated. Nothing moves, so no offset changes.
- Otherwise the edit changes the size of `moov` by a delta **at a known position P** (the end of the
  old `moov`). Two cases:
  - **`moov` is the last top-level atom** (only `free` atoms may follow it, and they are absorbed):
    `moov` is rewritten at its own offset and the file length adjusted. Nothing after P exists, so
    no offset changes. A fast path. Note "`moov` after `mdat`" is **not** the same condition: any
    other atom after `moov` (a second `mdat`, `uuid`, a trailing index) sends the file to the
    general case.
  - **General case** → **streaming rewrite**. Every chunk offset in every track's `stco`/`co64` is
    corrected **only if it points at or past P** (offsets before P, such as an `mdat` stored ahead
    of `moov`, are left alone). This is the conditional recalculation Mutagen uses, and it is
    correct with several `mdat` atoms in any order. A 16 KiB `free` is placed after `ilst`.
- No tags at all → `udta/meta(hdlr mdir)/ilst` created.
- Both `meta` variants are handled: ISO (4 bytes of version/flags) and QuickTime (none).
- Freeform iTunes atoms (`----`) are preserved.
- Refusals: fragmented MP4 (`moof`, `mfra`/`tfra`), DRM-protected `.m4p`, any other box that stores
  absolute file offsets and is not understood (`saio`, `iloc`, …), a chunk offset pointing **inside**
  the region being rewritten, a `stco` entry that would overflow 32 bits, an atom tree whose sizes do
  not add up.

## 5. Writing and durability

The rule "never damage a file" is carried by one protocol, not by care. Every write goes through a
**recovery store** and a **journal**, both in app-private *files* storage (never the cache, which the
system may clear at any time), and every step is either idempotent or recorded before it happens.

### 5.1 Recovery store and journal

- The journal is an append-only file of records, one per file being edited, each with a checksum so a
  torn record is detected and ignored. A record moves through states; each state change is appended
  and fsynced **before** the step it announces.
- The recovery store holds whatever bytes are needed to put the file back (§5.2, §5.3). An entry is
  deleted only after its file has been verified as durable **and** the journal records it as done.
- Both the journal file and its directory are fsynced when records are appended.

### 5.2 In-place patch

1. **Preflight**: free space in app storage for the saved ranges; free space on the target volume if
   the file grows.
2. **Save**: into the recovery store, the original bytes of every range to be written, the original
   length, and **the tail that will be cut off** when the file shrinks (an ID3v1 trailer, a shorter
   `moov` at the end). Fsync. Journal: `PATCH_PREPARED` with the expected checksum of every written
   range and the expected final length.
3. **Write** the ranges, set the length.
4. **Force to storage**: `FileChannel.force(true)` / `FileDescriptor.sync()` on the target **before**
   anything else; a read-back before this point could be served from the page cache and prove nothing.
5. **Verify**: read back the written ranges and compare checksums, check the length, then re-read the
   tags with the codec and compare every field with the expected snapshot.
6. Journal: `DONE`; delete the saved bytes.

Any failure in 3–5 → **roll back**: write the saved ranges and tail back, restore the length, force,
verify against the saved bytes, journal `ROLLED_BACK`. The file is reported as not changed.

### 5.3 Streaming rewrite

Used only when spare space runs out, so it may be slower in exchange for being safe.

1. **Preflight**: free space in app storage for the new file **and** a backup of the original
   (≈ 2 × file size + 16 MiB); free space on the target volume for the size difference. Otherwise the
   edit is refused with "not enough space" and nothing is touched.
2. **Stage**: build the new file in the recovery store in 1 MiB blocks, computing the audio checksum
   of the original while reading it. Force.
3. **Verify the staged file**: re-read its tags and compare with the expected snapshot; recompute its
   audio checksum and compare with the original's; record the whole staged file's checksum.
4. **Back up the original** into the recovery store and verify the copy's checksum. Force. Journal:
   `REPLACE_PREPARED` (staged checksum, backup checksum, final length).
5. **Copy over**: open the target **without truncating** (`"rw"`, not `"rwt"`), write the staged bytes
   from offset 0, then set the final length. Journal `REPLACING` is written before the first byte.
6. **Force** the target, then **verify the whole target** against the staged checksum.
7. Journal: `DONE`; delete the staged file and the backup.

The original is untouched until step 5, and from step 5 on two verified complete copies exist outside
it. The journal says exactly how far the replacement got.

### 5.4 Recovery

- At launch (and before any new edit), unfinished journal records are processed:
  - `PATCH_PREPARED` → roll back from the saved ranges (§5.2).
  - `REPLACE_PREPARED` / `REPLACING` → finish **forward** from the staged copy if its checksum still
    matches; if it does not, restore from the backup if its checksum matches. Both are complete
    copies, so either result is a whole, verified file.
- Recovery is idempotent: every action is "make the target equal these checksummed bytes", so a crash
  during recovery is recovered by running recovery again.
- **Recovery needs write access again.** A `createWriteRequest` grant does not survive the activity,
  so after a restart the app has none. Recovery therefore runs as a user-visible step: "LatentJam
  needs to finish saving 2 files" → the system consent for exactly those files (Android 11+), the
  per-file consent (Android 10), or the storage permission (Android 7–9, normally still granted).
  - Declined or dismissed: records and recovery data are kept, and the prompt returns at the next
    launch and whenever the file is opened in the editor. A persistent notice in Settings → Library
    says which files are waiting.
  - While a file sits in `REPLACING`, it is marked in the library as being repaired and is not
    offered for playback or a new edit.
- iOS needs no consent for imported files, so recovery there is automatic.

### 5.5 Platforms

- **Android 11+**: `MediaStore.createWriteRequest` for the files of an edit. One request holds at
  most 2,000 URIs for apps targeting API 36, so larger selections are split into consecutive
  requests of up to 2,000.
- **Android 10**: `RecoverableSecurityException` consent per file, the same mechanism as deletion.
  A 12-track album means 12 dialogs; accepted.
- **Android 7–9**: `WRITE_EXTERNAL_STORAGE` (already declared with `maxSdkVersion=28`), requested
  once, the same mechanism as deletion.
- **iOS**: files imported into the app's Documents are written with `FileManager`. The final step of a
  streaming rewrite is an atomic replace there (write a sibling, then swap), so no copy-over window
  exists. Tracks from the Apple Music library are shown as not editable, with the reason.
- **Batch**: files are written with a concurrency of 2–4, each with its own journal record. **One**
  media rescan for the whole list at the end (`MediaScannerConnection.scanFile` with all paths).

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

- Every field has three explicit states, so "leave alone" and "clear" can never be confused:
  - **Keep** (the default): a shared value is shown as usual; differing values show an empty field
    with the hint "Different values". Nothing is written for this field.
  - **Set**: something was typed; that value goes to every selected track.
  - **Remove**: chosen with the field's clear control (or its menu). The field shows "Removed from
    all 12" and the field is deleted from every selected track. Tapping the field again returns it to
    Keep.
  An empty text box therefore always means Keep; removal is always a deliberate, visible action.
  The same three states apply to the cover (Keep · Replace · Remove).
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
- **SMART keeps its audio analysis.** Today `sourceRevision` is part of the **audio** vector's
  identity (`DefaultSimilarityEngine.audioVectorIdentity`), so any byte change — even a cover —
  would drop the track's audio vector and force a fresh audio analysis. After a verified tag-only
  write the audio is proven unchanged (in place: never touched; rewrite: equal audio checksum), so:
  - the write session records a **carry-over** per file: track id, the old revision, and the new
    file length;
  - at the next index sync, when that track appears with a new revision **and** the expected length,
    SMART re-keys the existing audio vector to the new revision instead of discarding it. A mismatch
    (the file changed again by something else) falls back to normal re-analysis;
  - the **text** vector is re-embedded as usual, because artist, genre and year did change;
  - carry-overs are persisted, and dropped once applied or after one sync that does not match.

### 6.6 Strings

All new strings translated into the app's 17 locales, respecting the known locale traps
(literal `%`, `values-in` = `values-id`, sort keys never reused as titles).

## 7. Performance targets

- In-place edit: tens of milliseconds per file, excluding the system dialog.
- A 12-track album edit: well under a second of writing.
- Opening the editor: reads only the tag region (kilobytes), never the whole file.
- Streaming rewrite: rare by design, and deliberately paid for with safety: staging, a verified
  backup of the original, the copy-over and a full verification (§5.3) — a few passes over the file.
  Ogg adds only page CRCs (milliseconds per MB). The 16 KiB spare space keeps later edits of the same
  file on the fast path.

Measured on the phone (§8.4); the release notes state the measured numbers, not the targets.

## 8. Testing and release criteria

### 8.1 Codec unit tests (host JVM; also compiled for iOS)

- Per format, synthetic files built inside the test: field change, growth and shrink, cover
  add/replace/remove, lyrics, numbers. Each case also asserts **which path was chosen** (in place vs
  rewrite), which pins the fast path.
- Every refusal in §4, each asserting the input is left byte-identical.
- A no-op edit leaves the file byte-identical; a second edit after a rewrite goes in place.
- Journal and recovery, with a fault-injecting file layer: a crash at every step and at every byte of
  every write in §5.2 and §5.3, including the copy-over; a second crash during recovery (recovery run
  again must still end with a whole, verified file); a torn journal record; a staged copy or backup
  whose checksum no longer matches.
- Out of space at each step (preflight, staging, backup, copy-over, rollback): nothing damaged, the
  edit reported as not saved.
- Batch splitting at the 2,000-URI consent limit.
- SMART carry-over: the audio vector survives a verified tag-only edit, and is re-analysed when the
  new revision or length does not match.
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
  midway, album artist read on every version.
- Interrupted saves on a device: the app killed during a copy-over, then relaunched; the recovery
  prompt declined (file stays marked, prompt returns) and then accepted (file finished and verified).
- A full volume during an edit.
- The user's phone: **copies** of files in a separate folder only, never the real library.
- iOS simulator: editing imported files; Apple Music tracks show "can't be edited".

### 8.4 Speed

Per format on the phone: one in-place edit, one rewrite, one 12-track album edit.

### 8.5 Process

- Work on branch `feat/tag-editing`, in logical commits that each pass the full test suite.
- A code review pass before merging.
- Nothing is pushed or released until the user has checked it.

### 8.6 Release criteria (all required)

1. All unit tests, the fault-injection suite and the full corpus run pass.
2. **Zero** audio checksum and PCM mismatches across the corpus.
3. Every refusal on real files explained and correct.
4. Device checklist passed (all five Android versions and iOS), interrupted saves included.
5. The user has verified it on the phone.
6. Then: version **0.7.0**, changelog, APKs, IPA, and a reply on issue #4.
