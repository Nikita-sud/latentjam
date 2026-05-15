/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * PersistenceDatabase.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.playback.persist

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import io.github.nikitasud.latentjam.ml.data.LikedSongDao
import io.github.nikitasud.latentjam.ml.data.LikedSongEntity
import io.github.nikitasud.latentjam.ml.data.ListeningEventDao
import io.github.nikitasud.latentjam.ml.data.ListeningEventEntity
import io.github.nikitasud.latentjam.ml.data.TrackEmbeddingDao
import io.github.nikitasud.latentjam.ml.data.TrackEmbeddingEntity
import io.github.nikitasud.latentjam.ml.data.TrackMetadataOverrideDao
import io.github.nikitasud.latentjam.ml.data.TrackMetadataOverrideEntity
import io.github.nikitasud.latentjam.playback.state.RepeatMode
import io.github.nikitasud.latentjam.playback.state.ShuffleMode
import org.oxycblt.musikr.Music

/**
 * Provides raw access to the database storing the persisted playback state.
 *
 * @author Alexander Capehart
 */
@Database(
    entities = [
        PlaybackState::class,
        QueueHeapItem::class,
        QueueShuffledMappingItem::class,
        ListeningEventEntity::class,
        TrackEmbeddingEntity::class,
        TrackMetadataOverrideEntity::class,
        LikedSongEntity::class,
    ],
    version = 45,
    exportSchema = false,
)
@TypeConverters(Music.UID.TypeConverters::class)
abstract class PersistenceDatabase : RoomDatabase() {
    /**
     * Get the current [PlaybackStateDao].
     *
     * @return A [PlaybackStateDao] providing control of the database's playback state tables.
     */
    abstract fun playbackStateDao(): PlaybackStateDao

    /**
     * Get the current [QueueDao].
     *
     * @return A [QueueDao] providing control of the database's queue tables.
     */
    abstract fun queueDao(): QueueDao

    abstract fun listeningEventDao(): ListeningEventDao

    abstract fun trackEmbeddingDao(): TrackEmbeddingDao

    abstract fun trackMetadataOverrideDao(): TrackMetadataOverrideDao

    abstract fun likedSongDao(): LikedSongDao

    companion object {
        val MIGRATION_27_32 =
            Migration(27, 32) {
                // Switched from custom names to just letting room pick the names
                it.execSQL("ALTER TABLE playback_state RENAME TO PlaybackState")
                it.execSQL("ALTER TABLE queue_heap RENAME TO QueueHeapItem")
                it.execSQL("ALTER TABLE queue_mapping RENAME TO QueueMappingItem")
            }

        // v39 introduces the ML tables (listening events + cached track embeddings).
        // The two tables are new and additive — no existing column types change — so
        // a plain CREATE TABLE migration is sufficient. v0 of this migration omitted the
        // index DDL declared on the @Entity, which made Room's TableInfo check throw on
        // first open; v41 carries an idempotent fix-up so any in-the-wild v39/v40 DBs
        // pick up the missing indices on next launch.
        val MIGRATION_38_39 =
            Migration(38, 39) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ListeningEventEntity` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `songUid` TEXT NOT NULL,
                        `startedAtMs` INTEGER NOT NULL,
                        `endedAtMs` INTEGER NOT NULL,
                        `playedMs` INTEGER NOT NULL,
                        `trackDurationMs` INTEGER NOT NULL,
                        `completed` INTEGER NOT NULL,
                        `skipped` INTEGER NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sessionPos` INTEGER NOT NULL,
                        `parentUid` TEXT,
                        `ctxUid0` TEXT,
                        `ctxUid1` TEXT,
                        `ctxUid2` TEXT,
                        `ctxUid3` TEXT
                    )
                    """.trimIndent()
                )
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `TrackEmbeddingEntity` (
                        `songUid` TEXT NOT NULL PRIMARY KEY,
                        `modelVersion` TEXT NOT NULL,
                        `embedding` BLOB NOT NULL,
                        `embeddedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                createMlIndices(it)
            }

        val MIGRATION_39_40 =
            Migration(39, 40) {
                it.execSQL("ALTER TABLE PlaybackState ADD COLUMN shuffleMode TEXT NOT NULL DEFAULT 'OFF'")
            }

        // Fix-up: pre-existing v39/v40 DBs that crashed on the missing indices.
        val MIGRATION_40_41 =
            Migration(40, 41) { createMlIndices(it) }

        // v42 adds the per-track metadata override table — user-edited genre / artist /
        // year that the metadata rerank consults before falling back to file tags.
        val MIGRATION_41_42 =
            Migration(41, 42) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_metadata_override` (
                        `songUid` TEXT NOT NULL PRIMARY KEY,
                        `genre` TEXT,
                        `artist` TEXT,
                        `year` INTEGER,
                        `updatedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }

        // v43 adds:
        //   - `tempo` + `energy` on TrackEmbeddingEntity for the Phase 0 BPM filter and
        //     energy soft-penalty. Both are nullable; the embedding worker fills them
        //     during the same pass that produces the encoder output.
        //   - `wasSmartPick` on ListeningEventEntity so future training can distinguish
        //     SMART-driven plays from manually-queued ones (the reviewer flagged this
        //     as a precondition for any reranker training).
        // All columns are additive and nullable / default-false — no data loss.
        val MIGRATION_42_43 =
            Migration(42, 43) {
                it.execSQL("ALTER TABLE `TrackEmbeddingEntity` ADD COLUMN `tempo` REAL")
                it.execSQL("ALTER TABLE `TrackEmbeddingEntity` ADD COLUMN `energy` REAL")
                it.execSQL(
                    "ALTER TABLE `ListeningEventEntity` ADD COLUMN `wasSmartPick` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }

        // v44 adds four columns on ListeningEventEntity that the existing
        // `completed`/`skipped` booleans conflated:
        //   - `finalizeReason` distinguishes natural track-end from a user-initiated
        //     skip and from session-end / new-playback context shifts.
        //   - `shuffleMode` / `repeatMode` capture the playback context at finalize
        //     time (broader than the existing `wasSmartPick` bool).
        //   - `wasUserStarted` captures user agency at the START of this track
        //     (tap-to-play vs queue-advance), which `wasSmartPick` doesn't tell us.
        // All additive, all default to a safe value for legacy rows.
        val MIGRATION_43_44 =
            Migration(43, 44) {
                it.execSQL(
                    "ALTER TABLE `ListeningEventEntity` ADD COLUMN `finalizeReason` " +
                        "TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
                it.execSQL(
                    "ALTER TABLE `ListeningEventEntity` ADD COLUMN `shuffleMode` " +
                        "TEXT NOT NULL DEFAULT 'OFF'"
                )
                it.execSQL(
                    "ALTER TABLE `ListeningEventEntity` ADD COLUMN `repeatMode` " +
                        "TEXT NOT NULL DEFAULT 'NONE'"
                )
                it.execSQL(
                    "ALTER TABLE `ListeningEventEntity` ADD COLUMN `wasUserStarted` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }

        // v45 adds the LikedSongEntity table (user-favorited songs, toggled via
        // the star button on every song row) and the `liked` column on
        // ListeningEventEntity. The recorder queries the table at finalize time
        // and stamps the current liked state on each event row, giving the
        // predictor a strong explicit positive signal per listen.
        val MIGRATION_44_45 =
            Migration(44, 45) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `LikedSongEntity` (
                        `songUid` TEXT NOT NULL PRIMARY KEY,
                        `likedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                it.execSQL(
                    "ALTER TABLE `ListeningEventEntity` ADD COLUMN `liked` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }

        private fun createMlIndices(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ListeningEventEntity_sessionId` " +
                    "ON `ListeningEventEntity` (`sessionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ListeningEventEntity_startedAtMs` " +
                    "ON `ListeningEventEntity` (`startedAtMs`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ListeningEventEntity_songUid` " +
                    "ON `ListeningEventEntity` (`songUid`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_TrackEmbeddingEntity_modelVersion` " +
                    "ON `TrackEmbeddingEntity` (`modelVersion`)"
            )
        }
    }
}

/**
 * Provides control of the persisted playback state table.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@Dao
interface PlaybackStateDao {
    /**
     * Get the previously persisted [PlaybackState].
     *
     * @return The previously persisted [PlaybackState], or null if one was not present.
     */
    @Query("SELECT * FROM PlaybackState WHERE id = 0") suspend fun getState(): PlaybackState?

    /** Delete any previously persisted [PlaybackState]s. */
    @Query("DELETE FROM PlaybackState") suspend fun nukeState()

    /**
     * Insert a new [PlaybackState] into the database.
     *
     * @param state The [PlaybackState] to insert.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertState(state: PlaybackState)
}

/**
 * Provides control of the persisted queue state tables.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@Dao
interface QueueDao {
    /**
     * Get the previously persisted queue heap.
     *
     * @return A list of persisted [QueueHeapItem]s wrapping each heap item.
     */
    @Query("SELECT * FROM QueueHeapItem") suspend fun getHeap(): List<QueueHeapItem>

    /**
     * Get the previously persisted queue mapping.
     *
     * @return A list of persisted [QueueShuffledMappingItem]s wrapping each heap item.
     */
    @Query("SELECT * FROM QueueShuffledMappingItem")
    suspend fun getShuffledMapping(): List<QueueShuffledMappingItem>

    /** Delete any previously persisted queue heap entries. */
    @Query("DELETE FROM QueueHeapItem") suspend fun nukeHeap()

    /** Delete any previously persisted queue mapping entries. */
    @Query("DELETE FROM QueueShuffledMappingItem") suspend fun nukeShuffledMapping()

    /**
     * Insert new heap entries into the database.
     *
     * @param heap The list of wrapped [QueueHeapItem]s to insert.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertHeap(heap: List<QueueHeapItem>)

    /**
     * Insert new mapping entries into the database.
     *
     * @param mapping The list of wrapped [QueueShuffledMappingItem] to insert.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertShuffledMapping(mapping: List<QueueShuffledMappingItem>)
}

// TODO: Figure out how to get RepeatMode to map to an int instead of a string
@Entity
data class PlaybackState(
    @PrimaryKey val id: Int,
    val index: Int,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleMode: ShuffleMode,
    val songUid: Music.UID,
    val parentUid: Music.UID?,
)

@Entity data class QueueHeapItem(@PrimaryKey val id: Int, val uid: Music.UID)

@Entity data class QueueShuffledMappingItem(@PrimaryKey val id: Int, val index: Int)
