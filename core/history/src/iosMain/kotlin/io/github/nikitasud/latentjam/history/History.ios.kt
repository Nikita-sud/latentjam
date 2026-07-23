/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
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
import platform.Foundation.closeFile
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeData
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
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return emptyList()
    val text = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, error.ptr)
    } ?: return emptyList()
    // A trailing newline would otherwise yield a phantom empty final record.
    return text.split("\n").dropLastWhile { it.isEmpty() }
}

private fun writeText(path: String, text: String) {
    check(text.encodeToByteArray().toNSData().writeToFile(path, true)) {
        "Could not write private history data"
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

    override suspend fun append(line: String): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: return@withContext
        val handle = NSFileHandle.fileHandleForWritingAtPath(path)
        if (handle == null) {
            // Nothing to open yet — this is the first event on this device.
            writeText(path, line + "\n")
            return@withContext
        }
        handle.seekToEndOfFile()
        handle.writeData((line + "\n").encodeToByteArray().toNSData())
        handle.closeFile()
    }

    override suspend fun readAll(): List<String> = withContext(Dispatchers.Default) {
        appSupportFile(FILE_NAME)?.let(::readLines) ?: emptyList()
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
        appSupportFile(FILE_NAME)?.let(::readLines) ?: emptyList()
    }

    override suspend fun write(queries: List<String>): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        writeText(path, queries.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "recent_searches.txt"
    }
}

internal class FileSmartExclusionStore : SmartExclusionStore {
    override suspend fun read(): List<String> = withContext(Dispatchers.Default) {
        appSupportFile(FILE_NAME)?.let(::readLines) ?: emptyList()
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
