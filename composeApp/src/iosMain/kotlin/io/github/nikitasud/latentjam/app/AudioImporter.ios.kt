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
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.darwin.NSObject

internal actual val audioImportAvailable: Boolean = true

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberAudioImporter(
    onResult: (AudioImportResult) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnResult = rememberUpdatedState(onResult)
    val delegate = remember {
        AudioPickerDelegate { urls ->
            scope.launch {
                val result = withContext(Dispatchers.Default) { copyIntoDocuments(urls) }
                currentOnResult.value(result)
            }
        }
    }

    return remember(delegate) {
        {
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeAudio),
                asCopy = true,
            ).apply {
                allowsMultipleSelection = true
                this.delegate = delegate
            }
            UIApplication.sharedApplication.keyWindow?.rootViewController
                ?.presentViewController(picker, true, null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class AudioPickerDelegate(
    private val onPicked: (List<NSURL>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onPicked(didPickDocumentsAtURLs.mapNotNull { it as? NSURL })
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun copyIntoDocuments(urls: List<NSURL>): AudioImportResult {
    val documents = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true,
    ).firstOrNull() as? String ?: return AudioImportResult(0, 0, urls.size)
    val manager = NSFileManager.defaultManager
    val destinationPath = "$documents/$IMPORTED_DIRECTORY"
    manager.createDirectoryAtPath(
        destinationPath,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val destinationDirectory = NSURL.fileURLWithPath(destinationPath, isDirectory = true)
    var imported = 0
    var skipped = 0
    var failed = 0

    urls.forEach { source ->
        val name = source.lastPathComponent ?: run {
            failed += 1
            return@forEach
        }
        val destination = destinationDirectory.URLByAppendingPathComponent(name) ?: run {
            failed += 1
            return@forEach
        }
        val destinationPath = destination.path
        if (destinationPath != null && manager.fileExistsAtPath(destinationPath)) {
            skipped += 1
            return@forEach
        }
        val copied = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            manager.copyItemAtURL(source, destination, error.ptr)
        }
        if (copied) imported += 1 else failed += 1
    }
    return AudioImportResult(imported, skipped, failed)
}

private const val IMPORTED_DIRECTORY = "Imported"
