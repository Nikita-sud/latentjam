/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fopen
import platform.posix.fread

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberListenBrainzExportPicker(
    onResult: (LocalBackupFileResult<PickedExport>) -> Unit,
): () -> Unit {
    val current = rememberUpdatedState(onResult)
    // UIDocumentPickerViewController holds its delegate weakly; the composition keeps it alive.
    val delegate = remember {
        ExportPickerDelegate { url ->
            val path = url?.path
            current.value(
                if (path == null) {
                    LocalBackupFileResult.Cancelled
                } else {
                    LocalBackupFileResult.Success(
                        PickedExport(
                            opener = { openExport(path) },
                            // The picker handed over a private copy; it is ours to remove.
                            releaser = {
                                val manager = NSFileManager.defaultManager
                                if (manager.fileExistsAtPath(path)) manager.removeItemAtPath(path, null)
                            },
                        ),
                    )
                },
            )
        }
    }
    return remember(delegate) {
        {
            val presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
            if (presenter == null) {
                current.value(LocalBackupFileResult.Failure("No active window"))
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
    }
}

private class ExportPickerDelegate(
    private val onPicked: (NSURL?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.firstNotNullOfOrNull { it as? NSURL })
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onPicked(null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun openExport(path: String): ExportByteSource {
    val file = fopen(path, "rb") ?: error("The selected document cannot be opened")
    return object : ExportByteSource {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = buffer.usePinned { pinned ->
                fread(pinned.addressOf(offset), 1u, length.toULong(), file).toInt()
            }
            if (count > 0) return count
            check(ferror(file) == 0) { "Could not read the selected document" }
            return -1
        }

        override fun close() {
            fclose(file)
        }
    }
}
