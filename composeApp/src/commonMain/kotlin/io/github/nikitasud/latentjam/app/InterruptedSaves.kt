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
 * Finish could not finish it, since forgetting it leaves it damaged for good. [missing]: the last
 * Finish found the file not there. [label] is null when nothing names the file any more (a record
 * from before records kept where the file was, of a song the library no longer holds): Settings
 * then shows a generic name rather than a bare MediaStore id.
 */
internal data class InterruptedSave(
    val record: JournalRecord,
    val label: String?,
    val underRepair: Boolean,
    val forgettable: Boolean = !underRepair,
    val missing: Boolean = false,
)

/**
 * One row per waiting file, named by its song where the library knows it. Pass the library as
 * scanned, before files under repair are left out, or those would lose their names. A file the
 * library does not know is named by the last part of the path its record kept, else of its key
 * when that is a path; a `content://` key's last part is only an id, which names nothing.
 * [couldNotFinish]: the files a Finish tried and could not finish (see
 * [TagWriteCoordinator.couldNotFinish]); [missing]: those found not there (see
 * [TagWriteCoordinator.missingAtFinish]).
 */
internal fun interruptedSavesOf(
    records: List<JournalRecord>,
    tracks: List<TrackDescriptor>,
    couldNotFinish: Set<String> = emptySet(),
    missing: Set<String> = emptySet(),
    keyOf: (TrackDescriptor) -> String?,
): List<InterruptedSave> {
    val byKey = HashMap<String, TrackDescriptor>()
    tracks.forEach { track -> keyOf(track)?.let { byKey[it] = track } }
    return records.groupBy { it.target }.map { (target, same) ->
        val underRepair = same.any { it.state == JournalState.REPLACING }
        InterruptedSave(
            record = same.first(),
            label = byKey[target]?.let { trackLabel(it, "") }?.takeIf(String::isNotEmpty)
                ?: same.firstNotNullOfOrNull { it.path }
                    ?.substringAfterLast('/')?.takeIf(String::isNotEmpty)
                ?: target.takeUnless { it.startsWith("content://") }?.substringAfterLast('/'),
            underRepair = underRepair,
            forgettable = !underRepair || target in couldNotFinish,
            missing = target in missing,
        )
    }.sortedWith(compareBy(nullsLast()) { it.label?.lowercase() })
}
