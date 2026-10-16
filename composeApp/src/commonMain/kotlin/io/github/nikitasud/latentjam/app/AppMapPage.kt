/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout
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
        mapPageActive,
        historyRevision,
        discoveryMixLabel,
        semanticMixLabels,
    ) {
        // A previous keyed run may have been cancelled after publishing its transient build
        // state. Normalize before any precondition can return, so neither the first-build
        // placeholder nor a warm-page spinner can survive an interrupted transaction.
        mapPage.mapState = mapPage.mapState.afterInterruptedMapBuild()
        if (!mapPageActive) return@LaunchedEffect
        val loaded = tracks ?: return@LaunchedEffect
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
            mapPage.mapState = MapPageState.Indexing
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
                        val streamedBytes = MapMemoryBudget.projectionStreamedBytes(
                            coverage.size, 1344, LibraryLayout.experimentalBackend,
                        )
                        if (!mapWorkFits(streamedBytes)) {
                            throw MapComputationUnavailable("Insufficient memory for map projection", memoryLimited = true)
                        }
                        val rows = engine.libraryMixRows(loadedIds)
                        val computed = if (rows == null || rows.fingerprint != coverage.fingerprint) {
                            null
                        } else {
                            LibraryLayout.compute(
                                rows, previous.positions, isActive = { isActive },
                                workBudgetBytes = availableMapWorkBytes(),
                            )
                        }
                        if (computed == null) {
                            null // Index changed while waiting or reading; the new discovery run will retry.
                        } else {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            layoutStore.saveMapLayoutBestEffort(computed, coverage.fingerprint)
                            StoredLibraryLayout(
                                computed.associate { it.trackId to floatArrayOf(it.x, it.y) },
                                coverage.fingerprint,
                            )
                        }
                    },
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
