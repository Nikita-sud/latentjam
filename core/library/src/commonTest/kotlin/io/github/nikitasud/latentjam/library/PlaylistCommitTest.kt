/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PlaylistCommitTest {
    @Test
    fun cancellingAfterAnyDurableEditDoesNotLeaveAStaleCache() = runTest {
        val mutations: List<Pair<String, suspend (DefaultPlaylists) -> Unit>> = listOf(
            "create" to { it.create("New") },
            "rename" to { it.rename("first", "Renamed") },
            "delete" to { it.delete("first") },
            "move" to { it.move("first", 1) },
            "SMART" to { it.toggleIncludeInSmart("first") },
            "add" to { it.addTracks("first", listOf(TrackId("new"))) },
            "remove" to { it.removeTrack("first", TrackId("one")) },
            "undo" to {
                it.replaceTracksIfUnchanged("first", listOf(TrackId("one")), listOf(TrackId("new")))
            },
            "restore" to { it.replaceAll(listOf(Playlist("restored", "Restored"))) },
        )
        for ((name, mutate) in mutations) {
            val committed = CompletableDeferred<Unit>()
            val returnFromWrite = CompletableDeferred<Unit>()
            var lines = listOf(
                Playlist("first", "Mix", listOf("one"), customArtworkRef = "cover.jpg"),
                Playlist("second", "Other"),
            ).map(PlaylistSerializer::serialize)
            var pause = true
            val store = object : PlaylistStore {
                override suspend fun read(): List<String> = lines
                override suspend fun write(replacement: List<String>) {
                    lines = replacement
                    if (pause) {
                        committed.complete(Unit)
                        returnFromWrite.await()
                    }
                }
            }
            val playlists = DefaultPlaylists(store)
            val caller = launch { mutate(playlists) }
            committed.await()
            caller.cancel()
            returnFromWrite.complete(Unit)
            caller.join()

            val saved = DefaultPlaylists(store).all()
            assertEquals(saved, playlists.all(), "$name must publish its durable result")
            pause = false
            playlists.setArtwork(saved.first().id, "later.jpg")
            assertEquals(
                saved.mapIndexed { index, item -> if (index == 0) item.copy(customArtworkRef = "later.jpg") else item },
                DefaultPlaylists(store).all(),
                "$name must survive the next edit",
            )
        }
    }

    @Test
    fun blankMembershipDoesNotAppearOnlyUntilNextRestart() = runTest {
        var lines = emptyList<String>()
        val store = object : PlaylistStore {
            override suspend fun read(): List<String> = lines
            override suspend fun write(replacement: List<String>) { lines = replacement }
        }
        val playlists = DefaultPlaylists(store)
        val playlist = playlists.create("Mix")
        playlists.addTracks(playlist.id, listOf(TrackId(""), TrackId(" "), TrackId("one")))
        assertEquals(listOf("one"), playlists.all().single().trackIds)
        assertEquals(playlists.all(), DefaultPlaylists(store).all())
    }
}
