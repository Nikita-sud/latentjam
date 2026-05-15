/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
    @Binds
    @Singleton
    abstract fun mlSettings(impl: MlSettingsImpl): MlSettings
}
