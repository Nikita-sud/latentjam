/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class RecentSearchesTest {

    private class FakeStore(initial: List<String> = emptyList()) : RecentSearchStore {
        var queries: List<String> = initial
        override suspend fun read(): List<String> = queries
        override suspend fun write(queries: List<String>) { this.queries = queries }
    }

    @Test
    fun newestFirstAndDeduplicatedCaseInsensitively() = runTest {
        val searches = DefaultRecentSearches(FakeStore())
        searches.record("peggy")
        searches.record("aria")
        searches.record("PEGGY")
        assertContentEquals(listOf("PEGGY", "aria"), searches.recent())
    }

    @Test
    fun blankQueriesAreIgnored() = runTest {
        val searches = DefaultRecentSearches(FakeStore())
        searches.record("   ")
        searches.record("")
        assertTrue(searches.recent().isEmpty())
    }

    @Test
    fun queriesAreTrimmed() = runTest {
        val searches = DefaultRecentSearches(FakeStore())
        searches.record("  modern  ")
        assertEquals("modern", searches.recent().single())
    }

    @Test
    fun capIsEnforcedDroppingOldest() = runTest {
        val searches = DefaultRecentSearches(FakeStore(), cap = 3)
        listOf("a", "b", "c", "d").forEach { searches.record(it) }
        assertContentEquals(listOf("d", "c", "b"), searches.recent())
    }

    @Test
    fun removeForgetsOneQuery() = runTest {
        val searches = DefaultRecentSearches(FakeStore())
        searches.record("aria")
        searches.record("modern")
        searches.remove("ARIA")
        assertContentEquals(listOf("modern"), searches.recent())
    }

    @Test
    fun survivesReloadThroughTheStore() = runTest {
        val store = FakeStore()
        DefaultRecentSearches(store).record("nunta")
        assertContentEquals(listOf("nunta"), DefaultRecentSearches(store).recent())
    }

    @Test
    fun clearEmptiesTheList() = runTest {
        val store = FakeStore()
        val searches = DefaultRecentSearches(store)
        searches.record("aria")
        searches.clear()
        assertTrue(searches.recent().isEmpty())
        assertTrue(store.queries.isEmpty())
    }

    @Test
    fun clearKeepsInMemoryQueriesWhenPersistenceFails() = runTest {
        val store = object : RecentSearchStore {
            override suspend fun read(): List<String> = listOf("aria")
            override suspend fun write(queries: List<String>) {
                error("disk failure")
            }
        }
        val searches = DefaultRecentSearches(store)

        assertFailsWith<IllegalStateException> { searches.clear() }
        assertContentEquals(listOf("aria"), searches.recent())
    }

    @Test
    fun failedRecordDoesNotPublishAnUndurableQuery() = runTest {
        var failWrites = false
        val store = object : RecentSearchStore {
            override suspend fun read(): List<String> = listOf("aria")
            override suspend fun write(queries: List<String>) {
                if (failWrites) error("disk failure")
            }
        }
        val searches = DefaultRecentSearches(store)
        assertContentEquals(listOf("aria"), searches.recent())
        failWrites = true

        assertFailsWith<IllegalStateException> { searches.record("modern") }
        assertContentEquals(listOf("aria"), searches.recent())
    }

    @Test
    fun failedInitialReadIsRetried() = runTest {
        var reads = 0
        val store = object : RecentSearchStore {
            override suspend fun read(): List<String> {
                reads++
                if (reads == 1) error("temporary read failure")
                return listOf("aria")
            }

            override suspend fun write(queries: List<String>) = Unit
        }
        val searches = DefaultRecentSearches(store)

        assertFailsWith<IllegalStateException> { searches.recent() }
        assertContentEquals(listOf("aria"), searches.recent())
        assertEquals(2, reads)
    }

    @Test
    fun fileCodecRoundTripsNewlinesUnicodeAndControlCharacters() {
        val queries = listOf("first line\nsecond\tline", "КИНО 🚗\u0000")

        val encoded = RecentSearchFileCodec.encode(queries)

        assertContentEquals(queries, RecentSearchFileCodec.decode(encoded))
        assertTrue(encoded.startsWith("LATENTJAM-RECENT-SEARCHES\t1\n"))
        assertTrue("first line" !in encoded)
    }

    @Test
    fun fileCodecReadsLegacyOneQueryPerLineFiles() {
        assertContentEquals(
            listOf("aria", "modern", "КИНО"),
            RecentSearchFileCodec.decode("aria\r\nmodern\nКИНО\n"),
        )
    }

    @Test
    fun fileCodecRejectsCorruptVersionedRecords() {
        assertFailsWith<IllegalStateException> {
            RecentSearchFileCodec.decode("LATENTJAM-RECENT-SEARCHES\t1\nnot-hex\n")
        }
    }
}
