package io.github.nikitasud.latentjam.app

import kotlin.test.Test
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
}
