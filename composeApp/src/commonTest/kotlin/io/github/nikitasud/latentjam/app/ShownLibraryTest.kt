/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryScan
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShownLibraryTest {
    private val shown = listOf(TrackDescriptor(id = TrackId("a")), TrackDescriptor(id = TrackId("b")))
    private val unanswered = LibraryScan(tracks = emptyList(), complete = false, answered = false)

    @Test
    fun aSourceThatGaveNoAnswerDoesNotEmptyTheLibraryOnScreen() {
        assertTrue(keepsShownLibrary(unanswered, shown))
    }

    @Test
    fun theFirstScanIsPublishedEvenWithoutAnAnswer() {
        // Nothing to keep: a launch must leave the loading state, and an empty library has nothing to lose.
        assertFalse(keepsShownLibrary(unanswered, shown = null))
        assertFalse(keepsShownLibrary(unanswered, shown = emptyList()))
    }

    @Test
    fun anAnsweredScanIsPublishedWhateverItHolds() {
        // Incomplete and empty, but read: the last song deleted while a card is out.
        assertFalse(keepsShownLibrary(LibraryScan(emptyList(), complete = false), shown))
        assertFalse(keepsShownLibrary(LibraryScan(emptyList(), complete = true), shown))
        assertFalse(keepsShownLibrary(LibraryScan(shown.take(1), complete = false), shown))
        assertFalse(keepsShownLibrary(LibraryScan(shown.take(1), complete = true), shown))
    }
}
