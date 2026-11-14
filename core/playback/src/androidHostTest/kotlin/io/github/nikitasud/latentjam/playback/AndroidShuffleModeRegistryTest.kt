/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The registry is what the notification, the widgets and the player's button all cycle through. */
class AndroidShuffleModeRegistryTest {
    @AfterTest
    fun restore() {
        AndroidShuffleModeRegistry.setSmartAvailable(true)
        AndroidShuffleModeRegistry.set(ShuffleMode.OFF)
    }

    @Test
    fun switchingSmartOffTakesAQueueOutOfSmartAndKeepsItOut() {
        AndroidShuffleModeRegistry.set(ShuffleMode.SMART)
        AndroidShuffleModeRegistry.setSmartAvailable(false)
        assertEquals(ShuffleMode.OFF, AndroidShuffleModeRegistry.mode.value)
        // A saved session, a widget or a head unit asking for SMART gets plain order.
        AndroidShuffleModeRegistry.set(ShuffleMode.SMART)
        assertEquals(ShuffleMode.OFF, AndroidShuffleModeRegistry.mode.value)
        // The cycle button: off, shuffle, off.
        assertEquals(ShuffleMode.ON, AndroidShuffleModeRegistry.cycle())
        assertEquals(ShuffleMode.OFF, AndroidShuffleModeRegistry.next())
        assertEquals(ShuffleMode.OFF, AndroidShuffleModeRegistry.cycle())
    }

    @Test
    fun switchingSmartOnAgainBringsTheStepBackButNotTheMode() {
        AndroidShuffleModeRegistry.setSmartAvailable(false)
        AndroidShuffleModeRegistry.set(ShuffleMode.ON)
        AndroidShuffleModeRegistry.setSmartAvailable(true)
        assertEquals(ShuffleMode.ON, AndroidShuffleModeRegistry.mode.value)
        assertEquals(ShuffleMode.SMART, AndroidShuffleModeRegistry.cycle())
    }

    @Test
    fun shuffleIsUntouchedBySwitchingSmartOff() {
        AndroidShuffleModeRegistry.set(ShuffleMode.ON)
        AndroidShuffleModeRegistry.setSmartAvailable(false)
        assertEquals(ShuffleMode.ON, AndroidShuffleModeRegistry.mode.value)
    }
}
