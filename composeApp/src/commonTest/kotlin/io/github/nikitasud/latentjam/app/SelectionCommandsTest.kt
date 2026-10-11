/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlaylistRemove
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_delete
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_from_playlist
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class SelectionCommandsTest {

    private fun track(id: String, audioUri: String? = "content://audio/$id") =
        TrackDescriptor(id = TrackId(id), title = "Song $id", audioUri = audioUri)

    private fun commands(
        selected: List<TrackDescriptor>,
        playlistId: String? = null,
        calls: MutableList<String> = mutableListOf(),
    ) = SelectionCommands(
        selected = selected,
        playlistId = playlistId,
        onPlay = { calls += "play" },
        onAdd = { calls += "add" },
        onFavorite = { calls += "favorite:$it" },
        onShare = { calls += "share" },
        onEditTags = { calls += "edit" },
        onRemove = { calls += "remove" },
    )

    @Test
    fun aUserPlaylistPageRemovesFromThePlaylistAndEveryOtherSurfaceDeletes() {
        assertEquals(SelectionDestructiveAction.REMOVE_FROM_PLAYLIST, selectionDestructiveAction("p1"))
        assertEquals(SelectionDestructiveAction.DELETE, selectionDestructiveAction(null))
        assertEquals(
            SelectionDestructiveAction.REMOVE_FROM_PLAYLIST,
            commands(listOf(track("a")), playlistId = "p1").destructive,
        )
        assertEquals(SelectionDestructiveAction.DELETE, commands(listOf(track("a"))).destructive)
    }

    @Test
    fun theOverflowNamesTheDestructiveActionItOffers() {
        assertSame(Res.string.action_delete, SelectionDestructiveAction.DELETE.label)
        assertSame(Res.string.action_remove_from_playlist, SelectionDestructiveAction.REMOVE_FROM_PLAYLIST.label)
        assertSame(Icons.Rounded.DeleteOutline, SelectionDestructiveAction.DELETE.icon)
        assertSame(Icons.Rounded.PlaylistRemove, SelectionDestructiveAction.REMOVE_FROM_PLAYLIST.icon)
    }

    @Test
    fun everyActionNeedsASelectedTrackAndSharingNeedsEveryFile() {
        val playable = commands(listOf(track("a"), track("b")))
        assertTrue(playable.canAct)
        assertTrue(playable.canShare)

        // One track without a file to hand over keeps Share off and leaves the rest on.
        val oneWithoutFile = commands(listOf(track("a"), track("b", audioUri = null)))
        assertTrue(oneWithoutFile.canAct)
        assertFalse(oneWithoutFile.canShare)
        assertFalse(canShareSelection(listOf(track("c", audioUri = null))))

        // Nothing selected (the bars' exit frames, or rows gone from the library) does nothing.
        val empty = commands(emptyList())
        assertFalse(empty.canAct)
        assertFalse(empty.canShare)
        assertFalse(canShareSelection(emptyList()))
    }

    @Test
    fun anUnchangedSelectionWithTheSameHandlersComparesEqual() {
        // Compose keeps a lambda while its captures are unchanged; equal commands let screens skip.
        val noop = {}
        val favorite: (SelectionFavoriteAction) -> Unit = {}
        fun build(selected: List<TrackDescriptor>, playlistId: String?) =
            SelectionCommands(selected, playlistId, noop, noop, favorite, noop, noop, noop)
        assertEquals(build(listOf(track("a")), null), build(listOf(track("a")), null))
        assertNotEquals(build(listOf(track("a")), null), build(listOf(track("a")), "p1"))
        assertNotEquals(build(listOf(track("a")), null), build(emptyList(), null))
    }

    @Test
    fun eachCommandRunsItsOwnHandler() {
        val calls = mutableListOf<String>()
        val commands = commands(listOf(track("a")), calls = calls)
        commands.onPlay()
        commands.onAdd()
        commands.onFavorite(SelectionFavoriteAction.REMOVE)
        commands.onShare()
        commands.onEditTags()
        commands.onRemove()
        assertEquals(listOf("play", "add", "favorite:REMOVE", "share", "edit", "remove"), calls)
    }
}
