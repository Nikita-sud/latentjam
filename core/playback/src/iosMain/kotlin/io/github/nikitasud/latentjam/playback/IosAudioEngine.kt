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
 *
 * The graph is replaceable because a media services reset invalidates every audio object the app
 * holds — see [rebuildAfterMediaServicesReset].
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioEngine : EqualizerController {

    private var engine = AVAudioEngine()
    private var player = AVAudioPlayerNode()
    private var equalizer = AVAudioUnitEQ(numberOfBands = FREQUENCIES.size.toULong())
    private val preferences = NSUserDefaults.standardUserDefaults

    private var parameters: List<AVAudioUnitEQFilterParameters> = emptyList()

    private val mutableState = MutableStateFlow(EqualizerState())
    override val state: StateFlow<EqualizerState> = mutableState.asStateFlow()

    private var currentFile: AVAudioFile? = null
    private var segmentStartFrame: Long = 0L
    private var pausedFrame: Long = 0L
    private var completionGeneration: Long = 0L
    private var completion: (() -> Unit)? = null
    private var outputSupportsEqualizer: Boolean = true

    init {
        buildGraph()
        restoreCurve()
        publishEqualizerState()
    }

    /**
     * Attaches a fresh player -> EQ -> mixer chain and prepares it.
     *
     * Also the second half of [rebuildAfterMediaServicesReset]: the bands belong to the equalizer
     * instance, so they are re-collected here rather than kept from the previous graph.
     */
    private fun buildGraph() {
        parameters = equalizer.bands.map { it as AVAudioUnitEQFilterParameters }
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
    }

    /**
     * Recreates the whole graph after `AVAudioSessionMediaServicesWereResetNotification`.
     *
     * A media services reset invalidates every audio object the app owns, and it does so silently:
     * the objects keep their handles and the transport keeps its flags, while the graph renders
     * nothing. Apple's guidance is to dispose of the old objects and build new ones, so the engine,
     * the player node and the equalizer are replaced, the persisted curve is re-applied to the new
     * bands, and the cued file is scheduled again. The new position comes from the caller because a
     * dead player node cannot report one.
     *
     * @param resumePositionMs last published playhead of the cued file.
     * @return true when a file is cued again, so the caller can restart the player — this method
     *   never starts its own render loop, because the audio session the reset also invalidated has
     *   to be reconfigured first.
     */
    fun rebuildAfterMediaServicesReset(resumePositionMs: Long): Boolean {
        val file = currentFile
        val onEnded = completion
        engine.stop()
        // Drop every reference to the invalidated graph before the new one is built, so a throw
        // while building it cannot leave the engine scheduling segments on dead nodes.
        currentFile = null
        completion = null
        segmentStartFrame = 0L
        pausedFrame = 0L
        completionGeneration++
        engine = AVAudioEngine()
        player = AVAudioPlayerNode()
        equalizer = AVAudioUnitEQ(numberOfBands = FREQUENCIES.size.toULong())
        buildGraph()
        restoreCurve()
        publishEqualizerState()
        if (file == null || file.length <= 0L) return false
        currentFile = file
        completion = onEnded
        val lastFrame = (file.length - 1L).coerceAtLeast(0L)
        val start = millisToFrame(resumePositionMs, file).coerceIn(0L, lastFrame)
        segmentStartFrame = start
        pausedFrame = start
        scheduleSegment(file, start)
        return true
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
        // A file without frames can never yield a segment, and scheduleSegment forbids an empty
        // one, so such a file is unplayable here: fail the load like an unreadable URL and let
        // the controller walk on to the next candidate instead of cueing silence.
        if (file.length <= 0L) {
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
        } else {
            engine.pause()
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
        // Pausing a node alone leaves AVAudioEngine's hardware render loop running silently.
        // Keep its prepared graph for resume, but release the idle audio hardware now.
        engine.pause()
    }

    /** The pause/resume fade rides the mixer, leaving the player node and the equalizer alone. */
    fun setTransportGain(gain: Float) {
        engine.mainMixerNode.outputVolume = gain.coerceIn(0f, 1f)
    }

    fun stop() {
        completionGeneration++
        player.stop()
        engine.stop()
        currentFile = null
        completion = null
        segmentStartFrame = 0L
        pausedFrame = 0L
    }

    fun seekTo(positionMs: Long) {
        val file = currentFile ?: return
        val wasPlaying = player.playing
        // A segment must not start at file.length: it would contain no frames at all. Lyric taps
        // and the seek bar can ask for a position at or past the end, so the target stays on the
        // last frame and the item then ends through the scheduled segment's completion.
        val lastFrame = (file.length - 1L).coerceAtLeast(0L)
        val frame = millisToFrame(positionMs, file).coerceIn(0L, lastFrame)
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
        // AVAudioPlayerNode.scheduleSegment asserts "numberFrames > 0" inside CoreAudio, so an
        // empty range must never reach the node or the process dies. A playhead already at the end
        // of the file is therefore the end of playback, not a segment: park the transport there
        // and report the end so the queue can move on.
        if (framesLeft == 0L) {
            completionGeneration++
            segmentStartFrame = file.length
            pausedFrame = file.length
            completion?.invoke()
            return
        }
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
        preferences.setInteger(NO_PRESET.toLong(), KEY_PRESET)
        publishEqualizerState()
    }

    override suspend fun reset(): Unit = withContext(Dispatchers.Main) {
        parameters.forEachIndexed { index, band ->
            band.gain = 0f
            preferences.setInteger(0L, bandKey(index))
        }
        preferences.setInteger(NO_PRESET.toLong(), KEY_PRESET)
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
            bassBoostStrength = bassBoostStrength(),
            bassBoostSupported = true,
        )
    }

    /**
     * The bass-boost slider's position, read back from the bands themselves.
     *
     * iOS has no separate bass-boost effect: [setBassBoost] is a macro over the four lowest bands,
     * so their gains are the only honest report of how much boost is actually in the graph. A stored
     * strength beside them drifts the moment a preset or a hand-moved band rewrites the curve — the
     * slider then claims 0% bass boost while the low bands are still lifted — and it is what the
     * engine would restore from disk after a restart, when the curve is the part that is restored.
     */
    private fun bassBoostStrength(): Int {
        val strongest = parameters.firstOrNull() ?: return 0
        val level = (strongest.gain * 100f).toInt()
        if (level <= 0) return 0
        // Inverse of the slider's own `strength * MAX_LEVEL_MB / 1000`: rounding that division
        // drifts a unit off for 244 of the 1001 slider positions, so take the largest strength
        // that reproduces exactly this gain.
        return (((level + 1L) * 1000L - 1L) / MAX_LEVEL_MB).toInt().coerceIn(0, 1000)
    }

    private companion object {
        const val MIN_LEVEL_MB = -1200
        const val MAX_LEVEL_MB = 1200
        const val NO_PRESET = -1
        const val KEY_ENABLED = "ios_equalizer_enabled"
        const val KEY_PRESET = "ios_equalizer_preset"

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
