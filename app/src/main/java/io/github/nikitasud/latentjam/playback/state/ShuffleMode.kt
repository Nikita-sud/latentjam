/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * ShuffleMode.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.playback.state

import io.github.nikitasud.latentjam.R

/**
 * Represents the current shuffle mode of the player.
 */
enum class ShuffleMode {
    /** Shuffle is off. Tracks are played in their natural order. */
    OFF,

    /** Shuffle is on. Tracks are played in a random order. */
    ON,

    /** Smart shuffle is on. Tracks are picked by the AI recommendation engine. */
    SMART,

    /** The shuffle mode is determined by the player's current state. */
    IMPLICIT;

    /**
     * Increment the mode.
     *
     * @return If [OFF], [ON]. If [ON], [SMART]. If [SMART], [OFF].
     */
    fun increment() =
        when (this) {
            OFF -> ON
            ON -> SMART
            SMART -> OFF
            IMPLICIT -> OFF
        }

    /**
     * The drawable resource for this mode.
     */
    val icon: Int
        get() =
            when (this) {
                OFF -> R.drawable.ic_shuffle_off_24
                ON -> R.drawable.ic_shuffle_on_24
                SMART -> R.drawable.ic_shuffle_star_24
                IMPLICIT -> R.drawable.ic_shuffle_off_24
            }
}
