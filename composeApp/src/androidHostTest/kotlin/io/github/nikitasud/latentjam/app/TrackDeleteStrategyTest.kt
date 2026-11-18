/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The version boundaries, pinned. Each one is a different Android deletion mechanism, and getting
 * a boundary wrong reintroduces exactly the bug this replaced: a delete that does nothing at all.
 */
class TrackDeleteStrategyTest {

    @Test
    fun `Android 11 and newer hand the whole batch to the system`() {
        assertEquals(
            TrackDeleteStrategy.SYSTEM_DELETE_REQUEST,
            trackDeleteStrategy(Build.VERSION_CODES.R),
        )
        assertEquals(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST, trackDeleteStrategy(36))
    }

    @Test
    fun `Android 10 asks per file through a recoverable refusal`() {
        assertEquals(
            TrackDeleteStrategy.RECOVERABLE_CONSENT,
            trackDeleteStrategy(Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun `Android 9 and older have only the storage permission`() {
        assertEquals(
            TrackDeleteStrategy.WRITE_PERMISSION,
            trackDeleteStrategy(Build.VERSION_CODES.P),
        )
        // minSdk: the oldest system that can install LatentJam at all.
        assertEquals(TrackDeleteStrategy.WRITE_PERMISSION, trackDeleteStrategy(24))
    }

    @Test
    fun `Android 9 and older delete the file as well as its row`() {
        val steps = mutableListOf<String>()
        val attempt = deleteLegacyAudio(
            path = "/storage/emulated/0/Music/song.mp3",
            deleteFile = { steps += "file $it"; true },
            deleteRow = { steps += "row"; 1 },
        )
        assertEquals(DeleteAttempt.Deleted, attempt)
        assertEquals(listOf("file /storage/emulated/0/Music/song.mp3", "row"), steps)
    }

    @Test
    fun `a file that cannot be removed keeps its row and the delete is reported as failed`() {
        var rowDeleted = false
        val attempt = deleteLegacyAudio(
            path = "/storage/1A2B-3C4D/Music/song.mp3",
            deleteFile = { false },
            deleteRow = { rowDeleted = true; 1 },
        )
        assertEquals(DeleteAttempt.Failed, attempt)
        assertEquals(false, rowDeleted)
    }

    @Test
    fun `a removed file counts as deleted whatever becomes of its row`() {
        assertEquals(DeleteAttempt.Deleted, deleteLegacyAudio("/music/song.mp3", { true }, { 0 }))
        assertEquals(DeleteAttempt.Deleted, deleteLegacyAudio("/music/song.mp3", { true }, { error("provider") }))
    }

    @Test
    fun `a row that names no file is deleted as before and a missing row is missing`() {
        assertEquals(DeleteAttempt.Deleted, deleteLegacyAudio(null, { error("no file to delete") }, { 1 }))
        assertEquals(DeleteAttempt.Missing, deleteLegacyAudio(null, { error("no file to delete") }, { 0 }))
    }
}
