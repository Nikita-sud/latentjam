/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * Where the two kinds of iOS file live.
 *
 * [documents] is deliberately user-visible: the Info.plist sets
 * `UIFileSharingEnabled`, so it is the folder that appears under "On My
 * iPhone → LatentJam" in Files.app, and dropping music in there is how a track
 * gets into the library at all.
 *
 * [appSupport] is deliberately NOT: playlists, history and search state are
 * the app's bookkeeping, and surfacing them next to the user's music would
 * invite someone to "tidy up" a file the app depends on.
 */
internal object IosPaths {

    /** User-visible music folder. `null` only if the sandbox is malformed. */
    fun documents(): String? =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String

    /**
     * Private app-data folder, created on first use.
     *
     * Unlike Documents, Application Support is not guaranteed to exist in a
     * fresh container, so every caller would otherwise have to create it.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun appSupport(): String? {
        val base = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: return null
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(base)) {
            manager.createDirectoryAtPath(base, true, null, null)
        }
        // This directory contains listening-derived and app-private bookkeeping. Local backup is
        // explicit and user-controlled; silently copying these files into an OS backup would break
        // the app's on-device-only privacy contract. Returning null is privacy-preserving if the OS
        // refuses the exclusion flag: callers treat the private store as unavailable.
        val excluded = NSURL.fileURLWithPath(base, isDirectory = true)
            .setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
        if (!excluded) return null
        return base
    }

    /**
     * Regenerable derived data (extracted cover art), created on first use.
     *
     * Caches is the right home per Apple's guidance — it is excluded from
     * backup, and a purge under disk pressure costs only the next scan, which
     * re-extracts anything missing.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun caches(): String? {
        val base = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return null
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(base)) {
            manager.createDirectoryAtPath(base, true, null, null)
        }
        return base
    }

    /** Joins a directory and a file name without assuming a trailing slash. */
    fun child(directory: String, name: String): String =
        if (directory.endsWith("/")) directory + name else "$directory/$name"
}

/**
 * Reads a small UTF-8 text file, or `null` when it does not exist.
 *
 * Returns lines rather than the raw text because every caller here stores a
 * line-per-record log.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun readLinesOrEmpty(path: String): List<String> {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return emptyList()
    val text = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, error.ptr)
    } ?: error("Could not read private library data")
    // A trailing newline would otherwise yield a phantom empty final record.
    return text.split("\n").dropLastWhile { it.isEmpty() }
}

/**
 * Whole-file rewrite. Every caller here writes a handful of short lines.
 *
 * Routed through [NSData] rather than `NSString.create`, which would drag in
 * the `@BetaInteropApi` opt-in for no benefit at this size.
 */
internal fun writeText(path: String, text: String): Boolean =
    text.encodeToByteArray().toNSData().writeToFile(path, true)

/**
 * Appends one line.
 *
 * Read-modify-write rather than a real append handle: the history log is the
 * only caller, it appends once per finished track, and a file handle would
 * need explicit lifecycle management for no measurable gain at this size.
 */
internal fun appendLine(path: String, line: String) {
    val existing = readLinesOrEmpty(path)
    writeText(path, (existing + line).joinToString("\n"))
}

/** Copies an [NSData] payload into a Kotlin [ByteArray]. */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    // addressOf(0) throws on an empty array, hence the guard above.
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

/** Wraps a Kotlin [ByteArray] as [NSData] for the Foundation write APIs. */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }
}

/** File URL for a filesystem path, for the AVFoundation APIs that want one. */
internal fun fileUrl(path: String): NSURL = NSURL.fileURLWithPath(path)
