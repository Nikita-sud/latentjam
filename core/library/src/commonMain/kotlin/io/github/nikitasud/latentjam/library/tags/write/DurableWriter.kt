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
     * free space on the file's volume, or null when the platform cannot tell. [path], where the
     * platform knows one, goes into the journal ([JournalRecord.path]).
     */
    public fun write(
        key: String,
        target: TargetFile,
        edits: TagEdits,
        targetFreeBytes: Long?,
        path: String? = null,
    ): WriteResult {
        val seenLength: Long
        val codec: TagCodec
        val baseline: TagVerification.Baseline
        val plan: WritePlan
        try {
            // A store that cannot be listed throws here, and the save fails: a record it could not
            // rule out may be an interrupted save of this very file, whose bytes are half-written.
            journal.open().firstOrNull { it.target == key }?.let { return WriteResult.RecoveryPending(it.writeId) }
            // Read before the codec looks at the file: a file that changes length after this was
            // planned from a view that no longer holds.
            seenLength = target.length
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
            is WritePlan.InPlacePatch -> patch(key, target, codec, baseline, edits, plan, seenLength, targetFreeBytes, path)
            is WritePlan.StreamingRewrite -> rewrite(key, target, codec, baseline, edits, plan, seenLength, targetFreeBytes, path)
        }
    }

    private fun patch(
        key: String,
        target: TargetFile,
        codec: TagCodec,
        baseline: TagVerification.Baseline,
        edits: TagEdits,
        plan: WritePlan.InPlacePatch,
        seenLength: Long,
        targetFreeBytes: Long?,
        path: String?,
    ): WriteResult {
        val backup = PatchBackup.capture(target, plan) ?: return WriteResult.Failed("the bytes to overwrite could not be read")
        if (backup.originalLength != seenLength) return WriteResult.Failed(CHANGED)
        val originalLength = backup.originalLength
        val saved = backup.encode()
        if (directory.freeBytes() < saved.size + STORE_MARGIN) return WriteResult.NotEnoughSpace
        if (lacksRoom(plan.newLength, originalLength, targetFreeBytes)) return WriteResult.NotEnoughSpace

        val writeId = freshId() ?: return WriteResult.Failed(ID_TAKEN)
        val record = JournalRecord(writeId, key, JournalState.PATCH_PREPARED, originalLength, plan.newLength, path = path)
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
        // Journaled before the first byte is put back, so a crash from here on is recovered as the
        // roll-back the writer began.
        val rollingBack = record.copy(state = JournalState.ROLLING_BACK)
        try {
            journal.append(rollingBack)
        } catch (_: Exception) {
            // Roll back all the same while the process can: that is the file's best chance.
        }
        return when (recovery.rollBackPatch(rollingBack, target, backup)) {
            TagRecovery.Outcome.ROLLED_BACK -> WriteResult.Failed(problem)
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
        seenLength: Long,
        targetFreeBytes: Long?,
        path: String?,
    ): WriteResult {
        val originalLength = seenLength
        val newLength = plan.newLength
        val atomic = replacer != null
        val storeNeeded = newLength + (if (atomic) 0 else originalLength) + REWRITE_MARGIN
        if (directory.freeBytes() < storeNeeded) return WriteResult.NotEnoughSpace
        if (!atomic && lacksRoom(newLength, originalLength, targetFreeBytes)) return WriteResult.NotEnoughSpace

        val writeId = freshId() ?: return WriteResult.Failed(ID_TAKEN)
        val draft = JournalRecord(writeId, key, JournalState.REPLACE_PREPARED, originalLength, newLength, atomic = atomic, path = path)
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
            TagRecovery.Outcome.FOREIGN -> WriteResult.Failed(CHANGED)
            TagRecovery.Outcome.STUCK ->
                if (atomic && notReplaced(prepared, target)) {
                    discard(prepared)
                    WriteResult.Failed("the file could not be replaced; the original was kept")
                } else {
                    WriteResult.RecoveryPending(prepared.writeId)
                }
        }
    }

    /**
     * True when an atomic replace never happened: the staged file is still in the store (a rename
     * moves it out) and the track is still its original. Such a save can be dropped like one that
     * never started.
     */
    private fun notReplaced(record: JournalRecord, target: TargetFile): Boolean = try {
        record.stagedName in directory.names() &&
            target.length == record.originalLength &&
            FileOps.crc(target) == record.originalCrc
    } catch (_: Exception) {
        false
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
        // The length recorded is the one checksummed (the backup's own, with a backup), and it must
        // be the length the plan was made from: a file that grew or shrank since is not the original.
        val (originalLength, originalCrc) = if (draft.atomic) {
            val crc = FileOps.crc(target) ?: throw IllegalStateException("the original could not be read")
            target.length to crc
        } else {
            directory.create(draft.backupName).use { backup ->
                val crc = FileOps.copyOver(target, backup)
                backup.force()
                check(FileOps.crc(backup) == crc) { "the backup does not read back" }
                backup.length to crc
            }
        }
        check(originalLength == draft.originalLength) { CHANGED }
        // Load-bearing if a record is ever journaled before this one: only a first record syncs the directory.
        directory.sync()
        val prepared = draft.copy(originalLength = originalLength, stagedCrc = stagedCrc, originalCrc = originalCrc)
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

    /**
     * Removes a save that never touched its track. The journal goes first, durably: data files
     * without a record are swept by the next recovery, but a record whose saved bytes are gone
     * could never be finished, and would block the file for good.
     */
    private fun discard(record: JournalRecord) {
        try {
            val names = directory.names()
            if (record.journalName in names) {
                directory.delete(record.journalName)
                directory.sync()
            }
            for (name in listOf(record.patchName, record.stagedName, record.backupName)) {
                if (name in names) directory.delete(name)
            }
            directory.sync()
        } catch (_: Exception) {
            // TagRecovery.sweep() deletes whatever no open record needs.
        }
    }

    /**
     * A new write id, or null when the store already holds a file of that id: its files are named
     * `<id>.<kind>`, and a reused id would overwrite another save's journal and saved bytes.
     */
    private fun freshId(): String? {
        val id = newWriteId()
        require(id.isNotEmpty() && '.' !in id) { "a write id must be non-empty and hold no '.': $id" }
        return try {
            id.takeIf { directory.names().none { name -> name.substringBefore('.') == id } }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val CHANGED = "the file changed while it was being saved"
        const val ID_TAKEN = "the recovery store could not give this save a fresh id"
        const val STORE_MARGIN = 64L * 1024
        const val TARGET_MARGIN = 64L * 1024
        const val REWRITE_MARGIN = 16L * 1024 * 1024
    }
}
