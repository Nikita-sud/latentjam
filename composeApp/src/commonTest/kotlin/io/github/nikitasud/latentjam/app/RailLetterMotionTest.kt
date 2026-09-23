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
}
