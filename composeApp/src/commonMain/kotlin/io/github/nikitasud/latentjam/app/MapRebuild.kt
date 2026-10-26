/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayoutRefresh
import io.github.nikitasud.latentjam.smart.cluster.MapMemoryBudget
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Asks the platform to run [MapRebuild] later: on external power and, on Android, while idle. */
internal expect fun scheduleMapRebuild()

/** Whether the device is on external power now, for the in-app fallback. */
internal expect fun onExternalPower(): Boolean

/**
 * The deferred full rebuild of the Map (docs/map-cost-research-2026-10-25.md, implementation plan,
 * piece 3): regions rediscovered with identity, then a full layout build that rewrites the refresh
 * state, all from persisted inputs and without UI. Runs from the platform's background scheduler, or
 * in-app on a Map visit while charging when no scheduled run has happened for [FALLBACK_AFTER_MS].
 */
internal object MapRebuild {
    enum class Outcome { DONE, NOT_NEEDED, NOT_READY, CANCELLED, FAILED }

    const val FALLBACK_AFTER_MS: Long = 24L * 60 * 60 * 1000

    private val revisionState = MutableStateFlow(0L)

    /** Bumped after each completed rebuild; the Map's caches compare it and reload from disk. */
    val revision: StateFlow<Long> = revisionState

    private val staleState = MutableStateFlow(false)

    /** The Map shows a refreshed layout that should be rebuilt (the debug marker reads it). */
    val stale: StateFlow<Boolean> = staleState

    /** When a rebuild was first asked for and has not run since, in epoch ms; 0 when none is waiting. */
    private var requestedAtMs: Long = 0L

    /** Fallback runs this process has made (logged with each). */
    var fallbackRuns: Int = 0
        private set

    /** Called after a refresh that leaves work for the rebuild. */
    fun requested(stale: Boolean, nowMs: Long) {
        if (stale) staleState.value = true
        if (requestedAtMs == 0L) requestedAtMs = nowMs
        scheduleMapRebuild()
    }

    /** Called after a full build in the page itself: nothing is waiting any more. */
    fun builtInPage() {
        staleState.value = false
        requestedAtMs = 0L
    }

    /** The in-app fallback's condition: a request older than [FALLBACK_AFTER_MS], on external power. */
    fun fallbackDue(nowMs: Long): Boolean =
        requestedAtMs != 0L && nowMs - requestedAtMs >= FALLBACK_AFTER_MS && onExternalPower()

    suspend fun runFallback(isActive: () -> Boolean): Outcome {
        fallbackRuns++
        return run(isActive, trigger = "fallback#$fallbackRuns")
    }

    /**
     * Rebuilds when the stored state says a refresh or a join left work. Never while an analysis runs
     * or before every scanned track has its audio vector; those reschedule (NOT_READY).
     */
    suspend fun run(isActive: () -> Boolean, trigger: String): Outcome {
        val started = TimeSource.Monotonic.markNow()
        val outcome = try {
            rebuild(isActive)
        } catch (cancelled: CancellationException) {
            Outcome.CANCELLED
        } catch (failure: Throwable) {
            println("Map rebuild ($trigger) failed: $failure")
            Outcome.FAILED
        }
        println("Map rebuild ($trigger): $outcome in ${started.elapsedNow().inWholeMilliseconds} ms")
        if (outcome == Outcome.DONE || outcome == Outcome.NOT_NEEDED) {
            requestedAtMs = 0L
            staleState.value = false
        }
        return outcome
    }

    private suspend fun rebuild(isActive: () -> Boolean): Outcome {
        if (!MapRefreshFlag.enabled) return Outcome.NOT_NEEDED
        if (AppGraph.automaticIndexing.value.running) return Outcome.NOT_READY
        val engine = AppGraph.engine
        engine.initialize().getOrElse { return Outcome.NOT_READY }

        val layoutState = runCatching { AppGraph.mapRefreshStateStore.load() }.getOrNull()
        val layoutPending = layoutState?.let { it.changedSinceFull > 0 || it.stale } == true
        layoutState?.close()
        if (!layoutPending && RegionIdentityKeeper.pendingCount() == 0) return Outcome.NOT_NEEDED

        // The Map's own inputs, read as App reads them: the scanned library with cached genre facts.
        val scan = AppGraph.library.scan()
        val loaded = GenreEnrichment(AppGraph.settings).apply(scan.tracks)
        val ids = loaded.map(TrackDescriptor::id)
        if (!isActive()) throw CancellationException("Map rebuild cancelled")
        // "Analysis complete" from the index itself: a rebuild never runs on partial audio coverage.
        if (ids.isEmpty() || engine.missingFromIndex(ids) > 0) return Outcome.NOT_READY
        val coverage = engine.libraryMixCoverage(ids) ?: return Outcome.NOT_READY
        if (coverage.size > LibraryLayout.MAX_TRACKS) return Outcome.NOT_NEEDED

        val playlists = AppGraph.playlists.all()
        LibraryRegionsService.refresh(
            engine = engine,
            loaded = loaded,
            playlistGroups = { playlists.map { p -> p.name to p.trackIds.map(::TrackId) } },
            playlistPresentationIdentity = playlists.presentationIdentity(),
            includeNoveltyMixes = AppGraph.settings.includeNoveltyMixes.value,
            metadataReady = true,
            audioReady = true,
            rebuild = true,
        )
        if (!isActive()) throw CancellationException("Map rebuild cancelled")

        val layoutStore = AppGraph.layoutStore
        val built = withContext(AppGraph.mapLayoutDispatcher) {
            mapWorkGate.withLock {
                val streamedBytes = MapMemoryBudget.projectionStreamedBytes(coverage.size, 1344, LibraryLayout.experimentalBackend)
                val workBudget = mapWorkBytes(streamedBytes)
                if (workBudget < streamedBytes) return@withLock null
                val rows = engine.libraryMixRows(ids) ?: return@withLock null
                if (rows.trackIds != coverage.trackIds || rows.source != coverage.source) return@withLock null
                val previous = layoutStore.loadMapLayoutOrEmpty().positions
                val result = LibraryLayoutRefresh.build(
                    rows, previous, MapRefreshFlag.inputVersion(rows.source, rows.dim), isActive, workBudget,
                ) ?: return@withLock null
                if (!isActive()) throw CancellationException("Map rebuild cancelled")
                // The old map stays until both files are written: layout first, then the state it describes.
                layoutStore.saveMapLayoutBestEffort(result.points, coverage.fingerprint)
                runCatching {
                    val store = AppGraph.mapRefreshStateStore
                    result.snapshot?.let { store.save(it) } ?: store.clear()
                }
                result
            }
        } ?: return Outcome.NOT_READY
        if (built.points.isEmpty()) return Outcome.NOT_READY
        revisionState.value++
        return Outcome.DONE
    }
}
