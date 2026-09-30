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
