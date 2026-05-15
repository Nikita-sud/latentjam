/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * MlModule.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.ml

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// `OrtEnvironment` is intentionally NOT injected — `OrtEnvironment.getEnvironment()` is
// itself a process-wide singleton on the ORT side, and resolving it eagerly through Hilt
// would force the JNI lib to load during Application#onCreate. EncoderRuntime and
// PredictorRuntime each call it lazily inside `ensureLoaded()` so a missing/incompatible
// native library can't crash app startup.
@Module
@InstallIn(SingletonComponent::class)
abstract class MlBindingsModule {
    @Binds @Singleton abstract fun mlSettings(impl: MlSettingsImpl): MlSettings
}
