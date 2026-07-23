/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Append-only log file in the app's private files directory. Appends are one
 * line per event; a partially written trailing line (crash mid-write) is
 * simply skipped by the parser on the next load.
 */
internal class FileHistoryStore(context: Context) : HistoryStore {

    private val file = File(context.filesDir, FILE_NAME)

    override suspend fun append(line: String): Unit = withContext(Dispatchers.IO) {
        file.appendText(line + "\n")
    }

    override suspend fun readAll(): List<String> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else file.readLines()
    }

    override suspend fun replaceAll(lines: List<String>): Unit = withContext(Dispatchers.IO) {
        file.writeText(lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        check(!file.exists() || file.delete()) { "Could not clear listening history" }
    }

    private companion object {
        const val FILE_NAME = "listening_history.log"
    }
}

/** Whole-file rewrite; the list is a dozen short lines at most. */
internal class FileRecentSearchStore(context: Context) : RecentSearchStore {

    private val file = File(context.filesDir, FILE_NAME)

    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else file.readLines()
    }

    override suspend fun write(queries: List<String>): Unit = withContext(Dispatchers.IO) {
        file.writeText(queries.joinToString("\n"))
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
        file.writeText(lines.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "smart_exclusions.txt"
    }
}

public actual fun listeningHistoryModule(): Module = module {
    single<HistoryStore> { FileHistoryStore(context = get()) }
    single<ListeningHistory> { DefaultListeningHistory(store = get()) }
    single<RecentSearchStore> { FileRecentSearchStore(context = get()) }
    single<RecentSearches> { DefaultRecentSearches(store = get()) }
    single<SmartExclusionStore> { FileSmartExclusionStore(context = get()) }
    single { SmartExclusions(store = get()) }
}

public actual fun epochMillis(): Long = System.currentTimeMillis()
