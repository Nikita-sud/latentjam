/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
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

    /** The system's write-permission dialog was dismissed or declined. Not an error. */
    data object Cancelled : TagWriteOutcome

    /**
     * The tag writer would not rewrite this file — see [TagRefusal]. Every reason is a case where
     * writing anyway risked destroying data, so this is the writer working, not failing.
     */
    data class Refused(val reason: TagRefusal?) : TagWriteOutcome

    /** The save did not happen; the file is exactly as it was. */
    data object Failed : TagWriteOutcome

    /** Not enough free space for a safe save; nothing was touched. */
    data object NotEnoughSpace : TagWriteOutcome

    /** The save was interrupted; LatentJam finishes or undoes it once it may write again. */
    data object RecoveryPending : TagWriteOutcome

    /** This platform has no path to writing this file (a Music-library track on iOS). */
    data object Unavailable : TagWriteOutcome
}

internal enum class FileWriteStatus {
    SAVED, UNCHANGED, REFUSED, NO_SPACE, FAILED, RECOVERY_PENDING, DENIED, CANCELLED, MISSING, READ_ONLY,
    STOPPED,

    /** An interrupted save of this file was finished: the edit is in it. */
    RECOVERED,

    /** An interrupted save of this file was undone: it is its original again. */
    RESTORED,

    /** The file had been changed by another app since the interruption; it was left as found. */
    FOREIGN,
}

internal data class FileWriteResult(
    val key: String,
    val status: FileWriteStatus,
    val refusal: TagRefusal? = null,
    val newLength: Long? = null,
)

internal data class TagWriteReport(val kind: TagWriteKind, val results: List<FileWriteResult>)

/** The single-track editor's view of a one-file report. */
internal fun tagWriteOutcome(report: TagWriteReport): TagWriteOutcome {
    val result = report.results.singleOrNull() ?: return TagWriteOutcome.Failed
    return when (result.status) {
        FileWriteStatus.SAVED, FileWriteStatus.UNCHANGED, FileWriteStatus.RECOVERED -> TagWriteOutcome.Saved
        FileWriteStatus.REFUSED -> TagWriteOutcome.Refused(result.refusal)
        FileWriteStatus.NO_SPACE -> TagWriteOutcome.NotEnoughSpace
        FileWriteStatus.RECOVERY_PENDING -> TagWriteOutcome.RecoveryPending
        FileWriteStatus.CANCELLED, FileWriteStatus.DENIED, FileWriteStatus.STOPPED -> TagWriteOutcome.Cancelled
        FileWriteStatus.READ_ONLY -> TagWriteOutcome.Unavailable
        FileWriteStatus.FAILED, FileWriteStatus.MISSING, FileWriteStatus.RESTORED, FileWriteStatus.FOREIGN -> TagWriteOutcome.Failed
    }
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
 * Modifying media the app does not own needs the user's agreement on every
 * Android version, and it is asked before anything is written: a system dialog
 * covering many files at once on 11+, a dialog per file on 10, and the storage
 * permission on 7–9. iOS needs none — the app writes only files imported into
 * its own Documents. That is why this is a `@Composable` seam returning a
 * callback rather than a plain suspend function: the consent round trip is an
 * activity result.
 *
 * [onOutcome] runs exactly once per invocation of the returned callback.
 */
@Composable
expect fun rememberTagWriter(
    onOutcome: (TagWriteOutcome) -> Unit,
): (TrackDescriptor, TagEdits) -> Unit
