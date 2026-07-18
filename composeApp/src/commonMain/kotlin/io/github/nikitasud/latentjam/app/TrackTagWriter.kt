/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.library.tags.Id3Refusal
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * What became of a save.
 *
 * Every one of these except [Saved] leaves the file exactly as it was, and all
 * of them are shown to the user. A tag editor that fails quietly is worse than
 * one that is not offered — the user walks away believing the correction stuck.
 */
sealed interface TagWriteOutcome {

    /** The file's tags now say what the user asked, and the media index knows it. */
    data object Saved : TagWriteOutcome

    /** The system's write-permission dialog was dismissed. Not an error. */
    data object Cancelled : TagWriteOutcome

    /**
     * The tag writer would not rewrite this file — see [Id3Refusal]. Every
     * reason is a case where writing anyway risked destroying data, so this is
     * the writer working, not failing.
     */
    data class Refused(val reason: Id3Refusal?) : TagWriteOutcome

    /** The file could not be read or written. */
    data object Failed : TagWriteOutcome

    /** This platform or OS version has no path to writing the file at all. */
    data object Unavailable : TagWriteOutcome
}

/**
 * Applies [TagEdits] to a track's underlying file.
 *
 * ### Why the file and not the index
 *
 * The obvious implementation — writing MediaStore's TITLE/ARTIST/ALBUM/YEAR
 * columns through a ContentResolver — does not work, and was shipped once and
 * removed. Those columns are DERIVED by the media provider from the file's own
 * tags: the update is accepted, reports success, and is silently discarded.
 * Verified on API 36, and a `content update` from the shell with full
 * permissions fails identically, so it is the platform rather than the app.
 *
 * So the file itself is rewritten, and the media index is then told to re-read
 * it. Both halves are required: without the rescan the new tags are on disk and
 * every screen in the app still shows the old ones, which looks exactly like
 * the failure above.
 *
 * ### Consent
 *
 * Modifying media the app does not own needs the user's agreement, so a save
 * raises a system dialog before anything is written. That is why this is a
 * `@Composable` seam returning a callback rather than a plain suspend function:
 * the consent round trip is an activity result.
 *
 * [onOutcome] runs exactly once per invocation of the returned callback.
 */
@Composable
expect fun rememberTagWriter(
    onOutcome: (TagWriteOutcome) -> Unit,
): (TrackDescriptor, TagEdits) -> Unit
