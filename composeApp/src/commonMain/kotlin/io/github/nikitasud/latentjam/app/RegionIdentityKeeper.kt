/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.IdentityRegion
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayoutRefresh
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSource
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import io.github.nikitasud.latentjam.smart.cluster.RegionIdentity
import io.github.nikitasud.latentjam.smart.cluster.RegionIdentityCodec
import io.github.nikitasud.latentjam.smart.cluster.RegionIdentitySnapshot

/**
 * The regions' identity for [LibraryRegionsService] (plan piece 4), behind [MapRefreshFlag]. Holds the
 * last snapshot in memory and in [AppGraph.regionIdentityStore]; every call runs under `mapWorkGate`
 * (the service's), on the map lane, and streams the rows ([RegionIdentity]).
 */
internal object RegionIdentityKeeper {
    private var current: RegionIdentitySnapshot? = null

    /**
     * The worlds after joining what changed since [current], or null when the regions must be
     * rediscovered: no valid snapshot, other inputs, or more than [LibraryLayoutRefresh.MAX_SHARE]
     * of the library changed. Asks for the deferred rebuild while any track is pending.
     */
    suspend fun join(
        engine: SimilarityEngine,
        loaded: List<TrackDescriptor>,
        loadedIds: List<TrackId>,
        source: LibraryVectorSource,
        context: String,
        isActive: () -> Boolean,
    ): List<LibraryWorld>? {
        val rows = engine.libraryMixRows(loadedIds) ?: return null
        if (rows.source != source) return null
        val version = MapRefreshFlag.inputVersion(rows.source, rows.dim)
        val descriptorOf = loaded.associateBy { it.id }
        val previous = snapshot(descriptorOf)?.takeIf { it.inputVersion == version && it.context == context } ?: return null
        val hashes = trackHashes(rows, descriptorOf, isActive)
        val before = HashMap<TrackId, Int>(previous.ids.size * 2).also { m -> previous.ids.forEachIndexed { i, id -> m[id] = i } }
        val present = rows.trackIds.toHashSet()
        val changed = ArrayList<TrackDescriptor>()
        val pending = BooleanArray(rows.size)
        rows.trackIds.forEachIndexed { i, id ->
            val at = before[id]
            if (at == null || previous.hashes[at] != hashes[i]) {
                changed += descriptorOf[id] ?: TrackDescriptor(id)
                pending[i] = true
            } else {
                pending[i] = previous.pending[at]
            }
        }
        val removed = previous.ids.count { it !in present }
        if (changed.size + removed > LibraryLayoutRefresh.MAX_SHARE * rows.size) return null
        val regions = if (changed.isEmpty() && removed == 0) previous.regions
            else RegionIdentity.join(previous.regions, changed, present, rows, isActive)
        val next = RegionIdentitySnapshot(version, rows.trackIds, hashes, pending, regions, maxOf(previous.nextId, RegionIdentity.nextId(regions)), context)
        keep(next)
        if (next.pendingCount > 0) MapRebuild.requested(stale = false, nowMs = io.github.nikitasud.latentjam.history.epochMillis())
        return worlds(regions, descriptorOf)
    }

    /**
     * [named] (today's discovery, already named) with identity: matched to the previous regions when a
     * valid snapshot exists, fresh identities otherwise. Nothing is pending afterwards.
     */
    suspend fun rediscover(
        engine: SimilarityEngine,
        loaded: List<TrackDescriptor>,
        loadedIds: List<TrackId>,
        source: LibraryVectorSource,
        named: List<LibraryWorld>,
        context: String,
        isActive: () -> Boolean,
    ): List<LibraryWorld>? {
        val rows = engine.libraryMixRows(loadedIds) ?: return null
        if (rows.source != source) return null
        val version = MapRefreshFlag.inputVersion(rows.source, rows.dim)
        val descriptorOf = loaded.associateBy { it.id }
        val previous = snapshot(descriptorOf)?.takeIf { it.inputVersion == version }
        val hashes = trackHashes(rows, descriptorOf, isActive)
        val regions = if (previous == null) {
            RegionIdentity.initial(named, rows, isActive)
        } else {
            val at = HashMap<TrackId, Int>(previous.ids.size * 2).also { m -> previous.ids.forEachIndexed { i, id -> m[id] = i } }
            val settled = HashSet<TrackId>()
            rows.trackIds.forEachIndexed { i, id ->
                val j = at[id] ?: return@forEachIndexed
                if (previous.hashes[j] == hashes[i] && !previous.pending[j]) settled += id
            }
            RegionIdentity.rediscover(previous.regions, named, settled, rows, RegionIdentity.DELTA, isActive = isActive, nextId = previous.nextId)
        }
        val nextId = maxOf(previous?.nextId ?: 0, RegionIdentity.nextId(regions))
        keep(RegionIdentitySnapshot(version, rows.trackIds, hashes, BooleanArray(rows.size), regions, nextId, context))
        return worlds(regions, descriptorOf)
    }

    /**
     * A track's row hash mixed with its tags as discovery reads them ([worldIdentity]): a tag edit marks
     * the track changed, so it rejoins and the deferred rebuild renames its region.
     */
    private suspend fun trackHashes(
        rows: io.github.nikitasud.latentjam.smart.cluster.LibraryVectorRows,
        descriptorOf: Map<TrackId, TrackDescriptor>,
        isActive: () -> Boolean,
    ): LongArray {
        val out = RegionIdentity.hashes(rows, isActive)
        rows.trackIds.forEachIndexed { i, id ->
            var h = out[i]
            for (c in descriptorOf[id]?.worldIdentity().toString()) h = (h xor c.code.toLong()) * 0x100000001b3L
            out[i] = h
        }
        return out
    }

    /** The non-vector inputs of a discovery, as a short stable string (FNV-1a 64 of their text). */
    fun context(playlists: List<PlaylistPresentationIdentity>, includeNoveltyMixes: Boolean, metadataReady: Boolean, audioReady: Boolean): String {
        var h = -0x340d631b7bdddcdbL
        for (c in "$playlists|$includeNoveltyMixes|$metadataReady|$audioReady") h = (h xor c.code.toLong()) * 0x100000001b3L
        return h.toULong().toString(16)
    }

    /** Whether tracks have changed since the last rediscovery (the deferred rebuild has work). */
    suspend fun pendingCount(): Int = snapshot(emptyMap())?.pendingCount ?: 0

    private suspend fun snapshot(descriptorOf: Map<TrackId, TrackDescriptor>): RegionIdentitySnapshot? =
        current ?: runCatching { AppGraph.regionIdentityStore.load() }.getOrNull()
            ?.let { RegionIdentityCodec.decode(it) { id -> descriptorOf[id] } }
            ?.also { current = it }

    private suspend fun keep(snapshot: RegionIdentitySnapshot) {
        current = snapshot
        // A failed write only costs one rediscovery after the next restart.
        runCatching { AppGraph.regionIdentityStore.save(RegionIdentityCodec.encode(snapshot)) }
    }

    /** In id order, so a region keeps its place while others come and go; members carry today's tags. */
    private fun worlds(regions: List<IdentityRegion>, descriptorOf: Map<TrackId, TrackDescriptor>): List<LibraryWorld> =
        regions.sortedBy { it.id }.map { r -> r.world.copy(tracks = r.world.tracks.map { descriptorOf[it.id] ?: it }) }
}
