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

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        file.delete()
    }

    private companion object {
        const val FILE_NAME = "listening_history.log"
    }
}

public actual fun listeningHistoryModule(): Module = module {
    single<HistoryStore> { FileHistoryStore(context = get()) }
    single<ListeningHistory> { DefaultListeningHistory(store = get()) }
}

public actual fun epochMillis(): Long = System.currentTimeMillis()
