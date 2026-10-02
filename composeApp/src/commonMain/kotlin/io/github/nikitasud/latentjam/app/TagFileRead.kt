/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** What the editor learns from a file before the user types anything (spec §6.1). */
internal sealed interface TagFileRead {
    data class Ready(val snapshot: TagSnapshot) : TagFileRead
    data class NotEditable(val problem: TagProblem) : TagFileRead
}

/** A codec's reading of a file: no codec at all, a refusal, or a snapshot to edit. */
internal fun tagFileReadOf(snapshot: TagSnapshot?): TagFileRead {
    val refusal = snapshot?.refusal
    return when {
        snapshot == null -> TagFileRead.NotEditable(TagProblem.UNSUPPORTED_FORMAT)
        refusal != null -> TagFileRead.NotEditable(tagProblemOf(refusal))
        else -> TagFileRead.Ready(snapshot)
    }
}

/**
 * Reads [track]'s tags from its file through [TagCodecs], never the whole file: random access lets
 * each codec read only its tag region. A file that cannot be opened or parsed is
 * [TagProblem.UNREADABLE]. The editor then shows that instead of fields it could not fill honestly.
 */
internal expect suspend fun readTagFile(track: TrackDescriptor): TagFileRead
