# Tag Editor and App Integration Implementation Plan (plan 3 of 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The user edits every supported tag of one track, or of a whole album, artist or selection at once, sees exactly what happened to every file, and the rest of the app follows the new tags at once: album artist grouping, artwork, the player, the lock screen and SMART, which keeps its audio analysis.

**Architecture:**
- Plan 2 built the durable writer and `TagWriteCoordinator` (queue, consent, checkpoint, recovery). This plan puts the app on top of it:
  1. a process-wide owner of the coordinator on both platforms;
  2. one common seam, `TagSaver`, that both editors use;
  3. a read seam, `readTagFile`, that fills the editors from the file itself;
  4. a cover picker that returns bytes ready to write.
- Pure logic is kept in small, host-testable classes, and the Compose sheets only render it. These classes are `TagEditorForm`, `BulkTagForm`, `TagSaveResult`, `tagProblemOf`, `CheckpointFanOut`, `AudioCarryOverStore` and `refreshedCollectionSource`.
- The library learns the album artist and uses it as the album's identity.
- Android artwork URIs carry a per-album version, so every image cache (Coil, the accent colour, Media3's notification) reloads after an edit.
- SMART re-keys an unchanged audio vector instead of re-analysing the file.

**Tech Stack:** Kotlin Multiplatform 2.3.10, Compose Multiplatform (Material 3), Coil 3.4.0, Media3 (Android), MediaPlayer/MPNowPlayingInfoCenter (iOS), kotlinx.coroutines.

**Spec:** `docs/superpowers/specs/2026-09-28-tag-editing-design.md`. This plan implements:
- **§3.5:** album artist read and used for grouping and the subtitle.
- **§5.4:**
  - the Settings → Library notice;
  - the "being repaired" state of a file mid-replace;
  - reporting recovery outcomes;
  - "forget".
- **§6:** all of it.
  - §6.1 single-track editor;
  - §6.2 N-track editor and entry points;
  - §6.3 batch progress, Stop and the exact result;
  - §6.4 cover picker;
  - §6.5 after saving: library, artwork, now playing, SMART carry-over;
  - §6.6 strings.
- **§8.1:** the SMART carry-over tests.

It also closes the plan-2 items deferred here (ledger: `~/Documents/LJ/audits/tag-writing-plan2-2026-09-30/progress.md`, `final-review.md`):
- Android `FileRecoveryDirectory.open` reads a failed stat as "absent" (parked, fix first).
- The coordinator lives in `viewModelScope`: finishing the Activity stops a batch, and a second MainActivity prunes the first's key files. A process-wide owner is needed.
- iOS keeps no checkpoint, and a suspended save is rolled back silently. Needed: a checkpoint file, a background task, and telling the user.
- M2b: a RECOVER report has no listener and is dropped. The outcome must be shown.
- M8: "not available on this device" is shown for a read-only volume and for an iOS Music-library track. A reason-specific text is needed.
- T12: the library is not reloaded after a recovery.

Left open on purpose: plan-2 finding M5. Concurrent rewrites do not reserve free space together. A shortfall ends as NotEnoughSpace or a recovery and damages nothing, so it waits for real-phone numbers in plan 4.

Plan 4 is devices (§8.3, §8.4) and the 0.7.0 release.

**Base:**
- Worktree `/Users/nichitabulgaru/Documents/LJ/latentjam-tagwrite`, branch `feat/tag-writing`, head `f93b8bad`.
- The stack is plan 1 (`feat/tag-editing` c577cef) → issues 5/6/7 (97d7a56) → plan 2 (f93b8bad).

## Global Constraints

- Work ONLY in `/Users/nichitabulgaru/Documents/LJ/latentjam-tagwrite` (branch `feat/tag-writing`). Never touch `/Users/nichitabulgaru/Documents/LJ/latentjam` (another session's uncommitted work lives there), `latentjam-tags` or `latentjam-issues`.
- Every new source file starts with the header `/*\n * Copyright (c) 2026 LatentJam Project\n * SPDX-License-Identifier: Apache-2.0\n */`.
- `core/*` modules use `explicitApi()`: public API needs explicit visibility. `core/library` and `core/smart` commonMain may use only Kotlin stdlib and kotlinx.coroutines.
- Test names are plain camelCase. Kotlin/Native rejects `, ? ( ) $ % @` and similar characters in backticked names.
- **Never damage a file.** Nothing in this plan writes a track except through `TagWriteCoordinator` → `DurableWriter`.
- A field the user did not touch is never written (spec §3.1: `null` = keep, `""` = remove). In the N-track editor, an empty text box always means Keep; removal is always a deliberate, visible action (spec §6.2).
- Cover encoding (spec §3.4):
  - A picked image larger than 1000×1000 is downscaled to fit and encoded as JPEG, quality 90.
  - A PNG already within 1000×1000 and under 500 KB is kept as PNG byte for byte.
  - Every other image is re-encoded as JPEG q90 at its own size. This also drops EXIF, including GPS.
- User-visible strings live in `composeApp/src/commonMain/composeResources/values*/strings.xml`.
  - Tasks 1–14 add the English text to `values/strings.xml` only. Compose falls back to it.
  - Task 15 adds real translations for every new key to all 17 locale files: ar, de, es, fr, hi, id, in, it, ja, ko, pl, pt-rBR, ro, ru, tr, uk, zh-rCN.
  - Locale traps:
    - `values-in` is byte-identical to `values-id`;
    - a literal percent is `%`, never `%%`;
    - `sort_*` keys are never reused as titles;
    - counts use `<plurals>` (`pluralStringResource`), like `count_tracks`.
- Test commands, run from the worktree (`--rerun` so Gradle never reports a stale UP-TO-DATE pass):
  - `./gradlew :core:library:testAndroidHostTest --offline -q --rerun`
  - `./gradlew :core:smart:testAndroidHostTest --offline -q --rerun`
  - `./gradlew :core:playback:testAndroidHostTest --offline -q --rerun`
  - `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
  - `./gradlew :core:library:compileTestKotlinIosSimulatorArm64 :core:smart:compileTestKotlinIosSimulatorArm64 :core:playback:compileTestKotlinIosSimulatorArm64 :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
  - `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q`
  - Read counts from `*/build/test-results/testAndroidHostTest/*.xml`. Baselines at f93b8bad: `core:library` 604, `composeApp` 644. Record the `core:smart` and `core:playback` baselines before Task 1.
- Commits:
  - The author is the configured git user.
  - Messages must NOT contain a Co-Authored-By line, a "Generated with" line, or any mention of an AI, LLM or assistant.
  - Use the repository style (`feat(tags): …`, `fix(tags): …`, `feat(library): …`, `feat(smart): …`) with a subject that says what the user gets.
  - Never push.
- Match the surrounding code's idiom and comment density. The codebase explains *why* in KDoc and says nothing where the code is plain.

## Review Focus

1. **A file changed on disk between opening the editor and saving** (another tagger, a sync app). Only the fields the user changed are written; everything else stays as the file now has it, because edits are computed against the snapshot and the codec plans from the file as it is at write time. Pinned by `onlyTheFieldsTheUserChangedAreEdited` and `whitespaceAloneIsNotAnEdit` (Task 7).
2. **An N-track edit whose files differ, some of them not editable** (a DRM M4A, a Music-library track on iOS, a damaged FLAC). The editor says before any typing which files will be left alone and why. The save touches only the rest, and the result lists every file that was not changed, with its reason. Pinned by `sharedValuesShowAndDifferentValuesStayEmpty`, `anEmptyBoxNeverRemoves` and `notEditableFilesAreSetAsideWithTheirReason` (Task 8).
3. **Renaming an album or artist while its page is open.** The page follows the album or artist to its new identity instead of showing stale tags or closing. Pinned by `anOpenAlbumFollowsItsTracksToTheirNewAlbum` and `anOpenArtistPageFollowsTheRenamedArtist` (Task 13).
4. **An editor recreated mid-save** (rotation, or process death on Android). It re-attaches to its own request, shows its progress, and gets its exact result, including a report that arrived while it was away. Pinned by `aReportDeliveredWhileTheEditorWasAwayIsClaimedWhenItReturns` (Task 4) and `aQueuedRequestStoppedBeforeItStartsWritesNothing` (Task 2).
5. **Editing the track that is playing.** Title, artist, album and artwork update in the queue, the player, the notification and the lock screen, and playback is not interrupted. Pinned by `refreshedTracksReplaceQueuedDescriptorsInPlace` (Task 11) and `albumArtVersionChangesWhenAnyTrackOfTheAlbumChanges` (Task 10). Real-device playback continuity is on plan 4's checklist.

---

## File Structure

**core/library**

| File | Change |
|---|---|
| `src/androidMain/…/library/tags/write/FileRecoveryDirectory.kt` | `open` returns null only on ENOENT (Task 1) |
| `src/commonMain/…/library/tags/TagFacts.kt` | `EmbeddedTagFacts.albumArtist`; `ALBUMARTIST` family read (Task 9) |
| `src/commonMain/…/library/tags/GenreTags.kt` | ID3 `TPE2` → `ALBUMARTIST` comment (Task 9) |
| `src/commonMain/…/library/LibraryCatalog.kt` | album owner = album artist ?: artist; `AlbumGroup.artist` shows it (Task 9) |
| `src/androidMain/…/library/MusicLibrary.android.kt` | `ALBUM_ARTIST` column (probed below API 30); versioned album-art URIs (Tasks 9, 10) |
| `src/androidMain/…/library/AlbumArtVersions.kt` | pure per-album artwork version (Task 10) |
| `src/iosMain/…/library/MusicLibrary.ios.kt` | album artist from files and the Music library (Task 9) |

**core/smart**

| File | Change |
|---|---|
| `src/commonMain/…/smart/TrackTypes.kt` | `TrackDescriptor.albumArtist` (Task 9) |
| `src/commonMain/…/smart/AudioCarryOver.kt` | `AudioCarryOver`, `AudioCarryOverResult` (Task 12) |
| `src/commonMain/…/smart/SimilarityEngine.kt` | `carryOverAudio` (Task 12) |
| `src/commonMain/…/smart/DefaultSimilarityEngine.kt` | `carryOverAudio` implementation (Task 12) |

**core/playback**

| File | Change |
|---|---|
| `src/commonMain/…/playback/PlaybackController.kt` | `refreshTracks` (Task 11) |
| `src/commonMain/…/playback/QueueRefresh.kt` | pure `refreshedTracks` (Task 11) |
| `src/androidMain/…/playback/PlaybackController.android.kt` | metadata replaced in place (Task 11) |
| `src/iosMain/…/playback/PlaybackController.ios.kt` | queue, artwork cache and Now Playing refreshed (Task 11) |

**composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/**

| File | Change |
|---|---|
| `TagWriteCoordinator.kt` | `stop(id)`, `forget`, unclaimed reports, `TagWriteReport.id` (Task 2) |
| `TagWriteOwners.kt` | `CheckpointFanOut`, `TagWriteAccess`, `expect rememberTagWriteAccess` (Task 3) |
| `TrackTagWriter.kt` | shrinks to the status model; `rememberTagWriter` and `TagWriteOutcome` go (Task 4) |
| `TagSaves.kt` | `TagProblem`, `tagProblemOf`, `TagSaveEntry`, `TagSaveResult`, `TagSaver`, `rememberTagSaver`, `tagProblemText` (Task 4) |
| `TagFileRead.kt` | `TagFileRead`, `expect readTagFile` (Task 5) |
| `TagCoverPicker.kt` | `TagCoverPick`, `expect rememberTagCoverPicker`, `readTagCover`, `deleteTagCover`, `tagCoverUri`, encoding rule (Task 6) |
| `TagEditorForm.kt` | pure single-track form (Task 7) |
| `TagEditorFields.kt` | shared field composables: number pair, cover row, problem text (Task 7) |
| `TrackInfoSheet.kt` | extended single-track editor (Task 7) |
| `BulkTagForm.kt` | pure N-track form (Task 8) |
| `BulkTagEditorSheet.kt` | N-track sheet: read, edit, progress, Stop, result (Task 8) |
| `GenreEnrichment.kt` | v4 cache with album artist (Task 9) |
| `AudioCarryOverStore.kt` | persisted carry-overs (Task 12) |
| `AppSettings.kt` (+ android, ios, 3 test fakes) | carry-over payload (Task 12) |
| `AppGraph.kt` | carry-overs applied before each sync (Task 12) |
| `TagEditRefresh.kt` | `refreshedCollectionSource`, `withFreshTracks`, `withoutFilesUnderRepair` (Task 13) |
| `CollectionDetailScreen.kt` | `onEditTags` menu item (Task 13) |
| `App.kt` | entry points, after-save refresh, unclaimed reports, the repair filter (Task 13) |
| `SettingsScreens.kt` | the "Interrupted saves" notice with Finish and Forget (Task 14) |

**composeApp platform sources**

| File | Change |
|---|---|
| `androidMain/…/TrackTagWriter.android.kt` | `AndroidTagWrites` process owner; `rememberTagWriteAccess` actual (Task 3) |
| `iosMain/…/TrackTagWriter.ios.kt` | checkpoint file, background task, `rememberTagWriteAccess` actual (Task 3) |
| `androidMain/…/TagFileRead.android.kt`, `iosMain/…/TagFileRead.ios.kt` | snapshot readers (Task 5) |
| `androidMain/…/CoverImport.android.kt` | shared importer for playlist and tag covers (Task 6) |
| `androidMain/…/PlaylistCoverPicker.android.kt` | uses the shared importer and view model (Task 6) |
| `androidMain/…/TagCoverPicker.android.kt`, `iosMain/…/TagCoverPicker.ios.kt` | tag cover picker actuals (Task 6) |
| `iosMain/…/PlaylistCoverPicker.ios.kt` | shares the ImageIO importer (Task 6) |

---

### Task 1: The recovery store never reads a journal it cannot open as absent

`FileRecoveryDirectory.open` guards with `File.isFile`. `stat` answers false for any failure (EACCES, EIO, a transient FUSE error), so an interrupted save's journal can read as absent. A sweep then deletes its saved bytes, the only way back. iOS already opens with `O_RDWR` and returns null only on `ENOENT` (`IosTagFiles.openExisting`). This task brings Android to the same rule. It was parked in plan 2 as "fix first thing in plan 3".

**Files:**
- Modify: `core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/FileRecoveryDirectory.kt` (`open`, imports)
- Test: `core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/JvmWriteFilesTest.kt`

**Interfaces:**
- Consumes: `ChannelTargetFile.open(file: File): ChannelTargetFile` (opens `"rw"`, which creates a missing file).
- Produces: `FileRecoveryDirectory.open(name)` returns null only when the file does not exist, and throws `IOException` for every other open failure.

- [ ] **Step 1: Write the failing tests**

Add to `JvmWriteFilesTest`:

```kotlin
    @Test
    fun aStoreFileThatIsMissingOpensAsAbsentWithoutBeingCreated() {
        val store = File(root, "store").apply { mkdirs() }
        val directory = FileRecoveryDirectory(store) {}
        assertNull(directory.open("w1.journal"))
        assertFalse(File(store, "w1.journal").exists(), "a probe must never create the file it looks for")
    }

    @Test
    fun aStoreEntryThatCannotBeOpenedFailsInsteadOfReadingAsAbsent() {
        val store = File(root, "store").apply { mkdirs() }
        // A directory under a journal's name: the open fails, and not with ENOENT.
        File(store, "w1.journal").mkdirs()
        val directory = FileRecoveryDirectory(store) {}
        assertFailsWith<IOException> { directory.open("w1.journal") }
    }
```

- [ ] **Step 2: Run the tests to verify the second fails**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --rerun --tests '*JvmWriteFilesTest*'`
Expected:
- `aStoreEntryThatCannotBeOpenedFailsInsteadOfReadingAsAbsent` FAILS: `isFile` is false for a directory, so `open` returns null.
- The first test passes already.

- [ ] **Step 3: Implement**

Replace `open` in `FileRecoveryDirectory` and add `import java.io.FileNotFoundException`:

```kotlin
    /**
     * Null only when the file is certainly not there. `File.isFile` also answers false when the stat
     * itself fails, and a journal read as absent would let a sweep delete the saved bytes it names.
     * The probe opens read-only because "rw" would create a missing file. Between the probe and the
     * open only this store's own writers could delete the name, and they run under the store lock.
     */
    override fun open(name: String): TargetFile? {
        val file = file(name)
        try {
            RandomAccessFile(file, "r").close()
        } catch (failure: FileNotFoundException) {
            if (failure.isNoSuchFile()) return null
            throw failure
        }
        return ChannelTargetFile.open(file)
    }

    /** Android says "open failed: ENOENT (No such file or directory)", a desktop JVM "(No such file or directory)". */
    private fun FileNotFoundException.isNoSuchFile(): Boolean =
        message?.let { "ENOENT" in it || "No such file or directory" in it } == true
```

- [ ] **Step 4: Run the whole module**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 606 tests (604 + 2).

- [ ] **Step 5: Commit**

```bash
git add core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/FileRecoveryDirectory.kt core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/JvmWriteFilesTest.kt
git commit -m "fix(tags): an interrupted save's record that cannot be opened is never taken for a finished one"
```

---

### Task 2: The coordinator stops any request, forgets a save on request, and keeps unclaimed reports

The editors need three things plan 2's coordinator lacks:
- **`stop(id)`.** `stop()` stops only the first request. An editor whose request is still queued behind another one would otherwise stop someone else's save.
- **`forget(record)`.** The Settings "Forget" action (spec §5.4, ruling M2 in plan 2) calls `TagRecovery.abandon` under the store lock, and never for a file a queued request names.
- **Unclaimed reports.** Today a report with no listener is dropped (plan-2 finding M2b). Examples: a recovery, an edit whose sheet was closed, or an edit restored after process death. Instead it is kept in `unclaimed` until something acknowledges it: the editor that comes back, or App's snackbar (Task 13). `TagWriteReport` gains its request `id` so a report can be matched to its editor.

The coordinator test's `Backend`, `mp3()` and `MPEG_FRAME` move to a shared test file first, because Task 4's `TagSaverTest` needs them too.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagWriteCoordinator.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.kt` (`TagWriteReport`)
- Create: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagWriteTestBackend.kt`
- Modify: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagWriteCoordinatorTest.kt`

**Interfaces:**
- Consumes:
  - `TagRecovery.abandon(record: JournalRecord)`;
  - `TagWriteStoreLock` (the process-wide `Mutex` object in `TagWriteCoordinator.kt`).
- Produces:
  - `internal data class TagWriteReport(val kind: TagWriteKind, val results: List<FileWriteResult>, val id: Long = 0)`
  - `fun TagWriteCoordinator.stop(id: Long)`
  - `suspend fun TagWriteCoordinator.forget(record: JournalRecord): Boolean`
  - `val TagWriteCoordinator.unclaimed: StateFlow<List<TagWriteReport>>`
  - `fun TagWriteCoordinator.acknowledge(report: TagWriteReport)`
  - test helpers:
    - `internal class TestTagWriteBackend(strategy: TagWriteStrategy) : TagWriteBackend<String>`, the old private `Backend`, unchanged;
    - `internal fun testMp3(title: String = "Old"): ByteArray`;
    - `internal val TEST_MPEG_FRAME: ByteArray`.

- [ ] **Step 1: Move the test backend**

1. Create `TagWriteTestBackend.kt`. It holds the licence header, `package io.github.nikitasud.latentjam.app`, and the imports the moved code needs.
2. Move into it, unchanged except for names and visibility:
   - the whole `private class Backend(...)` from `TagWriteCoordinatorTest`, renamed `internal class TestTagWriteBackend`;
   - `mp3()`, as `internal fun testMp3(title: String = "Old"): ByteArray = Id3Tags.updateTag(TEST_MPEG_FRAME + ByteArray(1024), TagEdits(title = title))!!`;
   - `MPEG_FRAME` from the companion, as `internal val TEST_MPEG_FRAME`. Keep its KDoc.
3. In `TagWriteCoordinatorTest`, replace:
   - `Backend(` → `TestTagWriteBackend(`;
   - `mp3(` → `testMp3(`;
   - `backend: Backend` → `backend: TestTagWriteBackend`.
4. Delete the emptied companion object.

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagWriteCoordinatorTest*'`
Expected: PASS with the same count as before the move.

- [ ] **Step 2: Write the failing tests**

Add to `TagWriteCoordinatorTest`:

```kotlin
    @Test
    fun aQueuedRequestStoppedBeforeItStartsWritesNothing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val harness = Harness(backend, this)
        val release = CompletableDeferred<Unit>()
        backend.gate = { key -> if (key == "a") release.await() }
        harness.enqueue(listOf("a"), TagEdits(title = "First"))
        val second = harness.enqueue(listOf("b"), TagEdits(title = "Second"))
        runCurrent()
        harness.coordinator.stop(second)
        release.complete(Unit)
        runCurrent()
        harness.deliver()
        runCurrent()
        harness.deliver()
        assertEquals(FileWriteStatus.SAVED, harness.reports[0].results.single().status)
        assertEquals(FileWriteStatus.STOPPED, harness.reports[1].results.single().status)
        assertContentEquals(testMp3(), backend.files.bytes("b"))
    }

    @Test
    fun stoppingOneRequestLeavesTheRunningOneAlone() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val harness = Harness(backend, this)
        val release = CompletableDeferred<Unit>()
        backend.gate = { key -> if (key == "a") release.await() }
        harness.enqueue(listOf("a"), TagEdits(title = "First"))
        val second = harness.enqueue(listOf("b"), TagEdits(title = "Second"))
        runCurrent()
        harness.coordinator.stop(second)
        release.complete(Unit)
        runCurrent()
        harness.deliver()
        assertContentEquals(testMp3("First"), backend.files.bytes("a"))
    }

    @Test
    fun aReportNobodyListensForIsKeptUntilAcknowledged() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this)
        val id = assertNotNull(harness.coordinator.enqueue(listOf("a"), TagEdits(title = "New")))
        runCurrent()
        harness.deliver()
        val report = harness.coordinator.unclaimed.value.single()
        assertEquals(id, report.id)
        assertEquals(FileWriteStatus.SAVED, report.results.single().status)
        harness.coordinator.acknowledge(report)
        assertTrue(harness.coordinator.unclaimed.value.isEmpty())
    }

    @Test
    fun aListenedReportIsNeverAlsoUnclaimed() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(1, harness.reports.size)
        assertTrue(harness.coordinator.unclaimed.value.isEmpty())
    }

    @Test
    fun forgettingAnInterruptedSaveDeletesItsRecordAndLeavesTheFileAsItIs() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val halfWritten = backend.files.bytes("a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        val record = harness.coordinator.pendingRecovery.value.single()
        assertTrue(harness.coordinator.forget(record))
        assertTrue(harness.coordinator.pendingRecovery.value.isEmpty())
        assertTrue(backend.recovery.pending().isEmpty())
        assertContentEquals(halfWritten, backend.files.bytes("a"))
    }

    @Test
    fun aFileSomeQueuedRequestNamesIsNotForgotten() = runTest {
        // Consent is never granted here, so the request stays queued and owns the file's record.
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        val record = harness.coordinator.pendingRecovery.value.single()
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        assertFalse(harness.coordinator.forget(record))
        assertEquals(1, backend.recovery.pending().size)
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagWriteCoordinatorTest*'`
Expected: compilation FAILS. `stop(Long)`, `unclaimed`, `acknowledge`, `forget` and `TagWriteReport.id` do not exist.

- [ ] **Step 4: Implement**

In `TrackTagWriter.kt`:

```kotlin
/** A finished request: what became of each file, and the request [id] its editor listens on. */
internal data class TagWriteReport(val kind: TagWriteKind, val results: List<FileWriteResult>, val id: Long = 0)
```

In `TagWriteCoordinator`, next to `pendingRecovery`:

```kotlin
    private val mutableUnclaimed = MutableStateFlow<List<TagWriteReport>>(emptyList())

    /**
     * Finished reports nobody was listening for: a recovery, an edit whose sheet was closed or
     * recreated, or an edit restored after process death. Kept until [acknowledge]; a report shown
     * nowhere would be a save that failed or succeeded in silence.
     */
    val unclaimed = mutableUnclaimed.asStateFlow()

    fun acknowledge(report: TagWriteReport) {
        mutableUnclaimed.value = mutableUnclaimed.value.filterNot { it.id == report.id }
    }
```

In `deliver`, build the report with its id and hand an unlistened one to `unclaimed`. Replace:

```kotlin
        val report = TagWriteReport(first.kind, first.results)
```

with

```kotlin
        val report = TagWriteReport(first.kind, first.results, first.id)
```

and replace the last two statements:

```kotlin
        listeners.remove(id)?.invoke(report)
        resume()
```

with

```kotlin
        val listener = listeners.remove(id)
        if (listener != null) listener(report) else mutableUnclaimed.value = mutableUnclaimed.value + report
        resume()
```

Add after `stop()`:

```kotlin
    /**
     * Stops [id]'s request: between files when it is running, before its first file when it is
     * still queued behind another. An editor stops its own save, never the one in front of it.
     */
    fun stop(id: Long) {
        if (restoring) {
            deferred += { stop(id) }
            return
        }
        val index = requests.indexOfFirst { it.id == id }
        when {
            index < 0 -> return
            index == 0 -> stop()
            else -> {
                requests = requests.mapIndexed { i, request -> if (i == index) request.copy(stopRequested = true) else request }
                checkpoint()
            }
        }
    }

    /**
     * Gives up an interrupted save for good. Its record and saved bytes are deleted, and the file
     * stays exactly as it is now. Only for the user's explicit "Forget" in Settings, because it
     * deletes the only way back (see [TagRecovery.abandon]). Refused while any queued request names
     * the file: that request's save or recovery owns the record.
     */
    suspend fun forget(record: JournalRecord): Boolean {
        restoredSignal.await()
        val forgotten = TagWriteStoreLock.withLock {
            // Checked under the lock: a request enqueued meanwhile opens its files only under it too.
            if (requests.any { record.target in it.keys }) return@withLock false
            try {
                withContext(io) { backend.recovery.abandon(record) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
        refreshRecovery()
        return forgotten
    }
```

A queued request with `stopRequested` becomes first later. `step` then finishes it as STOPPED before it opens any file (`current.stopRequested -> finishRemaining(...)`), so no other change is needed. Add any imports that are missing (`JournalRecord`, `CancellationException`).

- [ ] **Step 5: Run the tests**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagWriteCoordinatorTest*'`
Expected: PASS.

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 650 tests (644 + 6).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagWriteCoordinator.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagWriteTestBackend.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagWriteCoordinatorTest.kt
git commit -m "feat(tags): a save can be stopped from its own editor, forgotten on request, and its report is never dropped"
```

---

### Task 3: One coordinator per process on both platforms, and a checkpoint on iOS

**Android.** The coordinator lives in an Activity-scoped `ViewModel`, so:
- finishing the Activity cancels `viewModelScope` and stops a batch in silence;
- a second `MainActivity` (a third-party start without `NEW_TASK`) builds a second coordinator. Its restore then prunes the first one's key and cover files.

The owner becomes a process singleton on a `SupervisorJob` + `Dispatchers.Main.immediate` scope. Checkpoints still go into each live Activity's `SavedStateHandle`, so process death restores a batch exactly as before:
- a small `CheckpointFanOut` writes each checkpoint to every attached handle;
- an Activity that attaches later receives the latest checkpoint at once.

**iOS.** Today the coordinator keeps no checkpoint (`checkpoints = false`), so a save cut short by suspension is rolled back silently at the next launch. It gets:
- a checkpoint file in `Application Support/tag-write-requests/state/checkpoint` (the `encodeTagWriteKeys` format, which survives any string);
- a UIKit background task while a request is active, so leaving the app mid-batch does not suspend it between files.

Both platforms also expose a small `TagWriteAccess` handle for the common code:
- the coordinator;
- how a track maps to its write key (Android: `audioUri`; iOS: the track id, and none for a Music-library item);
- what a READ_ONLY result means on this platform.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagWriteOwners.kt`
- Modify: `composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.android.kt` (`TagWriteViewModel`, new `AndroidTagWrites`, `rememberTagWriteAccess` actual)
- Modify: `composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.ios.kt` (`IosTagWrites`, `rememberTagWriteAccess` actual)
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/CheckpointFanOutTest.kt`

**Interfaces:**
- Consumes:
  - `TagWriteCoordinator(backend, scope, io, restored, save, …, checkpoints)`;
  - `encodeTagWriteKeys` / `decodeTagWriteKeys`;
  - `IosTagFiles.requestFiles(name: String): IosPrivateFiles?` (`read(name)`, `write(name, bytes)`, `delete(name)`).
- Produces:
  - `internal class CheckpointFanOut { fun save(state: List<String>); fun attach(owner: Any, sink: (List<String>) -> Unit); fun detach(owner: Any) }`
  - `internal class TagWriteAccess(val coordinator: TagWriteCoordinator<*>, val readOnlyIsMusicLibrary: Boolean, private val key: (TrackDescriptor) -> String?) { fun keyOf(track: TrackDescriptor): String? }`
  - `@Composable internal expect fun rememberTagWriteAccess(): TagWriteAccess?`

- [ ] **Step 1: Write the failing test**

`CheckpointFanOutTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

internal class CheckpointFanOutTest {

    @Test
    fun everyAttachedOwnerReceivesEachCheckpointAndALateOwnerTheLatest() {
        val fanOut = CheckpointFanOut()
        val first = ArrayList<List<String>>()
        val second = ArrayList<List<String>>()
        fanOut.attach("a") { first += it }
        fanOut.save(listOf("x"))
        fanOut.attach("b") { second += it }
        fanOut.save(listOf("y"))
        fanOut.detach("a")
        fanOut.save(listOf("z"))
        assertEquals(listOf(listOf("x"), listOf("y")), first)
        assertEquals(listOf(listOf("x"), listOf("y"), listOf("z")), second)
    }

    @Test
    fun anOwnerAttachedBeforeAnyCheckpointReceivesNothingYet() {
        val fanOut = CheckpointFanOut()
        val seen = ArrayList<List<String>>()
        fanOut.attach("a") { seen += it }
        assertEquals(emptyList(), seen)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*CheckpointFanOutTest*'`
Expected: compilation FAILS (`CheckpointFanOut` unresolved).

- [ ] **Step 3: Implement the common part**

`TagWriteOwners.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Hands each coordinator checkpoint to every live owner of saved state. On Android that is every
 * Activity's `SavedStateHandle`: whichever one the system restores after process death holds the
 * latest batch. An owner that attaches late gets the latest checkpoint at once.
 */
internal class CheckpointFanOut {
    private val sinks = LinkedHashMap<Any, (List<String>) -> Unit>()
    private var latest: List<String>? = null

    fun save(state: List<String>) {
        latest = state
        sinks.values.forEach { it(state) }
    }

    fun attach(owner: Any, sink: (List<String>) -> Unit) {
        sinks[owner] = sink
        latest?.let(sink)
    }

    fun detach(owner: Any) {
        sinks.remove(owner)
    }
}

/**
 * What common code needs from a platform's tag saving: the one coordinator, the key a track is
 * written under, and what a READ_ONLY file means here. On iOS that is a Music-library item, which
 * no app may write. On Android it is a read-only volume.
 */
internal class TagWriteAccess(
    val coordinator: TagWriteCoordinator<*>,
    val readOnlyIsMusicLibrary: Boolean,
    private val key: (TrackDescriptor) -> String?,
) {
    /** Null when this platform has no way to write the track at all. */
    fun keyOf(track: TrackDescriptor): String? = key(track)
}

/** The process's tag-save access, or null where saving is impossible (no Activity, a malformed sandbox). */
@Composable
internal expect fun rememberTagWriteAccess(): TagWriteAccess?
```

- [ ] **Step 4: Implement the Android owner**

In `TrackTagWriter.android.kt`:

1. Replace `TagWriteViewModel` with the view model and object below.
2. Add the imports: `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.SupervisorJob`.
3. Delete the now-unused `viewModelScope` import if nothing else uses it.

```kotlin
/**
 * The one coordinator over the store, for the life of the process. An Activity that finishes no
 * longer stops a batch. A second MainActivity (started into another task) shares this coordinator
 * instead of starting one whose restore would delete this one's key and cover files. Checkpoints
 * still go to every live Activity's saved state, so process death restores a batch as before.
 * Main thread only, like every coordinator call.
 */
internal object AndroidTagWrites {
    private const val CHECKPOINT = "tag-writes"
    private val checkpoints = CheckpointFanOut()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var coordinator: TagWriteCoordinator<IntentSender>? = null

    /** [handle]'s checkpoint restores only the coordinator it creates; a later handle just receives checkpoints. */
    fun attach(context: Context, handle: SavedStateHandle, owner: Any): TagWriteCoordinator<IntentSender> {
        checkpoints.attach(owner) { handle[CHECKPOINT] = ArrayList(it) }
        coordinator?.let { return it }
        val backend = AndroidTagWriteBackend(context.applicationContext)
        val created = TagWriteCoordinator(
            backend = backend,
            scope = scope,
            io = Dispatchers.IO,
            restored = handle.get<ArrayList<String>>(CHECKPOINT),
            save = checkpoints::save,
        )
        coordinator = created
        // Eagerly but off the main thread. A save that comes first prepares the store itself.
        scope.launch(Dispatchers.IO) {
            try {
                backend.prepareStore()
            } catch (_: Exception) {
                // Retried by the first save, which fails if it fails again.
            }
        }
        return created
    }

    fun detach(owner: Any) = checkpoints.detach(owner)
}

/** Connects an Activity's saved state to [AndroidTagWrites]; it owns nothing itself. */
internal class TagWriteViewModel(context: Context, handle: SavedStateHandle) : ViewModel() {
    val coordinator = AndroidTagWrites.attach(context, handle, this)

    override fun onCleared() {
        AndroidTagWrites.detach(this)
    }
}
```

Add after `tagWriteCoordinator()`:

```kotlin
@Composable
internal actual fun rememberTagWriteAccess(): TagWriteAccess? {
    val coordinator = tagWriteCoordinator() ?: return null
    return remember(coordinator) {
        TagWriteAccess(coordinator, readOnlyIsMusicLibrary = false) { track ->
            track.audioUri?.takeIf(String::isNotBlank)
        }
    }
}
```

Update the KDoc of `AndroidTagWriteBackend` or of the file if it still says the view model owns the coordinator.

- [ ] **Step 5: Implement the iOS checkpoint and background task**

In `TrackTagWriter.ios.kt`:
1. Replace `IosTagWrites` with the object and functions below.
2. Add the imports: `platform.UIKit.UIApplication`, `platform.UIKit.UIBackgroundTaskInvalid`, `kotlinx.cinterop.ExperimentalForeignApi`, `io.github.nikitasud.latentjam.library.IosPrivateFiles` (already imported).

```kotlin
/**
 * The one live coordinator over the store, for the life of the process. A sheet leaving
 * composition cannot cancel a save or lose its report, and a second coordinator would delete this
 * one's key and cover files when it starts. Its checkpoint file lets a batch that iOS suspended
 * or ended resume at the next launch, where it finishes the interrupted file before going on.
 */
private object IosTagWrites {
    val coordinator: TagWriteCoordinator<Nothing>? by lazy {
        val store = IosTagFiles.store() ?: return@lazy null
        val keys = IosTagFiles.requestFiles("keys") ?: return@lazy null
        val covers = IosTagFiles.requestFiles("covers") ?: return@lazy null
        val state = IosTagFiles.requestFiles("state") ?: return@lazy null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Eagerly but off the main thread. A save that comes first prepares the store itself.
        scope.launch(Dispatchers.IO) {
            try {
                IosTagFiles.prepareStore()
            } catch (_: Exception) {
                // Retried by the first save, which fails if it fails again.
            }
        }
        TagWriteCoordinator(
            IosTagWriteBackend(store, keys, covers),
            scope,
            Dispatchers.IO,
            restored = readCheckpoint(state),
            save = { saved -> writeCheckpoint(state, saved) },
        ).also { keepRunningInBackground(it, scope) }
    }
}

private const val CHECKPOINT = "checkpoint"

/** A checkpoint that cannot be read restores nothing: the journals still protect every interrupted file. */
private fun readCheckpoint(files: IosPrivateFiles): List<String>? = try {
    files.read(CHECKPOINT)?.let(::decodeTagWriteKeys)
} catch (_: Exception) {
    null
}

private fun writeCheckpoint(files: IosPrivateFiles, saved: List<String>) {
    if (saved.isEmpty()) files.delete(CHECKPOINT) else files.write(CHECKPOINT, encodeTagWriteKeys(saved))
}

/**
 * Keeps the app running while a save is queued or in flight, so leaving the app mid-batch does not
 * suspend it between files. When iOS runs out of background time the task just ends. The journal
 * protects the file being written, and the checkpoint resumes the rest at the next launch.
 */
@OptIn(ExperimentalForeignApi::class)
private fun keepRunningInBackground(coordinator: TagWriteCoordinator<Nothing>, scope: CoroutineScope) {
    var task = UIBackgroundTaskInvalid
    fun end() {
        if (task != UIBackgroundTaskInvalid) {
            UIApplication.sharedApplication.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
    }
    scope.launch {
        coordinator.active.collect { active ->
            if (active && task == UIBackgroundTaskInvalid) {
                task = UIApplication.sharedApplication.beginBackgroundTaskWithName("tag-save") { end() }
            } else if (!active) {
                end()
            }
        }
    }
}

@Composable
internal actual fun rememberTagWriteAccess(): TagWriteAccess? {
    val coordinator = IosTagWrites.coordinator ?: return null
    return remember(coordinator) {
        // Imported tracks are written by their path under Documents; a Music-library item has no file to write.
        TagWriteAccess(coordinator, readOnlyIsMusicLibrary = true) { track ->
            track.id.value.takeUnless(IosTagFiles::isMusicLibraryTrack)
        }
    }
}
```

`IosTagFiles.requestFiles` creates `tag-write-requests/<name>`. Check that it accepts `"state"`: its KDoc or regex must allow the name. If it only allows `keys`/`covers`, widen it to any name matching its safe-name pattern, and add a line to `IosTagFilesTest`.

- [ ] **Step 6: Run the tests and both compiles**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 652 tests.

Run: `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q`
Expected: success.

Run: `./gradlew :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: success.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagWriteOwners.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/CheckpointFanOutTest.kt composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.android.kt composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.ios.kt
git commit -m "feat(tags): a batch of tag saves outlives its screen, and iOS resumes one the system cut short"
```

If `IosTagFiles` needed widening, add `core/library/src/iosMain/kotlin/io/github/nikitasud/latentjam/library/IosTagFiles.kt` and its test to the same commit.

---

### Task 4: One save seam for both editors, and a reason in words for every file not saved

Two copies of `rememberTagWriter` (Android, iOS) turn a one-file report into a `TagWriteOutcome`. Both editors now need more:
- many files;
- progress;
- Stop;
- the exact per-file result;
- a report that arrived while the sheet was being recreated.

This task replaces both copies with one common `TagSaver` over `TagWriteAccess`. It also adds the `TagProblem` vocabulary, so every file not saved is explained in the user's terms. That covers plan-2 finding M8: a read-only volume and an iOS Music-library item each get their own text.

The single-track sheet moves onto `TagSaver` here, with the same five fields. Task 7 extends it.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagSaves.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.kt` (delete `TagWriteOutcome`, `tagWriteOutcome`, `expect rememberTagWriter`; keep `FileWriteStatus`, `FileWriteResult`, `TagWriteReport` and the file's KDoc about why the file itself is written, moved onto `FileWriteStatus`)
- Modify: `composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.android.kt` (delete `actual fun rememberTagWriter`)
- Modify: `composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.ios.kt` (delete `actual fun rememberTagWriter`)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackInfoSheet.kt` (use `rememberTagSaver`)
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagSaverTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagWriteCoordinatorTest.kt` (three `tagWriteOutcome` assertions)

**Interfaces:**
- Consumes:
  - `TagWriteAccess` (Task 3);
  - `TagWriteCoordinator.enqueue / listen / unlisten / stop(id) / unclaimed / acknowledge / progress` (Task 2);
  - `TagWriteReport(kind, results, id)`.
- Produces:
  - `internal enum class TagProblem { UNSUPPORTED_FORMAT, UNUSUAL_LAYOUT, DAMAGED, PROTECTED, TAGS_TOO_LARGE, BAD_NUMBER, NUMBER_UNREADABLE, BAD_IMAGE, READ_ONLY_STORAGE, MUSIC_LIBRARY, UNREADABLE, NO_SPACE, INTERRUPTED, NOT_ALLOWED, MISSING, STOPPED, CANCELLED, FAILED, UNDONE, CHANGED_ELSEWHERE }`
  - `internal fun tagProblemOf(refusal: TagRefusal): TagProblem`
  - `internal fun tagProblemOf(status: FileWriteStatus, refusal: TagRefusal?, readOnlyIsMusicLibrary: Boolean): TagProblem?` (null = saved)
  - `internal data class TagSaveEntry(val key: String, val status: FileWriteStatus, val refusal: TagRefusal? = null, val newLength: Long? = null, val problem: TagProblem? = null) { val saved: Boolean }`
  - `internal data class TagSaveResult(val entries: List<TagSaveEntry>)`, with:
    - `val savedCount: Int`;
    - `val notChanged: List<TagSaveEntry>`;
    - `val cancelled: Boolean`;
    - `fun toSaveable(): List<String>`;
    - companion `fun of(report: TagWriteReport, readOnlyIsMusicLibrary: Boolean): TagSaveResult`.
  - `internal fun tagSaveResultOf(saved: List<String>): TagSaveResult?`
  - `internal class TagSaver(val access: TagWriteAccess, private val mine: SnapshotStateList<Long>, private val onFinished: (TagSaveResult) -> Unit)`, with:
    - `val busy: Boolean`;
    - `fun owns(requestId: Long): Boolean`;
    - `fun start(tracks: List<TrackDescriptor>, edits: TagEdits)`;
    - `fun stop()`;
    - `fun attach()`;
    - `fun detach()`;
    - `fun claim(unclaimed: List<TagWriteReport>)`.
  - `@Composable internal fun rememberTagSaver(onFinished: (TagSaveResult) -> Unit): TagSaver?`
  - `@Composable internal fun TagSaver.progress(): TagWriteProgress?` (this saver's request only)
  - `@Composable internal fun tagProblemText(problem: TagProblem): String`

- [ ] **Step 1: Write the failing tests**

`TagSaverTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class TagSaverTest {

    private fun track(key: String) = TrackDescriptor(TrackId(key), audioUri = key)

    private fun TestScope.saverFor(
        backend: TestTagWriteBackend,
        results: MutableList<TagSaveResult>,
        mine: SnapshotStateList<Long> = mutableStateListOf(),
    ): TagSaver {
        val coordinator = TagWriteCoordinator(
            backend,
            CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler)),
            StandardTestDispatcher(testScheduler),
            checkpoints = false,
        )
        val access = TagWriteAccess(coordinator, readOnlyIsMusicLibrary = false) { it.audioUri }
        return TagSaver(access, mine) { results += it }
    }

    private fun TagSaver.deliverCompleted() {
        access.coordinator.completed.value?.let { access.coordinator.deliver(it.id) }
    }

    @Test
    fun aSaveReportsEveryFileAndForgetsItsRequest() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val results = ArrayList<TagSaveResult>()
        val saver = saverFor(backend, results)
        runCurrent()
        saver.start(listOf(track("a"), track("b")), TagEdits(title = "New"))
        assertTrue(saver.busy)
        runCurrent()
        saver.deliverCompleted()
        assertEquals(2, results.single().savedCount)
        assertTrue(results.single().notChanged.isEmpty())
        assertFalse(saver.busy)
    }

    @Test
    fun emptyEditsFinishAtOnceWithoutQueueing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val results = ArrayList<TagSaveResult>()
        val saver = saverFor(backend, results)
        saver.start(listOf(track("a")), TagEdits())
        assertEquals(FileWriteStatus.UNCHANGED, results.single().entries.single().status)
        assertFalse(saver.busy)
    }

    @Test
    fun aTrackWithNoWayToWriteItIsReportedReadOnlyWithoutQueueing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        val results = ArrayList<TagSaveResult>()
        val saver = saverFor(backend, results)
        saver.start(listOf(TrackDescriptor(TrackId("x"))), TagEdits(title = "New"))
        val entry = results.single().entries.single()
        assertEquals(FileWriteStatus.READ_ONLY, entry.status)
        assertEquals(TagProblem.READ_ONLY_STORAGE, entry.problem)
    }

    @Test
    fun aReportDeliveredWhileTheEditorWasAwayIsClaimedWhenItReturns() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val results = ArrayList<TagSaveResult>()
        val mine = mutableStateListOf<Long>()
        val saver = saverFor(backend, results, mine)
        runCurrent()
        saver.start(listOf(track("a")), TagEdits(title = "New"))
        // The sheet is being recreated: nobody listens when the report is handed out.
        saver.detach()
        runCurrent()
        saver.deliverCompleted()
        assertTrue(results.isEmpty())
        // The recreated sheet: the same saved request ids, a fresh saver over the same coordinator.
        val returned = TagSaver(saver.access, mine) { results += it }
        returned.attach()
        returned.claim(saver.access.coordinator.unclaimed.value)
        assertEquals(1, results.single().savedCount)
        assertTrue(saver.access.coordinator.unclaimed.value.isEmpty())
        assertFalse(returned.busy)
    }

    @Test
    fun anotherEditorsReportIsNotClaimed() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val results = ArrayList<TagSaveResult>()
        val saver = saverFor(backend, results)
        runCurrent()
        assertNotNull(saver.access.coordinator.enqueue(listOf("a"), TagEdits(title = "Elsewhere")))
        runCurrent()
        saver.deliverCompleted()
        saver.claim(saver.access.coordinator.unclaimed.value)
        assertTrue(results.isEmpty())
        assertEquals(1, saver.access.coordinator.unclaimed.value.size)
    }

    @Test
    fun aResultSurvivesBeingSavedAsStrings() {
        val result = TagSaveResult(
            listOf(
                TagSaveEntry("a", FileWriteStatus.SAVED, newLength = 10),
                TagSaveEntry("b\tc", FileWriteStatus.REFUSED, TagRefusal.MP4_DRM_PROTECTED, problem = TagProblem.PROTECTED),
            ),
        )
        assertEquals(result, tagSaveResultOf(result.toSaveable()))
        assertNull(tagSaveResultOf(listOf("a", "SAVED")))
    }

    @Test
    fun everyFailureHasAProblemAndEverySuccessNone() {
        val saved = setOf(FileWriteStatus.SAVED, FileWriteStatus.UNCHANGED, FileWriteStatus.RECOVERED)
        for (status in FileWriteStatus.entries) {
            val problem = tagProblemOf(status, null, readOnlyIsMusicLibrary = false)
            if (status in saved) assertNull(problem, "$status") else assertNotNull(problem, "$status")
        }
        assertEquals(TagProblem.MUSIC_LIBRARY, tagProblemOf(FileWriteStatus.READ_ONLY, null, readOnlyIsMusicLibrary = true))
        assertEquals(TagProblem.READ_ONLY_STORAGE, tagProblemOf(FileWriteStatus.READ_ONLY, null, readOnlyIsMusicLibrary = false))
        assertEquals(TagProblem.PROTECTED, tagProblemOf(FileWriteStatus.REFUSED, TagRefusal.MP4_DRM_PROTECTED, false))
        assertEquals(TagProblem.UNUSUAL_LAYOUT, tagProblemOf(FileWriteStatus.REFUSED, null, false))
    }

    @Test
    fun aDismissedPromptIsACancelNotAResult() {
        val cancelled = TagSaveResult(listOf(TagSaveEntry("a", FileWriteStatus.CANCELLED, problem = TagProblem.CANCELLED)))
        assertTrue(cancelled.cancelled)
        val mixed = cancelled.copy(entries = cancelled.entries + TagSaveEntry("b", FileWriteStatus.SAVED))
        assertFalse(mixed.cancelled)
    }
}
```

In `TagWriteCoordinatorTest`, replace the three `tagWriteOutcome` assertions:

```kotlin
        // was: assertEquals(TagWriteOutcome.Failed, tagWriteOutcome(harness.reports.single()))
        assertEquals(TagProblem.NOT_ALLOWED, TagSaveResult.of(harness.reports.single(), readOnlyIsMusicLibrary = false).entries.single().problem)
        // was: assertEquals(TagWriteOutcome.Cancelled, tagWriteOutcome(harness.reports.single()))
        assertEquals(TagProblem.CANCELLED, TagSaveResult.of(harness.reports.single(), readOnlyIsMusicLibrary = false).entries.single().problem)
        // was: assertEquals(TagWriteOutcome.Refused(TagRefusal.UNSUPPORTED_IMAGE), tagWriteOutcome(harness.reports.single()))
        assertEquals(TagProblem.BAD_IMAGE, TagSaveResult.of(harness.reports.single(), readOnlyIsMusicLibrary = false).entries.single().problem)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagSaverTest*'`
Expected: compilation FAILS (`TagSaver`, `TagSaveResult`, `TagProblem` unresolved).

- [ ] **Step 3: Add the English strings**

In `values/strings.xml`, after `tag_recovery_later`:

```xml
    <string name="tag_problem_unsupported_format">LatentJam can't edit this kind of file.</string>
    <string name="tag_problem_damaged">This file looks damaged, so it is left as it is.</string>
    <string name="tag_problem_protected">This file is copy-protected and can't be changed.</string>
    <string name="tag_problem_tags_too_large">The tags would be too large for this file.</string>
    <string name="tag_problem_bad_number">Track and disc numbers must be whole numbers from 1 to 999.</string>
    <string name="tag_problem_number_unreadable">The file's own track or disc number is not a plain number, so its total can't be changed on its own.</string>
    <string name="tag_problem_bad_image">The picture is not a JPEG or PNG image.</string>
    <string name="tag_problem_read_only_storage">This file is on storage that can't be written to.</string>
    <string name="tag_problem_music_library">Songs from the Music library can't be edited, only songs imported into LatentJam.</string>
    <string name="tag_problem_unreadable">This file could not be read.</string>
    <string name="tag_problem_not_allowed">Permission to change the file was not given.</string>
    <string name="tag_problem_missing">The file is no longer there.</string>
    <string name="tag_problem_stopped">Stopped before this file.</string>
    <string name="tag_problem_cancelled">The permission request was closed.</string>
    <string name="tag_problem_undone">An interrupted save was undone: the file is as it was before.</string>
    <string name="tag_problem_changed_elsewhere">Another app changed the file after the save was interrupted, so it was left as it is.</string>
```

The existing `info_edit_refused`, `info_edit_failed`, `info_edit_no_space` and `info_edit_recovery_pending` are already translated and serve `UNUSUAL_LAYOUT`, `FAILED`, `NO_SPACE` and `INTERRUPTED`. `info_edit_unavailable` becomes unused; Task 15 deletes it from all 18 files.

- [ ] **Step 4: Implement `TagSaves.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_failed
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_no_space
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_recovery_pending
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_refused
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_bad_image
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_bad_number
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_cancelled
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_changed_elsewhere
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_damaged
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_missing
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_music_library
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_not_allowed
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_number_unreadable
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_protected
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_read_only_storage
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_stopped
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_tags_too_large
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_undone
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_unreadable
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_unsupported_format
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.jetbrains.compose.resources.stringResource

/** Why a file was not saved, or cannot be edited, in the user's terms. */
internal enum class TagProblem {
    UNSUPPORTED_FORMAT, UNUSUAL_LAYOUT, DAMAGED, PROTECTED, TAGS_TOO_LARGE, BAD_NUMBER, NUMBER_UNREADABLE,
    BAD_IMAGE, READ_ONLY_STORAGE, MUSIC_LIBRARY, UNREADABLE, NO_SPACE, INTERRUPTED, NOT_ALLOWED, MISSING,
    STOPPED, CANCELLED, FAILED, UNDONE, CHANGED_ELSEWHERE,
}

/**
 * A codec refusal as the user sees it. Every refusal protects the file. The grouping only says
 * whether the file is of a kind the app does not edit, laid out in a way it will not risk,
 * damaged, protected, or asked for something invalid. Exhaustive on purpose: a new refusal must
 * choose its words.
 */
internal fun tagProblemOf(refusal: TagRefusal): TagProblem = when (refusal) {
    TagRefusal.UNSUPPORTED_FORMAT -> TagProblem.UNSUPPORTED_FORMAT
    TagRefusal.ID3_UNSUPPORTED_VERSION, TagRefusal.ID3_UNSYNCHRONISED, TagRefusal.ID3_UNKNOWN_HEADER_FLAGS,
    TagRefusal.ID3_UNSUPPORTED_LEGACY_TAG, TagRefusal.ID3_BEFORE_OTHER_CONTAINER, TagRefusal.ID3_UNKNOWN_AUDIO,
    TagRefusal.OGG_MULTIPLE_STREAMS, TagRefusal.OGG_UNKNOWN_CODEC, TagRefusal.MP4_FRAGMENTED,
    TagRefusal.MP4_UNKNOWN_OFFSET_BOX, TagRefusal.MP4_OFFSET_INSIDE_REWRITE -> TagProblem.UNUSUAL_LAYOUT
    TagRefusal.TRUNCATED, TagRefusal.ID3_BAD_EXTENDED_HEADER, TagRefusal.ID3_BAD_FOOTER,
    TagRefusal.ID3_MALFORMED_FRAMES, TagRefusal.ID3_NOT_TAGGABLE, TagRefusal.FLAC_STREAMINFO_NOT_FIRST,
    TagRefusal.FLAC_MALFORMED_METADATA, TagRefusal.OGG_BAD_PAGE_CRC, TagRefusal.OGG_MALFORMED_PAGES,
    TagRefusal.MP4_MALFORMED_ATOMS -> TagProblem.DAMAGED
    TagRefusal.MP4_DRM_PROTECTED -> TagProblem.PROTECTED
    TagRefusal.ID3_TAG_TOO_LARGE, TagRefusal.FLAC_BLOCK_TOO_LARGE, TagRefusal.MP4_TAGS_TOO_LARGE,
    TagRefusal.MP4_OFFSET_OVERFLOW -> TagProblem.TAGS_TOO_LARGE
    TagRefusal.INVALID_NUMBER -> TagProblem.BAD_NUMBER
    TagRefusal.UNREADABLE_NUMBER -> TagProblem.NUMBER_UNREADABLE
    TagRefusal.UNSUPPORTED_IMAGE -> TagProblem.BAD_IMAGE
    // A codec caught its own bug before writing: to the user, the save simply did not happen.
    TagRefusal.PLAN_INCONSISTENT -> TagProblem.FAILED
}

/** Null when the file holds the edit (saved now, already so, or finished from an interrupted save). */
internal fun tagProblemOf(status: FileWriteStatus, refusal: TagRefusal?, readOnlyIsMusicLibrary: Boolean): TagProblem? =
    when (status) {
        FileWriteStatus.SAVED, FileWriteStatus.UNCHANGED, FileWriteStatus.RECOVERED -> null
        FileWriteStatus.REFUSED -> refusal?.let(::tagProblemOf) ?: TagProblem.UNUSUAL_LAYOUT
        FileWriteStatus.NO_SPACE -> TagProblem.NO_SPACE
        FileWriteStatus.RECOVERY_PENDING -> TagProblem.INTERRUPTED
        FileWriteStatus.DENIED -> TagProblem.NOT_ALLOWED
        FileWriteStatus.CANCELLED -> TagProblem.CANCELLED
        FileWriteStatus.MISSING -> TagProblem.MISSING
        FileWriteStatus.READ_ONLY -> if (readOnlyIsMusicLibrary) TagProblem.MUSIC_LIBRARY else TagProblem.READ_ONLY_STORAGE
        FileWriteStatus.STOPPED -> TagProblem.STOPPED
        FileWriteStatus.FAILED -> TagProblem.FAILED
        FileWriteStatus.RESTORED -> TagProblem.UNDONE
        FileWriteStatus.FOREIGN -> TagProblem.CHANGED_ELSEWHERE
    }

/** One file's fate in a finished save. [problem] is null exactly when the file holds the edit. */
internal data class TagSaveEntry(
    val key: String,
    val status: FileWriteStatus,
    val refusal: TagRefusal? = null,
    val newLength: Long? = null,
    val problem: TagProblem? = null,
) {
    val saved: Boolean get() = problem == null
}

/** A finished save, file by file: what the editor reports exactly (spec §6.3). */
internal data class TagSaveResult(val entries: List<TagSaveEntry>) {
    val savedCount: Int get() = entries.count { it.saved }
    val notChanged: List<TagSaveEntry> get() = entries.filterNot { it.saved }

    /** The user closed the consent prompt, so nothing was written. What they just chose, not a result to report. */
    val cancelled: Boolean get() = entries.isNotEmpty() && entries.all { it.status == FileWriteStatus.CANCELLED }

    /** Five strings per file, so any key survives a Bundle: key, status, refusal, new length, problem. */
    fun toSaveable(): List<String> = entries.flatMap { entry ->
        listOf(
            entry.key,
            entry.status.name,
            entry.refusal?.name.orEmpty(),
            entry.newLength?.toString().orEmpty(),
            entry.problem?.name.orEmpty(),
        )
    }

    companion object {
        fun of(report: TagWriteReport, readOnlyIsMusicLibrary: Boolean) = TagSaveResult(
            report.results.map { result ->
                TagSaveEntry(
                    key = result.key,
                    status = result.status,
                    refusal = result.refusal,
                    newLength = result.newLength,
                    problem = tagProblemOf(result.status, result.refusal, readOnlyIsMusicLibrary),
                )
            },
        )
    }
}

/** The result [TagSaveResult.toSaveable] wrote, or null when the list is not one. */
internal fun tagSaveResultOf(saved: List<String>): TagSaveResult? {
    if (saved.size % 5 != 0) return null
    return try {
        TagSaveResult(
            saved.chunked(5).map { (key, status, refusal, length, problem) ->
                TagSaveEntry(
                    key = key,
                    status = FileWriteStatus.valueOf(status),
                    refusal = refusal.takeIf { it.isNotEmpty() }?.let(TagRefusal::valueOf),
                    newLength = length.takeIf { it.isNotEmpty() }?.toLong(),
                    problem = problem.takeIf { it.isNotEmpty() }?.let(TagProblem::valueOf),
                )
            },
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * An editor's handle on its own saves. It remembers its request ids across recreation (the list
 * is saved by [rememberTagSaver]) and so hears back even when the report arrived while it was away:
 * that report waits in [TagWriteCoordinator.unclaimed] and is claimed here.
 *
 * Tracks this platform cannot write at all are reported READ_ONLY without being queued. The
 * editors set such tracks aside before saving (see [readTagFile]), so a request of both kinds does
 * not happen.
 */
internal class TagSaver(
    val access: TagWriteAccess,
    private val mine: SnapshotStateList<Long>,
    private val onFinished: (TagSaveResult) -> Unit,
) {
    val busy: Boolean get() = mine.isNotEmpty()

    fun owns(requestId: Long): Boolean = requestId in mine

    fun start(tracks: List<TrackDescriptor>, edits: TagEdits) {
        val keys = tracks.mapNotNull(access::keyOf).distinct()
        when {
            edits.isEmpty -> finish(keys.map { TagSaveEntry(it, FileWriteStatus.UNCHANGED) })
            keys.isEmpty() -> finish(
                tracks.map { track ->
                    TagSaveEntry(
                        key = track.id.value,
                        status = FileWriteStatus.READ_ONLY,
                        problem = tagProblemOf(FileWriteStatus.READ_ONLY, null, access.readOnlyIsMusicLibrary),
                    )
                },
            )
            else -> {
                val id = access.coordinator.enqueue(keys, edits)
                if (id == null) {
                    finish(keys.map { TagSaveEntry(it, FileWriteStatus.FAILED, problem = TagProblem.FAILED) })
                } else {
                    mine += id
                    listen(id)
                }
            }
        }
    }

    /** Stops this editor's own requests, never the one in front of them. */
    fun stop() {
        mine.toList().forEach { access.coordinator.stop(it) }
    }

    fun attach() {
        mine.toList().forEach(::listen)
    }

    fun detach() {
        mine.toList().forEach(access.coordinator::unlisten)
    }

    /** Takes this editor's reports that were handed out while nobody listened. */
    fun claim(unclaimed: List<TagWriteReport>) {
        for (report in unclaimed) {
            if (report.id !in mine) continue
            access.coordinator.acknowledge(report)
            complete(report)
        }
    }

    private fun listen(id: Long) {
        access.coordinator.listen(id, ::complete)
    }

    private fun complete(report: TagWriteReport) {
        if (mine.remove(report.id)) onFinished(TagSaveResult.of(report, access.readOnlyIsMusicLibrary))
    }

    private fun finish(entries: List<TagSaveEntry>) = onFinished(TagSaveResult(entries))
}

/** A [TagSaver] for this editor, or null where saving is impossible. Its request ids survive recreation. */
@Composable
internal fun rememberTagSaver(onFinished: (TagSaveResult) -> Unit): TagSaver? {
    val access = rememberTagWriteAccess() ?: return null
    val current by rememberUpdatedState(onFinished)
    val mine = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<Long>()
    }
    val saver = remember(access, mine) { TagSaver(access, mine) { current(it) } }
    DisposableEffect(saver) {
        saver.attach()
        onDispose { saver.detach() }
    }
    val unclaimed by access.coordinator.unclaimed.collectAsState()
    LaunchedEffect(saver, unclaimed) { saver.claim(unclaimed) }
    return saver
}

/** This saver's running request's progress; null while it waits its turn or when it has none. */
@Composable
internal fun TagSaver.progress(): TagWriteProgress? {
    val progress by access.coordinator.progress.collectAsState()
    return progress?.takeIf { owns(it.requestId) }
}

@Composable
internal fun tagProblemText(problem: TagProblem): String = stringResource(
    when (problem) {
        TagProblem.UNSUPPORTED_FORMAT -> Res.string.tag_problem_unsupported_format
        TagProblem.UNUSUAL_LAYOUT -> Res.string.info_edit_refused
        TagProblem.DAMAGED -> Res.string.tag_problem_damaged
        TagProblem.PROTECTED -> Res.string.tag_problem_protected
        TagProblem.TAGS_TOO_LARGE -> Res.string.tag_problem_tags_too_large
        TagProblem.BAD_NUMBER -> Res.string.tag_problem_bad_number
        TagProblem.NUMBER_UNREADABLE -> Res.string.tag_problem_number_unreadable
        TagProblem.BAD_IMAGE -> Res.string.tag_problem_bad_image
        TagProblem.READ_ONLY_STORAGE -> Res.string.tag_problem_read_only_storage
        TagProblem.MUSIC_LIBRARY -> Res.string.tag_problem_music_library
        TagProblem.UNREADABLE -> Res.string.tag_problem_unreadable
        TagProblem.NO_SPACE -> Res.string.info_edit_no_space
        TagProblem.INTERRUPTED -> Res.string.info_edit_recovery_pending
        TagProblem.NOT_ALLOWED -> Res.string.tag_problem_not_allowed
        TagProblem.MISSING -> Res.string.tag_problem_missing
        TagProblem.STOPPED -> Res.string.tag_problem_stopped
        TagProblem.CANCELLED -> Res.string.tag_problem_cancelled
        TagProblem.FAILED -> Res.string.info_edit_failed
        TagProblem.UNDONE -> Res.string.tag_problem_undone
        TagProblem.CHANGED_ELSEWHERE -> Res.string.tag_problem_changed_elsewhere
    },
)
```

Then edit `TrackTagWriter.kt`:
1. Delete `TagWriteOutcome`, `tagWriteOutcome` and `expect fun rememberTagWriter`.
2. Move the "Why the file and not the index" and "Consent" KDoc sections onto `FileWriteStatus`, so the reasons stay beside the model.
3. Delete both actual `rememberTagWriter` functions. Keep `TagWriteHost` and everything else.

- [ ] **Step 5: Move `TrackInfoSheet` onto the saver**

In `TrackInfoSheet.kt`:
1. Replace `var saving …`, `var failure …` and the `rememberTagWriter` block with the code below.
2. Delete every `saving = true` / `saving = false`.
3. In the edit column, the failure `AnimatedContent` renders `tagProblemText(it)` instead of `failureMessage(it)`.
4. Delete `failureMessage` and the imports it alone used (`info_edit_*`).

```kotlin
    var failure by remember(track.id) { mutableStateOf<TagProblem?>(null) }
    val saver = rememberTagSaver { result ->
        val entry = result.entries.singleOrNull()
        when {
            entry == null -> failure = TagProblem.FAILED
            entry.saved -> {
                onSaved()
                onDismiss()
            }
            // The user closed the system's permission dialog. They know they did.
            result.cancelled -> Unit
            else -> failure = entry.problem
        }
    }
    val saving = saver?.busy == true
```

and in `onConfirm`:

```kotlin
                    onConfirm = {
                        if (!saving && !edits.isEmpty) {
                            focus.clearFocus()
                            keyboard?.hide()
                            failure = null
                            // Null leaves an untouched field intact; an empty string removes it.
                            if (saver == null) failure = TagProblem.FAILED else saver.start(listOf(track), edits)
                        }
                    },
```

- [ ] **Step 6: Run the tests and both compiles**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 660 tests (652 + 8).

Run: `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q && ./gradlew :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: success, with no reference to `rememberTagWriter` or `TagWriteOutcome` left (`grep -rn "TagWriteOutcome\|rememberTagWriter" composeApp/src` prints nothing).

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagSaves.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.kt composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.android.kt composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.ios.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackInfoSheet.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagSaverTest.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagWriteCoordinatorTest.kt
git commit -m "feat(tags): every file a save leaves alone is named with its reason, and an editor hears back even after it was recreated"
```

---

### Task 5: The editors read what is in the file itself

Spec §6.1 says the editor is filled from `TagSnapshot`, so it shows what is actually in the file, album artist and lyrics included. Editability is known **before** typing.

Today the editor starts from `TrackDescriptor`, which is MediaStore's opinion and holds five fields. This task adds the read seam. Both editors call it when editing starts:
- it reads the file's tag region only (kilobytes; spec §7 "Opening the editor");
- it answers either a snapshot or the reason the file cannot be edited.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagFileRead.kt`
- Create: `composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/TagFileRead.android.kt`
- Create: `composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/TagFileRead.ios.kt`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagFileReadTest.kt`

**Interfaces:**
- Consumes:
  - `TagCodecs.read(source: RandomAccessSource): TagSnapshot?` (null = no codec for the content);
  - Android `FileChannelSource(channel)` (TrackGenres.android.kt);
  - iOS `FileHandleSource(handle)` (TrackGenres.ios.kt);
  - `IosTagFiles.isMusicLibraryTrack(key)`;
  - `tagProblemOf(refusal)` (Task 4).
- Produces:
  - `internal sealed interface TagFileRead { data class Ready(val snapshot: TagSnapshot); data class NotEditable(val problem: TagProblem) }`
  - `internal fun tagFileReadOf(snapshot: TagSnapshot?): TagFileRead`
  - `internal expect suspend fun readTagFile(track: TrackDescriptor): TagFileRead`: never throws except on cancellation, and runs off the caller's thread.

- [ ] **Step 1: Write the failing test**

`TagFileReadTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.TagFormat
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

internal class TagFileReadTest {

    @Test
    fun aFileNoCodecKnowsIsNotEditableAsAnUnsupportedFormat() {
        assertEquals(TagFileRead.NotEditable(TagProblem.UNSUPPORTED_FORMAT), tagFileReadOf(null))
    }

    @Test
    fun aFileTheCodecWouldRefuseSaysWhyBeforeAnyTyping() {
        val protected = TagSnapshot(TagFormat.MP4, "MP4", refusal = TagRefusal.MP4_DRM_PROTECTED)
        assertEquals(TagFileRead.NotEditable(TagProblem.PROTECTED), tagFileReadOf(protected))
    }

    @Test
    fun anEditableFileIsReadyWithItsSnapshot() {
        val snapshot = TagSnapshot(TagFormat.FLAC, "FLAC", title = "Song", albumArtist = "Various Artists")
        assertEquals(TagFileRead.Ready(snapshot), tagFileReadOf(snapshot))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagFileReadTest*'`
Expected: compilation FAILS (`TagFileRead` unresolved).

- [ ] **Step 3: Implement the common part**

`TagFileRead.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** What the editor learns from a file before the user types anything (spec §6.1). */
internal sealed interface TagFileRead {
    data class Ready(val snapshot: TagSnapshot) : TagFileRead
    data class NotEditable(val problem: TagProblem) : TagFileRead
}

/** A codec's reading of a file: no codec at all, a refusal, or a snapshot to edit. */
internal fun tagFileReadOf(snapshot: TagSnapshot?): TagFileRead {
    val refusal = snapshot?.refusal
    return when {
        snapshot == null -> TagFileRead.NotEditable(TagProblem.UNSUPPORTED_FORMAT)
        refusal != null -> TagFileRead.NotEditable(tagProblemOf(refusal))
        else -> TagFileRead.Ready(snapshot)
    }
}

/**
 * Reads [track]'s tags from its file through [TagCodecs], never the whole file: random access lets
 * each codec read only its tag region. A file that cannot be opened or parsed is
 * [TagProblem.UNREADABLE]. The editor then shows that instead of fields it could not fill honestly.
 */
internal expect suspend fun readTagFile(track: TrackDescriptor): TagFileRead
```

- [ ] **Step 4: Implement the platform readers**

`TagFileRead.android.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.net.Uri
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

internal actual suspend fun readTagFile(track: TrackDescriptor): TagFileRead = withContext(Dispatchers.IO) {
    try {
        val uri = track.audioUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
            ?: return@withContext TagFileRead.NotEditable(TagProblem.UNREADABLE)
        val descriptor = AndroidAppContext.value.contentResolver.openFileDescriptor(uri, "r")
            ?: return@withContext TagFileRead.NotEditable(TagProblem.UNREADABLE)
        descriptor.use {
            // Not closed: closing a stream over this descriptor closes the descriptor itself, which is
            // the ParcelFileDescriptor's job.
            val channel = FileInputStream(it.fileDescriptor).channel
            tagFileReadOf(TagCodecs.read(FileChannelSource(channel)))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TagFileRead.NotEditable(TagProblem.UNREADABLE)
    }
}
```

`TagFileRead.ios.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.IosTagFiles
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileHandle
import platform.Foundation.NSURL
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readTagFile(track: TrackDescriptor): TagFileRead = withContext(Dispatchers.IO) {
    // Apple lets no app write the Music library's files; say so rather than "could not read".
    if (IosTagFiles.isMusicLibraryTrack(track.id.value)) return@withContext TagFileRead.NotEditable(TagProblem.MUSIC_LIBRARY)
    try {
        val url = track.audioUri?.takeIf(String::isNotBlank)?.let(NSURL::URLWithString)
        val path = url?.takeIf { it.isFileURL() }?.path ?: return@withContext TagFileRead.NotEditable(TagProblem.UNREADABLE)
        val handle = NSFileHandle.fileHandleForReadingAtPath(path)
            ?: return@withContext TagFileRead.NotEditable(TagProblem.UNREADABLE)
        try {
            tagFileReadOf(TagCodecs.read(FileHandleSource(handle)))
        } finally {
            handle.closeFile()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TagFileRead.NotEditable(TagProblem.UNREADABLE)
    }
}
```

- [ ] **Step 5: Run the tests and both compiles**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 663 tests.

Run: `./gradlew :composeApp:compileAndroidMain --offline -q && ./gradlew :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: success.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagFileRead.kt composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/TagFileRead.android.kt composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/TagFileRead.ios.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagFileReadTest.kt
git commit -m "feat(tags): the tag editor reads the file itself, and knows before any typing when a file cannot be edited"
```

---

### Task 6: A cover picker that hands the editor bytes ready to write

Spec §6.4 says to reuse `rememberPlaylistCoverPicker`, with the §3.4 encoding. The playlist importers downscale to 768 px as JPEG q88 (Android) or at the system default quality (iOS), and return a file reference. A tag cover needs a different rule:
- 1000 px, JPEG q90;
- a PNG within 1000×1000 and under 500 KB is kept byte for byte.

**Plan:**
- Generalize each platform's importer once, with the rule as a parameter.
- On Android, generalize the result-holding `ViewModel` too.
- Both pickers become thin callers of the shared code.

A picked tag cover is stored in app-private `tag-covers/` under a random name. The editor keeps only that reference, which is saveable across rotation, and reads the bytes when it saves. Once the save is enqueued the coordinator has stashed its own copy, so the editor deletes the file. A file left behind by a process death is pruned the next time the picker opens (older than one day).

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PlaylistCoverPicker.kt` (`coverDecodeSample`, `pngSize`, `PNG_HEAD_BYTES`)
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagCoverPicker.kt`
- Create: `composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/CoverImport.android.kt`
- Modify: `composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/PlaylistCoverPicker.android.kt`
- Create: `composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/TagCoverPicker.android.kt`
- Modify: `composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/PlaylistCoverPicker.ios.kt`
- Create: `composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/TagCoverPicker.ios.kt`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagCoverPickerTest.kt`

**Interfaces:**
- Consumes: the existing importers and `PlaylistCoverViewModel` (Android), `PlaylistCoverDelegate` and `importPlaylistCover` (iOS).
- Produces:
  - `internal fun coverDecodeSample(width: Int, height: Int, maxEdge: Int): Int?` (`playlistCoverDecodeSample(w, h)` delegates with `PLAYLIST_COVER_MAX_EDGE`)
  - `internal const val PNG_HEAD_BYTES = 24`
  - `internal fun pngSize(head: ByteArray): Pair<Int, Int>?`
  - `internal const val TAG_COVER_MAX_EDGE = 1000`, `TAG_COVER_JPEG_QUALITY = 90`, `TAG_COVER_PNG_KEEP_BYTES = 500L * 1024`, `TAG_COVER_DIRECTORY = "tag-covers"`, `TAG_COVER_STALE_MS = 24L * 60 * 60 * 1000`
  - `internal fun keepsPickedPng(width: Int, height: Int, byteSize: Long): Boolean`
  - `internal fun isTagCoverReference(reference: String?): Boolean`
  - `internal fun tagCoverMime(reference: String): String`
  - `internal sealed interface TagCoverPick { data class Picked(val reference: String); data object Cancelled; data object Failed }`
  - `internal sealed interface CoverPickOutcome { data class Picked(val reference: String); data object Cancelled; data object Failed }` (common; both platforms' shared pickers report it)
  - `@Composable internal expect fun rememberTagCoverPicker(onResult: (TagCoverPick) -> Unit): () -> Unit`
  - `internal expect suspend fun readTagCover(reference: String): ByteArray?`
  - `internal expect suspend fun deleteTagCover(reference: String)`
  - `internal expect fun tagCoverUri(reference: String): String?`

- [ ] **Step 1: Write the failing tests**

`TagCoverPickerTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TagCoverPickerTest {

    /** A PNG signature and IHDR chunk stating [width]×[height]. */
    private fun pngHead(width: Int, height: Int): ByteArray {
        val head = ByteArray(PNG_HEAD_BYTES)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(head)
        byteArrayOf(0, 0, 0, 13).copyInto(head, 8)
        "IHDR".encodeToByteArray().copyInto(head, 12)
        for (i in 0 until 4) {
            head[16 + i] = (width ushr (24 - 8 * i)).toByte()
            head[20 + i] = (height ushr (24 - 8 * i)).toByte()
        }
        return head
    }

    @Test
    fun aPngStatesItsSizeInItsHeader() {
        assertEquals(1000 to 640, pngSize(pngHead(1000, 640)))
        assertNull(pngSize(pngHead(1000, 640).copyOf(20)))
        assertNull(pngSize(ByteArray(PNG_HEAD_BYTES)))
    }

    @Test
    fun onlyASmallPngWithinTheEdgeIsKeptAsItIs() {
        assertTrue(keepsPickedPng(1000, 1000, TAG_COVER_PNG_KEEP_BYTES - 1))
        assertFalse(keepsPickedPng(1001, 1000, 1000))
        assertFalse(keepsPickedPng(1000, 1001, 1000))
        assertFalse(keepsPickedPng(800, 800, TAG_COVER_PNG_KEEP_BYTES))
        assertFalse(keepsPickedPng(0, 800, 1000))
    }

    @Test
    fun tagCoverReferencesCannotEscapeTheirDirectory() {
        val jpeg = "f08f8630-6120-4aa9-9580-973044632c42.jpg"
        val png = "f08f8630-6120-4aa9-9580-973044632c42.png"
        assertTrue(isTagCoverReference(jpeg))
        assertTrue(isTagCoverReference(png))
        assertEquals("image/png", tagCoverMime(png))
        assertEquals("image/jpeg", tagCoverMime(jpeg))
        listOf(null, "", "../$jpeg", "/tmp/$jpeg", jpeg.uppercase(), "f08f8630-6120-4aa9-9580-973044632c42.gif")
            .forEach { assertFalse(isTagCoverReference(it)) }
    }

    @Test
    fun tagCoversDecodeAtMostTwiceTheirEdge() {
        assertEquals(1, coverDecodeSample(1000, 800, TAG_COVER_MAX_EDGE))
        assertEquals(2, coverDecodeSample(4032, 3024, TAG_COVER_MAX_EDGE))
        assertEquals(playlistCoverDecodeSample(4032, 3024), coverDecodeSample(4032, 3024, PLAYLIST_COVER_MAX_EDGE))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagCoverPickerTest*'`
Expected: compilation FAILS.

- [ ] **Step 3: Implement the common parts**

In `PlaylistCoverPicker.kt`, replace `playlistCoverDecodeSample` with:

```kotlin
/** Decode at no more than twice the output edge, then do the final orientation/scale together. */
internal fun coverDecodeSample(width: Int, height: Int, maxEdge: Int): Int? {
    if (width !in 1..100_000 || height !in 1..100_000) return null
    val edge = maxOf(width, height)
    var sample = 1
    while (edge / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

internal fun playlistCoverDecodeSample(width: Int, height: Int): Int? =
    coverDecodeSample(width, height, PLAYLIST_COVER_MAX_EDGE)

/** Enough of a file to hold a PNG's signature and its IHDR width and height. */
internal const val PNG_HEAD_BYTES = 24

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** The width and height a PNG's IHDR chunk states, or null when [head] is not the start of a PNG. */
internal fun pngSize(head: ByteArray): Pair<Int, Int>? {
    if (head.size < PNG_HEAD_BYTES) return null
    if (PNG_SIGNATURE.indices.any { head[it] != PNG_SIGNATURE[it] }) return null
    if (head.decodeToString(12, 16) != "IHDR") return null
    fun int(at: Int): Int = (0 until 4).fold(0) { value, i -> (value shl 8) or (head[at + i].toInt() and 0xFF) }
    return int(16) to int(20)
}
```

`TagCoverPicker.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/**
 * A cover picked for writing into tags (spec §3.4, §6.4). [Picked.reference] names a file in the
 * app's private `tag-covers` directory: saveable across rotation, read as bytes when the save starts.
 */
internal sealed interface TagCoverPick {
    data class Picked(val reference: String) : TagCoverPick
    data object Cancelled : TagCoverPick
    data object Failed : TagCoverPick
}

internal const val TAG_COVER_MAX_EDGE = 1000
internal const val TAG_COVER_JPEG_QUALITY = 90
internal const val TAG_COVER_PNG_KEEP_BYTES = 500L * 1024
internal const val TAG_COVER_DIRECTORY = "tag-covers"

/** A picked cover nobody saved (the process died with the editor open) is pruned after this long. */
internal const val TAG_COVER_STALE_MS = 24L * 60 * 60 * 1000

/**
 * A PNG already within 1000×1000 and under 500 KB is written byte for byte. Every other image is
 * re-encoded as JPEG q90, which also drops its EXIF, including GPS.
 */
internal fun keepsPickedPng(width: Int, height: Int, byteSize: Long): Boolean =
    width in 1..TAG_COVER_MAX_EDGE && height in 1..TAG_COVER_MAX_EDGE && byteSize in 1 until TAG_COVER_PNG_KEEP_BYTES

private val TAG_COVER_REFERENCE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)")

internal fun isTagCoverReference(reference: String?): Boolean = reference != null && TAG_COVER_REFERENCE.matches(reference)

internal fun tagCoverMime(reference: String): String = if (reference.endsWith(".png")) "image/png" else "image/jpeg"

/** What a platform's system picker reports; each picker maps it to its own result type. */
internal sealed interface CoverPickOutcome {
    data class Picked(val reference: String) : CoverPickOutcome
    data object Cancelled : CoverPickOutcome
    data object Failed : CoverPickOutcome
}

/** Opens the system photo picker on explicit invocation; results arrive on the UI thread. */
@Composable
internal expect fun rememberTagCoverPicker(onResult: (TagCoverPick) -> Unit): () -> Unit

/** The picked cover's bytes, or null when the reference is not one or its file is gone. */
internal expect suspend fun readTagCover(reference: String): ByteArray?

internal expect suspend fun deleteTagCover(reference: String)

/** A URI the image loader can show the picked cover from. */
internal expect fun tagCoverUri(reference: String): String?
```

- [ ] **Step 4: Generalize the Android importer and result holder**

1. Create `CoverImport.android.kt`.
2. Move into it from `PlaylistCoverPicker.android.kt`:
   - `PlaylistCoverViewModel`, renamed `CoverPickViewModel`;
   - the body of `importPlaylistCover`, generalized as below.
3. The view model keeps its phases, `SavedStateHandle` keys and process-death behaviour exactly. It gains:
   - an `import: (ContentResolver, Uri) -> String?`;
   - `isReference: (String) -> Boolean`;
   - `delete: suspend (String) -> Unit`.
4. It reports a neutral `CoverPickOutcome`.
5. The launcher/lifecycle code of `rememberPlaylistCoverPicker` moves into `rememberSystemCoverPicker`, keyed so the playlist and tag pickers get separate view models.

```kotlin
/** How a picked image becomes a stored cover. */
internal class CoverImportRule(val maxEdge: Int, val jpegQuality: Int, val keepSmallPng: Boolean)

internal val PLAYLIST_COVER_RULE = CoverImportRule(PLAYLIST_COVER_MAX_EDGE, jpegQuality = 88, keepSmallPng = false)
internal val TAG_COVER_RULE = CoverImportRule(TAG_COVER_MAX_EDGE, TAG_COVER_JPEG_QUALITY, keepSmallPng = true)

/**
 * The system photo picker, with its decode held by a [CoverPickViewModel] under [key], so a
 * configuration change replaces only the launcher, never the running decode.
 */
@Composable
internal fun rememberSystemCoverPicker(
    key: String,
    import: (ContentResolver, Uri) -> String?,
    isReference: (String) -> Boolean,
    delete: suspend (String) -> Unit,
    onResult: (CoverPickOutcome) -> Unit,
): () -> Unit {
    val activity = LocalActivity.current as? ComponentActivity
        ?: return { onResult(CoverPickOutcome.Failed) }
    val model = remember(activity, key) {
        val factory = viewModelFactory {
            initializer {
                CoverPickViewModel(activity.applicationContext, createSavedStateHandle(), import, isReference, delete)
            }
        }
        ViewModelProvider(activity, factory)[key, CoverPickViewModel::class.java]
    }
    // … the rest is today's rememberPlaylistCoverPicker body, with PlaylistCoverPickResult replaced
    // by CoverPickOutcome, unchanged.
}

/** Imports [uri] into [directory] under [rule]; the new file's name, or null. */
internal fun importCoverImage(resolver: ContentResolver, uri: Uri, directory: File, rule: CoverImportRule): String? {
    val encodedSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    if (encodedSize != null && (encodedSize == 0L || encodedSize > 64L * 1024L * 1024L)) return null
    if (rule.keepSmallPng && encodedSize != null && encodedSize > 0) {
        val size = resolver.openInputStream(uri)?.use { pngSize(it.readAtMost(PNG_HEAD_BYTES)) }
        if (size != null && keepsPickedPng(size.first, size.second, encodedSize)) {
            return storeCover(directory, "png") { output ->
                resolver.openInputStream(uri)?.use { it.copyTo(output) } != null
            }
        }
    }
    // … today's decode path, unchanged except:
    //   - playlistCoverDecodeSample(w, h) → coverDecodeSample(w, h, rule.maxEdge)
    //   - the "* 2" sanity check and the scale use rule.maxEdge
    //   - the JPEG is written by storeCover(directory, "jpg") { rendered.compress(JPEG, rule.jpegQuality, it) }
}

/** Written through a temp file and a rename, so a torn write never sits under a cover's name. */
private inline fun storeCover(directory: File, extension: String, write: (OutputStream) -> Boolean): String? {
    if (!directory.isDirectory && !directory.mkdirs()) return null
    val reference = "${UUID.randomUUID()}.$extension"
    val pending = File.createTempFile("cover-", ".tmp", directory)
    try {
        if (!pending.outputStream().use(write)) return null
        return if (pending.renameTo(File(directory, reference))) reference else null
    } finally {
        pending.delete()
    }
}

private fun InputStream.readAtMost(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var done = 0
    while (done < count) {
        val read = read(buffer, done, count - done)
        if (read <= 0) break
        done += read
    }
    return buffer.copyOf(done)
}
```

`PlaylistCoverPicker.android.kt` keeps `playlistCoverUri`, `deletePlaylistCover` and `coverFile`, and becomes:

```kotlin
@Composable
internal actual fun rememberPlaylistCoverPicker(onResult: (PlaylistCoverPickResult) -> Unit): () -> Unit =
    rememberSystemCoverPicker(
        key = "playlist-cover",
        import = { resolver, uri ->
            importCoverImage(resolver, uri, File(AndroidAppContext.value.filesDir, PLAYLIST_COVER_DIRECTORY), PLAYLIST_COVER_RULE)
        },
        isReference = ::isPlaylistCoverReference,
        delete = { deletePlaylistCover(it) },
    ) { outcome ->
        onResult(
            when (outcome) {
                is CoverPickOutcome.Picked -> PlaylistCoverPickResult.Selected(outcome.reference)
                CoverPickOutcome.Cancelled -> PlaylistCoverPickResult.Cancelled
                CoverPickOutcome.Failed -> PlaylistCoverPickResult.Failed
            },
        )
    }
```

`TagCoverPicker.android.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.net.Uri
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun tagCoverDirectory(): File = File(AndroidAppContext.value.filesDir, TAG_COVER_DIRECTORY)

private fun tagCoverFile(reference: String): File? =
    reference.takeIf(::isTagCoverReference)?.let { File(tagCoverDirectory(), it) }

@Composable
internal actual fun rememberTagCoverPicker(onResult: (TagCoverPick) -> Unit): () -> Unit {
    val pick = rememberSystemCoverPicker(
        key = "tag-cover",
        import = { resolver, uri ->
            pruneStaleTagCovers()
            importCoverImage(resolver, uri, tagCoverDirectory(), TAG_COVER_RULE)
        },
        isReference = ::isTagCoverReference,
        delete = { deleteTagCover(it) },
    ) { outcome ->
        onResult(
            when (outcome) {
                is CoverPickOutcome.Picked -> TagCoverPick.Picked(outcome.reference)
                CoverPickOutcome.Cancelled -> TagCoverPick.Cancelled
                CoverPickOutcome.Failed -> TagCoverPick.Failed
            },
        )
    }
    return pick
}

/** Covers picked in an editor the process lost; a live editor's pick is always younger. */
private fun pruneStaleTagCovers() {
    val cutoff = System.currentTimeMillis() - TAG_COVER_STALE_MS
    tagCoverDirectory().listFiles()?.forEach { file ->
        if (file.isFile && file.lastModified() < cutoff) file.delete()
    }
}

internal actual suspend fun readTagCover(reference: String): ByteArray? = withContext(Dispatchers.IO) {
    tagCoverFile(reference)?.takeIf { it.isFile }?.readBytes()
}

internal actual suspend fun deleteTagCover(reference: String) {
    withContext(Dispatchers.IO) { tagCoverFile(reference)?.delete() }
}

internal actual fun tagCoverUri(reference: String): String? = tagCoverFile(reference)?.let { Uri.fromFile(it).toString() }
```

- [ ] **Step 5: Generalize the iOS importer**

In `PlaylistCoverPicker.ios.kt`:

1. Rename `importPlaylistCover(url)` to `importCoverImage(url: NSURL, directory: String, maxEdge: Int, jpegQuality: Double?, keepSmallPng: Boolean): String?`. Use `directory` in place of `coverDirectory` and `maxEdge` in place of `PLAYLIST_COVER_MAX_EDGE`.

2. Before the ImageIO path, when `keepSmallPng` and the size is known:

```kotlin
    if (keepSmallPng && bytes != null) {
        val head = NSFileHandle.fileHandleForReadingAtPath(sourcePath)?.let { handle ->
            try {
                handle.readDataOfLength(PNG_HEAD_BYTES.toULong()).toByteArray()
            } finally {
                handle.closeFile()
            }
        }
        val size = head?.let(::pngSize)
        if (size != null && keepsPickedPng(size.first, size.second, bytes.toLong())) {
            val reference = "${NSUUID().UUIDString.lowercase()}.png"
            val finalPath = "$directory/$reference"
            val pendingPath = "$finalPath.tmp"
            try {
                if (!manager.copyItemAtPath(sourcePath, pendingPath, null)) return null
                return if (manager.moveItemAtPath(pendingPath, finalPath, null)) reference else null
            } finally {
                manager.removeItemAtPath(pendingPath, null)
            }
        }
    }
```

Add a private `NSData.toByteArray()` (the same five lines as in `TrackGenres.ios.kt`).

3. When `jpegQuality` is not null, build a one-entry properties dictionary with `kCGImageDestinationLossyCompressionQuality` and pass it to `CGImageDestinationAddImage` (null keeps today's playlist output exactly):

```kotlin
                            val properties = jpegQuality?.let { quality ->
                                val dictionary = CFDictionaryCreateMutable(
                                    null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
                                ) ?: return null
                                val number = memScoped {
                                    val value = alloc<DoubleVar> { this.value = quality }
                                    CFNumberCreate(null, kCFNumberDoubleType, value.ptr)
                                } ?: run { CFRelease(dictionary); return null }
                                CFDictionarySetValue(dictionary, kCGImageDestinationLossyCompressionQuality, number)
                                CFRelease(number)
                                dictionary
                            }
                            try {
                                CGImageDestinationAddImage(destination, flattened, properties)
                                if (!CGImageDestinationFinalize(destination)) return null
                            } finally {
                                properties?.let(::CFRelease)
                                CFRelease(destination)
                            }
```

The output name's extension stays `.jpg`.

4. Generalize `PlaylistCoverDelegate` into `CoverPickerDelegate(private val import: (NSURL) -> String?, private val onResult: (CoverPickOutcome) -> Unit)`, using the common `CoverPickOutcome` from `TagCoverPicker.kt`.

5. Move the presenter code of `rememberPlaylistCoverPicker` into `rememberSystemCoverPicker(import: (NSURL) -> String?, remove: (String) -> Unit, onResult: (CoverPickOutcome) -> Unit): () -> Unit`. `rememberPlaylistCoverPicker` then calls it with `{ importCoverImage(it, playlistDirectory, PLAYLIST_COVER_MAX_EDGE, null, false) }` and `::removeCoverFile`.

`TagCoverPicker.ios.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSinceNow

private val tagCoverDirectory: String? by lazy {
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String)?.let { "$it/$TAG_COVER_DIRECTORY" }
}

private fun tagCoverPath(reference: String): String? =
    reference.takeIf(::isTagCoverReference)?.let { name -> tagCoverDirectory?.let { "$it/$name" } }

@Composable
internal actual fun rememberTagCoverPicker(onResult: (TagCoverPick) -> Unit): () -> Unit =
    rememberSystemCoverPicker(
        import = { url ->
            tagCoverDirectory?.let { directory ->
                pruneStaleTagCovers(directory)
                importCoverImage(url, directory, TAG_COVER_MAX_EDGE, TAG_COVER_JPEG_QUALITY / 100.0, keepSmallPng = true)
            }
        },
        remove = { reference -> tagCoverPath(reference)?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) } },
    ) { outcome ->
        onResult(
            when (outcome) {
                is CoverPickOutcome.Picked -> TagCoverPick.Picked(outcome.reference)
                CoverPickOutcome.Cancelled -> TagCoverPick.Cancelled
                CoverPickOutcome.Failed -> TagCoverPick.Failed
            },
        )
    }

/** Covers picked in an editor the process lost; a live editor's pick is always younger. */
@OptIn(ExperimentalForeignApi::class)
private fun pruneStaleTagCovers(directory: String) {
    val manager = NSFileManager.defaultManager
    val names = manager.contentsOfDirectoryAtPath(directory, null)?.filterIsInstance<String>() ?: return
    for (name in names) {
        val path = "$directory/$name"
        val modified = manager.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate ?: continue
        if (-modified.timeIntervalSinceNow * 1000 > TAG_COVER_STALE_MS) manager.removeItemAtPath(path, null)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readTagCover(reference: String): ByteArray? = withContext(Dispatchers.IO) {
    tagCoverPath(reference)?.let { NSData.dataWithContentsOfFile(it) }?.toByteArray()
}

internal actual suspend fun deleteTagCover(reference: String) {
    withContext(Dispatchers.IO) { tagCoverPath(reference)?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) } }
}

internal actual fun tagCoverUri(reference: String): String? = tagCoverPath(reference)?.let { NSURL.fileURLWithPath(it).absoluteString }
```

The private `toByteArray` from Step 5.2 must be `internal` to be reachable here. Keep one copy in `PlaylistCoverPicker.ios.kt`.

- [ ] **Step 6: Run the tests and both compiles**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 667 tests. `PlaylistCoverPickerTest` is unchanged and still green.

Run: `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q && ./gradlew :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: success.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PlaylistCoverPicker.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagCoverPicker.kt composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/CoverImport.android.kt composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/PlaylistCoverPicker.android.kt composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/TagCoverPicker.android.kt composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/PlaylistCoverPicker.ios.kt composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/TagCoverPicker.ios.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagCoverPickerTest.kt
git commit -m "feat(tags): a cover picked for a song's tags is sized and encoded the way players expect"
```

---

### Task 7: The single-track editor edits every field the file has

Spec §6.1. The track sheet's edit mode grows from five fields to all of them:
- title, artist, album, album artist, genre, year;
- track and total, disc and total (each pair on one row);
- lyrics (multi-line);
- cover (thumbnail with Replace and Remove, and "+N more images in the file, kept").

The fields are filled from the file (`readTagFile`, Task 5), not from MediaStore. A file that cannot be edited shows its reason instead of the fields. A file whose last save was interrupted says so and offers to finish it (spec §5.4: "the prompt returns … whenever the file is opened in the editor").

The edit logic lives in a pure, tested `TagEditorForm`:
- a field is written only when its trimmed text differs from what the file holds;
- an emptied field is removed;
- whitespace alone is never an edit.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagEditorForm.kt`
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagEditorFields.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/EditorComponents.kt` (`EditorTextField` gains `singleLine`, `minLines`, `maxLines`, `placeholder`, `trailingIcon`)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackInfoSheet.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt` (the `TrackInfoSheet` call: `onSaved` now receives the result)
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagEditorFormTest.kt`

**Interfaces:**
- Consumes:
  - `readTagFile`, `TagFileRead` (Task 5);
  - `rememberTagSaver`, `TagSaver.busy`, `TagSaveResult`, `tagProblemText`, `TagProblem` (Task 4);
  - `rememberTagCoverPicker`, `TagCoverPick`, `readTagCover`, `deleteTagCover`, `tagCoverUri`, `tagCoverMime` (Task 6);
  - `rememberTagWriteAccess` (Task 3);
  - `TagWriteCoordinator.pendingRecovery`, `enqueueRecovery()`.
- Produces:
  - `internal sealed interface CoverChoice { data object Keep; data object Remove; data class Replace(val reference: String) }`
  - `internal data class TagEditorForm(...)`, with:
    - `fun edits(baseline: TagEditorForm, cover: CoverEdit = CoverEdit.Keep): TagEdits`;
    - `fun hasChanges(baseline: TagEditorForm): Boolean`;
    - `fun toSaveable(): List<String>`;
    - companion `of(snapshot: TagSnapshot)`, `fromSaveable(saved: List<String>): TagEditorForm?`.
  - `internal fun numberInput(text: String): String`, `internal fun yearInput(text: String): String`
  - `@Composable internal fun NumberPairField(numberLabel: String, totalLabel: String, number: String, total: String, enabled: Boolean, onNumber: (String) -> Unit, onTotal: (String) -> Unit, numberPlaceholder: String? = null, totalPlaceholder: String? = null)`
  - `@Composable internal fun CoverEditRow(choice: CoverChoice, currentUri: String?, canRemove: Boolean, otherPictures: Int, enabled: Boolean, onReplace: () -> Unit, onRemove: () -> Unit, onKeep: () -> Unit)`
  - `internal fun TrackInfoSheet(track: TrackDescriptor, onSaved: (TagSaveResult) -> Unit = {}, onDismiss: () -> Unit)`

- [ ] **Step 1: Write the failing tests**

`TagEditorFormTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagFormat
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class TagEditorFormTest {

    private val snapshot = TagSnapshot(
        format = TagFormat.FLAC,
        version = "FLAC",
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumArtist = "Various Artists",
        genre = "Pop",
        year = "2001-05-03",
        trackNumber = 3,
        trackTotal = 12,
        discNumber = 1,
        lyrics = "la la",
    )
    private val baseline = TagEditorForm.of(snapshot)

    @Test
    fun theFormShowsWhatTheFileHolds() {
        assertEquals("Various Artists", baseline.albumArtist)
        assertEquals("2001-05-03", baseline.year)
        assertEquals("3", baseline.trackNumber)
        assertEquals("12", baseline.trackTotal)
        assertEquals("1", baseline.discNumber)
        assertEquals("", baseline.discTotal)
        assertEquals("la la", baseline.lyrics)
        assertFalse(baseline.hasChanges(baseline))
    }

    @Test
    fun onlyTheFieldsTheUserChangedAreEdited() {
        val edited = baseline.copy(title = "New song", trackTotal = "13")
        assertEquals(TagEdits(title = "New song", trackTotal = "13"), edited.edits(baseline))
    }

    @Test
    fun whitespaceAloneIsNotAnEdit() {
        val edited = baseline.copy(title = "  Song ", lyrics = "la la\n")
        assertTrue(edited.edits(baseline).isEmpty)
        assertFalse(edited.hasChanges(baseline))
    }

    @Test
    fun clearingAFieldRemovesIt() {
        val edited = baseline.copy(albumArtist = "", discNumber = " ")
        assertEquals(TagEdits(albumArtist = "", discNumber = ""), edited.edits(baseline))
    }

    @Test
    fun aNumberOutsideOneTo999IsCaughtBeforeSaving() {
        assertFalse(baseline.copy(trackNumber = "0").edits(baseline).numbersAreValid)
        assertTrue(baseline.copy(trackNumber = "999").edits(baseline).numbersAreValid)
    }

    @Test
    fun aCoverChoiceIsAChangeAndItsEditIsPassedThrough() {
        val replaced = baseline.copy(cover = CoverChoice.Replace("f08f8630-6120-4aa9-9580-973044632c42.jpg"))
        assertTrue(replaced.hasChanges(baseline))
        assertEquals(CoverEdit.Remove, baseline.copy(cover = CoverChoice.Remove).edits(baseline, CoverEdit.Remove).cover)
    }

    @Test
    fun theFormSurvivesBeingSaved() {
        val edited = baseline.copy(
            title = "A\tB\nC",
            lyrics = "line one\nline two",
            cover = CoverChoice.Replace("f08f8630-6120-4aa9-9580-973044632c42.png"),
        )
        assertEquals(edited, TagEditorForm.fromSaveable(edited.toSaveable()))
        assertEquals(baseline.copy(cover = CoverChoice.Remove), TagEditorForm.fromSaveable(baseline.copy(cover = CoverChoice.Remove).toSaveable()))
        assertEquals(null, TagEditorForm.fromSaveable(listOf("too", "short")))
    }

    @Test
    fun inputFiltersKeepWhatATagCanHold() {
        assertEquals("123", numberInput("1a2b34"))
        assertEquals("2001-05-03", yearInput("2001-05-03xyz9"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagEditorFormTest*'`
Expected: compilation FAILS (`TagEditorForm` unresolved).

- [ ] **Step 3: Implement `TagEditorForm.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagSnapshot

/** What the editor will do with the cover: nothing, remove it, or put a picked image in its place. */
internal sealed interface CoverChoice {
    data object Keep : CoverChoice
    data object Remove : CoverChoice
    data class Replace(val reference: String) : CoverChoice
}

/**
 * The single-track editor's fields as the user sees them: text, with numbers as digits.
 *
 * [edits] compares against the form built from the file ([of]), so only what the user changed is
 * written. Whitespace alone is not a change, and an emptied field is removed (`""`, spec §3.1).
 * Fields are compared trimmed because every codec stores them trimmed; a stray space typed at the
 * end must not rewrite a file.
 */
internal data class TagEditorForm(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val trackTotal: String = "",
    val discNumber: String = "",
    val discTotal: String = "",
    val lyrics: String = "",
    val cover: CoverChoice = CoverChoice.Keep,
) {
    fun edits(baseline: TagEditorForm, cover: CoverEdit = CoverEdit.Keep): TagEdits = TagEdits(
        title = changed(title, baseline.title),
        artist = changed(artist, baseline.artist),
        album = changed(album, baseline.album),
        albumArtist = changed(albumArtist, baseline.albumArtist),
        genre = changed(genre, baseline.genre),
        year = changed(year, baseline.year),
        trackNumber = changed(trackNumber, baseline.trackNumber),
        trackTotal = changed(trackTotal, baseline.trackTotal),
        discNumber = changed(discNumber, baseline.discNumber),
        discTotal = changed(discTotal, baseline.discTotal),
        lyrics = changed(lyrics, baseline.lyrics),
        cover = cover,
    )

    fun hasChanges(baseline: TagEditorForm): Boolean = !edits(baseline).isEmpty || cover != CoverChoice.Keep

    /** Twelve strings, so any text survives a Bundle; the cover as "keep", "remove" or "replace:<reference>". */
    fun toSaveable(): List<String> = listOf(
        title, artist, album, albumArtist, genre, year, trackNumber, trackTotal, discNumber, discTotal, lyrics,
        when (cover) {
            CoverChoice.Keep -> KEEP
            CoverChoice.Remove -> REMOVE
            is CoverChoice.Replace -> REPLACE + cover.reference
        },
    )

    companion object {
        private const val KEEP = "keep"
        private const val REMOVE = "remove"
        private const val REPLACE = "replace:"

        fun of(snapshot: TagSnapshot) = TagEditorForm(
            title = snapshot.title.orEmpty(),
            artist = snapshot.artist.orEmpty(),
            album = snapshot.album.orEmpty(),
            albumArtist = snapshot.albumArtist.orEmpty(),
            genre = snapshot.genre.orEmpty(),
            year = snapshot.year.orEmpty(),
            trackNumber = snapshot.trackNumber?.toString().orEmpty(),
            trackTotal = snapshot.trackTotal?.toString().orEmpty(),
            discNumber = snapshot.discNumber?.toString().orEmpty(),
            discTotal = snapshot.discTotal?.toString().orEmpty(),
            lyrics = snapshot.lyrics.orEmpty(),
        )

        fun fromSaveable(saved: List<String>): TagEditorForm? {
            if (saved.size != 12) return null
            val cover = when (val value = saved[11]) {
                KEEP -> CoverChoice.Keep
                REMOVE -> CoverChoice.Remove
                else -> value.removePrefix(REPLACE).takeIf { value.startsWith(REPLACE) && isTagCoverReference(it) }
                    ?.let(CoverChoice::Replace) ?: return null
            }
            return TagEditorForm(
                saved[0], saved[1], saved[2], saved[3], saved[4], saved[5],
                saved[6], saved[7], saved[8], saved[9], saved[10], cover,
            )
        }
    }
}

private fun changed(value: String, baseline: String): String? = value.trim().takeIf { it != baseline.trim() }

/** Digits only, at most three: a tag number runs from 1 to 999 ([TagEdits.numbersAreValid] checks the rest). */
internal fun numberInput(text: String): String = text.filter { it in '0'..'9' }.take(3)

/** A year or a full date ("2001-05-03"), which an untouched tag keeps (spec §3.4). */
internal fun yearInput(text: String): String = text.filter { it in '0'..'9' || it == '-' }.take(10)
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagEditorFormTest*'`
Expected: PASS (8 tests).

- [ ] **Step 5: Extend `EditorTextField`**

In `EditorComponents.kt`, add these parameters after `keyboardActions`:

```kotlin
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    /** Shown in an empty field, as the N-track editor's "Different values". */
    placeholder: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
```

Pass them to `TextField`: `singleLine = singleLine, minLines = minLines, maxLines = maxLines, placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyLarge) } }, trailingIcon = trailingIcon`. Replace the hard-coded `singleLine = true`.

- [ ] **Step 6: Add the English strings**

In `values/strings.xml`, after `info_lyrics`:

```xml
    <string name="info_album_artist">Album artist</string>
    <string name="info_track_number">Track</string>
    <string name="info_track_total">Total tracks</string>
    <string name="info_disc_number">Disc</string>
    <string name="info_disc_total">Total discs</string>
    <string name="info_cover">Cover</string>
    <string name="info_cover_replace">Replace</string>
    <string name="info_cover_remove">Remove</string>
    <string name="info_cover_keep">Keep current</string>
    <string name="info_cover_new">New cover</string>
    <string name="info_cover_removed">The cover will be removed.</string>
    <plurals name="info_cover_more">
        <item quantity="one">+%1$d more image in the file, kept</item>
        <item quantity="other">+%1$d more images in the file, kept</item>
    </plurals>
    <string name="info_reading_file">Reading the file…</string>
```

- [ ] **Step 7: Implement `TagEditorFields.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.info_cover
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_keep
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_more
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_new
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_remove
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_removed
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_replace
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A number and its total on one row, as a tag stores them ("3/12"). Input is digits only. */
@Composable
internal fun NumberPairField(
    numberLabel: String,
    totalLabel: String,
    number: String,
    total: String,
    enabled: Boolean,
    onNumber: (String) -> Unit,
    onTotal: (String) -> Unit,
    numberPlaceholder: String? = null,
    totalPlaceholder: String? = null,
) {
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EditorTextField(
            label = numberLabel,
            value = number,
            onValueChange = { onNumber(numberInput(it)) },
            enabled = enabled,
            keyboardOptions = numeric,
            placeholder = numberPlaceholder,
            modifier = Modifier.weight(1f),
        )
        EditorTextField(
            label = totalLabel,
            value = total,
            onValueChange = { onTotal(numberInput(it)) },
            enabled = enabled,
            keyboardOptions = numeric,
            placeholder = totalPlaceholder,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The cover as it will be after saving, with the actions that change it. [otherPictures] are kept
 * by every edit. Saying so means that removing the cover and seeing another picture take its place
 * is not a surprise (spec §3.4).
 */
@Composable
internal fun CoverEditRow(
    choice: CoverChoice,
    currentUri: String?,
    canRemove: Boolean,
    otherPictures: Int,
    enabled: Boolean,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            uri = when (choice) {
                CoverChoice.Keep -> currentUri
                CoverChoice.Remove -> null
                is CoverChoice.Replace -> tagCoverUri(choice.reference)
            },
            size = 72.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    when (choice) {
                        CoverChoice.Keep -> Res.string.info_cover
                        CoverChoice.Remove -> Res.string.info_cover_removed
                        is CoverChoice.Replace -> Res.string.info_cover_new
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onReplace, enabled = enabled) { Text(stringResource(Res.string.info_cover_replace)) }
                when {
                    choice != CoverChoice.Keep ->
                        TextButton(onClick = onKeep, enabled = enabled) { Text(stringResource(Res.string.info_cover_keep)) }
                    canRemove ->
                        TextButton(onClick = onRemove, enabled = enabled) { Text(stringResource(Res.string.info_cover_remove)) }
                }
            }
            if (otherPictures > 0) {
                Text(
                    text = pluralStringResource(Res.plurals.info_cover_more, otherPictures, otherPictures),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 8: Rebuild the sheet's edit mode**

In `TrackInfoSheet.kt`:

1. Change the signature to `onSaved: (TagSaveResult) -> Unit = {}`.
2. Replace the five `rememberSaveable` field states, the `edits` value and the edit column with the code below.
3. The read mode (`InfoRow`s, lyrics) and the header stay as they are.
4. Delete `MetadataField`: `EditorTextField` is used directly.

State, after `val lyricsSources = …`:

```kotlin
    val scope = rememberCoroutineScope()
    val access = rememberTagWriteAccess()
    // Read only once editing starts: the info view never opens the file for tags it does not show.
    var read by remember(track.id, track.sourceRevision) { mutableStateOf<TagFileRead?>(null) }
    LaunchedEffect(editing, track.id, track.sourceRevision) {
        if (editing && read == null) read = readTagFile(track)
    }
    val snapshot = (read as? TagFileRead.Ready)?.snapshot
    val baseline = remember(snapshot) { snapshot?.let(TagEditorForm::of) }
    var form by rememberSaveable(
        track.id.value,
        stateSaver = Saver<TagEditorForm?, List<String>>(
            save = { it?.toSaveable() ?: emptyList() },
            restore = { TagEditorForm.fromSaveable(it) },
        ),
    ) { mutableStateOf(null) }
    // A restored form keeps the user's typing; a fresh one starts from what the file holds.
    LaunchedEffect(baseline) {
        if (form == null && baseline != null) form = baseline
    }
    var failure by remember(track.id) { mutableStateOf<TagProblem?>(null) }
    val pending = access?.coordinator?.pendingRecovery?.collectAsState()?.value.orEmpty()
    val interrupted = access?.keyOf(track)?.let { key -> pending.any { it.target == key } } == true

    fun forgetPickedCover() {
        (form?.cover as? CoverChoice.Replace)?.let { scope.launch { deleteTagCover(it.reference) } }
    }

    val saver = rememberTagSaver { result ->
        val entry = result.entries.singleOrNull()
        when {
            entry == null -> failure = TagProblem.FAILED
            entry.saved -> {
                forgetPickedCover()
                onSaved(result)
                onDismiss()
            }
            // The user closed the system's permission dialog. They know they did.
            result.cancelled -> Unit
            else -> failure = entry.problem
        }
    }
    val saving = saver?.busy == true
    val pickCover = rememberTagCoverPicker { pick ->
        when (pick) {
            is TagCoverPick.Picked -> {
                forgetPickedCover()
                form = form?.copy(cover = CoverChoice.Replace(pick.reference))
            }
            TagCoverPick.Failed -> failure = TagProblem.BAD_IMAGE
            TagCoverPick.Cancelled -> Unit
        }
    }
    val edits = baseline?.let { form?.edits(it) }
    val canSave = !saving && baseline != null && form?.hasChanges(baseline) == true && edits?.numbersAreValid == true
```

The sheet's `onDismissRequest` and the Cancel action also call `forgetPickedCover()` before leaving. Cancel resets with `form = baseline` instead of the five assignments.

The edit column (replaces the `MetadataField` calls):

```kotlin
                    when {
                        interrupted -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tagProblemText(TagProblem.INTERRUPTED), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { access?.coordinator?.enqueueRecovery() }) {
                                Text(stringResource(Res.string.tag_recovery_finish))
                            }
                        }
                        read == null -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(Res.string.info_reading_file), style = MaterialTheme.typography.bodyMedium)
                        }
                        read is TagFileRead.NotEditable -> Text(
                            text = tagProblemText((read as TagFileRead.NotEditable).problem),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> form?.let { current ->
                            val enabled = !saving
                            CoverEditRow(
                                choice = current.cover,
                                currentUri = track.artworkUri,
                                canRemove = snapshot?.cover != null,
                                otherPictures = snapshot?.otherPictures ?: 0,
                                enabled = enabled,
                                onReplace = pickCover,
                                onRemove = { form = current.copy(cover = CoverChoice.Remove) },
                                onKeep = {
                                    forgetPickedCover()
                                    form = current.copy(cover = CoverChoice.Keep)
                                },
                            )
                            TextRow(stringResource(Res.string.info_title), current.title, enabled) { form = current.copy(title = it) }
                            TextRow(stringResource(Res.string.info_artist), current.artist, enabled) { form = current.copy(artist = it) }
                            TextRow(stringResource(Res.string.info_album), current.album, enabled) { form = current.copy(album = it) }
                            TextRow(stringResource(Res.string.info_album_artist), current.albumArtist, enabled) { form = current.copy(albumArtist = it) }
                            TextRow(stringResource(Res.string.info_genre), current.genre, enabled) { form = current.copy(genre = it) }
                            TextRow(stringResource(Res.string.info_year), current.year, enabled, KeyboardType.Number) {
                                form = current.copy(year = yearInput(it))
                            }
                            NumberPairField(
                                numberLabel = stringResource(Res.string.info_track_number),
                                totalLabel = stringResource(Res.string.info_track_total),
                                number = current.trackNumber,
                                total = current.trackTotal,
                                enabled = enabled,
                                onNumber = { form = current.copy(trackNumber = it) },
                                onTotal = { form = current.copy(trackTotal = it) },
                            )
                            NumberPairField(
                                numberLabel = stringResource(Res.string.info_disc_number),
                                totalLabel = stringResource(Res.string.info_disc_total),
                                number = current.discNumber,
                                total = current.discTotal,
                                enabled = enabled,
                                onNumber = { form = current.copy(discNumber = it) },
                                onTotal = { form = current.copy(discTotal = it) },
                            )
                            EditorTextField(
                                label = stringResource(Res.string.info_lyrics),
                                value = current.lyrics,
                                onValueChange = { form = current.copy(lyrics = it) },
                                enabled = enabled,
                                singleLine = false,
                                minLines = 3,
                                maxLines = 12,
                            )
                            if (edits?.numbersAreValid == false) {
                                Text(
                                    text = tagProblemText(TagProblem.BAD_NUMBER),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
```

Keep the failure `AnimatedContent` (with `tagProblemText`) and the `info_edit_note` text below it. The class KDoc's "What a save actually does" still says it rewrites "the ID3v2 tag". Make it say the file's own tags, in whichever of the four formats the file is, through the durable writer. Add the private helper at the bottom of the file:

```kotlin
@Composable
private fun TextRow(label: String, value: String, enabled: Boolean, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    EditorTextField(
        label = label,
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
    )
}
```

`EditorActions` becomes `confirmEnabled = canSave` and `busy = saving`. `onConfirm`:

```kotlin
                    onConfirm = {
                        val current = form
                        val base = baseline
                        if (canSave && current != null && base != null) {
                            focus.clearFocus()
                            keyboard?.hide()
                            failure = null
                            scope.launch {
                                val cover = when (val choice = current.cover) {
                                    CoverChoice.Keep -> CoverEdit.Keep
                                    CoverChoice.Remove -> CoverEdit.Remove
                                    is CoverChoice.Replace ->
                                        readTagCover(choice.reference)?.let { CoverEdit.Replace(it, tagCoverMime(choice.reference)) }
                                }
                                when {
                                    cover == null -> failure = TagProblem.BAD_IMAGE
                                    saver == null -> failure = TagProblem.FAILED
                                    // Null leaves an untouched field intact; an empty string removes it.
                                    else -> saver.start(listOf(track), current.edits(base, cover))
                                }
                            }
                        }
                    },
```

`confirmValueChange` of the sheet state keeps blocking dismissal while `saving`. Add the imports the code needs: `Saver`, `rememberCoroutineScope`, `collectAsState`, `CircularProgressIndicator`, `TextButton`, `KeyboardType`, `launch`, `CoverEdit` and the new string resources.

In `App.kt`, the call becomes `onSaved = { scope.launch { scanLibrary() } }`. The lambda now takes one parameter and ignores it here; Task 13 uses it.

- [ ] **Step 9: Run the tests and both compiles**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 675 tests.

Run: `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q && ./gradlew :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: success.

- [ ] **Step 10: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagEditorForm.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagEditorFields.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/EditorComponents.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackInfoSheet.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagEditorFormTest.kt
git commit -m "feat(tags): a song's album artist, track and disc numbers, lyrics and cover can be edited, straight from what the file holds"
```

---

### Task 8: The N-track editor: Keep, Set or Remove each field, then an exact result

Spec §6.2 and §6.3. A new sheet edits many tracks at once.

**Fields.** Every field except the per-track ones: artist, album, album artist, genre, year, track total, disc number and total, cover. Each field has three explicit states:
- **Keep** (the default): a shared value is shown; differing values show an empty field hinted "Different values". Nothing is written.
- **Set**: something was typed, and that value goes to every file.
- **Remove**: chosen with the field's clear control. The field says "Removed from all 12" and is deleted from every file. Undo returns it to Keep.

An empty text box always means Keep.

**Sheet flow:**
1. It first reads every file's tags (`readTagFile`, four at a time, "Reading 40 of 120…").
2. It sets aside the files that cannot be edited, each named with its reason.
3. The save button states the scope: "Change 3 fields in 12 files".
4. While saving: "Saved 5 of 12", and Stop.
5. Then the exact result: "Saved 11 of 12." and every file not changed, with its reason.

A full success closes the sheet; the caller says so (Task 13).

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/BulkTagForm.kt`
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/BulkTagEditorSheet.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/BulkTagFormTest.kt`

**Interfaces:**
- Consumes:
  - Task 4: `TagSaver`, `rememberTagSaver`, `TagSaver.progress()`, `TagSaveResult`, `tagSaveResultOf`, `tagProblemText`.
  - Task 5: `readTagFile`, `TagFileRead`.
  - Task 6: `rememberTagCoverPicker`, `readTagCover`, `deleteTagCover`, `tagCoverMime`.
  - Task 7: `CoverChoice`, `CoverEditRow`, `numberInput`, `yearInput`, `EditorTextField(placeholder, trailingIcon)`.
- Produces:
  - `internal enum class BulkField { ARTIST, ALBUM, ALBUM_ARTIST, GENRE, YEAR, TRACK_TOTAL, DISC_NUMBER, DISC_TOTAL }`
  - `internal sealed interface SharedValue { data class Same(val value: String); data object Different }`
  - `internal class BulkBaseline(val shared: Map<BulkField, SharedValue>)`, with:
    - `fun initialText(field: BulkField): String`;
    - companion `of(snapshots: List<TagSnapshot>)`.
  - `internal data class BulkFieldState(val text: String, val removed: Boolean = false)`
  - `internal data class BulkTagForm(val fields: Map<BulkField, BulkFieldState> = emptyMap(), val cover: CoverChoice = CoverChoice.Keep)`, with:
    - `text`, `removed`, `typed`, `remove`, `keep`;
    - `edits(baseline, cover)`;
    - `changedFieldCount(baseline)`;
    - `toSaveable()`, companion `fromSaveable`.
  - `internal class BulkTargets(val editable: List<Pair<TrackDescriptor, TagSnapshot>>, val notEditable: List<Pair<TrackDescriptor, TagProblem>>)`
  - `internal fun bulkTargets(tracks: List<TrackDescriptor>, reads: List<TagFileRead>): BulkTargets`
  - `internal fun trackLabel(track: TrackDescriptor?, key: String): String`
  - `internal enum class BulkEditScope { ALBUM, ARTIST, SELECTION }`
  - `@Composable internal fun BulkTagEditorSheet(tracks: List<TrackDescriptor>, scope: BulkEditScope, onSaved: (List<TrackDescriptor>, TagSaveResult) -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: Write the failing tests**

`BulkTagFormTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagFormat
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class BulkTagFormTest {

    private fun snapshot(artist: String?, album: String?, discTotal: Int? = null) =
        TagSnapshot(TagFormat.MP3, "ID3v2.3", artist = artist, album = album, discTotal = discTotal)

    private val baseline = BulkBaseline.of(
        listOf(snapshot("A", "Album", 2), snapshot("B", "Album", 2), snapshot("  A ", "Album")),
    )

    @Test
    fun sharedValuesShowAndDifferentValuesStayEmpty() {
        assertEquals(SharedValue.Same("Album"), baseline.shared[BulkField.ALBUM])
        assertEquals(SharedValue.Different, baseline.shared[BulkField.ARTIST])
        assertEquals(SharedValue.Different, baseline.shared[BulkField.DISC_TOTAL])
        assertEquals(SharedValue.Same(""), baseline.shared[BulkField.GENRE])
        assertEquals("Album", BulkTagForm().text(BulkField.ALBUM, baseline))
        assertEquals("", BulkTagForm().text(BulkField.ARTIST, baseline))
    }

    @Test
    fun anEmptyBoxNeverRemoves() {
        val cleared = BulkTagForm().typed(BulkField.ALBUM, "").typed(BulkField.ARTIST, "   ")
        assertTrue(cleared.edits(baseline).isEmpty)
        assertEquals(0, cleared.changedFieldCount(baseline))
    }

    @Test
    fun removingIsDeliberateAndUndoable() {
        val removed = BulkTagForm().remove(BulkField.ALBUM)
        assertTrue(removed.removed(BulkField.ALBUM))
        assertEquals(TagEdits(album = ""), removed.edits(baseline))
        assertTrue(removed.keep(BulkField.ALBUM).edits(baseline).isEmpty)
        // Typing into a removed field sets it instead.
        assertEquals(TagEdits(album = "New"), removed.typed(BulkField.ALBUM, "New").edits(baseline))
    }

    @Test
    fun typingTheSharedValueAgainIsNotAChange() {
        assertTrue(BulkTagForm().typed(BulkField.ALBUM, "Album ").edits(baseline).isEmpty)
    }

    @Test
    fun typingOverDifferentValuesSetsThemAll() {
        val form = BulkTagForm().typed(BulkField.ARTIST, "A").typed(BulkField.DISC_TOTAL, "2")
        assertEquals(TagEdits(artist = "A", discTotal = "2"), form.edits(baseline))
        assertEquals(2, form.changedFieldCount(baseline))
    }

    @Test
    fun theCoverCountsAsAField() {
        assertEquals(1, BulkTagForm(cover = CoverChoice.Remove).changedFieldCount(baseline))
    }

    @Test
    fun theFormSurvivesBeingSaved() {
        val form = BulkTagForm(cover = CoverChoice.Replace("f08f8630-6120-4aa9-9580-973044632c42.jpg"))
            .typed(BulkField.GENRE, "Jazz\tFusion")
            .remove(BulkField.YEAR)
        assertEquals(form, BulkTagForm.fromSaveable(form.toSaveable()))
        assertEquals(null, BulkTagForm.fromSaveable(listOf("keep", "NOT_A_FIELD", "x", "0")))
    }

    @Test
    fun notEditableFilesAreSetAsideWithTheirReason() {
        val tracks = listOf(TrackDescriptor(TrackId("a")), TrackDescriptor(TrackId("b")), TrackDescriptor(TrackId("c")))
        val ready = snapshot("A", "Album")
        val targets = bulkTargets(
            tracks,
            listOf(
                TagFileRead.Ready(ready),
                TagFileRead.NotEditable(TagProblem.PROTECTED),
                TagFileRead.Ready(ready),
            ),
        )
        assertEquals(listOf("a", "c"), targets.editable.map { it.first.id.value })
        assertEquals(listOf(TrackId("b") to TagProblem.PROTECTED), targets.notEditable.map { it.first.id to it.second })
    }

    @Test
    fun aFileIsNamedByItsTitleThenItsFileName() {
        assertEquals("Song", trackLabel(TrackDescriptor(TrackId("1"), title = "Song", fileName = "s.mp3"), "k"))
        assertEquals("s.mp3", trackLabel(TrackDescriptor(TrackId("1"), fileName = "s.mp3"), "k"))
        assertEquals("k", trackLabel(null, "k"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*BulkTagFormTest*'`
Expected: compilation FAILS.

- [ ] **Step 3: Implement `BulkTagForm.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** The fields every selected file can share: per-track ones (title, track number, lyrics) are not offered. */
internal enum class BulkField { ARTIST, ALBUM, ALBUM_ARTIST, GENRE, YEAR, TRACK_TOTAL, DISC_NUMBER, DISC_TOTAL }

/** What the selected files hold for one field: one value (possibly none, as ""), or different values. */
internal sealed interface SharedValue {
    data class Same(val value: String) : SharedValue
    data object Different : SharedValue
}

internal class BulkBaseline(val shared: Map<BulkField, SharedValue>) {

    /** What a field shows before the user touches it: the shared value, or empty over different ones. */
    fun initialText(field: BulkField): String = (shared[field] as? SharedValue.Same)?.value.orEmpty()

    companion object {
        fun of(snapshots: List<TagSnapshot>): BulkBaseline = BulkBaseline(
            BulkField.entries.associateWith { field ->
                val values = snapshots.map { valueOf(it, field)?.trim().orEmpty() }.distinct()
                values.singleOrNull()?.let(SharedValue::Same) ?: SharedValue.Different
            },
        )

        private fun valueOf(snapshot: TagSnapshot, field: BulkField): String? = when (field) {
            BulkField.ARTIST -> snapshot.artist
            BulkField.ALBUM -> snapshot.album
            BulkField.ALBUM_ARTIST -> snapshot.albumArtist
            BulkField.GENRE -> snapshot.genre
            BulkField.YEAR -> snapshot.year
            BulkField.TRACK_TOTAL -> snapshot.trackTotal?.toString()
            BulkField.DISC_NUMBER -> snapshot.discNumber?.toString()
            BulkField.DISC_TOTAL -> snapshot.discTotal?.toString()
        }
    }
}

internal data class BulkFieldState(val text: String, val removed: Boolean = false)

/**
 * The N-track editor's choices (spec §6.2). A field absent from [fields] is Keep. A typed field is
 * Set, unless its box is empty, which is Keep again: an empty box never removes anything. A
 * removed field is Remove, which only the field's clear control produces.
 */
internal data class BulkTagForm(
    val fields: Map<BulkField, BulkFieldState> = emptyMap(),
    val cover: CoverChoice = CoverChoice.Keep,
) {
    fun text(field: BulkField, baseline: BulkBaseline): String = fields[field]?.text ?: baseline.initialText(field)

    fun removed(field: BulkField): Boolean = fields[field]?.removed == true

    fun typed(field: BulkField, text: String) = copy(fields = fields + (field to BulkFieldState(text)))

    fun remove(field: BulkField) = copy(fields = fields + (field to BulkFieldState("", removed = true)))

    fun keep(field: BulkField) = copy(fields = fields - field)

    fun edits(baseline: BulkBaseline, cover: CoverEdit = CoverEdit.Keep): TagEdits = TagEdits(
        artist = edit(BulkField.ARTIST, baseline),
        album = edit(BulkField.ALBUM, baseline),
        albumArtist = edit(BulkField.ALBUM_ARTIST, baseline),
        genre = edit(BulkField.GENRE, baseline),
        year = edit(BulkField.YEAR, baseline),
        trackTotal = edit(BulkField.TRACK_TOTAL, baseline),
        discNumber = edit(BulkField.DISC_NUMBER, baseline),
        discTotal = edit(BulkField.DISC_TOTAL, baseline),
        cover = cover,
    )

    /** How many fields a save would write, the cover included: the "3 fields" of "Change 3 fields in 12 files". */
    fun changedFieldCount(baseline: BulkBaseline): Int =
        BulkField.entries.count { edit(it, baseline) != null } + (if (cover == CoverChoice.Keep) 0 else 1)

    private fun edit(field: BulkField, baseline: BulkBaseline): String? {
        val state = fields[field] ?: return null
        if (state.removed) return ""
        val typed = state.text.trim()
        if (typed.isEmpty()) return null
        val shared = baseline.shared[field]
        return typed.takeIf { shared !is SharedValue.Same || it != shared.value }
    }

    /** The cover first ("keep", "remove", "replace:<reference>"), then name, text and "1"/"0" for each touched field. */
    fun toSaveable(): List<String> = buildList {
        add(
            when (cover) {
                CoverChoice.Keep -> "keep"
                CoverChoice.Remove -> "remove"
                is CoverChoice.Replace -> "replace:" + cover.reference
            },
        )
        for ((field, state) in fields) {
            add(field.name)
            add(state.text)
            add(if (state.removed) "1" else "0")
        }
    }

    companion object {
        fun fromSaveable(saved: List<String>): BulkTagForm? {
            if (saved.isEmpty() || (saved.size - 1) % 3 != 0) return null
            val cover = when (val value = saved[0]) {
                "keep" -> CoverChoice.Keep
                "remove" -> CoverChoice.Remove
                else -> value.removePrefix("replace:").takeIf { value.startsWith("replace:") && isTagCoverReference(it) }
                    ?.let(CoverChoice::Replace) ?: return null
            }
            val fields = saved.drop(1).chunked(3).associate { (name, text, removed) ->
                val field = BulkField.entries.firstOrNull { it.name == name } ?: return null
                field to BulkFieldState(text, removed == "1")
            }
            return BulkTagForm(fields, cover)
        }
    }
}

/** The selected tracks, split by what their files allow: edited, or set aside with the reason (spec §6.2). */
internal class BulkTargets(
    val editable: List<Pair<TrackDescriptor, TagSnapshot>>,
    val notEditable: List<Pair<TrackDescriptor, TagProblem>>,
)

internal fun bulkTargets(tracks: List<TrackDescriptor>, reads: List<TagFileRead>): BulkTargets {
    val editable = ArrayList<Pair<TrackDescriptor, TagSnapshot>>()
    val notEditable = ArrayList<Pair<TrackDescriptor, TagProblem>>()
    tracks.zip(reads).forEach { (track, read) ->
        when (read) {
            is TagFileRead.Ready -> editable += track to read.snapshot
            is TagFileRead.NotEditable -> notEditable += track to read.problem
        }
    }
    return BulkTargets(editable, notEditable)
}

/** How a file is named in a result: its title, else its file name, else its key. */
internal fun trackLabel(track: TrackDescriptor?, key: String): String =
    track?.title?.takeIf(String::isNotBlank) ?: track?.fileName?.takeIf(String::isNotBlank) ?: key
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*BulkTagFormTest*'`
Expected: PASS (9 tests).

- [ ] **Step 5: Add the English strings**

In `values/strings.xml`, after the Task 7 strings:

```xml
    <string name="action_edit_album">Edit album</string>
    <string name="action_edit_artist">Edit artist</string>
    <plurals name="count_files">
        <item quantity="one">%1$d file</item>
        <item quantity="other">%1$d files</item>
    </plurals>
    <plurals name="count_fields">
        <item quantity="one">%1$d field</item>
        <item quantity="other">%1$d fields</item>
    </plurals>
    <!-- %1$s is a count_fields fragment, %2$s a count_files fragment, both already pluralized. -->
    <string name="bulk_save">Change %1$s in %2$s</string>
    <string name="bulk_reading">Reading %1$d of %2$d…</string>
    <plurals name="bulk_not_editable">
        <item quantity="one">%1$d file can't be edited and will be left as it is:</item>
        <item quantity="other">%1$d files can't be edited and will be left as they are:</item>
    </plurals>
    <plurals name="bulk_more_files">
        <item quantity="one">and %1$d more</item>
        <item quantity="other">and %1$d more</item>
    </plurals>
    <string name="bulk_nothing_editable">None of these files can be edited.</string>
    <string name="bulk_different">Different values</string>
    <plurals name="bulk_removed">
        <item quantity="one">Removed from this file</item>
        <item quantity="other">Removed from all %1$d</item>
    </plurals>
    <string name="bulk_remove_field">Remove from every file</string>
    <string name="bulk_keep_field">Keep as it is</string>
    <string name="bulk_saving">Saved %1$d of %2$d</string>
    <string name="bulk_stop">Stop</string>
    <string name="bulk_result">Saved %1$d of %2$d.</string>
    <plurals name="bulk_not_changed">
        <item quantity="one">%1$d file not changed:</item>
        <item quantity="other">%1$d files not changed:</item>
    </plurals>
    <string name="bulk_done">Done</string>
    <!-- One line of a result list: %1$s the song, %2$s why it was not changed. -->
    <string name="bulk_file_reason">%1$s — %2$s</string>
```

- [ ] **Step 6: Implement `BulkTagEditorSheet.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

// Imports: the Compose foundation/material3/runtime pieces used below, Icons.Rounded.Close and
// Icons.Rounded.Undo, the string resources above plus info_edit/info_album/info_artist/
// info_album_artist/info_genre/info_year/info_track_total/info_disc_number/info_disc_total/
// info_cancel/info_saving, CoverEdit, TrackDescriptor, Semaphore/withPermit, async/awaitAll, launch.

/** Where an N-track edit was opened from: it names the sheet and puts that field first. */
internal enum class BulkEditScope { ALBUM, ARTIST, SELECTION }

private const val READ_CONCURRENCY = 4
private const val LISTED_FILES = 20

/**
 * Edits the fields [tracks] can share, all at once (spec §6.2–§6.3).
 *
 * The sheet reads every file first, so which files cannot be edited, and why, is known before
 * anything is typed. Those files are set aside, never sent to the writer. [onSaved] runs after
 * any save that wrote at least one file, with the tracks the save was for. A save that wrote every
 * file closes the sheet; any other result stays on screen, file by file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulkTagEditorSheet(
    tracks: List<TrackDescriptor>,
    scope: BulkEditScope,
    onSaved: (List<TrackDescriptor>, TagSaveResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val coroutines = rememberCoroutineScope()
    var reads by remember(tracks) { mutableStateOf<List<TagFileRead>?>(null) }
    var readCount by remember(tracks) { mutableIntStateOf(0) }
    LaunchedEffect(tracks) {
        val permits = Semaphore(READ_CONCURRENCY)
        reads = coroutineScope {
            tracks.map { track ->
                async { permits.withPermit { readTagFile(track).also { readCount++ } } }
            }.awaitAll()
        }
    }
    val targets = remember(reads) { reads?.let { bulkTargets(tracks, it) } }
    val baseline = remember(targets) { targets?.let { BulkBaseline.of(it.editable.map { (_, snapshot) -> snapshot }) } }
    val editable = targets?.editable?.map { it.first }.orEmpty()

    var form by rememberSaveable(
        stateSaver = Saver<BulkTagForm, List<String>>(save = { it.toSaveable() }, restore = { BulkTagForm.fromSaveable(it) }),
    ) { mutableStateOf(BulkTagForm()) }
    var result by rememberSaveable(
        stateSaver = Saver<TagSaveResult?, List<String>>(save = { it?.toSaveable() ?: emptyList() }, restore = { tagSaveResultOf(it)?.takeIf { r -> r.entries.isNotEmpty() } }),
    ) { mutableStateOf(null) }
    var failure by remember { mutableStateOf<TagProblem?>(null) }

    fun forgetPickedCover() {
        (form.cover as? CoverChoice.Replace)?.let { coroutines.launch { deleteTagCover(it.reference) } }
    }

    val saver = rememberTagSaver { finished ->
        if (finished.cancelled) return@rememberTagSaver
        if (finished.savedCount > 0) onSaved(editable, finished)
        if (finished.notChanged.isEmpty()) {
            forgetPickedCover()
            onDismiss()
        } else {
            result = finished
        }
    }
    val saving = saver?.busy == true
    val progress = saver?.progress()
    val pickCover = rememberTagCoverPicker { pick ->
        when (pick) {
            is TagCoverPick.Picked -> {
                forgetPickedCover()
                form = form.copy(cover = CoverChoice.Replace(pick.reference))
            }
            TagCoverPick.Failed -> failure = TagProblem.BAD_IMAGE
            TagCoverPick.Cancelled -> Unit
        }
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !saving || it != SheetValue.Hidden },
    )
    val leave = {
        if (!saving) {
            forgetPickedCover()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = leave,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        when (scope) {
                            BulkEditScope.ALBUM -> Res.string.action_edit_album
                            BulkEditScope.ARTIST -> Res.string.action_edit_artist
                            BulkEditScope.SELECTION -> Res.string.info_edit
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = pluralStringResource(Res.plurals.count_files, tracks.size, tracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val shownResult = result
                when {
                    shownResult != null -> ResultList(shownResult, tracks, saver?.access)
                    targets == null || baseline == null -> Text(
                        stringResource(Res.string.bulk_reading, readCount, tracks.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        if (targets.notEditable.isNotEmpty()) NotEditableList(targets.notEditable)
                        if (editable.isEmpty()) {
                            Text(stringResource(Res.string.bulk_nothing_editable), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            BulkFields(
                                scope = scope,
                                form = form,
                                baseline = baseline,
                                count = editable.size,
                                enabled = !saving,
                                onForm = { form = it },
                            )
                            CoverEditRow(
                                choice = form.cover,
                                currentUri = editable.firstNotNullOfOrNull { it.artworkUri },
                                canRemove = targets.editable.any { it.second.cover != null },
                                otherPictures = 0,
                                enabled = !saving,
                                onReplace = pickCover,
                                onRemove = { form = form.copy(cover = CoverChoice.Remove) },
                                onKeep = {
                                    forgetPickedCover()
                                    form = form.copy(cover = CoverChoice.Keep)
                                },
                            )
                        }
                        failure?.let {
                            Text(tagProblemText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        if (saving) SavingProgress(progress, onStop = { saver?.stop() })
                    }
                }
                Spacer(Modifier.padding(bottom = 8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            if (result != null) {
                Button(
                    onClick = { forgetPickedCover(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.bulk_done)) }
            } else {
                val edits = baseline?.let { form.edits(it) }
                val fieldCount = baseline?.let { form.changedFieldCount(it) } ?: 0
                val fields = pluralStringResource(Res.plurals.count_fields, fieldCount, fieldCount)
                val files = pluralStringResource(Res.plurals.count_files, editable.size, editable.size)
                EditorActions(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    cancelLabel = stringResource(Res.string.info_cancel),
                    confirmLabel = if (saving) stringResource(Res.string.info_saving) else stringResource(Res.string.bulk_save, fields, files),
                    confirmEnabled = fieldCount > 0 && edits?.numbersAreValid == true && editable.isNotEmpty(),
                    busy = saving,
                    onCancel = leave,
                    onConfirm = {
                        val base = baseline ?: return@EditorActions
                        failure = null
                        coroutines.launch {
                            val cover = when (val choice = form.cover) {
                                CoverChoice.Keep -> CoverEdit.Keep
                                CoverChoice.Remove -> CoverEdit.Remove
                                is CoverChoice.Replace ->
                                    readTagCover(choice.reference)?.let { CoverEdit.Replace(it, tagCoverMime(choice.reference)) }
                            }
                            when {
                                cover == null -> failure = TagProblem.BAD_IMAGE
                                saver == null -> failure = TagProblem.FAILED
                                else -> saver.start(editable, form.edits(base, cover))
                            }
                        }
                    },
                )
            }
        }
    }
}

/** The fields in the order the entry point suggests: an artist page leads with the artist. */
@Composable
private fun BulkFields(
    scope: BulkEditScope,
    form: BulkTagForm,
    baseline: BulkBaseline,
    count: Int,
    enabled: Boolean,
    onForm: (BulkTagForm) -> Unit,
) {
    val text = listOf(BulkField.ARTIST, BulkField.ALBUM, BulkField.ALBUM_ARTIST, BulkField.GENRE)
    val ordered = when (scope) {
        BulkEditScope.ALBUM -> listOf(BulkField.ALBUM, BulkField.ALBUM_ARTIST, BulkField.ARTIST, BulkField.GENRE)
        else -> text
    }
    for (field in ordered) BulkTextField(field, form, baseline, count, enabled, onForm)
    BulkTextField(BulkField.YEAR, form, baseline, count, enabled, onForm, KeyboardType.Number, ::yearInput)
    BulkTextField(BulkField.TRACK_TOTAL, form, baseline, count, enabled, onForm, KeyboardType.Number, ::numberInput)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BulkTextField(BulkField.DISC_NUMBER, form, baseline, count, enabled, onForm, KeyboardType.Number, ::numberInput, Modifier.weight(1f))
        BulkTextField(BulkField.DISC_TOTAL, form, baseline, count, enabled, onForm, KeyboardType.Number, ::numberInput, Modifier.weight(1f))
    }
    if (form.edits(baseline).numbersAreValid.not()) {
        Text(tagProblemText(TagProblem.BAD_NUMBER), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * One field in its three states. The clear control removes the field from every file, a visible,
 * deliberate choice; undo returns it to Keep. Typing into a removed field sets it instead.
 */
@Composable
private fun BulkTextField(
    field: BulkField,
    form: BulkTagForm,
    baseline: BulkBaseline,
    count: Int,
    enabled: Boolean,
    onForm: (BulkTagForm) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    filter: (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    val removed = form.removed(field)
    EditorTextField(
        label = stringResource(field.label()),
        value = if (removed) "" else form.text(field, baseline),
        onValueChange = { onForm(form.typed(field, filter(it))) },
        enabled = enabled,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
        placeholder = when {
            removed -> pluralStringResource(Res.plurals.bulk_removed, count, count)
            baseline.shared[field] == SharedValue.Different -> stringResource(Res.string.bulk_different)
            else -> null
        },
        trailingIcon = {
            if (removed) {
                IconButton(onClick = { onForm(form.keep(field)) }, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(Res.string.bulk_keep_field))
                }
            } else {
                IconButton(onClick = { onForm(form.remove(field)) }, enabled = enabled) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.bulk_remove_field))
                }
            }
        },
    )
}

private fun BulkField.label(): StringResource = when (this) {
    BulkField.ARTIST -> Res.string.info_artist
    BulkField.ALBUM -> Res.string.info_album
    BulkField.ALBUM_ARTIST -> Res.string.info_album_artist
    BulkField.GENRE -> Res.string.info_genre
    BulkField.YEAR -> Res.string.info_year
    BulkField.TRACK_TOTAL -> Res.string.info_track_total
    BulkField.DISC_NUMBER -> Res.string.info_disc_number
    BulkField.DISC_TOTAL -> Res.string.info_disc_total
}

@Composable
private fun NotEditableList(files: List<Pair<TrackDescriptor, TagProblem>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pluralStringResource(Res.plurals.bulk_not_editable, files.size, files.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        files.take(LISTED_FILES).forEach { (track, problem) ->
            FileReasonLine(trackLabel(track, track.id.value), tagProblemText(problem))
        }
        MoreFiles(files.size - LISTED_FILES)
    }
}

@Composable
private fun ResultList(result: TagSaveResult, tracks: List<TrackDescriptor>, access: TagWriteAccess?) {
    val byKey = remember(tracks, access) { tracks.associateBy { access?.keyOf(it) ?: it.id.value } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.bulk_result, result.savedCount, result.entries.size),
            style = MaterialTheme.typography.bodyLarge,
        )
        val notChanged = result.notChanged
        if (notChanged.isNotEmpty()) {
            Text(pluralStringResource(Res.plurals.bulk_not_changed, notChanged.size, notChanged.size), style = MaterialTheme.typography.bodyMedium)
            notChanged.take(LISTED_FILES).forEach { entry ->
                FileReasonLine(trackLabel(byKey[entry.key], entry.key), entry.problem?.let { tagProblemText(it) }.orEmpty())
            }
            MoreFiles(notChanged.size - LISTED_FILES)
        }
    }
}

@Composable
private fun FileReasonLine(name: String, reason: String) {
    Text(
        text = stringResource(Res.string.bulk_file_reason, name, reason),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MoreFiles(count: Int) {
    if (count > 0) {
        Text(
            pluralStringResource(Res.plurals.bulk_more_files, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavingProgress(progress: TagWriteProgress?, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(Res.string.info_saving), style = MaterialTheme.typography.bodyMedium)
        } else {
            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(Res.string.bulk_saving, progress.done, progress.total), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onStop) { Text(stringResource(Res.string.bulk_stop)) }
    }
}
```

Replace the imports comment with the real import list the compiler asks for. `Icons.AutoMirrored.Rounded.Undo` and `Icons.Rounded.Close` come from `material-icons-extended`, which the app already uses (check `SelectionActionBar`'s icons; if `Undo` is missing, use `Icons.Rounded.Restore`).

- [ ] **Step 7: Run the tests and both compiles**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 684 tests.

Run: `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q && ./gradlew :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: success. The sheet is not reachable yet; Task 13 adds its entry points.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/BulkTagForm.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/BulkTagEditorSheet.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/BulkTagFormTest.kt
git commit -m "feat(tags): many songs' tags can be edited at once, keeping, setting or removing each field, with an exact result"
```

---

### Task 9: The library knows each album's artist and groups compilations by it

Spec §3.5. The album artist is read on every platform. It then becomes the album's identity, so a compilation whose tracks each have their own artist stops splitting into several albums, and the album's subtitle shows it.

**Sources:**
- **Android 11+:** MediaStore's `ALBUM_ARTIST` column (public since API 30).
- **Android 7–10:** the same column, used only when the provider actually has it (probed once). Otherwise the existing tag-enrichment pass reads it from the file (`EmbeddedTagFacts.albumArtist`: Vorbis `ALBUMARTIST` and its spellings, ID3 `TPE2`). Which of the two applies is checked on the API 24/28/29 emulators in plan 4.
- **iOS:** `ALBUMARTIST` / `TPE2` / `aART` from imported files, and `MPMediaItem.albumArtist` from the Music library.

**Grouping:**
- Within one album title, a track's owner is its album artist, or its artist when it has none.
- For a library without album artists, every key and grouping is exactly as today.

**Files:**
- Modify: `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/TrackTypes.kt`
- Modify: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagFacts.kt`
- Modify: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/GenreTags.kt` (`id3Comments`)
- Modify: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/LibraryCatalog.kt`
- Modify: `core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/MusicLibrary.android.kt`
- Modify: `core/library/src/iosMain/kotlin/io/github/nikitasud/latentjam/library/MusicLibrary.ios.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/GenreEnrichment.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackInfoSheet.kt` (an "Album artist" row in the info view)
- Test: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/LibraryCatalogTest.kt`
- Test: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/TagFactsTest.kt`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/GenreEnrichmentTest.kt`

**Interfaces:**
- Consumes:
  - `Id3Tags.textValues(prefix: ByteArray, id: String): List<String>`;
  - `Id3Tags.updateTag(bytes, TagEdits)` (tests only).
- Produces:
  - `TrackDescriptor.albumArtist: String? = null` (the last constructor parameter);
  - `EmbeddedTagFacts.albumArtist: String? = null`;
  - `AlbumGroup.artist` is the album artist when any track has one.

- [ ] **Step 1: Write the failing tests**

In `LibraryCatalogTest`, add an `albumArtist: String? = null` parameter to the `track(...)` helper (passed to `TrackDescriptor`), then:

```kotlin
    @Test
    fun aCompilationTaggedWithAnAlbumArtistIsOneAlbum() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "a", artist = "Queen", album = "Hits 1985", artworkUri = "art://1", albumArtist = "Various Artists"),
                track("2", title = "b", artist = "ABBA", album = "Hits 1985", artworkUri = "art://2", albumArtist = "Various Artists"),
                track("3", title = "c", artist = "Sade", album = "Hits 1985", albumArtist = "Various Artists"),
            ),
        )
        val album = catalog.albums.single()
        assertEquals("Various Artists", album.artist)
        assertEquals(3, album.tracks.size)
    }

    @Test
    fun theAlbumArtistIsTheAlbumsSubtitleOverAFeaturedCredit() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "a", artist = "Singer feat. Guest", album = "Record", albumArtist = "Singer"),
                track("2", title = "b", artist = "Singer", album = "Record", albumArtist = "Singer"),
            ),
        )
        assertEquals("Singer", catalog.albums.single().artist)
    }

    @Test
    fun withoutAlbumArtistsGroupingAndKeysAreUnchanged() {
        val tracks = listOf(
            track("1", title = "b", artist = "Queen", album = "Greatest Hits", artworkUri = "art://1"),
            track("2", title = "c", artist = "ABBA", album = "Greatest Hits", artworkUri = "art://2"),
            track("3", title = "d", artist = "X", album = "Demo"),
        )
        val plain = LibraryCatalog.build(tracks)
        // An album artist equal to the artist is the same identity: tagging it changes nothing.
        val tagged = LibraryCatalog.build(tracks.map { it.copy(albumArtist = it.artist) })
        assertEquals(plain.albums.map { it.key }, tagged.albums.map { it.key })
        assertEquals(3, plain.albums.size)
    }
```

In `TagFactsTest`:

```kotlin
    @Test
    fun theAlbumArtistComesFromAnySpellingOfTheField() {
        assertEquals("Various Artists", TagFacts.fromComments(listOf("ALBUMARTIST" to " Various Artists ")).albumArtist)
        assertEquals("VA", TagFacts.fromComments(listOf("ALBUM ARTIST" to "VA")).albumArtist)
        assertEquals("VA", TagFacts.fromComments(listOf("ALBUM_ARTIST" to "VA", "ALBUMARTIST" to "Other")).albumArtist)
        assertNull(TagFacts.fromComments(listOf("ALBUMARTIST" to "  ")).albumArtist)
    }

    @Test
    fun theAlbumArtistIsReadFromAnId3Tpe2Frame() {
        val tagged = Id3Tags.updateTag(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(1024), TagEdits(albumArtist = "Various Artists"))!!
        val facts = TagFacts.embedded(object : GenreTags.ByteSource {
            var at = 0
            override fun read(count: Int): ByteArray? =
                if (at + count > tagged.size) null else tagged.copyOfRange(at, at + count).also { at += count }
            override fun readUpTo(count: Int): ByteArray =
                tagged.copyOfRange(at, minOf(tagged.size, at + count)).also { at += it.size }
            override fun skip(count: Long): Boolean = (at + count <= tagged.size).also { if (it) at += count.toInt() }
        })
        assertEquals("Various Artists", facts?.albumArtist)
    }
```

If `TagFactsTest` already has a byte-array `GenreTags.ByteSource` helper, use it instead of the anonymous object.

In `GenreEnrichmentTest`:
1. Add `albumArtist = "Various Artists"` to `facts`, and `albumArtist = facts.albumArtist` to `enriched`.
2. Change the literal `"v3|"` in `v2CacheIsReadAgainOnceToLearnLanguage` to `"v4|"`. Its `substringBeforeLast('|')` then removes the album-artist field; remove one more field so the line has the v2 shape (`.substringBeforeLast('|').substringBeforeLast('|')`).
3. Bring any other literal payload line in the file to the v4 layout (one more `|`-separated field, the hex album artist, at the end).
4. Add:

```kotlin
    @Test
    fun aV3CacheIsReadAgainOnceToLearnTheAlbumArtist() = runTest {
        val settings = MemorySettings()
        GenreEnrichment(settings) { facts }.backfill(listOf(track))
        settings.trackGenresPayload = settings.trackGenresPayload!!
            .replaceFirst("v4|", "v3|").substringBeforeLast('|')
        var reads = 0
        val upgraded = GenreEnrichment(settings) { reads++; facts }
        assertTrue(upgraded.backfill(listOf(track)))
        assertEquals(listOf(enriched), upgraded.apply(listOf(track)))
        assertEquals(1, reads)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --rerun --tests '*LibraryCatalogTest*' --tests '*TagFactsTest*'`
Expected: compilation FAILS (`albumArtist` is not a parameter of `TrackDescriptor` / `EmbeddedTagFacts`).

- [ ] **Step 3: Add the field to the descriptor and the facts**

In `TrackTypes.kt`, append after `discNumber`:

```kotlin
    /**
     * The album's own artist (`TPE2`, `ALBUMARTIST`, `aART`): "Various Artists" on a compilation.
     * Albums group by it when present, so a compilation's tracks stay one album. Null when untagged.
     */
    public val albumArtist: String? = null,
```

In `TagFacts.kt`:
1. Add `public val albumArtist: String? = null` to `EmbeddedTagFacts`, with the KDoc line "The album's artist verbatim (`ALBUMARTIST`, ID3 `TPE2`); null when absent."
2. Include it in `isEmpty`.
3. Update the class KDoc list with an `[albumArtist]` item.
4. In `fromComments`, add a `var albumArtist: String? = null` and this branch:

```kotlin
                "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST", "TPE2" ->
                    if (albumArtist == null) albumArtist = value.trim().takeIf { it.isNotEmpty() }
```

5. Pass `albumArtist = albumArtist` to the returned facts.

In `GenreTags.id3Comments`, after the `TLAN` line:

```kotlin
        Id3Tags.textValues(prefix, "TPE2").forEach { comments.add("ALBUMARTIST" to it) }
```

MP4 files are not read by `embeddedComments`. On Android 7–10 their album artist comes only from the MediaStore column. This is a known gap, recorded for plan 4's API 24/28/29 check.

- [ ] **Step 4: Group albums by their owner**

In `LibraryCatalog.kt`:

```kotlin
        /**
         * Whose album a track belongs to: its album artist when tagged ("Various Artists" on a
         * compilation), otherwise its artist. Same-named albums are told apart by this, so a
         * compilation's per-track artists no longer split it.
         */
        private fun TrackDescriptor.albumOwner(): String? = albumArtist?.takeIf { it.isNotBlank() } ?: artist
```

Replace `sameTitle.mapNotNull { it.artist.normalizedKey() }` in `albumGroups` with `sameTitle.mapNotNull { it.albumOwner().normalizedKey() }`. In `albumDiscriminator`, replace `AlbumDiscriminator.Artist(artist.normalizedKey())` with `AlbumDiscriminator.Artist(albumOwner().normalizedKey())`. In `build`, the album's subtitle becomes:

```kotlin
                        artist = grouped.firstNotNullOfOrNull { it.albumArtist?.takeIf(String::isNotBlank) }
                            ?: grouped.firstNotNullOfOrNull { it.artist },
```

Update the `albumGroups` KDoc: "One album artist (or, untagged, one artist) + one normalized title…".

- [ ] **Step 5: Read it on Android**

In `MusicLibrary.android.kt`:

```kotlin
    /**
     * Whether MediaStore has an album-artist column. It is public from API 30, but the scanner filled
     * `album_artist` long before. Below 30 it is used only when the provider actually has it;
     * otherwise tag enrichment reads the file (spec §3.5). A query naming a missing column throws.
     */
    private val albumArtistColumn: Boolean by lazy {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R || runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(ALBUM_ARTIST), "0", null, null,
            )?.use { true } == true
        }.getOrDefault(false)
    }
```

1. Add `if (albumArtistColumn) add(ALBUM_ARTIST)` to the projection.
2. Add `val albumArtistIndex = cursor.getColumnIndex(ALBUM_ARTIST)` with the other indices.
3. In the descriptor, add `albumArtist = if (albumArtistIndex >= 0) cursor.getString(albumArtistIndex).knownTagOrNull() else null`.
4. In the companion: `const val ALBUM_ARTIST = "album_artist"`, with the comment "MediaStore.Audio.AudioColumns.ALBUM_ARTIST, whose constant is hidden below API 30."

- [ ] **Step 6: Read it on iOS**

In `MusicLibrary.ios.kt`:
- `read(...)`: add `albumArtist = asset.rawString("ALBUMARTIST", "ALBUM ARTIST", "TPE2", "aART"),`. The `artist` fallback to `ALBUMARTIST` stays: a file with only an album artist still shows an artist.
- `scanDeviceLibrary()`: add `albumArtist = item.albumArtist.knownOrNull(),`.

- [ ] **Step 7: Carry it through tag enrichment (cache v4)**

In `GenreEnrichment.kt`:
1. `Stored` gains `val albumArtist: String?`.
2. `apply` sets `albumArtist = stored.albumArtist ?: track.albumArtist` and compares it in the unchanged check.
3. `backfill` stores `albumArtist = facts.albumArtist`.
4. `changes` adds `|| (albumArtist != null && albumArtist != track.albumArtist)`.
5. `FORMAT` becomes `"v4"`, with the KDoc line "v4 the album artist".
6. `encode` appends `stored.albumArtist.orEmpty().hex()`.
7. `decode` requires `parts.size == 8` and reads `albumArtist = parts[7].unhex()?.takeIf { it.isNotEmpty() }?.let(TextRepair::repair)`. If `parts[7].unhex()` is null, drop the line.

Update the class KDoc's first sentence to name the album artist.

In `TrackInfoSheet`'s read mode, after the album row: `InfoRow(stringResource(Res.string.info_album_artist), track.albumArtist)`.

- [ ] **Step 8: Run all affected suites and compiles**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 611 tests (606 + 5).

Run: `./gradlew :core:smart:testAndroidHostTest --offline -q --rerun`
Expected: PASS, the baseline count (only the new default parameter).

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 685 tests.

Run: `./gradlew :core:library:compileTestKotlinIosSimulatorArm64 :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q && ./gradlew :androidApp:compileDebugKotlin --offline -q`
Expected: success.

- [ ] **Step 9: Commit**

```bash
git add core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/TrackTypes.kt core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagFacts.kt core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/GenreTags.kt core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/LibraryCatalog.kt core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/MusicLibrary.android.kt core/library/src/iosMain/kotlin/io/github/nikitasud/latentjam/library/MusicLibrary.ios.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/GenreEnrichment.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackInfoSheet.kt core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/LibraryCatalogTest.kt core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/TagFactsTest.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/GenreEnrichmentTest.kt
git commit -m "feat(library): albums group by their album artist, so a compilation stays one album and shows who it is by"
```

---

### Task 10: An edited cover shows everywhere at once on Android

Spec §6.5 "Artwork caches". Android names an album's art `content://media/external/audio/albumart/<albumId>`, and an edit does not change that URI. Everything that shows or tints by artwork caches by the URI string:
- Coil's memory cache (lists, the hero, `player-cover:$uri`);
- the accent colour (`TrackAccent`);
- Media3's notification bitmap.

So every one of them would keep the old cover.

The scan now appends the album's version to the URI (`…/albumart/12?v=3fa2`). The version is the same for every track of the album, since grouping compares these URIs. It changes when any of the album's tracks changes revision. MediaProvider and Coil's music-thumbnail path read only the URI's path, so the query changes nothing there. iOS needs nothing: its artwork files are already named by content.

**Files:**
- Create: `core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/AlbumArtVersions.kt`
- Modify: `core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/MusicLibrary.android.kt` (`queryTracks` returns versioned tracks)
- Test: `core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/AlbumArtVersionsTest.kt`

**Interfaces:**
- Consumes: `TrackDescriptor.artworkUri`, `sourceRevision`.
- Produces: `internal fun withAlbumArtVersions(tracks: List<TrackDescriptor>): List<TrackDescriptor>`.

- [ ] **Step 1: Write the failing test**

`AlbumArtVersionsTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class AlbumArtVersionsTest {

    private val art = "content://media/external/audio/albumart/12"

    private fun track(id: String, revision: String, artwork: String? = art) =
        TrackDescriptor(TrackId(id), artworkUri = artwork, sourceRevision = revision)

    @Test
    fun everyTrackOfAnAlbumSharesItsArtworkUri() {
        val versioned = withAlbumArtVersions(listOf(track("1", "a"), track("2", "b")))
        assertEquals(1, versioned.map { it.artworkUri }.distinct().size)
        assertTrue(versioned.first().artworkUri!!.startsWith("$art?v="))
    }

    @Test
    fun albumArtVersionChangesWhenAnyTrackOfTheAlbumChanges() {
        val before = withAlbumArtVersions(listOf(track("1", "a"), track("2", "b")))
        val after = withAlbumArtVersions(listOf(track("1", "a"), track("2", "b-edited")))
        assertNotEquals(before.first().artworkUri, after.first().artworkUri)
    }

    @Test
    fun theVersionDoesNotDependOnTrackOrder() {
        val one = withAlbumArtVersions(listOf(track("1", "a"), track("2", "b")))
        val other = withAlbumArtVersions(listOf(track("2", "b"), track("1", "a")))
        assertEquals(one.first().artworkUri, other.first().artworkUri)
    }

    @Test
    fun otherAlbumsKeepTheirVersionAndArtworklessTracksStayAsTheyAre() {
        val other = "content://media/external/audio/albumart/13"
        val before = withAlbumArtVersions(listOf(track("1", "a"), track("3", "c", other), track("4", "d", null)))
        val after = withAlbumArtVersions(listOf(track("1", "a-edited"), track("3", "c", other), track("4", "d", null)))
        assertEquals(before[1].artworkUri, after[1].artworkUri)
        assertNull(after[2].artworkUri)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --rerun --tests '*AlbumArtVersionsTest*'`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

`AlbumArtVersions.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Album-art URIs carrying their album's version.
 *
 * A tag edit changes a file's revision but not its album's `…/albumart/<id>` URI, and the image
 * loader, the accent colour and Media3's notification all cache by URI string. Without a version
 * every one of them keeps the old cover (spec §6.5).
 *
 * The version is the sum of the album's track revisions' hashes: the same for every track of the
 * album, so grouping, which compares these URIs, is unaffected. It does not depend on track order,
 * and it changes when any track changes. MediaProvider opens album art by the URI's path alone, so
 * the query is invisible to it.
 */
internal fun withAlbumArtVersions(tracks: List<TrackDescriptor>): List<TrackDescriptor> {
    val versions = HashMap<String, Long>()
    for (track in tracks) {
        val uri = track.artworkUri ?: continue
        versions[uri] = (versions[uri] ?: 0L) + (track.sourceRevision?.hashCode() ?: 0)
    }
    return tracks.map { track ->
        val uri = track.artworkUri ?: return@map track
        track.copy(artworkUri = "$uri?v=${versions.getValue(uri).toULong().toString(16)}")
    }
}
```

In `MusicLibrary.android.kt`, `queryTracks` ends with `withAlbumArtVersions(tracks)` instead of `tracks`. Both `scan()` and `allKnownTracks()` go through it, so hidden and visible tracks agree on the version.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 615 tests.

Run: `./gradlew :androidApp:compileDebugKotlin --offline -q`
Expected: success.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/AlbumArtVersions.kt core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/MusicLibrary.android.kt core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/AlbumArtVersionsTest.kt
git commit -m "fix(library): a new album cover shows in lists, the player and the notification right after the edit"
```

---

### Task 11: Editing the song that is playing updates the player without interrupting it

Spec §6.5 "Now playing". Both controllers hold queued tracks by value:
- Android: in `pool`, `poolById` and `smartById`, and in each Media3 `MediaItem`'s frozen metadata.
- iOS: in `queue` and `pool`, plus an artwork cache keyed by track id.

A new `PlaybackController.refreshTracks(tracks)` swaps in fresh descriptors by id and changes nothing about what plays: queue, order, position and play state stay.
- **Android:** the metadata of each matching `MediaItem` is replaced in place with `replaceMediaItem`. Media3 updates an item with the same id and URI without re-preparing it, as `publishFallbackArtwork` already relies on. Then the notification and lock screen follow.
- **iOS:** the cached cover for those ids is dropped, and Now Playing is republished.

**Files:**
- Create: `core/playback/src/commonMain/kotlin/io/github/nikitasud/latentjam/playback/QueueRefresh.kt`
- Modify: `core/playback/src/commonMain/kotlin/io/github/nikitasud/latentjam/playback/PlaybackController.kt`
- Modify: `core/playback/src/androidMain/kotlin/io/github/nikitasud/latentjam/playback/PlaybackController.android.kt`
- Modify: `core/playback/src/iosMain/kotlin/io/github/nikitasud/latentjam/playback/PlaybackController.ios.kt`
- Modify (fakes): `core/playback/src/commonTest/kotlin/io/github/nikitasud/latentjam/playback/SleepTimerTest.kt`, `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/PlaybackHistoryRecorderTest.kt`, `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/LoudnessNormalizationTest.kt`
- Test: `core/playback/src/commonTest/kotlin/io/github/nikitasud/latentjam/playback/QueueRefreshTest.kt`

**Interfaces:**
- Consumes: Android `pool`, `poolById`, `smartById`, `rebuildQueueSnapshot()`, `pushState()`, `toMediaItem()`; iOS `queue`, `pool`, `artworkCache`, `realArtworkIds`, `latentArtworkIds`, `artworkOrder`, `invalidateNowPlayingInfo()`, `pushState()`.
- Produces:
  - `public suspend fun PlaybackController.refreshTracks(tracks: List<TrackDescriptor>)`
  - `internal fun refreshedTracks(queue: List<TrackDescriptor>, updates: Map<TrackId, TrackDescriptor>): List<TrackDescriptor>?`

- [ ] **Step 1: Write the failing test**

`QueueRefreshTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class QueueRefreshTest {

    private fun track(id: String, title: String) = TrackDescriptor(TrackId(id), title = title)

    @Test
    fun refreshedTracksReplaceQueuedDescriptorsInPlace() {
        val queue = listOf(track("a", "A"), track("b", "B"), track("a", "A"), track("c", "C"))
        val refreshed = refreshedTracks(queue, mapOf(TrackId("a") to track("a", "A2"), TrackId("x") to track("x", "X")))
        assertEquals(listOf("A2", "B", "A2", "C"), refreshed?.map { it.title })
    }

    @Test
    fun aRefreshThatChangesNothingSaysSo() {
        val queue = listOf(track("a", "A"))
        assertNull(refreshedTracks(queue, mapOf(TrackId("a") to track("a", "A"))))
        assertNull(refreshedTracks(queue, emptyMap()))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:playback:testAndroidHostTest --offline -q --rerun --tests '*QueueRefreshTest*'`
Expected: compilation FAILS.

- [ ] **Step 3: Implement the common part**

`QueueRefresh.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * [queue] with each track [updates] holds a fresher copy of swapped in, by id, order kept. Null
 * when nothing differs, so a controller can skip republishing.
 */
internal fun refreshedTracks(queue: List<TrackDescriptor>, updates: Map<TrackId, TrackDescriptor>): List<TrackDescriptor>? {
    var changed = false
    val refreshed = queue.map { track ->
        val fresh = updates[track.id]
        if (fresh != null && fresh != track) {
            changed = true
            fresh
        } else {
            track
        }
    }
    return refreshed.takeIf { changed }
}
```

In `PlaybackController.kt`, after `retainQueue`:

```kotlin
    /**
     * Swaps in fresh descriptors for queued tracks whose tags changed (a tag edit). What plays is
     * untouched: queue, order, position and play state stay. The current track's title, artist,
     * album and artwork update in the player, the notification and the lock screen. Tracks that
     * are not queued are ignored.
     */
    public suspend fun refreshTracks(tracks: List<TrackDescriptor>)
```

In the three test fakes, add `override suspend fun refreshTracks(tracks: List<TrackDescriptor>) = Unit`.

- [ ] **Step 4: Implement Android**

In `PlaybackController.android.kt`, extract the metadata of `toMediaItem` into a helper and add `refreshTracks`:

```kotlin
    private fun TrackDescriptor.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setUri(audioUri)
        .setMediaMetadata(mediaMetadata())
        .build()

    private fun TrackDescriptor.mediaMetadata(): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(artworkUri?.let(Uri::parse))
        .build()

    /**
     * This item with [fresh]'s tags. Id and URI stay, so Media3 updates the item in place without
     * re-preparing it. A fallback cover the item carries as data (see [publishFallbackArtwork])
     * stays until real artwork replaces it.
     */
    private fun MediaItem.withTagsOf(fresh: TrackDescriptor): MediaItem {
        val metadata = mediaMetadata.buildUpon()
            .setTitle(fresh.title)
            .setArtist(fresh.artist)
            .setAlbumTitle(fresh.album)
        fresh.artworkUri?.let { metadata.setArtworkData(null, null).setArtworkUri(Uri.parse(it)) }
        return buildUpon().setMediaMetadata(metadata.build()).build()
    }

    override suspend fun refreshTracks(tracks: List<TrackDescriptor>): Unit = withContext(Dispatchers.Main) {
        if (tracks.isEmpty()) return@withContext
        val updates = tracks.associateBy { it.id }
        refreshedTracks(pool, updates)?.let { pool = it }
        poolById = poolById.mapValues { (id, track) -> updates[TrackId(id)] ?: track }
        smartById = smartById.mapValues { (id, track) -> updates[TrackId(id)] ?: track }
        controller?.let { player ->
            for (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                val fresh = updates[TrackId(item.mediaId)] ?: continue
                player.replaceMediaItem(index, item.withTagsOf(fresh))
            }
        }
        rebuildQueueSnapshot()
        pushState()
    }
```

Check with `grep` whether the controller keeps any other copy of queued descriptors (for example a resume snapshot, or `MediaBrowseRegistry`'s active resumption). Refresh each one the same way, or say in a comment why it can stay.

- [ ] **Step 5: Implement iOS**

In `PlaybackController.ios.kt`:

```kotlin
    override suspend fun refreshTracks(tracks: List<TrackDescriptor>): Unit = withContext(Dispatchers.Main) {
        if (tracks.isEmpty()) return@withContext
        val updates = tracks.associateBy { it.id }
        val refreshedQueue = refreshedTracks(queue, updates)
        val refreshedPool = refreshedTracks(pool, updates)
        if (refreshedQueue == null && refreshedPool == null) return@withContext
        refreshedQueue?.let { queue = it }
        refreshedPool?.let { pool = it }
        // Covers are cached by track id, so an edited cover must be read again.
        tracks.forEach { forgetArtwork(it.id.value) }
        invalidateNowPlayingInfo()
        pushState()
    }

    private fun forgetArtwork(id: String) {
        artworkCache.remove(id)
        realArtworkIds.remove(id)
        latentArtworkIds.remove(id)
        artworkOrder.remove(id)
    }
```

- [ ] **Step 6: Run the tests and compiles**

Run: `./gradlew :core:playback:testAndroidHostTest --offline -q --rerun`
Expected: PASS, the baseline + 2.

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 685 tests.

Run: `./gradlew :core:playback:compileTestKotlinIosSimulatorArm64 :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q && ./gradlew :androidApp:compileDebugKotlin --offline -q`
Expected: success.

- [ ] **Step 7: Commit**

```bash
git add core/playback/src composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/PlaybackHistoryRecorderTest.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/LoudnessNormalizationTest.kt
git commit -m "feat(playback): the playing song's new tags and cover show in the player, notification and lock screen without a pause"
```

---

### Task 12: SMART keeps a song's audio analysis through a tag edit

Spec §6.5 "SMART keeps its audio analysis" and §8.1. `sourceRevision` is part of the audio vector's identity. Any byte change, even a cover, therefore drops the vector at the next `synchronizeLibrary` and forces a fresh analysis, the most expensive thing the app does.

After a verified tag-only write, the audio is proven unchanged: an in-place patch never touches it, and a rewrite checks its audio checksum. So:
1. The editor records a **carry-over** per saved file: track id, old revision, and the new file length (`FileWriteResult.newLength`).
2. Before each index sync, the engine re-keys the stored audio vector to the track's new identity. It does so only when:
   - the vector was made from the old revision, **and**
   - the file now has exactly the expected length.
3. A mismatch means the file changed again by something else, and it is re-analysed as usual.
4. The text vector is re-embedded as usual, because artist, genre and year did change.
5. Carry-overs are persisted. They are dropped once settled, which means applied, or the track appeared with a new revision that did not match. A carry-over that waited three syncs without its rescan landing is dropped as well; the worst case is one re-analysis.

**Files:**
- Create: `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/AudioCarryOver.kt`
- Modify: `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/SimilarityEngine.kt`
- Modify: `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/DefaultSimilarityEngine.kt`
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AudioCarryOverStore.kt`
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PayloadHex.kt` (move `hex`/`unhex` out of `GenreEnrichment`'s companion)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/GenreEnrichment.kt` (use the moved helpers)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AppSettings.kt`, `composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/AppSettings.android.kt`, `composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/AppSettings.ios.kt`
- Modify (fakes): `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/LocalBackupTest.kt`, `GenreEnrichmentTest.kt`, `LoudnessNormalizationTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AppGraph.kt`
- Test: `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/DefaultSimilarityEngineTest.kt`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/AudioCarryOverStoreTest.kt`

**Interfaces:**
- Consumes: `TagSaveResult` / `TagSaveEntry(status, newLength)` (Task 4); `TrackDescriptor.sizeBytes`, `sourceRevision`.
- Produces:
  - `public data class AudioCarryOver(val trackId: TrackId, val oldRevision: String?, val newLength: Long)`
  - `public data class AudioCarryOverResult(val applied: Set<TrackId>, val settled: Set<TrackId>)`
  - `public suspend fun SimilarityEngine.carryOverAudio(library: List<TrackDescriptor>, carryOvers: List<AudioCarryOver>): AudioCarryOverResult` (default: nothing settled)
  - `internal class AudioCarryOverStore(read: () -> String?, write: (String) -> Unit)`, with `suspend fun add(List<AudioCarryOver>)`, `suspend fun pending(): List<AudioCarryOver>` and `suspend fun settle(offered, result)`
  - `internal fun audioCarryOversOf(tracks: List<TrackDescriptor>, result: TagSaveResult, keyOf: (TrackDescriptor) -> String?): List<AudioCarryOver>`
  - `AppGraph.audioCarryOvers: AudioCarryOverStore`
  - `AppSettings.readAudioCarryOversPayload(): String?` / `writeAudioCarryOversPayload(payload: String)`

- [ ] **Step 1: Write the failing engine tests**

In `DefaultSimilarityEngineTest`:

```kotlin
    private val edited = TrackId("edited")
    private val beforeEdit = TrackDescriptor(
        id = edited,
        audioUri = "content://media/1",
        durationMs = 1_000,
        sourceRevision = "android-mediastore-v1:10:20:30",
        sizeBytes = 10,
    )
    private val afterEdit = beforeEdit.copy(sourceRevision = "android-mediastore-v1:12:25:31", sizeBytes = 12, title = "New")

    @Test
    fun aVerifiedTagEditKeepsTheAudioVectorAcrossARestart() = runTest {
        val store = FakeIndexStore()
        val first = engine(FakeEmbeddingBackend(mutableMapOf(edited to floatArrayOf(1f, 0f, 0f))), store)
        first.initialize()
        first.indexLibrary(listOf(beforeEdit))

        val result = first.carryOverAudio(listOf(afterEdit), listOf(AudioCarryOver(edited, beforeEdit.sourceRevision, 12)))
        assertEquals(setOf(edited), result.applied)
        assertEquals(setOf(edited), result.settled)
        assertEquals(0, first.synchronizeLibrary(listOf(afterEdit)))
        assertContentEquals(floatArrayOf(1f, 0f, 0f), first.embedding(edited))

        val secondBackend = FakeEmbeddingBackend()
        val restarted = engine(secondBackend, store)
        restarted.initialize()
        assertEquals(0, restarted.synchronizeLibrary(listOf(afterEdit)))
        assertEquals(1, restarted.indexLibrary(listOf(afterEdit)).skipped)
        assertEquals(0, secondBackend.embedCalls)
    }

    @Test
    fun aFileThatChangedAgainIsReanalysed() = runTest {
        val engine = engine(FakeEmbeddingBackend(mutableMapOf(edited to floatArrayOf(1f, 0f, 0f))), FakeIndexStore())
        engine.initialize()
        engine.indexLibrary(listOf(beforeEdit))
        val result = engine.carryOverAudio(listOf(afterEdit.copy(sizeBytes = 13)), listOf(AudioCarryOver(edited, beforeEdit.sourceRevision, 12)))
        assertEquals(emptySet(), result.applied)
        assertEquals(setOf(edited), result.settled)
        assertEquals(1, engine.synchronizeLibrary(listOf(afterEdit.copy(sizeBytes = 13))))
    }

    @Test
    fun aCarryOverWaitsWhileItsTrackStillShowsTheOldRevision() = runTest {
        val engine = engine(FakeEmbeddingBackend(mutableMapOf(edited to floatArrayOf(1f, 0f, 0f))), FakeIndexStore())
        engine.initialize()
        engine.indexLibrary(listOf(beforeEdit))
        val result = engine.carryOverAudio(listOf(beforeEdit), listOf(AudioCarryOver(edited, beforeEdit.sourceRevision, 12)))
        assertEquals(AudioCarryOverResult(emptySet(), emptySet()), result)
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:smart:testAndroidHostTest --offline -q --rerun --tests '*DefaultSimilarityEngineTest*'`
Expected: compilation FAILS (`AudioCarryOver`, `carryOverAudio` unresolved).

- [ ] **Step 3: Implement the engine side**

`AudioCarryOver.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * A verified tag-only write of a track's file. Its audio is proven unchanged, so the audio vector
 * made from [oldRevision] still describes it once the file shows up with a new revision and
 * exactly [newLength] bytes.
 */
public data class AudioCarryOver(val trackId: TrackId, val oldRevision: String?, val newLength: Long)

/** [applied]: vectors re-keyed. [settled]: carry-overs done with, applied or found not to match. */
public data class AudioCarryOverResult(val applied: Set<TrackId>, val settled: Set<TrackId>)
```

In `SimilarityEngine`, after `synchronizeLibrary`:

```kotlin
    /**
     * Re-keys audio vectors across verified tag-only writes, before [synchronizeLibrary] would
     * discard them for their file's new revision. A carry-over settles when its track appears in
     * [library] with a revision other than its old one. It applies when the stored vector was made
     * from that old revision and the file is exactly the expected length; otherwise the track is
     * re-analysed as usual. One whose track still shows the old revision stays unsettled: the
     * rescan has not landed yet.
     */
    public suspend fun carryOverAudio(
        library: List<TrackDescriptor>,
        carryOvers: List<AudioCarryOver>,
    ): AudioCarryOverResult = AudioCarryOverResult(emptySet(), emptySet())
```

In `DefaultSimilarityEngine`:

```kotlin
    override suspend fun carryOverAudio(
        library: List<TrackDescriptor>,
        carryOvers: List<AudioCarryOver>,
    ): AudioCarryOverResult = withContext(dispatcher) {
        mutex.withLock {
            val none = AudioCarryOverResult(emptySet(), emptySet())
            if (carryOvers.isEmpty() || mutableState.value !is EngineState.Ready) return@withLock none
            val byId = library.associateBy { it.id }
            val applied = HashSet<TrackId>()
            val settled = HashSet<TrackId>()
            for (carry in carryOvers) {
                val track = byId[carry.trackId] ?: continue
                if (track.sourceRevision == carry.oldRevision) continue
                settled += carry.trackId
                // The identity the vector was made under, had the file kept its old revision.
                val before = vectorIdentity(AUDIO_IDENTITY_VERSION, track.audioUri, track.durationMs?.toString(), carry.oldRevision)
                if (carry.trackId in index && audioVectorIdentities[carry.trackId] == before && track.sizeBytes == carry.newLength) {
                    audioVectorIdentities[carry.trackId] = track.audioVectorIdentity()
                    applied += carry.trackId
                }
            }
            if (applied.isNotEmpty()) {
                audioIndexDirty = true
                persistAudioIndex()
            }
            AudioCarryOverResult(applied, settled)
        }
    }
```

`persistAudioIndex()` saves the identities with the vectors; this is what the restart part of the first test checks. If it saves only when the vector set changed, make it honour `audioIndexDirty` for identity-only changes, and say so in its KDoc.

- [ ] **Step 4: Run the engine tests**

Run: `./gradlew :core:smart:testAndroidHostTest --offline -q --rerun`
Expected: PASS, the baseline + 3.

- [ ] **Step 5: Write the failing store tests**

`AudioCarryOverStoreTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.AudioCarryOver
import io.github.nikitasud.latentjam.smart.AudioCarryOverResult
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class AudioCarryOverStoreTest {

    private var payload: String? = null
    private fun store() = AudioCarryOverStore({ payload }, { payload = it })

    private val a = AudioCarryOver(TrackId("a|\n1"), "rev|1", 10)
    private val b = AudioCarryOver(TrackId("b"), null, 20)

    @Test
    fun carryOversSurviveARestart() = runTest {
        store().add(listOf(a, b))
        assertEquals(setOf(a, b), store().pending().toSet())
    }

    @Test
    fun aSettledCarryOverIsDroppedAndAnUnsettledOneAfterThreeSyncs() = runTest {
        val store = store()
        store.add(listOf(a, b))
        store.settle(listOf(a, b), AudioCarryOverResult(applied = setOf(a.trackId), settled = setOf(a.trackId)))
        assertEquals(listOf(b), store.pending())
        repeat(2) { store.settle(listOf(b), AudioCarryOverResult(emptySet(), emptySet())) }
        assertEquals(emptyList(), store.pending())
    }

    @Test
    fun aNewerSaveOfTheSameTrackReplacesItsCarryOver() = runTest {
        val store = store()
        store.add(listOf(a))
        val newer = a.copy(oldRevision = "rev|2", newLength = 11)
        store.add(listOf(newer))
        // The sync offered the old one; settling it must not drop the newer save's carry-over.
        store.settle(listOf(a), AudioCarryOverResult(emptySet(), setOf(a.trackId)))
        assertEquals(listOf(newer), store.pending())
    }

    @Test
    fun onlyFilesSavedWithAKnownLengthCarryOver() {
        val tracks = listOf(
            TrackDescriptor(TrackId("1"), audioUri = "k1", sourceRevision = "r1"),
            TrackDescriptor(TrackId("2"), audioUri = "k2", sourceRevision = "r2"),
            TrackDescriptor(TrackId("3"), audioUri = "k3", sourceRevision = "r3"),
        )
        val result = TagSaveResult(
            listOf(
                TagSaveEntry("k1", FileWriteStatus.SAVED, newLength = 100),
                TagSaveEntry("k2", FileWriteStatus.UNCHANGED),
                TagSaveEntry("k3", FileWriteStatus.REFUSED, problem = TagProblem.DAMAGED),
            ),
        )
        assertEquals(listOf(AudioCarryOver(TrackId("1"), "r1", 100)), audioCarryOversOf(tracks, result) { it.audioUri })
    }
}
```

- [ ] **Step 6: Run them to verify they fail**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*AudioCarryOverStoreTest*'`
Expected: compilation FAILS.

- [ ] **Step 7: Implement the store and the app wiring**

`PayloadHex.kt`: move `String.hex()` and `String.unhex()` from `GenreEnrichment`'s companion, unchanged, as `internal` top-level functions. Delete them from the companion; `GenreEnrichment` keeps compiling through the import-free top-level calls.

`AudioCarryOverStore.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.AudioCarryOver
import io.github.nikitasud.latentjam.smart.AudioCarryOverResult
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carry-overs waiting for the index sync that applies them (spec §6.5), kept across restarts: the
 * sync may come at the next launch. One per track; a newer save of the track replaces the older.
 * A carry-over whose rescan has not landed after [MAX_WAITS] syncs is dropped. The worst case is
 * one re-analysis, never a vector kept for audio that changed.
 */
internal class AudioCarryOverStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    private class Entry(val carry: AudioCarryOver, val waits: Int)

    private val mutex = Mutex()
    private var entries: LinkedHashMap<TrackId, Entry>? = null

    suspend fun add(carryOvers: List<AudioCarryOver>) {
        if (carryOvers.isEmpty()) return
        mutex.withLock {
            val map = loaded()
            carryOvers.forEach { map[it.trackId] = Entry(it, 0) }
            save(map)
        }
    }

    suspend fun pending(): List<AudioCarryOver> = mutex.withLock { loaded().values.map { it.carry } }

    /** Records what a sync did with [offered]; a carry-over replaced by a newer save meanwhile is left alone. */
    suspend fun settle(offered: List<AudioCarryOver>, result: AudioCarryOverResult) = mutex.withLock {
        val map = loaded()
        for (carry in offered) {
            val entry = map[carry.trackId]?.takeIf { it.carry == carry } ?: continue
            if (carry.trackId in result.settled || entry.waits + 1 >= MAX_WAITS) {
                map.remove(carry.trackId)
            } else {
                map[carry.trackId] = Entry(carry, entry.waits + 1)
            }
        }
        save(map)
    }

    private fun loaded(): LinkedHashMap<TrackId, Entry> {
        entries?.let { return it }
        val map = LinkedHashMap<TrackId, Entry>()
        read()?.lineSequence()?.forEach { line ->
            val parts = line.split('|')
            if (parts.size != 5 || parts[0] != FORMAT) return@forEach
            val id = parts[1].unhex() ?: return@forEach
            val revision = when {
                parts[2] == NULL -> null
                parts[2].startsWith(VALUE) -> parts[2].removePrefix(VALUE).unhex() ?: return@forEach
                else -> return@forEach
            }
            val length = parts[3].toLongOrNull() ?: return@forEach
            val waits = parts[4].toIntOrNull() ?: return@forEach
            map[TrackId(id)] = Entry(AudioCarryOver(TrackId(id), revision, length), waits)
        }
        entries = map
        return map
    }

    /** A carry-over that fails to persist is only a re-analysis later; it never fails the save. */
    private fun save(map: Map<TrackId, Entry>) {
        try {
            write(
                map.values.joinToString("\n") { entry ->
                    val revision = entry.carry.oldRevision?.let { VALUE + it.hex() } ?: NULL
                    listOf(FORMAT, entry.carry.trackId.value.hex(), revision, entry.carry.newLength, entry.waits).joinToString("|")
                },
            )
        } catch (_: Exception) {
            // Kept in memory for this run.
        }
    }

    private companion object {
        const val FORMAT = "v1"
        const val NULL = "n"
        const val VALUE = "s"
        const val MAX_WAITS = 3
    }
}

/** What a finished save lets SMART keep: every file written, with its new length, keyed by its track. */
internal fun audioCarryOversOf(
    tracks: List<TrackDescriptor>,
    result: TagSaveResult,
    keyOf: (TrackDescriptor) -> String?,
): List<AudioCarryOver> {
    val written = result.entries.filter { it.status == FileWriteStatus.SAVED && it.newLength != null }.associateBy { it.key }
    return tracks.mapNotNull { track ->
        val entry = keyOf(track)?.let(written::get) ?: return@mapNotNull null
        AudioCarryOver(track.id, track.sourceRevision, entry.newLength!!)
    }
}
```

`AppSettings`:
1. In the interface: add `fun readAudioCarryOversPayload(): String?` and `fun writeAudioCarryOversPayload(payload: String)`, with the KDoc "SMART carry-overs from tag edits (see [AudioCarryOverStore]); null when none."
2. Android: `KEY_AUDIO_CARRY_OVERS = "audio_carry_overs"`, the same `readString` / `putString(...).apply()` pair as `track_genres`.
3. iOS: `defaults.objectForKey` / `setObject`, as `KEY_TRACK_GENRES`.
4. The three test fakes: `override fun readAudioCarryOversPayload(): String? = null` and `override fun writeAudioCarryOversPayload(payload: String) = Unit`.

The local backup must not include this payload. Leave `LocalBackup` alone.

`AppGraph`:
1. Add `val audioCarryOvers = AudioCarryOverStore(settings::readAudioCarryOversPayload, settings::writeAudioCarryOversPayload)` next to the other lazily built stores.
2. In `ensureAutomaticIndexing`, immediately before `engine.synchronizeLibrary(...)`:

```kotlin
                // A verified tag edit left the audio as it was: re-key its vector before the sync
                // below would discard it for the file's new revision (spec §6.5).
                val carryOvers = audioCarryOvers.pending()
                if (carryOvers.isNotEmpty()) {
                    audioCarryOvers.settle(carryOvers, engine.carryOverAudio(tracks, carryOvers))
                }
```

- [ ] **Step 8: Run all suites**

Run: `./gradlew :core:smart:testAndroidHostTest --offline -q --rerun && ./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS. `composeApp`: 689 tests.

Run: `./gradlew :core:smart:compileTestKotlinIosSimulatorArm64 :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q && ./gradlew :androidApp:compileDebugKotlin --offline -q`
Expected: success.

- [ ] **Step 9: Commit**

```bash
git add core/smart/src composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AudioCarryOverStore.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PayloadHex.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/GenreEnrichment.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AppSettings.kt composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/AppSettings.android.kt composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/AppSettings.ios.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AppGraph.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app
git commit -m "feat(smart): editing a song's tags keeps its audio analysis instead of listening to it again"
```

---

### Task 13: Entry points, and the app following an edit everywhere

This task makes the editors reachable and the app follow a save (spec §6.2 entry points, §6.5, §5.4).

**Entry points:**
- The album screen's menu gets "Edit album".
- The artist screen's menu gets "Edit artist", with the artist field first.
- Selection mode's action bar gets "Edit tags".
- One song opens the single-track editor, which has the per-track fields. Several songs open the N-track editor. Genre and folder screens are covered by selection.

**After a save**, `afterTagSave` runs, in this order:
1. It records SMART carry-overs.
2. It reloads the library once.
3. It refreshes the queue, which holds descriptors by value (Task 11).
4. It refreshes the open page. An album or artist page follows its tracks to their new album or artist, and keeps its route identity so no page push replays. Any other page swaps in fresh descriptors.

**Reports no editor claimed** (Task 2) are shown as snackbars, and the library reloads when files changed:
- a recovery the user accepted in the prompt;
- iOS's automatic recovery;
- a batch restored after process death;
- an edit whose sheet was closed.

This covers plan-2 findings M2b and T12.

**Files under repair.** A file whose save stopped mid-replace (journal state `REPLACING`) holds neither the old bytes nor the new ones. It is left out of the library until its recovery finishes, so it is not offered for playback (spec §5.4).

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagEditRefresh.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/CollectionDetailScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagEditRefreshTest.kt`

**Interfaces:**
- Consumes:
  - Task 2: `TagWriteCoordinator.unclaimed` / `acknowledge` / `pendingRecovery`.
  - Tasks 3 and 4: `rememberTagWriteAccess`, `TagSaveResult.of`, `TagSaveResult`.
  - Task 7: `TrackInfoSheet(onSaved: (TagSaveResult) -> Unit)`.
  - Task 8: `BulkTagEditorSheet`, `BulkEditScope`.
  - Task 11: `PlaybackController.refreshTracks`.
  - Task 12: `AppGraph.audioCarryOvers`, `audioCarryOversOf`.
  - `JournalRecord.state`, `JournalState.REPLACING`.
- Produces:
  - `internal fun refreshedAlbum(catalog: LibraryCatalog, pageIds: Set<TrackId>): AlbumGroup?`
  - `internal fun refreshedArtist(catalog: LibraryCatalog, pageIds: Set<TrackId>): ArtistGroup?`
  - `internal fun CollectionSelection.withFreshTracks(byId: Map<TrackId, TrackDescriptor>): CollectionSelection`
  - `internal fun withoutFilesUnderRepair(tracks: List<TrackDescriptor>, repairing: Set<String>, keyOf: (TrackDescriptor) -> String?): List<TrackDescriptor>`
  - `internal data class TagReportNotice(val kind: Kind, val count: Int, val total: Int = count) { enum class Kind { SAVED, SAVED_PARTLY, RECOVERED, UNDONE, LEFT_AS_FOUND, WAITING } }`
  - `internal fun tagReportNotices(kind: TagWriteKind, result: TagSaveResult): List<TagReportNotice>`
  - `internal suspend fun tagReportNoticeText(notice: TagReportNotice): String`
  - `CollectionDetailScreen(..., onEditTags: (() -> Unit)? = null, ...)`

- [ ] **Step 1: Write the failing tests**

`TagEditRefreshTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TagEditRefreshTest {

    private fun track(id: String, album: String, artist: String = "Band") =
        TrackDescriptor(TrackId(id), title = "t$id", artist = artist, album = album, audioUri = "k$id")

    private fun ids(vararg values: String) = values.mapTo(HashSet()) { TrackId(it) }

    @Test
    fun anOpenAlbumFollowsItsTracksToTheirNewAlbum() {
        val catalog = LibraryCatalog.build(listOf(track("1", "Renamed"), track("2", "Renamed"), track("3", "Other")))
        assertEquals("Renamed", refreshedAlbum(catalog, ids("1", "2"))?.title)
    }

    @Test
    fun anOpenAlbumStaysWhenOnlySomeOfItsTracksMoved() {
        val catalog = LibraryCatalog.build(
            listOf(track("1", "Elsewhere"), track("2", "Old"), track("3", "Old"), track("4", "Old")),
        )
        assertEquals("Old", refreshedAlbum(catalog, ids("1", "2", "3", "4"))?.title)
    }

    @Test
    fun anOpenArtistPageFollowsTheRenamedArtist() {
        val catalog = LibraryCatalog.build(
            listOf(track("1", "A", artist = "New Name"), track("2", "B", artist = "New Name"), track("3", "C", artist = "Someone")),
        )
        assertEquals("New Name", refreshedArtist(catalog, ids("1", "2"))?.name)
    }

    @Test
    fun freshTracksKeepOrderAndSections() {
        val old = listOf(track("2", "A"), track("1", "A"))
        val selection = CollectionSelection(
            title = "Mix",
            subtitle = null,
            artworkUri = null,
            tracks = old,
            sections = listOf(CollectionSection("A", old)),
        )
        val fresh = mapOf(TrackId("1") to track("1", "A").copy(title = "new"))
        val refreshed = selection.withFreshTracks(fresh)
        assertEquals(listOf("t2", "new"), refreshed.tracks.map { it.title })
        assertEquals(listOf("t2", "new"), refreshed.sections!!.single().tracks.map { it.title })
        assertEquals(selection.routeId, refreshed.routeId)
    }

    @Test
    fun filesUnderRepairAreLeftOut() {
        val tracks = listOf(track("1", "A"), track("2", "A"))
        assertEquals(listOf("1"), withoutFilesUnderRepair(tracks, setOf("k2")) { it.audioUri }.map { it.id.value })
        assertTrue(withoutFilesUnderRepair(tracks, emptySet()) { it.audioUri } === tracks)
    }

    @Test
    fun aRecoveryReportSaysWhatWasFinishedUndoneAndStillWaits() {
        val result = TagSaveResult(
            listOf(
                TagSaveEntry("a", FileWriteStatus.RECOVERED),
                TagSaveEntry("b", FileWriteStatus.RESTORED, problem = TagProblem.UNDONE),
                TagSaveEntry("c", FileWriteStatus.MISSING, problem = TagProblem.MISSING),
                TagSaveEntry("d", FileWriteStatus.FOREIGN, problem = TagProblem.CHANGED_ELSEWHERE),
            ),
        )
        assertEquals(
            listOf(
                TagReportNotice(TagReportNotice.Kind.RECOVERED, 1),
                TagReportNotice(TagReportNotice.Kind.UNDONE, 1),
                TagReportNotice(TagReportNotice.Kind.LEFT_AS_FOUND, 1),
                TagReportNotice(TagReportNotice.Kind.WAITING, 1),
            ),
            tagReportNotices(TagWriteKind.RECOVER, result),
        )
    }

    @Test
    fun anEditReportSaysHowManyFilesWereSaved() {
        val all = TagSaveResult(listOf(TagSaveEntry("a", FileWriteStatus.SAVED), TagSaveEntry("b", FileWriteStatus.UNCHANGED)))
        assertEquals(listOf(TagReportNotice(TagReportNotice.Kind.SAVED, 2)), tagReportNotices(TagWriteKind.EDIT, all))
        val partly = all.copy(entries = all.entries + TagSaveEntry("c", FileWriteStatus.FAILED, problem = TagProblem.FAILED))
        assertEquals(listOf(TagReportNotice(TagReportNotice.Kind.SAVED_PARTLY, 2, 3)), tagReportNotices(TagWriteKind.EDIT, partly))
        val cancelled = TagSaveResult(listOf(TagSaveEntry("a", FileWriteStatus.CANCELLED, problem = TagProblem.CANCELLED)))
        assertEquals(emptyList(), tagReportNotices(TagWriteKind.EDIT, cancelled))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagEditRefreshTest*'`
Expected: compilation FAILS.

- [ ] **Step 3: Add the English strings**

After the Task 8 strings:

```xml
    <plurals name="tags_saved">
        <item quantity="one">Tags saved in %1$d file</item>
        <item quantity="other">Tags saved in %1$d files</item>
    </plurals>
    <string name="tags_saved_partly">Tags saved in %1$d of %2$d files</string>
    <plurals name="tag_recovery_done">
        <item quantity="one">Finished saving %1$d file</item>
        <item quantity="other">Finished saving %1$d files</item>
    </plurals>
    <plurals name="tag_recovery_undone">
        <item quantity="one">%1$d interrupted save was undone; the file is as it was</item>
        <item quantity="other">%1$d interrupted saves were undone; the files are as they were</item>
    </plurals>
    <plurals name="tag_recovery_left">
        <item quantity="one">%1$d file was changed by another app and was left as it is</item>
        <item quantity="other">%1$d files were changed by another app and were left as they are</item>
    </plurals>
    <plurals name="tag_recovery_waiting">
        <item quantity="one">%1$d file still waits to be finished. See Settings → Library.</item>
        <item quantity="other">%1$d files still wait to be finished. See Settings → Library.</item>
    </plurals>
```

- [ ] **Step 4: Implement `TagEditRefresh.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_done
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_left
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_undone
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_waiting
import io.github.nikitasud.latentjam.app.generated.resources.tags_saved
import io.github.nikitasud.latentjam.app.generated.resources.tags_saved_partly
import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Where an open album page lives after an edit: the album now holding most of the page's tracks.
 * A renamed album is followed. An album that lost only a few of its tracks to another stays.
 */
internal fun refreshedAlbum(catalog: LibraryCatalog, pageIds: Set<TrackId>): AlbumGroup? =
    catalog.albums.maxByOrNull { album -> album.tracks.count { it.id in pageIds } }
        ?.takeIf { album -> album.tracks.any { it.id in pageIds } }

/** As [refreshedAlbum], for an artist page. */
internal fun refreshedArtist(catalog: LibraryCatalog, pageIds: Set<TrackId>): ArtistGroup? =
    catalog.artists.maxByOrNull { artist -> artist.tracks.count { it.id in pageIds } }
        ?.takeIf { artist -> artist.tracks.any { it.id in pageIds } }

/** The same page with each track's fresh descriptor, order, sections and identity kept. */
internal fun CollectionSelection.withFreshTracks(byId: Map<TrackId, TrackDescriptor>): CollectionSelection = copy(
    tracks = tracks.map { byId[it.id] ?: it },
    sections = sections?.map { section -> section.copy(tracks = section.tracks.map { byId[it.id] ?: it }) },
)

/**
 * The library without files whose save stopped mid-replace (spec §5.4). Such a file holds neither
 * its old bytes nor its new ones, so it is not offered for playback until recovery finishes it.
 */
internal fun withoutFilesUnderRepair(
    tracks: List<TrackDescriptor>,
    repairing: Set<String>,
    keyOf: (TrackDescriptor) -> String?,
): List<TrackDescriptor> =
    if (repairing.isEmpty()) tracks else tracks.filterNot { track -> keyOf(track)?.let(repairing::contains) == true }

/** One line to tell the user about a save no editor was there to report. */
internal data class TagReportNotice(val kind: Kind, val count: Int, val total: Int = count) {
    enum class Kind { SAVED, SAVED_PARTLY, RECOVERED, UNDONE, LEFT_AS_FOUND, WAITING }
}

internal fun tagReportNotices(kind: TagWriteKind, result: TagSaveResult): List<TagReportNotice> {
    if (result.entries.isEmpty() || result.cancelled) return emptyList()
    if (kind == TagWriteKind.EDIT) {
        return listOf(
            if (result.notChanged.isEmpty()) {
                TagReportNotice(TagReportNotice.Kind.SAVED, result.savedCount)
            } else {
                TagReportNotice(TagReportNotice.Kind.SAVED_PARTLY, result.savedCount, result.entries.size)
            },
        )
    }
    val recovered = result.entries.count { it.status == FileWriteStatus.RECOVERED }
    val undone = result.entries.count { it.status == FileWriteStatus.RESTORED }
    val left = result.entries.count { it.status == FileWriteStatus.FOREIGN }
    val waiting = result.entries.size - recovered - undone - left
    return listOfNotNull(
        TagReportNotice(TagReportNotice.Kind.RECOVERED, recovered).takeIf { recovered > 0 },
        TagReportNotice(TagReportNotice.Kind.UNDONE, undone).takeIf { undone > 0 },
        TagReportNotice(TagReportNotice.Kind.LEFT_AS_FOUND, left).takeIf { left > 0 },
        TagReportNotice(TagReportNotice.Kind.WAITING, waiting).takeIf { waiting > 0 },
    )
}

internal suspend fun tagReportNoticeText(notice: TagReportNotice): String = when (notice.kind) {
    TagReportNotice.Kind.SAVED -> getPluralString(Res.plurals.tags_saved, notice.count, notice.count)
    TagReportNotice.Kind.SAVED_PARTLY -> getString(Res.string.tags_saved_partly, notice.count, notice.total)
    TagReportNotice.Kind.RECOVERED -> getPluralString(Res.plurals.tag_recovery_done, notice.count, notice.count)
    TagReportNotice.Kind.UNDONE -> getPluralString(Res.plurals.tag_recovery_undone, notice.count, notice.count)
    TagReportNotice.Kind.LEFT_AS_FOUND -> getPluralString(Res.plurals.tag_recovery_left, notice.count, notice.count)
    TagReportNotice.Kind.WAITING -> getPluralString(Res.plurals.tag_recovery_waiting, notice.count, notice.count)
}

/** Statuses after which the file's bytes differ from what the library last read. */
internal val TAG_FILE_CHANGED = setOf(FileWriteStatus.SAVED, FileWriteStatus.RECOVERED, FileWriteStatus.RESTORED)
```

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*TagEditRefreshTest*'`
Expected: PASS (7 tests).

- [ ] **Step 5: The album and artist menu item**

In `CollectionDetailScreen`:
1. Add the parameter `onEditTags: (() -> Unit)? = null` after `onToggleSmart`.
2. In the overflow menu, add the item below after the `onToggleSmart` item.
3. Change the divider condition to `if (onChangeCover != null || onEditTags != null)`.

```kotlin
                                onEditTags?.let { editTags ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    when {
                                                        selection.routeId.startsWith("album:") -> Res.string.action_edit_album
                                                        selection.routeId.startsWith("artist:") -> Res.string.action_edit_artist
                                                        else -> Res.string.info_edit
                                                    },
                                                ),
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(20.dp)) },
                                        enabled = selection.tracks.isNotEmpty(),
                                        onClick = { optionsOpen = false; editTags() },
                                    )
                                }
```

- [ ] **Step 6: Wire `App.kt`**

These pieces go in `App.kt`, each at the place named.

**(a) Before `publishLibraryTracks`, the repair filter:**

```kotlin
        val tagAccess = rememberTagWriteAccess()
        val interruptedSaves = tagAccess?.coordinator?.pendingRecovery?.collectAsState()?.value.orEmpty()
        val repairingKeys = remember(interruptedSaves) {
            interruptedSaves.filter { it.state == JournalState.REPLACING }.mapTo(HashSet()) { it.target }
        }
        val currentRepairingKeys = rememberUpdatedState(repairingKeys)
```

In `scanLibrary`, publish and return the filtered list:

```kotlin
            val enriched = genreEnrichment.apply(scan.tracks)
            // A file mid-replace is neither its old bytes nor its new ones: not offered until recovered.
            val playable = withoutFilesUnderRepair(enriched, currentRepairingKeys.value) { tagAccess?.keyOf(it) }
            publishLibraryTracks(
                value = playable,
                authoritative = authoritativeLibrarySnapshot(
                    scanCompleted = scan.complete,
                    permissionStatus = AppGraph.permissions.audioLibraryStatus.value,
                ),
            )
            return playable
```

**(b) After `scanLibrary`, rescan when the set of files under repair changes.** The first composition starts equal, so nothing extra runs at launch:

```kotlin
        var shownRepairingKeys by remember { mutableStateOf(emptySet<String>()) }
        LaunchedEffect(repairingKeys) {
            if (repairingKeys != shownRepairingKeys && tracks != null) {
                shownRepairingKeys = repairingKeys
                scanLibrary()
            }
        }
```

**(c) Extract the catalog build.** Move the `LibraryCatalog.build(…, isKnownArtist = …)` expression out of `LaunchedEffect(tracks)` into a local function. `LaunchedEffect(tracks)` then calls `buildCatalog(loaded)` inside its `withContext(Dispatchers.Default)`, before `AlbumBrowseDerivation.of(...)`:

```kotlin
        suspend fun buildCatalog(from: List<TrackDescriptor>): LibraryCatalog = withContext(Dispatchers.Default) {
            // Collaborations split only where the MusicBrainz list can tell a band named
            // "Earth, Wind & Fire" from two artists; without it, only at semicolons.
            val entities = AppGraph.musicEntities
            LibraryCatalog.build(
                from,
                isKnownArtist = if (entities.isAvailable) { name -> entities.resolve(name).isNotEmpty() } else null,
            )
        }
```

**(d) After `openCollection`, the editor state and the after-save refresh.** `infoTargetId` is declared above them:

```kotlin
        var bulkEditIds by rememberSaveable { mutableStateOf<List<String>?>(null) }
        var bulkEditScope by rememberSaveable { mutableStateOf(BulkEditScope.SELECTION) }

        /** One song gets the full editor, with the per-track fields the N-track editor leaves out. */
        fun openTagEditor(targets: List<TrackDescriptor>, editScope: BulkEditScope) {
            when (targets.size) {
                0 -> Unit
                1 -> infoTargetId = targets.single().id.value
                else -> {
                    bulkEditScope = editScope
                    bulkEditIds = targets.map { it.id.value }
                }
            }
        }

        /**
         * An open page after an edit. An album or artist page follows its tracks to where they now
         * group. Any other page swaps in fresh descriptors. The route identity is kept, so this is
         * the same page updated, never a new one pushed.
         */
        suspend fun refreshOpenCollection(fresh: List<TrackDescriptor>) {
            val open = selectedCollection ?: return
            val pageIds = open.tracks.mapTo(HashSet()) { it.id }
            val rebuilt = when {
                open.routeId.startsWith("album:") -> refreshedAlbum(buildCatalog(fresh), pageIds)?.toSelection()
                open.routeId.startsWith("artist:") ->
                    refreshedArtist(buildCatalog(fresh), pageIds)?.toSelection(artistAlbumSortChoice)
                else -> open.withFreshTracks(fresh.associateBy { it.id })
            }
            if (selectedCollection === open && rebuilt != null) updateSelectedCollection(rebuilt.copy(routeId = open.routeId))
        }

        /**
         * Everything a finished save changes outside the file (spec §6.5). First SMART's carry-overs,
         * before the reload that would otherwise start a sync without them. Then one library reload.
         * Then the queue and the open page, which hold descriptors by value.
         */
        fun afterTagSave(saved: List<TrackDescriptor>, result: TagSaveResult) {
            scope.launch {
                tagAccess?.let { access -> AppGraph.audioCarryOvers.add(audioCarryOversOf(saved, result, access::keyOf)) }
                val fresh = scanLibrary()
                val freshById = fresh.associateBy { it.id }
                playback.refreshTracks(saved.mapNotNull { freshById[it.id] })
                refreshOpenCollection(fresh)
            }
        }
```

**(e) Reports nobody claimed.** Place this after `afterTagSave`. The reports are acknowledged at once, because acknowledging restarts this effect, and are shown from the App scope so a restart cannot cut a snackbar short:

```kotlin
        val unclaimedTagReports = tagAccess?.coordinator?.unclaimed?.collectAsState()?.value.orEmpty()
        val tagEditorOpen = infoTargetId != null || bulkEditIds != null
        LaunchedEffect(unclaimedTagReports, tagEditorOpen) {
            val access = tagAccess ?: return@LaunchedEffect
            // An open editor claims its own report; the others wait until it closes.
            if (tagEditorOpen || unclaimedTagReports.isEmpty()) return@LaunchedEffect
            val reports = unclaimedTagReports
            reports.forEach(access.coordinator::acknowledge)
            scope.launch {
                val results = reports.map { it to TagSaveResult.of(it, access.readOnlyIsMusicLibrary) }
                if (results.any { (_, result) -> result.entries.any { it.status in TAG_FILE_CHANGED } }) scanLibrary()
                for ((report, result) in results) {
                    for (notice in tagReportNotices(report.kind, result)) snackbar.showSnackbar(tagReportNoticeText(notice))
                }
            }
        }
```

**(f) The sheets.** The `TrackInfoSheet` call becomes `onSaved = { result -> afterTagSave(listOf(target), result) }`. After it:

```kotlin
        // Resolved once the catalog has hydrated; kept while the sheet is open, since a reload after
        // a partial save must not restart the sheet's file reading.
        val bulkTargets = remember(bulkEditIds, tracksById.isNotEmpty()) {
            bulkEditIds?.mapNotNull { tracksById[TrackId(it)] }?.takeIf { it.isNotEmpty() }
        }
        bulkTargets?.let { targets ->
            BulkTagEditorSheet(
                tracks = targets,
                scope = bulkEditScope,
                onSaved = { saved, result ->
                    afterTagSave(saved, result)
                    if (result.notChanged.isEmpty()) {
                        scope.launch { snackbar.showSnackbar(getPluralString(Res.plurals.tags_saved, result.savedCount, result.savedCount)) }
                    }
                },
                onDismiss = { bulkEditIds = null },
            )
        }
```

**(g) The collection menu.** At the `CollectionDetailScreen(...)` call, add:

```kotlin
                            onEditTags = when {
                                selection.routeId.startsWith("album:") -> {
                                    { openTagEditor(selection.tracks, BulkEditScope.ALBUM) }
                                }
                                selection.routeId.startsWith("artist:") -> {
                                    { openTagEditor(selection.tracks, BulkEditScope.ARTIST) }
                                }
                                else -> null
                            },
```

**(h) The selection bar.** `SelectionActionBar` gains `onEditTags: () -> Unit`, and a fifth action between Share and Delete:

```kotlin
        SelectionAction(
            icon = Icons.Rounded.Edit,
            label = stringResource(Res.string.info_edit),
            enabled = canAct,
            onClick = onEditTags,
            modifier = Modifier.weight(1f),
        )
```

Its call passes:

```kotlin
                            onEditTags = {
                                val selection = selectedTracks
                                updateTrackSelection(emptySet())
                                openTagEditor(selection, BulkEditScope.SELECTION)
                            },
```

Add the imports the pieces need: `JournalState`, `LibraryCatalog`, `Icons.Rounded.Edit`, `getPluralString`, `tags_saved`, and so on. `TrackInfoSheet` already dismisses itself after a save; nothing else changes there.

- [ ] **Step 7: Run the tests and both compiles**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 696 tests.

Run: `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q && ./gradlew :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: success.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagEditRefresh.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/CollectionDetailScreen.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagEditRefreshTest.kt
git commit -m "feat(tags): albums, artists and selections open the tag editor, and the whole app follows a save at once"
```

---

### Task 14: Settings → Library lists interrupted saves, with Finish and Forget

Spec §5.4: "A persistent notice in Settings → Library says which files are waiting." Plan 2's ruling kept `TagRecovery.abandon` for an explicit user action only. A file that looks gone may only be on an unmounted volume, and abandoning deletes its only way back. So the prompt offered at launch can return indefinitely for a file that is truly gone, until the user can say "forget".

This task adds a section under Library, shown only while saves are waiting:
- which files wait;
- which of them are being repaired, and so are left out of playback (Task 13);
- "Finish now", which is the same recovery request as the launch prompt;
- a "Forget" per file, behind a confirmation.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/InterruptedSaves.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/SettingsScreens.kt` (`SettingsScreen` and `LibrarySettings` parameters, the section, the dialog)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt` (`scannedTracks`, the rows, the `SettingsScreen` call)
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/InterruptedSavesTest.kt`

**Interfaces:**
- Consumes: `JournalRecord(target, state)`, `TagWriteCoordinator.enqueueRecovery()`, `forget(record)` (Task 2), `trackLabel` (Task 8), `interruptedSaves` / `tagAccess` in `App` (Task 13).
- Produces:
  - `internal data class InterruptedSave(val record: JournalRecord, val label: String, val underRepair: Boolean)`
  - `internal fun interruptedSavesOf(records: List<JournalRecord>, tracks: List<TrackDescriptor>, keyOf: (TrackDescriptor) -> String?): List<InterruptedSave>`
  - `SettingsScreen(..., interruptedSaves: List<InterruptedSave> = emptyList(), onFinishInterruptedSaves: () -> Unit = {}, onForgetInterruptedSave: (InterruptedSave) -> Unit = {}, ...)`

- [ ] **Step 1: Write the failing test**

`InterruptedSavesTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.write.JournalRecord
import io.github.nikitasud.latentjam.library.tags.write.JournalState
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

internal class InterruptedSavesTest {

    private fun record(id: String, target: String, state: JournalState) = JournalRecord(id, target, state, 10, 10)

    @Test
    fun eachWaitingFileIsNamedOnceAndMarkedWhenItIsBeingRepaired() {
        val tracks = listOf(
            TrackDescriptor(TrackId("1"), title = "Zebra", audioUri = "content://media/external/audio/media/1"),
            TrackDescriptor(TrackId("2"), title = "Apple", audioUri = "content://media/external/audio/media/2"),
        )
        val saves = interruptedSavesOf(
            listOf(
                record("w1", "content://media/external/audio/media/1", JournalState.PATCH_PREPARED),
                record("w2", "content://media/external/audio/media/2", JournalState.REPLACING),
                record("w3", "content://media/external/audio/media/2", JournalState.REPLACING),
                record("w4", "Music/gone.flac", JournalState.REPLACE_PREPARED),
            ),
            tracks,
        ) { it.audioUri }
        assertEquals(listOf("Apple", "gone.flac", "Zebra"), saves.map { it.label })
        assertEquals(listOf(true, false, false), saves.map { it.underRepair })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*InterruptedSavesTest*'`
Expected: compilation FAILS.

- [ ] **Step 3: Implement `InterruptedSaves.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.write.JournalRecord
import io.github.nikitasud.latentjam.library.tags.write.JournalState
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** A file whose save was interrupted, as Settings lists it. [underRepair]: mid-replace, left out of playback. */
internal data class InterruptedSave(val record: JournalRecord, val label: String, val underRepair: Boolean)

/**
 * One row per waiting file, named by its song where the library knows it. Pass the library as
 * scanned, before files under repair are left out, or those would lose their names. A file the
 * library does not know is named by the last part of its path.
 */
internal fun interruptedSavesOf(
    records: List<JournalRecord>,
    tracks: List<TrackDescriptor>,
    keyOf: (TrackDescriptor) -> String?,
): List<InterruptedSave> {
    val byKey = HashMap<String, TrackDescriptor>()
    tracks.forEach { track -> keyOf(track)?.let { byKey[it] = track } }
    return records.groupBy { it.target }.map { (target, same) ->
        InterruptedSave(
            record = same.first(),
            label = trackLabel(byKey[target], target.substringAfterLast('/')),
            underRepair = same.any { it.state == JournalState.REPLACING },
        )
    }.sortedBy { it.label.lowercase() }
}
```

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun --tests '*InterruptedSavesTest*'`
Expected: PASS.

- [ ] **Step 4: Add the English strings**

```xml
    <string name="settings_interrupted_saves">Interrupted saves</string>
    <plurals name="settings_interrupted_saves_body">
        <item quantity="one">Saving tags into %1$d file was interrupted. LatentJam finishes or undoes it once it may write the file.</item>
        <item quantity="other">Saving tags into %1$d files was interrupted. LatentJam finishes or undoes each once it may write the file.</item>
    </plurals>
    <string name="settings_interrupted_finish">Finish now</string>
    <string name="settings_interrupted_being_repaired">Being repaired: not played until it is finished</string>
    <string name="settings_interrupted_forget">Forget</string>
    <string name="settings_interrupted_forget_title">Forget this save?</string>
    <string name="settings_interrupted_forget_body">The file stays exactly as it is now, and LatentJam stops trying to finish or undo the save. This can't be undone.</string>
```

- [ ] **Step 5: The Settings section**

`SettingsScreen` gains three parameters, passed straight to `LibrarySettings`:
- `interruptedSaves: List<InterruptedSave> = emptyList()`
- `onFinishInterruptedSaves: () -> Unit = {}`
- `onForgetInterruptedSave: (InterruptedSave) -> Unit = {}`

In `LibrarySettings`, add the parameters too, and this `item` right after the Status section:

```kotlin
        if (interruptedSaves.isNotEmpty()) {
            item {
                SettingsSection(stringResource(Res.string.settings_interrupted_saves)) {
                    SettingsBody(pluralStringResource(Res.plurals.settings_interrupted_saves_body, interruptedSaves.size, interruptedSaves.size))
                    SettingsActionRow(
                        title = stringResource(Res.string.settings_interrupted_finish),
                        subtitle = null,
                        onClick = onFinishInterruptedSaves,
                    )
                    interruptedSaves.forEach { save ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(save.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (save.underRepair) {
                                    Text(
                                        stringResource(Res.string.settings_interrupted_being_repaired),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = { forgetTarget = save }) {
                                Text(stringResource(Res.string.settings_interrupted_forget))
                            }
                        }
                    }
                }
            }
        }
```

It needs `var forgetTarget by remember { mutableStateOf<InterruptedSave?>(null) }` at the top of `LibrarySettings`, and after the `FadingLazyColumn`:

```kotlin
    forgetTarget?.let { save ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text(stringResource(Res.string.settings_interrupted_forget_title)) },
            text = { Text(stringResource(Res.string.settings_interrupted_forget_body)) },
            confirmButton = {
                TextButton(onClick = { forgetTarget = null; onForgetInterruptedSave(save) }) {
                    Text(stringResource(Res.string.settings_interrupted_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text(stringResource(Res.string.info_cancel)) }
            },
        )
    }
```

- [ ] **Step 6: Feed it from `App`**

1. In `scanLibrary`, record the list before the repair filter: `scannedTracks = enriched`, with `var scannedTracks by remember { mutableStateOf<List<TrackDescriptor>>(emptyList()) }` declared beside `tracks`.
2. Beside `repairingKeys`:

```kotlin
        val interruptedSaveRows = remember(interruptedSaves, scannedTracks, tagAccess) {
            tagAccess?.let { access -> interruptedSavesOf(interruptedSaves, scannedTracks, access::keyOf) }.orEmpty()
        }
```

3. At the `SettingsScreen(...)` call:

```kotlin
                interruptedSaves = interruptedSaveRows,
                onFinishInterruptedSaves = { tagAccess?.coordinator?.enqueueRecovery() },
                onForgetInterruptedSave = { save -> scope.launch { tagAccess?.coordinator?.forget(save.record) } },
```

`forget` refreshes `pendingRecovery` itself, and Task 13's effect rescans when the set of files under repair changes, so a forgotten file under repair comes back to the library.

- [ ] **Step 7: Run the tests and both compiles**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun`
Expected: PASS, 697 tests.

Run: `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q && ./gradlew :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: success.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/InterruptedSaves.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/SettingsScreens.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/InterruptedSavesTest.kt
git commit -m "feat(tags): Settings lists saves that were interrupted, finishes them on request, and can forget one for good"
```

---

### Task 15: Every new string in all seventeen languages

Spec §6.6. Tasks 4, 7, 8, 13 and 14 added English text only. Every key below gets a real, natural translation in each of the 17 locale files: ar, de, es, fr, hi, id, in, it, ja, ko, pl, pt-rBR, ro, ru, tr, uk, zh-rCN. The now-unused `info_edit_unavailable` is deleted from all 18 files.

**Keys (61):**
- **Task 4:** `tag_problem_unsupported_format`, `tag_problem_damaged`, `tag_problem_protected`, `tag_problem_tags_too_large`, `tag_problem_bad_number`, `tag_problem_number_unreadable`, `tag_problem_bad_image`, `tag_problem_read_only_storage`, `tag_problem_music_library`, `tag_problem_unreadable`, `tag_problem_not_allowed`, `tag_problem_missing`, `tag_problem_stopped`, `tag_problem_cancelled`, `tag_problem_undone`, `tag_problem_changed_elsewhere`
- **Task 7:** `info_album_artist`, `info_track_number`, `info_track_total`, `info_disc_number`, `info_disc_total`, `info_cover`, `info_cover_replace`, `info_cover_remove`, `info_cover_keep`, `info_cover_new`, `info_cover_removed`, plurals `info_cover_more`, `info_reading_file`
- **Task 8:** `action_edit_album`, `action_edit_artist`, plurals `count_files`, plurals `count_fields`, `bulk_save`, `bulk_reading`, plurals `bulk_not_editable`, plurals `bulk_more_files`, `bulk_nothing_editable`, `bulk_different`, plurals `bulk_removed`, `bulk_remove_field`, `bulk_keep_field`, `bulk_saving`, `bulk_stop`, `bulk_result`, plurals `bulk_not_changed`, `bulk_done`, `bulk_file_reason`
- **Task 13:** plurals `tags_saved`, `tags_saved_partly`, plurals `tag_recovery_done`, plurals `tag_recovery_undone`, plurals `tag_recovery_left`, plurals `tag_recovery_waiting`
- **Task 14:** `settings_interrupted_saves`, plurals `settings_interrupted_saves_body`, `settings_interrupted_finish`, `settings_interrupted_being_repaired`, `settings_interrupted_forget`, `settings_interrupted_forget_title`, `settings_interrupted_forget_body`

**Rules:**
- Placeholders stay exactly as in English (`%1$d`, `%1$s`, `%2$s`), and none is dropped or added.
- A literal percent is `%`, never `%%`.
- Plurals use exactly the quantities `count_tracks` uses in the same file:
  - ru/uk/pl: `one` `few` `many` `other`;
  - ar: `zero` `one` `two` `few` `many` `other`;
  - ja/ko/zh-rCN/id/in: `other` only;
  - the others as their `count_tracks` shows.
- `values-in/strings.xml` is byte-identical to `values-id/strings.xml` for every key (`diff` must print nothing for the new lines).
- `bulk_save` is built from two plural fragments (`count_fields`, `count_files`). The fragments carry the grammar; the frame must read naturally with them in each language. Reorder the placeholders if a language needs it.
- Keep terms consistent with the file's existing words for "track", "tags", "album", "artist", "cover", "Settings" and "Library". Search the file before choosing a word.
- The Settings path in `tag_recovery_waiting` uses the file's own translated names of the Settings and Library screens.
- Apostrophes are plain `'`, as elsewhere in these files.

**Files:**
- Modify: all 18 `composeApp/src/commonMain/composeResources/values*/strings.xml`

- [ ] **Step 1: Add the translations**

For each locale, insert each new key right after the neighbour it follows in `values/strings.xml`, so the files stay in the same order. Translate from the English text in `values/strings.xml`.

- [ ] **Step 2: Delete `info_edit_unavailable`**

Remove its line from all 18 files. Run `grep -rn "info_edit_unavailable" composeApp/src`; it must print nothing.

- [ ] **Step 3: Check completeness and the traps**

Run:

```bash
cd composeApp/src/commonMain/composeResources
for key in tag_problem_unsupported_format info_album_artist bulk_save tags_saved settings_interrupted_forget_body count_files; do
  for dir in values*; do grep -q "name=\"$key\"" $dir/strings.xml || echo "MISSING $key in $dir"; done
done
diff <(grep -E 'name="(tag_problem|info_album_artist|info_track|info_disc|info_cover|info_reading|action_edit|count_files|count_fields|bulk_|tags_saved|tag_recovery_(done|undone|left|waiting)|settings_interrupted)' values-id/strings.xml) \
     <(grep -E 'name="(tag_problem|info_album_artist|info_track|info_disc|info_cover|info_reading|action_edit|count_files|count_fields|bulk_|tags_saved|tag_recovery_(done|undone|left|waiting)|settings_interrupted)' values-in/strings.xml)
grep -n '%%' values*/strings.xml
```

Expected: no `MISSING` line, an empty `diff`, and no `%%`. Extend the key loop to all 61 keys if any locale was edited by hand in pieces.

- [ ] **Step 4: Build and test**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun && ./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q && ./gradlew :composeApp:compileTestKotlinIosSimulatorArm64 --offline -q`
Expected: PASS, 697 tests, and both compiles succeed. The resource generator fails on malformed XML or a plural with an unknown quantity.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/composeResources
git commit -m "feat(i18n): the tag editor speaks all seventeen of the app's languages"
```

---

### Task 16: Verification

No code unless something fails. Results go to `~/Documents/LJ/audits/tag-editor-plan3-2026-09-30/verification.md`, outside the repository.

- [ ] **Step 1: Full suites**

Run every test command in Global Constraints. Record each module's count against its baseline:
- `core:library`: 615;
- `composeApp`: 697;
- `core:smart`: baseline + 3;
- `core:playback`: baseline + 2.

All must pass.

- [ ] **Step 2: iOS on the simulator**

1. Build the app for an iPhone simulator (`xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17' build`, or this repository's usual command). Launch it.
2. Import two MP3s and one FLAC through Files (copies from `~/Documents/LJ/tag-corpus/files`, never the originals).
3. Walk through and record:
   - (a) Edit one song's title, album artist, track 3 of 12, lyrics and a new cover. Reopen the editor: every field reads back, and the list shows the new title and cover.
   - (b) Select all three and use Edit tags. Set the genre, and remove the year. The result is "Tags saved in 3 files", and the files show the new genre and no year.
   - (c) Edit the song that is playing: the lock screen's title and artwork change, and playback continues.

- [ ] **Step 3: Android on the emulator**

1. On the Android 16 emulator used for plan 2's smoke test, push copies of 12 corpus files (MP3, FLAC, Opus, M4A) into `Music/tag-editor-smoke/`. Never use the real library, and never use `LJ-demo`.
2. Walk through and record:
   - (a) The single-track editor on each format: all fields, a cover replace and a cover remove.
   - (b) Album → menu → Edit album on a 12-file album, with one consent dialog. "Saved 5 of 12" advances.
   - (c) Stop mid-batch: the result names the stopped files, and they are byte-identical to their copies (`adb shell md5sum`).
   - (d) An artist rename from Edit artist: the open page follows the new name.
   - (e) Edit the playing track: the notification's title and cover change, with no audible gap.
   - (f) Kill the app during a large-cover batch (`adb shell am kill`), relaunch, and use Settings → Library → Interrupted saves → Finish now. Then do the same again and use Forget.
   - (g) SMART: note `missingFromIndex` or the indexing notification after an edit. No audio re-analysis starts for edited files.
3. Record timings from logcat for §8.4, where visible.

- [ ] **Step 4: Report**

Write `verification.md` with every step's result. Anything that failed becomes a fix commit in this plan's style, followed by a re-run of Step 1. List the device checks that stay with plan 4:
- API 24/28/29/30 consent and album artist;
- the full-volume check;
- the real phone;
- the RECOVER with one file deleted on Android 11+.
