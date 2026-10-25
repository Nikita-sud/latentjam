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
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSTimeIntervalSince1970
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
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
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy

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
internal fun appSupportFile(name: String): String? {
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

/** Copies an [NSData] payload into a Kotlin [ByteArray]. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val output = ByteArray(size)
    output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return output
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
    val bytes = NSFileManager.defaultManager.contentsAtPath(path)?.toByteArray()
        ?: error("Could not read private history data")
    // A file whose bytes are not UTF-8 is damaged, but that is no reason to refuse every later
    // operation: decoding leniently (a malformed byte becomes U+FFFD) keeps every intact line —
    // the app writes ASCII, so damaged bytes are a short foreign stretch — and leaves clear() and
    // replace() able to repair the file. This is the iOS counterpart of Android's readLines(),
    // which never failed on an undecodable file either.
    return bytes.decodeToString()
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

    override suspend fun append(line: String): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        durableAppend(path, line + "\n", what = "private history data")
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

internal class FileSmartPressStore : SmartPressStore {
    override suspend fun read(): List<String> = withContext(Dispatchers.Default) {
        readLines(appSupportFile(FILE_NAME) ?: error("Application Support is unavailable"))
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        writeText(path, lines.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "smart_presses.txt"
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

/**
 * Appends [text] at the end of the file at [path], creating it if needed, and does not return
 * until the kernel has durably synchronized it — a real append via [NSFileHandle] rather than a
 * read-modify-write. [what] names the file in error messages.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun durableAppend(path: String, text: String, what: String) {
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(path)) {
        check(manager.createFileAtPath(path, NSData(), null)) { "Could not create $what" }
    }
    val handle = NSFileHandle.fileHandleForWritingAtPath(path)
        ?: error("Could not open $what for append")
    var primaryFailure: Throwable? = null
    try {
        requireFileOperation("Could not seek $what") { error ->
            handle.seekToEndReturningOffset(null, error)
        }
        requireFileOperation("Could not append $what") { error ->
            handle.writeData(text.encodeToByteArray().toNSData(), error)
        }
        // A record is not acknowledged until the kernel has durably synchronized it. Otherwise an
        // apparently successful write can disappear after a crash or power loss.
        requireFileOperation("Could not synchronize $what") { error ->
            handle.synchronizeAndReturnError(error)
        }
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        try {
            requireFileOperation("Could not close $what") { error ->
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

internal class FileForYouImpressionStore : ForYouImpressionStore {
    override suspend fun read(): List<String> = withContext(Dispatchers.Default) {
        readLines(appSupportFile(FILE_NAME) ?: error("Application Support is unavailable"))
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        // Ends with a line feed so an append can start straight after it.
        writeText(path, lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }

    override suspend fun append(lines: List<String>): Unit = withContext(Dispatchers.Default) {
        if (lines.isEmpty()) return@withContext
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        durableAppend(path, lines.joinToString(separator = "\n", postfix = "\n"), what = "For You offers")
    }

    private companion object {
        const val FILE_NAME = "foryou_impressions.txt"
    }
}

internal class FileFavoriteTimeStore : FavoriteTimeStore {
    override suspend fun read(): List<String> = withContext(Dispatchers.Default) {
        readLines(appSupportFile(FILE_NAME) ?: error("Application Support is unavailable"))
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        writeText(path, lines.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "favorite_times.txt"
    }
}

internal class FileFavoritesStore : FavoritesStore {
    override suspend fun read(): List<String> = withContext(Dispatchers.Default) {
        readLines(appSupportFile(FILE_NAME) ?: error("Application Support is unavailable"))
    }

    override suspend fun write(ids: List<String>): Unit = withContext(Dispatchers.Default) {
        val path = appSupportFile(FILE_NAME) ?: error("Application Support is unavailable")
        writeText(path, ids.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "favorites.txt"
    }
}

public actual fun listeningHistoryModule(): Module = module {
    single<HistoryStore> { FileHistoryStore() }
    single<ListeningHistory> { DefaultListeningHistory(store = get()) }
    single<RecentSearchStore> { FileRecentSearchStore() }
    single<RecentSearches> { DefaultRecentSearches(store = get()) }
    single<SmartExclusionStore> { FileSmartExclusionStore() }
    single { SmartExclusions(store = get()) }
    single<SmartPressStore> { FileSmartPressStore() }
    single { SmartPresses(store = get()) }
    single<FavoritesStore> { FileFavoritesStore() }
    single<Favorites> { DefaultFavorites(store = get()) }
    single<ForYouImpressionStore> { FileForYouImpressionStore() }
    single { ForYouImpressions(store = get()) }
    single<SmartLogStore> { FileSmartLogStore() }
    single { SmartImpressionLog(store = get()) }
    single<FavoriteTimeStore> { FileFavoriteTimeStore() }
    single { FavoriteTimes(store = get()) }
}

public actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/** The local zone and when it was read; one object, so a reader never pairs one with the other's age. */
private class LocalZone(val zone: NSTimeZone, val readAt: TimeSource.Monotonic.ValueTimeMark)

/**
 * The local zone, re-read at most once a second, as on Android. Statistics call [localTimePoint] for
 * every listen of the log; a calendar per call (`NSCalendar.currentCalendar` hands out a new one)
 * plus its hour component made a 200k-listen pass about 600k Objective-C allocations, while the
 * zone changes only when the listener travels or edits it.
 */
@Volatile private var cachedLocalZone: LocalZone? = null

public actual fun localTimePoint(epochMs: Long): LocalTimePoint {
    val zone = cachedLocalZone?.takeIf { it.readAt.elapsedNow() < 1.seconds }
        ?: LocalZone(NSTimeZone.localTimeZone, TimeSource.Monotonic.markNow()).also { cachedLocalZone = it }
    val date = NSDate(timeIntervalSinceReferenceDate = epochMs / 1000.0 - NSTimeIntervalSince1970)
    // The zone's offset at this instant, DST included, as the calendar applied it; the day and the
    // hour then follow arithmetically, exactly as the calendar's components did.
    val local = epochMs + zone.zone.secondsFromGMTForDate(date) * 1000L
    return LocalTimePoint(
        epochDay = local.floorDiv(86_400_000L),
        hourOfDay = (local.mod(86_400_000L) / 3_600_000L).toInt(),
    )
}
