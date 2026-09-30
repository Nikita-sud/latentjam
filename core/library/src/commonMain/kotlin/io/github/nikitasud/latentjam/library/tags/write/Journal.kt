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

    /**
     * The writer found its own patch wrong and is putting the saved ranges back. Recovery finishes
     * the roll-back, unless the file has since been changed by someone else.
     */
    ROLLING_BACK,

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

    public val finished: Boolean
        get() = this != PATCH_PREPARED && this != ROLLING_BACK && this != REPLACE_PREPARED && this != REPLACING
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
        // An empty file may be the leftover of a first append that failed before its entry was synced.
        val firstRecord = existing == null || existing.length == 0L
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
        if (firstRecord) directory.sync()
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
        // A last line that lost only its line end still checks, and counts: the next append ends
        // that line, and the journal must read the same before and after it does.
        if (start < bytes.size) decode(bytes, start, bytes.size)?.takeIf { it.writeId == writeId }?.let { latest = it }
        return latest
    }

    /** Every save that has not reached a finished state, in name order. Throws when the store cannot be listed. */
    public fun open(): List<JournalRecord> = openAmong(directory.names())

    /** Like [open], over a listing the caller already holds ([names] of the store). */
    internal fun openAmong(names: List<String>): List<JournalRecord> = names
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

        // Exception only: a PowerLoss (an Error in the crash tests) must never be swallowed here.
        fun decode(bytes: ByteArray, start: Int, end: Int): JournalRecord? = try {
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
        } catch (_: Exception) {
            null
        }

        private fun escape(text: String): String =
            text.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")

        private fun unescape(text: String): String =
            text.replace("%0D", "\r").replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")
    }
}
