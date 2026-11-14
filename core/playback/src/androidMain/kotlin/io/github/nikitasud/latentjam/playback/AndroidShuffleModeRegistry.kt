/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local bridge between the system-created [PlaybackService] and the app-owned controller.
 *
 * ExoPlayer only knows binary random shuffle; SMART is a LatentJam queue-planning mode. Keeping the
 * three-state value here lets the notification and player UI cycle the same OFF -> ON -> SMART
 * contract (OFF -> ON while SMART is switched off) without persisting music data or involving another process.
 */
internal object AndroidShuffleModeRegistry {
    private val mutableMode = MutableStateFlow(ShuffleMode.OFF)
    val mode: StateFlow<ShuffleMode> = mutableMode.asStateFlow()

    /** False while SMART is switched off in settings: SMART is then never the mode, whoever asks for it. */
    @Volatile
    var smartAvailable: Boolean = true
        private set

    fun set(value: ShuffleMode) {
        mutableMode.value = value.allowed(smartAvailable)
    }

    fun setSmartAvailable(available: Boolean) {
        smartAvailable = available
        // A queue in SMART goes on in plain order: the controller follows this flow.
        if (!available) mutableMode.value = mutableMode.value.allowed(false)
    }

    /** The mode a press of the cycle button leads to from [from]. */
    fun next(from: ShuffleMode = mutableMode.value): ShuffleMode = from.next(smartAvailable)

    fun cycle(): ShuffleMode = next().also(::set)
}

/** Custom Media3 command because SMART has no equivalent ExoPlayer player command. */
internal val CycleShuffleModeCommand: SessionCommand = SessionCommand(
    "io.github.nikitasud.latentjam.CYCLE_SHUFFLE_MODE",
    Bundle.EMPTY,
)
