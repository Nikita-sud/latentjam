/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.RecentSearches
import io.github.nikitasud.latentjam.history.listeningHistoryModule
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.library.musicLibraryModule
import io.github.nikitasud.latentjam.library.nowMillis
import io.github.nikitasud.latentjam.playback.NextTrackChooser
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.EqualizerController
import io.github.nikitasud.latentjam.playback.equalizerModule
import io.github.nikitasud.latentjam.playback.playbackModule
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.SmartEngineConfig
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.di.smartEngineModule
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
    private var automaticIndexingKey: List<TrackId>? = null
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
                    indexingNotifierModule(),
                    listeningHistoryModule(),
                    module {
                        // Production contract: FP16 MNv4 audio + 960-d SMART state/acoustic scorer
                        // plus learned optional text, fully local.
                        single {
                            SmartEngineConfig(
                                embeddingDim = 960,
                                modelLocator = "ml/mnv4_audio.onnx",
                                // Must match assets/ml/embedding_version.txt;
                                // keys the persisted index snapshot.
                                modelVersion = "mnv4-960-allfp16-v5",
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
            appScope.launchPlaybackHistoryRecorder(playback, history)
            appScope.launch { musicEntities.preload() }
        }
    }

    /** The process-wide similarity engine. */
    val engine: SimilarityEngine
        get() = koin.get()

    /** The local listening record. */
    val history: ListeningHistory
        get() = koin.get()

    /** Previously searched queries. */
    val recentSearches: RecentSearches
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

    /** The system equalizer attached to our audio output. */
    val equalizer: EqualizerController
        get() = koin.get()

    /** The playback controller (media-session-backed on Android). */
    val playback: PlaybackController
        get() = koin.get()

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
        notificationText: suspend (done: Int, total: Int, etaMinutes: Int?) -> String,
    ) {
        val key = tracks.map(TrackDescriptor::id)
        val existing = automaticIndexingJob
        if (automaticIndexingKey == key && (existing?.isActive == true || automaticIndexing.value.complete)) {
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
            val eta = IndexingEta(nowMillis())
            mutableAutomaticIndexing.value = AutomaticIndexingState(
                trackIds = key,
                running = true,
            )
            try {
                // Promote before model loading or the first audio batch. Starting after a slow
                // first batch leaves Android free to suspend the process during exactly the work
                // the foreground service is meant to protect.
                notifier.show(
                    title = notificationTitle,
                    text = notificationText(0, total, null),
                    done = 0,
                    total = total,
                )

                engine.initialize()
                engine.synchronizeLibrary(tracks)
                tracks.chunked(AUTOMATIC_INDEX_CHUNK_SIZE).forEach { chunk ->
                    engine.ensureMetadataVectors(chunk)
                }
                mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                    metadataReady = true,
                )

                var done = 0
                tracks.chunked(AUTOMATIC_INDEX_CHUNK_SIZE).forEach { chunk ->
                    failures.putAll(engine.indexLibrary(chunk).errors)
                    done += chunk.size
                    val remaining = eta.remainingMs(done, total, nowMillis())
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
                    mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                        done = done,
                        failures = failures.toMap(),
                    )
                }
                mutableAutomaticIndexing.value = mutableAutomaticIndexing.value.copy(
                    running = false,
                    complete = true,
                    done = total,
                    failures = failures.toMap(),
                )
            } finally {
                notifier.finish()
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

private const val AUTOMATIC_INDEX_CHUNK_SIZE = 8
