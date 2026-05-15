/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * TrackEmbeddingDao.kt is part of LatentJam.
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
interface TrackEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackEmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TrackEmbeddingEntity>)

    @Query(
        "SELECT * FROM TrackEmbeddingEntity WHERE songUid = :uid AND modelVersion = :version LIMIT 1"
    )
    suspend fun get(uid: Music.UID, version: String): TrackEmbeddingEntity?

    @Query("SELECT * FROM TrackEmbeddingEntity WHERE modelVersion = :version")
    suspend fun loadAll(version: String): List<TrackEmbeddingEntity>

    @Query("SELECT songUid FROM TrackEmbeddingEntity WHERE modelVersion = :version")
    suspend fun knownUids(version: String): List<Music.UID>

    @Query("SELECT COUNT(*) FROM TrackEmbeddingEntity WHERE modelVersion = :version")
    suspend fun count(version: String): Int

    @Query("SELECT COUNT(*) FROM TrackEmbeddingEntity WHERE modelVersion = :version")
    fun countFlow(version: String): Flow<Int>

    @Query("DELETE FROM TrackEmbeddingEntity WHERE modelVersion != :keepVersion")
    suspend fun nukeOtherVersions(keepVersion: String)
}
