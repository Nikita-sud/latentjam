/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * What an offline teacher knows about a library artist, as a vector for SMART's descriptor space.
 *
 * The artist resolves through [MusicEntityResolver] to entity ids, and [ArtistKnowledgePack] keeps one
 * descriptor per entity. Both assets load lazily and fail closed, so a build without the pack behaves
 * exactly as before.
 */
public class ArtistKnowledge(
    private val entities: MusicEntityResolver,
    loadPack: () -> ByteArray?,
) {
    private val pack: ArtistKnowledgePack? by lazy {
        runCatching { loadPack()?.let(ArtistKnowledgePack::parse) }.getOrNull()
    }

    /** Forces the lazy asset read; intended for an app-lifetime background coroutine. */
    public fun preload() {
        pack
    }

    /**
     * The descriptor for a track tagged [artist]. The whole tag is tried first, then [primaryArtist],
     * because a collaboration's tag rarely names a single entity. Null when neither is known.
     */
    public fun descriptor(artist: String?, primaryArtist: String? = null): FloatArray? {
        val knowledge = pack ?: return null
        return listOfNotNull(artist, primaryArtist?.takeIf { it != artist })
            .filter(String::isNotBlank)
            .firstNotNullOfOrNull { name ->
                entities.resolve(name).takeIf(IntArray::isNotEmpty)?.let { knowledge.descriptor(it, name) }
            }
    }
}
