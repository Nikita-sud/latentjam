/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * EmbeddingScheduler.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.ml.encoder

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nikitasud.latentjam.ml.predictor.RecommendationEngine
import io.github.nikitasud.latentjam.music.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges [MusicRepository.UpdateListener] callbacks into [EmbeddingWorker] enqueues so we embed
 * every newly-indexed track once. Also invalidates the [RecommendationEngine]'s cached embedding
 * matrix so the recommender picks up new tracks immediately.
 */
@Singleton
class EmbeddingScheduler
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val recommendationEngine: RecommendationEngine,
) : MusicRepository.UpdateListener {

    fun attach() {
        musicRepository.addUpdateListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (!changes.deviceLibrary) return
        EmbeddingWorker.enqueueIfPending(context)
        recommendationEngine.invalidateLibraryCache()
    }
}
