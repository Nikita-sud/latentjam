/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * What an offline teacher knows about a library artist, as a vector for SMART's descriptor space.
 *
 * The artist resolves through [MusicEntityResolver] to entity ids, and [ArtistKnowledgePack] keeps one
 * descriptor per entity. An artist the pack does not know gets [ArtistAdapter]'s guess from the track's
 * own trusted-text vector instead. Every asset loads lazily and fails closed, so a build without them
 * behaves exactly as before.
 */
public class ArtistKnowledge(
    private val entities: MusicEntityResolver,
    loadAdapter: () -> ByteArray? = { null },
    loadPack: () -> ByteArray?,
) {
    private val pack: ArtistKnowledgePack? by lazy {
        runCatching { loadPack()?.let(ArtistKnowledgePack::parse) }.getOrNull()
    }
    private val adapter: ArtistAdapter? by lazy {
        runCatching { loadAdapter()?.let(ArtistAdapter::parse) }.getOrNull()
    }

    /** Forces the lazy asset reads; intended for an app-lifetime background coroutine. */
    public fun preload() {
        pack
        adapter
    }

    /**
     * The descriptor for a track tagged [artist]. The whole tag is tried first, then [primaryArtist],
     * because a collaboration's tag rarely names a single entity. When the pack knows neither, the
     * adapter guesses from [text], the track's trusted-text vector. Null when there is nothing to go on.
     */
    public fun descriptor(artist: String?, primaryArtist: String? = null, text: FloatArray? = null): FloatArray? {
        val known = pack?.let { knowledge ->
            listOfNotNull(artist, primaryArtist?.takeIf { it != artist })
                .filter(String::isNotBlank)
                .firstNotNullOfOrNull { name ->
                    entities.resolve(name).takeIf(IntArray::isNotEmpty)?.let { knowledge.descriptor(it, name) }
                }
        }
        // Without the pack the adapter's guesses would be the only descriptors, a space it was never
        // measured alone in; it only fills the pack's gaps.
        if (known != null || pack == null) return known
        return text?.let { adapter?.descriptor(it) }
    }
}
