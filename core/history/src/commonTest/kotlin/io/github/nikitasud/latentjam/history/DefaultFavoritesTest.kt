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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

    /**
     * Shaped like the platform stores, which write inside `withContext(Dispatchers.IO)` (iOS:
     * `Default`): it refuses to start for a caller that is already cancelled, and it can pause once
     * the list has reached the disk, which is where the hop back to the caller's dispatcher delivers
     * a cancellation.
     */
    private class PausingStore(
        var stored: List<String>,
    ) : FavoritesStore {
        var writes = 0
        var pauseNextWrite = false
        val landed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        override suspend fun read(): List<String> = stored
        override suspend fun write(ids: List<String>) {
            currentCoroutineContext().ensureActive()
            stored = ids
            writes++
            if (pauseNextWrite) {
                pauseNextWrite = false
                landed.complete(Unit)
                resume.await()
            }
        }
    }

    private val a = TrackId("a")
    private val b = TrackId("b")
    private val x = TrackId("x")

    /** Every edit that writes, each starting from the stored list `a, x` and changing it. */
    private val edits: List<Pair<String, suspend (Favorites) -> Unit>> = listOf(
        "toggle on" to { it.toggle(b) },
        "toggle off" to { it.toggle(a) },
        "addAll" to { it.addAll(listOf(b, TrackId("c"))) },
        "removeAll" to { it.removeAll(listOf(a)) },
        "replace" to { it.replace(listOf(b, a)) },
        "replaceIfUnchanged" to { it.replaceIfUnchanged(expected = listOf(a, x), replacement = listOf(x)) },
    )

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

    @Test
    fun aCancellationAfterTheWriteLandsStillPublishesItForEveryEdit() = runTest {
        val diverged = mutableListOf<String>()
        for ((name, edit) in edits) {
            val store = PausingStore(stored = listOf("a", "x"))
            val favorites = DefaultFavorites(store)
            favorites.all() // Loaded, so the edit reaches its write without another suspension.
            store.pauseNextWrite = true

            val caller = launch { edit(favorites) }
            store.landed.await()
            caller.cancel() // The screen that asked for the edit goes away while the bytes land.
            store.resume.complete(Unit)
            caller.join()

            val durable = DefaultFavorites(store).all() // What a restart would read.
            assertNotEquals(listOf(a, x), durable, "$name: the write reached the store")
            if (favorites.all() != durable) diverged += "$name: memory ${favorites.all()}, store $durable"
            // The next edit has to build on the stored list instead of overwriting it.
            val later = TrackId("later")
            favorites.toggle(later)
            if (store.stored != (listOf(later) + durable).map { it.value }) {
                diverged += "$name: the next edit stored ${store.stored} over $durable"
            }
        }
        assertEquals(emptyList(), diverged)
    }

    @Test
    fun aCallerCancelledBeforeTheWriteStartsChangesNeitherStoreNorMemory() = runTest {
        for ((name, edit) in edits) {
            val store = PausingStore(stored = listOf("a", "x"))
            val favorites = DefaultFavorites(store)
            favorites.all()

            val caller = launch {
                cancel()
                edit(favorites)
            }
            caller.join()

            assertEquals(0, store.writes, "$name: nothing is written for a cancelled caller")
            assertEquals(listOf("a", "x"), store.stored, name)
            assertEquals(listOf(a, x), favorites.all(), name)
        }
    }

    @Test
    fun aCallerCancelledWhileWaitingForAnotherEditChangesNothing() = runTest {
        for ((name, edit) in edits) {
            val store = PausingStore(stored = listOf("a", "x"))
            val favorites = DefaultFavorites(store)
            favorites.all()
            store.pauseNextWrite = true
            // Rewrites the same list, so only the waiting edit could change it; it holds the lock
            // while its write is paused.
            val first = launch { favorites.replace(listOf(a, x)) }
            store.landed.await()

            val waiting = launch { edit(favorites) }
            testScheduler.runCurrent()
            waiting.cancel()
            store.resume.complete(Unit)
            first.join()
            waiting.join()

            assertEquals(1, store.writes, "$name: only the edit that held the lock wrote")
            assertEquals(listOf("a", "x"), store.stored, name)
            assertEquals(listOf(a, x), favorites.all(), name)
        }
    }

    @Test
    fun aFailedWriteOfAnyEditLeavesTheListAsItWas() = runTest {
        for ((name, edit) in edits) {
            val store = object : FavoritesStore {
                override suspend fun read(): List<String> = listOf("a", "x")
                override suspend fun write(ids: List<String>) = error("disk full")
            }
            val favorites = DefaultFavorites(store)

            assertFailsWith<IllegalStateException>(name) { edit(favorites) }
            assertEquals(listOf(a, x), favorites.all(), name)
        }
    }
}
