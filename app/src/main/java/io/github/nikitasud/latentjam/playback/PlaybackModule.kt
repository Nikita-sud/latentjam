/*
 * Copyright (c) 2023 LatentJam Project
 * PlaybackModule.kt is part of LatentJam.
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
 
package io.github.nikitasud.latentjam.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import io.github.nikitasud.latentjam.playback.state.PlaybackStateManager
import io.github.nikitasud.latentjam.playback.state.PlaybackStateManagerImpl

@Module
@InstallIn(SingletonComponent::class)
interface PlaybackModule {
    @Singleton
    @Binds
    fun stateManager(playbackManager: PlaybackStateManagerImpl): PlaybackStateManager

    @Binds fun settings(playbackSettings: PlaybackSettingsImpl): PlaybackSettings
}
