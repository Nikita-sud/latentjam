/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun addAllHeartsTheNewOnesAsTheNewestInTheGivenOrderWithOneWrite() = runTest {
        val store = FakeStore(stored = listOf("a", "b"))
        val favorites = DefaultFavorites(store)
        val x = TrackId("x")
        val y = TrackId("y")
        val z = TrackId("z")

        // b is already a favourite and keeps its place; y repeats and counts once.
        val change = favorites.addAll(listOf(x, b, y, z, y))

        assertEquals(listOf(x, y, z, a, b), favorites.all())
        assertEquals(listOf("x", "y", "z", "a", "b"), store.stored)
        assertEquals(1, store.writes, "one durable write for the whole batch")
        assertEquals(FavoritesChange(before = listOf(a, b), after = listOf(x, y, z, a, b)), change)
        assertEquals(3, change.addedCount)
        assertTrue(change.changed)
    }

    @Test
    fun addAllOfFavouritesOnlyWritesNothing() = runTest {
        val store = FakeStore(stored = listOf("a", "b"))
        val favorites = DefaultFavorites(store)

        val change = favorites.addAll(listOf(b, a, b))

        assertFalse(change.changed)
        assertEquals(0, change.addedCount)
        assertEquals(listOf(a, b), favorites.all())
        assertEquals(0, store.writes)
        assertFalse(favorites.addAll(emptyList()).changed)
        assertEquals(0, store.writes)
    }

    @Test
    fun aBulkHeartIsAFavouriteLikeAToggledOne() = runTest {
        val favorites = DefaultFavorites(FakeStore())
        favorites.addAll(listOf(a))
        assertTrue(favorites.contains(a))
        // The single heart treats it as hearted: the next toggle removes it.
        assertFalse(favorites.toggle(a))
        assertEquals(emptyList(), favorites.all())
    }

    @Test
    fun undoOfAddAllRestoresTheExactPreviousList() = runTest {
        val store = FakeStore(stored = listOf("a", "b"))
        val favorites = DefaultFavorites(store)
        val change = favorites.addAll((1..400).map { TrackId("t$it") } + b)
        assertEquals(402, favorites.all().size)

        assertTrue(favorites.replaceIfUnchanged(expected = change.after, replacement = change.before))

        assertEquals(listOf(a, b), favorites.all())
        assertEquals(listOf("a", "b"), store.stored)
        assertEquals(2, store.writes)
    }

    @Test
    fun undoOfAddAllStepsBackForANewerHeart() = runTest {
        val favorites = DefaultFavorites(FakeStore(stored = listOf("a")))
        val change = favorites.addAll(listOf(b))
        val newer = TrackId("newer")
        favorites.toggle(newer)

        assertFalse(favorites.replaceIfUnchanged(expected = change.after, replacement = change.before))
        assertEquals(listOf(newer, b, a), favorites.all())
    }

    @Test
    fun removeAllTakesTheTracksOutWithOneWriteAndUndoPutsThemBackInPlace() = runTest {
        val store = FakeStore(stored = listOf("a", "x", "b", "y"))
        val favorites = DefaultFavorites(store)

        val change = favorites.removeAll(setOf(TrackId("x"), TrackId("y"), TrackId("missing")))

        assertEquals(listOf(a, b), favorites.all())
        assertEquals(2, change.removedCount)
        assertEquals(1, store.writes)
        assertFalse(favorites.removeAll(listOf(TrackId("missing"))).changed)
        assertEquals(1, store.writes)

        assertTrue(favorites.replaceIfUnchanged(expected = change.after, replacement = change.before))
        assertEquals(listOf("a", "x", "b", "y"), store.stored)
    }

    @Test
    fun aFailedBulkWriteLeavesTheListAsItWas() = runTest {
        val store = object : FavoritesStore {
            override suspend fun read(): List<String> = listOf("a")
            override suspend fun write(ids: List<String>) = error("disk full")
        }
        val favorites = DefaultFavorites(store)

        assertFailsWith<IllegalStateException> { favorites.addAll(listOf(b)) }
        assertFailsWith<IllegalStateException> { favorites.removeAll(listOf(a)) }
        assertEquals(listOf(a), favorites.all())
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
