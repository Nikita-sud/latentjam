package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionIndexTest {
    @Test fun smallCollectionsStayCompactEvenInShortLandscapeWindows() {
        for (count in 0..11) {
            assertFalse(collectionNeedsAlphabetIndex(count, count, 1, 72f, 160f))
            assertFalse(collectionNeedsAlphabetIndex(count, count, 2, 240f, 160f))
        }
    }

    @Test fun theSameListAdaptsToAvailableHeightAndScaledRows() {
        assertTrue(collectionNeedsAlphabetIndex(20, 8, 1, 72f, 600f))
        assertFalse(collectionNeedsAlphabetIndex(20, 8, 1, 72f, 900f))
        assertTrue(collectionNeedsAlphabetIndex(20, 8, 1, 108f, 900f))
    }

    @Test fun albumsUseGridRowsInsteadOfTheNumberOfCards() {
        assertTrue(collectionNeedsAlphabetIndex(18, 8, 2, 240f, 700f))
        assertFalse(collectionNeedsAlphabetIndex(18, 8, 6, 200f, 700f))
        // The incomplete final row still occupies one row.
        assertTrue(collectionNeedsAlphabetIndex(13, 8, 3, 240f, 600f))
        assertFalse(collectionNeedsAlphabetIndex(12, 8, 3, 240f, 600f))
    }

    @Test fun oneLetterDoesNotNeedAnIndexRegardlessOfCollectionLength() {
        assertFalse(collectionNeedsAlphabetIndex(1000, 1, 1, 72f, 600f))
        assertFalse(collectionNeedsAlphabetIndex(1000, 0, 2, 240f, 600f))
    }

    @Test fun unmeasuredOrUnboundedViewportsNeverEnableAnIndex() {
        for (height in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(collectionNeedsAlphabetIndex(100, 10, 1, 72f, height))
        }
        assertFalse(collectionNeedsAlphabetIndex(100, 10, 0, 72f, 600f))
    }

    @Test fun pinchingAWideAdaptiveGridMovesTheWayTheFingersMove() {
        // A tablet's adaptive grid shows six columns; closing the fingers asks for more.
        assertNull(albumColumnsAfterPinch(columns = 0, adaptive = 6, spread = false))
        assertEquals(4, albumColumnsAfterPinch(columns = 0, adaptive = 6, spread = true))
        assertEquals(3, albumColumnsAfterPinch(columns = 4, adaptive = 6, spread = true))
    }

    @Test fun pinchingForMorePastTheLargestChoiceReturnsToTheAdaptiveGrid() {
        assertEquals(0, albumColumnsAfterPinch(columns = 4, adaptive = 6, spread = false))
        // A phone fits fewer than four: four stays four.
        assertNull(albumColumnsAfterPinch(columns = 4, adaptive = 2, spread = false))
    }

    @Test fun aPhoneGridStepsBetweenTwoAndFourColumns() {
        assertEquals(3, albumColumnsAfterPinch(columns = 0, adaptive = 2, spread = false))
        assertEquals(2, albumColumnsAfterPinch(columns = 3, adaptive = 2, spread = true))
        assertNull(albumColumnsAfterPinch(columns = 2, adaptive = 2, spread = true))
        assertNull(albumColumnsAfterPinch(columns = 0, adaptive = 1, spread = true))
    }
}
