/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-owned state; the system-created service never opens a second favorites store. */
public data class NotificationFavoritesState(
    public val enabled: Boolean = false,
    public val ready: Boolean = false,
    public val favoriteIds: Set<TrackId> = emptySet(),
)

public object NotificationFavoritesRegistry {
    private val mutableState = MutableStateFlow(NotificationFavoritesState())
    public val state: StateFlow<NotificationFavoritesState> = mutableState.asStateFlow()

    /** Installed once at application startup; edits use the same durable store as the UI. */
    public var toggleFavorite: (suspend (TrackId) -> Unit)? = null

    public fun publish(state: NotificationFavoritesState) {
        mutableState.value = state
    }
}

internal val ToggleFavoriteCommand: SessionCommand = SessionCommand(
    "io.github.nikitasud.latentjam.TOGGLE_FAVORITE",
    Bundle.EMPTY,
)
