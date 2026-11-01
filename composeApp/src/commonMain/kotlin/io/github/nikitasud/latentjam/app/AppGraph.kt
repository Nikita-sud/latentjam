/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.FavoriteTimes
import io.github.nikitasud.latentjam.smart.idList
import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.RecentSearches
import io.github.nikitasud.latentjam.history.ForYouImpressions
import io.github.nikitasud.latentjam.history.SmartExclusions
import io.github.nikitasud.latentjam.history.SmartImpressionLog
import io.github.nikitasud.latentjam.history.SmartPresses
import io.github.nikitasud.latentjam.history.listeningHistoryModule
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.library.musicLibraryModule
import io.github.nikitasud.latentjam.library.nowMillis
import io.github.nikitasud.latentjam.playback.NextTrackChooser
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.SleepTimerController
import io.github.nikitasud.latentjam.playback.EqualizerController
import io.github.nikitasud.latentjam.playback.equalizerModule
import io.github.nikitasud.latentjam.playback.playbackModule
import io.github.nikitasud.latentjam.smart.IndexStore
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.SmartEngineConfig
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.HabitFlow
import io.github.nikitasud.latentjam.smart.ranker.SaltedKey
import io.github.nikitasud.latentjam.smart.ranker.SmartRankerAssets
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.di.smartEngineModule
import io.github.nikitasud.latentjam.smart.di.smartLayoutQualifier
import io.github.nikitasud.latentjam.smart.di.smartMapLayoutDispatcherQualifier
import io.github.nikitasud.latentjam.smart.smartChainInputsModule
import io.github.nikitasud.latentjam.smart.smartEngineBackendModule
import io.github.nikitasud.latentjam.smart.text.smartTextEncoderModule
import io.github.nikitasud.latentjam.smart.text.MusicEntityResolver
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The app's single composition root, shared verbatim by the Android and iOS
 * entry points.
 *
 * A SCOPED Koin application (not the `startKoin` global) — nothing else in
 * the process can collide with it. Each platform entry point calls [start]
 * exactly once before touching [engine]/[library], passing whatever platform
 * bindings its modules need (Android contributes an `android.content.Context`
 * for the MediaStore-backed library; iOS passes nothing).
 *
 * [start] is idempotent but not thread-safe by design: both entry points call
 * it from the platform's main thread during launch, before any concurrent
 * access exists.
 *
 * Resolving [engine]/[library] is allocation-only (all heavy work hides
 * behind their suspend functions), so both are safe to read while building
 * the very first UI frame.
 */
object AppGraph {
    internal val normalizationStatus = MutableStateFlow(NormalizationStatus())

    internal val lyricsSearchCache = LyricsSearchCache()

    private var koinApp: KoinApplication? = null

    /**
     * App-lifetime scope for work that must outlive any single composition
     * (e.g. library indexing). Dies with the process; WorkManager-grade
     * scheduling is a later roadmap item.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutableAutomaticIndexing = MutableStateFlow(AutomaticIndexingState())
    val automaticIndexing: StateFlow<AutomaticIndexingState> =
        mutableAutomaticIndexing.asStateFlow()
    private val mutableHistoryRevision = MutableStateFlow(0L)

    /**
     * What the current queue was started from — set by the surface that starts a queue, shown as
     * the player's "Playing from" line, and persisted with the resume session so the label
     * survives a restart. Null means unknown, which simply shows nothing.
     */
    val queueSource = MutableStateFlow<QueueSource?>(null)

    /**
     * The playlists the listener marked "keep together in SMART", as bare id-set groups. Kept
     * current by the App whenever playlists change; read by every SMART queue planner.
     */
    val smartCompanionGroups = MutableStateFlow<List<Set<TrackId>>>(emptyList())

    /** Every playlist (app and imported M3U) as its ordered ids; read only by FULL ranker plans ("Learn from my habits"). */
    val smartUserPlaylists = MutableStateFlow<List<List<TrackId>>>(emptyList())

    /** The unmarked playlists, as the chain's personal term reads them ("Learn from my habits"). */
    val smartPersonalPlaylists = MutableStateFlow<List<List<TrackId>>>(emptyList())

    /** SMART's eligible library as the App last handed it to playback; read by the habit slots. */
    val smartLibraryTracks = MutableStateFlow<List<TrackDescriptor>>(emptyList())
    /** Advances after each accepted listening event so For You can apply feedback next time it opens. */
    val historyRevision: StateFlow<Long> = mutableHistoryRevision.asStateFlow()

    /**
     * Reports listens written without playback (a ListenBrainz import): every reader keyed on
     * [historyRevision] — For You, Statistics, Search, auto playlists — then rebuilds from the log.
     */
    fun noteHistoryImported() {
        mutableHistoryRevision.value += 1L
    }
    private val historyFlushRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var automaticIndexingKey: AutomaticIndexingRequest? = null
    private var automaticIndexingJob: Job? = null

    /** Initializes the graph. Call once from the platform entry point. */
    fun start(platformModule: Module = module { }) {
        if (koinApp == null) {
            koinApp = koinApplication {
                modules(
                    platformModule,
                    smartEngineModule,
                    smartEngineBackendModule(),
                    // The SMART chain's text model and inputs. Bound separately from the embedding
                    // backend because a platform can have one without the other. No learned
                    // predictor is bound: the chain plans on its own terms (0.8.1 dropped the
                    // state and scorer nets).
                    smartTextEncoderModule(),
                    smartChainInputsModule(),
                    musicLibraryModule(),
                    playbackModule(),
                    equalizerModule(),
                    appSettingsModule(),
                    appPermissionsModule(),
                    indexingNotifierModule(),
                    loudnessMeterModule(),
                    listeningHistoryModule(),
                    module {
                        // Retrieval-distilled MNv4 audio (pruned, 4-bit) + learned optional
                        // text, fully local.
                        single {
                            SmartEngineConfig(
                                embeddingDim = 960,
                                modelLocator = "ml/mnv4_audio.onnx",
                                // Must match assets/ml/embedding_version.txt;
                                // keys the persisted index snapshot, so a new encoder
                                // re-indexes the library instead of mixing two spaces.
                                // The FMA-only A0 encoder: every audio asset the ranker
                                // uses (tagger, semantic head, audioWeight, the energy constants) was fitted on it.
                                modelVersion = io.github.nikitasud.latentjam.smart.AUDIO_MODEL_VERSION,
                                // EXPERIMENT CONCLUDED, off. Two weeks live at 0.3f
                                // (2026-07-27 → 08-12): SMART skip rate 0.469 pre vs 0.463
                                // post over 783/363 events, Fisher p=0.90, with the
                                // non-SMART control moving the same amount. Typicality
                                // PREDICTS keeps (AUC 0.633 on 717 transitions) but boosting
                                // it does not CAUSE them — the prediction was carried by
                                // familiarity, not by queue fit. The term and snapshot axis
                                // stay for future experiments; re-enable only with a new
                                // hypothesis and its own before/after window.
                                typicalityWeight = 0f,
                                // Queues spend the pick's neighbourhood before moving on, across the
                                // plans the queue is topped up with. Against 0.7.1 on held-out
                                // libraries (450 starts each): more
                                // tracks from the pick's playlists (+1.0 pp top-up, +1.5 at length 10),
                                // smoother transitions and fewer abrupt ones at every queue length.
                                // Thematic libraries (the owner's) lose playlist share; history-mode
                                // evidence exists only for that library.
                                continueAfterExhaustion = true,
                                // Judged scoring: a sound floor and a soft penalty for runs of one artist.
                                // The saved artist-variety level from the first plan on, so a process the UI never
                                // composes (Android Auto, a headset resume) does not plan at the default; App's
                                // ArtistVarietyApplier then follows changes.
                                judgedScoring = true,
                                artistRunPenalty = ARTIST_VARIETY_PENALTIES[sanitizeArtistVariety(get<AppSettings>().artistVariety.value)],
                                queueClassPenalty = SMART_QUEUE_CLASS_PENALTY,
                                artistSets = SMART_ARTIST_SETS,
                                themeWeights = SMART_THEME_WEIGHTS,
                                themeSlots = SMART_THEME_SLOTS,
                                themeBonus = SMART_THEME_BONUS,
                                // c095: no learned correction — the queue theme replaces it.
                                judgedCorrection = SMART_JUDGED_CORRECTION,
                            )
                        }
                        // SMART's on-device impression and outcome log: local only, read by nothing in ranking.
                        single {
                            SmartLogRecorder(log = get(), scope = appScope, enabled = { settings.saveListeningHistory.value })
                        }
                        // The single point where playback meets the engine.
                        single<NextTrackChooser> {
                            EngineNextTrackChooser(
                                engine = get(),
                                history = get(),
                                companionGroups = { smartCompanionGroups.value },
                                // No ranker ships; "Learn from my habits" turns on the chain's habits and priors (PLAN_PERSONAL).
                                learnFromHabits = { !SMART_RANKER_SHIPPED && settings.learnFromHabits.value },
                                useRanker = { SMART_RANKER_SHIPPED && settings.smartRanker.value },
                                ranker = getOrNull<SmartRankerAssets>().let { assets -> { assets?.ranker } },
                                rankerHealthy = getOrNull<SmartRankerAssets>().let { assets -> { assets?.healthy ?: false } },
                                personalRanker = { settings.learnFromHabits.value },
                                // Nested under habits: rankerPlan reads hearts only for personal plans anyway.
                                // Without the ranker the switch is not shown, so a value a test build stored cannot rule.
                                useFavourites = { !SMART_RANKER_SHIPPED || settings.useFavourites.value },
                                presses = getOrNull(),
                                smartLog = getOrNull<SmartLogRecorder>(),
                                favourites = run {
                                    val hearts = getOrNull<Favorites>()
                                    val heartTimes = getOrNull<FavoriteTimes>()
                                    suspend {
                                        val ids = hearts?.all().orEmpty()
                                        val at = heartTimes?.all().orEmpty()
                                        ids.associateWith { at[it] }
                                    }
                                },
                                library = { smartLibraryTracks.value },
                                userPlaylists = { smartUserPlaylists.value },
                                personalPlaylists = { smartPersonalPlaylists.value },
                                varietyScale = { ARTIST_VARIETY_RANKER_SCALES[sanitizeArtistVariety(settings.artistVariety.value)] },
                                personalModel = get<SimilarityEngine>().let { engine ->
                                    val cache = PersonalModelCache(get(), engine::artistKeysOf)
                                    ({ library: List<TrackDescriptor> -> cache.model(library) })
                                },
                            )
                        }
                    },
                )
            }
            if (playbackGainControlsAvailable) {
                // Volume normalization measures lazily as tracks play and pushes the gain map. Its
                // stored loudness is a file read, so it starts off the main thread (this is onCreate).
                val normalizer = LoudnessNormalizer(
                    settings = settings,
                    playback = playback,
                    meter = koin.get(),
                    onStatus = { normalizationStatus.value = it },
                )
                appScope.launch(Dispatchers.Default) { normalizer.start(appScope) }
                // Boundary fades follow the preference where the platform implements them.
                if (boundaryFadeControlsAvailable) appScope.launch {
                    settings.crossfadeSeconds.collect { playback.setCrossfadeSeconds(it) }
                }
                // SMART-adapted crossfade lengths are held back for now: the slider alone decides,
                // whatever an earlier build stored.
            }
            // History observes playback for the app's whole lifetime.
            val smartLog = koin.getOrNull<SmartLogRecorder>()
            appScope.launchPlaybackHistoryRecorder(
                playback = playback,
                history = history,
                enabled = settings.saveListeningHistory,
                flushRequests = historyFlushRequests,
                parentId = { queueSource.value?.parentCollectionId() },
                onRecorded = { mutableHistoryRevision.value += 1L },
                onSnapshot = { now -> smartLog?.onNowPlaying(now) },
                onListen = { event -> smartLog?.onListen(event) },
                annotate = PersonalSignals::annotate,
            )
            // Hearts: their times for FULL's favourite features, and the events for SMART's impression log.
            koin.getOrNull<FavoriteTimes>()?.let { heartTimes ->
                appScope.launchFavoriteRecorder(
                    favorites = favorites,
                    times = heartTimes,
                    log = koin.getOrNull(),
                    logEnabled = { settings.saveListeningHistory.value },
                    trackKey = { ids ->
                        val wanted = ids.toHashSet()
                        smartLibraryTracks.value.filter { it.id in wanted }
                            .associate { it.id to SaltedKey.contentHash(SaltedKey.contentKey(it)) }
                    },
                )
            }
            // SMART's library-wide work (the hub scores) for the list the planner passes, off the first press: after a
            // library change here, after indexing in the indexing job (new vectors).
            appScope.launch {
                smartLibraryTracks.collectLatest { tracks ->
                    if (tracks.isEmpty()) return@collectLatest
                    delay(SMART_WARM_DELAY_MS)
                    warmSmartRanker(tracks)
                }
            }
            // Remembers where listening stood, so the next launch reopens with the same track
            // parked in the player and the same shuffle mode — SMART stays on across restarts.
            // Ignore the empty launch state until playback has a session to restore or create.
            // Later empty states clear the saved queue. While playing, bucket position to 10 s
            // so the ~2 Hz ticker does not become 2 Hz disk writes; pause/seek keeps exact time.
            appScope.launch {
                // Android's ticker reuses immutable queue snapshots between actual queue edits.
                // Preserve their projected ids too: otherwise a 10k-row queue allocates 20k string
                // references every half-second merely because playback position advanced.
                var cachedLiveQueue: List<TrackDescriptor>? = null
                var cachedLiveQueueIds: List<String> = emptyList()
                var cachedSourceQueue: List<TrackDescriptor>? = null
                var cachedSourceQueueIds: List<String> = emptyList()
                combine(playback.state, queueSource) { now, source ->
                    if (now.track == null) {
                        // Release a stopped session's potentially 10k-row descriptor graphs.
                        cachedLiveQueue = null
                        cachedLiveQueueIds = emptyList()
                        cachedSourceQueue = null
                        cachedSourceQueueIds = emptyList()
                    }
                    now.track?.let { track ->
                        val persistableLiveQueue = now.queue
                            .takeIf { it.size in 1..MAX_RESUME_QUEUE }
                        val liveQueueIds = if (persistableLiveQueue == null) {
                            cachedLiveQueue = null
                            cachedLiveQueueIds = emptyList()
                            cachedLiveQueueIds
                        } else if (persistableLiveQueue === cachedLiveQueue) {
                            cachedLiveQueueIds
                        } else {
                            cachedLiveQueue = persistableLiveQueue
                            persistableLiveQueue.mapTo(ArrayList(persistableLiveQueue.size)) {
                                it.id.value
                            }.also { cachedLiveQueueIds = it }
                        }
                        // Empty is a real source after every original playlist row was deleted;
                        // null means an oversized source was deliberately omitted and restore must
                        // use its backward-compatible fallback instead.
                        val persistedSourceQueue = now.sourceQueue
                            .takeIf { it.size <= MAX_RESUME_QUEUE }
                        val sourceQueueIds = if (persistedSourceQueue == null) {
                            cachedSourceQueue = null
                            cachedSourceQueueIds = emptyList()
                            cachedSourceQueueIds
                        } else if (persistedSourceQueue === cachedSourceQueue) {
                            cachedSourceQueueIds
                        } else {
                            cachedSourceQueue = persistedSourceQueue
                            persistedSourceQueue.mapTo(ArrayList(persistedSourceQueue.size)) {
                                it.id.value
                            }.also { cachedSourceQueueIds = it }
                        }
                        ResumePlayback(
                            trackId = track.id.value,
                            shuffleMode = now.shuffleMode.name,
                            positionMs = resumePositionMs(now.positionMs, now.isPlaying),
                            sourceKind = source?.kind?.name,
                            sourceName = source?.name,
                            sourceReference = source?.reference,
                            // The queue itself, so a restart resumes the playlist that was
                            // actually playing. A whole-library queue is exactly what the
                            // fallback restore rebuilds anyway, so oversized ones are skipped.
                            queueTrackIds = liveQueueIds,
                            queueIndex = now.queueIndex.takeIf { liveQueueIds.isNotEmpty() } ?: -1,
                            // SMART's live queue is a generated path, not its durable source. Save
                            // the latter independently so SMART -> OFF after restart returns to the
                            // originating playlist/collection instead of the recommendation tail.
                            sourceQueueTrackIds = sourceQueueIds,
                            sourceQueuePersisted = persistedSourceQueue != null,
                            smartContinuationIds = if (persistableLiveQueue == null) emptySet() else {
                                now.smartContinuationIds.mapTo(HashSet()) { it.value }
                            },
                            smartPlanPositions = if (persistableLiveQueue == null) emptyMap() else {
                                now.smartPlanPositions.mapKeys { (id, _) -> id.value }
                            },
                        )
                    }
                }
                    .resumePlaybackWrites()
                    .collect(settings::setResumePlayback)
            }
        }
    }

    /**
     * Asks the history recorder to finalize the in-progress listening session — called when the
     * app leaves the foreground. Without this the log only gained a session when playback moved
     * OFF a track, so the last track of every sitting was lost to a process kill. The recorder
     * ignores the request while sound is still playing (the foreground service keeps the
     * eventual transition recording intact); backgrounding while paused is what ends a sitting.
     */
    fun flushListeningSession() {
        historyFlushRequests.tryEmit(Unit)
    }

    /** The process-wide similarity engine. */
    val engine: SimilarityEngine
        get() = koin.get()

    /** The local listening record. */
    val history: ListeningHistory
        get() = koin.get()

    /** Where the Map's 2-D layout is cached between visits. */
    val layoutStore: IndexStore
        get() = koin.get(smartLayoutQualifier)

    /** The Map's refresh state, beside [layoutStore]. */
    val mapRefreshStateStore: io.github.nikitasud.latentjam.smart.cluster.MapRefreshStateStore
        get() = koin.get()

    /** The regions' identity between rebuilds (plan piece 4), beside [mapRefreshStateStore]. */
    val regionIdentityStore: io.github.nikitasud.latentjam.smart.cluster.RegionIdentityStore
        get() = koin.get()

    /** Dedicated low-priority serial lane for the Map's potentially minute-long O(n²) layout. */
    val mapLayoutDispatcher: CoroutineDispatcher
        get() = koin.get(smartMapLayoutDispatcherQualifier)

    /** Previously searched queries. */
    val recentSearches: RecentSearches
        get() = koin.get()

    /** Hearted tracks, newest first; device-local like the rest of the listening data. */
    val favorites: Favorites
        get() = koin.get()

    /** Tracks/artists kept in the library but explicitly excluded from SMART suggestions. */
    val smartExclusions: SmartExclusions
        get() = koin.get()

    /** What For You actually offered, per track and local day — cooldowns and honest eval. */
    val forYouImpressions: ForYouImpressions
        get() = koin.get()

    /** CC0 aliases and artist/group relationships, loaded in the background and queried locally. */
    val musicEntities: MusicEntityResolver
        get() = koin.get()

    /** The user's playlists. */
    val playlists: Playlists
        get() = koin.get()

    /** The device music collection. */
    val library: MusicLibrary
        get() = koin.get()

    /** This platform's full-track loudness and sound-edge scan; null where none is bound. */
    internal val loudnessMeter: TrackLoudnessMeter?
        get() = koin.getOrNull()

    /** Shell-level user preferences (theme). */
    /**
     * The test-only "Diagnose recent SMART starts": re-plans the last eight ranker queues with full diagnostics and
     * writes them to the app's debug file; its path, or null when there is nothing to diagnose or it failed.
     */
    internal suspend fun diagnoseSmartStarts(): String? {
        val log = smartImpressionLog ?: return null
        if (!SMART_RANKER_SHIPPED) return null
        val ranker = koin.getOrNull<SmartRankerAssets>()?.ranker ?: return null
        val hearts = koin.getOrNull<Favorites>()?.all().orEmpty()
        val times = koin.getOrNull<FavoriteTimes>()?.all().orEmpty()
        val text = diagnoseRecentSmartStarts(
            log = log,
            ranker = ranker,
            library = smartLibraryTracks.value,
            events = history.recentEvents(10_000),
            favourites = hearts.associateWith { times[it] },
            useFavourites = settings.useFavourites.value,
            fullModelName = ranker.fullModelName,
            engine = koin.getOrNull(),
        )
        if (text.isEmpty()) return null
        return writeDebugFile("smart_diagnostics.jsonl", text)
    }

    /** Clear listening history: SMART's in-memory press list (freshness) is cleared with the log. */
    internal suspend fun forgetSmartPresses() {
        koin.getOrNull<SmartLogRecorder>()?.forgetAll()
        // The per-seed press counts name every SMART seed's file; they go with the history.
        koin.getOrNull<SmartPresses>()?.clear()
    }

    /** SMART's impression log, for the debug export (null when the platform binds none). */
    internal val smartImpressionLog: SmartImpressionLog?
        get() = koin.getOrNull()

    val settings: AppSettings
        get() = koin.get()

    /** Verified tag edits whose songs keep their audio analysis at the next index sync (spec §6.5). */
    internal val audioCarryOvers: AudioCarryOverStore by lazy {
        AudioCarryOverStore(settings::readAudioCarryOversPayload, settings::writeAudioCarryOversPayload)
    }

    /** Operating-system permission state plus durable recovery destinations. */
    val permissions: AppPermissions
        get() = koin.get()

    /** The system equalizer attached to our audio output. */
    val equalizer: EqualizerController
        get() = koin.get()

    /** The playback controller (media-session-backed on Android). */
    val playback: PlaybackController
        get() = koin.get()

    /**
     * One process-lifetime timer shared by every Activity/composition recreation. Keeping it here
     * prevents a rotation, theme change, or temporary UI teardown from silently cancelling an
     * active timer while playback itself continues in the platform media session.
     */
    val sleepTimer: SleepTimerController by lazy {
        SleepTimerController(playback = playback, scope = appScope, nowMillis = ::nowMillis)
    }

    /** Reports long-running library analysis to the platform's notification surface. */
    val indexingNotifier: IndexingNotifier
        get() = koin.get()

    /**
     * Starts (or resumes) the library's automatic local analysis outside the UI lifecycle.
     *
     * A Compose effect is only responsible for handing us the latest library. The actual model
     * work lives in [appScope], while Android's [IndexingNotifier] promotes the process to a
     * foreground service. Consequently changing screen, locking the phone, or swiping the task
     * away does not cancel a half-finished library scan.
     *
     * Work stays in small interactive chunks, while full snapshots are checkpointed at a bounded
     * cadence. A genuine process death can therefore lose at most one checkpoint window; the next
     * launch presents the same key again and already-persisted tracks are skipped.
     */
    fun ensureAutomaticIndexing(
        tracks: List<TrackDescriptor>,
        librarySnapshotAuthoritative: Boolean,
        notificationTitle: String,
        force: Boolean = false,
        call: AnalysisCall = if (force) AnalysisCall.RETRY else AnalysisCall.AUTOMATIC,
        notices: suspend (AnalysisNotice) -> String = { "" },
        notificationText: suspend (done: Int, total: Int, etaMinutes: Int?) -> String,
    ) {
        val key = AutomaticIndexingRequest(
            tracks = tracks,
            librarySnapshotAuthoritative = librarySnapshotAuthoritative,
        )
        lastAnalysisCall = LastAnalysisCall(key, notificationTitle, notices, notificationText)
        val control = analysisControl
        val store = analysisStore
        // A Continue tapped while no library was known yet (a cold start from the notification)
        // is consumed by the first call that has one.
        val kind = if (call == AnalysisCall.AUTOMATIC && store.pendingContinue) AnalysisCall.CONTINUE else call
        if (kind == AnalysisCall.CONTINUE) {
            store.pendingContinue = false
            control.setUserPaused(false)
            if (store.stopReason == AnalysisStopReason.USER) store.stopReason = null
        }
        val trackIds = tracks.idList()
        val existing = automaticIndexingJob
        val sameKey = automaticIndexingKey == key
        val live = existing?.isActive == true
        if (sameKey && kind != AnalysisCall.RETRY && kind != AnalysisCall.REBUILD &&
            (live || (kind == AnalysisCall.AUTOMATIC && automaticIndexing.value.complete))
        ) {
            // A live pass sees Continue through the cleared pause and resumes by itself.
            return
        }
        if (sameKey && kind == AnalysisCall.AUTOMATIC && !restartRules.allowed(store.stopReason)) return
        if (kind == AnalysisCall.RETRY) restartRules.reset()

        // A same-key relaunch keeps what the stopped pass showed, so its steps 1–3 never flash a
        // busy bar or rebuild the map regions while the audio pass stays paused.
        val previous = mutableAutomaticIndexing.value
        // Retry while paused ("only retry") keeps it too: the audio pass stays paused, so nothing else changes.
        val carry = sameKey && kind != AnalysisCall.REBUILD &&
            (kind != AnalysisCall.RETRY || control.userPaused.value) &&
            previous.trackIds == trackIds && !previous.complete
        automaticIndexingKey = key
        // Cancelled here as well as joined below: a replacement that is itself replaced before it
        // starts never runs its body, and would otherwise leave this run going beside the newest.
        existing?.cancel()
        automaticIndexingJob = appScope.launch {
            // Opened before the old run is cancelled: its close then leaves the foreground service to
            // this run instead of stopping it, and a background start is never needed for the handover.
            val notifier = indexingNotifier.open()
            try {
                // Library changes are uncommon, but if one arrives mid-scan the obsolete worker must
                // finish cancellation (including its final checkpoint) before the replacement starts.
                existing?.cancelAndJoin()
            } catch (cancelled: CancellationException) {
                notifier.close()
                throw cancelled
            }

            val failures = LinkedHashMap<TrackId, EngineError>()
            val total = tracks.size
            var reportProgress = false
            val eta = IndexingEta(nowMillis())
            val progress = AnalysisProgress(total)
            val checkpointCadence = AutomaticIndexCheckpointCadence(
                tracksPerCheckpoint = AUTOMATIC_INDEX_CHECKPOINT_TRACKS,
            )
            val pausedAtStart = control.userPaused.value
            mutableAutomaticIndexing.value = if (carry) {
                previous.copy(running = !pausedAtStart, phase = null, etaMinutes = null, progressAt = null)
            } else {
                // Paused: steps 1–3 run quietly, without a busy bar that would flip to "Paused" at the first chunk.
                AutomaticIndexingState(
                    trackIds = trackIds,
                    running = !pausedAtStart,
                    stopReason = store.stopReason,
                )
            }
            // The stopped notice to post once the service is gone (the user's pause, the 6 h cap).
            var stoppedNotice: AnalysisNotice? = null
            runAutomaticIndexingSafely(
                checkpoint = { engine.persistPendingAnalysis() },
                finishNotification = {
                    control.release()
                    notifier.close()
                    val notice = stoppedNotice ?: timeoutNotice.also { timeoutNotice = null }
                    if (notice != null) {
                        indexingNotifier.notice(
                            title = notificationTitle,
                            text = notices(notice),
                            continueLabel = notices(AnalysisNotice.ContinueAction),
                        )
                    }
                },
                onInterrupted = { failure ->
                    val reason = if (failure is AnalysisShowFailed) AnalysisStopReason.SHOW_FAILED else AnalysisStopReason.EXCEPTION
                    store.stopReason = reason
                    if (reason == AnalysisStopReason.EXCEPTION) restartRules.failed()
                    mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(stopReason = reason)
                },
                cancelNotificationPrompt = { permissions.cancelNotificationPrompt() },
                onStopped = {
                    // Cancellation and failure are not completion. A retry must be allowed to
                    // continue from the persisted chunks, even when notification cleanup failed.
                    if (!mutableAutomaticIndexing.value.complete) {
                        mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                            running = false,
                            phase = null,
                            etaMinutes = null,
                            stopReason = store.stopReason,
                            failures = failures.toMap(),
                        )
                    }
                },
            ) {
                engine.initialize().getOrThrow()
                // A denied/not-yet-resolved permission is represented by an empty (or on iOS,
                // partial app-owned-files-only) scan. Index the rows we can see, but do not let
                // that ambiguous snapshot erase durable vectors or remembered decode failures.
                // The authority bit belongs to this exact scan and participates in the dedup key,
                // so a later granted, genuinely empty rescan still reaches reconciliation.
                // A verified tag edit left the audio as it was: re-key its vector before the sync
                // below would discard it for the file's new revision (spec §6.5).
                val carryOvers = audioCarryOvers.pending()
                if (carryOvers.isNotEmpty()) {
                    audioCarryOvers.settle(carryOvers, engine.carryOverAudio(tracks, carryOvers))
                }
                engine.synchronizeLibrary(
                    library = tracks,
                    pruneMissing = librarySnapshotAuthoritative,
                )
                if (force) engine.retryFailedTracks(trackIds)
                // The foreground service and its notification are only warranted when audio
                // embedding will actually run. The dedup key lives in process memory, so every
                // cold start re-enters this job — and used to flash a "0 of N" notification at
                // an already-complete library on every single launch. Promotion still happens
                // BEFORE the first audio batch, which is the work the service protects; the
                // window during model loading is seconds and carries no batch to lose.
                val missing = if (total > 0) engine.missingFromIndex(trackIds) else 0
                // Counted before the metadata phase: a pass that only re-encodes text vectors (a
                // year learned after an upgrade) must not read as analysis lost.
                progress.started(missing)
                mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                    done = progress.analysed,
                    progressKnown = true,
                )
                val pauseLabel = notices(AnalysisNotice.PauseAction)
                // The table gates only an audio pass with work to do, and never one whose index
                // cannot be read (as in 0.9.3, every chunk then fails at once).
                var gated = missing > 0 && !engine.audioIndexUnreadable
                if (missing == 0) {
                    // Nothing to analyse: let a service still up from the replaced run stop now.
                    notifier.close()
                    // A pause has nothing left to hold back, and a finished library is not stopped.
                    if (control.userPaused.value) control.setUserPaused(false)
                    store.stopReason = null
                } else {
                    store.lastPassComplete = false
                    if (!control.userPaused.value) {
                        reportProgress = true
                        // This signal only offers a rationale in the active UI. It never waits for or
                        // requires permission: indexing remains fully functional after "Not now".
                        permissions.backgroundAnalysisNeedsNotifications()
                        showOrFail(
                            notifier,
                            title = notificationTitle,
                            text = notificationText(progress.analysed, total, null),
                            done = progress.analysed,
                            total = total,
                            pauseLabel = pauseLabel,
                        )
                    }
                }
                tracks.chunked(AUTOMATIC_INDEX_CHUNK_SIZE).forEach { chunk ->
                    val added = engine.stageMetadataVectors(chunk)
                    checkpointCadence.afterBatch(chunk.size, engine::persistPendingAnalysis)
                    if (added > 0) delay(AUTOMATIC_INDEX_YIELD_MS)
                }
                // metadataReady promises more than an in-memory process cache: a restart should
                // see every vector encoded by this phase, including a short final window.
                checkpointCadence.flush(engine::persistPendingAnalysis)
                mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                    metadataReady = true,
                )

                val pass = GatedAudioPass(
                    engine = audioPassEngine,
                    control = control,
                    state = mutableAutomaticIndexing,
                    scope = appScope,
                    show = { text, done, label ->
                        showOrFail(notifier, notificationTitle, text, done, total, label)
                    },
                    notices = notices,
                    progressText = notificationText,
                    onFirstShow = { permissions.backgroundAnalysisNeedsNotifications() },
                    nowMs = ::nowMillis,
                )
                // Spread over the library rather than in title order, so the SMART pool is
                // representative long before a big library finishes (see analysisOrder).
                val end = pass.run(
                    order = analysisOrder(tracks),
                    allIds = trackIds,
                    total = total,
                    progress = progress,
                    failures = failures,
                    eta = eta,
                    gatedAtStart = gated,
                    shownAtStart = reportProgress,
                )
                if (end == AudioPassEnd.PAUSED) {
                    store.stopReason = AnalysisStopReason.USER
                    if (pass.shown) stoppedNotice = AnalysisNotice.PausedByYou(progress.analysed, total)
                    val published = pass.publishedFailures(tracks, failures)
                    mutableAutomaticIndexing.update {
                        it.copy(
                            running = false,
                            phase = null,
                            etaMinutes = null,
                            progressAt = null,
                            stopReason = AnalysisStopReason.USER,
                            failures = published,
                        )
                    }
                    return@runAutomaticIndexingSafely
                }
                mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(progressAt = null)
                // PLAN_AUDIO_ON §8.8: e for every indexed track that lacks it — new rows, and a restored library's
                // one-time backfill. Only the small semantic head runs, on the stored vectors.
                try {
                    val added = engine.ensureEnergies()
                    if (added > 0) {
                        val (indexed, withEnergy) = engine.energyCoverage()
                        println("SMART: energy for $added tracks ($withEnergy of $indexed indexed)")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    println("SMART: energy pass failed (${failure::class.simpleName})")
                }
                // Audits 04 #2 / 05 #5: the audio tagger's output for every indexed track, once (no plan-time budget).
                try {
                    val ranker = if (SMART_RANKER_SHIPPED) koin.getOrNull<SmartRankerAssets>()?.ranker else null
                    if (ranker != null) {
                        val computed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            ranker.backfillImputations(smartLibraryTracks.value)
                        }
                        if (computed > 0) println("SMART: tagger output kept for $computed tracks")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    println("SMART: tagger backfill failed (${failure::class.simpleName})")
                }
                warmSmartRanker(smartLibraryTracks.value)
                // A finished pass is not paused: a Pause that arrived during its last chunk is moot.
                if (control.userPaused.value) control.setUserPaused(false)
                store.stopReason = null
                store.lastPassComplete = true
                restartRules.reset()
                // A map rebuild that gave up while analysis waited or was paused (in this run or an
                // earlier one) is asked for again; resumePending checks the stored state for work.
                resumeMapRebuild()
                mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                    running = false,
                    complete = true,
                    done = total,
                    phase = null,
                    stopReason = null,
                    failures = failures.toMap(),
                )
            }
        }
    }

    /** SmartRanker.warm off the main thread; a failure only means the first press computes it. */
    private suspend fun warmSmartRanker(tracks: List<TrackDescriptor>) {
        try {
            if (!SMART_RANKER_SHIPPED) return
            val ranker = koin.getOrNull<SmartRankerAssets>()?.ranker ?: return
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            val scored = withContext(Dispatchers.Default) { ranker.warm(tracks) }
            if (scored > 0) println("SMART: warmed the hub scores for $scored tracks in ${started.elapsedNow().inWholeMilliseconds} ms")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            println("SMART: warm-up failed (${failure::class.simpleName})")
        }
    }

    /** Stops the process-lifetime scan when the platform withdraws its execution budget. */
    fun cancelAutomaticIndexing() {
        automaticIndexingJob?.cancel()
    }

    /**
     * The system's 6 h foreground cap ended the service: the pass stops, says where it stopped, and
     * restarts at the next app open.
     */
    fun onAnalysisForegroundTimeout() {
        val job = automaticIndexingJob
        if (job?.isActive == true) {
            analysisStore.stopReason = AnalysisStopReason.TIMEOUT
            val state = automaticIndexing.value
            timeoutNotice = AnalysisNotice.PausedAt(state.done, state.trackIds.size)
        }
        appScope.launch { analysisControl.release() }
        job?.cancel()
    }

    /** The user's Pause: the pass stops at its next chunk boundary and stays paused across launches. */
    fun pauseAutomaticIndexing() {
        val live = automaticIndexingJob?.isActive == true
        // A stale Pause after a finished pass holds nothing back.
        if (!live && analysisStore.lastPassComplete) return
        analysisControl.setUserPaused(true)
    }

    /**
     * The user's Continue. Written before anything else, so a cold start or an open permission gate
     * never loses the tap; the first call that knows the library consumes it.
     */
    fun continueAutomaticIndexing() {
        analysisStore.pendingContinue = true
        analysisControl.setUserPaused(false)
        indexingNotifier.clearNotice()
        val last = lastAnalysisCall ?: return
        val job = automaticIndexingJob
        val state = automaticIndexing.value
        if (job?.isActive == true && !state.running && state.stopReason == AnalysisStopReason.USER) {
            // The pass has published its stop and is finishing: relaunch once it has.
            appScope.launch {
                job.join()
                continueAutomaticIndexing()
            }
            return
        }
        ensureAutomaticIndexing(
            tracks = last.key.tracks,
            librarySnapshotAuthoritative = last.key.librarySnapshotAuthoritative,
            notificationTitle = last.title,
            call = AnalysisCall.CONTINUE,
            notices = last.notices,
            notificationText = last.progressText,
        )
    }

    /** The app came to the foreground: restarts a pass the platform or a failure stopped. */
    fun onAppForeground() {
        val last = lastAnalysisCall ?: return
        ensureAutomaticIndexing(
            tracks = last.key.tracks,
            librarySnapshotAuthoritative = last.key.librarySnapshotAuthoritative,
            notificationTitle = last.title,
            call = AnalysisCall.AUTOMATIC,
            notices = last.notices,
            notificationText = last.progressText,
        )
    }

    /**
     * Rebuild: the running pass is cancelled and joined before [clear] deletes the analysis, so no
     * old job installs rows after it; analysis then restarts for the whole library, unpaused.
     */
    suspend fun rebuildAnalysis(clear: suspend () -> Unit) {
        automaticIndexingJob?.cancelAndJoin()
        try {
            clear()
        } finally {
            analysisControl.setUserPaused(false)
            analysisStore.stopReason = null
            mutableAutomaticIndexing.value = AutomaticIndexingState()
            val last = lastAnalysisCall
            if (last != null) {
                ensureAutomaticIndexing(
                    tracks = last.key.tracks,
                    librarySnapshotAuthoritative = last.key.librarySnapshotAuthoritative,
                    notificationTitle = last.title,
                    call = AnalysisCall.REBUILD,
                    notices = last.notices,
                    notificationText = last.progressText,
                )
            }
        }
    }

    /** Whether the audio pass waits or was stopped short of completing (not merely running or done). */
    fun analysisHeldBack(): Boolean {
        val state = automaticIndexing.value
        // The stored flags, so a cold WorkManager process answers too without building the player.
        return analysisStore.userPaused || analysisStore.stopReason != null || state.phase != null
    }

    /** A map rebuild that gave up while analysis was held back is asked for again, once per resume. */
    private fun resumeMapRebuild() {
        appScope.launch {
            runCatching { MapRebuild.resumePending(nowMillis()) }
                .onFailure { println("Map rebuild: could not resume: $it") }
        }
    }

    /** Clears the user's pause without a relaunch (the relaunch that follows reads it). */
    fun clearAnalysisPause() {
        analysisControl.setUserPaused(false)
        if (analysisStore.stopReason == AnalysisStopReason.USER) analysisStore.stopReason = null
        mutableAutomaticIndexing.update { if (it.stopReason == AnalysisStopReason.USER) it.copy(stopReason = null) else it }
    }

    /** The system refused the foreground start: the pass ends now and restarts at the next app open. */
    fun onAnalysisForegroundRefused() {
        val job = automaticIndexingJob
        if (job?.isActive == true) {
            analysisStore.stopReason = AnalysisStopReason.SHOW_FAILED
            mutableAutomaticIndexing.update { it.copy(stopReason = AnalysisStopReason.SHOW_FAILED) }
        }
        job?.cancel()
    }

    /** Whether the user paused analysis; persisted. */
    val analysisUserPaused: StateFlow<Boolean> get() = analysisControl.userPaused


    private suspend fun showOrFail(
        session: IndexingSession,
        title: String,
        text: String,
        done: Int,
        total: Int,
        pauseLabel: String?,
    ) {
        try {
            session.show(title, text, done, total, pauseLabel)
        } catch (refused: RuntimeException) {
            throw AnalysisShowFailed(refused)
        }
    }

    private val analysisStore: AnalysisControlStore by lazy { analysisControlStore() }
    private val analysisDevice: AnalysisDevice by lazy { analysisDevice() }
    private val analysisPlaying: StateFlow<Boolean> by lazy {
        playback.state.map { it.isPlaying }.stateIn(appScope, SharingStarted.Eagerly, playback.state.value.isPlaying)
    }
    private val analysisControl: AnalysisRunControl by lazy {
        AnalysisRunControl(
            device = analysisDevice,
            store = analysisStore,
            playing = analysisPlaying,
            wallClockMs = ::nowMillis,
            monotonicMs = ::analysisElapsedMs,
        )
    }
    private val audioPassEngine: AudioPassEngine by lazy {
        object : AudioPassEngine {
            override suspend fun missingFromIndex(ids: List<TrackId>) = engine.missingFromIndex(ids)
            override suspend fun stage(chunk: List<TrackDescriptor>) = engine.stageLibraryIndex(chunk)
            override suspend fun persist() = engine.persistPendingAnalysis()
            override fun envelopeRefused() = engine.audioEnvelopeRefused()
            override suspend fun rememberedFailures(tracks: List<TrackDescriptor>) = engine.rememberedAudioFailures(tracks)
            override val trackChanges: kotlinx.coroutines.flow.Flow<Any?> get() = engine.analysing
            override val signals: kotlinx.coroutines.flow.Flow<Any?>
                get() = combine(analysisDevice.conditions, analysisPlaying) { conditions, playing -> conditions to playing }
        }
    }
    private var lastAnalysisCall: LastAnalysisCall? = null
    private var timeoutNotice: AnalysisNotice? = null
    private val restartRules = AnalysisRestartRules()

    private val koin: Koin
        get() = checkNotNull(koinApp) {
            "AppGraph.start() must be called by the platform entry point before use"
        }.koin
}


/**
 * Whether this build ships the learned ranker and its assets. The FB build (0.9.2's chain + habit slots with LW2, no
 * ranker tables) leaves them out of the app: SMART plans with the chain, and the ranker settings are hidden.
 */
internal const val SMART_RANKER_SHIPPED: Boolean = false

/** Spreads a SMART candidate loses for a kind of track that jars after its seed (QueueClasses); 0 is off. */
internal const val SMART_QUEUE_CLASS_PENALTY: Float = 1f

/** SMART counts every credited artist (the artist list: "Queen feat. David Bowie" is Queen and Bowie) for its cap and runs. */
internal const val SMART_ARTIST_SETS: Boolean = true

/**
 * The learned queue theme's weights (ThemePool; audio z, descriptor z, family Jaccard, family any, same language, same era,
 * shared artist, energy gap): P(a track belongs in the queue | its start), fitted to DeepSeek's queues for the owner's
 * library (track list and sound hints, by the owner's leave) and the 16 FMA stand libraries on the device's own features
 * (a shared artist counts only when it also sounds alike; held out: top-15 overlap with the
 * teacher 4.73 owner / 4.25 FMA vs audio alone 2.61 / 2.70).
 * Null is off.
 */
internal val SMART_THEME_WEIGHTS: List<Float>? = listOf(0.7455f, 0.6732f, 0.6191f, 0.3468f, 0.9851f, 0.1879f, 0.4924f, -2.7054f)

/** Pool rows the queue theme may take. */
internal const val SMART_THEME_SLOTS: Int = 25

/** Spreads of a hop's scores per unit of a candidate's queue-theme z-score; 0 keeps the theme a pool channel only. */
internal const val SMART_THEME_BONUS: Float = 1.5f

/** Whether the chain adds Rerank.JUDGED_WEIGHTS' learned correction to every candidate (c093's judge refit). */
internal const val SMART_JUDGED_CORRECTION: Boolean = false

/**
 * Optional background analysis must not crash playback when storage or foreground promotion
 * fails. SupervisorJob isolates siblings, but an uncaught launch exception still reaches the
 * platform's uncaught-exception handler. Keep cancellation observable and independently release
 * every resource so one cleanup failure cannot leave the UI permanently reporting a running job.
 */
internal suspend fun runAutomaticIndexingSafely(
    checkpoint: suspend () -> Unit,
    finishNotification: suspend () -> Unit,
    cancelNotificationPrompt: () -> Unit,
    onStopped: () -> Unit,
    onInterrupted: (Exception) -> Unit = {},
    work: suspend () -> Unit,
) {
    try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        println("SMART: automatic indexing interrupted: $failure")
        try {
            onInterrupted(failure)
        } catch (secondary: Exception) {
            println("SMART: could not record the interruption: $secondary")
        }
    } finally {
        // A batch can be cancelled after installing vectors but before reaching its checkpoint.
        // Persist that dirty window before allowing a replacement worker to acquire its resources.
        withContext(NonCancellable) {
            try {
                checkpoint()
            } catch (failure: Exception) {
                // The engine retains dirty flags; a retry still attempts the outstanding save.
                println("SMART: final indexing checkpoint failed: $failure")
            }
        }
        try {
            withContext(NonCancellable) { finishNotification() }
        } catch (failure: Exception) {
            println("SMART: could not dismiss indexing notification: $failure")
        }
        try {
            cancelNotificationPrompt()
        } catch (failure: Exception) {
            println("SMART: could not dismiss indexing permission prompt: $failure")
        } finally {
            onStopped()
        }
    }
}

/** Which kind of call relaunches analysis; each honours the stop reasons its own way (plan 1e-min). */
enum class AnalysisCall {
    /** A library change, a cold start or an app open. */
    AUTOMATIC,

    /** The user's Continue: clears the pause. */
    CONTINUE,

    /** The recovery Retry: clears remembered failures and restarts a stopped pass. */
    RETRY,

    /** After a Rebuild cleared the analysis. */
    REBUILD,
}

/** What a notification needs beyond the progress line, resolved in the caller's locale. */
sealed interface AnalysisNotice {
    data class Waiting(val phase: AnalysisPhase) : AnalysisNotice
    data class PausedByYou(val done: Int, val total: Int) : AnalysisNotice
    data class PausedAt(val done: Int, val total: Int) : AnalysisNotice
    data object PauseAction : AnalysisNotice
    data object ContinueAction : AnalysisNotice
}

/** The last library and texts handed to analysis, for relaunches the UI does not drive. */
internal class LastAnalysisCall(
    val key: AutomaticIndexingRequest,
    val title: String,
    val notices: suspend (AnalysisNotice) -> String,
    val progressText: suspend (done: Int, total: Int, etaMinutes: Int?) -> String,
)

/** The platform refused to show the analysis in the foreground (a background start). */
internal class AnalysisShowFailed(cause: Throwable) : RuntimeException(cause)

/** A completed UI library scan plus whether it proves which durable SMART rows are still live. */
internal data class AutomaticIndexingRequest(
    val tracks: List<TrackDescriptor>,
    val librarySnapshotAuthoritative: Boolean,
)

data class AutomaticIndexingState(
    val trackIds: List<TrackId> = emptyList(),
    val running: Boolean = false,
    val metadataReady: Boolean = false,
    val complete: Boolean = false,
    /** Tracks of the library analysed so far, those analysed before this pass included ([AnalysisProgress]). */
    val done: Int = 0,
    /** Whether [done] has been counted yet; until then this pass has nothing true to show. */
    val progressKnown: Boolean = false,
    /** Minutes the pass expects to need, once it has seen enough to say ([IndexingEta]). */
    val etaMinutes: Int? = null,
    val failures: Map<TrackId, EngineError> = emptyMap(),
    /** Why the pass waits or stops before its next chunk; null while it runs. */
    val phase: AnalysisPhase? = null,
    /** Why the last pass stopped before completing, persisted across launches. */
    val stopReason: AnalysisStopReason? = null,
    /** Whether the running audio pass is under the state table, so Pause would act. */
    val pausable: Boolean = false,
    /** The audio index is too large to keep between launches (the envelope refused it). */
    val tooLarge: Boolean = false,
    /** When the audio pass last moved (started, or finished a chunk); null outside it ([analysisStall]). */
    val progressAt: TimeSource.Monotonic.ValueTimeMark? = null,
)

/**
 * "N of M tracks analysed" for one analysis pass: the library's tracks that need no more audio
 * work, whether analysed before this pass or by it. A pass starts from those it finds already
 * analysed, and each chunk it visits adds only the tracks that still needed work. A new pass (an
 * upgrade's re-enrichment re-encodes text vectors and visits the library in a spread order) never
 * restarts the count near zero while every fingerprint is kept; it never drops, and ends at [total].
 */
internal class AnalysisProgress(private val total: Int) {
    var analysed: Int = 0
        private set

    /** The pass found [missing] tracks still needing audio analysis. */
    fun started(missing: Int) {
        analysed = (total - missing).coerceIn(0, total)
    }

    /** A chunk was visited; [missingBefore] of its tracks still needed analysis. */
    fun visited(missingBefore: Int) {
        analysed = (analysed + missingBefore.coerceAtLeast(0)).coerceAtMost(total)
    }
}

// The engine serialises a batch under its model/index lock. Eight bounds how long an interactive
// SMART/search request can wait; the separate cadence coalesces four such batches per snapshot.
internal const val AUTOMATIC_INDEX_CHUNK_SIZE = 8

/** Launch and library changes settle before SMART's warm-up takes a core for a few seconds. */
private const val SMART_WARM_DELAY_MS = 5_000L
internal const val AUTOMATIC_INDEX_CHECKPOINT_TRACKS = 32
internal const val AUTOMATIC_INDEX_YIELD_MS = 75L

/** Coalesces small model-lock batches without allowing an unbounded crash-loss window. */
internal class AutomaticIndexCheckpointCadence(
    private val tracksPerCheckpoint: Int,
) {
    private var tracksSinceCheckpoint: Int = 0

    init {
        require(tracksPerCheckpoint > 0)
    }

    suspend fun afterBatch(
        trackCount: Int,
        checkpoint: suspend () -> Unit,
    ) {
        require(trackCount in 0..tracksPerCheckpoint)
        tracksSinceCheckpoint += trackCount
        if (tracksSinceCheckpoint >= tracksPerCheckpoint) {
            checkpoint()
            tracksSinceCheckpoint = 0
        }
    }

    /** Always attempts a checkpoint so partially completed/cancelled batches are included too. */
    suspend fun flush(checkpoint: suspend () -> Unit) {
        checkpoint()
        tracksSinceCheckpoint = 0
    }
}

/** Queues beyond this are effectively "the whole library" and are cheaper to rebuild than store. */
// Keep this aligned with the collision-safe decoder cap. A 10k-id source is still a modest
// preferences value (opaque ids dominate size), and persisting it is more truthful than inferring
// the order of a shuffled/sorted/multi-selected Tracks or Search queue after restart.
private const val MAX_RESUME_QUEUE = 10_000
