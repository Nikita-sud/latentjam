/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.write.JournalRecord
import io.github.nikitasud.latentjam.library.tags.write.JournalState
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * A file whose save was interrupted, as Settings lists it. [underRepair]: mid-replace, left out of
 * playback. [forgettable]: Settings offers Forget. A file under repair is forgettable only once a
 * Finish could not finish it, since forgetting it leaves it damaged for good.
 */
internal data class InterruptedSave(
    val record: JournalRecord,
    val label: String,
    val underRepair: Boolean,
    val forgettable: Boolean = !underRepair,
)

/**
 * One row per waiting file, named by its song where the library knows it. Pass the library as
 * scanned, before files under repair are left out, or those would lose their names. A file the
 * library does not know is named by the last part of its path. [couldNotFinish]: the files a
 * Finish tried and could not finish (see [TagWriteCoordinator.couldNotFinish]).
 */
internal fun interruptedSavesOf(
    records: List<JournalRecord>,
    tracks: List<TrackDescriptor>,
    couldNotFinish: Set<String> = emptySet(),
    keyOf: (TrackDescriptor) -> String?,
): List<InterruptedSave> {
    val byKey = HashMap<String, TrackDescriptor>()
    tracks.forEach { track -> keyOf(track)?.let { byKey[it] = track } }
    return records.groupBy { it.target }.map { (target, same) ->
        val underRepair = same.any { it.state == JournalState.REPLACING }
        InterruptedSave(
            record = same.first(),
            label = trackLabel(byKey[target], target.substringAfterLast('/')),
            underRepair = underRepair,
            forgettable = !underRepair || target in couldNotFinish,
        )
    }.sortedBy { it.label.lowercase() }
}
