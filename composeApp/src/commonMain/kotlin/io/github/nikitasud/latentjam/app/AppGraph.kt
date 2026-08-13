/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.RecentSearches
import io.github.nikitasud.latentjam.history.SmartExclusions
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
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.di.smartEngineModule
import io.github.nikitasud.latentjam.smart.di.smartLayoutQualifier
import io.github.nikitasud.latentjam.smart.chain.smartPredictorModule
import io.github.nikitasud.latentjam.smart.smartChainInputsModule
import io.github.nikitasud.latentjam.smart.smartEngineBackendModule
import io.github.nikitasud.latentjam.smart.text.smartTextEncoderModule
import io.github.nikitasud.latentjam.smart.text.MusicEntityResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    /** Advances after each accepted listening event so For You can apply feedback next time it opens. */
    val historyRevision: StateFlow<Long> = mutableHistoryRevision.asStateFlow()
    private val historyFlushRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var automaticIndexingKey: List<TrackDescriptor>? = null
    private var automaticIndexingJob: Job? = null

    /** Initializes the graph. Call once from the platform entry point. */
    fun start(platformModule: Module = module { }) {
        if (koinApp == null) {
            koinApp = koinApplication {
                modules(
                    platformModule,
                    smartEngineModule,
                    smartEngineBackendModule(),
                    // The SMART chain's models and inputs. Bound separately from the embedding
                    // backend because a platform can have one without the other.
                    smartPredictorModule(),
                    smartTextEncoderModule(),
                    smartChainInputsModule(),
                    musicLibraryModule(),
                    playbackModule(),
                    equalizerModule(),
                    appSettingsModule(),
                    appPermissionsModule(),
                    indexingNotifierModule(),
                    listeningHistoryModule(),
                    module {
                        // Experimental retrieval-distilled FP16 MNv4 audio + 960-d SMART
                        // state/acoustic scorer plus learned optional text, fully local.
                        single {
                            SmartEngineConfig(
                                embeddingDim = 960,
                                modelLocator = "ml/mnv4_audio.onnx",
                                // Must match assets/ml/embedding_version.txt;
                                // keys the persisted index snapshot.
                                modelVersion = "mnv4-960-retrieval-distill-v1",
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
                            )
                        }
                        // The single point where playback meets the engine.
                        single<NextTrackChooser> {
                            EngineNextTrackChooser(engine = get(), history = get())
                        }
                    },
                )
            }
            // History observes playback for the app's whole lifetime.
            appScope.launchPlaybackHistoryRecorder(
                playback = playback,
                history = history,
                enabled = settings.saveListeningHistory,
                flushRequests = historyFlushRequests,
                onRecorded = { mutableHistoryRevision.value += 1L },
            )
            // Remembers where listening stood, so the next launch reopens with the same track
            // parked in the player and the same shuffle mode — SMART stays on across restarts.
            // Never cleared on a null track: launch itself starts with no track, and wiping the
            // saved session at that moment would defeat the restore it exists for. Position is
            // bucketed to 10 s so the ~2 Hz playback ticker does not become 2 Hz disk writes.
            appScope.launch {
                combine(playback.state, queueSource) { now, source ->
                    now.track?.let { track ->
                        ResumePlayback(
                            trackId = track.id.value,
                            shuffleMode = now.shuffleMode.name,
                            positionMs = now.positionMs - (now.positionMs % 10_000),
                            sourceKind = source?.kind?.name,
                            sourceName = source?.name,
                        )
                    }
                }
                    .filterNotNull()
                    .distinctUntilChanged()
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

    /** Previously searched queries. */
    val recentSearches: RecentSearches
        get() = koin.get()

    /** Tracks/artists kept in the library but explicitly excluded from SMART suggestions. */
    val smartExclusions: SmartExclusions
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

    /** Shell-level user preferences (theme). */
    val settings: AppSettings
        get() = koin.get()

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
     * Chunks are persisted by [SimilarityEngine], so a genuine process death is also harmless:
     * the next launch presents the same key again and already-indexed tracks are skipped.
     */
    fun ensureAutomaticIndexing(
        tracks: List<TrackDescriptor>,
        notificationTitle: String,
        force: Boolean = false,
        notificationText: suspend (done: Int, total: Int, etaMinutes: Int?) -> String,
    ) {
        val key = tracks
        val trackIds = tracks.map(TrackDescriptor::id)
        val existing = automaticIndexingJob
        if (!force &&
            automaticIndexingKey == key &&
            (existing?.isActive == true || automaticIndexing.value.complete)
        ) {
            return
        }

        automaticIndexingKey = key
        automaticIndexingJob = appScope.launch {
            // Library changes are uncommon, but if one arrives mid-scan the obsolete worker must
            // finish cancellation (including removing its notification) before the replacement
            // announces itself. Otherwise the old finally block can erase the new progress bar.
            existing?.cancelAndJoin()

            val notifier = indexingNotifier
            val failures = LinkedHashMap<TrackId, EngineError>()
            val total = tracks.size
            var reportProgress = false
            val eta = IndexingEta(nowMillis())
            mutableAutomaticIndexing.value = AutomaticIndexingState(
                trackIds = trackIds,
                running = true,
            )
            try {
                engine.initialize()
                engine.synchronizeLibrary(tracks)
                // The foreground service and its notification are only warranted when audio
                // embedding will actually run. The dedup key lives in process memory, so every
                // cold start re-enters this job — and used to flash a "0 of N" notification at
                // an already-complete library on every single launch. Promotion still happens
                // BEFORE the first audio batch, which is the work the service protects; the
                // window during model loading is seconds and carries no batch to lose.
                if (total > 0 && engine.missingFromIndex(trackIds) > 0) {
                    reportProgress = true
                    // This signal only offers a rationale in the active UI. It never waits for or
                    // requires permission: indexing remains fully functional after "Not now".
                    permissions.backgroundAnalysisNeedsNotifications()
                    notifier.show(
                        title = notificationTitle,
                        text = notificationText(0, total, null),
                        done = 0,
                        total = total,
                    )
                }
                tracks.chunked(AUTOMATIC_INDEX_CHUNK_SIZE).forEach { chunk ->
                    val added = engine.ensureMetadataVectors(chunk)
                    if (added > 0) delay(AUTOMATIC_INDEX_YIELD_MS)
                }
                mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                    metadataReady = true,
                )

                var done = 0
                tracks.chunked(AUTOMATIC_INDEX_CHUNK_SIZE).forEach { chunk ->
                    val report = engine.indexLibrary(chunk)
                    failures.putAll(report.errors)
                    done += chunk.size
                    val remaining = eta.remainingMs(done, total, nowMillis())
                    if (reportProgress) {
                        notifier.show(
                            title = notificationTitle,
                            text = notificationText(
                                done,
                                total,
                                remaining?.let(IndexingEta::minutesFrom),
                            ),
                            done = done,
                            total = total,
                        )
                    }
                    mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                        done = done,
                        failures = failures.toMap(),
                    )
                    // A short scheduling gap also gives thermal management a chance to settle;
                    // 75 ms per checkpoint is invisible beside audio inference but materially
                    // improves gesture latency on mid-range phones.
                    if (report.indexed > 0 || report.failed > 0) {
                        delay(AUTOMATIC_INDEX_YIELD_MS)
                    }
                }
                mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                    running = false,
                    complete = true,
                    done = total,
                    failures = failures.toMap(),
                )
            } finally {
                if (reportProgress) notifier.finish()
                permissions.cancelNotificationPrompt()
                // Cancellation is not completion. The replacement job (or the next process
                // launch) must be allowed to continue from the engine's persisted chunks.
                if (!mutableAutomaticIndexing.value.complete) {
                    mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                        running = false,
                        failures = failures.toMap(),
                    )
                }
            }
        }
    }

    /** Stops the process-lifetime scan when the platform withdraws its execution budget. */
    fun cancelAutomaticIndexing() {
        automaticIndexingJob?.cancel()
    }

    private val koin: Koin
        get() = checkNotNull(koinApp) {
            "AppGraph.start() must be called by the platform entry point before use"
        }.koin
}

data class AutomaticIndexingState(
    val trackIds: List<TrackId> = emptyList(),
    val running: Boolean = false,
    val metadataReady: Boolean = false,
    val complete: Boolean = false,
    val done: Int = 0,
    val failures: Map<TrackId, EngineError> = emptyMap(),
)

// The engine serialises a batch under its model/index lock. Eight bounds how long an interactive
// SMART/search request can wait behind first-run indexing while retaining frequent checkpoints.
private const val AUTOMATIC_INDEX_CHUNK_SIZE = 8
private const val AUTOMATIC_INDEX_YIELD_MS = 75L
