/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.Application
import android.content.Context
import org.koin.dsl.module

/** Process entry point shared by the UI, playback service, widgets, and Android Auto. */
public class LatentJamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.start(
            platformModule = module {
                single<Context> { applicationContext }
            },
        )
        installMediaBrowseCatalog(this)
    }
}
