/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class NavigationChromeTest {
    @Test
    fun revealStartsAtTheActualButtonAndEndsAtTheWholeDestination() {
        val button = Rect(4f, 35f, 52f, 83f)
        val page = Rect(0f, 0f, 412f, 892f)
        assertEquals(button, navigationRevealBounds(button, page, -0.2f))
        assertEquals(button, navigationRevealBounds(button, page, 0f))
        assertEquals(page, navigationRevealBounds(button, page, 1f))
        assertEquals(page, navigationRevealBounds(button, page, 1.2f))
    }

    @Test
    fun revealGrowsWithoutFlippingItsBoundsInNarrowWideAndRtlWindows() {
        for ((width, height) in listOf(280f to 640f, 412f to 892f, 892f to 412f, 1366f to 1024f)) {
            for (rtl in listOf(false, true)) {
                val button = if (rtl) Rect(width - 52f, 35f, width - 4f, 83f)
                    else Rect(4f, 35f, 52f, 83f)
                val page = Rect(0f, 0f, width, height)
                var previous = button
                for (step in 1..100) {
                    val bounds = navigationRevealBounds(button, page, step / 100f)
                    assertTrue(bounds.left <= previous.left && bounds.top <= previous.top)
                    assertTrue(bounds.right >= previous.right && bounds.bottom >= previous.bottom)
                    assertTrue(bounds.width > 0f && bounds.height > 0f)
                    previous = bounds
                }
            }
        }
    }

    @Test
    fun searchFieldRevealMirrorsExactlyForRtl() {
        val width = 412f
        fun Rect.mirror() = Rect(width - right, top, width - left, bottom)
        val search = Rect(360f, 4f, 408f, 52f)
        val field = Rect(20f, 8f, 392f, 56f)
        for (step in 0..20) {
            val progress = step / 20f
            val expected = navigationRevealBounds(search, field, progress).mirror()
            val actual = navigationRevealBounds(search.mirror(), field.mirror(), progress)
            assertEquals(expected.left, actual.left, 0.0001f)
            assertEquals(expected.right, actual.right, 0.0001f)
            assertEquals(expected.top, actual.top)
            assertEquals(expected.bottom, actual.bottom)
        }
    }

    @Test
    fun contentPhasesRetraceAndKeepOpacityBounded() {
        assertEquals(0f, navigationPhase(0.1f, 0.3f, 1f))
        assertEquals(1f, navigationPhase(1.1f, 0.3f, 1f))
        val opening = (0..100).map { navigationPhase(it / 100f, 0.3f, 1f) }
        val closing = (100 downTo 0).map { navigationPhase(it / 100f, 0.3f, 1f) }
        assertEquals(opening.reversed(), closing)
        assertTrue(opening.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun reducedMotionSkipsGeometryAndClosingReversesTheOpeningCurve() {
        assertIs<SnapSpec<Float>>(chromeNavigationSpec(true, true))
        assertIs<SnapSpec<Float>>(chromeNavigationSpec(true, false))
        val opening = assertIs<TweenSpec<Float>>(chromeNavigationSpec(false, true))
        val closing = assertIs<TweenSpec<Float>>(chromeNavigationSpec(false, false))
        assertTrue(opening.durationMillis <= 320)
        assertTrue(closing.durationMillis <= opening.durationMillis)
        for (step in 0..20) {
            val fraction = step / 20f
            assertEquals(1f - opening.easing.transform(1f - fraction),
                closing.easing.transform(fraction), 0.0001f)
        }
    }
}
