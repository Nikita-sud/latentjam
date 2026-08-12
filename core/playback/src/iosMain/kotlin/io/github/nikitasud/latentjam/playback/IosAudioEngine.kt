/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.AVFAudio.AVAudioUnitEQ
import platform.AVFAudio.AVAudioUnitEQFilterParameters
import platform.AVFAudio.AVAudioUnitEQFilterTypeParametric
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The app-owned iOS audio graph: file player -> ten-band EQ -> main mixer.
 *
 * AVPlayer does not expose a node where an equalizer can be inserted. Owning this graph is what
 * makes the settings on iOS affect the samples that reach the speaker instead of being decorative
 * UI. Music.app protected items still use MPMusicPlayerController because iOS does not expose their
 * raw stream to third-party audio graphs; imported/local files use this path.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioEngine : EqualizerController {

    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val equalizer = AVAudioUnitEQ(numberOfBands = FREQUENCIES.size.toULong())
    private val preferences = NSUserDefaults.standardUserDefaults

    private val parameters: List<AVAudioUnitEQFilterParameters> =
        equalizer.bands.map { it as AVAudioUnitEQFilterParameters }

    private val mutableState = MutableStateFlow(EqualizerState())
    override val state: StateFlow<EqualizerState> = mutableState.asStateFlow()

    private var currentFile: AVAudioFile? = null
    private var segmentStartFrame: Long = 0L
    private var pausedFrame: Long = 0L
    private var completionGeneration: Long = 0L
    private var completion: (() -> Unit)? = null
    private var outputSupportsEqualizer: Boolean = true

    init {
        parameters.forEachIndexed { index, band ->
            band.filterType = AVAudioUnitEQFilterTypeParametric
            band.frequency = FREQUENCIES[index].toFloat()
            band.bandwidth = 1f
            band.bypass = false
        }
        equalizer.globalGain = 0f
        engine.attachNode(player)
        engine.attachNode(equalizer)
        engine.connect(player, equalizer, null)
        engine.connect(equalizer, engine.mainMixerNode, null)
        engine.prepare()
        restoreCurve()
        publishEqualizerState()
    }

    /**
     * Cues a local file and optionally starts it.
     *
     * Failure leaves no previous segment alive. The controller may already have moved its logical
     * playhead to this URL; keeping the old file running would put different audio under that new
     * metadata until another successful load.
     */
    fun load(url: NSURL, autoPlay: Boolean, onEnded: () -> Unit): Boolean {
        val file = runCatching { AVAudioFile(forReading = url, error = null) }.getOrNull()
            ?: run {
                stop()
                return false
            }
        completionGeneration++
        player.stop()
        currentFile = file
        segmentStartFrame = 0L
        pausedFrame = 0L
        completion = onEnded
        scheduleSegment(file, 0L)
        if (autoPlay) {
            if (!ensureEngineRunning()) {
                stop()
                return false
            }
            player.play()
        }
        return true
    }

    fun play(): Boolean {
        val file = currentFile ?: return false
        // A completed AVAudioPlayerNode segment is consumed. Calling play() again changes the
        // node's flag but emits no samples, so a transport press after natural end must schedule a
        // fresh segment just like an explicit seek to zero.
        if (shouldRestartConsumedSegment(pausedFrame, file.length)) {
            completionGeneration++
            player.stop()
            segmentStartFrame = 0L
            pausedFrame = 0L
            scheduleSegment(file, 0L)
        }
        if (!ensureEngineRunning()) return false
        player.play()
        return true
    }

    /** Music-library DRM playback bypasses this graph; reflect that honestly in Settings. */
    fun setOutputSupportsEqualizer(supported: Boolean) {
        if (outputSupportsEqualizer == supported) return
        outputSupportsEqualizer = supported
        publishEqualizerState()
    }

    fun pause() {
        pausedFrame = currentFrame()
        player.pause()
    }

    fun stop() {
        completionGeneration++
        player.stop()
        currentFile = null
        completion = null
        segmentStartFrame = 0L
        pausedFrame = 0L
    }

    fun seekTo(positionMs: Long) {
        val file = currentFile ?: return
        val wasPlaying = player.playing
        val frame = millisToFrame(positionMs, file).coerceIn(0L, file.length)
        completionGeneration++
        player.stop()
        segmentStartFrame = frame
        pausedFrame = frame
        scheduleSegment(file, frame)
        if (wasPlaying && ensureEngineRunning()) player.play()
    }

    val playing: Boolean
        get() = player.playing

    fun positionMs(): Long {
        val file = currentFile ?: return 0L
        return frameToMillis(currentFrame(), file)
    }

    fun durationMs(): Long? {
        val file = currentFile ?: return null
        return frameToMillis(file.length, file).takeIf { it > 0L }
    }

    private fun currentFrame(): Long {
        val file = currentFile ?: return 0L
        if (!player.playing) return pausedFrame.coerceIn(0L, file.length)
        val rendered = player.lastRenderTime ?: return pausedFrame
        val played = player.playerTimeForNodeTime(rendered) ?: return pausedFrame
        return (segmentStartFrame + played.sampleTime).coerceIn(0L, file.length)
    }

    private fun scheduleSegment(file: AVAudioFile, startFrame: Long) {
        val framesLeft = (file.length - startFrame).coerceAtLeast(0L)
        val token = ++completionGeneration
        player.scheduleSegment(
            file = file,
            startingFrame = startFrame,
            frameCount = framesLeft.coerceAtMost(UInt.MAX_VALUE.toLong()).toUInt(),
            atTime = null,
            completionCallbackType = AVAudioPlayerNodeCompletionDataPlayedBack,
            completionHandler = { _ ->
                dispatch_async(dispatch_get_main_queue()) {
                    if (token == completionGeneration && currentFile === file) {
                        pausedFrame = file.length
                        completion?.invoke()
                    }
                }
            },
        )
    }

    private fun ensureEngineRunning(): Boolean =
        engine.running || engine.startAndReturnError(null)

    private fun millisToFrame(milliseconds: Long, file: AVAudioFile): Long =
        (milliseconds.coerceAtLeast(0L) * file.processingFormat.sampleRate / 1000.0).toLong()

    private fun frameToMillis(frame: Long, file: AVAudioFile): Long {
        val sampleRate = file.processingFormat.sampleRate
        return if (sampleRate <= 0.0) 0L else (frame * 1000.0 / sampleRate).toLong()
    }

    // -------------------------------------------------------------- equalizer

    override suspend fun setEnabled(enabled: Boolean): Unit = withContext(Dispatchers.Main) {
        equalizer.bypass = !enabled
        preferences.setBool(enabled, KEY_ENABLED)
        publishEqualizerState()
    }

    override suspend fun setBandLevel(bandIndex: Int, levelMillibels: Int): Unit =
        withContext(Dispatchers.Main) {
            val band = parameters.getOrNull(bandIndex) ?: return@withContext
            val clamped = levelMillibels.coerceIn(MIN_LEVEL_MB, MAX_LEVEL_MB)
            band.gain = clamped / 100f
            preferences.setInteger(clamped.toLong(), bandKey(bandIndex))
            preferences.setInteger(NO_PRESET.toLong(), KEY_PRESET)
            preferences.setInteger(0L, KEY_BASS_BOOST)
            publishEqualizerState()
        }

    override suspend fun applyPreset(presetIndex: Int): Unit = withContext(Dispatchers.Main) {
        val curve = PRESET_CURVES.getOrNull(presetIndex) ?: return@withContext
        parameters.forEachIndexed { index, band ->
            val level = curve[index]
            band.gain = level / 100f
            preferences.setInteger(level.toLong(), bandKey(index))
        }
        preferences.setInteger(presetIndex.toLong(), KEY_PRESET)
        preferences.setInteger(0L, KEY_BASS_BOOST)
        publishEqualizerState()
    }

    override suspend fun setBassBoost(strength: Int): Unit = withContext(Dispatchers.Main) {
        val clamped = strength.coerceIn(0, 1000)
        val maximum = clamped * MAX_LEVEL_MB / 1000
        val lowBandScale = floatArrayOf(1f, 0.82f, 0.55f, 0.25f)
        parameters.forEachIndexed { index, band ->
            val level = if (index < lowBandScale.size) {
                (maximum * lowBandScale[index]).toInt()
            } else {
                0
            }
            band.gain = level / 100f
            preferences.setInteger(level.toLong(), bandKey(index))
        }
        preferences.setInteger(clamped.toLong(), KEY_BASS_BOOST)
        preferences.setInteger(NO_PRESET.toLong(), KEY_PRESET)
        publishEqualizerState()
    }

    override suspend fun reset(): Unit = withContext(Dispatchers.Main) {
        parameters.forEachIndexed { index, band ->
            band.gain = 0f
            preferences.setInteger(0L, bandKey(index))
        }
        preferences.setInteger(NO_PRESET.toLong(), KEY_PRESET)
        preferences.setInteger(0L, KEY_BASS_BOOST)
        publishEqualizerState()
    }

    private fun restoreCurve() {
        equalizer.bypass = !preferences.boolForKey(KEY_ENABLED)
        parameters.forEachIndexed { index, band ->
            val level = preferences.integerForKey(bandKey(index)).toInt()
                .coerceIn(MIN_LEVEL_MB, MAX_LEVEL_MB)
            band.gain = level / 100f
        }
    }

    private fun publishEqualizerState() {
        val activePreset = preferences.integerForKey(KEY_PRESET).toInt()
            .takeIf { it in PRESET_CURVES.indices }
        mutableState.value = EqualizerState(
            available = outputSupportsEqualizer,
            enabled = !equalizer.bypass,
            bands = parameters.mapIndexed { index, band ->
                EqualizerBand(index, FREQUENCIES[index], (band.gain * 100f).toInt())
            },
            presets = PRESET_NAMES.mapIndexed { index, name ->
                EqualizerPreset(index, name, EqualizerPresetKind.entries[index])
            },
            activePreset = activePreset,
            minLevelMillibels = MIN_LEVEL_MB,
            maxLevelMillibels = MAX_LEVEL_MB,
            bassBoostStrength = preferences.integerForKey(KEY_BASS_BOOST).toInt()
                .coerceIn(0, 1000),
            bassBoostSupported = true,
        )
    }

    private companion object {
        const val MIN_LEVEL_MB = -1200
        const val MAX_LEVEL_MB = 1200
        const val NO_PRESET = -1
        const val KEY_ENABLED = "ios_equalizer_enabled"
        const val KEY_PRESET = "ios_equalizer_preset"
        const val KEY_BASS_BOOST = "ios_equalizer_bass_boost"

        val FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
        val PRESET_NAMES = listOf("Flat", "Bass", "Treble", "Vocal", "Electronic")
        val PRESET_CURVES = listOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(700, 600, 450, 250, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, 0, 0, 0, 0, 100, 250, 450, 600, 700),
            intArrayOf(-200, -150, 0, 200, 400, 500, 400, 200, 0, -100),
            intArrayOf(500, 350, 100, 0, -150, 100, 300, 450, 500, 350),
        )

        fun bandKey(index: Int): String = "ios_equalizer_band_$index"
    }
}
