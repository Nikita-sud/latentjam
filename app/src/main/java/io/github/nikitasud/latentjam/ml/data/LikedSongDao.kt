/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * LikedSongDao.kt is part of LatentJam.
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
interface LikedSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entity: LikedSongEntity)

    @Query("DELETE FROM LikedSongEntity WHERE songUid = :songUid")
    suspend fun delete(songUid: Music.UID)

    @Query("SELECT COUNT(*) FROM LikedSongEntity WHERE songUid = :songUid")
    suspend fun isLikedCount(songUid: Music.UID): Int

    @Query("SELECT songUid FROM LikedSongEntity") fun likedSongsFlow(): Flow<List<Music.UID>>

    @Query("SELECT songUid FROM LikedSongEntity") suspend fun allLikedUids(): List<Music.UID>
}
