package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RailLetterMotionTest {
    @Test fun commonLettersHaveNewPositionsWithoutChangingIdentity() {
        val first = railLetterPositions(listOf("A", "B", "C"), "A", 300f, 18f, 6f)
        val next = railLetterPositions(listOf("A", "C"), "C", 300f, 18f, 6f)
        assertEquals(setOf("A", "C"), first.keys.intersect(next.keys))
        assertTrue(next.getValue("A") > first.getValue("A"))
        assertTrue(next.getValue("C") < first.getValue("C"))
    }

    @Test fun shortLandscapeRailKeepsTheExactActiveLetter() {
        val labels = (0..400).map { "letter-$it" }
        val positions = railLetterPositions(labels, "letter-177", 144f, 26f, 6f)
        assertTrue("letter-177" in positions)
        assertTrue(positions.size <= 6)
        assertTrue(positions.values.all { it in 13f..131f })
    }

    @Test fun mixedScriptsNeverCreateUnboundedVisualNodes() {
        val labels = (0..2000).map { "letter-$it" }
        val positions = railLetterPositions(labels, "letter-1999", 3000f, 18f, 6f)
        assertTrue(positions.size <= 33)
        assertTrue("letter-1999" in positions)
    }

    @Test fun normalRailDoesNotRepositionLettersWhenScrolling() {
        val labels = listOf("A", "B", "C", "Д", "Я")
        assertEquals(
            railLetterPositions(labels, "A", 400f, 18f, 6f),
            railLetterPositions(labels, "Я", 400f, 18f, 6f),
        )
    }

    @Test fun tinyOrUnmeasuredViewportsRemainSafe() {
        assertTrue(railLetterPositions(emptyList(), null, 200f, 18f, 6f).isEmpty())
        assertTrue(railLetterPositions(listOf("A"), null, 0f, 18f, 6f).isEmpty())
        assertEquals(mapOf("A" to 5f), railLetterPositions(listOf("A"), "A", 10f, 28f, 6f))
    }

    @Test fun aThinnedRailKeepsAnEvenRhythm() {
        val labels = (0..39).map { "letter-$it" }
        for (active in listOf("letter-0", "letter-7", "letter-23", "letter-39")) {
            val positions = railLetterPositions(labels, active, 500f, 18f, 6f).values.sorted()
            val gaps = positions.zipWithNext { a, b -> b - a }
            assertTrue(gaps.isNotEmpty())
            assertTrue(gaps.max() - gaps.min() < 0.01f, "uneven gaps $gaps with $active")
            assertTrue(active in railLetterPositions(labels, active, 500f, 18f, 6f))
        }
    }

    @Test fun theCurrentLetterTakesItsSlotWithoutClearingNeighbours() {
        val labels = (0..39).map { "letter-$it" }
        val atRest = railLetterPositions(labels, "letter-0", 500f, 18f, 6f)
        val scrolled = railLetterPositions(labels, "letter-21", 500f, 18f, 6f)
        assertEquals(atRest.size, scrolled.size)
        assertEquals(atRest.values.sorted(), scrolled.values.sorted())
    }

    @Test fun touchingAShownLetterSelectsThatLetter() {
        val labels = (0..39).map { "letter-$it" }
        for (active in listOf(null, "letter-0", "letter-13", "letter-39")) {
            val positions = railLetterPositions(labels, active, 500f, 18f, 6f)
            val anchors = railTouchAnchors(labels, active, 500f, 18f, 6f)
            for ((label, y) in positions) {
                assertEquals(labels.indexOf(label), railBucketIndexAt(y, anchors), "$label with $active")
                assertEquals(labels.indexOf(label), railBucketIndexAt(y + 4f, anchors), "below $label")
                assertEquals(labels.indexOf(label), railBucketIndexAt(y - 4f, anchors), "above $label")
            }
        }
    }

    @Test fun aThinnedRailStillReachesEveryLetterInOrder() {
        val labels = (0..199).map { "letter-$it" }
        val anchors = railTouchAnchors(labels, "letter-77", 400f, 18f, 6f)
        val reached = (0..4000).mapNotNull { railBucketIndexAt(it / 10f, anchors) }
        assertEquals(labels.indices.toList(), reached.distinct())
        assertTrue(reached.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun theEndsOfTheAlphabetStayReachableWhenTheCurrentLetterTakesTheirSlot() {
        val labels = (0..39).map { "letter-$it" }
        val anchors = railTouchAnchors(labels, "letter-1", 500f, 18f, 6f)
        assertEquals(0, railBucketIndexAt(0f, anchors))
        assertEquals(39, railBucketIndexAt(500f, anchors))
    }

    @Test fun aBucketsPlaceMapsBackToThatBucket() {
        val labels = (0..59).map { "letter-$it" }
        val anchors = railTouchAnchors(labels, "letter-30", 450f, 18f, 6f)
        for (index in labels.indices) {
            assertEquals(index, railBucketIndexAt(railYOfBucket(index, anchors)!!, anchors))
        }
    }

    @Test fun unmeasuredRailsHaveNoTouchMap() {
        assertTrue(railTouchAnchors(listOf("A", "B"), null, 0f, 18f, 6f).isEmpty())
        assertEquals(null, railBucketIndexAt(10f, emptyList()))
        assertEquals(null, railYOfBucket(0, emptyList()))
    }
}
