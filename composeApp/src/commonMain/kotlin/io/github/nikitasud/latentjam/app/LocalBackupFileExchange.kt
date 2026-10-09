/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

internal const val LOCAL_BACKUP_MIME_TYPE: String = "application/vnd.latentjam.backup"
internal const val MAX_LOCAL_BACKUP_DOCUMENT_CHARS: Int = 64 * 1024 * 1024

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
    onExportResult: (LocalBackupFileResult<Unit>) -> Unit,
    onImportResult: (LocalBackupFileResult<String>) -> Unit,
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
