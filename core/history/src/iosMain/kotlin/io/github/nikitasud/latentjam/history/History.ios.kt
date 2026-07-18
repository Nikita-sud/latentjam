/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS [HistoryStore] — in-memory STUB (history lost on process death) until
 * the real NSFileManager-backed log lands alongside the iOS playback backend.
 */
internal class InMemoryHistoryStore : HistoryStore {
    private val lines = mutableListOf<String>()
    override suspend fun append(line: String) { lines += line }
    override suspend fun readAll(): List<String> = lines.toList()
    override suspend fun clear() { lines.clear() }
}

internal class InMemoryRecentSearchStore : RecentSearchStore {
    private var queries: List<String> = emptyList()
    override suspend fun read(): List<String> = queries
    override suspend fun write(queries: List<String>) { this.queries = queries }
}

public actual fun listeningHistoryModule(): Module = module {
    single<HistoryStore> { InMemoryHistoryStore() }
    single<ListeningHistory> { DefaultListeningHistory(store = get()) }
    single<RecentSearchStore> { InMemoryRecentSearchStore() }
    single<RecentSearches> { DefaultRecentSearches(store = get()) }
}

public actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
