/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

internal const val LOCAL_BACKUP_MIME_TYPE: String = "application/vnd.latentjam.backup"
internal const val MAX_LOCAL_BACKUP_DOCUMENT_CHARS: Int = 64 * 1024 * 1024

/** Text read from the selected document, with its provider's file name when available. */
internal data class ImportedLocalDocument(
    val text: String,
    val displayName: String? = null,
)

/**
 * What an import picker returned: every document that could be read, in the order picked, and how
 * many of the picked ones could not (unreadable, not text, or past [ImportReadBudget]'s bound).
 */
internal data class ImportedLocalDocuments(
    val documents: List<ImportedLocalDocument>,
    val unreadableCount: Int = 0,
)

/**
 * Bounds one picker selection as a whole. Each document stays within
 * [MAX_LOCAL_BACKUP_DOCUMENT_CHARS], and so do all of them together: a multi-select over a music
 * folder must not decode every MP3 in it as text and hold them all. With [textOnly] a document
 * whose first bytes are binary (see [looksLikeText]) is refused before the rest of it is read.
 */
internal class ImportReadBudget(
    private val textOnly: Boolean,
    total: Int = MAX_LOCAL_BACKUP_DOCUMENT_CHARS,
) {
    var remaining: Int = total
        private set

    /** The most bytes the next document may hold. */
    val nextLimit: Int get() = minOf(MAX_LOCAL_BACKUP_DOCUMENT_CHARS, remaining)

    /** Refuses a document whose first bytes ([prefix], at most [SNIFF_BYTES]) are not text. */
    fun checkStart(prefix: ByteArray) {
        if (textOnly) check(looksLikeText(prefix)) { "The selected document is not a text file" }
    }

    /** Counts a whole document against the selection. */
    fun accept(bytes: ByteArray) {
        check(bytes.size <= nextLimit) { "The selected documents exceed the supported size" }
        checkStart(if (bytes.size <= SNIFF_BYTES) bytes else bytes.copyOf(SNIFF_BYTES))
        remaining -= bytes.size
    }

    companion object {
        /** How much of a document's start [looksLikeText] judges. */
        const val SNIFF_BYTES: Int = 1024
    }
}

/**
 * Whether a document starting with [prefix] can be a playlist: a UTF-16 byte order mark, or no NUL
 * byte and none of the audio, image or container signatures a music folder holds.
 */
internal fun looksLikeText(prefix: ByteArray): Boolean {
    fun at(offset: Int, signature: String) = prefix.size >= offset + signature.length &&
        signature.indices.all { prefix[offset + it].toInt() and 0xff == signature[it].code }
    fun byte(index: Int) = if (index < prefix.size) prefix[index].toInt() and 0xff else -1
    if (byte(0) == 0xff && byte(1) == 0xfe) return true
    if (byte(0) == 0xfe && byte(1) == 0xff) return true
    val binarySignatures = listOf("ID3", "fLaC", "OggS", "RIFF", "FORM", "MThd", "wvpk", "MAC ", "%PDF", "PK\u0003\u0004")
    if (binarySignatures.any { at(0, it) } || at(4, "ftyp")) return false
    // An MPEG audio frame without a tag, a JPEG, a PNG.
    if (byte(0) == 0xff && byte(1) >= 0 && byte(1) and 0xe0 == 0xe0) return false
    if (byte(0) == 0xff && byte(1) == 0xd8) return false
    if (byte(0) == 0x89 && at(1, "PNG")) return false
    return prefix.none { it == 0.toByte() }
}

/** System document-picker result; cancellation is normal and never reported as a failure. */
internal sealed interface LocalBackupFileResult<out T> {
    data class Success<T>(val value: T) : LocalBackupFileResult<T>
    data object Cancelled : LocalBackupFileResult<Nothing>
    data class Failure(val message: String) : LocalBackupFileResult<Nothing>
}

internal data class LocalBackupFileExchange(
    /**
     * Lets the listener pick a destination and writes the payload [encode] returns for it.
     *
     * [encode] runs off the main thread with the folder the document was created in, as an
     * absolute path, or null when the platform cannot tell (a cloud provider, a Downloads entry
     * without a path). Android calls it once the picker has returned, so a payload that names
     * other files can point at them from where it lands; iOS calls it with null before its picker
     * opens, because that picker exports a finished file. A failure in [encode] is reported like a
     * failed write.
     */
    val exportTo: (encode: (destinationDirectory: String?) -> String, suggestedName: String) -> Unit,
    val import: () -> Unit,
    /** Includes a platform picker or retained document IO after a configuration change. */
    val inProgress: Boolean = false,
) {
    /** Exports a payload that reads the same wherever it is saved, such as a local backup. */
    fun export(encoded: String, suggestedName: String) = exportTo({ encoded }, suggestedName)
}

/**
 * Remembers launchers for the platform's local Files/Documents UI.
 *
 * The picker owns the destination/source. LatentJam neither uploads the payload nor requests broad
 * storage permission. Callers still decide when to show confirmation and which sections to restore.
 */
@Composable
internal expect fun rememberLocalBackupFileExchange(
    /** MIME the export document is created under; pickers may also derive it from the name. */
    exportMimeType: String = LOCAL_BACKUP_MIME_TYPE,
    /** MIME filter the import picker offers; platforms without MIME filtering may ignore it. */
    importMimeTypes: List<String> = listOf(LOCAL_BACKUP_MIME_TYPE, "application/octet-stream"),
    /**
     * Lets the import picker select several documents. The result then lists every one that could
     * be read, in the order picked, and counts the rest; it fails only when none could. Without it
     * the list holds one. Several picked documents share one [ImportReadBudget], and one whose
     * first bytes are binary is skipped unread: a "select all" in a music folder offers its audio.
     */
    allowMultipleImport: Boolean = false,
    onExportResult: (LocalBackupFileResult<Unit>) -> Unit,
    onImportResult: (LocalBackupFileResult<ImportedLocalDocuments>) -> Unit,
): LocalBackupFileExchange

internal fun normalizedBackupFileName(suggestedName: String): String {
    val safe = suggestedName
        .trim()
        .replace('/', '-')
        .replace('\\', '-')
        .take(120)
        .ifEmpty { "latentjam-backup" }
    // A caller-supplied extension (an .m3u8 playlist, say) is respected; only extensionless
    // names — the backup flow's own "latentjam-backup-<millis>" — get the backup extension.
    return if ('.' in safe) safe else "$safe.$LOCAL_BACKUP_FILE_EXTENSION"
}
