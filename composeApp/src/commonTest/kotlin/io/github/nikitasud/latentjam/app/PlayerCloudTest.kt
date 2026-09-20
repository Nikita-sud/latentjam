/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerCloudTest {
    private fun close(a: Color, b: Color) =
        abs(a.red - b.red) < 0.02f && abs(a.green - b.green) < 0.02f && abs(a.blue - b.blue) < 0.02f

    @Test
    fun aFullTurnComesBackToTheSameColour() {
        val colour = Color(0.31f, 0.67f, 0.69f)
        assertTrue(close(colour, rotateHue(colour, 360f)))
        assertTrue(close(colour, rotateHue(rotateHue(colour, 28f), -28f)))
    }

    @Test
    fun greyHasNoHueToRotate() {
        val grey = Color(0.5f, 0.5f, 0.5f)
        assertEquals(grey, rotateHue(grey, 90f))
    }

    @Test
    fun rotatingRedByAThirdOfTheWheelGivesGreen() {
        assertTrue(close(Color(0f, 1f, 0f), rotateHue(Color(1f, 0f, 0f), 120f)))
    }
}
