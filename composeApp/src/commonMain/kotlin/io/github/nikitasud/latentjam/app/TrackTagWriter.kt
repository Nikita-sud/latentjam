/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagRefusal

/**
 * What became of one file in a save.
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
 * its own Documents. That is why saving is a `@Composable` seam (see
 * [rememberTagSaver]) rather than a plain suspend function: the consent round
 * trip is an activity result.
 */
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

/**
 * A finished request: what became of each file, and the request [id] its editor listens on. [cover]
 * is the request's cover edit, which the files that hold the edit now have; a request restored
 * after process death carries its stashed cover, so a report nobody claims still knows it.
 */
internal data class TagWriteReport(
    val kind: TagWriteKind,
    val results: List<FileWriteResult>,
    val id: Long = 0,
    val cover: CoverEdit = CoverEdit.Keep,
)
