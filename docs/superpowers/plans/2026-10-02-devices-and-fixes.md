# 0.7.0 Plan 4a — Device Verification and Fixes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the two known tag-editing gaps (O2, O4) and verify the 0.7.0 candidate on Android 7, 9, 10, 11 and 16, on iOS, and over an existing 0.6.0 install. This plan does not include the release: version bump, changelog, APKs, IPA and issue replies wait for the user's separate command.

**Architecture:**
- Both fixes land on `feat/tag-writing` (worktree `~/Documents/LJ/latentjam-tagwrite`).
- O4 lives entirely in `:core:library`'s codecs. A Year edit also moves the file's original-release year, but only when that year said the same thing.
- O2 is Android-only. A track whose own cover was edited gets a per-track `file://` cover, cached by CRC32, instead of MediaStore's per-album art. No other track changes.
- Device checks run on a local integration branch `rc/0.7.0` = `feat/tag-writing` + `feat/smart-journey`, so what is tested is what would ship.

**Tech Stack:** KMP 2.3.10, Compose Multiplatform, Media3 1.10.1, Coil 3, Android emulator (API 24/28/29/30/36), iOS 26 simulator.

**Spec:** `docs/superpowers/specs/2026-09-28-tag-editing-design.md`. It is silent on original year (§3.1/§3.3 list only `year`) and on per-track covers inside an album. The rulings below fill those gaps.

## Global Constraints

- Commits use the user's git identity, with NO `Co-Authored-By` or "Generated with" line and no AI mention. Commit messages follow the repo style, e.g. `fix(tags): a year edit moves the original year when both said the same`.
- Never push, merge into `main`, tag or release.
- Never touch the main checkout `~/Documents/LJ/latentjam` (another session's uncommitted WIP lives there), nor the worktrees `latentjam-tags` and `latentjam-issues`.
- Never touch the real phone or the user's real library. Audio comes only from copies of `~/Documents/LJ/tag-corpus/files` (read-only source).
- Android: never use AVD `LJ-demo`. Always pass `adb -s emulator-<port>`, because a phone may be plugged in. New AVDs are disposable and named `LJ-api24`, `LJ-api28`, `LJ-api29` and `LJ-api30`. `LJ-tagtest3` (API 36.1) and the iOS simulator `LJ-tagtest3-ios` may be reused.
- Every new user-visible string goes into all 17 locales in the same commit (`StringResourceParityTest`). values-in body = values-id.
- Kotlin/Native test names: none of the characters `, ? ( ) $ % @` in backticked names. Run `compileTestKotlinIosSimulatorArm64` for any module whose tests change.
- Gradle: `./gradlew --no-daemon`. Suites:
  - `:core:library:testAndroidHostTest`
  - `:core:smart:testAndroidHostTest`
  - `:core:playback:testAndroidHostTest`
  - `:composeApp:testAndroidHostTest`

  Baseline at `2e8a38d4`: 615 / 383 / 149 / 713.

## Review Focus

1. **A remaster** (DATE 2011, ORIGINALDATE 1973). Editing Year to 2012 must leave 1973 alone, and the album stays 1973.
2. **Removing a cover from one track of a multi-track album** on Android. That track shows no cover (generated colour cover). The other tracks keep theirs.
3. **Another app retags a track** whose cover LatentJam overrode. After a rescan, the override is re-checked against the file's embedded cover and dropped if it differs.
4. **An album with one overridden track** stays ONE album in every grouping, including the same-title multi-artist album and the null-title album.
5. **Clearing Year** on a file whose original year equals the old year. The original year is kept, not removed.

---

### Task 1: O4 — a Year edit moves the original year when both said the same

**Ruling:** option (b) from the investigation.
- When an edit sets `year`, and the file's original-release year has the same leading four digits as the file's current year, the codec writes the new year value (exactly as typed) to the original-date field(s) the file already has.
- It never adds an original-date field the file did not have.
- It never touches the original date when the years differ (a remaster), or when the edit clears Year (`""`).
- MP4 has no standard original-date item and none is read today, so MP4 is unchanged.

**Files:**
- Modify: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/Id3Tags.kt`. ID3 v2.4 `TDOR`; v2.3 `TORY`, plus `TDOR` if it is present on v2.3.
- Modify: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/VorbisComments.kt`. `ORIGINALDATE` and `ORIGINALYEAR`, each only if present. Used by FLAC, Ogg Vorbis and Opus.
- Modify: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagVerification.kt` and the expected-value model in `TagModel.kt`, so post-write verification expects the moved original year.
- Test: new `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/OriginalYearFollowsYearTest.kt`.

**Interfaces:**
- Consumes: `TagEdits.year`, `TagFacts.fromComments` (reads `ORIGINALYEAR`/`ORIGINALDATE`/`TDOR`/`TORY`, first match wins), and each codec's existing year writer (`Id3Tags.kt:395-404`, `VorbisComments.kt:165`).
- Produces: no new public API. `TagEdits` is unchanged; the behaviour sits inside the codecs' plan/write path.

- [ ] **Step 1: Write failing tests.** One test per row, each building a minimal file with the existing test helpers:

| Format | Before | Edit | Expected after |
|---|---|---|---|
| ID3v2.4 | TDRC 1999, TDOR 1999 | year 2004 | TDRC 2004, TDOR 2004 |
| ID3v2.4 | TDRC 2011, TDOR 1973 | year 2012 | TDRC 2012, TDOR 1973 |
| ID3v2.4 | TDRC 1999-05-01, TDOR 1999-05-01 | year 2004 | TDOR "2004" |
| ID3v2.3 | TYER 1999, TORY 1999 | year 2004 | TORY 2004 |
| ID3v2.4 | TDRC 1999, no TDOR | year 2004 | no TDOR frame added |
| ID3v2.4 | TDRC 1999, TDOR 1999 | year "" | TDRC removed, TDOR 1999 kept |
| FLAC | DATE 1999, ORIGINALDATE 1999, ORIGINALYEAR 1999 | year 2004 | both original keys 2004 |
| FLAC | DATE 2011, ORIGINALDATE 1973 | year 2012 | ORIGINALDATE 1973 |
| Opus | DATE 1999, ORIGINALDATE 1999 | year 2004 | ORIGINALDATE 2004 |
| Ogg Vorbis | DATE 1999, ORIGINALDATE 1999 | year 2004 | ORIGINALDATE 2004 |
| MP4 | ©day 1999 | year 2004 | ©day 2004, no other atom added |
| Any | edit not touching year | — | original untouched |

Also: every written file passes `TagVerification`, and audio bytes stay identical (existing digest helper).

- [ ] **Step 2:** Run `./gradlew --no-daemon :core:library:testAndroidHostTest --tests '*OriginalYearFollowsYearTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement in the codecs. Comparison: the leading 4 digits of the current year value equal the leading 4 digits of the original value. Use the same year parsing as `TagFacts`.
- [ ] **Step 4:** Run the test, the whole `:core:library` suite and `:core:library:compileTestKotlinIosSimulatorArm64`. Expected: PASS, with ≥ 615 + new tests.
- [ ] **Step 5:** Corpus check. Copy `~/Documents/LJ/tag-corpus/files` into a scratch dir and run the existing real-library test the way plan 1 and 2 did (see `~/Documents/LJ/tag-corpus/verify.py` and the archive `~/Documents/LJ/audits/tag-writing-plan2-2026-09-30/`), with a year edit on every file. Expected: 0 failures, 0 audio mismatches. Report how many files had an original date equal to their year.
- [ ] **Step 6:** Commit `fix(tags): a year edit moves the original year when both said the same`.

### Task 2: O2 — on Android a track whose cover was edited shows its own cover

**Rulings:**
- Only tracks whose cover LatentJam edited get a per-track cover. Everything else keeps MediaStore's album art, so there is no per-row decoding.
- `CoverEdit.Replace`: after a successful save, the bytes are written to `cacheDir/../files/track-covers/<crc32>.<ext>` (files dir, so the system does not evict them). The override `trackKey → (crc32, revision)` is persisted in a small file next to them.
- `CoverEdit.Remove`: the override is "no cover" (`artworkUri = null`), so the player's generated colour cover shows.
- On every library scan, an override whose recorded revision differs from the track's current `sourceRevision` is re-checked by reading the file's embedded `CoverInfo` (`TagCodecs.read`, which only runs for overridden tracks):
  - same crc → keep the override and update its revision;
  - different crc or no cover → drop the override and delete the file if nothing else references it;
  - track gone → drop.
- Album grouping must not change. `LibraryCatalog` compares `artworkUri` (lines ~269-283, ~333, album key ~350). It must keep seeing the album-level identity: either the override is applied in a field the catalog does not group on, or the catalog groups on the album-level URI. The implementer picks one and documents it.
- Album cards: `AlbumGroup.artworkUri` = first non-null, so an album whose first track is overridden shows that cover. This matches iOS and is accepted.

**Files (expected, the implementer confirms):**
- Create: `core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/TrackCoverOverrides.kt` (store + scan-time application).
- Modify: `core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/MusicLibrary.android.kt` (apply overrides after `withAlbumArtVersions`).
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt` (`followTagSaves`, ~909-924, and the unclaimed-report path ~4557-4580). Record overrides for saves that carried a `CoverEdit` before `scanLibrary()`; a common `expect`/no-op on iOS.
- Possibly: `LibraryCatalog.kt` grouping key.
- Tests:
  - `core/library/src/androidHostTest/.../TrackCoverOverridesTest.kt`;
  - a `LibraryCatalog` grouping test in commonTest;
  - a `refreshTracks` test showing only the edited track gets `coverChanged`.

**Interfaces:**
- Consumes: `TagSaveResult`, `changedKeys()` / `tracksWithKeys` (`TagEditRefresh.kt:100-107`), `CoverEdit.Replace(bytes, mime)`, `CoverInfo.of(bytes, mime)`, `PlaybackController.refreshTracks` (compares the old and new `artworkUri`), and `TagFileRead.android.kt` (fd + `FileChannelSource` + `TagCodecs.read` pattern).
- Produces: a `file://…/track-covers/<crc32>.<ext>` URI for overridden tracks. Coil, `TrackAccent`, the widget and Media3 all key on the URI string, so no other changes are needed.

- [ ] **Step 1: Write failing tests:**
  - store round-trip;
  - Replace → file URI;
  - Remove → null;
  - revision change with the same crc → kept with the revision updated;
  - revision change with a different crc → dropped and the file deleted;
  - track gone → dropped;
  - one overridden track does not split any album group (cover Review Focus 4 explicitly);
  - `refreshTracks` flags only the edited track.
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run all four suites, plus `:composeApp:compileKotlinIosSimulatorArm64` and `:core:library:compileTestKotlinIosSimulatorArm64`. Expected: PASS.
- [ ] **Step 5:** Commit `fix(tags): on Android a song's new cover shows even when the rest of its album keeps the old one`.

### Task 3: Release-candidate branch

- [ ] Create worktree `~/Documents/LJ/latentjam-rc` on new branch `rc/0.7.0` from `feat/tag-writing` (after Tasks 1–2), then `git merge --no-ff feat/smart-journey` and `git merge --no-ff build/reduced-ort`. Resolve conflicts if any; record each.
- [ ] Run all four suites and the parity test against `feat/smart-journey`'s regenerated fixture (`SMART_PARITY_FIXTURE=~/Documents/LJ/latentjam-smartjourney/tools/research/output/parity-fixture-journey … --tests '*SmartChainParityTest*' --rerun`). Check the XML for "parity: 10 seeds".
- [ ] Build the debug-key release APKs the way 0.6.0 was built (`./gradlew :androidApp:assembleRelease`, see the memory `latentjam-release-plan.md`). Build the arm64 APK WITH the reduced ONNX Runtime: `./gradlew --no-daemon --no-configuration-cache :androidApp:assembleRelease -I tools/runtime/use-reduced-ort.init.gradle -Platentjam.reducedOrtRepo=$HOME/Documents/LJ/ort-reduced/1.26.0-20261001/maven`. Confirm with `unzip -l` that `lib/arm64-v8a/libonnxruntime.so` is 11,723,184 bytes. All Android checks below use this APK. Also build the iOS simulator app. Do not bump versions.

### Task 4: Android 16 verification (`LJ-tagtest3`)

The pass criteria are written down here; capture evidence with screenshots plus `adb shell md5`. Report to `~/Documents/LJ/audits/devices-plan4a-2026-10-02/android16.md`.
- [ ] **Upgrade over 0.6.0:**
  1. Uninstall the app.
  2. Build `v0.6.0` from its tag on the same debug key, install it, and grant access to a corpus copy (~300 files). Set an album sort and play SMART.
  3. Install the rc over it.
  4. Pass: the library, sort, history and SMART index survive with no re-analysis of unchanged files, and the lyrics re-index finishes.
- [ ] **O2:** replace one track's cover in a 3+ track album → that track shows the new cover in the list, the player and the notification, and the siblings keep the old one. Remove a cover from another track → generated cover. Restart the app → it persists.
- [ ] **O4:** edit Year on a file with ORIGINALDATE = DATE → the album year changes. On a remaster file → the album year stays.
- [ ] **Rotation right after a save:** single and bulk; rotate within 1 s of tapping Save. Pass: the save completes, the editor state is coherent, and the queue shows the new tags.
- [ ] **Full volume:** all 853 corpus copies on the device. Scan, open Albums, Artists and Tracks, search, edit an album of 10+ tracks. Record the scan time and any jank.
- [ ] **RECOVER with one deleted file:**
  1. Start a 5-file bulk save and kill the process with `run-as … kill -9` mid-save.
  2. Delete one of the five via `adb shell rm`.
  3. Relaunch.
  4. Pass: the other four finish or roll back cleanly, and the deleted one is reported, not looped on.
- [ ] **#5/#6/#7 smoke:**
  - #5: album sort by release year.
  - #6: M4A embedded lyrics and a .lrc next to a song.
  - #7: a mojibake fixture reads as "Grüße".
- [ ] **SMART:** play SMART from three seeds for 25+ tracks each. Pass: the queue never stops, and artist runs are noted.

### Task 5: iOS verification (`LJ-tagtest3-ios`)

Report to `…/ios.md`.
- [ ] Bulk editor focus: typing in several fields never loses focus. Discard dialog: closing with edits asks, and Keep editing returns with the edits intact.
- [ ] O4 on an imported FLAC/MP3 with an equal ORIGINALDATE: the album year follows.
- [ ] #6: a .lrc dropped next to a song via the Files app (Imported folder) shows lyrics.
- [ ] SMART plays 25+ tracks without stopping.

### Task 6: Old Android (API 24, 28, 29, 30)

Create the four AVDs from the downloaded `google_apis;arm64-v8a` images. Report to `…/android-old.md`.
- [ ] **Each API:**
  - install the rc;
  - grant library access;
  - edit one MP3 and one FLAC (consent flow: ≤28 storage permission, 29 `RecoverableSecurityException` prompt, 30 `createWriteRequest`);
  - verify the audio md5 is unchanged;
  - the album-artist column probe groups a compilation correctly (24/28/29 have no `ALBUM_ARTIST` column semantics like 30+).
- [ ] **API 29:** I1. Edit a playing track's tags, background the app and return. Pass: no re-analysis of the edited track (SMART carry-over) and no rescan while a save is active.
- [ ] **API 30:** RECOVER with one deleted file (as in Task 4).
- [ ] **API 30:** SD card. Create the AVD with an sdcard image, put an album on the SD card's Music folder, and edit a tag there. Pass: the save succeeds, or the app refuses with the reason-specific "not editable" text, never a silent failure.

### Task 7: Large library (Android 16)

- [ ] Generate a 10,000-file library on the emulator from corpus copies: distinct titles, artists and albums rewritten via the app's own codecs in a host-side tool, or by duplicating files into ~1,000 folders with edited tags. Record:
  - first scan time;
  - the time to open Tracks, Albums and Artists;
  - search latency for three queries;
  - lyrics index time;
  - the time SMART takes to plan its first queue;
  - memory (`dumpsys meminfo`).

  Pass: no ANR, no crash, and search stays interactive.

### Task 8: Real phone (needs the user; prepared, not executed)

- [ ] Prepare the fsync timing build/instructions from spec §8.4 and a one-page checklist for the user, including the 4-format edit, a bulk album edit and a recovery, plus SMART on the reduced ONNX Runtime: background analysis progresses and a SMART queue plays (the reduced runtime has emulator coverage only). Do NOT install anything on the phone; hand it to the user.

### Task 9: Close-out

- [ ] Final whole-branch review of Tasks 1–2 (most capable model), one fix wave, re-run the suites on `rc/0.7.0`.
- [ ] Update the memory `latentjam-tag-editing-0-7.md` with the results, and archive the ledger and reports in `~/Documents/LJ/audits/devices-plan4a-2026-10-02/`.
- [ ] Report to the user in Russian: what passed, what failed and what got fixed, and the open items. Do not release.
