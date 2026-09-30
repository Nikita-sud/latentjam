# Durable Tag Writing Implementation Plan (plan 2 of 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every tag edit the app makes goes through a journaled, verified, crash-safe write path that works for all four formats on Android 7–16 and on iOS imported files, and the existing track editor uses it.

**Architecture:**
- The protocol is pure Kotlin in `core/library` (package `io.github.nikitasud.latentjam.library.tags.write`). It works over two small interfaces: `TargetFile` (a file that can be read, written, resized and forced to storage) and `RecoveryDirectory` (app-private store).
- It is tested against an in-memory file system that simulates power loss at every operation and tears unforced writes at every byte.
- Platforms supply the file adapters and the consent flow:
  - Android: MediaStore write request on 11+, per-file recoverable consent on 10, storage permission on 7–9.
  - iOS: no consent needed; an atomic rename replaces the file.
- An app-level coordinator (a sibling of `TrackDeleteCoordinator`) batches consent, writes files three at a time, rescans once, and survives process death.

**Tech Stack:** Kotlin Multiplatform 2.3.10, kotlinx.coroutines (already a `core/library` dependency), Compose Multiplatform. Android: MediaStore, `ParcelFileDescriptor`, `FileChannel`, `android.system.Os`. iOS: POSIX via Kotlin/Native `platform.posix` and Foundation.

**Spec:** `docs/superpowers/specs/2026-09-28-tag-editing-design.md`. This plan implements:
- **§5 (all of it):** journal, recovery store, in-place patch, streaming rewrite, recovery, platforms.
- **§7:** performance targets.
- **§8.1:** fault injection, out-of-space and batch splitting.

It leaves these to plan 3:
- §6 (the new editor UI, N-track editor, cover picker, after-save caches);
- two §5.4 UI pieces: the persistent notice in Settings → Library listing files that wait for recovery, and marking a file in `REPLACING` as "being repaired" in the library (not offered for playback or editing). This plan already refuses to edit such a file until it is recovered;
- §3.5 (album artist in the library);
- the SMART carry-over, although this plan's `WriteResult.Saved.newLength` is the data it needs.

Plan 4 is devices (§8.3) and the 0.7.0 release.

**Base:**
- Worktree `/Users/nichitabulgaru/Documents/LJ/latentjam-tagwrite`, branch `feat/tag-writing`.
- It was created from `fix/issues-5-6-7` at 97d7a56, which is itself stacked on `feat/tag-editing` (plan 1, c577cef).
- That base already holds the plan-1 codecs, `TagVerification`, the Android `FileChannelSource` and the iOS `FileHandleSource` read adapters.

## Global Constraints

- Work ONLY in `/Users/nichitabulgaru/Documents/LJ/latentjam-tagwrite` (branch `feat/tag-writing`). Never touch `/Users/nichitabulgaru/Documents/LJ/latentjam` (another session's uncommitted work lives there), `latentjam-tags` or `latentjam-issues`.
- Every new source file starts with the header `/*\n * Copyright (c) 2026 LatentJam Project\n * SPDX-License-Identifier: Apache-2.0\n */`.
- `core/library` commonMain: Kotlin stdlib + kotlinx.coroutines only; no `java.*`, no third-party libraries. Public API needs explicit visibility (`explicitApi()`).
- Test names are plain camelCase. Kotlin/Native rejects `, ? ( ) $ % @` and similar characters in backticked names.
- **Never damage a file.** At every instant a track file is either byte-identical to its original or byte-identical to the verified edit. Anything else must be recoverable to one of the two, and the recovery path is itself crash-safe (spec §5).
- The recovery store lives in app-private **files** storage, never a cache directory:
  - Android: `File(context.noBackupFilesDir, "tag-write")`;
  - iOS: `IosPaths.appSupport() + "/tag-write"`, which is already excluded from backup.
- Spare-space margins:
  - store margin for an in-place patch: 64 KiB beyond the saved bytes;
  - streaming rewrite: `2 × file size + 16 MiB`, or `file size + 16 MiB` with an atomic replace;
  - target volume: the growth plus 64 KiB.
- The write consent limit is **2,000 URIs per `createWriteRequest`** (spec §5.5).
- A batch writes with concurrency **3** and runs **one** media rescan at the end.
- User-visible strings live in `composeApp/src/commonMain/composeResources/values*/strings.xml`.
  - There are 18 files: `values` plus ar, de, es, fr, hi, id, in, it, ja, ko, pl, pt-rBR, ro, ru, tr, uk, zh-rCN.
  - Every new key goes into all 18, with a real translation.
  - `values-in` is byte-identical to `values-id`.
  - A literal percent is written `%`, not `%%`.
  - Counts use `<plurals>` (`pluralStringResource`), as `count_tracks` does.
- Test commands, run from the worktree:
  - `./gradlew :core:library:testAndroidHostTest :composeApp:testAndroidHostTest --offline -q --rerun`
  - `./gradlew :core:library:compileTestKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64 --offline -q`
  - `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q`
  - Read counts from `*/build/test-results/testAndroidHostTest/*.xml`. Baselines at 97d7a56: `core:library` 507, `composeApp` 598.
- Commits:
  - The author is the configured git user.
  - Messages must NOT contain a Co-Authored-By line, a "Generated with" line, or any mention of an AI, LLM or assistant.
  - Use the repository style (`feat(tags): …`, `fix(tags): …`) with a subject that says what the user gets.
  - Never push.
- Match the surrounding code's idiom and comment density. The codebase explains *why* in KDoc.

## Review Focus

1. **Power loss at any instant, including inside `force()` and while unforced bytes are half-persisted, and a second power loss during recovery.** The track must end byte-identical to either the original or the verified edit, and the store must end empty. This is pinned by the fault-injection suites of Tasks 5 and 6.
2. **The file changed by someone else while a journal record was open.** For example, a crash leaves `PATCH_PREPARED`, and another tagger edits the file before LatentJam is reopened. Recovery must not write stale bytes over the other app's change: it reports the record as stuck and leaves the file. This is pinned by `recoveryLeavesAFileSomeoneElseChanged` (Task 5).
3. **No space in the app store or on the target volume, at preflight and mid-write** (`StorageFullException` from any write). Nothing is damaged, and the result is `NotEnoughSpace` or `Failed` with the file unchanged. Pinned in Tasks 5 and 6.
4. **Process death while a consent dialog is up, or mid-batch.** The restored coordinator re-asks consent instead of assuming it, finishes or recovers the file that was being written, and never double-counts. Pinned in Task 8.
5. **More than 2,000 files, a revoked grant between batches, and a denied storage permission on Android 7–9.** Split requests are used, the unfinished tail is reported as denied, and nothing is written without consent. Pinned in Task 8.

---

## File Structure

**core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/**

| File | Purpose |
|---|---|
| `TagVerification.kt` (modify) | `Baseline` and `verifyTags`, a verification without an audio pass |
| `TagCodec.kt` (modify) | `TagCodecs.planSafely`: a codec exception becomes a refusal |
| `write/WriteFiles.kt` | `TargetFile`, `RecoveryDirectory`, `AtomicReplacer`, `StorageFullException`, `FileOps` helpers |
| `write/Journal.kt` | `JournalState`, `JournalRecord`, `Journal` (one CRC-checked append-only file per write) |
| `write/PatchBackup.kt` | saved ranges and tail of an in-place patch; `explains()` guards against foreign changes |
| `write/DurableWriter.kt` | `WriteResult`, `DurableWriter` (§5.2 in place, §5.3 copy-over or atomic replace) |
| `write/TagRecovery.kt` | `TagRecovery` (§5.4 roll back or finish forward, `sweep`) |

**core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/**

| File | Purpose |
|---|---|
| `FaultFiles.kt` | in-memory file system with power loss, torn writes, unsynced directory entries and a capacity limit |
| `FaultFilesTest.kt`, `JournalTest.kt`, `PatchBackupTest.kt` | unit tests for those pieces |
| `DurableWriterPatchTest.kt`, `DurableWriterRewriteTest.kt` | protocol tests and crash-everywhere suites |
| `WriteFixtures.kt` | small MP3, FLAC, Ogg and M4A files and edits that force each path |

**core/library/src/androidMain/…/tags/write/** (JVM; also runs in host tests)

| File | Purpose |
|---|---|
| `ChannelTargetFile.kt` | `TargetFile` over a read and a write `FileChannel` |
| `FileRecoveryDirectory.kt` | `RecoveryDirectory` over `java.io.File`, with a pluggable directory sync |

**core/library/src/androidHostTest/…/tags/write/**

| File | Purpose |
|---|---|
| `JvmWriteFilesTest.kt` | the adapters against real temp files |
| `DurableWriteRealFileTest.kt` | the corpus through the durable writer (self-skips without `TAG_REAL_FILES`) |

**core/library/src/iosMain/kotlin/io/github/nikitasud/latentjam/library/**

| File | Purpose |
|---|---|
| `IosTagFiles.kt` | POSIX `TargetFile` (pread, pwrite, ftruncate, F_FULLFSYNC), `IosRecoveryDirectory`, `IosAtomicReplacer`, `writableIosTrackPath` |

**composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/**

| File | Purpose |
|---|---|
| `TagWriteCoordinator.kt` | request state machine: consent strategies, 2,000 split, concurrency 3, stop, checkpoint, recovery requests, one rescan |
| `TrackTagWriter.kt` (modify) | `TagWriteOutcome` with `TagRefusal`, `NotEnoughSpace`, `RecoveryPending`; the report types |
| `TagRecoveryPrompt.kt` | "LatentJam needs to finish saving N files" dialog, shown by `App` |
| `TrackInfoSheet.kt` (modify) | messages for the new outcomes |

**composeApp platform files**

| File | Purpose |
|---|---|
| `androidMain/…/app/TrackTagWriter.android.kt` (rewrite) | Android backend: consent per API level, `FileChannel` target, `StatFs`, batch rescan; `rememberTagWriter` / `rememberTagRecovery` actuals |
| `iosMain/…/app/TrackTagWriter.ios.kt` (rewrite) | iOS backend: POSIX target, atomic replacer, no consent; the actuals |

**composeApp/src/commonTest/…/app/TagWriteCoordinatorTest.kt** — consent flows, splitting, process death, stop, recovery, report.

**Strings** — every values*/strings.xml gets the new outcome and recovery-prompt strings.

---

### Task 1: Verification without an audio pass, and plans that cannot throw

The in-place path must never read the audio (spec §1, priority 2), but `TagVerification.verify` digests it. An in-place write touches only tag bytes, and its ranges are checked byte for byte (Task 5), so read-back plus inventory is the complete check there. A codec that throws inside `plan()` (the `InPlacePatch` `require`s) must become a refusal before the writer sees it. That item was carried over from the plan-1 review (N1).

**Files:**
- Modify: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagVerification.kt`
- Modify: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagCodec.kt`
- Test: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/TagVerificationBaselineTest.kt`

**Interfaces:**
- Produces:
  - `TagVerification.Baseline(snapshot: TagSnapshot, inventory: List<String>)`
  - `TagVerification.baseline(codec: TagCodec, source: RandomAccessSource): Baseline`
  - `TagVerification.verifyTags(codec: TagCodec, baseline: Baseline, after: RandomAccessSource, edits: TagEdits): List<Failure>`
  - `TagCodecs.planSafely(codec: TagCodec, source: RandomAccessSource, edits: TagEdits): WritePlan`

- [ ] **Step 1: Write the failing test**

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

internal class TagVerificationBaselineTest {
    private val original = Id3TestTags.build(3, listOf(TestFrame("TIT2", Id3TestTags.latin1Body("Old")))) +
        Id3TestTags.mp3Payload()

    @Test
    fun aCorrectInPlaceEditVerifiesWithoutAnAudioPass() {
        val codec = Id3TagCodec
        val source = ByteArraySource(original)
        val baseline = TagVerification.baseline(codec, source)
        val edits = TagEdits(title = "New")
        val edited = WritePlans.applyInMemory(original, codec.plan(source, edits))!!
        assertEquals(emptyList(), TagVerification.verifyTags(codec, baseline, ByteArraySource(edited), edits))
    }

    @Test
    fun aWrongFieldFailsTheTagCheck() {
        val codec = Id3TagCodec
        val baseline = TagVerification.baseline(codec, ByteArraySource(original))
        val failures = TagVerification.verifyTags(codec, baseline, ByteArraySource(original), TagEdits(title = "New"))
        assertEquals(TagVerification.Check.READ_BACK, failures.single().check)
    }

    @Test
    fun aRefusedOriginalNeverVerifies() {
        val broken = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 2, 0, 0, 0, 0, 0, 10) +
            ByteArray(10)
        val baseline = TagVerification.baseline(Id3TagCodec, ByteArraySource(broken))
        val failures = TagVerification.verifyTags(Id3TagCodec, baseline, ByteArraySource(broken), TagEdits(title = "x"))
        assertEquals(TagVerification.Check.EDITABLE, failures.single().check)
    }

    @Test
    fun aCodecThatThrowsIsARefusalNotACrash() {
        val throwing = object : TagCodec by Id3TagCodec {
            override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan =
                WritePlan.InPlacePatch(listOf(ByteWrite(-1, byteArrayOf(1))), 10)
        }
        val plan = TagCodecs.planSafely(throwing, ByteArraySource(original), TagEdits(title = "x"))
        assertIs<WritePlan.Refused>(plan)
        assertEquals(TagRefusal.PLAN_INCONSISTENT, plan.reason)
        assertTrue(TagCodecs.planSafely(Id3TagCodec, ByteArraySource(original), TagEdits(title = "x")) !is WritePlan.Refused)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*TagVerificationBaselineTest*'`
Expected: compilation fails with unresolved `baseline`, `verifyTags` and `planSafely`.

- [ ] **Step 3: Implement**

In `TagVerification.kt`, add inside `object TagVerification` (after `Failure`), and make `verify` reuse them:

```kotlin
    /** What a file said before a write: all an in-place check needs, captured before any byte moves. */
    public class Baseline(public val snapshot: TagSnapshot, public val inventory: List<String>)

    public fun baseline(codec: TagCodec, source: RandomAccessSource): Baseline =
        Baseline(codec.read(source), codec.inventory(source))

    /**
     * Read-back and inventory checks of [after] against [baseline], with no audio pass. For writes
     * whose ranges are checked byte for byte and lie inside the tag (in-place patches): reading the
     * audio there would cost exactly what the fast path exists to avoid.
     */
    public fun verifyTags(codec: TagCodec, baseline: Baseline, after: RandomAccessSource, edits: TagEdits): List<Failure> {
        baseline.snapshot.refusal?.let { return listOf(Failure(Check.EDITABLE, "the original was refused: $it")) }
        val failures = ArrayList(readBackFailures(baseline.snapshot, edits, codec.read(after)))
        val expected = expectedInventory(baseline.inventory, edits)
        val actual = codec.inventory(after)
        if (expected != actual) failures += Failure(Check.INVENTORY, "expected $expected\nactual   $actual")
        return failures
    }
```

Then replace the body of `verify` with:

```kotlin
        val failures = ArrayList(verifyTags(codec, baseline(codec, before), after, edits))
        if (failures.any { it.check == Check.EDITABLE }) return failures
        val audioBefore = codec.audioDigest(before)
        val audioAfter = codec.audioDigest(after)
        if (!audioMatches(audioBefore, audioAfter)) {
            failures += Failure(Check.AUDIO, "digest $audioBefore before, $audioAfter after")
        }
        return failures
```

(The order of failures changes from READ_BACK, AUDIO, INVENTORY to READ_BACK, INVENTORY, AUDIO. Tests assert on membership. If one asserts on order, update it and say so in the report.)

In `TagCodec.kt`, inside `object TagCodecs`:

```kotlin
    /**
     * [TagCodec.plan], where a codec that throws — a plan breaking its own invariants, an index
     * past an array — is a refusal. A codec bug must cost the user an edit, never reach the writer.
     */
    public fun planSafely(codec: TagCodec, source: RandomAccessSource, edits: TagEdits): WritePlan = try {
        codec.plan(source, edits)
    } catch (_: Exception) {
        WritePlan.Refused(TagRefusal.PLAN_INCONSISTENT)
    }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --rerun`. Expected: every test passes (507 + 4).

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagVerification.kt core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/TagCodec.kt core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/TagVerificationBaselineTest.kt
git commit -m "feat(tags): an in-place save is verified without reading the audio, and a codec slip becomes a refusal"
```

---

### Task 2: Files that can be forced to storage, and a file system that loses power on demand

**Files:**
- Create: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/WriteFiles.kt`
- Create: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/FaultFiles.kt`
- Test: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/FaultFilesTest.kt`

**Interfaces:**
- Produces:
  - `TargetFile : RandomAccessSource, AutoCloseable` with `write(offset, bytes, from = 0, count = …)`, `setLength(length)` and `force()`.
  - `RecoveryDirectory` with `create(name)`, `open(name)`, `delete(name)`, `names()`, `sync()` and `freeBytes()`.
  - `fun interface AtomicReplacer { fun replace(key: String, stagedName: String): TargetFile }`
  - `class StorageFullException : Exception`
  - `internal object FileOps` with `crc(source): Long?`, `readAll(source): ByteArray?`, `copyOver(from, to): Long`, and `class AppendingSink(file: TargetFile) : ByteSink`.
  - Test-only: `FaultFiles` and `PowerLoss : Error`.

- [ ] **Step 1: Write `WriteFiles.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteSink
import io.github.nikitasud.latentjam.library.tags.Crc32
import io.github.nikitasud.latentjam.library.tags.RandomAccessSource
import io.github.nikitasud.latentjam.library.tags.WritePlans

/**
 * A file the durable writer changes: the track being edited, or a file in the recovery store.
 *
 * Platforms implement it over a file descriptor and do not buffer: [write] hands bytes to the OS,
 * and [force] returns only once every earlier write and length change is on storage (`fsync`,
 * `F_FULLFSYNC` on Apple platforms, where `fsync` alone does not flush the drive's cache).
 */
public interface TargetFile : RandomAccessSource, AutoCloseable {
    /** Writes [count] bytes of [bytes] from [from] at [offset], extending the file when past its end. */
    public fun write(offset: Long, bytes: ByteArray, from: Int = 0, count: Int = bytes.size - from)

    /** Truncates, or extends with zeros, to exactly [length] bytes. */
    public fun setLength(length: Long)

    public fun force()
}

/**
 * App-private storage for the journal and the bytes a recovery needs. Never a cache directory: the
 * system may clear those at any moment, and with them the only way back for a half-written file.
 */
public interface RecoveryDirectory {
    /** A new, empty file named [name], replacing any existing one. */
    public fun create(name: String): TargetFile

    /** The file named [name], or null when there is none. */
    public fun open(name: String): TargetFile?

    public fun delete(name: String)

    public fun names(): List<String>

    /** Makes every create and delete so far durable (an fsync of the directory itself). */
    public fun sync()

    public fun freeBytes(): Long
}

/**
 * Replaces a track file with a staged one in one atomic step — iOS, where the track and the store
 * share a volume and a rename cannot leave a half-written file behind.
 */
public fun interface AtomicReplacer {
    /** Moves store file [stagedName] over the track [key], durably, and opens the result. */
    public fun replace(key: String, stagedName: String): TargetFile
}

/** A write ran out of space. Every step that can meet it leaves the track whole. */
public class StorageFullException(message: String) : Exception(message)

internal object FileOps {
    fun crc(source: RandomAccessSource): Long? {
        val crc = Crc32()
        var position = 0L
        while (position < source.length) {
            val count = minOf(WritePlans.COPY_CHUNK.toLong(), source.length - position).toInt()
            crc.update(source.read(position, count) ?: return null)
            position += count
        }
        return crc.value
    }

    fun readAll(source: RandomAccessSource): ByteArray? =
        if (source.length > Int.MAX_VALUE) null else source.read(0, source.length.toInt())

    /** Writes all of [from] over [to] from offset 0 and sets the length; the CRC of what was written. */
    fun copyOver(from: RandomAccessSource, to: TargetFile): Long {
        val crc = Crc32()
        var position = 0L
        while (position < from.length) {
            val count = minOf(WritePlans.COPY_CHUNK.toLong(), from.length - position).toInt()
            val chunk = from.read(position, count) ?: throw IllegalStateException("short read at $position")
            to.write(position, chunk)
            crc.update(chunk)
            position += count
        }
        to.setLength(from.length)
        return crc.value
    }
}

/** A [ByteSink] that appends to a [TargetFile] and keeps the CRC of what it wrote. */
internal class AppendingSink(private val file: TargetFile) : ByteSink {
    var position = 0L
        private set
    private val crc = Crc32()
    val crcValue: Long get() = crc.value

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        file.write(position, bytes, offset, count)
        crc.update(bytes, offset, count)
        position += count
    }
}
```

- [ ] **Step 2: Write the fake, `FaultFiles.kt` (commonTest)**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

/** A simulated power loss. An Error, so production `catch (e: Exception)` never swallows it. */
internal class PowerLoss : Error("simulated power loss")

/**
 * An in-memory file system for crash testing.
 *
 * Model:
 * - Every file has durable bytes (what storage holds) and current bytes (what reads see).
 * - `force()` makes current durable.
 * - Directory entries created or deleted since the last `sync()` are not durable.
 * - [crashAt] throws [PowerLoss] at the Nth mutating operation.
 * - [powerLoss] then rebuilds what storage would hold. Every file keeps its durable bytes plus the
 *   first `keep` bytes of its unforced writes, in order: a torn write at any byte.
 * - Track files (made with [put]) are always durable entries; store files live in [directory].
 */
internal class FaultFiles(var storeCapacity: Long = Long.MAX_VALUE) {
    private class Node(var durable: ByteArray) {
        var current: ByteArray = durable.copyOf()
        val pending = ArrayList<Change>()
    }

    private sealed interface Change {
        class Write(val offset: Long, val bytes: ByteArray) : Change
        class Length(val length: Long) : Change
    }

    private val tracks = LinkedHashMap<String, Node>()
    private var store = LinkedHashMap<String, Node>()
    private var syncedStore: Map<String, Node> = emptyMap()

    /** 0 = never. */
    var crashAt: Int = 0
    var operations: Int = 0
        private set

    fun put(name: String, bytes: ByteArray) {
        tracks[name] = Node(bytes.copyOf())
    }

    fun track(name: String): TargetFile = NodeFile(tracks.getValue(name), store = false)

    fun trackBytes(name: String): ByteArray = tracks.getValue(name).current.copyOf()

    fun storeNames(): Set<String> = store.keys.toSet()

    /** Bytes written since the last force, across all files: the range of useful `keep` values. */
    fun pendingBytes(): Long = (tracks.values + store.values).sumOf { node ->
        node.pending.sumOf { if (it is Change.Write) it.bytes.size.toLong() else 0L }
    }

    private fun tick() {
        operations++
        if (operations == crashAt) throw PowerLoss()
    }

    fun powerLoss(keep: Long = 0) {
        store = LinkedHashMap(syncedStore)
        for (node in tracks.values + store.values) {
            var budget = keep
            var data = node.durable
            for (change in node.pending) {
                if (budget <= 0) break
                data = when (change) {
                    is Change.Write -> {
                        val n = minOf(budget, change.bytes.size.toLong()).toInt()
                        budget -= n
                        splice(data, change.offset, change.bytes.copyOf(n))
                    }
                    is Change.Length -> data.copyOf(change.length.toInt())
                }
            }
            node.durable = data
            node.current = data.copyOf()
            node.pending.clear()
        }
        crashAt = 0
        operations = 0
    }

    private fun storeSize(): Long = store.values.sumOf { it.current.size.toLong() }

    val directory: RecoveryDirectory = object : RecoveryDirectory {
        override fun create(name: String): TargetFile {
            tick()
            val node = Node(ByteArray(0))
            store[name] = node
            return NodeFile(node, store = true)
        }

        override fun open(name: String): TargetFile? = store[name]?.let { NodeFile(it, store = true) }

        override fun delete(name: String) {
            tick()
            store.remove(name)
        }

        override fun names(): List<String> = store.keys.toList()

        override fun sync() {
            tick()
            syncedStore = LinkedHashMap(store)
        }

        override fun freeBytes(): Long = storeCapacity - storeSize()
    }

    private inner class NodeFile(private val node: Node, private val store: Boolean) : TargetFile {
        override val length: Long get() = node.current.size.toLong()

        override fun read(offset: Long, count: Int): ByteArray? {
            if (offset < 0 || count < 0 || offset > node.current.size || count > node.current.size - offset) return null
            return node.current.copyOfRange(offset.toInt(), offset.toInt() + count)
        }

        override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
            tick()
            val chunk = bytes.copyOfRange(from, from + count)
            val grows = maxOf(0L, offset + count - node.current.size)
            if (store && grows > 0 && storeSize() + grows > storeCapacity) throw StorageFullException("store full")
            node.current = splice(node.current, offset, chunk)
            node.pending += Change.Write(offset, chunk)
        }

        override fun setLength(length: Long) {
            tick()
            node.current = node.current.copyOf(length.toInt())
            node.pending += Change.Length(length)
        }

        override fun force() {
            tick()
            node.durable = node.current.copyOf()
            node.pending.clear()
        }

        override fun close() = Unit
    }

    private companion object {
        fun splice(data: ByteArray, offset: Long, chunk: ByteArray): ByteArray {
            val end = offset.toInt() + chunk.size
            val out = if (end > data.size) data.copyOf(end) else data.copyOf()
            chunk.copyInto(out, offset.toInt())
            return out
        }
    }
}
```

- [ ] **Step 3: Write `FaultFilesTest.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class FaultFilesTest {
    @Test
    fun unforcedWritesAreLostAndForcedOnesStay() {
        val files = FaultFiles()
        files.put("t", byteArrayOf(1, 2, 3))
        files.track("t").apply { write(0, byteArrayOf(9)); force(); write(1, byteArrayOf(8)) }
        files.powerLoss()
        assertContentEquals(byteArrayOf(9, 2, 3), files.trackBytes("t"))
    }

    @Test
    fun aTornWriteKeepsExactlyItsFirstBytes() {
        val files = FaultFiles()
        files.put("t", ByteArray(4))
        files.track("t").write(0, byteArrayOf(1, 2, 3, 4))
        files.powerLoss(keep = 2)
        assertContentEquals(byteArrayOf(1, 2, 0, 0), files.trackBytes("t"))
    }

    @Test
    fun unsyncedDirectoryEntriesVanish() {
        val files = FaultFiles()
        files.directory.create("a").apply { write(0, byteArrayOf(1)); force() }
        files.powerLoss()
        assertNull(files.directory.open("a"))
        files.directory.create("b").apply { write(0, byteArrayOf(1)); force() }
        files.directory.sync()
        files.directory.delete("b")
        files.powerLoss()
        assertContentEquals(byteArrayOf(1), files.directory.open("b")!!.read(0, 1))
    }

    @Test
    fun theNthOperationLosesPower() {
        val files = FaultFiles()
        files.put("t", ByteArray(2))
        files.crashAt = 2
        val file = files.track("t")
        file.write(0, byteArrayOf(1))
        assertFailsWith<PowerLoss> { file.force() }
        assertEquals(2, files.operations)
    }

    @Test
    fun theStoreRefusesToGrowPastItsCapacity() {
        val files = FaultFiles(storeCapacity = 3)
        val file = files.directory.create("a")
        file.write(0, byteArrayOf(1, 2, 3))
        assertFailsWith<StorageFullException> { file.write(3, byteArrayOf(4)) }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*FaultFilesTest*'`. Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/WriteFiles.kt core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/
git commit -m "feat(tags): the files a save writes, and a test file system that loses power at any step"
```

---

### Task 3: A journal a torn write cannot confuse

One journal file per write, named `<writeId>.journal`. Concurrent saves never share a file, so there is no lock. Each record is one line, `v1 \t id \t target \t state \t originalLength \t finalLength \t stagedCrc \t originalCrc \t atomic \t crc32hex \n`, where the target is percent-escaped for `%`, tab, CR and LF.

- A line with a bad CRC, or a trailing fragment with no newline, is ignored.
- Before appending after a torn fragment, a newline is written first, so the fragment cannot swallow the next record.
- The latest valid record is the write's state.

**Files:**
- Create: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/Journal.kt`
- Test: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/JournalTest.kt`

**Interfaces:**
- Produces:
  - `enum class JournalState { PATCH_PREPARED, REPLACE_PREPARED, REPLACING, DONE, ROLLED_BACK, RESTORED, ABANDONED }` with `val finished: Boolean`.
  - `data class JournalRecord(writeId: String, target: String, state: JournalState, originalLength: Long, finalLength: Long, stagedCrc: Long = -1, originalCrc: Long = -1, atomic: Boolean = false)` with the derived names `journalName`, `patchName`, `stagedName` and `backupName`.
  - `class Journal(directory: RecoveryDirectory)` with `append(record)`, `latest(writeId): JournalRecord?`, `open(): List<JournalRecord>` and `forget(writeId)`.

- [ ] **Step 1: Write the failing test**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class JournalTest {
    private fun record(state: JournalState, id: String = "w1") =
        JournalRecord(id, "content://media/external/audio/media/7\tweird\nname%", state, 100, 120, 5, 6, atomic = true)

    @Test
    fun theLatestRecordWinsAndSurvivesAReopen() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.REPLACE_PREPARED))
        journal.append(record(JournalState.REPLACING))
        assertEquals(record(JournalState.REPLACING), Journal(files.directory).latest("w1"))
        assertEquals(listOf(record(JournalState.REPLACING)), Journal(files.directory).open())
    }

    @Test
    fun finishedWritesAreNotOpen() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.PATCH_PREPARED))
        journal.append(record(JournalState.DONE))
        assertTrue(journal.open().isEmpty())
    }

    @Test
    fun aTornRecordIsIgnoredAndDoesNotSwallowTheNextOne() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.PATCH_PREPARED))
        // A record torn after 10 bytes by a power loss.
        val file = files.directory.open("w1.journal")!!
        file.write(file.length, "v1\tw1\tcont".encodeToByteArray())
        file.force()
        assertEquals(JournalState.PATCH_PREPARED, journal.latest("w1")?.state)
        journal.append(record(JournalState.ROLLED_BACK))
        assertEquals(JournalState.ROLLED_BACK, Journal(files.directory).latest("w1")?.state)
    }

    @Test
    fun aCorruptedLineIsIgnored() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.PATCH_PREPARED))
        val file = files.directory.open("w1.journal")!!
        val bytes = file.read(0, file.length.toInt())!!
        bytes[5] = (bytes[5] + 1).toByte()
        file.write(0, bytes)
        assertNull(journal.latest("w1"))
    }

    @Test
    fun aJournalWhoseCreationWasNotSyncedIsGoneAfterPowerLoss() {
        val files = FaultFiles()
        files.crashAt = 3 // create, write, force — then the directory sync never happens
        runCatching { Journal(files.directory).append(record(JournalState.PATCH_PREPARED)) }
        files.powerLoss()
        assertTrue(Journal(files.directory).open().isEmpty())
    }

    @Test
    fun forgetDeletesTheWritesJournal() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.DONE))
        journal.forget("w1")
        assertTrue("w1.journal" !in files.storeNames())
    }
}
```

(`runCatching` catches `Throwable`, `PowerLoss` included. That is right in a *test*: the test simulates the crash and carries on after it. Production code in `tags.write` must never do the same (see Task 4).)

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*JournalTest*'`. Expected: compilation fails, `Journal` unresolved.

- [ ] **Step 3: Implement `Journal.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.Crc32

/** Where one file's save stands. Each state is on storage before the step it announces begins. */
public enum class JournalState {
    /** Saved ranges are durable; the track may now be patched (§5.2). */
    PATCH_PREPARED,

    /** A verified staged file (and, without an atomic replace, a verified backup) is durable (§5.3). */
    REPLACE_PREPARED,

    /** The copy over the track has begun. */
    REPLACING,

    DONE,
    ROLLED_BACK,

    /** The track was put back from its backup. */
    RESTORED,

    /**
     * The track changed under an open record — another app edited it after a crash. Nothing of
     * ours was written over it; the record is closed so the file can be edited again.
     */
    ABANDONED,
    ;

    public val finished: Boolean get() = this != PATCH_PREPARED && this != REPLACE_PREPARED && this != REPLACING
}

/**
 * One save of one file. [target] is the platform's key: a content URI, or a path under Documents.
 * [originalCrc] is the CRC of the whole original (rewrites only): the backup's checksum when there
 * is one, and in [atomic] mode — no backup, a rename replaces the file — what tells an untouched
 * original from a file someone else has since changed.
 */
public data class JournalRecord(
    public val writeId: String,
    public val target: String,
    public val state: JournalState,
    public val originalLength: Long,
    public val finalLength: Long,
    public val stagedCrc: Long = -1,
    public val originalCrc: Long = -1,
    public val atomic: Boolean = false,
) {
    public val journalName: String get() = "$writeId.journal"
    public val patchName: String get() = "$writeId.patch"
    public val stagedName: String get() = "$writeId.staged"
    public val backupName: String get() = "$writeId.backup"
}

/**
 * The write-ahead log (§5.1), one append-only file per save so concurrent saves need no lock.
 *
 * Every record carries its own CRC. A record torn by a power loss fails it and is ignored, and the
 * state falls back to the last complete one — which is exactly how far the save is known to have
 * got, since each record is forced before the step it announces.
 */
public class Journal(private val directory: RecoveryDirectory) {

    public fun append(record: JournalRecord) {
        val existing = directory.open(record.journalName)
        val file = existing ?: directory.create(record.journalName)
        file.use {
            var at = it.length
            // A fragment torn mid-record must end its own line, or it would swallow this record.
            if (at > 0 && it.read(at - 1, 1)?.get(0) != NEWLINE) {
                it.write(at, byteArrayOf(NEWLINE))
                at++
            }
            it.write(at, encode(record))
            it.force()
        }
        if (existing == null) directory.sync()
    }

    public fun latest(writeId: String): JournalRecord? {
        val bytes = directory.open("$writeId.journal")?.use { FileOps.readAll(it) } ?: return null
        var latest: JournalRecord? = null
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] != NEWLINE) continue
            decode(bytes, start, i)?.takeIf { it.writeId == writeId }?.let { latest = it }
            start = i + 1
        }
        return latest
    }

    /** Every save that has not reached a finished state, oldest first by name. */
    public fun open(): List<JournalRecord> = directory.names()
        .filter { it.endsWith(SUFFIX) }
        .sorted()
        .mapNotNull { latest(it.removeSuffix(SUFFIX)) }
        .filterNot { it.state.finished }

    /** Deletes a finished save's journal. Its data files must already be gone. */
    public fun forget(writeId: String) {
        directory.delete("$writeId$SUFFIX")
        directory.sync()
    }

    internal companion object {
        const val SUFFIX = ".journal"
        private const val NEWLINE = '\n'.code.toByte()

        fun encode(record: JournalRecord): ByteArray {
            val body = listOf(
                "v1", record.writeId, escape(record.target), record.state.name,
                record.originalLength.toString(), record.finalLength.toString(),
                record.stagedCrc.toString(), record.originalCrc.toString(), record.atomic.toString(),
            ).joinToString("\t")
            val crc = Crc32.of(body.encodeToByteArray())
            return "$body\t${crc.toString(16)}\n".encodeToByteArray()
        }

        fun decode(bytes: ByteArray, start: Int, end: Int): JournalRecord? = runCatching {
            val line = bytes.decodeToString(start, end)
            val cut = line.lastIndexOf('\t')
            val body = line.substring(0, cut)
            check(Crc32.of(body.encodeToByteArray()).toString(16) == line.substring(cut + 1))
            val f = body.split('\t')
            check(f.size == 9 && f[0] == "v1")
            JournalRecord(
                writeId = f[1],
                target = unescape(f[2]),
                state = JournalState.valueOf(f[3]),
                originalLength = f[4].toLong(),
                finalLength = f[5].toLong(),
                stagedCrc = f[6].toLong(),
                originalCrc = f[7].toLong(),
                atomic = f[8].toBooleanStrict(),
            )
        }.getOrNull()

        private fun escape(text: String): String =
            text.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")

        private fun unescape(text: String): String =
            text.replace("%0D", "\r").replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*JournalTest*'`. Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/Journal.kt core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/JournalTest.kt
git commit -m "feat(tags): a save journal whose half-written records are recognised and ignored"
```

---

### Task 4: The bytes an in-place patch overwrites, kept aside

`PatchBackup` holds everything needed to undo an in-place patch, serialised with a trailing CRC-32:
- the original bytes of every range the patch overwrites (clipped to the original length: bytes past the old end did not exist);
- the new bytes the patch writes;
- the tail a shrinking patch cuts off;
- both lengths;
- `restCrc`: a CRC of the untouched bytes in the file's first 256 KiB and last 64 KiB, where every format keeps its tags.

`explains(target)` is true only when the file is the original, the patched file, or a torn mix of the two:
- the length lies between the two lengths;
- inside every written range each byte is the old or the new byte at that position;
- the tail holds original bytes or zeros;
- `restCrc` still matches.

Anything else means someone else changed the file after a crash. Recovery must then leave it alone (Review Focus 2).

**Files:**
- Create: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/PatchBackup.kt`
- Test: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/PatchBackupTest.kt`

**Interfaces:**
- Consumes: `WritePlan.InPlacePatch`, `ByteWrite`, `TargetFile`, `ByteArraySink`, `Crc32`, `WritePlans.COPY_CHUNK`.
- Produces: `internal class PatchBackup(originalLength, newLength, ranges, writes, tail, restCrc)` with:
  - `encode(): ByteArray`
  - `restore(target: TargetFile)` (writes the old bytes and tail, sets the length, forces)
  - `matches(target): Boolean` (the target is exactly the original)
  - `explains(target): Boolean`
  - `companion capture(target, plan): PatchBackup?`
  - `companion decode(bytes): PatchBackup?`

- [ ] **Step 1: Write the failing test**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteWrite
import io.github.nikitasud.latentjam.library.tags.WritePlan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PatchBackupTest {
    private val original = ByteArray(20) { it.toByte() }

    private fun files(bytes: ByteArray = original) = FaultFiles().apply { put("t", bytes) }

    @Test
    fun aShrinkingPatchRestoresRangesAndTheCutTail() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(2, byteArrayOf(50, 51))), newLength = 16)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        files.track("t").apply { write(2, byteArrayOf(50, 51)); setLength(16) }
        assertTrue(backup.explains(files.track("t")))
        backup.restore(files.track("t"))
        assertContentEquals(original, files.trackBytes("t"))
        assertTrue(backup.matches(files.track("t")))
    }

    @Test
    fun aGrowingPatchSavesOnlyBytesThatExisted() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(18, byteArrayOf(1, 1, 1, 1))), newLength = 22)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        assertEquals(2, backup.ranges.single().bytes.size)
        files.track("t").write(18, byteArrayOf(1, 1, 1, 1))
        backup.restore(files.track("t"))
        assertContentEquals(original, files.trackBytes("t"))
    }

    @Test
    fun itSurvivesEncodingAndRejectsACorruptCopy() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(0, byteArrayOf(9))), newLength = 19)
        val backup = assertNotNull(PatchBackup.capture(files().track("t"), plan))
        val bytes = backup.encode()
        val decoded = assertNotNull(PatchBackup.decode(bytes))
        assertEquals(backup.originalLength, decoded.originalLength)
        assertEquals(backup.newLength, decoded.newLength)
        assertContentEquals(backup.tail!!.bytes, decoded.tail!!.bytes)
        assertContentEquals(backup.writes.single().bytes, decoded.writes.single().bytes)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        assertNull(PatchBackup.decode(bytes))
    }

    @Test
    fun aTornPatchIsExplainedButAForeignChangeIsNot() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(4, byteArrayOf(70, 71, 72))), newLength = 20)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        files.track("t").write(4, byteArrayOf(70)) // torn after one byte
        assertTrue(backup.explains(files.track("t")))
        files.track("t").write(10, byteArrayOf(99)) // another app's edit, outside our ranges
        assertFalse(backup.explains(files.track("t")))
    }

    @Test
    fun aForeignChangeInsideAWrittenRangeIsNotExplained() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(4, byteArrayOf(70, 71, 72))), newLength = 20)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        files.track("t").write(5, byteArrayOf(33)) // neither the old 5 nor our 71
        assertFalse(backup.explains(files.track("t")))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*PatchBackupTest*'`. Expected: compilation fails, `PatchBackup` unresolved.

- [ ] **Step 3: Implement `PatchBackup.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteArraySink
import io.github.nikitasud.latentjam.library.tags.ByteWrite
import io.github.nikitasud.latentjam.library.tags.Crc32
import io.github.nikitasud.latentjam.library.tags.RandomAccessSource
import io.github.nikitasud.latentjam.library.tags.WritePlan
import io.github.nikitasud.latentjam.library.tags.WritePlans

/**
 * Everything needed to undo an in-place patch (§5.2), and to tell "our half-finished patch" from
 * "a file someone else has since changed" — a recovery must put back the first and leave the second.
 */
internal class PatchBackup(
    val originalLength: Long,
    val newLength: Long,
    /** The original bytes each write covers, clipped to [originalLength]. */
    val ranges: List<ByteWrite>,
    /** What the patch writes. */
    val writes: List<ByteWrite>,
    /** The original bytes from [newLength] to [originalLength] when the patch shrinks the file. */
    val tail: ByteWrite?,
    val restCrc: Long,
) {
    fun restore(target: TargetFile) {
        for (range in ranges) target.write(range.offset, range.bytes)
        tail?.let { target.write(it.offset, it.bytes) }
        target.setLength(originalLength)
        target.force()
    }

    fun matches(target: RandomAccessSource): Boolean =
        target.length == originalLength &&
            ranges.all { target.read(it.offset, it.bytes.size)?.contentEquals(it.bytes) == true } &&
            (tail == null || target.read(tail.offset, tail.bytes.size)?.contentEquals(tail.bytes) == true) &&
            restCrc(target, writes, minOf(originalLength, newLength)) == restCrc

    fun explains(target: RandomAccessSource): Boolean {
        val low = minOf(originalLength, newLength)
        if (target.length !in low..maxOf(originalLength, newLength)) return false
        for (write in writes) {
            val old = ranges.firstOrNull { it.offset == write.offset }?.bytes ?: ByteArray(0)
            val count = minOf(write.bytes.size.toLong(), maxOf(0L, target.length - write.offset)).toInt()
            val now = target.read(write.offset, count) ?: return false
            for (i in 0 until count) {
                // Past the original end a torn extension reads as zeros.
                val before = if (i < old.size) old[i] else 0
                if (now[i] != before && now[i] != write.bytes[i]) return false
            }
        }
        tail?.let { t ->
            val count = minOf(t.bytes.size.toLong(), maxOf(0L, target.length - t.offset)).toInt()
            val now = target.read(t.offset, count) ?: return false
            for (i in 0 until count) if (now[i] != t.bytes[i] && now[i] != 0.toByte()) return false
        }
        return restCrc(target, writes, low) == restCrc
    }

    fun encode(): ByteArray {
        val out = Writer()
        out.bytes(MAGIC)
        out.long(originalLength)
        out.long(newLength)
        out.long(restCrc)
        out.ranges(ranges)
        out.ranges(writes)
        out.int(if (tail == null) 0 else 1)
        tail?.let { out.range(it) }
        val body = out.toByteArray()
        return body + Writer().apply { long(Crc32.of(body)) }.toByteArray()
    }

    companion object {
        private val MAGIC = "LJPB1".encodeToByteArray()
        private const val HEAD_WINDOW = 256L * 1024
        private const val TAIL_WINDOW = 64L * 1024

        fun capture(target: RandomAccessSource, plan: WritePlan.InPlacePatch): PatchBackup? {
            val length = target.length
            val ranges = plan.writes.mapNotNull { write ->
                val end = minOf(write.offset + write.bytes.size, length)
                if (write.offset >= end) null
                else ByteWrite(write.offset, target.read(write.offset, (end - write.offset).toInt()) ?: return null)
            }
            val tail = if (plan.newLength < length) {
                ByteWrite(plan.newLength, target.read(plan.newLength, (length - plan.newLength).toInt()) ?: return null)
            } else null
            val rest = restCrc(target, plan.writes, minOf(length, plan.newLength)) ?: return null
            return PatchBackup(length, plan.newLength, ranges, plan.writes, tail, rest)
        }

        /**
         * CRC of the bytes below [limit] that no write covers, within the first [HEAD_WINDOW] and
         * the last [TAIL_WINDOW] bytes. Every format keeps its tags there (ID3, FLAC metadata and Ogg
         * headers at the head; an MP4 `moov` at either end), so another tagger's edit shows up —
         * without an in-place save ever reading the audio in between.
         */
        private fun restCrc(source: RandomAccessSource, writes: List<ByteWrite>, limit: Long): Long? {
            val headEnd = minOf(limit, HEAD_WINDOW)
            val windows = listOf(0L until headEnd, maxOf(headEnd, limit - TAIL_WINDOW) until limit)
            val covered = writes.map { it.offset until it.offset + it.bytes.size }
            val crc = Crc32()
            for (window in windows) {
                var position = window.first
                while (position <= window.last) {
                    val block = covered.firstOrNull { position in it }
                    if (block != null) {
                        position = block.last + 1
                        continue
                    }
                    val nextCovered = covered.filter { it.first > position }.minOfOrNull { it.first } ?: Long.MAX_VALUE
                    val end = minOf(window.last + 1, nextCovered)
                    val count = minOf(WritePlans.COPY_CHUNK.toLong(), end - position).toInt()
                    crc.update(source.read(position, count) ?: return null)
                    position += count
                }
            }
            return crc.value
        }

        fun decode(bytes: ByteArray): PatchBackup? = try {
            check(bytes.size > 8)
            val body = bytes.copyOf(bytes.size - 8)
            check(Reader(bytes.copyOfRange(body.size, bytes.size)).long() == Crc32.of(body))
            val r = Reader(body)
            check(r.bytes(MAGIC.size).contentEquals(MAGIC))
            val originalLength = r.long()
            val newLength = r.long()
            val restCrc = r.long()
            val ranges = r.ranges()
            val writes = r.ranges()
            val tail = if (r.int() == 1) r.range() else null
            check(r.atEnd)
            PatchBackup(originalLength, newLength, ranges, writes, tail, restCrc)
        } catch (_: Exception) {
            null
        }
    }

    private class Writer {
        private val sink = ByteArraySink()
        fun bytes(value: ByteArray) = sink.write(value)
        fun int(value: Int) = sink.write(ByteArray(4) { (value ushr (24 - 8 * it)).toByte() })
        fun long(value: Long) = sink.write(ByteArray(8) { (value ushr (56 - 8 * it)).toByte() })
        fun range(range: ByteWrite) {
            long(range.offset)
            int(range.bytes.size)
            bytes(range.bytes)
        }
        fun ranges(list: List<ByteWrite>) {
            int(list.size)
            list.forEach(::range)
        }
        fun toByteArray() = sink.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        private var at = 0
        val atEnd: Boolean get() = at == data.size
        fun bytes(n: Int): ByteArray {
            check(n >= 0 && n <= data.size - at)
            return data.copyOfRange(at, at + n).also { at += n }
        }
        fun int(): Int = bytes(4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        fun long(): Long = bytes(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        fun range(): ByteWrite {
            val offset = long()
            return ByteWrite(offset, bytes(int()))
        }
        fun ranges(): List<ByteWrite> = List(int()) { range() }
    }
}
```

A rule for everything in `tags.write`: **never `runCatching` and never `catch (Throwable)`**. The test file system simulates power loss with an `Error`, and code that swallowed it would keep running after the "power" was gone and make every crash test meaningless. Catch `Exception` only.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*PatchBackupTest*'`. Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/PatchBackup.kt core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/PatchBackupTest.kt
git commit -m "feat(tags): the bytes an in-place save overwrites are kept aside, and a file changed since is recognised"
```

---

### Task 5: The durable writer and its recovery (§5.2–§5.4), proven on in-place saves

This task writes `DurableWriter` and `TagRecovery` whole, both paths included, because they share the close and roll-back code. Its tests pin the in-place path and the power-loss suite for it. Task 6 then pins the rewrite paths with their own suites.

**Files:**
- Create: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/DurableWriter.kt`
- Create: `core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/TagRecovery.kt`
- Modify: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/FaultFiles.kt`. Add:
  - `reportedFree`: a store that claims room it does not have;
  - `crashAtTrackWrite`: power loss right after the Nth write to a track;
  - `replacer()`: an atomic rename, like iOS.
- Create: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/WriteFixtures.kt`
- Create: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/CrashHarness.kt`
- Test: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/DurableWriterPatchTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:
  - `sealed interface WriteResult`, with the cases `NoChange`, `Saved(newLength: Long, rewritten: Boolean)`, `Refused(reason: TagRefusal)`, `NotEnoughSpace`, `Failed(detail: String)` and `RecoveryPending(writeId: String)`.
  - `class DurableWriter(directory: RecoveryDirectory, newWriteId: () -> String, replacer: AtomicReplacer? = null)` with `write(key: String, target: TargetFile, edits: TagEdits, targetFreeBytes: Long?): WriteResult`.
  - `class TagRecovery(directory: RecoveryDirectory, replacer: AtomicReplacer? = null)` with:
    - `enum Outcome { COMPLETED, ROLLED_BACK, RESTORED, FOREIGN, STUCK }`
    - `pending(): List<JournalRecord>`
    - `recover(record: JournalRecord, target: TargetFile): Outcome`
    - `sweep()`

- [ ] **Step 1: Extend `FaultFiles`**

Add these members to `FaultFiles` (Task 2):

```kotlin
    /** When set, the store reports this much free space whatever it holds (a store that lies). */
    var reportedFree: Long? = null

    /** 0 = never. Power is lost right after the Nth write to any track (that write done, unforced). */
    var crashAtTrackWrite: Int = 0
    private var trackWrites = 0

    /** An [AtomicReplacer] renaming a store file over a track in one durable step, as iOS does. */
    fun replacer(): AtomicReplacer = AtomicReplacer { key, stagedName ->
        tick()
        val node = store.remove(stagedName) ?: throw IllegalStateException("no $stagedName")
        syncedStore = syncedStore - stagedName
        node.durable = node.current.copyOf()
        node.pending.clear()
        tracks[key] = node
        NodeFile(node, store = false)
    }
```

- In `directory.freeBytes()`, return `reportedFree ?: (storeCapacity - storeSize())`.
- In `NodeFile.write`, after the write is applied, add: `if (!store && ++trackWrites == crashAtTrackWrite) throw PowerLoss()`.
- In `powerLoss`, also reset `crashAtTrackWrite = 0` and `trackWrites = 0`.

- [ ] **Step 2: Write `WriteFixtures.kt` and `CrashHarness.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteArraySource
import io.github.nikitasud.latentjam.library.tags.FlacFixtures
import io.github.nikitasud.latentjam.library.tags.Id3TestTags
import io.github.nikitasud.latentjam.library.tags.Mp4Fixtures
import io.github.nikitasud.latentjam.library.tags.OggFixtures
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TestFrame
import io.github.nikitasud.latentjam.library.tags.WritePlan
import io.github.nikitasud.latentjam.library.tags.WritePlans

/** One small file per format and path, with an edit that sends it down that path. */
internal object WriteFixtures {
    class Case(val name: String, val original: ByteArray, val edits: TagEdits)

    private val longLyrics = "la ".repeat(2_000)
    private fun mp3(padding: Int, audio: Int = 1024) =
        Id3TestTags.build(3, listOf(TestFrame("TIT2", Id3TestTags.latin1Body("Old"))), padding = padding) +
            Id3TestTags.mp3Payload(audio)

    // FLAC block types: 1 = PADDING, 4 = VORBIS_COMMENT.
    val inPlace: List<Case>
        get() = listOf(
            Case("mp3", mp3(padding = 512), TagEdits(title = "New")),
            Case("mp3 losing its ID3v1 trailer", mp3(padding = 512) + Id3TestTags.v1Trailer(), TagEdits(title = "New")),
            Case("flac", FlacFixtures.file(4 to FlacFixtures.comments("TITLE" to "Old"), 1 to ByteArray(1_000)), TagEdits(title = "New")),
            Case("opus", OggFixtures.opus("TITLE" to "Old"), TagEdits(title = "New")),
            Case("m4a", Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "Old")), freeAfterIlst = 2_000), TagEdits(title = "New")),
            Case("m4a whose last moov grows", Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "Old")), moovFirst = false), TagEdits(lyrics = longLyrics)),
        )

    val rewrites: List<Case>
        get() = listOf(
            Case("mp3", mp3(padding = 0, audio = 4_096), TagEdits(lyrics = longLyrics)),
            Case("flac", FlacFixtures.file(4 to FlacFixtures.comments("TITLE" to "Old")), TagEdits(lyrics = longLyrics)),
            Case("opus", OggFixtures.opus("TITLE" to "Old", padding = 0), TagEdits(lyrics = "la ".repeat(30_000))),
            Case("m4a", Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "Old"))), TagEdits(lyrics = longLyrics)),
        )

    fun plan(case: Case): WritePlan = TagCodecs.plan(ByteArraySource(case.original), case.edits)

    fun expected(case: Case): ByteArray = WritePlans.applyInMemory(case.original, plan(case))!!
}
```

If a case does not plan the path its list claims, adjust that case's padding or edit size until it does, and say so in the report. The first assertion of each suite checks the path. The m4a "moov last grows" case must plan an `InPlacePatch` with `newLength` larger than the file (spec §4.4, case B2).

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Power lost at every operation of a save — with none, one, half and all of the unforced bytes
 * surviving — and then lost again at every operation of the recovery that follows. Whatever
 * happens, the track must end as the original or the verified edit, byte for byte, and the
 * store must end empty.
 */
internal object CrashHarness {
    const val TRACK = "track"

    private fun ids(): () -> String {
        var n = 0
        return { "w${++n}" }
    }

    /** One save with power lost at operation [crashAt]; the files are left as the crash left them. */
    private fun save(case: WriteFixtures.Case, crashAt: Int, atomic: Boolean): Pair<FaultFiles, WriteResult?> {
        val files = FaultFiles()
        files.put(TRACK, case.original)
        val writer = DurableWriter(files.directory, ids(), if (atomic) files.replacer() else null)
        files.crashAt = crashAt
        val result = try {
            writer.write(TRACK, files.track(TRACK), case.edits, targetFreeBytes = null)
        } catch (_: PowerLoss) {
            null
        }
        return files to result
    }

    fun recoverAll(files: FaultFiles, atomic: Boolean): List<TagRecovery.Outcome> {
        val recovery = TagRecovery(files.directory, if (atomic) files.replacer() else null)
        val outcomes = recovery.pending().map { recovery.recover(it, files.track(it.target)) }
        recovery.sweep()
        return outcomes
    }

    private fun samples(pending: Long): Set<Long> =
        if (pending <= 64) (0..pending).toSet() else setOf(0L, 1L, pending / 2, pending - 1, pending)

    fun everywhere(case: WriteFixtures.Case, atomic: Boolean) {
        val edited = WriteFixtures.expected(case)
        var crashAt = 1
        while (true) {
            val (probe, finished) = save(case, crashAt, atomic)
            if (finished != null) {
                assertTrue(finished is WriteResult.Saved, "${case.name}: $finished")
                assertWhole(probe, case.original, edited, "${case.name}, no crash")
                return
            }
            for (keep in samples(probe.pendingBytes())) {
                var recoveryCrash = 1
                while (true) {
                    val (files, _) = save(case, crashAt, atomic)
                    files.powerLoss(keep)
                    files.crashAt = recoveryCrash
                    val crashed = try {
                        recoverAll(files, atomic)
                        false
                    } catch (_: PowerLoss) {
                        true
                    }
                    if (crashed) {
                        files.powerLoss(0)
                        val outcomes = recoverAll(files, atomic)
                        if (TagRecovery.Outcome.STUCK in outcomes || TagRecovery.Outcome.FOREIGN in outcomes) {
                            fail("${case.name}: crash $crashAt, keep $keep, recovery crash $recoveryCrash → $outcomes")
                        }
                    }
                    assertWhole(files, case.original, edited, "${case.name}: crash $crashAt, keep $keep, recovery crash $recoveryCrash")
                    if (!crashed) break
                    recoveryCrash++
                }
            }
            crashAt++
        }
    }

    fun assertWhole(files: FaultFiles, original: ByteArray, edited: ByteArray, where: String) {
        val now = files.trackBytes(TRACK)
        assertTrue(now.contentEquals(original) || now.contentEquals(edited), "$where: the track is neither original nor edited")
        assertEquals(emptySet(), files.storeNames(), "$where: the store kept files")
    }
}
```

- [ ] **Step 3: Write the failing test `DurableWriterPatchTest.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.library.tags.WritePlan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DurableWriterPatchTest {
    private val track = CrashHarness.TRACK

    private fun setUp(case: WriteFixtures.Case, files: FaultFiles = FaultFiles()) =
        files.apply { put(track, case.original) }

    private fun writer(files: FaultFiles) = DurableWriter(files.directory, { "w1" })

    @Test
    fun everyInPlaceCaseSavesTheEditAndLeavesNothingBehind() {
        for (case in WriteFixtures.inPlace) {
            val plan = WriteFixtures.plan(case)
            assertIs<WritePlan.InPlacePatch>(plan, case.name)
            val files = setUp(case)
            val result = writer(files).write(track, files.track(track), case.edits, targetFreeBytes = null)
            assertEquals(WriteResult.Saved(plan.newLength, rewritten = false), result, case.name)
            assertContentEquals(WriteFixtures.expected(case), files.trackBytes(track), case.name)
            assertEquals(emptySet(), files.storeNames(), case.name)
        }
    }

    @Test
    fun aSaveThatChangesNothingWritesNothing() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case)
        assertEquals(WriteResult.NoChange, writer(files).write(track, files.track(track), TagEdits(title = "Old"), null))
        assertEquals(0, files.operations)
    }

    @Test
    fun anUnsupportedFileIsLeftAsItIs() {
        val files = FaultFiles().apply { put(track, ByteArray(100) { 7 }) }
        assertEquals(
            WriteResult.Refused(TagRefusal.UNSUPPORTED_FORMAT),
            writer(files).write(track, files.track(track), TagEdits(title = "x"), null),
        )
        assertEquals(0, files.operations)
    }

    @Test
    fun noRoomInTheStoreTouchesNothing() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case, FaultFiles(storeCapacity = 100))
        assertEquals(WriteResult.NotEnoughSpace, writer(files).write(track, files.track(track), case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aStoreThatFillsDuringTheSaveTouchesNothing() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case, FaultFiles(storeCapacity = 10).apply { reportedFree = Long.MAX_VALUE })
        assertEquals(WriteResult.NotEnoughSpace, writer(files).write(track, files.track(track), case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun noRoomOnTheVolumeForAGrowingPatchTouchesNothing() {
        val case = WriteFixtures.inPlace.single { it.name == "m4a whose last moov grows" }
        val plan = assertIs<WritePlan.InPlacePatch>(WriteFixtures.plan(case))
        assertTrue(plan.newLength > case.original.size)
        val files = setUp(case)
        assertEquals(WriteResult.NotEnoughSpace, writer(files).write(track, files.track(track), case.edits, targetFreeBytes = 0))
        assertContentEquals(case.original, files.trackBytes(track))
    }

    @Test
    fun aWriteThatDoesNotReadBackIsRolledBack() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case)
        val flipping = FlippingFile(files.track(track))
        assertIs<WriteResult.Failed>(writer(files).write(track, flipping, case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aFileWithAnOpenRecordIsNotEditedAgainUntilRecovered() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case).apply { crashAtTrackWrite = 1 }
        runCatching { writer(files).write(track, files.track(track), case.edits, null) }
        files.powerLoss(keep = Long.MAX_VALUE)
        assertIs<WriteResult.RecoveryPending>(writer(files).write(track, files.track(track), case.edits, null))
    }

    @Test
    fun recoveryRollsBackAnInterruptedPatch() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case).apply { crashAtTrackWrite = 1 }
        runCatching { writer(files).write(track, files.track(track), case.edits, null) }
        files.powerLoss(keep = Long.MAX_VALUE)
        assertEquals(listOf(TagRecovery.Outcome.ROLLED_BACK), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun recoveryLeavesAFileSomeoneElseChanged() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case).apply { crashAtTrackWrite = 1 }
        runCatching { writer(files).write(track, files.track(track), case.edits, null) }
        files.powerLoss(keep = Long.MAX_VALUE)
        // Another tagger rewrites the last byte of the file (audio, outside every range we wrote).
        val last = case.original.size.toLong() - 1
        files.track(track).apply { write(last, byteArrayOf((case.original.last() + 1).toByte())); force() }
        val changed = files.trackBytes(track)
        assertEquals(listOf(TagRecovery.Outcome.FOREIGN), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(changed, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun powerLossAnywhereLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.inPlace) CrashHarness.everywhere(case, atomic = false)
    }

    /** Flips one bit of the first byte written: a write that does not read back. */
    private class FlippingFile(private val delegate: TargetFile) : TargetFile by delegate {
        private var flipped = false

        override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
            val copy = bytes.copyOfRange(from, from + count)
            if (!flipped && count > 0) {
                copy[0] = (copy[0].toInt() xor 1).toByte()
                flipped = true
            }
            delegate.write(offset, copy, 0, copy.size)
        }
    }
}
```

The "mp3 losing its ID3v1 trailer" case shrinks the file, which exercises the saved tail. "Someone else changed it" relies on the whole mp3 fixture fitting inside the 256 KiB head window. It does: about 2 KB.

- [ ] **Step 4: Run it and watch it fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*DurableWriterPatchTest*'`. Expected: compilation fails, `DurableWriter` unresolved.

- [ ] **Step 5: Implement `DurableWriter.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.StreamRefusedException
import io.github.nikitasud.latentjam.library.tags.TagCodec
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.library.tags.TagVerification
import io.github.nikitasud.latentjam.library.tags.WritePlan
import io.github.nikitasud.latentjam.library.tags.WritePlans

/** What became of one file's save. Only [RecoveryPending] can leave the file other than whole. */
public sealed interface WriteResult {
    /** The edit asked for what the file already says. Nothing was opened for writing. */
    public data object NoChange : WriteResult

    /** The file holds the verified edit. [newLength] lets a later scan recognise it (SMART carry-over). */
    public data class Saved(public val newLength: Long, public val rewritten: Boolean) : WriteResult

    public data class Refused(public val reason: TagRefusal) : WriteResult

    /** The app's store or the file's volume lacks room. Nothing was touched. */
    public data object NotEnoughSpace : WriteResult

    /** The file is exactly as it was: the save stopped before it, or was rolled back and checked. */
    public data class Failed(public val detail: String) : WriteResult

    /** The file may be partly written. Its journal record is open, and [TagRecovery] finishes it. */
    public data class RecoveryPending(public val writeId: String) : WriteResult
}

/**
 * Saves tag edits into one file so that at every instant it is either its original or the
 * verified edit (spec §5).
 *
 * - **In place** (§5.2): the bytes about to be overwritten are saved to the store first. The patch
 *   is written, forced to storage, and then read back and verified. Any failure puts the saved
 *   bytes back.
 * - **Rewrite** (§5.3): the new file is staged in the store and verified, the original is backed
 *   up and verified, and only then is the track overwritten. The journal records how far that got.
 *   With an [AtomicReplacer] (iOS), a rename replaces the file instead, and no backup is needed.
 *
 * Each state is journaled and forced before the step it announces, so [TagRecovery] can always
 * tell what is on storage. One instance may serve several saves at once, provided each targets a
 * different file.
 */
public class DurableWriter(
    private val directory: RecoveryDirectory,
    private val newWriteId: () -> String,
    private val replacer: AtomicReplacer? = null,
) {
    private val journal = Journal(directory)
    private val recovery = TagRecovery(directory, replacer)

    /**
     * Saves [edits] into [target], the file the platform knows as [key]. [targetFreeBytes] is the
     * free space on the file's volume, or null when the platform cannot tell.
     */
    public fun write(key: String, target: TargetFile, edits: TagEdits, targetFreeBytes: Long?): WriteResult {
        val codec: TagCodec
        val baseline: TagVerification.Baseline
        val plan: WritePlan
        try {
            journal.open().firstOrNull { it.target == key }?.let { return WriteResult.RecoveryPending(it.writeId) }
            codec = TagCodecs.forSource(target) ?: return WriteResult.Refused(TagRefusal.UNSUPPORTED_FORMAT)
            baseline = TagVerification.baseline(codec, target)
            baseline.snapshot.refusal?.let { return WriteResult.Refused(it) }
            plan = TagCodecs.planSafely(codec, target, edits)
        } catch (e: Exception) {
            return WriteResult.Failed(e.message ?: "the file could not be read")
        }
        return when (plan) {
            WritePlan.NoChange -> WriteResult.NoChange
            is WritePlan.Refused -> WriteResult.Refused(plan.reason)
            is WritePlan.InPlacePatch -> patch(key, target, codec, baseline, edits, plan, targetFreeBytes)
            is WritePlan.StreamingRewrite -> rewrite(key, target, codec, baseline, edits, plan, targetFreeBytes)
        }
    }

    private fun patch(
        key: String,
        target: TargetFile,
        codec: TagCodec,
        baseline: TagVerification.Baseline,
        edits: TagEdits,
        plan: WritePlan.InPlacePatch,
        targetFreeBytes: Long?,
    ): WriteResult {
        val originalLength = target.length
        val backup = PatchBackup.capture(target, plan) ?: return WriteResult.Failed("the bytes to overwrite could not be read")
        val saved = backup.encode()
        if (directory.freeBytes() < saved.size + STORE_MARGIN) return WriteResult.NotEnoughSpace
        if (lacksRoom(plan.newLength, originalLength, targetFreeBytes)) return WriteResult.NotEnoughSpace

        val record = JournalRecord(newWriteId(), key, JournalState.PATCH_PREPARED, originalLength, plan.newLength)
        try {
            directory.create(record.patchName).use {
                it.write(0, saved)
                it.force()
            }
            directory.sync()
            journal.append(record)
        } catch (_: StorageFullException) {
            discard(record)
            return WriteResult.NotEnoughSpace
        } catch (e: Exception) {
            discard(record)
            return WriteResult.Failed(e.message ?: "the save could not be prepared")
        }

        val problem = try {
            for (write in plan.writes) target.write(write.offset, write.bytes)
            target.setLength(plan.newLength)
            // Before any read-back: until the data is on storage a read can come from the page cache
            // and prove nothing (§5.2 step 4).
            target.force()
            writtenProblem(target, plan) ?: TagVerification.verifyTags(codec, baseline, target, edits).firstOrNull()?.toString()
        } catch (e: Exception) {
            e.message ?: "the write failed"
        }

        if (problem == null) {
            return try {
                recovery.close(record, JournalState.DONE, TagRecovery.Outcome.COMPLETED)
                WriteResult.Saved(plan.newLength, rewritten = false)
            } catch (_: Exception) {
                WriteResult.RecoveryPending(record.writeId)
            }
        }
        return when (recovery.rollBackPatch(record, target, backup)) {
            TagRecovery.Outcome.ROLLED_BACK -> WriteResult.Failed(problem)
            TagRecovery.Outcome.FOREIGN -> WriteResult.Failed("the file changed while it was being saved")
            else -> WriteResult.RecoveryPending(record.writeId)
        }
    }

    private fun rewrite(
        key: String,
        target: TargetFile,
        codec: TagCodec,
        baseline: TagVerification.Baseline,
        edits: TagEdits,
        plan: WritePlan.StreamingRewrite,
        targetFreeBytes: Long?,
    ): WriteResult {
        val originalLength = target.length
        val newLength = plan.newLength
        val atomic = replacer != null
        val storeNeeded = newLength + (if (atomic) 0 else originalLength) + REWRITE_MARGIN
        if (directory.freeBytes() < storeNeeded) return WriteResult.NotEnoughSpace
        if (!atomic && lacksRoom(newLength, originalLength, targetFreeBytes)) return WriteResult.NotEnoughSpace

        val draft = JournalRecord(newWriteId(), key, JournalState.REPLACE_PREPARED, originalLength, newLength, atomic = atomic)
        val prepared = try {
            stage(draft, target, codec, baseline, edits, plan)
        } catch (e: StreamRefusedException) {
            discard(draft)
            return WriteResult.Refused(e.reason)
        } catch (_: StorageFullException) {
            discard(draft)
            return WriteResult.NotEnoughSpace
        } catch (e: Exception) {
            discard(draft)
            return WriteResult.Failed(e.message ?: "the new file could not be prepared")
        }

        val outcome = if (atomic) {
            recovery.finishAtomic(prepared, target)
        } else {
            val replacing = prepared.copy(state = JournalState.REPLACING)
            try {
                // Journaled before the first byte of the track is overwritten (§5.3 step 5).
                journal.append(replacing)
            } catch (_: Exception) {
                return WriteResult.RecoveryPending(prepared.writeId)
            }
            recovery.finishCopyOver(replacing, target)
        }
        return when (outcome) {
            TagRecovery.Outcome.COMPLETED -> WriteResult.Saved(newLength, rewritten = true)
            TagRecovery.Outcome.ROLLED_BACK, TagRecovery.Outcome.RESTORED ->
                WriteResult.Failed("the new file did not verify; the original was kept")
            TagRecovery.Outcome.FOREIGN -> WriteResult.Failed("the file changed while it was being saved")
            TagRecovery.Outcome.STUCK -> WriteResult.RecoveryPending(prepared.writeId)
        }
    }

    /**
     * §5.3 steps 2–4: build the new file in the store and verify it (tags, audio, a full read-back),
     * back the original up and verify that, then journal `REPLACE_PREPARED`. The track is only read.
     */
    private fun stage(
        draft: JournalRecord,
        target: TargetFile,
        codec: TagCodec,
        baseline: TagVerification.Baseline,
        edits: TagEdits,
        plan: WritePlan.StreamingRewrite,
    ): JournalRecord {
        val audio = codec.audioDigest(target) ?: throw IllegalStateException("the audio could not be read")
        val stagedCrc = directory.create(draft.stagedName).use { staged ->
            val sink = AppendingSink(staged)
            WritePlans.stream(target, plan, sink)
            staged.force()
            check(staged.length == plan.newLength) { "staged ${staged.length} bytes, planned ${plan.newLength}" }
            TagVerification.verifyTags(codec, baseline, staged, edits).firstOrNull()?.let { throw IllegalStateException(it.toString()) }
            check(TagVerification.audioMatches(audio, codec.audioDigest(staged))) { "the staged audio differs" }
            check(FileOps.crc(staged) == sink.crcValue) { "the staged file does not read back" }
            sink.crcValue
        }
        val originalCrc = if (draft.atomic) {
            FileOps.crc(target) ?: throw IllegalStateException("the original could not be read")
        } else {
            directory.create(draft.backupName).use { backup ->
                val crc = FileOps.copyOver(target, backup)
                backup.force()
                check(FileOps.crc(backup) == crc) { "the backup does not read back" }
                crc
            }
        }
        directory.sync()
        val prepared = draft.copy(stagedCrc = stagedCrc, originalCrc = originalCrc)
        journal.append(prepared)
        return prepared
    }

    /** Null when the file has exactly the planned length and every write reads back. */
    private fun writtenProblem(target: TargetFile, plan: WritePlan.InPlacePatch): String? {
        if (target.length != plan.newLength) return "length ${target.length}, planned ${plan.newLength}"
        for (write in plan.writes) {
            if (target.read(write.offset, write.bytes.size)?.contentEquals(write.bytes) != true) {
                return "the bytes at ${write.offset} do not read back"
            }
        }
        return null
    }

    private fun lacksRoom(newLength: Long, originalLength: Long, targetFreeBytes: Long?): Boolean =
        newLength > originalLength && targetFreeBytes != null && targetFreeBytes < newLength - originalLength + TARGET_MARGIN

    /** Removes a save that never touched its track. Leftovers are swept by the next recovery. */
    private fun discard(record: JournalRecord) {
        try {
            val names = directory.names()
            for (name in listOf(record.patchName, record.stagedName, record.backupName, record.journalName)) {
                if (name in names) directory.delete(name)
            }
            directory.sync()
        } catch (_: Exception) {
            // TagRecovery.sweep() deletes whatever no open record needs.
        }
    }

    private companion object {
        const val STORE_MARGIN = 64L * 1024
        const val TARGET_MARGIN = 64L * 1024
        const val REWRITE_MARGIN = 16L * 1024 * 1024
    }
}
```

A patch that reaches `discard` never wrote to its track. The journal file is deleted as well, so a half-created record cannot later ask for a roll-back of a patch that never happened.

- [ ] **Step 6: Implement `TagRecovery.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.RandomAccessSource

/**
 * Finishes saves a crash interrupted (spec §5.4).
 *
 * - An interrupted patch is rolled back from its saved bytes.
 * - An interrupted rewrite is finished forward from its verified staged copy, or put back from its
 *   verified backup.
 *
 * Every action amounts to "make the file equal these checksummed bytes", so a crash during
 * recovery is recovered by running recovery again.
 *
 * A file that has since been changed by someone else is left exactly as found ([Outcome.FOREIGN]).
 * Recovery never writes stale bytes over another app's edit.
 */
public class TagRecovery(
    private val directory: RecoveryDirectory,
    private val replacer: AtomicReplacer? = null,
) {
    public enum class Outcome {
        /** The edit is in the file, verified. */
        COMPLETED,

        /** The file is its original again; the edit was not saved. */
        ROLLED_BACK,

        /** The original was copied back from its backup; the edit was not saved. */
        RESTORED,

        /** Someone else changed the file after the crash. It was left exactly as found. */
        FOREIGN,

        /** Neither finished nor undone — the store or the file could not be read or written. Kept for another try. */
        STUCK,
    }

    private val journal = Journal(directory)

    public fun pending(): List<JournalRecord> = journal.open()

    /** Finishes [record] on [target], which must be the file the record names, opened for writing. */
    public fun recover(record: JournalRecord, target: TargetFile): Outcome = when (record.state) {
        JournalState.PATCH_PREPARED -> rollBackPatch(record, target, known = null)
        JournalState.REPLACE_PREPARED, JournalState.REPLACING ->
            if (record.atomic) finishAtomic(record, target) else finishCopyOver(record, target)
        else -> Outcome.COMPLETED
    }

    /** Deletes every store file that no open save needs. Safe at any time. */
    public fun sweep() {
        try {
            val open = journal.open().mapTo(HashSet()) { it.writeId }
            val stale = directory.names().filter { it.substringBefore('.') !in open }
            if (stale.isEmpty()) return
            stale.forEach(directory::delete)
            directory.sync()
        } catch (_: Exception) {
            // The next sweep repeats it.
        }
    }

    internal fun rollBackPatch(record: JournalRecord, target: TargetFile, known: PatchBackup?): Outcome = try {
        val backup = known
            ?: directory.open(record.patchName)?.use { file -> FileOps.readAll(file)?.let(PatchBackup::decode) }
        when {
            backup == null -> Outcome.STUCK
            backup.matches(target) -> close(record, JournalState.ROLLED_BACK, Outcome.ROLLED_BACK)
            // Only a recovery after a crash asks whose change this is. The writer's own roll-back
            // ([known] given) undoes bytes it has just written, however storage mangled them.
            known == null && !backup.explains(target) -> close(record, JournalState.ABANDONED, Outcome.FOREIGN)
            else -> {
                backup.restore(target)
                if (backup.matches(target)) close(record, JournalState.ROLLED_BACK, Outcome.ROLLED_BACK) else Outcome.STUCK
            }
        }
    } catch (_: Exception) {
        Outcome.STUCK
    }

    /** §5.3 steps 5–7 and their recovery: finish forward from the staged copy, else put the backup back. */
    internal fun finishCopyOver(record: JournalRecord, target: TargetFile): Outcome = try {
        when {
            matches(target, record.finalLength, record.stagedCrc) -> close(record, JournalState.DONE, Outcome.COMPLETED)
            // Not one byte of ours was written before REPLACING; a different file is someone else's.
            record.state == JournalState.REPLACE_PREPARED && !matches(target, record.originalLength, record.originalCrc) ->
                close(record, JournalState.ABANDONED, Outcome.FOREIGN)
            copyVerified(record.stagedName, record.finalLength, record.stagedCrc, record, target) ->
                close(record, JournalState.DONE, Outcome.COMPLETED)
            matches(target, record.originalLength, record.originalCrc) ->
                close(record, JournalState.ROLLED_BACK, Outcome.ROLLED_BACK)
            copyVerified(record.backupName, record.originalLength, record.originalCrc, record, target) ->
                close(record, JournalState.RESTORED, Outcome.RESTORED)
            else -> Outcome.STUCK
        }
    } catch (_: Exception) {
        Outcome.STUCK
    }

    /** An atomic replace (iOS): the rename either happened or it did not. */
    internal fun finishAtomic(record: JournalRecord, target: TargetFile): Outcome = try {
        when {
            matches(target, record.finalLength, record.stagedCrc) -> close(record, JournalState.DONE, Outcome.COMPLETED)
            !matches(target, record.originalLength, record.originalCrc) -> close(record, JournalState.ABANDONED, Outcome.FOREIGN)
            replacer != null && storeFileMatches(record.stagedName, record.finalLength, record.stagedCrc) ->
                replacer.replace(record.target, record.stagedName).use { replaced ->
                    if (matches(replaced, record.finalLength, record.stagedCrc)) {
                        close(record, JournalState.DONE, Outcome.COMPLETED)
                    } else {
                        Outcome.STUCK
                    }
                }
            else -> close(record, JournalState.ROLLED_BACK, Outcome.ROLLED_BACK)
        }
    } catch (_: Exception) {
        Outcome.STUCK
    }

    /** Journals [state], then deletes the save's files and its journal; a crash in between is swept later. */
    internal fun close(record: JournalRecord, state: JournalState, outcome: Outcome): Outcome {
        journal.append(record.copy(state = state))
        try {
            val names = directory.names()
            for (name in listOf(record.patchName, record.stagedName, record.backupName)) {
                if (name in names) directory.delete(name)
            }
            directory.sync()
            journal.forget(record.writeId)
        } catch (_: Exception) {
            // Finished records and their files are removed by sweep().
        }
        return outcome
    }

    private fun matches(file: RandomAccessSource, length: Long, crc: Long): Boolean =
        crc >= 0 && file.length == length && FileOps.crc(file) == crc

    private fun storeFileMatches(name: String, length: Long, crc: Long): Boolean =
        directory.open(name)?.use { matches(it, length, crc) } ?: false

    /** Copies store file [name] over [target] if it still is what the record says, and checks the result. */
    private fun copyVerified(name: String, length: Long, crc: Long, record: JournalRecord, target: TargetFile): Boolean = try {
        directory.open(name)?.use { source ->
            if (!matches(source, length, crc)) return@use false
            if (record.state != JournalState.REPLACING) journal.append(record.copy(state = JournalState.REPLACING))
            FileOps.copyOver(source, target)
            target.force()
            matches(target, length, crc)
        } ?: false
    } catch (_: Exception) {
        false
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*DurableWriterPatchTest*'`. Expected: PASS, 11 tests.

The crash suite runs a few thousand scenarios. If it takes more than about 60 s on the host, reduce `samples()` for pending counts above 64 to `{0, pending}` and say so in the report. Do not drop the recovery-crash loop.

Whenever a scenario fails, fix the protocol, not the test. A failing message names the crash point, the surviving bytes and the recovery crash, and that is enough to replay it.

- [ ] **Step 8: Run the whole module and commit**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --rerun` and `./gradlew :core:library:compileTestKotlinIosSimulatorArm64 --offline -q`. Expected: all green.

```bash
git add core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/ core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/
git commit -m "feat(tags): a save survives a power cut at any instant: the file is the original or the edit, never a mix"
```

---

### Task 6: Rewrites under fire — copy-over and atomic replace

This task pins the rewrite path. If a test exposes a defect in Task 5's code, fix the code in this task and report it.

**Files:**
- Test: `core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/DurableWriterRewriteTest.kt`
- Modify, only when a test exposes a defect: `DurableWriter.kt`, `TagRecovery.kt`

**Interfaces:**
- Consumes: Task 5.

- [ ] **Step 1: Write the tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.WritePlan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal class DurableWriterRewriteTest {
    private val track = CrashHarness.TRACK

    private fun setUp(case: WriteFixtures.Case, files: FaultFiles = FaultFiles()) =
        files.apply { put(track, case.original) }

    private fun writer(files: FaultFiles, atomic: Boolean) =
        DurableWriter(files.directory, { "w1" }, if (atomic) files.replacer() else null)

    @Test
    fun everyRewriteSavesTheEditBothWays() {
        for (atomic in listOf(false, true)) for (case in WriteFixtures.rewrites) {
            val plan = WriteFixtures.plan(case)
            assertIs<WritePlan.StreamingRewrite>(plan, case.name)
            val files = setUp(case)
            val result = writer(files, atomic).write(track, files.track(track), case.edits, targetFreeBytes = null)
            assertEquals(WriteResult.Saved(plan.newLength, rewritten = true), result, "${case.name}, atomic=$atomic")
            assertContentEquals(WriteFixtures.expected(case), files.trackBytes(track), case.name)
            assertEquals(emptySet(), files.storeNames(), case.name)
        }
    }

    @Test
    fun aCopyOverNeedsRoomForTheNewFileAndABackupButAnAtomicReplaceOnlyForTheNewFile() {
        val case = WriteFixtures.rewrites.first()
        val plan = assertIs<WritePlan.StreamingRewrite>(WriteFixtures.plan(case))
        val room = plan.newLength + 16L * 1024 * 1024 + case.original.size / 2
        val copyOver = setUp(case, FaultFiles(storeCapacity = room))
        assertEquals(WriteResult.NotEnoughSpace, writer(copyOver, atomic = false).write(track, copyOver.track(track), case.edits, null))
        assertContentEquals(case.original, copyOver.trackBytes(track))
        val atomic = setUp(case, FaultFiles(storeCapacity = room))
        assertIs<WriteResult.Saved>(writer(atomic, atomic = true).write(track, atomic.track(track), case.edits, null))
    }

    @Test
    fun aStoreThatFillsWhileStagingTouchesNothing() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case, FaultFiles(storeCapacity = 100).apply { reportedFree = Long.MAX_VALUE })
        assertEquals(WriteResult.NotEnoughSpace, writer(files, atomic = false).write(track, files.track(track), case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aVolumeThatFillsDuringTheCopyOverPutsTheOriginalBack() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case)
        val full = VolumeFullAt(files.track(track), limit = case.original.size.toLong())
        assertIs<WriteResult.Failed>(writer(files, atomic = false).write(track, full, case.edits, targetFreeBytes = null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aDamagedStagedCopyIsNotFinishedButTheBackupIsPutBack() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case).apply { crashAtTrackWrite = 1 }
        runCatching { writer(files, atomic = false).write(track, files.track(track), case.edits, null) }
        files.powerLoss(keep = 100) // the first 100 bytes of the copy landed: neither original nor edit
        files.directory.open("w1.staged")!!.apply { write(0, byteArrayOf(0x55)); force() }
        assertEquals(listOf(TagRecovery.Outcome.RESTORED), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aFileChangedByAnotherAppBeforeTheReplaceIsLeftAlone() {
        for (atomic in listOf(false, true)) {
            val case = WriteFixtures.rewrites.first()
            val files = setUp(case)
            // Stop right after REPLACE_PREPARED is journaled: the track is still untouched.
            files.crashAt = operationsUntilPrepared(case, atomic)
            runCatching { writer(files, atomic).write(track, files.track(track), case.edits, null) }
            files.powerLoss()
            // Another app changes the file's last byte.
            files.track(track).apply { write(case.original.size - 1L, byteArrayOf((case.original.last() + 1).toByte())); force() }
            val changed = files.trackBytes(track)
            assertEquals(listOf(TagRecovery.Outcome.FOREIGN), CrashHarness.recoverAll(files, atomic), "atomic=$atomic")
            assertContentEquals(changed, files.trackBytes(track))
        }
    }

    @Test
    fun powerLossAnywhereDuringACopyOverLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.rewrites) CrashHarness.everywhere(case, atomic = false)
    }

    @Test
    fun powerLossAnywhereDuringAnAtomicReplaceLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.rewrites) CrashHarness.everywhere(case, atomic = true)
    }

    /** The operation count at which REPLACE_PREPARED is on storage, found by replaying the save. */
    private fun operationsUntilPrepared(case: WriteFixtures.Case, atomic: Boolean): Int {
        var n = 1
        while (true) {
            val files = setUp(case).apply { crashAt = n }
            runCatching { writer(files, atomic).write(track, files.track(track), case.edits, null) }
            files.powerLoss()
            if (Journal(files.directory).open().any { it.state == JournalState.REPLACE_PREPARED }) return n
            n++
        }
    }

    /** A track whose volume has no room past [limit] bytes. */
    private class VolumeFullAt(private val delegate: TargetFile, private val limit: Long) : TargetFile by delegate {
        override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
            if (offset + count > limit) throw StorageFullException("volume full")
            delegate.write(offset, bytes, from, count)
        }
    }
}
```

`operationsUntilPrepared` returns the first crash point at which the recovered journal shows `REPLACE_PREPARED`. Crashing there loses power right after that record is durable, before anything touches the track: in the copy-over path before `REPLACING` is appended, and in the atomic path before the rename.

- [ ] **Step 2: Run the tests**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*DurableWriterRewriteTest*'`. Expected: PASS, 7 tests. Every failure is a protocol defect: fix it in `DurableWriter` or `TagRecovery`, keep the test, and describe the defect in the report.

- [ ] **Step 3: Full module run and commit**

Run the module suite and the iOS test compile (the Task 5 commands).

```bash
git add core/library/src/commonTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/ core/library/src/commonMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/
git commit -m "test(tags): a rewrite cut off at any instant, or out of room mid-copy, ends as the original or the edit"
```

---

### Task 7: Real files on the JVM and Android, and the real library through the durable writer

**Files:**
- Create: `core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/ChannelTargetFile.kt`
- Create: `core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/FileRecoveryDirectory.kt`
- Test: `core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/JvmWriteFilesTest.kt`
- Test: `core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/DurableWriteRealFileTest.kt`

**Interfaces:**
- Produces:
  - `class ChannelTargetFile(reader: FileChannel, writer: FileChannel, onClose: () -> Unit) : TargetFile`, with `companion fun open(file: java.io.File): ChannelTargetFile`.
  - `class FileRecoveryDirectory(root: java.io.File, syncDirectory: (java.io.File) -> Unit) : RecoveryDirectory`.

- [ ] **Step 1: Write the failing test `JvmWriteFilesTest.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class JvmWriteFilesTest {
    private val root: File = Files.createTempDirectory("lj-write").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aChannelFileReadsWritesGrowsAndShrinks() {
        val file = File(root, "t").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        ChannelTargetFile.open(file).use {
            it.write(2, byteArrayOf(9, 9, 9))
            assertEquals(5, it.length)
            it.setLength(3)
            it.setLength(6)
            it.force()
            assertContentEquals(byteArrayOf(1, 2, 9, 0, 0, 0), it.read(0, 6))
            assertNull(it.read(4, 3))
        }
    }

    @Test
    fun theDirectoryCreatesListsAndDeletes() {
        var syncs = 0
        val directory = FileRecoveryDirectory(File(root, "store")) { syncs++ }
        directory.create("a.patch").use { it.write(0, byteArrayOf(1)) }
        directory.create("a.patch").use { assertEquals(0, it.length) }
        assertEquals(listOf("a.patch"), directory.names())
        directory.sync()
        directory.delete("a.patch")
        assertTrue(directory.names().isEmpty())
        assertNull(directory.open("a.patch"))
        assertEquals(1, syncs)
        assertTrue(directory.freeBytes() > 0)
    }

    @Test
    fun aRealFileIsSavedDurablyInPlaceAndByRewrite() {
        val store = FileRecoveryDirectory(File(root, "store")) {}
        var n = 0
        val writer = DurableWriter(store, { "w${++n}" })
        for (case in WriteFixtures.inPlace + WriteFixtures.rewrites) {
            val file = File(root, "track").apply { writeBytes(case.original) }
            val result = ChannelTargetFile.open(file).use { writer.write(file.path, it, case.edits, root.usableSpace) }
            assertTrue(result is WriteResult.Saved, "${case.name}: $result")
            assertContentEquals(WriteFixtures.expected(case), file.readBytes(), case.name)
            assertTrue(store.names().isEmpty(), case.name)
        }
    }
}
```

(`WriteFixtures` lives in commonTest, which the Android host tests can see.)

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*JvmWriteFilesTest*'`. Expected: compilation fails.

- [ ] **Step 3: Implement the adapters**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * [TargetFile] over positional channel reads and writes. Android hands a track over as a
 * `ParcelFileDescriptor`, whose descriptor yields a read channel and a write channel; a file in the
 * store is opened directly.
 */
public class ChannelTargetFile(
    private val reader: FileChannel,
    private val writer: FileChannel,
    private val onClose: () -> Unit,
) : TargetFile {
    override val length: Long get() = writer.size()

    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || offset > length || count > length - offset) return null
        val buffer = ByteBuffer.allocate(count)
        var at = offset
        while (buffer.hasRemaining()) {
            val read = reader.read(buffer, at)
            if (read < 0) return null
            at += read
        }
        return buffer.array()
    }

    override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
        val buffer = ByteBuffer.wrap(bytes, from, count)
        var at = offset
        try {
            while (buffer.hasRemaining()) at += writer.write(buffer, at)
        } catch (e: IOException) {
            throw if (e.isNoSpace()) StorageFullException(e.message ?: "no space") else e
        }
    }

    override fun setLength(length: Long) {
        val size = writer.size()
        when {
            length < size -> writer.truncate(length)
            length > size -> write(length - 1, byteArrayOf(0))
        }
    }

    override fun force() {
        writer.force(true)
    }

    override fun close() {
        onClose()
    }

    public companion object {
        public fun open(file: File): ChannelTargetFile {
            val access = RandomAccessFile(file, "rw")
            return ChannelTargetFile(access.channel, access.channel) { access.close() }
        }

        private fun IOException.isNoSpace(): Boolean =
            message?.let { "ENOSPC" in it || "No space left" in it } == true
    }
}
```

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import java.io.File
import java.io.RandomAccessFile

/**
 * The recovery store as a plain directory. [syncDirectory] makes entries durable: Android passes an
 * `Os.open` + `Os.fsync` of the directory (java.nio.file is not available below API 26); host tests
 * pass a no-op.
 */
public class FileRecoveryDirectory(
    private val root: File,
    private val syncDirectory: (File) -> Unit,
) : RecoveryDirectory {

    private fun file(name: String): File {
        require(NAME.matches(name)) { "unsafe store name $name" }
        root.mkdirs()
        return File(root, name)
    }

    override fun create(name: String): TargetFile {
        val file = file(name)
        RandomAccessFile(file, "rw").use { it.setLength(0) }
        return ChannelTargetFile.open(file)
    }

    override fun open(name: String): TargetFile? = file(name).takeIf { it.isFile }?.let { ChannelTargetFile.open(it) }

    override fun delete(name: String) {
        file(name).delete()
    }

    override fun names(): List<String> = root.list()?.sorted().orEmpty()

    override fun sync() {
        root.mkdirs()
        syncDirectory(root)
    }

    override fun freeBytes(): Long {
        root.mkdirs()
        return root.usableSpace
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9._-]+")
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:library:testAndroidHostTest --offline -q --tests '*JvmWriteFilesTest*'`. Expected: PASS, 3 tests.

- [ ] **Step 5: Real-library run through the durable writer**

Create `DurableWriteRealFileTest.kt`, modelled on the existing `TagCodecRealFileTest` (read it first). It self-skips unless `TAG_REAL_FILES` (input directory, read-only) and `TAG_REAL_FILES_OUT` (scratch directory) are set.

For every audio file in the input directory:
1. Copy it to `OUT/<index>.<ext>`.
2. Open a `ChannelTargetFile` on the copy and a `FileRecoveryDirectory(OUT/store)` with a no-op sync.
3. Save the plan-1 "full edit" scenario from `TagCodecRealFileTest` through `DurableWriter` with `targetFreeBytes = OUT.usableSpace`.
4. Classify the result:
   - `Saved`: `TagVerification.verify(codec, ByteArraySource(original), ChannelTargetFile(copy), edits)` must be empty, and the store must be empty;
   - `Refused`: record the reason;
   - `NoChange`: the copy is byte-identical;
   - anything else is a failure.
5. Record the wall time of each `write` call and the path taken (`rewritten`).

At the end, print:
- the counts per outcome and per refusal reason;
- failures, listed by file name;
- the median and 95th-percentile save time, in place and rewrite separately.

Assert zero failures. Refusals must match the plan-1 corpus run, which is recorded in `/Users/nichitabulgaru/Documents/LJ/audits/tag-codecs-plan1-2026-09-29/`: `ID3_UNKNOWN_AUDIO` ×3 and `MP4_FRAGMENTED` ×1 on the full edit.

Run: `TAG_REAL_FILES=/Users/nichitabulgaru/Documents/LJ/tag-corpus/files TAG_REAL_FILES_OUT=/Users/nichitabulgaru/Documents/LJ/tag-corpus/out ./gradlew :core:library:testAndroidHostTest --offline --rerun --tests '*DurableWriteRealFileTest*'`

Then run the independent PCM check with `/Users/nichitabulgaru/Documents/LJ/tag-corpus/verify2.py`. Read it first for usage; ffmpeg, flac and mutagen are installed. Expected: 0 PCM mismatches. When done, delete the contents of `tag-corpus/out` (only `out`, never `files`) to free the disk.

- [ ] **Step 6: Commit**

```bash
git add core/library/src/androidMain/kotlin/io/github/nikitasud/latentjam/library/tags/write/ core/library/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/library/tags/write/
git commit -m "feat(tags): durable saves on real files, proven on the real library"
```

Record the corpus numbers and timings in the task report. They are the first real numbers for spec §7.

---

### Task 8: The coordinator: consent, batches of 2,000, three files at a time, and process death

A sibling of `TrackDeleteCoordinator`. Read it first. This coordinator follows its structure (a main-thread state machine, string-only checkpoints, prompt, answer and acknowledge), with these differences:
- It writes instead of deleting.
- A request carries its edits.
- A batch writes with concurrency 3.
- It finishes interrupted saves before re-saving.
- It runs one rescan at the end.
- It can stop between files.
- It carries a second request kind, `RECOVER`, which finishes the journal's open records under fresh consent.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagWriteCoordinator.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.kt`. Change `TagWriteOutcome` and add the report types and `tagWriteOutcome(report)`.
- Create: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/MemoryWriteFiles.kt`, an in-memory `TargetFile`/`RecoveryDirectory` for app tests. The core `FaultFiles` lives in core/library's test sources, which composeApp cannot see.
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagWriteCoordinatorTest.kt`

**Interfaces:**
- Consumes: `DurableWriter`, `TagRecovery`, `WriteResult`, `TargetFile`, `JournalRecord` (core/library `tags.write`).
- Produces:
  - `internal enum class TagWriteStrategy { SYSTEM_WRITE_REQUEST, RECOVERABLE_CONSENT, WRITE_PERMISSION, NO_CONSENT }`
  - `internal enum class TagWriteKind { EDIT, RECOVER }`
  - `internal enum class FileWriteStatus { SAVED, UNCHANGED, REFUSED, NO_SPACE, FAILED, RECOVERY_PENDING, DENIED, CANCELLED, MISSING, READ_ONLY, STOPPED, RECOVERED, RESTORED, FOREIGN }`
  - `internal data class FileWriteResult(key: String, status: FileWriteStatus, refusal: TagRefusal? = null, newLength: Long? = null)`
  - `internal data class TagWriteReport(kind: TagWriteKind, results: List<FileWriteResult>)`
  - `internal sealed interface WriteOpen<out C>`, with the cases `Opened(file: TargetFile, freeBytes: Long?)`, `Missing`, `Denied`, `ReadOnly`, `Failed` and `NeedsConsent<C>(consent: C)`.
  - `internal interface TagWriteBackend<C>` with the members `strategy`, `writer`, `recovery`, `hasWritePermission()`, `suspend batchConsent(keys): C`, `suspend open(key): WriteOpen<C>`, `suspend rescan(keys)`, `stash(name, bytes)`, `unstash(name)` and `drop(name)`.
  - `internal class TagWriteCoordinator<C>(backend, scope, io: CoroutineDispatcher, restored: List<String>? = null, save: (List<String>) -> Unit = {}, concurrency: Int = 3)` with:
    - flows: `prompt`, `completed`, `progress`, `pendingRecovery`, `active`;
    - `enqueue(keys, edits): Long?` and `enqueueRecovery()`;
    - `suspend refreshRecovery()` and `stop()`;
    - `promptLaunched(id): Boolean` and `answer(WriteAnswer)`;
    - `onHostResumed()`;
    - `listen(id, listener)`, `unlisten(id)` and `deliver(id)`.
  - `internal enum class WriteAnswer { APPROVED, CANCELLED, FAILED }`
  - `TagWriteOutcome` now has the cases `Saved`, `Cancelled`, `Refused(reason: TagRefusal?)`, `Failed`, `Unavailable`, `NotEnoughSpace` and `RecoveryPending`.
  - `internal fun tagWriteOutcome(report: TagWriteReport): TagWriteOutcome`

- [ ] **Step 1: Change `TrackTagWriter.kt`**

Replace the `Id3Refusal` import with `io.github.nikitasud.latentjam.library.tags.TagRefusal`, and change `TagWriteOutcome` to:

```kotlin
sealed interface TagWriteOutcome {

    /** The file's tags now say what the user asked, and the media index knows it. */
    data object Saved : TagWriteOutcome

    /** The system's write-permission dialog was dismissed or declined. Not an error. */
    data object Cancelled : TagWriteOutcome

    /**
     * The tag writer would not rewrite this file — see [TagRefusal]. Every reason is a case where
     * writing anyway risked destroying data, so this is the writer working, not failing.
     */
    data class Refused(val reason: TagRefusal?) : TagWriteOutcome

    /** The save did not happen; the file is exactly as it was. */
    data object Failed : TagWriteOutcome

    /** Not enough free space for a safe save; nothing was touched. */
    data object NotEnoughSpace : TagWriteOutcome

    /** The save was interrupted; LatentJam finishes or undoes it once it may write again. */
    data object RecoveryPending : TagWriteOutcome

    /** This platform has no path to writing this file (a Music-library track on iOS). */
    data object Unavailable : TagWriteOutcome
}
```

Below it, add the report types:

```kotlin
internal enum class FileWriteStatus {
    SAVED, UNCHANGED, REFUSED, NO_SPACE, FAILED, RECOVERY_PENDING, DENIED, CANCELLED, MISSING, READ_ONLY,
    STOPPED,

    /** An interrupted save of this file was finished: the edit is in it. */
    RECOVERED,

    /** An interrupted save of this file was undone: it is its original again. */
    RESTORED,

    /** The file had been changed by another app since the interruption; it was left as found. */
    FOREIGN,
}

internal data class FileWriteResult(
    val key: String,
    val status: FileWriteStatus,
    val refusal: TagRefusal? = null,
    val newLength: Long? = null,
)

internal data class TagWriteReport(val kind: TagWriteKind, val results: List<FileWriteResult>)

/** The single-track editor's view of a one-file report. */
internal fun tagWriteOutcome(report: TagWriteReport): TagWriteOutcome {
    val result = report.results.singleOrNull() ?: return TagWriteOutcome.Failed
    return when (result.status) {
        FileWriteStatus.SAVED, FileWriteStatus.UNCHANGED, FileWriteStatus.RECOVERED -> TagWriteOutcome.Saved
        FileWriteStatus.REFUSED -> TagWriteOutcome.Refused(result.refusal)
        FileWriteStatus.NO_SPACE -> TagWriteOutcome.NotEnoughSpace
        FileWriteStatus.RECOVERY_PENDING -> TagWriteOutcome.RecoveryPending
        FileWriteStatus.CANCELLED, FileWriteStatus.DENIED, FileWriteStatus.STOPPED -> TagWriteOutcome.Cancelled
        FileWriteStatus.READ_ONLY -> TagWriteOutcome.Unavailable
        FileWriteStatus.FAILED, FileWriteStatus.MISSING, FileWriteStatus.RESTORED, FileWriteStatus.FOREIGN -> TagWriteOutcome.Failed
    }
}
```

Also update the KDoc on `rememberTagWriter`: consent now exists on every Android version (a system dialog on 11+, a per-file dialog on 10, the storage permission on 7–9), and iOS needs none.

Fix every compile error in `TrackInfoSheet.kt` from the changed `Refused` type. The message mapping comes in Task 11; for now the new outcomes fall into the existing `else` branch.

- [ ] **Step 2: Write `MemoryWriteFiles.kt` (composeApp commonTest)**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.write.RecoveryDirectory
import io.github.nikitasud.latentjam.library.tags.write.TargetFile

/** Files in memory for coordinator tests; crash behaviour is core/library's FaultFiles' job. */
internal class MemoryWriteFiles {
    private val tracks = HashMap<String, Box>()
    private val store = LinkedHashMap<String, Box>()

    class Box(var bytes: ByteArray)

    fun put(key: String, bytes: ByteArray) {
        tracks[key] = Box(bytes.copyOf())
    }

    fun has(key: String) = key in tracks
    fun bytes(key: String): ByteArray = tracks.getValue(key).bytes.copyOf()
    fun track(key: String): TargetFile = BoxFile(tracks.getValue(key))

    val directory: RecoveryDirectory = object : RecoveryDirectory {
        override fun create(name: String): TargetFile = BoxFile(Box(ByteArray(0)).also { store[name] = it })
        override fun open(name: String): TargetFile? = store[name]?.let(::BoxFile)
        override fun delete(name: String) {
            store.remove(name)
        }
        override fun names(): List<String> = store.keys.toList()
        override fun sync() = Unit
        override fun freeBytes(): Long = Long.MAX_VALUE / 4
    }

    private class BoxFile(private val box: Box) : TargetFile {
        override val length: Long get() = box.bytes.size.toLong()
        override fun read(offset: Long, count: Int): ByteArray? =
            if (offset < 0 || count < 0 || offset > length || count > length - offset) null
            else box.bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
            val end = offset.toInt() + count
            if (end > box.bytes.size) box.bytes = box.bytes.copyOf(end)
            bytes.copyInto(box.bytes, offset.toInt(), from, from + count)
        }
        override fun setLength(length: Long) {
            box.bytes = box.bytes.copyOf(length.toInt())
        }
        override fun force() = Unit
        override fun close() = Unit
    }
}
```

- [ ] **Step 3: Write the failing coordinator test**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.Id3Tags
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.TagRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class TagWriteCoordinatorTest {

    /** An MP3 whose fresh ID3v2.3 tag (16 KiB padding) holds [title], so title edits patch in place. */
    private fun mp3(title: String = "Old"): ByteArray =
        Id3Tags.updateTag(MPEG_FRAME + ByteArray(1024), TagEdits(title = title))!!

    private class Backend(override val strategy: TagWriteStrategy) : TagWriteBackend<String> {
        val files = MemoryWriteFiles()
        var permission = false
        val granted = HashSet<String>()
        val consentBatches = ArrayList<List<String>>()
        val rescans = ArrayList<List<String>>()
        val stashed = HashMap<String, ByteArray>()
        private var ids = 0
        override val writer = DurableWriter(files.directory, { "w${++ids}" })
        override val recovery = TagRecovery(files.directory)

        override fun hasWritePermission() = permission
        override suspend fun batchConsent(keys: List<String>): String {
            consentBatches += keys
            return "batch:${keys.size}"
        }
        override suspend fun open(key: String): WriteOpen<String> {
            if (!files.has(key)) return WriteOpen.Missing
            val allowed = when (strategy) {
                TagWriteStrategy.NO_CONSENT -> true
                TagWriteStrategy.WRITE_PERMISSION -> permission
                TagWriteStrategy.SYSTEM_WRITE_REQUEST, TagWriteStrategy.RECOVERABLE_CONSENT -> key in granted
            }
            return when {
                allowed -> WriteOpen.Opened(files.track(key), freeBytes = null)
                strategy == TagWriteStrategy.RECOVERABLE_CONSENT -> WriteOpen.NeedsConsent("file:$key")
                else -> WriteOpen.Denied
            }
        }
        override suspend fun rescan(keys: List<String>) {
            rescans += keys
        }
        override fun stash(name: String, bytes: ByteArray) {
            stashed[name] = bytes
        }
        override fun unstash(name: String): ByteArray? = stashed[name]
        override fun drop(name: String) {
            stashed.remove(name)
        }
    }

    private class Harness(val backend: Backend, val test: TestScope, restored: List<String>? = null) {
        var saved: List<String> = restored.orEmpty()
        val scope = CoroutineScope(test.coroutineContext + StandardTestDispatcher(test.testScheduler))
        val coordinator = TagWriteCoordinator(backend, scope, StandardTestDispatcher(test.testScheduler), restored, { saved = it })
        val reports = ArrayList<TagWriteReport>()

        fun enqueue(keys: List<String>, edits: TagEdits): Long {
            val id = assertNotNull(coordinator.enqueue(keys, edits))
            coordinator.listen(id) { reports += it }
            return id
        }

        /** What the platform host does: launch the prompt, let the "system" grant it, answer. */
        fun approvePrompt() {
            val prompt = assertNotNull(coordinator.prompt.value)
            assertTrue(coordinator.promptLaunched(prompt.requestId))
            when {
                prompt.consent == null -> backend.permission = true
                prompt.consent!!.startsWith("batch:") -> backend.granted += backend.consentBatches.last()
                else -> backend.granted += prompt.consent!!.removePrefix("file:")
            }
            coordinator.answer(WriteAnswer.APPROVED)
        }

        fun deliver() {
            coordinator.completed.value?.let { coordinator.deliver(it.id) }
        }

        fun recreate(): Harness {
            scope.cancel()
            return Harness(backend, test, saved)
        }
    }

    @Test
    fun withoutConsentEveryFileIsSavedAndTheIndexRescannedOnce() = runTest {
        val backend = Backend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b", "c", "d").forEach { backend.files.put(it, mp3()) }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b", "c", "d"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(List(4) { FileWriteStatus.SAVED }, harness.reports.single().results.map { it.status })
        assertEquals(listOf(listOf("a", "b", "c", "d")), backend.rescans.map { it.sorted() })
        assertContentEquals(mp3("New"), backend.files.bytes("c"))
    }

    @Test
    fun theSystemRequestIsSplitAt2000Files() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        val keys = List(4_500) { "k$it" }
        val file = mp3()
        keys.forEach { backend.files.put(it, file) }
        val harness = Harness(backend, this)
        harness.enqueue(keys, TagEdits(title = "Old")) // a no-op edit: fast, and no file changes
        repeat(3) {
            runCurrent()
            harness.approvePrompt()
        }
        runCurrent()
        harness.deliver()
        assertEquals(listOf(2_000, 2_000, 500), backend.consentBatches.map { it.size })
        assertEquals(4_500, harness.reports.single().results.count { it.status == FileWriteStatus.UNCHANGED })
        assertEquals(emptyList(), backend.rescans)
    }

    @Test
    fun recoverableConsentAsksOncePerFile() = runTest {
        val backend = Backend(TagWriteStrategy.RECOVERABLE_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, mp3()) }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b"), TagEdits(title = "New"))
        runCurrent()
        harness.approvePrompt()
        runCurrent()
        harness.approvePrompt()
        runCurrent()
        harness.deliver()
        assertEquals(List(2) { FileWriteStatus.SAVED }, harness.reports.single().results.map { it.status })
    }

    @Test
    fun aDeniedStoragePermissionWritesNothing() = runTest {
        val backend = Backend(TagWriteStrategy.WRITE_PERMISSION)
        backend.files.put("a", mp3())
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        val prompt = assertNotNull(harness.coordinator.prompt.value)
        harness.coordinator.promptLaunched(prompt.requestId)
        harness.coordinator.answer(WriteAnswer.CANCELLED)
        runCurrent()
        harness.deliver()
        assertEquals(FileWriteStatus.DENIED, harness.reports.single().results.single().status)
        assertContentEquals(mp3(), backend.files.bytes("a"))
    }

    @Test
    fun aDismissedSystemDialogWritesNothing() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        harness.coordinator.promptLaunched(harness.coordinator.prompt.value!!.requestId)
        harness.coordinator.answer(WriteAnswer.CANCELLED)
        runCurrent()
        harness.deliver()
        assertEquals(TagWriteOutcome.Cancelled, tagWriteOutcome(harness.reports.single()))
        assertContentEquals(mp3(), backend.files.bytes("a"))
    }

    @Test
    fun aDialogLostWithTheProcessIsAskedAgain() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
        val first = Harness(backend, this)
        first.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        first.coordinator.promptLaunched(first.coordinator.prompt.value!!.requestId)
        val restored = first.recreate()
        runCurrent()
        assertNull(restored.coordinator.prompt.value)
        restored.coordinator.onHostResumed()
        runCurrent()
        restored.approvePrompt()
        runCurrent()
        restored.coordinator.listen(restored.coordinator.completed.value!!.id) { restored.reports += it }
        restored.deliver()
        assertEquals(FileWriteStatus.SAVED, restored.reports.single().results.single().status)
        assertEquals(2, backend.consentBatches.size)
    }

    @Test
    fun aSaveInterruptedByProcessDeathIsRecoveredThenRedone() = runTest {
        val backend = Backend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", mp3())
        // A previous process died mid-patch: the journal holds an open record for "a".
        val interrupted = DurableWriter(backend.files.directory, { "old" })
        val throwing = object : io.github.nikitasud.latentjam.library.tags.write.TargetFile by backend.files.track("a") {
            override fun force() = throw IllegalStateException("process died")
        }
        interrupted.write("a", throwing, TagEdits(title = "Half"), null)
        assertTrue(backend.recovery.pending().isNotEmpty() || backend.files.bytes("a").contentEquals(mp3()))
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(FileWriteStatus.SAVED, harness.reports.single().results.single().status)
        assertContentEquals(mp3("New"), backend.files.bytes("a"))
        assertTrue(backend.recovery.pending().isEmpty())
    }

    @Test
    fun stopLeavesTheRestUntouched() = runTest {
        val backend = Backend(TagWriteStrategy.RECOVERABLE_CONSENT)
        listOf("a", "b", "c").forEach { backend.files.put(it, mp3()) }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b", "c"), TagEdits(title = "New"))
        runCurrent()
        harness.approvePrompt()
        runCurrent()
        harness.coordinator.stop()
        runCurrent()
        harness.deliver()
        val statuses = harness.reports.single().results.associate { it.key to it.status }
        assertEquals(FileWriteStatus.SAVED, statuses["a"])
        assertEquals(FileWriteStatus.STOPPED, statuses["b"])
        assertEquals(FileWriteStatus.STOPPED, statuses["c"])
        assertContentEquals(mp3(), backend.files.bytes("c"))
    }

    @Test
    fun aRecoveryRequestFinishesEveryInterruptedSave() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
        val throwing = object : io.github.nikitasud.latentjam.library.tags.write.TargetFile by backend.files.track("a") {
            override fun force() = throw IllegalStateException("process died")
        }
        DurableWriter(backend.files.directory, { "old" }).write("a", throwing, TagEdits(title = "Half"), null)
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        // The roll-back's own force() threw too, so the record stayed open.
        assertEquals(1, harness.coordinator.pendingRecovery.value.size)
        harness.coordinator.enqueueRecovery()
        runCurrent()
        assertEquals(listOf(listOf("a")), backend.consentBatches)
        harness.approvePrompt()
        runCurrent()
        val completed = harness.coordinator.completed.value!!
        harness.coordinator.listen(completed.id) { harness.reports += it }
        harness.deliver()
        assertEquals(TagWriteKind.RECOVER, harness.reports.single().kind)
        assertTrue(backend.recovery.pending().isEmpty())
        assertContentEquals(mp3(), backend.files.bytes("a"))
    }

    @Test
    fun aNewCoverSurvivesProcessDeath() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
        val first = Harness(backend, this)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        first.enqueue(listOf("a"), TagEdits(cover = CoverEdit.Replace(png, "image/png")))
        runCurrent()
        val restored = first.recreate()
        val edits = decodeTagWriteRequests(restored.saved) { backend.unstash(it) }.single().edits
        val cover = edits.cover as CoverEdit.Replace
        assertContentEquals(png, cover.bytes)
        assertEquals("image/png", cover.mime)
    }

    private companion object {
        /** An MPEG-1 Layer III frame header, so an untagged file may be given a tag. */
        val MPEG_FRAME = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)
    }
}
```

`mp3("New")` equals what an in-place save of `TagEdits(title = "New")` makes of `mp3()`: both carry one three-character TIT2 in a tag of the same total length. If a test shows otherwise, compare against `WritePlans.applyInMemory(mp3(), TagCodecs.plan(ByteArraySource(mp3()), edits))` instead, and say so in the report.

About `aSaveInterruptedByProcessDeathIsRecoveredThenRedone`: a `force()` that throws makes the writer roll back in place. Whether a record stays open depends on whether the roll-back's own `force()` (same wrapper) throws, and it does. So the record stays `PATCH_PREPARED`, and the coordinator must recover it before saving again. Assert on that path; the guard assertion documents it.

- [ ] **Step 4: Run it and watch it fail**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --tests '*TagWriteCoordinatorTest*'`. Expected: compilation fails (`TagWriteCoordinator` unresolved).

- [ ] **Step 5: Implement `TagWriteCoordinator.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.JournalRecord
import io.github.nikitasud.latentjam.library.tags.write.TagRecovery
import io.github.nikitasud.latentjam.library.tags.write.TargetFile
import io.github.nikitasud.latentjam.library.tags.write.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

internal enum class TagWriteStrategy { SYSTEM_WRITE_REQUEST, RECOVERABLE_CONSENT, WRITE_PERMISSION, NO_CONSENT }
internal enum class TagWriteKind { EDIT, RECOVER }
internal enum class TagWriteStage { READY, WRITING, OFFER_PERMISSION, OFFER_FILE, OFFER_BATCH, WAIT_PERMISSION, WAIT_FILE, WAIT_BATCH, COMPLETE }
internal enum class WriteAnswer { APPROVED, CANCELLED, FAILED }

internal sealed interface WriteOpen<out C> {
    class Opened(val file: TargetFile, val freeBytes: Long?) : WriteOpen<Nothing>
    data object Missing : WriteOpen<Nothing>
    data object Denied : WriteOpen<Nothing>
    data object ReadOnly : WriteOpen<Nothing>
    data object Failed : WriteOpen<Nothing>
    data class NeedsConsent<C>(val consent: C) : WriteOpen<C>
}

/** What a platform supplies: consent, file access, the durable writer over its store, and the media index. */
internal interface TagWriteBackend<C> {
    val strategy: TagWriteStrategy
    val writer: DurableWriter
    val recovery: TagRecovery
    fun hasWritePermission(): Boolean
    suspend fun batchConsent(keys: List<String>): C
    suspend fun open(key: String): WriteOpen<C>
    suspend fun rescan(keys: List<String>)

    /** Keeps a replacement cover outside the saved state, which cannot hold image bytes. */
    fun stash(name: String, bytes: ByteArray)
    fun unstash(name: String): ByteArray?
    fun drop(name: String)
}

internal data class TagWriteRequest(
    val id: Long,
    val kind: TagWriteKind,
    val keys: List<String>,
    val edits: TagEdits,
    val stage: TagWriteStage = TagWriteStage.READY,
    val consented: Boolean = false,
    val permissionRequested: Boolean = false,
    /** The files the current system write request covers (Android 11+). */
    val batch: List<String> = emptyList(),
    val results: List<FileWriteResult> = emptyList(),
    val stopRequested: Boolean = false,
) {
    val remaining: List<String>
        get() {
            val done = results.mapTo(HashSet()) { it.key }
            return keys.filter { it !in done }
        }
}

internal data class TagWritePrompt<C>(val requestId: Long, val consent: C? = null)
internal data class TagWriteProgress(val requestId: Long, val done: Int, val total: Int)

/**
 * Saves tag edits into many files with the consent each platform needs, three at a time, and
 * reports each file's fate exactly.
 *
 * Runs on the main thread, where every state change happens; the writes themselves run on [io].
 * Its owner outlives the Activity and checkpoints every transition. A process that dies mid-request
 * restores it, asks for consent again (a grant never outlives its process) and finishes the file
 * that was being written through [TagRecovery] before saving it again.
 */
internal class TagWriteCoordinator<C>(
    private val backend: TagWriteBackend<C>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    restored: List<String>? = null,
    private val save: (List<String>) -> Unit = {},
    private val concurrency: Int = 3,
) {
    private var requests: List<TagWriteRequest> = decodeTagWriteRequests(restored, backend::unstash)
    private var nextId = (requests.maxOfOrNull { it.id } ?: 0L) + 1L
    private var worker: Job? = null
    private val listeners = HashMap<Long, (TagWriteReport) -> Unit>()

    private val mutablePrompt = MutableStateFlow<TagWritePrompt<C>?>(null)
    val prompt = mutablePrompt.asStateFlow()
    private val mutableCompleted = MutableStateFlow<TagWriteRequest?>(null)
    val completed = mutableCompleted.asStateFlow()
    private val mutableProgress = MutableStateFlow<TagWriteProgress?>(null)
    val progress = mutableProgress.asStateFlow()
    private val mutablePending = MutableStateFlow<List<JournalRecord>>(emptyList())
    val pendingRecovery = mutablePending.asStateFlow()

    /** True while any request is queued or running: an open journal record then may be a save in flight. */
    private val mutableActive = MutableStateFlow(requests.isNotEmpty())
    val active = mutableActive.asStateFlow()

    private var restoredWait = requests.firstOrNull()?.takeIf { it.stage in WAITING }?.id

    init {
        val first = requests.firstOrNull()
        when {
            first == null -> Unit
            first.stage in OFFERING -> update(first.copy(stage = TagWriteStage.READY))
            // Consent died with the process; the interrupted file has an open journal record.
            first.stage == TagWriteStage.WRITING ->
                update(first.copy(stage = TagWriteStage.READY, consented = false, batch = emptyList()))
        }
        resume()
    }

    /** Queues [edits] for [keys]; the id to [listen] on, or null when every key is already queued. */
    fun enqueue(keys: List<String>, edits: TagEdits): Long? {
        val queued = requests.flatMapTo(HashSet()) { it.keys }
        val distinct = keys.filter { it.isNotBlank() && it !in queued }.distinct()
        if (distinct.isEmpty()) return null
        val id = nextId++
        (edits.cover as? CoverEdit.Replace)?.let { backend.stash(coverName(id), it.bytes) }
        requests = requests + TagWriteRequest(id, TagWriteKind.EDIT, distinct, edits)
        checkpoint()
        resume()
        return id
    }

    /** One request finishing every interrupted save in [pendingRecovery], with consent for exactly those files. */
    fun enqueueRecovery(): Long? {
        val queued = requests.flatMapTo(HashSet()) { it.keys }
        val targets = mutablePending.value.map { it.target }.distinct().filter { it !in queued }
        if (targets.isEmpty()) return null
        val id = nextId++
        requests = requests + TagWriteRequest(id, TagWriteKind.RECOVER, targets, TagEdits())
        checkpoint()
        resume()
        return id
    }

    suspend fun refreshRecovery() {
        val records = withContext(io) {
            try {
                backend.recovery.pending()
            } catch (_: Exception) {
                emptyList()
            }
        }
        // A file some queued request is saving right now has an open record that is not an interruption.
        val busy = requests.flatMapTo(HashSet()) { it.keys }
        mutablePending.value = records.filter { it.target !in busy }
    }

    /** Stops between files: those written stay written, the rest untouched. */
    fun stop() {
        val first = requests.firstOrNull() ?: return
        if (first.stage == TagWriteStage.COMPLETE || first.stopRequested) return
        update(first.copy(stopRequested = true))
        if (first.stage in OFFERING) {
            mutablePrompt.value = null
            finishRemaining(requests.first(), FileWriteStatus.STOPPED)
            resume()
        }
    }

    fun listen(id: Long, listener: (TagWriteReport) -> Unit) {
        listeners[id] = listener
    }

    fun unlisten(id: Long) {
        listeners.remove(id)
    }

    /** Hands a completed request's report to its listener, if one is still there, and forgets it. */
    fun deliver(id: Long) {
        val first = requests.firstOrNull() ?: return
        if (first.id != id || first.stage != TagWriteStage.COMPLETE) return
        val report = TagWriteReport(first.kind, first.results)
        if (first.edits.cover is CoverEdit.Replace) backend.drop(coverName(id))
        requests = requests.drop(1)
        mutableCompleted.value = null
        checkpoint()
        listeners.remove(id)?.invoke(report)
        resume()
    }

    /** Activity results arrive before the host resumes; none after a recreation means the dialog is gone. */
    fun onHostResumed() {
        val first = requests.firstOrNull() ?: return
        if (first.id != restoredWait) return
        restoredWait = null
        if (first.stage !in WAITING) return
        update(first.copy(
            stage = TagWriteStage.READY,
            consented = false,
            batch = emptyList(),
            permissionRequested = first.permissionRequested && first.stage != TagWriteStage.WAIT_PERMISSION,
        ))
        resume()
    }

    /** Called immediately before a prompt is launched, with no suspension between saving and launching. */
    fun promptLaunched(id: Long): Boolean {
        val first = requests.firstOrNull() ?: return false
        if (first.id != id || mutablePrompt.value?.requestId != id) return false
        val waiting = when (first.stage) {
            TagWriteStage.OFFER_PERMISSION -> TagWriteStage.WAIT_PERMISSION
            TagWriteStage.OFFER_FILE -> TagWriteStage.WAIT_FILE
            TagWriteStage.OFFER_BATCH -> TagWriteStage.WAIT_BATCH
            else -> return false
        }
        update(first.copy(stage = waiting, permissionRequested = first.permissionRequested || waiting == TagWriteStage.WAIT_PERMISSION))
        mutablePrompt.value = null
        return true
    }

    fun answer(answer: WriteAnswer) {
        val first = requests.firstOrNull() ?: return
        if (first.stage !in WAITING) return
        restoredWait = null
        when {
            answer == WriteAnswer.FAILED -> finishRemaining(first, FileWriteStatus.FAILED)
            answer == WriteAnswer.CANCELLED && first.stage == TagWriteStage.WAIT_PERMISSION -> finishRemaining(first, FileWriteStatus.DENIED)
            answer == WriteAnswer.CANCELLED -> finishRemaining(first, FileWriteStatus.CANCELLED)
            first.stopRequested -> finishRemaining(first, FileWriteStatus.STOPPED)
            else -> update(first.copy(stage = TagWriteStage.READY, consented = true))
        }
        resume()
    }

    private fun checkpoint() {
        mutableActive.value = requests.isNotEmpty()
        save(encodeTagWriteRequests(requests))
    }

    private fun update(request: TagWriteRequest) {
        requests = listOf(request) + requests.drop(1)
        checkpoint()
    }

    private fun finishRemaining(first: TagWriteRequest, status: FileWriteStatus) {
        update(first.copy(
            stage = TagWriteStage.READY,
            consented = false,
            batch = emptyList(),
            results = first.results + first.remaining.map { FileWriteResult(it, status) },
        ))
    }

    private fun offer(request: TagWriteRequest, stage: TagWriteStage, consent: C? = null) {
        update(request.copy(stage = stage))
        mutablePrompt.value = TagWritePrompt(request.id, consent)
    }

    private fun record(result: FileWriteResult) {
        val first = requests.first()
        if (first.results.any { it.key == result.key }) return
        val updated = first.copy(results = first.results + result)
        update(updated)
        mutableProgress.value = TagWriteProgress(updated.id, updated.results.size, updated.keys.size)
    }

    private fun resume() {
        val first = requests.firstOrNull() ?: return
        if (first.stage == TagWriteStage.COMPLETE) {
            mutableCompleted.value = first
            return
        }
        if (worker?.isActive == true || first.stage !in RUNNABLE) return
        val job = scope.launch { run() }
        worker = job
        job.invokeOnCompletion { failure ->
            if (worker === job) worker = null
            if (failure == null && requests.firstOrNull()?.stage in RUNNABLE) resume()
        }
    }

    private suspend fun run() {
        while (true) {
            val current = requests.firstOrNull() ?: return
            if (current.stage == TagWriteStage.COMPLETE) {
                mutableCompleted.value = current
                return
            }
            if (current.stage !in RUNNABLE) return
            val remaining = current.remaining
            when {
                remaining.isEmpty() -> complete(current)
                current.stopRequested -> finishRemaining(current, FileWriteStatus.STOPPED)
                else -> when (backend.strategy) {
                    TagWriteStrategy.NO_CONSENT -> writeAll(current, remaining)
                    TagWriteStrategy.WRITE_PERMISSION -> when {
                        backend.hasWritePermission() -> writeAll(current, remaining)
                        current.permissionRequested -> finishRemaining(current, FileWriteStatus.DENIED)
                        else -> offer(current, TagWriteStage.OFFER_PERMISSION)
                    }
                    TagWriteStrategy.SYSTEM_WRITE_REQUEST -> if (current.consented && current.batch.isNotEmpty()) {
                        writeAll(current, current.batch.filter { it in remaining })
                        update(requests.first().copy(consented = false, batch = emptyList()))
                    } else {
                        // Android 16 caps one request at 2,000 items (spec §5.5).
                        val batch = remaining.take(CONSENT_LIMIT)
                        try {
                            offer(current.copy(batch = batch), TagWriteStage.OFFER_BATCH, backend.batchConsent(batch))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            finishRemaining(current, FileWriteStatus.FAILED)
                        }
                    }
                    TagWriteStrategy.RECOVERABLE_CONSENT -> writeWithFileConsent(current, remaining.first())
                }
            }
        }
    }

    private suspend fun writeAll(current: TagWriteRequest, keys: List<String>) {
        update(current.copy(stage = TagWriteStage.WRITING))
        val permits = Semaphore(concurrency)
        coroutineScope {
            for (key in keys) {
                permits.acquire()
                if (requests.first().stopRequested) {
                    permits.release()
                    break
                }
                launch {
                    try {
                        val step = attempt(current, key)
                        record(if (step is Attempt.Done) step.result else FileWriteResult(key, FileWriteStatus.DENIED))
                    } finally {
                        permits.release()
                    }
                }
            }
        }
        val after = requests.first()
        if (after.stage == TagWriteStage.WRITING) update(after.copy(stage = TagWriteStage.READY))
    }

    private suspend fun writeWithFileConsent(current: TagWriteRequest, key: String) {
        update(current.copy(stage = TagWriteStage.WRITING))
        when (val step = attempt(current, key)) {
            is Attempt.Done -> {
                record(step.result)
                update(requests.first().copy(stage = TagWriteStage.READY, consented = false))
            }
            is Attempt.Consent -> if (!current.consented) {
                offer(requests.first(), TagWriteStage.OFFER_FILE, step.consent)
            } else {
                record(FileWriteResult(key, FileWriteStatus.DENIED))
                update(requests.first().copy(stage = TagWriteStage.READY, consented = false))
            }
        }
    }

    private suspend fun complete(current: TagWriteRequest) {
        val changed = current.results.filter { it.status == FileWriteStatus.SAVED || it.status == FileWriteStatus.RECOVERED }
            .map { it.key }
        if (changed.isNotEmpty()) {
            try {
                backend.rescan(changed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The files are saved; the index catches up at its next scan.
            }
        }
        update(requests.first().copy(stage = TagWriteStage.COMPLETE, batch = emptyList()))
        mutableProgress.value = null
        mutableCompleted.value = requests.first()
    }

    private sealed interface Attempt<out C> {
        data class Done(val result: FileWriteResult) : Attempt<Nothing>
        data class Consent<C>(val consent: C) : Attempt<C>
    }

    private suspend fun attempt(request: TagWriteRequest, key: String): Attempt<C> {
        if (request.kind == TagWriteKind.RECOVER) return withOpened(key) { file, _ -> recoverKey(key, file) }
        val first = withOpened(key) { file, free -> saved(key, backend.writer.write(key, file, request.edits, free)) }
        if (first !is Attempt.Done || first.result.status != FileWriteStatus.RECOVERY_PENDING) return first
        // An earlier save of this file was interrupted: finish or undo it, then save again on a fresh handle
        // (after an atomic replace the old handle points at the replaced file).
        val recovered = withOpened(key) { file, _ -> recoverKey(key, file) }
        if (recovered !is Attempt.Done || recovered.result.status == FileWriteStatus.RECOVERY_PENDING) return first
        return withOpened(key) { file, free -> saved(key, backend.writer.write(key, file, request.edits, free)) }
    }

    private suspend fun withOpened(key: String, action: (TargetFile, Long?) -> FileWriteResult): Attempt<C> {
        val opened = try {
            backend.open(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WriteOpen.Failed
        }
        return when (opened) {
            is WriteOpen.Opened -> Attempt.Done(withContext(io) {
                try {
                    opened.file.use { action(it, opened.freeBytes) }
                } catch (_: Exception) {
                    FileWriteResult(key, FileWriteStatus.FAILED)
                }
            })
            is WriteOpen.NeedsConsent -> Attempt.Consent(opened.consent)
            WriteOpen.Missing -> Attempt.Done(FileWriteResult(key, FileWriteStatus.MISSING))
            WriteOpen.Denied -> Attempt.Done(FileWriteResult(key, FileWriteStatus.DENIED))
            WriteOpen.ReadOnly -> Attempt.Done(FileWriteResult(key, FileWriteStatus.READ_ONLY))
            WriteOpen.Failed -> Attempt.Done(FileWriteResult(key, FileWriteStatus.FAILED))
        }
    }

    private fun saved(key: String, result: WriteResult): FileWriteResult = when (result) {
        is WriteResult.Saved -> FileWriteResult(key, FileWriteStatus.SAVED, newLength = result.newLength)
        WriteResult.NoChange -> FileWriteResult(key, FileWriteStatus.UNCHANGED)
        is WriteResult.Refused -> FileWriteResult(key, FileWriteStatus.REFUSED, refusal = result.reason)
        WriteResult.NotEnoughSpace -> FileWriteResult(key, FileWriteStatus.NO_SPACE)
        is WriteResult.Failed -> FileWriteResult(key, FileWriteStatus.FAILED)
        is WriteResult.RecoveryPending -> FileWriteResult(key, FileWriteStatus.RECOVERY_PENDING)
    }

    private fun recoverKey(key: String, file: TargetFile): FileWriteResult {
        val records = backend.recovery.pending().filter { it.target == key }
        if (records.isEmpty()) return FileWriteResult(key, FileWriteStatus.UNCHANGED)
        val outcomes = records.map { backend.recovery.recover(it, file) }
        backend.recovery.sweep()
        val status = when {
            TagRecovery.Outcome.STUCK in outcomes -> FileWriteStatus.RECOVERY_PENDING
            TagRecovery.Outcome.FOREIGN in outcomes -> FileWriteStatus.FOREIGN
            TagRecovery.Outcome.COMPLETED in outcomes -> FileWriteStatus.RECOVERED
            else -> FileWriteStatus.RESTORED
        }
        return FileWriteResult(key, status)
    }

    private companion object {
        const val CONSENT_LIMIT = 2_000
        val WAITING = setOf(TagWriteStage.WAIT_PERMISSION, TagWriteStage.WAIT_FILE, TagWriteStage.WAIT_BATCH)
        val OFFERING = setOf(TagWriteStage.OFFER_PERMISSION, TagWriteStage.OFFER_FILE, TagWriteStage.OFFER_BATCH)
        val RUNNABLE = setOf(TagWriteStage.READY, TagWriteStage.WRITING)
    }
}

internal fun coverName(id: Long): String = "cover-$id"

/**
 * String-only saved state (SavedStateHandle holds no Context, IntentSender or image). A replaced
 * cover is stashed by the backend under [coverName] and only its mime is written here.
 */
internal fun encodeTagWriteRequests(requests: List<TagWriteRequest>): List<String> = buildList {
    add("1")
    add(requests.size.toString())
    for (r in requests) {
        addAll(listOf(r.id.toString(), r.kind.name, r.stage.name, r.consented.toString(),
            r.permissionRequested.toString(), r.stopRequested.toString()))
        addAll(listOf(r.edits.title, r.edits.artist, r.edits.album, r.edits.genre, r.edits.year, r.edits.albumArtist,
            r.edits.trackNumber, r.edits.trackTotal, r.edits.discNumber, r.edits.discTotal, r.edits.lyrics)
            .map { if (it == null) "0" else "1$it" })
        add(when (val cover = r.edits.cover) {
            CoverEdit.Keep -> "k"
            CoverEdit.Remove -> "r"
            is CoverEdit.Replace -> "c${cover.mime}"
        })
        add(r.keys.size.toString())
        addAll(r.keys)
        add(r.batch.size.toString())
        addAll(r.batch)
        add(r.results.size.toString())
        for (result in r.results) {
            addAll(listOf(result.key, result.status.name, result.refusal?.name.orEmpty(), result.newLength?.toString().orEmpty()))
        }
    }
}

internal fun decodeTagWriteRequests(saved: List<String>?, unstash: (String) -> ByteArray?): List<TagWriteRequest> {
    if (saved == null) return emptyList()
    return try {
        val values = saved.iterator()
        check(values.next() == "1")
        List(values.next().toInt()) {
            val id = values.next().toLong()
            val kind = TagWriteKind.valueOf(values.next())
            val stage = TagWriteStage.valueOf(values.next())
            val consented = values.next().toBooleanStrict()
            val permissionRequested = values.next().toBooleanStrict()
            val stopRequested = values.next().toBooleanStrict()
            val fields = List(11) { values.next().let { v -> if (v == "0") null else v.removePrefix("1") } }
            val coverCode = values.next()
            val keys = List(values.next().toInt()) { values.next() }
            val batch = List(values.next().toInt()) { values.next() }
            val results = List(values.next().toInt()) {
                FileWriteResult(
                    key = values.next(),
                    status = FileWriteStatus.valueOf(values.next()),
                    refusal = values.next().takeIf { it.isNotEmpty() }?.let(TagRefusal::valueOf),
                    newLength = values.next().takeIf { it.isNotEmpty() }?.toLong(),
                )
            }
            val coverBytes = if (coverCode.startsWith("c")) unstash(coverName(id)) else null
            val cover = when {
                coverCode == "k" -> CoverEdit.Keep
                coverCode == "r" -> CoverEdit.Remove
                coverBytes != null -> CoverEdit.Replace(coverBytes, coverCode.removePrefix("c"))
                else -> null
            }
            val edits = TagEdits(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6],
                fields[7], fields[8], fields[9], fields[10], cover ?: CoverEdit.Keep)
            val request = TagWriteRequest(id, kind, keys, edits, stage, consented, permissionRequested, batch, results, stopRequested)
            // A cover whose bytes were lost must not be saved as "keep": finish the rest as failed.
            if (cover == null) {
                request.copy(stage = TagWriteStage.READY, results = results + request.remaining.map { FileWriteResult(it, FileWriteStatus.FAILED) })
            } else request
        }.also { check(!values.hasNext()) }
    } catch (_: Exception) {
        emptyList()
    }
}
```

Check the `TagEdits` constructor's parameter order against `TagModel.kt` (title, artist, album, genre, year, albumArtist, trackNumber, trackTotal, discNumber, discTotal, lyrics, cover), and use named arguments if it differs.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :composeApp:testAndroidHostTest --offline -q --tests '*TagWriteCoordinatorTest*'`. Expected: PASS, 10 tests.

Then run the whole composeApp suite and the iOS compile. Expected: green, except compile errors in the Android and iOS `rememberTagWriter` actuals that still use `Id3Refusal`. Keep those compiling with the smallest change (map to `TagWriteOutcome.Refused(null)`); Tasks 9 and 10 replace them.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/ composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/ composeApp/src/androidMain composeApp/src/iosMain
git commit -m "feat(tags): tag saves are batched under one consent per 2,000 files, three at a time, and survive a restart"
```

---

### Task 9: Android — every version from 7 to 16, one rescan, and the save host

**Files:**
- Rewrite: `composeApp/src/androidMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.android.kt`
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagWriteHost.kt` (expect)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt` (place `TagWriteHost()` once)
- Test: `composeApp/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/app/AndroidTagWriteStrategyTest.kt`

**Interfaces:**
- Consumes: Task 8, plus `ChannelTargetFile`, `FileRecoveryDirectory`, `filePathOf` (existing, in this file) and `AndroidAppContext` (existing).
- Produces:
  - `@Composable expect fun TagWriteHost()`: launches consent prompts, delivers completions, and shows the recovery prompt (Task 11 adds the dialog).
  - `rememberTagWriter` keeps its signature. It now only enqueues and listens; it launches nothing.
  - `internal fun tagWriteStrategy(sdkInt: Int): TagWriteStrategy`.

Only the host launches anything. Two composables that both launch the same prompt would each show a dialog, so the host exists exactly once, in `App`. A writer whose sheet is gone simply stops listening: the host still delivers the completion, to nobody. That fixes the "outcome delivered to the wrong sheet" defect found in the review of the earlier main-checkout code.

- [ ] **Step 1: Strategy test (host JVM)**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidTagWriteStrategyTest {
    @Test
    fun eachAndroidVersionGetsItsConsentMechanism() {
        assertEquals(TagWriteStrategy.WRITE_PERMISSION, tagWriteStrategy(24))
        assertEquals(TagWriteStrategy.WRITE_PERMISSION, tagWriteStrategy(28))
        assertEquals(TagWriteStrategy.RECOVERABLE_CONSENT, tagWriteStrategy(29))
        assertEquals(TagWriteStrategy.SYSTEM_WRITE_REQUEST, tagWriteStrategy(30))
        assertEquals(TagWriteStrategy.SYSTEM_WRITE_REQUEST, tagWriteStrategy(36))
    }
}
```

- [ ] **Step 2: Add the expect in `TagWriteHost.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/**
 * The one place tag saves meet the user: it launches consent prompts, hands finished saves to
 * whichever editor asked for them, and offers to finish saves a crash interrupted. Placed once, in
 * [App] — two hosts would each launch the same prompt.
 */
@Composable
expect fun TagWriteHost()
```

- [ ] **Step 3: Rewrite `TrackTagWriter.android.kt`**

Keep the existing `rescan`, `filePathOf` and `SCAN_TIMEOUT_MS` helpers, reading them first, but turn `rescan` into a batch. Everything else is replaced:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.write.ChannelTargetFile
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.FileRecoveryDirectory
import io.github.nikitasud.latentjam.library.tags.write.TagRecovery
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume

internal fun tagWriteStrategy(sdkInt: Int): TagWriteStrategy = when {
    sdkInt >= Build.VERSION_CODES.R -> TagWriteStrategy.SYSTEM_WRITE_REQUEST
    sdkInt >= Build.VERSION_CODES.Q -> TagWriteStrategy.RECOVERABLE_CONSENT
    else -> TagWriteStrategy.WRITE_PERMISSION
}

/** Holds only application context; an Activity supplies launchers while its UI is resumed. */
internal class TagWriteViewModel(context: Context, handle: SavedStateHandle) : ViewModel() {
    val coordinator = TagWriteCoordinator(
        backend = AndroidTagWriteBackend(context.applicationContext),
        scope = viewModelScope,
        io = Dispatchers.IO,
        restored = handle.get<ArrayList<String>>("tag-writes"),
        save = { handle["tag-writes"] = ArrayList(it) },
    )
}

@Composable
private fun tagWriteCoordinator(): TagWriteCoordinator<IntentSender>? {
    val activity = LocalActivity.current as? ComponentActivity ?: return null
    return remember(activity) {
        val factory = viewModelFactory {
            initializer { TagWriteViewModel(activity.applicationContext, createSavedStateHandle()) }
        }
        ViewModelProvider(activity, factory)[TagWriteViewModel::class.java]
    }.coordinator
}

@Composable
actual fun rememberTagWriter(onOutcome: (TagWriteOutcome) -> Unit): (TrackDescriptor, TagEdits) -> Unit {
    val coordinator = tagWriteCoordinator() ?: return { _, _ -> onOutcome(TagWriteOutcome.Unavailable) }
    val currentOnOutcome by rememberUpdatedState(onOutcome)
    // The requests this editor made, kept across recreation so a restored sheet still hears back.
    val mine = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<Long>()
    }
    DisposableEffect(coordinator) {
        mine.toList().forEach { id -> coordinator.listen(id) { report -> mine.remove(id); currentOnOutcome(tagWriteOutcome(report)) } }
        // Every request this sheet made, including those made after it appeared.
        onDispose { mine.toList().forEach(coordinator::unlisten) }
    }
    return { track, edits ->
        val uri = track.audioUri
        when {
            uri == null -> currentOnOutcome(TagWriteOutcome.Unavailable)
            edits.isEmpty -> currentOnOutcome(TagWriteOutcome.Saved)
            else -> {
                val id = coordinator.enqueue(listOf(uri), edits)
                if (id == null) {
                    currentOnOutcome(TagWriteOutcome.Failed)
                } else {
                    mine += id
                    coordinator.listen(id) { report -> mine.remove(id); currentOnOutcome(tagWriteOutcome(report)) }
                }
            }
        }
    }
}

@Composable
actual fun TagWriteHost() {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val coordinator = tagWriteCoordinator() ?: return
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        coordinator.answer(when {
            result.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true -> WriteAnswer.FAILED
            result.resultCode == Activity.RESULT_OK -> WriteAnswer.APPROVED
            else -> WriteAnswer.CANCELLED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        coordinator.answer(if (granted) WriteAnswer.APPROVED else WriteAnswer.CANCELLED)
    }
    var resumed by remember(activity) { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(coordinator, resumed) {
        if (resumed) {
            coordinator.onHostResumed()
            coordinator.refreshRecovery()
        }
    }
    val prompt by coordinator.prompt.collectAsState()
    LaunchedEffect(prompt, resumed) {
        val pending = prompt
        if (resumed && pending != null && coordinator.promptLaunched(pending.requestId)) {
            try {
                val sender = pending.consent
                if (sender == null) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coordinator.answer(WriteAnswer.FAILED)
            }
        }
    }
    val completed by coordinator.completed.collectAsState()
    LaunchedEffect(completed, resumed) {
        val request = completed
        if (resumed && request != null) {
            // Delivered synchronously so recomposition cannot hand the same report out twice.
            coordinator.deliver(request.id)
            coordinator.refreshRecovery()
        }
    }
    TagRecoveryPrompt(coordinator)
}

private class AndroidTagWriteBackend(private val context: Context) : TagWriteBackend<IntentSender> {
    override val strategy = tagWriteStrategy(Build.VERSION.SDK_INT)
    private val store = FileRecoveryDirectory(File(context.noBackupFilesDir, "tag-write"), ::syncDirectory)
    private val covers = File(context.noBackupFilesDir, "tag-write-covers")
    override val writer = DurableWriter(store, { UUID.randomUUID().toString() })
    override val recovery = TagRecovery(store)

    override fun hasWritePermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    override suspend fun batchConsent(keys: List<String>): IntentSender = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        MediaStore.createWriteRequest(context.contentResolver, keys.map(Uri::parse)).intentSender
    }

    override suspend fun open(key: String): WriteOpen<IntentSender> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(key)
        try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: return@withContext WriteOpen.Failed
            // Neither stream is closed: closing one would close the descriptor, which is the ParcelFileDescriptor's job.
            val file = ChannelTargetFile(
                FileInputStream(descriptor.fileDescriptor).channel,
                FileOutputStream(descriptor.fileDescriptor).channel,
            ) { descriptor.close() }
            WriteOpen.Opened(file, freeBytesOf(uri))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FileNotFoundException) {
            WriteOpen.Missing
        } catch (failure: SecurityException) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && failure is RecoverableSecurityException) {
                WriteOpen.NeedsConsent(failure.userAction.actionIntent.intentSender)
            } else WriteOpen.Denied
        } catch (_: Exception) {
            WriteOpen.Failed
        }
    }

    private fun freeBytesOf(uri: Uri): Long? =
        filePathOf(context, uri)?.let { path -> runCatching { StatFs(File(path).parent).availableBytes }.getOrNull() }

    override suspend fun rescan(keys: List<String>) {
        val paths = withContext(Dispatchers.IO) { keys.mapNotNull { filePathOf(context, Uri.parse(it)) } }
        if (paths.isEmpty()) return
        withTimeoutOrNull(maxOf(SCAN_TIMEOUT_MS, paths.size * 50L)) {
            suspendCancellableCoroutine { continuation ->
                var remaining = paths.size
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    remaining--
                    if (remaining == 0 && continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    override fun stash(name: String, bytes: ByteArray) {
        covers.mkdirs()
        File(covers, name).writeBytes(bytes)
    }

    override fun unstash(name: String): ByteArray? = File(covers, name).takeIf { it.isFile }?.readBytes()

    override fun drop(name: String) {
        File(covers, name).delete()
    }
}

/** fsync of the store directory, so created and deleted entries survive a power cut (API 21+). */
private fun syncDirectory(directory: File) {
    val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}
```

After the class, keep the existing `filePathOf` and `SCAN_TIMEOUT_MS` from the current file, and delete its old single-file `rescan` and every old writer function (`saveTags`, `rewriteFile`, `planRewrite`, `stage`, `patchHead`, `replaceContents`, `copyOver`, `readAt`, `writeAt`, `Rewrite`, `Plan`). The durable writer replaces all of them.

The `runCatching` in `freeBytesOf` is platform code outside `tags.write`, and `StatFs` failure is not a crash signal, so it is acceptable there.

- [ ] **Step 4: Place the host**

In `App.kt`, add `TagWriteHost()` once, next to where `DeleteTracksDialog` is shown, inside the root content that is always composed. Stubs are needed now for `TagRecoveryPrompt(coordinator)` (Task 11 implements it) and for the iOS actual (Task 10). Add this to `TagWriteHost.kt` to keep this task compiling:

```kotlin
@Composable
internal fun <C> TagRecoveryPrompt(coordinator: TagWriteCoordinator<C>) = Unit
```

Task 11 replaces the body. On iOS, add a minimal `actual fun TagWriteHost() = Unit` in `TrackTagWriter.ios.kt`; Task 10 replaces it.

- [ ] **Step 5: Verify**

Run:
- `./gradlew :composeApp:testAndroidHostTest --offline -q --rerun` (all green, including the strategy test);
- `./gradlew :composeApp:compileAndroidMain :androidApp:compileDebugKotlin --offline -q`;
- `./gradlew :composeApp:compileKotlinIosSimulatorArm64 --offline -q`.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src
git commit -m "feat(tags): Android saves tags on every version from 7 to 16, with the consent each one needs"
```

---

### Task 10: iOS — imported files saved with an atomic replace, Music-library tracks read-only

**Files:**
- Create: `core/library/src/iosMain/kotlin/io/github/nikitasud/latentjam/library/IosTagFiles.kt`
- Test: `core/library/src/iosTest/kotlin/io/github/nikitasud/latentjam/library/IosTagFilesTest.kt` (runs on a booted simulator; otherwise compile-only)
- Rewrite: `composeApp/src/iosMain/kotlin/io/github/nikitasud/latentjam/app/TrackTagWriter.ios.kt`

**Interfaces:**
- Produces, in core/library iosMain:

```kotlin
public object IosTagFiles {
    /** The recovery store under Application Support, or null when the sandbox is malformed. */
    public fun store(): RecoveryDirectory?
    /** The imported track [key] (a path relative to Documents) opened for writing; null for Music-library ids, escapes and missing files. */
    public fun open(key: String): TargetFile?
    public fun replacer(): AtomicReplacer?
    public fun freeBytes(): Long?
}
```

- [ ] **Step 1: Write `IosTagFiles.kt`**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.write.AtomicReplacer
import io.github.nikitasud.latentjam.library.tags.write.RecoveryDirectory
import io.github.nikitasud.latentjam.library.tags.write.StorageFullException
import io.github.nikitasud.latentjam.library.tags.write.TargetFile
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.posix.ENOSPC
import platform.posix.F_FULLFSYNC
import platform.posix.O_CREAT
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.open
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink

/** A POSIX file descriptor as a [TargetFile]. [force] uses F_FULLFSYNC: plain fsync does not flush the drive. */
internal class PosixTargetFile(private var fd: Int) : TargetFile {
    override val length: Long
        get() = memScoped {
            val info = alloc<stat>()
            check(fstat(fd, info.ptr) == 0) { "fstat failed" }
            info.st_size
        }

    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || offset > length || count > length - offset) return null
        val buffer = ByteArray(count)
        if (count == 0) return buffer
        buffer.usePinned { pinned ->
            var done = 0
            while (done < count) {
                val n = pread(fd, pinned.addressOf(done), (count - done).toULong(), offset + done)
                if (n <= 0) return null
                done += n.toInt()
            }
        }
        return buffer
    }

    override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
        if (count == 0) return
        bytes.usePinned { pinned ->
            var done = 0
            while (done < count) {
                val n = pwrite(fd, pinned.addressOf(from + done), (count - done).toULong(), offset + done)
                if (n < 0) {
                    if (errno == ENOSPC) throw StorageFullException("no space")
                    throw IllegalStateException("write failed: errno $errno")
                }
                done += n.toInt()
            }
        }
    }

    override fun setLength(length: Long) {
        check(ftruncate(fd, length) == 0) { if (errno == ENOSPC) "no space" else "ftruncate failed: errno $errno" }
    }

    override fun force() {
        if (fcntl(fd, F_FULLFSYNC) == -1) check(fsync(fd) == 0) { "fsync failed: errno $errno" }
    }

    override fun close() {
        if (fd >= 0) close(fd)
        fd = -1
    }
}

internal class IosRecoveryDirectory(private val root: String) : RecoveryDirectory {
    private fun path(name: String): String {
        require(Regex("[A-Za-z0-9._-]+").matches(name)) { "unsafe store name $name" }
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
        return "$root/$name"
    }

    override fun create(name: String): TargetFile {
        val fd = open(path(name), O_RDWR or O_CREAT or O_TRUNC or O_NOFOLLOW, 0x180) // 0600
        check(fd >= 0) { "create failed: errno $errno" }
        return PosixTargetFile(fd)
    }

    override fun open(name: String): TargetFile? {
        val fd = open(path(name), O_RDWR or O_NOFOLLOW)
        return if (fd >= 0) PosixTargetFile(fd) else null
    }

    override fun delete(name: String) {
        unlink(path(name))
    }

    @Suppress("UNCHECKED_CAST")
    override fun names(): List<String> =
        (NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null) as? List<String>).orEmpty().sorted()

    override fun sync() {
        syncDirectory(root)
    }

    override fun freeBytes(): Long =
        (NSFileManager.defaultManager.attributesOfFileSystemForPath(root, null)?.get(NSFileSystemFreeSize) as? NSNumber)
            ?.longLongValue ?: 0L
}

private fun syncDirectory(path: String) {
    val fd = open(path, O_RDONLY)
    if (fd < 0) return
    try {
        if (fcntl(fd, F_FULLFSYNC) == -1) fsync(fd)
    } finally {
        close(fd)
    }
}

/**
 * Resolves an imported track's stable id (its path relative to Documents) to a real file inside
 * Documents — never a Music-library id, an absolute path, `..`, or a symlink leading out.
 */
internal fun writableIosTrackPath(documents: String, key: String): String? {
    if (key.isBlank() || key.startsWith('/') || key.startsWith("ios-media:") || '\u0000' in key ||
        key.split('/').any { it == ".." || it == "." || it.isEmpty() }
    ) return null
    val root = NSURL.fileURLWithPath(documents).URLByResolvingSymlinksInPath?.path?.trimEnd('/') ?: return null
    val path = NSURL.fileURLWithPath("$root/$key").URLByResolvingSymlinksInPath?.path ?: return null
    return path.takeIf { it.startsWith("$root/") }
}

public object IosTagFiles {
    private fun storeRoot(): String? = IosPaths.appSupport()?.let { "$it/tag-write" }

    public fun store(): RecoveryDirectory? = storeRoot()?.let(::IosRecoveryDirectory)

    public fun open(key: String): TargetFile? {
        val documents = IosPaths.documents() ?: return null
        val path = writableIosTrackPath(documents, key) ?: return null
        val fd = open(path, O_RDWR or O_NOFOLLOW)
        return if (fd >= 0) PosixTargetFile(fd) else null
    }

    public fun freeBytes(): Long? = IosPaths.documents()?.let {
        (NSFileManager.defaultManager.attributesOfFileSystemForPath(it, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
    }

    /** Rename within the app container: Documents and Application Support share one volume. */
    public fun replacer(): AtomicReplacer? {
        val documents = IosPaths.documents() ?: return null
        val root = storeRoot() ?: return null
        return AtomicReplacer { key, stagedName ->
            val path = writableIosTrackPath(documents, key) ?: throw IllegalStateException("not an imported track")
            val staged = "$root/$stagedName"
            // Keep the file's creation date and data protection class across the swap.
            val manager = NSFileManager.defaultManager
            manager.attributesOfItemAtPath(path, null)
                ?.filterKeys { it == platform.Foundation.NSFileCreationDate || it == platform.Foundation.NSFileProtectionKey }
                ?.let { manager.setAttributes(it, staged, null) }
            check(rename(staged, path) == 0) { "rename failed: errno $errno" }
            syncDirectory(path.substringBeforeLast('/'))
            syncDirectory(root)
            val fd = open(path, O_RDWR or O_NOFOLLOW)
            check(fd >= 0) { "reopen failed: errno $errno" }
            PosixTargetFile(fd)
        }
    }
}
```

`setAttributes(it, staged, null)` is `setAttributes(_:ofItemAtPath:error:)`; the implementer checks the exact Kotlin/Native signature (`setAttributes(attributes, ofItemAtPath = staged, error = null)`) and adjusts.

- [ ] **Step 2: Write `IosTagFilesTest.kt` (iosTest)**

It exercises `PosixTargetFile` and `IosRecoveryDirectory` in a temporary directory (`NSTemporaryDirectory()`), with the same checks as `JvmWriteFilesTest`:
- read, write, grow and shrink;
- create, list and delete;
- a durable in-place save and a rewrite of every `WriteFixtures` case through `DurableWriter` with an atomic replacer built over the temp directory: a rename of a staged file onto the track path.

It cannot use `IosPaths`, so the test builds its own replacer the same way `IosTagFiles.replacer()` does, over its temp paths.

Run it compiled: `./gradlew :core:library:compileTestKotlinIosSimulatorArm64 --offline -q`. If a simulator is booted, `./gradlew :core:library:iosSimulatorArm64Test --offline` runs it. Report which of the two you did.

- [ ] **Step 3: Rewrite `TrackTagWriter.ios.kt`**

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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import io.github.nikitasud.latentjam.library.IosTagFiles
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.RecoveryDirectory
import io.github.nikitasud.latentjam.library.tags.write.TagRecovery
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSUUID

/** iOS writes only files it owns (imported into Documents): no consent, and a rename replaces a file atomically. */
private class IosTagWriteBackend(private val store: RecoveryDirectory) : TagWriteBackend<Nothing> {
    override val strategy = TagWriteStrategy.NO_CONSENT
    override val writer = DurableWriter(store, { NSUUID().UUIDString }, IosTagFiles.replacer())
    override val recovery = TagRecovery(store, IosTagFiles.replacer())
    override fun hasWritePermission() = true
    override suspend fun batchConsent(keys: List<String>): Nothing = error("iOS asks for no consent")
    override suspend fun open(key: String): WriteOpen<Nothing> {
        if (key.startsWith("ios-media:")) return WriteOpen.ReadOnly
        val file = IosTagFiles.open(key) ?: return WriteOpen.Missing
        return WriteOpen.Opened(file, IosTagFiles.freeBytes())
    }
    // The library reload after a save re-reads imported files itself; there is no system index to tell.
    override suspend fun rescan(keys: List<String>) = Unit
    override fun stash(name: String, bytes: ByteArray) = IosCoverStash.put(name, bytes)
    override fun unstash(name: String): ByteArray? = IosCoverStash.get(name)
    override fun drop(name: String) = IosCoverStash.remove(name)
}

/** Replacement covers of queued saves; in memory, since an iOS save never outlives its process mid-consent. */
private object IosCoverStash {
    private val covers = HashMap<String, ByteArray>()
    fun put(name: String, bytes: ByteArray) {
        covers[name] = bytes
    }
    fun get(name: String): ByteArray? = covers[name]
    fun remove(name: String) {
        covers.remove(name)
    }
}

/** A sheet leaving composition cannot cancel a save or lose its report. */
private object IosTagWrites {
    val coordinator: TagWriteCoordinator<Nothing>? by lazy {
        IosTagFiles.store()?.let { store ->
            TagWriteCoordinator(IosTagWriteBackend(store), CoroutineScope(SupervisorJob() + Dispatchers.Main), Dispatchers.Default)
        }
    }
}

@Composable
actual fun rememberTagWriter(onOutcome: (TagWriteOutcome) -> Unit): (TrackDescriptor, TagEdits) -> Unit {
    val coordinator = IosTagWrites.coordinator ?: return { _, _ -> onOutcome(TagWriteOutcome.Unavailable) }
    val currentOnOutcome by rememberUpdatedState(onOutcome)
    val mine = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<Long>()
    }
    DisposableEffect(coordinator) {
        mine.toList().forEach { id -> coordinator.listen(id) { report -> mine.remove(id); currentOnOutcome(tagWriteOutcome(report)) } }
        // Every request this sheet made, including those made after it appeared.
        onDispose { mine.toList().forEach(coordinator::unlisten) }
    }
    return { track, edits ->
        when {
            edits.isEmpty -> currentOnOutcome(TagWriteOutcome.Saved)
            else -> {
                // Imported tracks are identified by their path under Documents; Music-library ids are refused as read-only.
                val id = coordinator.enqueue(listOf(track.id.value), edits)
                if (id == null) {
                    currentOnOutcome(TagWriteOutcome.Failed)
                } else {
                    mine += id
                    coordinator.listen(id) { report -> mine.remove(id); currentOnOutcome(tagWriteOutcome(report)) }
                }
            }
        }
    }
}

@Composable
actual fun TagWriteHost() {
    val coordinator = IosTagWrites.coordinator ?: return
    val completed by coordinator.completed.collectAsState()
    LaunchedEffect(completed) {
        completed?.let {
            coordinator.deliver(it.id)
            coordinator.refreshRecovery()
        }
    }
    // Imported files need no consent, so interrupted saves are finished at once (spec §5.4).
    val pending by coordinator.pendingRecovery.collectAsState()
    LaunchedEffect(Unit) { coordinator.refreshRecovery() }
    LaunchedEffect(pending) { if (pending.isNotEmpty()) coordinator.enqueueRecovery() }
}
```

Check first that iOS `TrackDescriptor.id.value` is the Documents-relative path for imported files and `ios-media:<id>` for the Music library, as `MusicLibrary.ios.kt` builds them. Adjust if not, and say so in the report. `IosPaths` is `internal` in core/library; `IosTagFiles` is the public door, so the app never touches `IosPaths`.

- [ ] **Step 4: Verify**

Run:
- `./gradlew :core:library:compileTestKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64 --offline -q`;
- the iOS test task, if a simulator is booted;
- both host suites, with `--rerun`.

- [ ] **Step 5: Commit**

```bash
git add core/library/src/iosMain core/library/src/iosTest composeApp/src/iosMain
git commit -m "feat(tags): iOS saves tags into imported files with an atomic replace; Music-library tracks stay read-only"
```

---

### Task 11: "LatentJam needs to finish saving N files", and the editor's new messages

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TagWriteHost.kt` (the real `TagRecoveryPrompt`)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackInfoSheet.kt` (`failureMessage` for `NotEnoughSpace` and `RecoveryPending`)
- Modify: all 18 `composeResources/values*/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/TagRecoveryPromptStateTest.kt`

**Interfaces:**
- Produces:
  - `internal fun recoveryPromptVisible(pending: Int, busy: Boolean, dismissed: Boolean): Boolean`, pure and tested;
  - the dialog itself.

- [ ] **Step 1: Strings**

Add these keys to all 18 files, with real translations. Keep `values-in` byte-identical to `values-id`, write `%` never `%%`, and put the plural forms each locale needs (`one`/`few`/`many`/`other` for ru, uk and pl; `zero` through `other` for ar; `other` only for ja, ko and zh-rCN):

```xml
    <string name="info_edit_no_space">Not enough free space to save these tags safely. Nothing was changed.</string>
    <string name="info_edit_recovery_pending">Saving was interrupted. LatentJam will finish or undo it the next time it is allowed to write the file.</string>
    <string name="tag_recovery_title">Finish saving tags</string>
    <plurals name="tag_recovery_body">
        <item quantity="one">LatentJam was interrupted while saving tags to %1$d file. It can finish the change now, or undo it if the file cannot be finished.</item>
        <item quantity="other">LatentJam was interrupted while saving tags to %1$d files. It can finish the changes now, or undo them where a file cannot be finished.</item>
    </plurals>
    <string name="tag_recovery_finish">Finish</string>
    <string name="tag_recovery_later">Later</string>
```

Russian, for reference and as a check:

```xml
    <string name="info_edit_no_space">Недостаточно свободного места, чтобы безопасно сохранить теги. Ничего не изменено.</string>
    <string name="info_edit_recovery_pending">Сохранение было прервано. LatentJam завершит или отменит его, когда ему снова разрешат изменить файл.</string>
    <string name="tag_recovery_title">Завершить сохранение тегов</string>
    <plurals name="tag_recovery_body">
        <item quantity="one">Сохранение тегов в %1$d файл было прервано. LatentJam может завершить его сейчас, а если файл завершить нельзя — вернуть как было.</item>
        <item quantity="few">Сохранение тегов в %1$d файла было прервано. LatentJam может завершить его сейчас, а если какой-то файл завершить нельзя — вернуть его как было.</item>
        <item quantity="many">Сохранение тегов в %1$d файлов было прервано. LatentJam может завершить его сейчас, а если какой-то файл завершить нельзя — вернуть его как было.</item>
        <item quantity="other">Сохранение тегов в %1$d файла было прервано. LatentJam может завершить его сейчас, а если какой-то файл завершить нельзя — вернуть его как было.</item>
    </plurals>
    <string name="tag_recovery_finish">Завершить</string>
    <string name="tag_recovery_later">Позже</string>
```

Also update the existing `info_edit_note` in all 18 files so it does not claim that only Android asks for permission. Read its current text first; the Android wording now holds on every Android version.

- [ ] **Step 2: The pure rule and its test**

```kotlin
/** Offer to finish interrupted saves when some are waiting, nothing is being saved, and the user has not said "Later" this session. */
internal fun recoveryPromptVisible(pending: Int, busy: Boolean, dismissed: Boolean): Boolean =
    pending > 0 && !busy && !dismissed
```

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagRecoveryPromptStateTest {
    @Test
    fun thePromptShowsOnlyWhenSomethingWaitsAndNothingIsRunning() {
        assertTrue(recoveryPromptVisible(pending = 2, busy = false, dismissed = false))
        assertFalse(recoveryPromptVisible(pending = 0, busy = false, dismissed = false))
        assertFalse(recoveryPromptVisible(pending = 2, busy = true, dismissed = false))
        assertFalse(recoveryPromptVisible(pending = 2, busy = false, dismissed = true))
    }
}
```

- [ ] **Step 3: The dialog**

Replace the stub in `TagWriteHost.kt`. Follow the `AlertDialog` style used in `SettingsScreens.kt`: read one first and match its colours, shapes and button style.

```kotlin
@Composable
internal fun <C> TagRecoveryPrompt(coordinator: TagWriteCoordinator<C>) {
    val pending by coordinator.pendingRecovery.collectAsState()
    val progress by coordinator.progress.collectAsState()
    val prompt by coordinator.prompt.collectAsState()
    // "Later" lasts this session; the offer returns at the next launch (spec §5.4).
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val active by coordinator.active.collectAsState()
    val busy = active || progress != null || prompt != null
    if (!recoveryPromptVisible(pending.size, busy, dismissed)) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(Res.string.tag_recovery_title)) },
        text = { Text(pluralStringResource(Res.plurals.tag_recovery_body, pending.size, pending.size)) },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                coordinator.enqueueRecovery()
            }) { Text(stringResource(Res.string.tag_recovery_finish)) }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text(stringResource(Res.string.tag_recovery_later)) }
        },
    )
}
```

iOS finishes recovery automatically (Task 10) and never shows this. On iOS, `TagWriteHost` does not call `TagRecoveryPrompt`.

- [ ] **Step 4: Editor messages**

In `TrackInfoSheet.kt`'s `failureMessage`:
- `TagWriteOutcome.NotEnoughSpace` → `info_edit_no_space`;
- `TagWriteOutcome.RecoveryPending` → `info_edit_recovery_pending`;
- `is TagWriteOutcome.Refused` keeps `info_edit_refused`.

Run all suites (`StringResourceParityTest` included), the Android compile and the iOS compile.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src
git commit -m "feat(tags): an interrupted save is offered to be finished at the next launch, and the editor says when space runs out"
```

---

### Task 12: Whole-plan verification

- [ ] **Step 1: All suites and compiles**

Run every command under Global Constraints, then `./gradlew :androidApp:assembleDebug --offline -q`. Record the test counts.

- [ ] **Step 2: The corpus again, through the Android adapter path**

Rerun Task 7's `DurableWriteRealFileTest` plus `verify2.py`. Expected: 0 failures and 0 PCM mismatches, with the refusal set unchanged. Record the median and p95 save times. Empty `tag-corpus/out` afterwards.

- [ ] **Step 3: A smoke test on a disposable emulator**

Create a throwaway AVD by hand (the recipe is in `/Users/nichitabulgaru/Documents/LJ/audits/issues-5-6-7-2026-09-29/device-smoke-android.md`) on port 5560. Never touch emulator-5556 or LJ-demo. Install the debug APK, push one MP3, FLAC, Opus and M4A with padding, and one MP3 without padding (so a longer title forces a rewrite). Then, in the track sheet:
1. Edit each file's title, accept the system dialog, and confirm the new title shows after the rescan.
2. Pull each file and check with mutagen that only the title changed. `ffmpeg -f md5` of the audio must be equal before and after.
3. Dismiss the system dialog once: the file must be unchanged and no message shown.
4. Force a recovery. With `adb shell run-as io.github.nikitasud.latentjam.kmp`, copy a hand-made `PATCH_PREPARED` journal plus patch file into `no_backup/tag-write`. Build them on the host with the core `Journal` and `PatchBackup` from a small test, and apply the half patch to the pushed file. Relaunch: the dialog must offer to finish one file; "Finish" asks for consent and restores the file byte-identically.
5. Check that the app never crashed (`logcat | grep -E "FATAL|AndroidRuntime"`).

Delete the AVD and the APKs afterwards.

Interrupted saves by killing the app mid-copy-over, full volumes and Android 7, 9 and 10 devices belong to plan 4 (spec §8.3). Say so in the report.

- [ ] **Step 4: Report**

Write the whole-plan summary into the SDD ledger: test counts, corpus numbers, timings against spec §7, and smoke results. Nothing is pushed.
