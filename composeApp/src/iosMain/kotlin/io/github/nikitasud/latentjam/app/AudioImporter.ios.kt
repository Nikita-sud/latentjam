/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.darwin.NSObject
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.errno
import platform.posix.pread
import platform.posix.close as posixClose
import platform.posix.open as posixOpen

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
        when (importOne(manager, destinationDirectory, source)) {
            ImportOutcome.IMPORTED -> imported += 1
            ImportOutcome.ALREADY_PRESENT -> skipped += 1
            ImportOutcome.FAILED -> failed += 1
        }
    }
    // Counts only: imported file names are user content and do not belong in device logs.
    println("IOS_IMPORT: imported=$imported skipped=$skipped failed=$failed")
    return AudioImportResult(imported, skipped, failed)
}

/** What one picked file did, i.e. which counter of [AudioImportResult] it feeds. */
internal enum class ImportOutcome { IMPORTED, ALREADY_PRESENT, FAILED }

/**
 * Copies one picked file into `Documents/Imported`.
 *
 * A name that is already taken is no longer an automatic skip, which used to lose files two ways.
 * A file that already holds exactly this source is still reported as skipped, but one that holds
 * anything else keeps its name while this source lands under a Finder-style " (2)" name.
 * A shorter matching prefix can be a complete trimmed MP3, so it never proves an interrupted import.
 * Even empty existing files are preserved; only the unique temporary copy made by this import may
 * be removed on failure.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun importOne(
    manager: NSFileManager,
    destinationDirectory: NSURL,
    source: NSURL,
): ImportOutcome {
    val sourcePath = source.path
    val name = source.lastPathComponent
    val sourceSize = sourcePath?.let { fileSize(manager, it) }
    if (sourcePath == null || name == null || sourceSize == null || sourceSize <= 0L) {
        println("IOS_IMPORT: picked item is not a readable, non-empty file")
        return ImportOutcome.FAILED
    }

    var destinationPath: String? = null
    for (attempt in 1..MAX_NAME_ATTEMPTS) {
        val candidate = destinationDirectory
            .URLByAppendingPathComponent(nameOfAttempt(name, attempt))
            ?.path
        if (candidate == null) break
        if (!manager.fileExistsAtPath(candidate)) {
            destinationPath = candidate
            break
        }
        val existingSize = fileSize(manager, candidate)
        when {
            // The complete file is already here: the import is a no-op, as it always was.
            existingSize == sourceSize && hasEqualPrefix(candidate, sourcePath, sourceSize) ->
                return ImportOutcome.ALREADY_PRESENT

            // A different file owns this name. Leave it alone and try " (2)", " (3)", ...
            else -> Unit
        }
    }

    val target = destinationPath
    if (target == null) {
        println("IOS_IMPORT: no free name for a picked file after $MAX_NAME_ATTEMPTS attempts")
        return ImportOutcome.FAILED
    }
    return copyAtomically(manager, source, target)
}

/**
 * Stages a complete copy beside the destination and moves it without replacing an existing file.
 * A unique temporary name keeps concurrent imports and remnants of earlier imports independent.
 * If another writer takes the destination after the name check, the move fails and preserves it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun copyAtomically(
    manager: NSFileManager,
    source: NSURL,
    destinationPath: String,
): ImportOutcome {
    val partialPath = "$destinationPath.${NSUUID().UUIDString}$PARTIAL_SUFFIX"
    var failure: String? = null
    val copied = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        val ok = manager.copyItemAtURL(source, NSURL.fileURLWithPath(partialPath), error.ptr)
        if (!ok) failure = error.value?.localizedDescription
        ok
    }
    if (!copied) {
        manager.removeItemAtPath(partialPath, null)
        println("IOS_IMPORT: copy failed: ${failure ?: "unknown error"}")
        return ImportOutcome.FAILED
    }
    if (!manager.moveItemAtPath(partialPath, destinationPath, null)) {
        manager.removeItemAtPath(partialPath, null)
        println("IOS_IMPORT: could not move the copy into place")
        return ImportOutcome.FAILED
    }
    return ImportOutcome.IMPORTED
}

/**
 * The [attempt]-th candidate name: the source name first, then Finder-style " (2)", " (3)"
 * insertions before the extension, which the library scanner keeps recognizing as audio.
 */
private fun nameOfAttempt(name: String, attempt: Int): String {
    if (attempt <= 1) return name
    val dot = name.lastIndexOf('.')
    return if (dot > 0) {
        name.substring(0, dot) + " ($attempt)" + name.substring(dot)
    } else {
        "$name ($attempt)"
    }
}

/** Size of [path] in bytes, or null when the item is absent or its attributes cannot be read. */
@OptIn(ExperimentalForeignApi::class)
private fun fileSize(manager: NSFileManager, path: String): Long? {
    val attributes = manager.attributesOfItemAtPath(path, null) ?: return null
    return (attributes.get(NSFileSize) as? NSNumber)?.longLongValue
}

/**
 * True when the first [byteCount] bytes of both files are equal. Both sides are read in bounded
 * chunks: an interrupted copy can be tens of megabytes, and so can a track whose name collides
 * with it. A file that cannot be read compares as different, which keeps the caller on the safe
 * "keep the existing file, import under another name" path.
 */
@OptIn(ExperimentalForeignApi::class)
private fun hasEqualPrefix(firstPath: String, secondPath: String, byteCount: Long): Boolean {
    if (byteCount <= 0L) return true
    val first = posixOpen(firstPath, O_RDONLY)
    if (first < 0) return false
    val second = posixOpen(secondPath, O_RDONLY)
    if (second < 0) {
        posixClose(first)
        return false
    }
    try {
        val firstChunk = ByteArray(COMPARE_CHUNK_BYTES)
        val secondChunk = ByteArray(COMPARE_CHUNK_BYTES)
        var offset = 0L
        while (offset < byteCount) {
            val length = minOf(COMPARE_CHUNK_BYTES.toLong(), byteCount - offset).toInt()
            if (!readExactly(first, firstChunk, length, offset)) return false
            if (!readExactly(second, secondChunk, length, offset)) return false
            for (index in 0 until length) {
                if (firstChunk[index] != secondChunk[index]) return false
            }
            offset += length
        }
        return true
    } finally {
        posixClose(first)
        posixClose(second)
    }
}

/** Reads [count] bytes at [offset], retrying [EINTR]; false on any short read. */
@OptIn(ExperimentalForeignApi::class)
private fun readExactly(fd: Int, buffer: ByteArray, count: Int, offset: Long): Boolean {
    buffer.usePinned { pinned ->
        var done = 0
        while (done < count) {
            val read = pread(fd, pinned.addressOf(done), (count - done).toULong(), offset + done)
            when {
                read > 0 -> done += read.toInt()
                read < 0 && errno == EINTR -> continue
                else -> return false
            }
        }
    }
    return true
}

private const val IMPORTED_DIRECTORY = "Imported"

/** Suffix of the in-flight copy: no audio extension matches it, so the scanner ignores it. */
private const val PARTIAL_SUFFIX = ".partial"

/** Names tried before a picked file is reported as failed instead of looping forever. */
private const val MAX_NAME_ATTEMPTS = 100

/** Bytes compared per read; bounds what a large collision or remnant costs in memory. */
private const val COMPARE_CHUNK_BYTES = 64 * 1024
