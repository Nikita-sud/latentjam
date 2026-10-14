// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** Dialog presentation only; the platform deleter still owns consent and durable outcomes. */
@Composable
internal fun ShellDeleteDialogs(
    sheets: ShellSheets,
    deleteTracks: (List<TrackDescriptor>) -> Unit,
    clearSelection: () -> Unit,
) {
    sheets.deleteTarget?.let { target ->
        DeleteTrackDialog(
            track = target,
            onConfirm = {
                sheets.deleteTarget = null
                deleteTracks(listOf(target))
            },
            onDismiss = { sheets.deleteTarget = null },
        )
    }
    sheets.deleteSelection?.let { selection ->
        DeleteTracksDialog(
            count = selection.size,
            onConfirm = {
                sheets.deleteSelection = null
                clearSelection()
                deleteTracks(selection)
            },
            onDismiss = { sheets.deleteSelection = null },
        )
    }
}
