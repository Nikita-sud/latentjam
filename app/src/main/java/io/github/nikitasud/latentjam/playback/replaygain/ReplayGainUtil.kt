/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * ReplayGainUtil.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.playback.replaygain

import android.content.Context
import io.github.nikitasud.latentjam.R
import kotlin.math.abs

/**
 * Format a decibel value in a human-readable format.
 *
 * @param context The context to resolve resources from.
 * @return A formatted decibel value. Will be prefixed by a + or - sign.
 */
fun Float.formatDb(context: Context) =
    if (this >= 0) {
        context.getString(R.string.fmt_db_pos, this)
    } else {
        context.getString(R.string.fmt_db_neg, abs(this))
    }
