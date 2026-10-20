/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Append-only log file in the app's private files directory. Appends are one
 * line per event; a partially written trailing line (crash mid-write) is
 * simply skipped by the parser on the next load.
 *
 * Every change to the log's directory entry — the first append that creates the file, the atomic
 * replacement, and clearing the log — is followed by a sync of that directory, so the newest state
 * is on storage rather than in a cache when the device loses power.
 */
internal class FileHistoryStore(context: Context) : HistoryStore {

    private val file = File(context.filesDir, FILE_NAME)

    override suspend fun append(line: String): Unit = withContext(Dispatchers.IO) {
        // A crash mid-append leaves a torn tail with no line feed. Appending straight after it
        // glues the next listening to that fragment, and the parser skips the whole glued record —
        // losing the new event along with the fragment. Terminating the fragment first keeps the
        // damage to the fragment itself.
        val separator = if (file.endsInsideALine()) "\n" else ""
        file.durableAppendText(separator + line + "\n")
    }

    override suspend fun readAll(): List<String> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else file.readLines()
    }

    override suspend fun replaceAll(lines: List<String>): Unit = withContext(Dispatchers.IO) {
        file.atomicReplaceText(
            lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"),
        )
    }

    /**
     * Deletes the log and makes the deletion durable.
     *
     * Removing the bytes is only half of the change: the directory entry that named the file has to
     * reach storage as well. Without that sync a power cut right after "clear history" could bring
     * the deleted log back and show the user the listening history they just erased.
     */
    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        check(file.delete()) { "Could not clear listening history" }
        syncDirectory(file.parentFile)
    }

    private companion object {
        const val FILE_NAME = "listening_history.log"
    }
}

/**
 * fsync of the directory itself, so the log's removal survives a power cut (API 21+).
 *
 * Mirrors the helper that guards the atomic replacement in AtomicFile.android.kt; that one is
 * private to its file, so the few lines are repeated here. Best effort on purpose: the log is
 * already gone, and a filesystem that refuses to open or sync a directory must not fail an
 * otherwise successful clear.
 */
private fun syncDirectory(directory: File?) {
    val target = directory ?: return
    try {
        val descriptor = Os.open(target.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    } catch (_: Exception) {
        // Nothing left to do: the deletion itself already happened.
    }
}

/**
 * True when the file ends inside a line: its last byte is not a line feed.
 *
 * A missing or empty file ends on a line boundary by definition, and so does a file whose last
 * append completed — only a write interrupted by a crash leaves a partial record behind.
 */
private fun File.endsInsideALine(): Boolean {
    if (!exists() || length() == 0L) return false
    return RandomAccessFile(this, "r").use { file ->
        file.seek(length() - 1)
        file.read() != '\n'.code
    }
}

/** Whole-file rewrite; the list is a dozen short lines at most. */
internal class FileRecentSearchStore internal constructor(private val file: File) : RecentSearchStore {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else RecentSearchFileCodec.decode(file.readText())
    }

    override suspend fun write(queries: List<String>): Unit = withContext(Dispatchers.IO) {
        file.atomicReplaceText(RecentSearchFileCodec.encode(queries))
    }

    private companion object {
        const val FILE_NAME = "recent_searches.txt"
    }
}

internal class FileSmartExclusionStore(context: Context) : SmartExclusionStore {
    private val file = File(context.filesDir, FILE_NAME)

    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        if (file.exists()) file.readLines() else emptyList()
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.IO) {
        file.atomicReplaceText(lines.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "smart_exclusions.txt"
    }
}

internal class FileSmartPressStore(context: Context) : SmartPressStore {
    private val file = File(context.filesDir, FILE_NAME)

    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        if (file.exists()) file.readLines() else emptyList()
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.IO) {
        file.atomicReplaceText(lines.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "smart_presses.txt"
    }
}

internal class FileForYouImpressionStore(context: Context) : ForYouImpressionStore {
    private val file = File(context.filesDir, FILE_NAME)

    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        if (file.exists()) file.readLines() else emptyList()
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.IO) {
        file.atomicReplaceText(lines.joinToString("\n"))
    }

    override suspend fun append(lines: List<String>): Unit = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext
        // The rewrite above leaves no final line feed; neither may a torn append. Either way the
        // new lines must start on a line of their own.
        val separator = if (file.endsInsideALine()) "\n" else ""
        file.durableAppendText(separator + lines.joinToString("\n") + "\n")
    }

    private companion object {
        const val FILE_NAME = "foryou_impressions.txt"
    }
}

internal class FileFavoriteTimeStore internal constructor(private val file: File) : FavoriteTimeStore {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        if (file.exists()) file.readLines() else emptyList()
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.IO) {
        file.atomicReplaceText(lines.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "favorite_times.txt"
    }
}

internal class FileFavoritesStore internal constructor(private val file: File) : FavoritesStore {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        if (file.exists()) file.readLines() else emptyList()
    }

    override suspend fun write(ids: List<String>): Unit = withContext(Dispatchers.IO) {
        file.atomicReplaceText(ids.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "favorites.txt"
    }
}

public actual fun listeningHistoryModule(): Module = module {
    single<HistoryStore> { FileHistoryStore(context = get()) }
    single<ListeningHistory> { DefaultListeningHistory(store = get()) }
    single<RecentSearchStore> { FileRecentSearchStore(context = get()) }
    single<RecentSearches> { DefaultRecentSearches(store = get()) }
    single<SmartExclusionStore> { FileSmartExclusionStore(context = get()) }
    single { SmartExclusions(store = get()) }
    single<SmartPressStore> { FileSmartPressStore(context = get()) }
    single { SmartPresses(store = get()) }
    single<FavoritesStore> { FileFavoritesStore(context = get()) }
    single<Favorites> { DefaultFavorites(store = get()) }
    single<ForYouImpressionStore> { FileForYouImpressionStore(context = get()) }
    single { ForYouImpressions(store = get()) }
    single<SmartLogStore> { FileSmartLogStore(context = get()) }
    single { SmartImpressionLog(store = get()) }
    single<FavoriteTimeStore> { FileFavoriteTimeStore(context = get()) }
    single { FavoriteTimes(store = get()) }
}

public actual fun epochMillis(): Long = System.currentTimeMillis()

/**
 * The default zone, re-read at most once a second. Statistics call [localTimePoint] for every listen
 * of the log; a new Calendar per call made each pass allocate ~650 bytes per listen (130 MB of garbage
 * for 200k listens), while the zone changes only when the listener travels or edits it.
 */
@Volatile private var cachedZone: java.util.TimeZone? = null

@Volatile private var cachedZoneAtNs = 0L

public actual fun localTimePoint(epochMs: Long): LocalTimePoint {
    val nowNs = System.nanoTime()
    val zone = cachedZone?.takeIf { nowNs - cachedZoneAtNs < 1_000_000_000L }
        ?: java.util.TimeZone.getDefault().also {
            cachedZone = it
            cachedZoneAtNs = nowNs
        }
    // ZONE_OFFSET + DST_OFFSET at this instant, as Calendar computed it.
    val local = epochMs + zone.getOffset(epochMs)
    return LocalTimePoint(
        epochDay = local.floorDiv(86_400_000L),
        hourOfDay = (local.mod(86_400_000L) / 3_600_000L).toInt(),
    )
}
