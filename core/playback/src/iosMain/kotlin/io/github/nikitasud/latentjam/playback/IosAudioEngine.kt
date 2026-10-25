/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.*
import io.github.nikitasud.latentjam.playback.nativeeq.*
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
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioMixerNode
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSProcessInfo
import kotlin.math.log10
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The app-owned iOS audio graph: two file players -> submixer -> shared Signalsmith DSP Audio Unit
 * -> main mixer. One player (the current deck) plays the track at the playhead; the other joins
 * only for an overlap, playing the next track's opening under the current one's end, and becomes
 * the current deck when that end has played ([promoteOverlap]). Each deck's volume carries its
 * track's loudness and transition curve; the main mixer carries the pause/resume fade.
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
    private var standby = AVAudioPlayerNode()
    private var submix = AVAudioMixerNode()
    private var playerBus = 0
    private var standbyBus = 1
    private val preferences = NSUserDefaults.standardUserDefaults
    private var settings = restoreSettings()
    private var equalizer = checkNotNull(lj_eq_ios_create()) { "Could not instantiate the shared EQ Audio Unit" }

    private val mutableState = MutableStateFlow(EqualizerState())
    override val state: StateFlow<EqualizerState> = mutableState.asStateFlow()
    override val processing: Flow<EqualizerProcessingState> = flow {
        var heldGain = 1f
        var heldUntil = 0.0
        while (currentCoroutineContext().isActive) {
            if (!outputSupportsEqualizer) emit(EqualizerProcessingState()) else {
                val now = NSProcessInfo.processInfo.systemUptime
                val gain = lj_eq_ios_take_meter_gain(equalizer)
                if (gain <= heldGain || now >= heldUntil) {
                    heldGain = gain
                    heldUntil = now + 0.4
                }
                val rate = lj_eq_ios_sample_rate(equalizer).takeIf { it > 0 }
                val reduction = if (heldGain < 1f) (-20 * log10(heldGain.toDouble())).toFloat() else 0f
                emit(EqualizerProcessingState(rate, reduction))
            }
            delay(100)
        }
    }.distinctUntilChanged()

    private var transportGain = 1f
    private var currentGain = 1f
    private var standbyGain = 0f
    private var currentFile: AVAudioFile? = null
    private var standbyFile: AVAudioFile? = null
    private var standbyStarted = false

    /** The frame the standby deck's segment begins at: 0, or later for an overlap begun late. */
    private var standbyStartFrame = 0L

    /** Where the standby deck stopped when the graph paused; -1 while it plays or stands empty. */
    private var standbyPausedFrame = -1L

    /** Completion tokens: a segment's end counts only while its token is [completionGeneration]. */
    private var tokens: Long = 0L
    private var standbyToken: Long = NO_TOKEN
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
     * Also the second half of [rebuildAfterMediaServicesReset]: each fresh Audio Unit owns its
     * C++ stream, and receives the saved settings before rendering starts.
     */
    private fun buildGraph() {
        playerBus = 0
        standbyBus = 1
        engine.attachNode(player)
        engine.attachNode(standby)
        engine.attachNode(submix)
        engine.attachNode(equalizer)
        engine.connect(player, submix, 0u, playerBus.toULong(), null)
        engine.connect(standby, submix, 0u, standbyBus.toULong(), null)
        engine.connect(submix, equalizer, null)
        engine.connect(equalizer, engine.mainMixerNode, null)
        applySettings()
        engine.prepare()
        applyMixerGain()
    }

    /**
     * Recreates the whole graph after `AVAudioSessionMediaServicesWereResetNotification`.
     *
     * A media services reset invalidates every audio object the app owns, and it does so silently:
     * the objects keep their handles and the transport keeps its flags, while the graph renders
     * nothing. Apple's guidance is to dispose of the old objects and build new ones, so the engine,
     * the player node and the equalizer are replaced, the persisted curve is re-applied to the new
     * C++ stream, and the cued file is scheduled again. The new position comes from the caller because a
     * dead player node cannot report one.
     *
     * @param resumePositionMs last published playhead of the cued file, or null when the file
     *   backend was idle. The graph is recreated in both cases.
     * @return true when a file is cued again, so the caller can restart the player — this method
     *   never starts its own render loop, because the audio session the reset also invalidated has
     *   to be reconfigured first.
     */
    fun rebuildAfterMediaServicesReset(resumePositionMs: Long?): Boolean {
        val fileUrl = currentFile?.url
        val onEnded = completion
        cancelOverlap()
        engine.stop()
        // Drop every reference to the invalidated graph before the new one is built, so a throw
        // while building it cannot leave the engine scheduling segments on dead nodes.
        currentFile = null
        completion = null
        segmentStartFrame = 0L
        pausedFrame = 0L
        invalidateCompletion()
        engine = AVAudioEngine()
        player = AVAudioPlayerNode()
        standby = AVAudioPlayerNode()
        submix = AVAudioMixerNode()
        equalizer = checkNotNull(lj_eq_ios_create())
        buildGraph()
        restoreCurve()
        publishEqualizerState()
        if (resumePositionMs == null || fileUrl == null) return false
        // AVAudioFile belongs to the old audio services too. Keep only its URL and reopen it;
        // a file removed in the meantime must not leave a stale handle in the new graph.
        val file = runCatching { AVAudioFile(forReading = fileUrl, error = null) }.getOrNull()
            ?.takeIf { it.length > 0L && supportsFormat(it) } ?: return false
        currentFile = file
        if (!configureFileGraph(file)) { stop(); return false }
        completion = onEnded
        val lastFrame = (file.length - 1L).coerceAtLeast(0L)
        val start = millisToFrame(resumePositionMs, file).coerceIn(0L, lastFrame)
        segmentStartFrame = start
        pausedFrame = start
        if (start > 0L) lj_eq_ios_restart(equalizer)
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
        if (file.length <= 0L || !supportsFormat(file)) {
            stop()
            return false
        }
        invalidateCompletion()
        cancelOverlap()
        engine.stop()
        player.stop()
        currentFile = file
        segmentStartFrame = 0L
        pausedFrame = 0L
        completion = onEnded
        if (!configureFileGraph(file)) { stop(); return false }
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
            invalidateCompletion()
            cancelOverlap()
            engine.stop()
            player.stop()
            lj_eq_ios_reset(equalizer)
            segmentStartFrame = 0L
            pausedFrame = 0L
            scheduleSegment(file, 0L)
        }
        if (!ensureEngineRunning()) return false
        player.play()
        if (standbyStarted) {
            standby.play()
            standbyPausedFrame = -1L
        }
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
        if (standbyStarted) {
            standbyPausedFrame = standbyFile?.let(::standbyPlayedFrame) ?: -1L
            standby.pause()
        }
        // Pausing a node alone leaves AVAudioEngine's hardware render loop running silently.
        // Keep its prepared graph for resume, but release the idle audio hardware now.
        engine.pause()
    }

    /** The pause/resume fade rides the mixer, leaving the player node and the equalizer alone. */
    fun setTransportGain(gain: Float) {
        transportGain = if (gain.isFinite()) gain.coerceIn(0f, 1f) else 1f
        applyMixerGain()
    }

    /**
     * Each deck's gain: the loudness of its track times its transition curve. The standby deck is
     * silent unless an overlap is sounding.
     */
    fun setDeckGains(current: Float, incoming: Float = 0f) {
        currentGain = if (current.isFinite()) current.coerceIn(0f, 1f) else 1f
        standbyGain = if (incoming.isFinite()) incoming.coerceIn(0f, 1f) else 0f
        applyMixerGain()
    }

    /** Effective platform volume of the current deck, after composing independent gain controls. */
    val effectiveOutputVolume: Float get() = engine.mainMixerNode.outputVolume * player.volume

    private fun applyMixerGain() {
        engine.mainMixerNode.outputVolume = transportGain
        player.volume = currentGain
        standby.volume = standbyGain
    }

    /**
     * Cues [url] on the standby deck for an overlap, silent and not yet playing; returns its
     * duration, or null when it cannot join — no current file, a stopped graph, an unreadable file,
     * or a file of another channel count than the graph's (see [sharesGraphFormat]).
     */
    fun prepareOverlap(url: NSURL, fromMs: Long = 0L): Long? {
        cancelOverlap()
        val current = currentFile ?: return null
        if (!engine.running) return null
        val file = runCatching { AVAudioFile(forReading = url, error = null) }.getOrNull()
            ?.takeIf { it.length > 0L && supportsFormat(it) } ?: return null
        // The graph after the submixer runs in the current file's format and stays so after the
        // decks swap; a track of another rate or layout would play through it converted for good.
        if (!sharesGraphFormat(current.processingFormat, file.processingFormat)) return null
        if (lj_eq_ios_connect_deck(engine, standby, submix, standbyBus, file.processingFormat) == 0) return null
        standbyGain = 0f
        applyMixerGain()
        standbyFile = file
        // Cued at the track's first sound ([seamAnchors]), its leading silence left out.
        scheduleStandby(file, millisToFrame(fromMs, file).coerceIn(0L, (file.length - 1L).coerceAtLeast(0L)))
        return frameToMillis(file.length, file)
    }

    /** Cues [file] on the standby deck from [startFrame]; its end counts once it is promoted. */
    private fun scheduleStandby(file: AVAudioFile, startFrame: Long) {
        val token = ++tokens
        standbyToken = token
        standbyStartFrame = startFrame
        // Mid-waveform at a high level would click: a late start opens with a short fade written
        // into its first samples, sample-exact, before the rest of the segment follows.
        val rampFrames = if (startFrame > 0L) scheduleRampIn(file, startFrame) else 0L
        val segmentStart = startFrame + rampFrames
        if (segmentStart >= file.length) return
        standby.scheduleSegment(
            file = file,
            startingFrame = segmentStart,
            frameCount = (file.length - segmentStart).coerceIn(1L, UInt.MAX_VALUE.toLong()).toUInt(),
            atTime = null,
            completionCallbackType = AVAudioPlayerNodeCompletionDataPlayedBack,
            completionHandler = { _ ->
                dispatch_async(dispatch_get_main_queue()) {
                    // Only once promoted is this the playing track whose end moves the queue on.
                    if (token == completionGeneration && currentFile === file) {
                        pausedFrame = file.length
                        completion?.invoke()
                    }
                }
            },
        )
    }

    /**
     * Schedules [RAMP_IN_MS] of [file] from [startFrame] on the standby deck with a linear fade-in
     * applied to the samples, read through a separate handle so the deck's own reads of [file]
     * are not disturbed. Returns the frames scheduled, 0 when they could not be read.
     */
    private fun scheduleRampIn(file: AVAudioFile, startFrame: Long): Long {
        val frames = millisToFrame(RAMP_IN_MS, file).coerceAtMost(file.length - startFrame)
        if (frames <= 0L) return 0L
        val reader = runCatching { AVAudioFile(forReading = file.url, error = null) }.getOrNull() ?: return 0L
        val buffer = AVAudioPCMBuffer(pCMFormat = reader.processingFormat, frameCapacity = frames.toUInt())
            ?: return 0L
        reader.framePosition = startFrame
        if (!reader.readIntoBuffer(buffer, frameCount = frames.toUInt(), error = null)) return 0L
        val read = buffer.frameLength.toInt()
        val channels = buffer.floatChannelData ?: return 0L
        for (channel in 0 until reader.processingFormat.channelCount.toInt()) {
            val samples = channels[channel] ?: continue
            for (i in 0 until read) samples[i] = samples[i] * i / read
        }
        standby.scheduleBuffer(buffer, null)
        return read.toLong()
    }

    /**
     * Whether a track of [incoming] format may join a graph configured for [current]: the same
     * channel count, as Android's mixer requires. Another sample rate joins: the submixer converts
     * its input bus to the graph's rate, and the track plays on converted after the decks swap until
     * the next load sets the graph to a file's own rate — what Android does across such a seam
     * ([SeamResamplingAudioProcessor]). Otherwise the seam keeps the plain fades and the next track
     * loads, reconfiguring the graph for itself.
     */
    internal fun sharesGraphFormat(current: AVAudioFormat, incoming: AVAudioFormat): Boolean =
        current.channelCount == incoming.channelCount

    /** Starts the cued overlap under the current track. */
    fun startOverlap(fromMs: Long = 0L): Boolean {
        val file = standbyFile ?: return false
        if (!player.playing) return false
        // Begun late ([lateOverlapStartMs]) — a seek into the overlap's window, the slider raised
        // inside it — the incoming track joins where the overlap has got to, on the same clock as
        // the outgoing curve, instead of replaying its opening from a quiet start. The point it
        // was cued at plays the segment cued ahead, opening and all.
        val fromFrame = millisToFrame(fromMs, file).coerceIn(0L, (file.length - 1L).coerceAtLeast(0L))
        if (fromFrame != standbyStartFrame) {
            standby.stop()
            scheduleStandby(file, fromFrame)
        }
        standby.play()
        standbyStarted = true
        return true
    }

    /** Whether the next track is sounding under the current one. */
    val overlapping: Boolean get() = standbyStarted

    /** How far into its own file the overlap's incoming track is. */
    fun standbyPositionMs(): Long {
        val file = standbyFile ?: return 0L
        if (!standbyStarted) return 0L
        return frameToMillis(standbyPlayedFrame(file), file)
    }

    private fun standbyPlayedFrame(file: AVAudioFile): Long {
        // A paused graph has no render time: the frame it reached was kept when it paused.
        if (!standby.playing && standbyPausedFrame >= 0L) return standbyPausedFrame
        val rendered = standby.lastRenderTime ?: return standbyStartFrame
        val played = standby.playerTimeForNodeTime(rendered) ?: return standbyStartFrame
        return (standbyStartFrame + played.sampleTime).coerceIn(0L, file.length)
    }

    /** Drops the standby deck's track, sounding or not. */
    fun cancelOverlap() {
        standbyPausedFrame = -1L
        standbyStartFrame = 0L
        standbyToken = NO_TOKEN
        standbyStarted = false
        standbyFile = null
        standby.stop()
        standbyGain = 0f
        standby.volume = 0f
    }

    /**
     * The current track's end has played: the overlap's incoming track becomes the current one,
     * carrying on where it is, and its end now calls [onEnded].
     */
    fun promoteOverlap(onEnded: () -> Unit): Boolean {
        val file = standbyFile ?: return false
        if (!standbyStarted) return false
        // Where the incoming track is, kept for a promotion while paused (Next during a paused
        // overlap): a paused deck reports its position from pausedFrame.
        val playedFrame = standbyPlayedFrame(file)
        val previous = player
        val previousBus = playerBus
        previous.stop()
        player = standby
        playerBus = standbyBus
        standby = previous
        standbyBus = previousBus
        currentFile = file
        completion = onEnded
        completionGeneration = standbyToken
        standbyToken = NO_TOKEN
        standbyFile = null
        standbyStarted = false
        segmentStartFrame = standbyStartFrame
        pausedFrame = playedFrame
        standbyStartFrame = 0L
        standbyPausedFrame = -1L
        currentGain = standbyGain
        standbyGain = 0f
        applyMixerGain()
        return true
    }

    private fun invalidateCompletion() {
        completionGeneration = NO_TOKEN
    }

    fun stop() {
        invalidateCompletion()
        cancelOverlap()
        player.stop()
        engine.stop()
        lj_eq_ios_reset(equalizer)
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
        invalidateCompletion()
        cancelOverlap()
        engine.stop()
        player.stop()
        // The new position starts mid-waveform: let it rise over 5 ms instead of clicking.
        lj_eq_ios_restart(equalizer)
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
            invalidateCompletion()
            segmentStartFrame = file.length
            pausedFrame = file.length
            completion?.invoke()
            return
        }
        // The AU advertises its fixed latency. DataPlayedBack accounts for downstream
        // processing latency, so the delayed tail is rendered before advancing the queue.
        val token = ++tokens
        completionGeneration = token
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

    private fun supportsFormat(file: AVAudioFile): Boolean =
        file.processingFormat.channelCount in 1u..32u && file.processingFormat.sampleRate > 0 &&
            file.processingFormat.sampleRate <= 768000

    private fun configureFileGraph(file: AVAudioFile): Boolean {
        // A prepared graph can allocate the AU after changing only one bus, briefly leaving its
        // old stereo output against a new mono input. Detach and set BOTH formats first.
        if (lj_eq_ios_connect_deck(engine, player, submix, playerBus, file.processingFormat) == 0) return false
        if (lj_eq_ios_connect(engine, submix, equalizer, file.processingFormat) == 0) return false
        applySettings()
        engine.prepare()
        lj_eq_ios_reset(equalizer)
        applyMixerGain()
        return true
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

    override suspend fun setEnabled(enabled: Boolean) { edit { it.copy(enabled = enabled) } }
    override suspend fun setBandLevel(bandIndex: Int, levelMillibels: Int) {
        if (bandIndex !in SoftwareEqualizerSettings.FREQUENCIES.indices) return
        edit { old -> old.copy(levels = old.levels.mapIndexed { index, level ->
            if (index == bandIndex) levelMillibels else level
        }, preset = null) }
    }
    override suspend fun applyPreset(presetIndex: Int) { edit { it.withPreset(presetIndex) } }
    override suspend fun setBassBoost(strength: Int) { edit { it.copy(bass = strength, preset = null) } }
    override suspend fun reset() { edit { SoftwareEqualizerSettings(enabled = it.enabled, preset = 0) } }

    private suspend fun edit(change: (SoftwareEqualizerSettings) -> SoftwareEqualizerSettings): Unit =
        withContext(Dispatchers.Main.immediate) {
            val updated = change(settings).sanitized()
            if (updated != settings) {
                settings = updated
                persistSettings()
                applySettings()
                publishEqualizerState()
            }
        }

    private fun applySettings() {
        settings.levels.toIntArray().usePinned {
            lj_eq_ios_set_settings(equalizer, if (settings.enabled) 1 else 0, it.addressOf(0), settings.bass)
        }
    }

    private fun restoreCurve() { settings = restoreSettings(); applySettings() }

    private fun restoreSettings(): SoftwareEqualizerSettings {
        // Preserve the old native curve exactly as band settings. Its bass macro already changed
        // those bands; the new independent shelf starts at zero to avoid applying it twice.
        val software = preferences.integerForKey(KEY_VERSION).toInt() == 1
        val levels = List(10) { preferences.integerForKey(bandKey(it)).toInt() }
        val preset = preferences.integerForKey(KEY_PRESET).toInt().takeIf {
            preferences.objectForKey(KEY_PRESET) != null && it in SoftwareEqualizerSettings.PRESETS.indices &&
                levels == SoftwareEqualizerSettings.PRESETS[it]
        }
        return SoftwareEqualizerSettings(
            enabled = preferences.boolForKey(KEY_ENABLED), levels = levels,
            bass = if (software) preferences.integerForKey(KEY_BASS).toInt() else 0, preset = preset,
        ).sanitized()
    }

    private fun persistSettings() {
        preferences.setInteger(1, KEY_VERSION)
        preferences.setBool(settings.enabled, KEY_ENABLED)
        preferences.setInteger((settings.preset ?: -1).toLong(), KEY_PRESET)
        preferences.setInteger(settings.bass.toLong(), KEY_BASS)
        settings.levels.forEachIndexed { index, value -> preferences.setInteger(value.toLong(), bandKey(index)) }
    }

    private fun publishEqualizerState() {
        mutableState.value = settings.asState().copy(available = outputSupportsEqualizer)
    }

    private companion object {
        const val NO_TOKEN = -1L

        /** The fade a late-started next track opens with, against a click. */
        const val RAMP_IN_MS = 20L
        const val KEY_ENABLED = "ios_equalizer_enabled"
        const val KEY_PRESET = "ios_equalizer_preset"
        const val KEY_BASS = "ios_equalizer_software_bass"
        const val KEY_VERSION = "ios_equalizer_software_version"
        fun bandKey(index: Int): String = "ios_equalizer_band_$index"
    }
}
