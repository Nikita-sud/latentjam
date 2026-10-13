/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NotificationFavoritesRegistry
import io.github.nikitasud.latentjam.playback.NotificationFavoritesState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application lifetime, including cold starts from a headset or Android Auto. */
internal fun installNotificationFavorites() {
    val favorites = AppGraph.favorites
    val settings = AppGraph.settings
    val refreshMutex = Mutex()
    suspend fun refresh() = refreshMutex.withLock {
        val enabled = settings.notificationFavorite.value
        val ids = try {
            favorites.all().toSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            NotificationFavoritesRegistry.publish(NotificationFavoritesState(enabled = enabled))
            return@withLock
        }
        NotificationFavoritesRegistry.publish(NotificationFavoritesState(enabled, true, ids))
    }
    NotificationFavoritesRegistry.toggleFavorite = { id ->
        favorites.toggle(id)
        refresh()
    }
    AppGraph.appScope.launch {
        combine(settings.notificationFavorite, favorites.revision) { enabled, _ -> enabled }
            .collect { refresh() }
    }
}
