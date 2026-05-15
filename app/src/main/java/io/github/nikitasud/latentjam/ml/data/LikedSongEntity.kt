/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * LikedSongEntity.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.ml.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.oxycblt.musikr.Music

/**
 * One row per song the user has explicitly favorited via the star button. The presence of a row
 * means "liked"; removing the row unlikes the song.
 *
 * Kept in a separate table from [ListeningEventEntity] because likes are track-level (one per song,
 * mutable) while events are append-only logs of individual listens. Joined at finalize-time so each
 * event row stamps the current liked state, which is the per-event signal the predictor wants.
 */
@Entity(tableName = "LikedSongEntity")
data class LikedSongEntity(@PrimaryKey val songUid: Music.UID, val likedAtMs: Long)
