/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorlds
import io.github.nikitasud.latentjam.smart.cluster.WorldNamingGroups
import kotlin.time.TimeSource
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The library's worlds (For You's mixes, the Map's regions), discovered outside composition so a
 * background Map rebuild can run them without UI (docs/map-cost-research-2026-10-25.md, implementation
 * plan, piece 3a). The body is the one the For You / Map effect in App ran, moved unchanged; App
 * still decides when to call it.
 */
internal object LibraryRegionsService {
    /** Process-wide: App observes it, the Map page reads it, a headless rebuild may fill it. */
    val state: LibraryWorldsState = LibraryWorldsState()

    /**
     * Rebuilds [state]'s worlds for [loaded] unless they were built from the same inputs. Returns
     * quietly when coverage, memory or features are not available, exactly as the effect did.
     */
    suspend fun refresh(
        engine: SimilarityEngine,
        loaded: List<TrackDescriptor>,
        playlistGroups: () -> List<Pair<String, List<TrackId>>>,
        playlistPresentationIdentity: List<PlaylistPresentationIdentity>,
        includeNoveltyMixes: Boolean,
        metadataReady: Boolean,
        audioReady: Boolean,
        rebuild: Boolean = false,
    ) {
        val libraryWorlds = state
        val loadedIds = loaded.map(TrackDescriptor::id)
        mapWorkGate.withLock {
            // Check raw-vector identity without allocating the fused matrix on a warm
            // page return. A changed vector, tag, playlist, preference or readiness tier
            // still invalidates discovery, even if the library population is unchanged.
            val coverage = engine.libraryMixCoverage(loadedIds) ?: return
            val descriptorIdentity = loaded.map(TrackDescriptor::worldIdentity)
            val cachedKey = libraryWorlds.builtWorldsKey
            // Plan piece 4: with the refresh flag on and the analysis over, regions keep their identity.
            // A change joins the regions as they are (no discovery); a rebuild rediscovers and matches.
            val identityOn = MapRefreshFlag.enabled && audioReady
            if (
                !rebuild &&
                cachedKey != null &&
                libraryWorlds.builtWorldNoveltyPreference == includeNoveltyMixes &&
                libraryWorlds.builtWorldReadiness == (metadataReady to audioReady) &&
                cachedKey.playlistIdentity == playlistPresentationIdentity &&
                cachedKey.discovery.trackIds == loadedIds &&
                cachedKey.discovery.source == coverage.source &&
                cachedKey.discovery.vectorFingerprint == coverage.fingerprint &&
                cachedKey.discovery.descriptorIdentity == descriptorIdentity
            ) {
                // A refused newer population must not hide reusable regions if the
                // library returns to this cached population. Projection checks its own budget.
                libraryWorlds.memoryLimited = false
                return
            }
            // Measured on the map lane, where a short reading may collect garbage first.
            libraryWorlds.memoryLimited = withContext(AppGraph.mapLayoutDispatcher) {
                !mapWorkFits(4L * coverage.size * 1344 + 8L * 1024 * 1024)
            }
            if (libraryWorlds.memoryLimited) return
            // Joining only answers a change of vectors or members. A changed playlist, novelty preference
            // or readiness tier changes the discovery itself: the keeper refuses to join, and it rediscovers.
            val identityContext = RegionIdentityKeeper.context(playlistPresentationIdentity, includeNoveltyMixes, metadataReady, audioReady)
            if (identityOn && !rebuild) {
                val joined = withContext(AppGraph.mapLayoutDispatcher) {
                    RegionIdentityKeeper.join(engine, loaded, loadedIds, coverage.source, identityContext, isActive = { isActive })
                }
                if (joined != null) {
                    libraryWorlds.worldLibraryIds = loadedIds
                    libraryWorlds.worlds = joined
                    // No features were built: the key carries what the early check compares.
                    val discoveryKey = LibraryWorldDiscoveryKey(
                        trackIds = loadedIds,
                        source = coverage.source,
                        vectorFingerprint = coverage.fingerprint,
                        descriptorIdentity = descriptorIdentity,
                        semanticsCount = -1,
                    )
                    libraryWorlds.builtWorldDiscoveryKey = discoveryKey
                    libraryWorlds.builtDiscoveredWorlds = null
                    libraryWorlds.builtWorldsKey = LibraryWorldsKey(discoveryKey, playlistPresentationIdentity)
                    libraryWorlds.builtWorldNoveltyPreference = includeNoveltyMixes
                    libraryWorlds.builtWorldReadiness = metadataReady to audioReady
                    libraryWorlds.discoveryFailed = false
                    return
                }
            }
            val features = engine.libraryMixFeatures(
                ids = loadedIds,
                // This preference is the listener's explicit request for semantic novelty
                // and effect routing, so loading the shared model here is useful work. The
                // default path keeps restored/failed-only libraries completely lazy.
                loadMissingSemantics = includeNoveltyMixes,
            ) ?: return
            val discoveryKey = LibraryWorldDiscoveryKey(
                trackIds = loadedIds,
                source = features.vectorSpace.source,
                vectorFingerprint = features.vectorSpace.fingerprint,
                descriptorIdentity = descriptorIdentity,
                semanticsCount = features.semantics.size,
            )
            val worldsKey = LibraryWorldsKey(
                discovery = discoveryKey,
                playlistIdentity = playlistPresentationIdentity,
            )
            if (!rebuild && worldsKey == libraryWorlds.builtWorldsKey) {
                libraryWorlds.builtWorldNoveltyPreference = includeNoveltyMixes
                libraryWorlds.builtWorldReadiness = metadataReady to audioReady
                return
            }
            // Snapshot for the background pass; a world mostly inside one named group
            // takes that group's name. Playlists come first — the listener's own word
            // outranks the artist's on a tie — then real (multi-track, one artist's)
            // albums, whose titles are the artist's own curation.
            val playlistGroups = playlistGroups()
            val cachedDiscovery = libraryWorlds.builtDiscoveredWorlds
                ?.takeIf { !rebuild && libraryWorlds.builtWorldDiscoveryKey == discoveryKey }
            // Discovery is derived library work, not an interaction prerequisite. Keep it
            // on the Map's low-priority serial lane so a cold restore cannot make an
            // immediately opened page compete with clustering at normal CPU priority.
            val discovered = cachedDiscovery ?: withContext(AppGraph.mapLayoutDispatcher) {
                val started = TimeSource.Monotonic.markNow()
                LibraryWorlds.discover(
                    library = loaded,
                    vectorSpace = features.vectorSpace,
                    semantics = features.semantics,
                    isActive = { isActive },
                    // Discovery takes seconds; one collection first keeps released
                    // matrices from pushing a large library onto the k-means fallback.
                    workBudgetBytes = collectMapGarbage().let { availableMapWorkBytes() },
                ).also { mixes ->
                    val routed = mixes.groupingBy { it.content }.eachCount()
                    println(
                        "SMART: built ${mixes.size} ${features.vectorSpace.source} " +
                            "local mixes (semantic=${features.semantics.size}, " +
                            "routes=$routed) in " +
                            "${started.elapsedNow().inWholeMilliseconds} ms",
                    )
                }
            }
            val namedWorlds = withContext(AppGraph.mapLayoutDispatcher) {
                // Counted over the tracks that exist (a playlist keeps the ids of
                // deleted files) and over real albums only; the map replay builds the
                // same groups the same way.
                val naming = WorldNamingGroups.of(
                    library = loadedIds.toHashSet(),
                    playlists = playlistGroups,
                    albums = LibraryCatalog.build(loaded).albums.map { it.title to it.tracks },
                )
                LibraryWorlds.namedAfterGroups(discovered, naming)
            }.let { named ->
                if (!identityOn) named
                else withContext(AppGraph.mapLayoutDispatcher) {
                    RegionIdentityKeeper.rediscover(engine, loaded, loadedIds, coverage.source, named, identityContext, isActive = { isActive })
                } ?: named
            }
            libraryWorlds.worldLibraryIds = loadedIds
            libraryWorlds.worlds = namedWorlds
            libraryWorlds.builtWorldDiscoveryKey = discoveryKey
            libraryWorlds.builtDiscoveredWorlds = discovered
            libraryWorlds.builtWorldsKey = worldsKey
            libraryWorlds.builtWorldNoveltyPreference = includeNoveltyMixes
            libraryWorlds.builtWorldReadiness = metadataReady to audioReady
            libraryWorlds.discoveryFailed = false
        
        }
    }
}
