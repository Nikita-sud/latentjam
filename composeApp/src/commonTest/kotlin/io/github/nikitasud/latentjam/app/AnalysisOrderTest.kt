/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AnalysisOrderTest {

    /** A library as the scan hands it over: title order, MediaStore's sequential numeric ids. */
    private val library: List<TrackDescriptor> = (0 until 2_600).map { index ->
        val letter = 'A' + index / 100
        TrackDescriptor(TrackId((1_000 + index).toString()), title = "$letter title ${index % 100}")
    }

    @Test
    fun theOrderIsTheSameOnEveryRunWhateverOrderTheScanUsed() {
        val order = analysisOrder(library)
        assertEquals(order, analysisOrder(library))
        assertEquals(order, analysisOrder(library.reversed()))
        assertEquals(order, analysisOrder(library.shuffled(kotlin.random.Random(7))))
        assertEquals(library.toSet(), order.toSet())
        assertEquals(library.size, order.size)
    }

    @Test
    fun theOrderIsNotTitleOrderAndSpreadsOverTheWholeLibrary() {
        val order = analysisOrder(library)
        assertNotEquals(library, order)
        // The first few percent analysed already reach every initial letter: an early SMART
        // queue draws from the whole library, not from its alphabetical head.
        val firstLetters = order.take(library.size / 20).mapTo(HashSet()) { it.title!!.first() }
        assertEquals(('A'..'Z').toSet(), firstLetters)
        // Every quarter of the title order holds a fair share of the first quarter analysed.
        val position = library.withIndex().associate { (index, track) -> track.id to index }
        val quarters = order.take(library.size / 4).groupingBy { position.getValue(it.id) * 4 / library.size }.eachCount()
        assertEquals(setOf(0, 1, 2, 3), quarters.keys)
        quarters.values.forEach { count -> assertTrue(count in 100..225, "quarter share $count of 650") }
    }

    @Test
    fun aGrowingOrShrinkingLibraryKeepsTheRestInTheSameOrder() {
        // Resume after a restart walks the same sequence; tracks already analysed are skipped by
        // the engine, so the remaining work is exactly what the earlier run had not reached.
        val full = analysisOrder(library)
        val subset = library.filterIndexed { index, _ -> index % 3 != 0 }
        assertEquals(full.filter { it in subset.toSet() }, analysisOrder(subset))
        val added = TrackDescriptor(TrackId("99999"), title = "Added later")
        assertEquals(full, analysisOrder(library + added).filter { it != added })
    }

    @Test
    fun resumingContinuesWhereTheEarlierRunStopped() {
        val order = analysisOrder(library)
        val analysedBeforeRestart = order.take(700).mapTo(HashSet()) { it.id }
        val pending = analysisOrder(library).filter { it.id !in analysedBeforeRestart }
        assertEquals(order.drop(700), pending)
    }
}
