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
import io.github.nikitasud.latentjam.playback.NextTrackChooser
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.EqualizerController
import io.github.nikitasud.latentjam.playback.equalizerModule
import io.github.nikitasud.latentjam.playback.playbackModule
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.SmartEngineConfig
import io.github.nikitasud.latentjam.smart.di.smartEngineModule
import io.github.nikitasud.latentjam.smart.chain.smartPredictorModule
import io.github.nikitasud.latentjam.smart.smartChainInputsModule
import io.github.nikitasud.latentjam.smart.smartEngineBackendModule
import io.github.nikitasud.latentjam.smart.text.smartTextEncoderModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
                    listeningHistoryModule(),
                    module {
                        // Production model contract (mnv4-conv-m-distill-mw):
                        // 960-dim embeddings, asset-shipped ONNX. Last-wins
                        // override of the library default.
                        single {
                            SmartEngineConfig(
                                embeddingDim = 960,
                                modelLocator = "ml/mnv4_audio.onnx",
                                // Must match assets/ml/embedding_version.txt;
                                // keys the persisted index snapshot.
                                modelVersion = "mnv4-conv-m-distill-mw-ep4+v3",
                            )
                        }
                        // The single point where playback meets the engine.
                        single<NextTrackChooser> { EngineNextTrackChooser(engine = get()) }
                    },
                )
            }
            // History observes playback for the app's whole lifetime.
            appScope.launchPlaybackHistoryRecorder(playback, history)
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

    private val koin: Koin
        get() = checkNotNull(koinApp) {
            "AppGraph.start() must be called by the platform entry point before use"
        }.koin
}
