/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** Music-library entries on iOS are read-only; imported files and Android media can be deleted. */
internal fun canDeleteTrack(track: TrackDescriptor): Boolean =
    !track.audioUri.isNullOrBlank() && !track.id.value.startsWith("ios-media:")

/** Counts are retained even when only part of a request succeeds. */
data class TrackDeleteReport(
    val deleted: Int = 0,
    val denied: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
) {
    val total: Int get() = deleted + denied + failed + cancelled
    val isSilent: Boolean get() = deleted == 0 && denied == 0 && failed == 0
    val isPartial: Boolean get() = deleted > 0 && deleted < total
    val needsSummary: Boolean get() = isPartial || (total > 1 && (denied > 0 || failed > 0))
}

/** Reports the completed request, including partial success; cancellation alone is silent. */
@Composable
expect fun rememberTrackDeleter(
    onResult: (TrackDeleteReport) -> Unit,
): (List<TrackDescriptor>) -> Unit
