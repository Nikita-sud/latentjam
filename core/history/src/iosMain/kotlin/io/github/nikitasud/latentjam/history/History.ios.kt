/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * Path to a file in Application Support, creating the directory on first use.
 *
 * Deliberately not Documents: `UIFileSharingEnabled` exposes Documents in
 * Files.app so the user can drop music in, and the listening log is app
 * bookkeeping nobody should be invited to "tidy up". Duplicated from
 * :core:library's equivalent rather than shared — :core:history depends only
 * on :core:smart, and a module seam for fifteen lines of path handling would
 * cost more than it saves.
 */
@OptIn(ExperimentalForeignApi::class)
private fun appSupportFile(name: String): String? {
    val base = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory,
        NSUserDomainMask,
        true,
    ).firstOrNull() as? String ?: return null
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(base)) {
        manager.createDirectoryAtPath(base, true, null, null)
    }
    // History, searches and SMART exclusions are local-only. The explicit in-app backup flow is
    // the only path that should copy them off this device.
    val excluded = NSURL.fileURLWithPath(base, isDirectory = true)
        .setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    if (!excluded) return null
    return if (base.endsWith("/")) base + name else "$base/$name"
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    // addressOf(0) throws on an empty array, hence the guard.
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun readLines(path: String): List<String> {
    val text = readText(path)
    // A trailing newline would otherwise yield a phantom empty final record.
    return text.split("\n").dropLastWhile { it.isEmpty() }
}

@OptIn(ExperimentalForeignApi::class)
private fun readText(path: String): String {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return ""
    val text = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, error.ptr)
    } ?: error("Could not read private history data")
    return text
}

private fun writeText(path: String, text: String) {
    check(text.encodeToByteArray().toNSData().writeToFile(path, true)) {
        "Could not write private history data"
    }
}

/** Runs one error-returning NSFileHandle operation and preserves Foundation's diagnosis. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun requireFileOperation(
    fallbackMessage: String,
    operation: (CPointer<ObjCObjectVar<NSError?>>?) -> Boolean,
) {
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        check(operation(error.ptr)) {
            error.value?.localizedDescription?.let { "$fallbackMessage: $it" }
                ?: fallbackMessage
        }
    }
}

/**
 * Append-only log in the app's private directory, one line per event.
 *
 * A real append via [NSFileHandle] rather than read-modify-write: this file
 * gains a line for every track ever finished, so rewriting it in full each
 * time would make the cost of listening grow with how much you have listened.
 * A partially written trailing line (crash mid-write) is simply skipped by the
 * parser on the next load.
 */
internal class FileHistoryStore : HistoryStore {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun append(line: String): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) {
            check(manager.createFileAtPath(path, NSData(), null)) {
                "Could not create private history data"
            }
        }
        val handle = NSFileHandle.fileHandleForWritingAtPath(path)
            ?: error("Could not open private history data for append")
        var primaryFailure: Throwable? = null
        try {
            requireFileOperation("Could not seek private history data") { error ->
                handle.seekToEndReturningOffset(null, error)
            }
            requireFileOperation("Could not append private history data") { error ->
                handle.writeData((line + "\n").encodeToByteArray().toNSData(), error)
            }
            // A record is not acknowledged to DefaultListeningHistory until the kernel has
            // durably synchronized it. Otherwise an apparently successful play can disappear
            // after a crash or power loss while remaining visible in this process's aggregates.
            requireFileOperation("Could not synchronize private history data") { error ->
                handle.synchronizeAndReturnError(error)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                requireFileOperation("Could not close private history data") { error ->
                    handle.closeAndReturnError(error)
                }
            } catch (closeFailure: Throwable) {
                val original = primaryFailure
                if (original != null) {
                    original.addSuppressed(closeFailure)
                } else {
                    throw closeFailure
                }
            }
        }
    }

    override suspend fun readAll(): List<String> = withContext(Dispatchers.Default) {
        readLines(appSupportFile(FILE_NAME) ?: error("Application Support is unavailable"))
    }

    override suspend fun replaceAll(lines: List<String>): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        writeText(path, lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        val manager = NSFileManager.defaultManager
        check(!manager.fileExistsAtPath(path) || manager.removeItemAtPath(path, null)) {
            "Could not clear listening history"
        }
    }

    private companion object {
        const val FILE_NAME = "listening_history.log"
    }
}

/** Whole-file rewrite; the list is a dozen short lines at most. */
internal class FileRecentSearchStore : RecentSearchStore {

    override suspend fun read(): List<String> = withContext(Dispatchers.Default) {
        RecentSearchFileCodec.decode(
            readText(appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")),
        )
    }

    override suspend fun write(queries: List<String>): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        writeText(path, RecentSearchFileCodec.encode(queries))
    }

    private companion object {
        const val FILE_NAME = "recent_searches.txt"
    }
}

internal class FileSmartExclusionStore : SmartExclusionStore {
    override suspend fun read(): List<String> = withContext(Dispatchers.Default) {
        readLines(appSupportFile(FILE_NAME) ?: error("Application Support is unavailable"))
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        writeText(path, lines.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "smart_exclusions.txt"
    }
}

public actual fun listeningHistoryModule(): Module = module {
    single<HistoryStore> { FileHistoryStore() }
    single<ListeningHistory> { DefaultListeningHistory(store = get()) }
    single<RecentSearchStore> { FileRecentSearchStore() }
    single<RecentSearches> { DefaultRecentSearches(store = get()) }
    single<SmartExclusionStore> { FileSmartExclusionStore() }
    single { SmartExclusions(store = get()) }
}

public actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
