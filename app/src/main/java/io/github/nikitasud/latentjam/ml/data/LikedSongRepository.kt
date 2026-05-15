/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.oxycblt.musikr.Music

/**
 * Singleton wrapper around [LikedSongDao] that exposes the liked-uid set as a hot
 * `StateFlow<Set<UID>>`. UI consumers should observe [likedSet] and rebind affected
 * rows when it changes; the recorder reads it synchronously to stamp `liked` on
 * each finalized listening event.
 */
@Singleton
class LikedSongRepository @Inject constructor(private val dao: LikedSongDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val likedSet: StateFlow<Set<Music.UID>> =
        dao.likedSongsFlow()
            .map { it.toHashSet() }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun likedSetFlow(): Flow<Set<Music.UID>> = likedSet

    /** Snapshot of the current liked set. Safe to call from any thread. */
    fun isLikedNow(uid: Music.UID): Boolean = likedSet.value.contains(uid)

    fun toggle(uid: Music.UID) {
        scope.launch {
            if (dao.isLikedCount(uid) > 0) {
                dao.delete(uid)
            } else {
                dao.insert(LikedSongEntity(songUid = uid, likedAtMs = System.currentTimeMillis()))
            }
        }
    }
}
