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
