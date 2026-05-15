/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * TrackMetadataOverrideDao.kt is part of LatentJam.
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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.oxycblt.musikr.Music

@Dao
interface TrackMetadataOverrideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackMetadataOverrideEntity)

    @Query("SELECT * FROM track_metadata_override WHERE songUid = :uid LIMIT 1")
    suspend fun get(uid: Music.UID): TrackMetadataOverrideEntity?

    @Query("SELECT * FROM track_metadata_override")
    suspend fun all(): List<TrackMetadataOverrideEntity>

    /** Distinct override genres across the library — feeds the autocomplete suggestions. */
    @Query("SELECT DISTINCT genre FROM track_metadata_override WHERE genre IS NOT NULL")
    suspend fun distinctOverrideGenres(): List<String>

    @Query("SELECT COUNT(*) FROM track_metadata_override") fun countFlow(): Flow<Int>

    @Query("DELETE FROM track_metadata_override WHERE songUid = :uid")
    suspend fun delete(uid: Music.UID)
}
