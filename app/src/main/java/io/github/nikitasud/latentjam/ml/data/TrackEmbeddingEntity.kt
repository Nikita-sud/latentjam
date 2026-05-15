/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * TrackEmbeddingEntity.kt is part of LatentJam.
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
import androidx.room.Index
import androidx.room.PrimaryKey
import org.oxycblt.musikr.Music

/**
 * Cached encoder output + handcrafted audio features for a single song.
 *
 * `embedding` is the L2-normalized 512-d float32 vector packed little-endian (2048 bytes).
 * `modelVersion` lets us re-embed everything when the encoder ships a new checkpoint or when the
 * feature bundle gains a field — bumping the version forces a full backfill.
 *
 * `tempo` (BPM) is nullable because the autocorrelation estimator declines to commit on
 * non-percussive material rather than producing confident garbage. `energy` is deterministic so
 * it's always present once a row exists for this version.
 */
@Entity(indices = [Index("modelVersion")])
data class TrackEmbeddingEntity(
    @PrimaryKey val songUid: Music.UID,
    val modelVersion: String,
    val embedding: ByteArray,
    val embeddedAtMs: Long,
    val tempo: Float? = null,
    val energy: Float? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TrackEmbeddingEntity
        if (songUid != other.songUid) return false
        if (modelVersion != other.modelVersion) return false
        if (embeddedAtMs != other.embeddedAtMs) return false
        if (tempo != other.tempo) return false
        if (energy != other.energy) return false
        return embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = songUid.hashCode()
        result = 31 * result + modelVersion.hashCode()
        result = 31 * result + embeddedAtMs.hashCode()
        result = 31 * result + (tempo?.hashCode() ?: 0)
        result = 31 * result + (energy?.hashCode() ?: 0)
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
