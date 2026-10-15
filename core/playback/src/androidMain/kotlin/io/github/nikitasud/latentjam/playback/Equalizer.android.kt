/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The service and UI share one app-owned curve, including when playback starts without an Activity.
 * Playback consumes immutable snapshots on its audio thread. Native effects are only queried once
 * to migrate old device-specific curves; they are never attached to the player's audio session.
 */
internal class AndroidEqualizerController private constructor(context: Context) : EqualizerController {
    private val preferences = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private val store = AndroidEqualizerSettingsStore(preferences) { migrateNativeCurve(context) }
    @Volatile internal var settings: SoftwareEqualizerSettings = store.restore()
        private set
    private val mutableState = MutableStateFlow(settings.asState())
    override val state: StateFlow<EqualizerState> = mutableState.asStateFlow()
    private val lock = Any()
    internal val meter = EqualizerMeter()
    override val processing: Flow<EqualizerProcessingState> = flow {
        while (currentCoroutineContext().isActive) {
            emit(meter.snapshot())
            delay(100)
        }
    }.distinctUntilChanged()

    private fun migrateNativeCurve(context: Context): List<Int>? {
        var effect: Equalizer? = null
        return try {
            val session = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
            if (session <= 0) return null
            val native = Equalizer(0, session).also { effect = it }
            val preset = preferences.getInt("preset", -1)
            if (preset in 0 until native.numberOfPresets) native.usePreset(preset.toShort())
            val bands = (0 until native.numberOfBands).map { band ->
                val level = if (preset in 0 until native.numberOfPresets) native.getBandLevel(band.toShort()).toInt()
                    else preferences.getInt("band_$band", 0)
                native.getCenterFreq(band.toShort()) / 1000.0 to level
            }.filter { it.first > 0 }.sortedBy { it.first }
            if (bands.isEmpty()) return null
            SoftwareEqualizerSettings.FREQUENCIES.map { frequency ->
                val upper = bands.indexOfFirst { it.first >= frequency }
                when {
                    upper == 0 -> bands.first().second
                    upper < 0 -> bands.last().second
                    else -> {
                        val (lowHz, low) = bands[upper - 1]
                        val (highHz, high) = bands[upper]
                        val fraction = ln(frequency / lowHz) / ln(highHz / lowHz)
                        (low + fraction * (high - low)).roundToInt()
                    }
                }
            }
        } catch (failure: Exception) {
            Log.w("LatentJamEQ", "Could not migrate the device-specific curve; using flat bands", failure)
            null
        } finally {
            runCatching { effect?.release() }
        }
    }

    private suspend fun edit(
        replaceCurve: Boolean = false,
        change: (SoftwareEqualizerSettings) -> SoftwareEqualizerSettings,
    ) =
        withContext(Dispatchers.Main.immediate) {
            synchronized(lock) {
                val updated = change(settings).sanitized()
                if (updated != settings || (replaceCurve && store.migrationPending)) {
                    store.persist(updated, replaceCurve)
                    settings = updated
                    mutableState.value = updated.asState()
                }
            }
        }

    override suspend fun setEnabled(enabled: Boolean) { edit { it.copy(enabled = enabled) } }
    override suspend fun setBandLevel(bandIndex: Int, levelMillibels: Int) {
        if (bandIndex !in SoftwareEqualizerSettings.FREQUENCIES.indices) return
        edit(replaceCurve = true) { old -> old.copy(levels = old.levels.mapIndexed { index, level ->
            if (index == bandIndex) levelMillibels else level
        }, preset = null) }
    }
    override suspend fun applyPreset(presetIndex: Int) {
        if (presetIndex in SoftwareEqualizerSettings.PRESETS.indices) edit(replaceCurve = true) { it.withPreset(presetIndex) }
    }
    override suspend fun setBassBoost(strength: Int) { edit { it.copy(bass = strength, preset = null) } }
    override suspend fun reset() { edit(replaceCurve = true) { SoftwareEqualizerSettings(enabled = it.enabled, preset = 0) } }

    companion object {
        @Volatile private var instance: AndroidEqualizerController? = null
        fun get(context: Context): AndroidEqualizerController = instance ?: synchronized(this) {
            instance ?: AndroidEqualizerController(context.applicationContext).also { instance = it }
        }
    }
}

public actual fun equalizerModule(): Module = module {
    single<EqualizerController> { AndroidEqualizerController.get(get()) }
}
