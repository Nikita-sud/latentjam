/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.test.Test
import kotlin.test.assertEquals

class SoundBackedZTest {

    @Test
    fun aSemanticBonusNeedsTheCandidateToSoundCloseToItsReference() {
        // As far apart as a random pair of tracks: labels alone earn nothing.
        assertEquals(0f, soundBackedZ(2f, -0.15f))
        assertEquals(0f, soundBackedZ(2f, ChainConfig.SEM_SOUND_GATE_LOW))
        // Halfway up the gate, half the bonus.
        assertEquals(1f, soundBackedZ(2f, 0.075f), 1e-6f)
        // Close in sound: the whole bonus.
        assertEquals(2f, soundBackedZ(2f, ChainConfig.SEM_SOUND_GATE_HIGH))
        assertEquals(2f, soundBackedZ(2f, 0.6f))
    }

    @Test
    fun aSemanticPenaltyStaysWholeWhateverTheSound() {
        assertEquals(-1.5f, soundBackedZ(-1.5f, -0.3f))
        assertEquals(-1.5f, soundBackedZ(-1.5f, 0.9f))
        assertEquals(0f, soundBackedZ(0f, 0.9f))
    }
}
