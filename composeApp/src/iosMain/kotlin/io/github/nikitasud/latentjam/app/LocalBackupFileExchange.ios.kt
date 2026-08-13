/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToEndOfFile
import platform.Foundation.seekToFileOffset
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.posix.memcpy

private enum class PickerPurpose { IMPORT, EXPORT }

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberLocalBackupFileExchange(
    // The document picker works by file name/extension; MIME filtering has no iOS equivalent
    // here, so both parameters are accepted for the shared contract and intentionally unused.
    exportMimeType: String,
    importMimeTypes: List<String>,
    onExportResult: (LocalBackupFileResult<Unit>) -> Unit,
    onImportResult: (LocalBackupFileResult<String>) -> Unit,
): LocalBackupFileExchange {
    val scope = rememberCoroutineScope()
    val currentExportResult = rememberUpdatedState(onExportResult)
    val currentImportResult = rememberUpdatedState(onImportResult)
    val delegate = remember {
        BackupDocumentDelegate(
            onPicked = { purpose, urls ->
                when (purpose) {
                    PickerPurpose.EXPORT -> currentExportResult.value(
                        if (urls.isEmpty()) LocalBackupFileResult.Cancelled
                        else LocalBackupFileResult.Success(Unit),
                    )
                    PickerPurpose.IMPORT -> {
                        val url = urls.firstOrNull()
                        if (url == null) {
                            currentImportResult.value(LocalBackupFileResult.Cancelled)
                        } else {
                            scope.launch {
                                val result = withContext(Dispatchers.Default) { readDocument(url) }
                                currentImportResult.value(result)
                            }
                        }
                    }
                }
            },
            onCancelled = { purpose ->
                when (purpose) {
                    PickerPurpose.IMPORT -> currentImportResult.value(LocalBackupFileResult.Cancelled)
                    PickerPurpose.EXPORT -> currentExportResult.value(LocalBackupFileResult.Cancelled)
                }
            },
        )
    }

    return remember(delegate) {
        LocalBackupFileExchange(
            export = { encoded, suggestedName ->
                if (!delegate.begin(PickerPurpose.EXPORT)) {
                    currentExportResult.value(LocalBackupFileResult.Failure("Another document picker is open"))
                } else scope.launch {
                    val prepared = withContext(Dispatchers.Default) {
                        prepareExport(encoded, normalizedBackupFileName(suggestedName))
                    }
                    when (prepared) {
                        is LocalBackupFileResult.Failure -> {
                            delegate.abort()
                            currentExportResult.value(prepared)
                        }
                        LocalBackupFileResult.Cancelled -> {
                            delegate.abort()
                            currentExportResult.value(LocalBackupFileResult.Cancelled)
                        }
                        is LocalBackupFileResult.Success -> {
                            val presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
                            if (presenter == null) {
                                delegate.abort(prepared.value)
                                currentExportResult.value(LocalBackupFileResult.Failure("No active window"))
                            } else {
                                delegate.attachExport(prepared.value)
                                val picker = UIDocumentPickerViewController(
                                    forExportingURLs = listOf(prepared.value),
                                    asCopy = true,
                                ).apply { this.delegate = delegate }
                                presenter.presentViewController(picker, true, null)
                            }
                        }
                    }
                }
            },
            import = {
                if (!delegate.begin(PickerPurpose.IMPORT)) {
                    currentImportResult.value(LocalBackupFileResult.Failure("Another document picker is open"))
                } else {
                    val presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
                    if (presenter == null) {
                        delegate.abort()
                        currentImportResult.value(LocalBackupFileResult.Failure("No active window"))
                    } else {
                        val picker = UIDocumentPickerViewController(
                            forOpeningContentTypes = listOf(UTTypeData),
                            asCopy = true,
                        ).apply {
                            allowsMultipleSelection = false
                            this.delegate = delegate
                        }
                        presenter.presentViewController(picker, true, null)
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private class BackupDocumentDelegate(
    private val onPicked: (PickerPurpose, List<NSURL>) -> Unit,
    private val onCancelled: (PickerPurpose) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    private var purpose: PickerPurpose = PickerPurpose.IMPORT
    private var busy: Boolean = false
    private var pendingExportUrl: NSURL? = null

    fun begin(newPurpose: PickerPurpose): Boolean {
        if (busy) return false
        busy = true
        purpose = newPurpose
        return true
    }

    fun attachExport(url: NSURL) {
        check(busy && purpose == PickerPurpose.EXPORT && pendingExportUrl == null)
        pendingExportUrl = url
    }

    fun abort(unattachedExportUrl: NSURL? = null) {
        unattachedExportUrl?.let(::discardTemporaryBackup)
        finish()
    }

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val completedPurpose = purpose
        val urls = didPickDocumentsAtURLs.mapNotNull { it as? NSURL }
        finish()
        onPicked(completedPurpose, urls)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        val cancelledPurpose = purpose
        finish()
        onCancelled(cancelledPurpose)
    }

    private fun finish() {
        pendingExportUrl?.let(::discardTemporaryBackup)
        pendingExportUrl = null
        busy = false
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun prepareExport(
    encoded: String,
    fileName: String,
): LocalBackupFileResult<NSURL> {
    val path = NSTemporaryDirectory().trimEnd('/') + "/" + fileName
    return runCatching {
        check(encoded.length <= MAX_LOCAL_BACKUP_DOCUMENT_CHARS) { "Backup exceeds the supported size" }
        check(encoded.encodeToByteArray().toNSData().writeToFile(path, true)) {
            "Could not prepare backup document"
        }
        NSURL.fileURLWithPath(path)
    }.fold(
        onSuccess = { LocalBackupFileResult.Success(it) },
        onFailure = {
            discardTemporaryBackup(path)
            LocalBackupFileResult.Failure(it.message ?: "Could not export backup")
        },
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun discardTemporaryBackup(url: NSURL) {
    url.path?.let(::discardTemporaryBackup)
}

@OptIn(ExperimentalForeignApi::class)
private fun discardTemporaryBackup(path: String) {
    val manager = NSFileManager.defaultManager
    if (manager.fileExistsAtPath(path)) manager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
private fun readDocument(url: NSURL): LocalBackupFileResult<String> = runCatching {
    val path = url.path ?: error("The selected document cannot be opened")
    val handle = NSFileHandle.fileHandleForReadingAtPath(path)
        ?: error("The selected document cannot be opened")
    val data = try {
        // `NSData.dataWithContentsOfURL` reads the entire selection before its length is known. A
        // malicious or accidental multi-gigabyte file could therefore exhaust memory before the
        // 64 MiB policy check ran. Preflight the opened file and still read at most limit+1 bytes,
        // so a replacement/growth race cannot bypass the bound between the two operations.
        check(handle.seekToEndOfFile() <= MAX_LOCAL_BACKUP_DOCUMENT_CHARS.toULong()) {
            "Backup exceeds the supported size"
        }
        handle.seekToFileOffset(0uL)
        handle.readDataOfLength(MAX_LOCAL_BACKUP_DOCUMENT_CHARS.toULong() + 1uL)
    } finally {
        handle.closeFile()
    }
    check(data.length <= MAX_LOCAL_BACKUP_DOCUMENT_CHARS.toULong()) {
        "Backup exceeds the supported size"
    }
    data.toByteArray().decodeToString(throwOnInvalidSequence = true)
}.fold(
    onSuccess = { LocalBackupFileResult.Success(it) },
    onFailure = { LocalBackupFileResult.Failure(it.message ?: "Could not import backup") },
)

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { output ->
        output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
