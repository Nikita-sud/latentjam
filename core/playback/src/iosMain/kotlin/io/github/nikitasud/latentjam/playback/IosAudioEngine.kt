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
import platform.AVFAudio.AVAudioEngineManualRenderingModeOffline
import platform.AVFAudio.AVAudioEngineManualRenderingStatusSuccess
import platform.AVFAudio.AVAudioPlayerNodeCompletionCallbackType
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataRendered
import platform.Foundation.NSFileManager
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
internal class IosAudioEngine(
    /**
     * Tests only: with a format the graph renders offline into buffers the test pulls ([renderOffline]) instead of
     * to a device. No sound and no device, and the nodes, their queues and their clocks are the framework's own.
     */
    private val offlineFormat: AVAudioFormat? = null,
) : EqualizerController {

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

    /**
     * The standby deck's segment has played out before its promotion — an incoming track shorter
     * than what it had to sound over, or one cued too near its own end to schedule. Its one end
     * was spent while it did not count yet, so [promoteOverlap] reports it on promotion instead.
     */
    private var standbyDrained = false

    /** The frame the standby deck's segment begins at: 0, or later for an overlap begun late. */
    private var standbyStartFrame = 0L

    /** Where the standby deck stopped when the graph paused; -1 while it plays or stands empty. */
    private var standbyPausedFrame = -1L

    /** Completion tokens: a segment's end counts only while its token is [completionGeneration]. */
    private var tokens: Long = 0L
    private var standbyToken: Long = NO_TOKEN
    private var segmentStartFrame: Long = 0L
    private var pausedFrame: Long = 0L
        set(value) {
            field = value
            // Cued or paused here: the node has rendered nothing since.
            lastRenderedFrame = null
        }

    /**
     * The last frame the current deck's render clock reported since it was cued or paused here. An interruption or a
     * route change stops the graph before this class hears of it, and a stopped graph has no render time: the
     * playhead is then here, not at [pausedFrame] (the last pause or seek, or the start of the track).
     */
    private var lastRenderedFrame: Long? = null

    /**
     * The system emptied the current deck in the middle of its track ([SegmentEnd.FLUSHED]): nothing is scheduled on
     * it any more, so before it plays again its segment is cued again from where it stood ([play]).
     */
    private var needsCue = false

    /**
     * The next track queued on the current deck right behind the current segment ([chainNext]): the node plays the
     * two sample after sample. [token] is its segment's, [onEnded] what its own end will call once it is current.
     */
    private class Chained(val file: AVAudioFile, val token: Long, val onSeam: () -> Unit, val onEnded: () -> Unit)

    private var chained: Chained? = null
    private var completionGeneration: Long = 0L
    private var completion: (() -> Unit)? = null
    private var outputSupportsEqualizer: Boolean = true

    init {
        offlineFormat?.let { format ->
            check(engine.enableManualRenderingMode(AVAudioEngineManualRenderingModeOffline, format, OFFLINE_QUANTUM, null)) {
                "Could not enter offline rendering"
            }
        }
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
        chained = null
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

    /** Why the last [load] that returned false did: the file's fault, or the output's ([RowLoad.NO_OUTPUT]). */
    var lastLoadFailure: RowLoad = RowLoad.UNREADABLE
        private set

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
                // A file that is there and readable, and still does not open, is one this device cannot decode.
                val readable = url.isFileURL() && url.path?.let(NSFileManager.defaultManager::isReadableFileAtPath) == true
                lastLoadFailure = if (readable) RowLoad.UNSUPPORTED else RowLoad.UNREADABLE
                stop()
                return false
            }
        // A file without frames can never yield a segment, and scheduleSegment forbids an empty
        // one, so such a file is unplayable here: fail the load like an unreadable URL and let
        // the controller walk on to the next candidate instead of cueing silence.
        if (file.length <= 0L || !supportsFormat(file)) {
            lastLoadFailure = if (file.length <= 0L) RowLoad.UNREADABLE else RowLoad.UNSUPPORTED
            stop()
            return false
        }
        invalidateCompletion()
        cancelOverlap()
        engine.stop()
        player.stop()
        chained = null
        currentFile = file
        segmentStartFrame = 0L
        pausedFrame = 0L
        completion = onEnded
        if (!configureFileGraph(file)) {
            lastLoadFailure = RowLoad.UNSUPPORTED
            stop()
            return false
        }
        scheduleSegment(file, 0L)
        if (autoPlay) {
            if (!ensureEngineRunning()) {
                // The output would not start; the file is sound.
                lastLoadFailure = RowLoad.NO_OUTPUT
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
        noteSystemStop()
        // A completed AVAudioPlayerNode segment is consumed. Calling play() again changes the
        // node's flag but emits no samples, so a transport press after natural end must schedule a
        // fresh segment just like an explicit seek to zero.
        if (shouldRestartConsumedSegment(pausedFrame, file.length)) {
            invalidateCompletion()
            cancelOverlap()
            engine.stop()
            player.stop()
            chained = null
            lj_eq_ios_reset(equalizer)
            segmentStartFrame = 0L
            pausedFrame = 0L
            scheduleSegment(file, 0L)
        } else if (needsCue) {
            cueAt(file, currentFrame(), resume = false)
        }
        if (!ensureEngineRunning()) return false
        player.play()
        if (standbyStarted) {
            standby.play()
            standbyPausedFrame = -1L
        }
        return true
    }

    /**
     * A node that says it plays on an engine that does not run: the system stopped the engine (this class pauses the
     * node before it pauses the engine). A stopped engine empties its nodes, and started again they stay silent while
     * still saying they play (IosPlayerNodeFactsTest), so the deck is cued again before it next sounds. The notice of
     * the emptied segment says the same ([SegmentEnd.FLUSHED]) but is not waited for: on a device it may not come.
     */
    private fun noteSystemStop() {
        if (currentFile != null && player.playing && !engine.running) {
            needsCue = true
            chained = null
        }
    }

    /** Music-library DRM playback bypasses this graph; reflect that honestly in Settings. */
    fun setOutputSupportsEqualizer(supported: Boolean) {
        if (outputSupportsEqualizer == supported) return
        outputSupportsEqualizer = supported
        publishEqualizerState()
    }

    fun pause() {
        noteSystemStop()
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
        engine.mainMixerNode.outputVolume = if (silentOutput) 0f else transportGain
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
        // A track queued behind the current one is this seam already; the caller takes it back first.
        if (chained != null) return null
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
        standbyDrained = false
        // Mid-waveform at a high level would click: a late start opens with a short fade written
        // into its first samples, sample-exact, before the rest of the segment follows.
        val rampFrames = if (startFrame > 0L) scheduleRampIn(file, startFrame) else 0L
        val segmentStart = startFrame + rampFrames
        if (segmentStart >= file.length) {
            standbyDrained = true
            return
        }
        standby.scheduleSegment(
            file = file,
            startingFrame = segmentStart,
            frameCount = (file.length - segmentStart).coerceIn(1L, UInt.MAX_VALUE.toLong()).toUInt(),
            atTime = null,
            completionCallbackType = segmentNotice,
            completionHandler = { _ ->
                dispatch_async(dispatch_get_main_queue()) {
                    // Only once promoted is this the playing track whose end moves the queue on.
                    if (token == completionGeneration && currentFile === file) {
                        segmentFinished(file, token)
                    } else if (token == standbyToken) {
                        when (segmentEnd(true, engine.running, standbyPlayedFrame(file), file.length, endToleranceFrames(file))) {
                            SegmentEnd.PLAYED_OUT -> standbyDrained = true
                            // Emptied by the system before it counted: no overlap is left to carry on with, and
                            // the seam is the plain one.
                            SegmentEnd.FLUSHED -> cancelOverlap()
                            SegmentEnd.STALE -> Unit
                        }
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
        if (lj_eq_ios_read_at(reader, buffer, startFrame, frames.toUInt()) == 0) return 0L
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
     * (SeamResamplingAudioSink). Otherwise the seam keeps the plain fades and the next track
     * loads, reconfiguring the graph for itself.
     */
    internal fun sharesGraphFormat(current: AVAudioFormat, incoming: AVAudioFormat): Boolean =
        current.channelCount == incoming.channelCount

    /** Starts the cued overlap under the current track. */
    fun startOverlap(fromMs: Long = 0L): Boolean {
        val file = standbyFile ?: return false
        // A node started on a stopped engine raises, which ends the app: the system stops the engine by itself (an
        // interruption, a change of route) and the current node may go on saying it plays.
        if (!player.playing || !engine.running) return false
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
        val played = playedFrames(standby) ?: return standbyStartFrame
        return (standbyStartFrame + played).coerceIn(0L, file.length)
    }

    /**
     * The frames [node] has played since it was started, or null when it cannot say: not playing, an engine that is
     * stopped (no render time), or one that has started and not rendered yet. That last render time exists and is
     * not valid, and the node raises when asked about it, which would end the app (IosPlayerNodeFactsTest).
     */
    private fun playedFrames(node: AVAudioPlayerNode): Long? {
        if (!node.playing) return null
        val rendered = node.lastRenderTime ?: return null
        if (!rendered.sampleTimeValid && !rendered.hostTimeValid) return null
        return node.playerTimeForNodeTime(rendered)?.sampleTime
    }

    /** Drops the standby deck's track, sounding or not. */
    fun cancelOverlap() {
        standbyPausedFrame = -1L
        standbyStartFrame = 0L
        standbyToken = NO_TOKEN
        standbyStarted = false
        standbyDrained = false
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
        if (standbyDrained) {
            // Its end went by unpromoted: nothing scheduled is left to report it, so the track
            // ends now, from where it stands — never replayed from its start.
            standbyDrained = false
            pausedFrame = file.length
            val generation = completionGeneration
            dispatch_async(dispatch_get_main_queue()) {
                if (completionGeneration == generation && currentFile === file) completion?.invoke()
            }
        }
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
        needsCue = false
        chained = null
        segmentStartFrame = 0L
        pausedFrame = 0L
    }

    fun seekTo(positionMs: Long, resume: Boolean = player.playing) {
        val file = currentFile ?: return
        cueAt(file, millisToFrame(positionMs, file), resume)
    }

    /** Whether a file is cued, playing or not. */
    val cued: Boolean get() = currentFile != null

    /** Whether the engine renders. False while paused here, and after the system stopped it under a playing track. */
    val running: Boolean get() = engine.running

    /**
     * Whether the output has a format the engine can start on. Between two routes it can have none (no channels, no
     * rate), and an engine started then fails or raises; the caller waits, or pauses, instead.
     */
    val outputReady: Boolean
        get() = engine.outputNode.outputFormatForBus(0u).let { it.channelCount > 0u && it.sampleRate > 0.0 }

    /**
     * Cues the current file again where it stands, without starting it, after the system stopped the engine (a
     * change of route, or of the output's format). A stopped engine is uninitialized and what its player node still
     * holds is not known; a fresh segment from the last frame that sounded is. The position is the one
     * [lastRenderedFrame] kept, so at most the ticker's interval is heard twice. False when no file is cued.
     */
    fun recueAfterSystemStop(): Boolean {
        val file = currentFile ?: return false
        val frame = currentFrame()
        // A segment that played out needs no cue: [play] schedules it afresh by itself.
        if (!player.playing && shouldRestartConsumedSegment(frame, file.length)) return true
        cueAt(file, frame, resume = false)
        return true
    }

    private fun cueAt(file: AVAudioFile, requestedFrame: Long, resume: Boolean) {
        // A segment must not start at file.length: it would contain no frames at all. Lyric taps
        // and the seek bar can ask for a position at or past the end, so the target stays on the
        // last frame and the item then ends through the scheduled segment's completion.
        val lastFrame = (file.length - 1L).coerceAtLeast(0L)
        val frame = requestedFrame.coerceIn(0L, lastFrame)
        // Stopping the node drops whatever is queued on it.
        chained = null
        invalidateCompletion()
        cancelOverlap()
        engine.stop()
        player.stop()
        // The new position starts mid-waveform: let it rise over 5 ms instead of clicking.
        lj_eq_ios_restart(equalizer)
        segmentStartFrame = frame
        pausedFrame = frame
        scheduleSegment(file, frame)
        if (resume && ensureEngineRunning()) player.play()
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
        val played = playedFrames(player)
        if (played != null && chained != null && segmentStartFrame + played >= file.length) {
            // The clock is past the current file's last frame: the queued track is what sounds.
            crossSeam()
            return currentFrame()
        }
        val frame = playheadFrame(played, segmentStartFrame, pausedFrame, lastRenderedFrame, file.length)
        if (played != null) lastRenderedFrame = frame
        return frame
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
        needsCue = false
        // The AU advertises its fixed latency. DataPlayedBack accounts for downstream
        // processing latency, so the delayed tail is rendered before advancing the queue.
        val token = ++tokens
        completionGeneration = token
        player.scheduleSegment(
            file = file,
            startingFrame = startFrame,
            frameCount = framesLeft.coerceAtMost(UInt.MAX_VALUE.toLong()).toUInt(),
            atTime = null,
            completionCallbackType = segmentNotice,
            completionHandler = { _ ->
                dispatch_async(dispatch_get_main_queue()) { segmentFinished(file, token) }
            },
        )
    }

    /**
     * The current deck's node says its segment of [file] is finished ([segmentEnd]): played out, which ends the
     * track; or emptied by the system stopping the engine mid-track, which does not, and leaves the deck to be cued
     * again before it can sound.
     */
    private fun segmentFinished(file: AVAudioFile, token: Long) {
        // The clock first: if the node is past a seam, the queued track is the current one before this notice is read
        // (the notice of a queued track that has itself played out is then the current track's).
        val frame = currentFrame()
        val current = token == completionGeneration && currentFile === file
        when (segmentEnd(current, engine.running, frame, file.length, endToleranceFrames(file))) {
            SegmentEnd.STALE -> Unit
            SegmentEnd.PLAYED_OUT -> if (chained == null) {
                pausedFrame = file.length
                completion?.invoke()
            }
            // Else a track is queued behind this one and the clock has not reached the seam: the clock decides when
            // the queued track is the current one, never this notice, whose timing on a device is not known here.
            SegmentEnd.FLUSHED -> {
                needsCue = true
                // Whatever was queued behind went with it.
                chained = null
            }
        }
    }

    /**
     * Queues [url] on the current deck right behind the track that is playing, for a seam without a gap. Only a
     * readable file of the same sample rate and channel count as the current one, only while the current one sounds
     * and nothing else is under way (an overlap, a deck that has to be cued again); false otherwise, and then the
     * plain seam applies. When the node's clock passes the current file's last frame the queued one becomes the
     * current file ([crossSeam]): [onSeam] is called once, and [onEnded] when the queued track itself has played out.
     */
    fun chainNext(url: NSURL, onSeam: () -> Unit, onEnded: () -> Unit): Boolean {
        val current = currentFile ?: return false
        if (chained != null || standbyFile != null || needsCue) return false
        if (!engine.running || !player.playing) return false
        val file = runCatching { AVAudioFile(forReading = url, error = null) }.getOrNull()
            ?.takeIf { it.length > 0L && supportsFormat(it) } ?: return false
        val playing = current.processingFormat
        val next = file.processingFormat
        // The node would convert another rate (with its converter's ringing) and raises on another layout.
        if (playing.channelCount != next.channelCount || playing.sampleRate != next.sampleRate) return false
        // Behind a segment that has already played out the file would simply start: that is no seam to adopt.
        if (currentFrame() >= current.length) return false
        val token = ++tokens
        chained = Chained(file, token, onSeam, onEnded)
        player.scheduleSegment(
            file = file,
            startingFrame = 0L,
            frameCount = file.length.coerceAtMost(UInt.MAX_VALUE.toLong()).toUInt(),
            atTime = null,
            completionCallbackType = segmentNotice,
            completionHandler = { _ ->
                dispatch_async(dispatch_get_main_queue()) { segmentFinished(file, token) }
            },
        )
        return true
    }

    /** Whether a track is queued behind the current one and has not begun yet. */
    val hasChained: Boolean get() = chained != null

    /** How many seams this engine has taken: tells a queued track that began from one that was dropped. */
    var seamsCrossed: Long = 0L
        private set

    /**
     * Takes the queued track back before it has begun (the queue changed, the sleep timer holds, repeat-one). A node
     * gives up one queued segment only with everything else, so the current track is cued again at the exact frame
     * it is at, which costs a moment of silence. If the seam has in fact been passed, the current track's last frame
     * is cued and its end takes the plain way.
     */
    fun dropChained() {
        if (chained == null) return
        val file = currentFile ?: return
        val resume = player.playing && engine.running
        chained = null
        cueAt(file, currentFrame(), resume)
    }

    /** The node has gone from the current file into the queued one: the queued one is the current file now. */
    private fun crossSeam() {
        val next = chained ?: return
        val previous = currentFile ?: return
        chained = null
        seamsCrossed++
        val start = seamSegmentStart(segmentStartFrame, previous.length)
        currentFile = next.file
        completion = next.onEnded
        completionGeneration = next.token
        segmentStartFrame = start
        pausedFrame = (start + (playedFrames(player) ?: -start)).coerceIn(0L, next.file.length)
        // Not from inside whatever asked for the position: the controller takes the row over on its own turn.
        dispatch_async(dispatch_get_main_queue()) { next.onSeam() }
    }

    /** How near its end a stopped engine's last known frame must be for a finished segment to count as played: a second. */
    private fun endToleranceFrames(file: AVAudioFile): Long = file.processingFormat.sampleRate.toLong()

    // A file AVAudioFile opens but cannot decode (32-bit FLAC in MP4) is unplayable, like an unreadable one, so the
    // controller walks on: scheduled, it plays nothing, and a crossfade's ramp-in read of it would end the app.
    private fun supportsFormat(file: AVAudioFile): Boolean =
        file.processingFormat.channelCount in 1u..32u && file.processingFormat.sampleRate > 0 &&
            file.processingFormat.sampleRate <= 768000 && lj_eq_ios_file_decodes(file) != 0

    private fun configureFileGraph(file: AVAudioFile): Boolean {
        // A prepared graph can allocate the AU after changing only one bus, briefly leaving its
        // old stereo output against a new mono input. Detach and set BOTH formats first.
        if (lj_eq_ios_connect_deck(engine, player, submix, playerBus, file.processingFormat) == 0) return false
        if (lj_eq_ios_connect(engine, submix, equalizer, file.processingFormat) == 0) return false
        // The main mixer plays a mono input 3 dB down (a mixer's pan law; measured in IosPlayerNodeFactsTest and
        // IosMonoLevelTest). Made up at the equalizer unit's output, a mono file is as loud as the same music in two
        // channels, which is what Android plays. Every deck of this graph has the file's layout and goes through
        // this one unit, so the one gain serves both.
        lj_eq_ios_set_output_gain(equalizer, if (file.processingFormat.channelCount == 1u) MONO_MAKE_UP else 1f)
        applySettings()
        engine.prepare()
        lj_eq_ios_reset(equalizer)
        applyMixerGain()
        return true
    }

    private fun ensureEngineRunning(): Boolean {
        if (engine.running) return true
        // Before the first frame can sound, not only at the next change of the gain.
        if (silentOutput) engine.mainMixerNode.outputVolume = 0f
        return engine.startAndReturnError(null)
    }

    /**
     * A debug build started with LATENTJAM_SILENT_OUTPUT=1 plays everything at volume zero: the Simulator sounds
     * through the Mac's speakers, and a check of real playback there (a seam, a recovery) has to be inaudible. The
     * whole graph still runs, its clocks and notices included. Never in a release build.
     */
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    private val silentOutput: Boolean =
        Platform.isDebugBinary && NSProcessInfo.processInfo.environment["LATENTJAM_SILENT_OUTPUT"] == "1"

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

    /**
     * When a segment reports itself finished: once it has been heard. Offline nothing is heard and that notice never
     * comes (IosPlayerNodeFactsTest), so there it is the notice that the segment was rendered.
     */
    private val segmentNotice: AVAudioPlayerNodeCompletionCallbackType =
        if (offlineFormat != null) AVAudioPlayerNodeCompletionDataRendered else AVAudioPlayerNodeCompletionDataPlayedBack

    /** Tests only ([offlineFormat]): renders [frames] and returns the first channel of what came out. */
    internal fun renderOffline(frames: Int): FloatArray {
        val format = checkNotNull(offlineFormat) { "Not an offline engine" }
        val buffer = checkNotNull(AVAudioPCMBuffer(pCMFormat = format, frameCapacity = OFFLINE_QUANTUM))
        val out = FloatArray(frames)
        var done = 0
        while (done < frames) {
            val count = minOf(OFFLINE_QUANTUM.toInt(), frames - done)
            check(engine.renderOffline(count.toUInt(), buffer, null) == AVAudioEngineManualRenderingStatusSuccess) { "Offline render failed" }
            val samples = checkNotNull(buffer.floatChannelData)[0]!!
            for (i in 0 until count) out[done + i] = samples[i]
            done += count
        }
        return out
    }

    /**
     * Tests only: stops the engine under whatever plays, without this class being told, which is what the system does
     * at a change of route or of the output's format.
     */
    internal fun stopEngineAsTheSystemDoes() {
        engine.stop()
    }

    private companion object {
        const val NO_TOKEN = -1L
        const val OFFLINE_QUANTUM = 512u

        /** The square root of two: what undoes a mixer's 3 dB for a mono input. */
        const val MONO_MAKE_UP = 1.4142135f

        /** The fade a late-started next track opens with, against a click. */
        const val RAMP_IN_MS = 20L
        const val KEY_ENABLED = "ios_equalizer_enabled"
        const val KEY_PRESET = "ios_equalizer_preset"
        const val KEY_BASS = "ios_equalizer_software_bass"
        const val KEY_VERSION = "ios_equalizer_software_version"
        fun bandKey(index: Int): String = "ios_equalizer_band_$index"
    }
}
