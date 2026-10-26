/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Debug-only marker (plan piece 3): a refreshed Map that waits for its deferred rebuild says so.
 * Shown only where the refresh flag's debug override is on; otherwise a stale map looks like any map.
 */
@Composable
internal fun MapStaleMarker(modifier: Modifier = Modifier) {
    if (!mapRefreshOverride()) return
    val stale by MapRebuild.stale.collectAsState()
    if (!stale) return
    Text(
        text = "stale",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(8.dp),
    )
}
