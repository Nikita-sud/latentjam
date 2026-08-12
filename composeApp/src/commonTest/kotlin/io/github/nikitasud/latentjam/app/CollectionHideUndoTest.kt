/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class CollectionHideUndoTest {

    private val sourceTab = RootTabSnapshot(
        navigationRevision = 7L,
        currentTab = 2,
        settledTab = 2,
    )

    @Test
    fun unchangedCollectionAndRootTabCanRestore() {
        assertTrue(
            shouldRestoreCollectionAfterHideUndo(
                appliedCollectionRevision = 12L,
                currentCollectionRevision = 12L,
                sourceRootTab = sourceTab,
                currentRootTab = sourceTab,
            ),
        )
    }

    @Test
    fun collectionThatWasNotUpdatedOrWasLaterChangedCannotRestore() {
        assertFalse(
            shouldRestoreCollectionAfterHideUndo(
                appliedCollectionRevision = null,
                currentCollectionRevision = 12L,
                sourceRootTab = sourceTab,
                currentRootTab = sourceTab,
            ),
        )
        assertFalse(
            shouldRestoreCollectionAfterHideUndo(
                appliedCollectionRevision = 12L,
                currentCollectionRevision = 13L,
                sourceRootTab = sourceTab,
                currentRootTab = sourceTab,
            ),
        )
    }

    @Test
    fun awayAndBackCannotRestoreEvenWhenTheVisibleTabMatchesAgain() {
        val afterAwayAndBack = sourceTab.copy(navigationRevision = 9L)

        assertFalse(
            shouldRestoreCollectionAfterHideUndo(
                appliedCollectionRevision = 12L,
                currentCollectionRevision = 12L,
                sourceRootTab = sourceTab,
                currentRootTab = afterAwayAndBack,
            ),
        )
    }

    @Test
    fun inFlightOrDifferentRootTabCannotRestore() {
        assertFalse(
            shouldRestoreCollectionAfterHideUndo(
                appliedCollectionRevision = 12L,
                currentCollectionRevision = 12L,
                sourceRootTab = sourceTab,
                currentRootTab = sourceTab.copy(currentTab = 3),
            ),
        )
        assertFalse(
            shouldRestoreCollectionAfterHideUndo(
                appliedCollectionRevision = 12L,
                currentCollectionRevision = 12L,
                sourceRootTab = sourceTab,
                currentRootTab = sourceTab.copy(settledTab = 3),
            ),
        )
    }

    @Test
    fun commandStartedWhileRootPagerWasUnsettledCannotRestore() {
        assertFalse(
            shouldRestoreCollectionAfterHideUndo(
                appliedCollectionRevision = 12L,
                currentCollectionRevision = 12L,
                sourceRootTab = sourceTab.copy(currentTab = 3),
                currentRootTab = sourceTab.copy(currentTab = 3),
            ),
        )
    }
}
