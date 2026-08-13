/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

internal class DefaultFavoritesTest {

    private class FakeStore(
        var stored: List<String> = emptyList(),
    ) : FavoritesStore {
        var writes = 0
        override suspend fun read(): List<String> = stored
        override suspend fun write(ids: List<String>) {
            stored = ids
            writes++
        }
    }

    private val a = TrackId("a")
    private val b = TrackId("b")

    @Test
    fun toggleAddsThenRemovesAndPersistsEachStep() = runTest {
        val store = FakeStore()
        val favorites = DefaultFavorites(store)

        assertTrue(favorites.toggle(a), "first toggle makes it a favourite")
        assertEquals(listOf(a), favorites.all())
        assertEquals(listOf("a"), store.stored)

        assertFalse(favorites.toggle(a), "second toggle removes it")
        assertEquals(emptyList(), favorites.all())
        assertEquals(emptyList(), store.stored)
        assertEquals(2, store.writes)
    }

    @Test
    fun newestFavoriteComesFirst() = runTest {
        val favorites = DefaultFavorites(FakeStore())
        favorites.toggle(a)
        favorites.toggle(b)
        assertEquals(listOf(b, a), favorites.all())
    }

    @Test
    fun startsFromThePersistedListAndAnswersContains() = runTest {
        val favorites = DefaultFavorites(FakeStore(stored = listOf("a", "b")))
        assertEquals(listOf(a, b), favorites.all())
        assertTrue(favorites.contains(a))
        assertFalse(favorites.contains(TrackId("missing")))
    }

    @Test
    fun replaceOverwritesAndDeduplicates() = runTest {
        val store = FakeStore(stored = listOf("old"))
        val favorites = DefaultFavorites(store)
        favorites.replace(listOf(a, b, a))
        assertEquals(listOf(a, b), favorites.all())
        assertEquals(listOf("a", "b"), store.stored)
    }

    @Test
    fun compareAndSetPreservesANewerFavoriteEdit() = runTest {
        val store = FakeStore(stored = listOf("a"))
        val favorites = DefaultFavorites(store)
        val snapshot = favorites.all()
        favorites.toggle(b)

        assertFalse(favorites.replaceIfUnchanged(snapshot, listOf(TrackId("replacement"))))
        assertEquals(listOf(b, a), favorites.all())
        assertEquals(listOf("b", "a"), store.stored)
    }

    @Test
    fun compareAndSetPublishesTheExactOrderedReplacement() = runTest {
        val store = FakeStore(stored = listOf("a", "b"))
        val favorites = DefaultFavorites(store)

        assertTrue(favorites.replaceIfUnchanged(listOf(a, b), listOf(b, a, b)))
        assertEquals(listOf(b, a), favorites.all())
        assertEquals(listOf("b", "a"), store.stored)
    }
}
