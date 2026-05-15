/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * MlSettings.kt is part of LatentJam.
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

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nikitasud.latentjam.settings.Settings
import javax.inject.Inject

/**
 * On-device ML configuration. The recommender on/off toggle lives on the shuffle button as
 * `ShuffleMode.SMART`; this interface covers the privacy-sensitive bit (event logging) and runtime
 * parameters that don't fit on the playback UI.
 */
interface MlSettings : Settings<MlSettings.Listener> {
    /** When true, ListeningEventRecorder writes one row per finalized track listen. */
    val enableEventLogging: Boolean

    /** Number of logged events that triggers a one-shot RetrainWorker run. */
    val retrainThreshold: Int

    /**
     * Encoder version string. Bumping this re-embeds every track on next library scan, so we keep
     * it scoped to anything that affects the embedding distribution: encoder weights, mel-spec
     * params, audio decode + resampler. The predictor version is tracked separately via
     * [predictorVersion] so swapping in a fresh scorer doesn't trigger an unnecessary full library
     * re-embed.
     *
     * Sourced from `assets/ml/embedding_version.txt`.
     */
    val embeddingVersion: String

    /**
     * Predictor (state encoder + scorer) version string. Bumping this hot-swaps the ONNX assets but
     * leaves the embedding store intact. Sourced from `assets/ml/predictor_version.txt`.
     */
    val predictorVersion: String

    /**
     * `<embeddingVersion>+<predictorVersion>`. Diagnostic-only: written to JSONL logs and shown in
     * settings so we can correlate a sweep file with the exact (encoder, predictor) pair that
     * produced it.
     */
    val modelVersion: String
        get() = "$embeddingVersion+$predictorVersion"

    interface Listener {
        fun onEventLoggingToggleChanged() {}
    }

    companion object {
        // Wire-format constants. Kept here (not in strings.xml) because they are not
        // user-facing strings — they are stable preference keys that must not be localized.
        const val KEY_ENABLE_EVENT_LOGGING = "ml_enable_event_logging"
        const val KEY_RETRAIN_THRESHOLD = "ml_retrain_threshold"

        const val DEFAULT_RETRAIN_THRESHOLD = 1000

        const val EMBEDDING_VERSION_ASSET = "ml/embedding_version.txt"
        const val PREDICTOR_VERSION_ASSET = "ml/predictor_version.txt"
        const val FALLBACK_EMBEDDING_VERSION = "clap-htsat-fused-rank5+features-v9"
        const val FALLBACK_PREDICTOR_VERSION = "scoring-v0"
    }
}

class MlSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<MlSettings.Listener>(context), MlSettings {

    private val appContext = context.applicationContext

    override val enableEventLogging: Boolean
        get() = sharedPreferences.getBoolean(MlSettings.KEY_ENABLE_EVENT_LOGGING, false)

    override val retrainThreshold: Int
        get() =
            sharedPreferences.getInt(
                MlSettings.KEY_RETRAIN_THRESHOLD,
                MlSettings.DEFAULT_RETRAIN_THRESHOLD,
            )

    override val embeddingVersion: String by lazy {
        runCatching {
                appContext.assets.open(MlSettings.EMBEDDING_VERSION_ASSET).bufferedReader().use {
                    it.readText().trim().ifEmpty { MlSettings.FALLBACK_EMBEDDING_VERSION }
                }
            }
            .getOrDefault(MlSettings.FALLBACK_EMBEDDING_VERSION)
    }

    override val predictorVersion: String by lazy {
        runCatching {
                appContext.assets.open(MlSettings.PREDICTOR_VERSION_ASSET).bufferedReader().use {
                    it.readText().trim().ifEmpty { MlSettings.FALLBACK_PREDICTOR_VERSION }
                }
            }
            .getOrDefault(MlSettings.FALLBACK_PREDICTOR_VERSION)
    }

    override fun migrate() {
        // No legacy keys yet; reserved for future schema bumps. Intentionally empty so
        // LatentJam.onCreate can call settings.migrate() uniformly across all settings.
    }

    override fun onSettingChanged(key: String, listener: MlSettings.Listener) {
        when (key) {
            MlSettings.KEY_ENABLE_EVENT_LOGGING -> listener.onEventLoggingToggleChanged()
        }
    }

    /** Test-only hook — settings UI uses preference XML and doesn't need this. */
    fun setRetrainThreshold(value: Int) {
        sharedPreferences.edit { putInt(MlSettings.KEY_RETRAIN_THRESHOLD, value) }
    }
}
