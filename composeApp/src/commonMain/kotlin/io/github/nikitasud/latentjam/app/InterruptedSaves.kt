/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.write.JournalRecord
import io.github.nikitasud.latentjam.library.tags.write.JournalState
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** A file whose save was interrupted, as Settings lists it. [underRepair]: mid-replace, left out of playback. */
internal data class InterruptedSave(val record: JournalRecord, val label: String, val underRepair: Boolean)

/**
 * One row per waiting file, named by its song where the library knows it. Pass the library as
 * scanned, before files under repair are left out, or those would lose their names. A file the
 * library does not know is named by the last part of its path.
 */
internal fun interruptedSavesOf(
    records: List<JournalRecord>,
    tracks: List<TrackDescriptor>,
    keyOf: (TrackDescriptor) -> String?,
): List<InterruptedSave> {
    val byKey = HashMap<String, TrackDescriptor>()
    tracks.forEach { track -> keyOf(track)?.let { byKey[it] = track } }
    return records.groupBy { it.target }.map { (target, same) ->
        InterruptedSave(
            record = same.first(),
            label = trackLabel(byKey[target], target.substringAfterLast('/')),
            underRepair = same.any { it.state == JournalState.REPLACING },
        )
    }.sortedBy { it.label.lowercase() }
}
