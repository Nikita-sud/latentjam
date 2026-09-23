/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import org.koin.dsl.module

/** Process entry point shared by the UI, playback service, widgets, and Android Auto. */
public class LatentJamApplication : Application() {
    private var widgetFontScale = 1f
    private var widgetDensityDpi = 0
    private var widgetOrientation = Configuration.ORIENTATION_UNDEFINED

    override fun onCreate() {
        super.onCreate()
        AndroidAppContext.value = applicationContext
        AppGraph.start(
            platformModule = module {
                single<Context> { applicationContext }
            },
        )
        installMediaBrowseCatalog(this)
        widgetFontScale = resources.configuration.fontScale
        widgetDensityDpi = resources.configuration.densityDpi
        widgetOrientation = resources.configuration.orientation
        // Covers configuration changes made while this process was stopped. With no installed
        // widgets the coordinator returns before scheduling work or decoding artwork.
        refreshPlaybackWidgets(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.fontScale != widgetFontScale || newConfig.densityDpi != widgetDensityDpi ||
            newConfig.orientation != widgetOrientation
        ) {
            widgetFontScale = newConfig.fontScale
            widgetDensityDpi = newConfig.densityDpi
            widgetOrientation = newConfig.orientation
            refreshPlaybackWidgets(this)
        }
    }
}
