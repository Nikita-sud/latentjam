/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [known] in one key space with the name hashes ArtistSets.parse gives unknown parts: a part [known] names as entity E
 * keys as the name hash of the first spelling seen for E. Spellings that normalise alike then always share a key (even
 * when only one of them is an exact pack name), and two names of one entity share one through the alias.
 * Safe to call from any thread.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class EntityAliases(private val known: KnownArtists) : KnownArtists {
    private val alias = HashMap<Long, Long>()
    private val lock = AtomicInt(0)

    override fun canonical(matchKey: Long): Long? = null

    override fun canonicalOf(raw: String): Long? {
        val entity = known.canonicalOf(raw) ?: return null
        while (!lock.compareAndSet(0, 1)) Unit
        try {
            return alias.getOrPut(entity) { ArtistSets.keyHash(ArtistSets.normalize(raw)) }
        } finally {
            lock.store(0)
        }
    }
}
