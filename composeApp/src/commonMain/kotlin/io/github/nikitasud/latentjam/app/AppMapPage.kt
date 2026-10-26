/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import io.github.nikitasud.latentjam.history.epochMillis
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayoutRefresh
import io.github.nikitasud.latentjam.smart.cluster.MapMemoryBudget
import io.github.nikitasud.latentjam.smart.cluster.MapComputationUnavailable
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldSemanticTitle
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorlds
import io.github.nikitasud.latentjam.smart.cluster.StoredLibraryLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Owns map effects and cache lifetime independently of the player shell. */
@Composable
internal fun rememberMapPageModel(
    engine: SimilarityEngine,
    tracks: List<TrackDescriptor>?,
    libraryWorlds: LibraryWorldsState,
    mapPageActive: Boolean,
    historyRevision: Long,
    discoveryMixLabel: String,
    semanticMixLabels: Map<LibraryWorldSemanticTitle, String>,
): MapPageModel {
    // Geometry is cached on disk and in memory. Listening facts refresh independently;
    // MapTab keeps its lens, selection and viewport when those facts change.
    val mapPageCache = remember { MapPageCache() }
    val mapPage = remember { MapPageModel() }
    // A background rebuild (plan piece 3) rewrote the disk layout: re-run and reload it from disk.
    val rebuildRevision by MapRebuild.revision.collectAsState()
    // Keyed on the region labels too, exactly as the For You effect above is: they are
    // stringResource reads, so a locale change produces new ones and the page they name has to be
    // rebuilt to pick them up. Cheap when that is all that changed -- LibraryLayout.covers finds
    // the stored layout still valid, so no PCA/t-SNE runs.
    LaunchedEffect(
        tracks,
        libraryWorlds.worlds,
        libraryWorlds.worldLibraryIds,
        libraryWorlds.builtWorldsKey,
        libraryWorlds.memoryLimited,
        libraryWorlds.audioVectorsReady,
        libraryWorlds.discoveryFailed,
        mapPageActive,
        historyRevision,
        discoveryMixLabel,
        semanticMixLabels,
        rebuildRevision,
    ) {
        // A previous keyed run may have been cancelled after publishing its transient build
        // state. Normalize before any precondition can return, so neither the first-build
        // placeholder nor a warm-page spinner can survive an interrupted transaction.
        mapPage.mapState = mapPage.mapState.afterInterruptedMapBuild()
        if (!mapPageActive) return@LaunchedEffect
        val loaded = tracks ?: return@LaunchedEffect
        // No scheduled rebuild for a day: run it here while on external power (plan piece 3, fallback).
        if (MapRefreshFlag.enabled && libraryWorlds.audioVectorsReady && MapRebuild.fallbackDue(epochMillis())) {
            MapRebuild.runFallback(isActive = { isActive })
        }
        if (libraryWorlds.memoryLimited) {
            // Explain the refusal even after a warm page. MapPageCache and the disk snapshot
            // stay intact, so retrying does not discard the last successful coordinates.
            mapPage.mapState = MapPageState.MemoryLimited
            return@LaunchedEffect
        }
        val loadedIds = loaded.map(TrackDescriptor::id)
        val layoutStore = AppGraph.layoutStore

        // Coverage is a cached fingerprint/population lookup, not the fused vector matrix.
        val coverage = withContext(Dispatchers.Default) {
            engine.libraryMixCoverage(loadedIds)
        } ?: return@LaunchedEffect

        // Finding (a) of the final review: Tsne/Pca are O(n^2) per array with nothing capping n
        // anywhere upstream. Refuse outright above the ceiling rather than silently draw a
        // truncated map that looks complete -- see LibraryLayout.MAX_TRACKS's doc for why this
        // number and what it costs at it.
        if (coverage.size > LibraryLayout.MAX_TRACKS) {
            mapPage.mapState = MapPageState.TooLarge(
                trackCount = coverage.size,
                limit = LibraryLayout.MAX_TRACKS,
            )
            return@LaunchedEffect
        }

        // Spec section 8, row 3: a library too small for TrackClustering to form even one
        // region must not leave the Map stuck showing the indexing state forever for a user
        // whose indexing already finished -- see mapFallbackRegions. Final review finding
        // (IMPORTANT): gated on mapFallbackShouldApply, not bare `worlds.isEmpty()` -- `worlds`
        // reads empty both when discovery genuinely found nothing for this library AND when it
        // simply has not reported on this library yet (see that function's doc), and only the
        // first may borrow the fallback. When it does not apply, `regions` falls through to
        // `worlds` itself (empty or not) and the existing `regions.isEmpty()` check below keeps
        // this effect's prior state rather than treating "not ready" as "ready".
        // Worlds are a snapshot with their own library key. A non-empty result for yesterday's
        // library is no safer than an empty one: it can name deleted tracks and route region
        // actions into a stale queue. Wait until discovery reports on this exact population.
        val currentWorlds = libraryWorlds.worlds.takeIf { libraryWorlds.worldLibraryIds == loadedIds }.orEmpty()
        val regions = if (mapFallbackShouldApply(currentWorlds, libraryWorlds.worldLibraryIds, loadedIds)) {
            mapFallbackRegions(loaded, coverage.trackIds)
        } else {
            currentWorlds
        }
        if (regions.isEmpty()) {
            mapPage.mapRegions = emptyList()
            // A discovery that failed will not report on this library by itself; waiting for it
            // left the Map saying "still reading" for good (issue: endless Map indexing).
            mapPage.mapState = if (libraryWorlds.discoveryFailed) MapPageState.Unavailable else MapPageState.Indexing
            return@LaunchedEffect
        }

        // Region ids are indices into `regions`: every listening/headline lookup below keys off
        // this same index, so it must stay the one place a track's region id is assigned.
        val regionOf = buildMap {
            regions.forEachIndexed { index, world ->
                for (track in world.tracks) put(track.id, index)
            }
        }

        val stableStateBeforeBuild = mapPage.mapState
        var transientBuildState: MapPageState? = null
        val built = try {
            withContext(AppGraph.mapLayoutDispatcher) {
                mapWorkGate.withLock {
                val layout = mapPageCache.layout(
                    trackIds = coverage.trackIds,
                    fingerprint = coverage.fingerprint,
                    load = { layoutStore.loadMapLayoutOrEmpty() },
                    compute = { previous ->
                        withContext(Dispatchers.Main) {
                            transientBuildState = stableStateBeforeBuild.duringMapBuild()
                            mapPage.mapState = transientBuildState!!
                        }
                        // Streamed: rows are read from the engine per chunk, so the fused
                        // n x dim input (54 MB at 10,000) is never held, and budgeted without it.
                        // Measured once, before the rows allocate their chunk buffer: the budget
                        // already counts that chunk, so a second reading after it exists would
                        // charge it twice.
                        val streamedBytes = MapMemoryBudget.projectionStreamedBytes(
                            coverage.size, 1344, LibraryLayout.experimentalBackend,
                        )
                        val workBudget = mapWorkBytes(streamedBytes)
                        if (workBudget < streamedBytes) {
                            throw MapComputationUnavailable("Insufficient memory for map projection", memoryLimited = true)
                        }
                        val rows = engine.libraryMixRows(loadedIds)
                        // Vectors analysed since `coverage` was taken do not discard the run: the
                        // effect is not keyed on them, so nothing would retry it before the analysis
                        // ends. The layout is filed under `coverage.fingerprint`, which then no longer
                        // matches, so the next visit redraws it from the newer vectors.
                        val usable = rows != null && rows.trackIds == coverage.trackIds && rows.source == coverage.source
                        // Refresh small changes (plan piece 1) only once the analysis is over: while it runs
                        // every analysed track moves the rows, and the 5-minute reuse below applies instead.
                        val refreshOn = MapRefreshFlag.enabled && libraryWorlds.audioVectorsReady
                        var snapshot: io.github.nikitasud.latentjam.smart.cluster.MapRefreshSnapshot? = null
                        val computed = if (!usable) {
                            null
                        } else if (refreshOn) {
                            val inputVersion = MapRefreshFlag.inputVersion(rows!!.source, rows.dim)
                            val stored = runCatching { AppGraph.mapRefreshStateStore.load() }.getOrNull()
                            val artists = loaded.associate { it.id to it.artist }
                            val result = try {
                                stored?.let {
                                    LibraryLayoutRefresh.refresh(
                                        rows, previous.positions, it, inputVersion, isActive = { isActive },
                                        artistOf = { id -> artists[id] },
                                    )
                                } ?: run {
                                    // A refresh consumed nothing it cannot re-read: rows are read again from the start.
                                    val fresh = engine.libraryMixRows(loadedIds)
                                    if (fresh == null || fresh.trackIds != coverage.trackIds || fresh.source != coverage.source) null
                                    else LibraryLayoutRefresh.build(fresh, previous.positions, inputVersion, { isActive }, workBudget)
                                }
                            } finally {
                                stored?.close()
                            }
                            snapshot = result?.snapshot
                            if (result != null) {
                                if (!result.refreshed) MapRebuild.builtInPage()
                                else if (result.stale || result.changedSinceFull > 0) MapRebuild.requested(result.stale, epochMillis())
                            }
                            result?.points
                        } else {
                            LibraryLayout.compute(
                                rows!!, previous.positions, isActive = { isActive },
                                workBudgetBytes = workBudget,
                            )
                        }
                        if (computed == null) {
                            null // The population changed; a library change or the end of the analysis re-runs this.
                        } else {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            layoutStore.saveMapLayoutBestEffort(computed, coverage.fingerprint)
                            if (refreshOn) {
                                // Written after the layout it describes; a missing or failed write only means
                                // the next change rebuilds.
                                runCatching {
                                    val store = AppGraph.mapRefreshStateStore
                                    if (snapshot != null) store.save(snapshot!!) else store.clear()
                                }
                            }
                            StoredLibraryLayout(
                                computed.associate { it.trackId to floatArrayOf(it.x, it.y) },
                                coverage.fingerprint,
                            )
                        }
                    },
                    // Only while the analysis runs, and only for the same kind of vectors: once it
                    // ends (this effect re-runs on that) the Map is drawn from the final vectors.
                    reuseRecentFor = if (libraryWorlds.audioVectorsReady) Duration.ZERO else MAP_LAYOUT_REUSE,
                    source = coverage.source,
                    revision = rebuildRevision,
                ) ?: return@withLock null
                mapPageCache.page(
                    layout = layout,
                    regionOf = regionOf,
                    regionNames = regionDisplayNames(regions, discoveryMixLabel, semanticMixLabels),
                    historyRevision = historyRevision,
                    readStats = { AppGraph.history.stats() },
                )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: MapComputationUnavailable) {
            mapPage.mapState = if (unavailable.memoryLimited) MapPageState.MemoryLimited else MapPageState.Unavailable
            null
        } catch (failure: Throwable) {
            println("Map: could not assemble the current page: $failure")
            // A warm page keeps showing; a first build says it failed rather than "still reading".
            if (stableStateBeforeBuild !is MapPageState.Ready) mapPage.mapState = MapPageState.Unavailable
            null
        }

        if (built == null || built.dots.isEmpty()) {
            // This run is still authoritative only while its exact transient value remains
            // installed. An external/newer transition wins; otherwise roll the transaction
            // back to the last stable page (or Indexing for a cold first build).
            if (
                isActive &&
                transientBuildState != null &&
                mapPage.mapState === transientBuildState
            ) {
                mapPage.mapState = stableStateBeforeBuild
            }
            return@LaunchedEffect
        }

        // Final review finding (MINOR 3): MapPageState.Ready is documented to always carry a
        // non-empty page -- reachable only when `positions` (from this visit's own
        // libraryMixFeatures call) and `regionOf` (built from `regions`, which may trace back to
        // a differently-timed libraryMixFeatures/LibraryWorlds.discover call) end up disjoint.
        // Guarded here, at the one place Ready is ever constructed, so MapTab.kt never has to
        // decide what to tell a reader about a Ready state that -- by definition -- means
        // indexing already finished; it keeps whatever state was already showing instead.
        mapPage.mapRegions = regions
        mapPage.mapState = MapPageState.Ready(built)
    }
    return mapPage
}

/** How long a layout computed while an analysis runs is shown again before the Map recomputes it. */
private val MAP_LAYOUT_REUSE = 5.minutes
