/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Playback continues behind Settings and in the background; its hidden UI need not observe ticks. */
@Composable
internal fun <T> Flow<T>.collectPlayerState(initialValue: T, active: Boolean): State<T> {
    val visibleFlow = remember(this, active) { if (active) this else emptyFlow() }
    return visibleFlow.collectAsStateWithLifecycle(initialValue)
}
