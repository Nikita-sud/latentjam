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
 * contract without persisting music data or involving another process.
 */
internal object AndroidShuffleModeRegistry {
    private val mutableMode = MutableStateFlow(ShuffleMode.OFF)
    val mode: StateFlow<ShuffleMode> = mutableMode.asStateFlow()

    fun set(value: ShuffleMode) {
        mutableMode.value = value
    }

    fun cycle(): ShuffleMode = when (mutableMode.value) {
        ShuffleMode.OFF -> ShuffleMode.ON
        ShuffleMode.ON -> ShuffleMode.SMART
        ShuffleMode.SMART -> ShuffleMode.OFF
    }.also(::set)
}

/** Custom Media3 command because SMART has no equivalent ExoPlayer player command. */
internal val CycleShuffleModeCommand: SessionCommand = SessionCommand(
    "io.github.nikitasud.latentjam.CYCLE_SHUFFLE_MODE",
    Bundle.EMPTY,
)
