/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android [EqualizerController] over `android.media.audiofx`.
 *
 * The effect binds to an audio SESSION, which only exists once the player has been built, and dies
 * with it. So this class is told the session id by [PlaybackService] via [attachTo] and re-applies
 * the stored curve each time — the user's settings live in preferences, not in the effect.
 *
 * Every band, frequency and preset is read from the device. Effect implementations differ (five
 * bands is common, ten is not rare), and drawing a fixed set of sliders would misrepresent what is
 * actually being changed.
 */
internal class AndroidEqualizerController(context: Context) : EqualizerController {

    private val preferences = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(EqualizerState())
    override val state: StateFlow<EqualizerState> = mutableState.asStateFlow()

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    /**
     * Guards the native effect handles.
     *
     * The session listener runs on whichever thread the service published from — its main thread —
     * while band and preset edits arrive on [Dispatchers.IO], so without this an edit could take a
     * handle just as it is released, or land on the effect the listener has already replaced and
     * disappear with it: the user's band is stored but never heard. Every native call is short and
     * none of them suspends, so one monitor for both sides is enough.
     */
    private val effectLock = Any()

    init {
        // Follows the player's session for the app's lifetime: the service can be torn down and
        // rebuilt underneath us, and each new session needs the stored curve applied again. When
        // the session goes away, fall back to a probe rather than nothing, so the screen stays a
        // working equalizer instead of claiming the device has none.
        AudioSessionRegistry.observe { sessionId ->
            if (sessionId == null) attachToProbe() else attachTo(sessionId)
        }
        // observe() only replays a session that already exists, so with nothing playing the effect
        // would never attach and the screen would wrongly read "no equalizer". Attach a probe up
        // front so the controls are usable before the first track — the curve is persisted and
        // re-applied to the real player session the moment playback starts.
        if (hasNoEffect()) attachToProbe()
    }

    /**
     * Attaches to a throwaway session so capabilities can be read and the curve pre-configured
     * without anything playing.
     *
     * A generated session id backs a real [Equalizer] the framework will happily create; it just
     * carries no audio. That is enough to render the device's actual bands and presets and to hold
     * the user's edits, all of which persist and transfer to the player session on [attachTo].
     */
    private fun attachToProbe() {
        val probeSession = runCatching { audioManager.generateAudioSessionId() }.getOrNull()
        if (probeSession == null || probeSession <= 0) {
            release()
            mutableState.value = EqualizerState(available = false)
            return
        }
        attachTo(probeSession)
    }

    /**
     * Binds to a freshly created audio session, restoring whatever the user last set.
     *
     * Failures are swallowed into `available = false`: some devices, and most emulators, have no
     * effect implementation at all, and that is a state to explain rather than a crash.
     */
    private fun attachTo(audioSessionId: Int) {
        synchronized(effectLock) {
            release()
            runCatching {
                val effect = Equalizer(EFFECT_PRIORITY, audioSessionId)
                equalizer = effect
                bassBoost = runCatching { BassBoost(EFFECT_PRIORITY, audioSessionId) }.getOrNull()
                restore(effect)
                publish()
            }.onFailure {
                release()
                mutableState.value = EqualizerState(available = false)
            }
        }
    }

    private fun release() {
        synchronized(effectLock) {
            runCatching { equalizer?.release() }
            runCatching { bassBoost?.release() }
            equalizer = null
            bassBoost = null
        }
    }

    /** Whether no effect is attached; read under [effectLock] so the init probe cannot race one. */
    private fun hasNoEffect(): Boolean = synchronized(effectLock) { equalizer == null }

    /**
     * Runs [block] on the attached effect under [effectLock], and does nothing when no effect is
     * attached — the device may have no implementation, and the listener may be between sessions.
     *
     * Every edit goes through here so it can never touch a handle that [release] is freeing or that
     * [attachTo] has replaced; the failure of the native call itself is still swallowed, because a
     * refused band must not take the settings screen down.
     */
    private inline fun withAttachedEffect(block: (Equalizer) -> Unit): Unit = synchronized(effectLock) {
        val effect = equalizer
        if (effect != null) runCatching { block(effect) }
        Unit
    }

    private fun restore(effect: Equalizer) {
        val enabled = preferences.getBoolean(KEY_ENABLED, false)
        val storedPreset = preferences.getInt(KEY_PRESET, NO_PRESET)
        effect.enabled = enabled
        bassBoost?.let { boost ->
            boost.enabled = enabled && boost.strengthSupported
            if (boost.strengthSupported) {
                boost.setStrength(preferences.getInt(KEY_BASS_BOOST, 0).toShort())
            }
        }
        if (storedPreset != NO_PRESET && storedPreset < effect.numberOfPresets) {
            effect.usePreset(storedPreset.toShort())
            return
        }
        for (band in 0 until effect.numberOfBands) {
            val stored = preferences.getInt(bandKey(band), Int.MIN_VALUE)
            if (stored != Int.MIN_VALUE) {
                effect.setBandLevel(band.toShort(), stored.coerceIn(effect).toShort())
            }
        }
    }

    override suspend fun setEnabled(enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        withAttachedEffect { effect ->
            effect.enabled = enabled
            bassBoost?.let { if (it.strengthSupported) it.enabled = enabled }
            preferences.update { putBoolean(KEY_ENABLED, enabled) }
            publish()
        }
    }

    override suspend fun setBandLevel(bandIndex: Int, levelMillibels: Int): Unit =
        withContext(Dispatchers.IO) {
            withAttachedEffect { effect ->
                val level = levelMillibels.coerceIn(effect)
                effect.setBandLevel(bandIndex.toShort(), level.toShort())
                preferences.update {
                    putInt(bandKey(bandIndex), level)
                    // Touching a band means the curve is no longer the named preset it came from.
                    putInt(KEY_PRESET, NO_PRESET)
                }
                publish()
            }
        }

    override suspend fun applyPreset(presetIndex: Int): Unit = withContext(Dispatchers.IO) {
        withAttachedEffect { effect ->
            effect.usePreset(presetIndex.toShort())
            preferences.update {
                putInt(KEY_PRESET, presetIndex)
                // The preset defines every band; stale per-band overrides would fight it on restore.
                for (band in 0 until effect.numberOfBands) remove(bandKey(band))
            }
            publish()
        }
    }

    override suspend fun setBassBoost(strength: Int): Unit = withContext(Dispatchers.IO) {
        withAttachedEffect {
            val boost = bassBoost
            if (boost?.strengthSupported == true) {
                val clamped = strength.coerceIn(0, 1000)
                boost.setStrength(clamped.toShort())
                preferences.update { putInt(KEY_BASS_BOOST, clamped) }
                publish()
            }
        }
    }

    override suspend fun reset(): Unit = withContext(Dispatchers.IO) {
        withAttachedEffect { effect ->
            for (band in 0 until effect.numberOfBands) effect.setBandLevel(band.toShort(), 0)
            bassBoost?.takeIf { it.strengthSupported }?.setStrength(0)
            preferences.update {
                for (band in 0 until effect.numberOfBands) remove(bandKey(band))
                putInt(KEY_PRESET, NO_PRESET)
                putInt(KEY_BASS_BOOST, 0)
            }
            publish()
        }
    }

    private fun publish() {
        val effect = equalizer
        if (effect == null) {
            mutableState.value = EqualizerState(available = false)
            return
        }
        val range = effect.bandLevelRange
        val boost = bassBoost
        mutableState.value = EqualizerState(
            available = true,
            enabled = effect.enabled,
            bands = (0 until effect.numberOfBands).map { band ->
                EqualizerBand(
                    index = band,
                    // The platform reports milliHertz.
                    centreFrequencyHz = effect.getCenterFreq(band.toShort()) / 1000,
                    levelMillibels = effect.getBandLevel(band.toShort()).toInt(),
                )
            },
            presets = (0 until effect.numberOfPresets).map { preset ->
                EqualizerPreset(preset, effect.getPresetName(preset.toShort()))
            },
            activePreset = preferences.getInt(KEY_PRESET, NO_PRESET).takeIf { it != NO_PRESET },
            minLevelMillibels = range[0].toInt(),
            maxLevelMillibels = range[1].toInt(),
            bassBoostStrength = preferences.getInt(KEY_BASS_BOOST, 0),
            bassBoostSupported = boost?.strengthSupported == true,
        )
    }

    private fun Int.coerceIn(effect: Equalizer): Int {
        val range = effect.bandLevelRange
        return coerceIn(range[0].toInt(), range[1].toInt())
    }

    /** Local stand-in for core-ktx's `edit`, so this module needs no extra dependency. */
    private inline fun SharedPreferences.update(block: SharedPreferences.Editor.() -> Unit) {
        val editor = edit()
        editor.block()
        editor.apply()
    }

    private companion object {
        /** Above 0 so the effect survives lower-priority apps also attaching to the session. */
        const val EFFECT_PRIORITY = 1
        const val NO_PRESET = -1
        const val KEY_ENABLED = "enabled"
        const val KEY_PRESET = "preset"
        const val KEY_BASS_BOOST = "bass_boost"
        fun bandKey(band: Int) = "band_$band"
    }
}

public actual fun equalizerModule(): Module = module {
    single<EqualizerController> { AndroidEqualizerController(get()) }
}
