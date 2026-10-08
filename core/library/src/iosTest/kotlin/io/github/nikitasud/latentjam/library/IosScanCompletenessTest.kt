/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosScanCompletenessTest {

    @Test
    fun aDeviceLibraryThatReturnedSongsIsComplete() {
        assertTrue(isComplete(documents = listOf(track("a.mp3")), device = listOf(track("ios-media:1"))))
    }

    @Test
    fun aGenuinelyEmptyLibraryIsCompleteBecauseNothingCanBePruned() {
        assertTrue(isComplete(documents = emptyList(), device = emptyList()))
    }

    @Test
    fun importedFilesWithNoDeviceSongsAreTransientRatherThanDeleted() {
        // Music.app answers with an empty list both when it holds no songs and while the query is
        // still loading, so app-owned files must win: pruning on that snapshot would drop the
        // device rows an earlier scan indexed.
        assertFalse(isComplete(documents = listOf(track("a.mp3")), device = emptyList()))
    }

    @Test
    fun documentsThatCouldNotBeEnumeratedAreIncomplete() {
        assertFalse(isComplete(documents = emptyList(), device = listOf(track("ios-media:1")), documentsReadable = false))
    }

    @Test
    fun aDisabledDeviceSourceIsNotExpectedToAnswer() {
        assertTrue(
            isComplete(
                documents = listOf(track("a.mp3")),
                device = emptyList(),
                deviceSourceEnabled = false,
            ),
        )
    }

    private fun isComplete(
        documents: List<TrackDescriptor>,
        device: List<TrackDescriptor>,
        deviceSourceEnabled: Boolean = true,
        documentsReadable: Boolean = true,
    ): Boolean = snapshotIsComplete(
        documentsReadable = documentsReadable,
        device = device,
        deviceSourceEnabled = deviceSourceEnabled,
        hasDocuments = documents.isNotEmpty(),
    )

    private fun track(id: String): TrackDescriptor = TrackDescriptor(id = TrackId(id), title = id)
}
