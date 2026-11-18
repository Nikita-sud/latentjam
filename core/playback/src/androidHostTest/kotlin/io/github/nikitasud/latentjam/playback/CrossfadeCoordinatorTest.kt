/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

internal class CrossfadeCoordinatorTest {

    /** A player with nothing loaded: every question gets the empty answer of its type. */
    private val idlePlayer = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Timeline::class.java -> Timeline.EMPTY
            else -> null
        }
    } as Player

    private val settings = CrossfadeSettings()
    private val pausesAtItemEnd = mutableListOf<Boolean>()
    private val coordinator = CrossfadeCoordinator(
        context = android.content.ContextWrapper(null),
        player = idlePlayer,
        settings = settings,
        pauseAtItemEnd = { pausesAtItemEnd += it },
    )

    @Test
    fun theSleepTimersEndOfTrackMakesThePlayerStopAtTheEndOfTheRow() {
        coordinator.update()
        assertEquals(emptyList(), pausesAtItemEnd)

        // Armed: the player must stop by itself, before any of the next track sounds.
        settings.finishingTrack = true
        coordinator.update()
        coordinator.update()
        assertEquals(listOf(true), pausesAtItemEnd)

        // The timer fired or was cancelled: rows follow each other again.
        settings.finishingTrack = false
        coordinator.update()
        assertEquals(listOf(true, false), pausesAtItemEnd)
    }

    @Test
    fun aPlayerCreatedWhileTheTimerWaitsIsToldAtOnce() {
        // The settings outlive the service: a new service's coordinator starts with the timer already armed.
        settings.finishingTrack = true
        coordinator.start()

        assertEquals(listOf(true), pausesAtItemEnd)
        coordinator.release()
    }

    @Test
    fun theSliderAtZeroDoesNotKeepThePlayerFromStopping() {
        // Overlaps are off (the default): the coordinator still carries the timer's hold to the player.
        settings.fadeMs = 0L
        settings.finishingTrack = true
        coordinator.update()

        assertEquals(listOf(true), pausesAtItemEnd)
    }
}
