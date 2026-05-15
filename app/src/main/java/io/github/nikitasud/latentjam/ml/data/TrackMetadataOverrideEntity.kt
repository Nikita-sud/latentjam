/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * TrackMetadataOverrideEntity.kt is part of LatentJam.
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
 * User-supplied overrides for a song's metadata fields. Layered on top of the file's embedded ID3 /
 * Vorbis / MP4 tags by [TrackMetadataResolver]. Stored in-app only — never writes back to the audio
 * file. Existence of an override row does NOT imply all three fields are set; null = "no override
 * for this field, fall back to file tag".
 */
@Entity(tableName = "track_metadata_override")
data class TrackMetadataOverrideEntity(
    @PrimaryKey val songUid: Music.UID,
    val genre: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)
