/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * ListeningEventDao.kt is part of LatentJam.
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

@Dao
interface ListeningEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ListeningEventEntity): Long

    @Query("SELECT COUNT(*) FROM ListeningEventEntity") fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ListeningEventEntity") suspend fun count(): Int

    @Query(
        """
        SELECT * FROM ListeningEventEntity
        WHERE completed = 1
        ORDER BY endedAtMs DESC
        LIMIT :limit
        """
    )
    suspend fun recentCompleted(limit: Int): List<ListeningEventEntity>

    @Query(
        """
        SELECT * FROM ListeningEventEntity
        WHERE sessionId = :sessionId AND completed = 1
        ORDER BY endedAtMs DESC
        LIMIT :limit
        """
    )
    suspend fun recentCompletedInSession(sessionId: String, limit: Int): List<ListeningEventEntity>

    @Query(
        """
        SELECT * FROM ListeningEventEntity
        WHERE startedAtMs >= :sinceMs
        ORDER BY startedAtMs ASC
        """
    )
    suspend fun eventsSince(sinceMs: Long): List<ListeningEventEntity>

    @Query(
        """
        SELECT * FROM ListeningEventEntity
        WHERE sessionId = :sessionId
        ORDER BY startedAtMs ASC
        """
    )
    suspend fun eventsForSession(sessionId: String): List<ListeningEventEntity>

    @Query("DELETE FROM ListeningEventEntity") suspend fun nuke()
}
