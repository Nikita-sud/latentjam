/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_failed
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_no_space
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_recovery_pending
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_refused
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_bad_image
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_bad_number
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_cancelled
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_changed_elsewhere
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_damaged
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_lost
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_missing
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_music_library
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_not_allowed
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_number_unreadable
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_protected
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_read_only_storage
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_stopped
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_tags_too_large
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_undone
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_unreadable
import io.github.nikitasud.latentjam.app.generated.resources.tag_problem_unsupported_format
import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.jetbrains.compose.resources.stringResource

/** Why a file was not saved, or cannot be edited, in the user's terms. */
internal enum class TagProblem {
    UNSUPPORTED_FORMAT, UNUSUAL_LAYOUT, DAMAGED, PROTECTED, TAGS_TOO_LARGE, BAD_NUMBER, NUMBER_UNREADABLE,
    BAD_IMAGE, READ_ONLY_STORAGE, MUSIC_LIBRARY, UNREADABLE, NO_SPACE, INTERRUPTED, NOT_ALLOWED, MISSING,
    STOPPED, CANCELLED, FAILED, UNDONE, CHANGED_ELSEWHERE, LOST,
}

/**
 * A codec refusal as the user sees it. Every refusal protects the file. The grouping only says
 * whether the file is of a kind the app does not edit, laid out in a way it will not risk,
 * damaged, protected, or asked for something invalid. Exhaustive on purpose: a new refusal must
 * choose its words.
 */
internal fun tagProblemOf(refusal: TagRefusal): TagProblem = when (refusal) {
    TagRefusal.UNSUPPORTED_FORMAT -> TagProblem.UNSUPPORTED_FORMAT
    TagRefusal.ID3_UNSUPPORTED_VERSION, TagRefusal.ID3_UNSYNCHRONISED, TagRefusal.ID3_UNKNOWN_HEADER_FLAGS,
    TagRefusal.ID3_UNSUPPORTED_LEGACY_TAG, TagRefusal.ID3_BEFORE_OTHER_CONTAINER, TagRefusal.ID3_UNKNOWN_AUDIO,
    TagRefusal.OGG_MULTIPLE_STREAMS, TagRefusal.OGG_UNKNOWN_CODEC, TagRefusal.MP4_FRAGMENTED,
    TagRefusal.MP4_UNKNOWN_OFFSET_BOX, TagRefusal.MP4_OFFSET_INSIDE_REWRITE -> TagProblem.UNUSUAL_LAYOUT
    TagRefusal.TRUNCATED, TagRefusal.ID3_BAD_EXTENDED_HEADER, TagRefusal.ID3_BAD_FOOTER,
    TagRefusal.ID3_MALFORMED_FRAMES, TagRefusal.ID3_NOT_TAGGABLE, TagRefusal.FLAC_STREAMINFO_NOT_FIRST,
    TagRefusal.FLAC_MALFORMED_METADATA, TagRefusal.OGG_BAD_PAGE_CRC, TagRefusal.OGG_MALFORMED_PAGES,
    TagRefusal.MP4_MALFORMED_ATOMS -> TagProblem.DAMAGED
    TagRefusal.MP4_DRM_PROTECTED -> TagProblem.PROTECTED
    TagRefusal.ID3_TAG_TOO_LARGE, TagRefusal.FLAC_BLOCK_TOO_LARGE, TagRefusal.MP4_TAGS_TOO_LARGE,
    TagRefusal.MP4_OFFSET_OVERFLOW -> TagProblem.TAGS_TOO_LARGE
    TagRefusal.INVALID_NUMBER -> TagProblem.BAD_NUMBER
    TagRefusal.UNREADABLE_NUMBER -> TagProblem.NUMBER_UNREADABLE
    TagRefusal.UNSUPPORTED_IMAGE -> TagProblem.BAD_IMAGE
    // A codec caught its own bug before writing: to the user, the save simply did not happen.
    TagRefusal.PLAN_INCONSISTENT -> TagProblem.FAILED
}

/** Null when the file holds the edit (saved now, already so, or finished from an interrupted save). */
internal fun tagProblemOf(status: FileWriteStatus, refusal: TagRefusal?, readOnlyIsMusicLibrary: Boolean): TagProblem? =
    when (status) {
        FileWriteStatus.SAVED, FileWriteStatus.UNCHANGED, FileWriteStatus.RECOVERED -> null
        FileWriteStatus.REFUSED -> refusal?.let(::tagProblemOf) ?: TagProblem.UNUSUAL_LAYOUT
        FileWriteStatus.NO_SPACE -> TagProblem.NO_SPACE
        FileWriteStatus.RECOVERY_PENDING -> TagProblem.INTERRUPTED
        FileWriteStatus.DENIED -> TagProblem.NOT_ALLOWED
        FileWriteStatus.CANCELLED -> TagProblem.CANCELLED
        FileWriteStatus.MISSING -> TagProblem.MISSING
        FileWriteStatus.READ_ONLY -> if (readOnlyIsMusicLibrary) TagProblem.MUSIC_LIBRARY else TagProblem.READ_ONLY_STORAGE
        FileWriteStatus.STOPPED -> TagProblem.STOPPED
        FileWriteStatus.FAILED -> TagProblem.FAILED
        FileWriteStatus.RESTORED -> TagProblem.UNDONE
        FileWriteStatus.FOREIGN -> TagProblem.CHANGED_ELSEWHERE
    }

/** One file's fate in a finished save. [problem] is null exactly when the file holds the edit. */
internal data class TagSaveEntry(
    val key: String,
    val status: FileWriteStatus,
    val refusal: TagRefusal? = null,
    val newLength: Long? = null,
    val problem: TagProblem? = null,
) {
    val saved: Boolean get() = problem == null
}

/**
 * A finished save, file by file: what the editor reports exactly (spec §6.3). [lost]: the editor's
 * save is one the writer no longer holds and will never report, so nothing is known file by file.
 * [cover]: the save's cover edit, which every saved file now holds. Not kept by [toSaveable]: it is
 * acted on when the save finishes, never by an editor restored afterwards.
 */
internal data class TagSaveResult(
    val entries: List<TagSaveEntry>,
    val lost: Boolean = false,
    val cover: CoverEdit = CoverEdit.Keep,
) {
    val savedCount: Int get() = entries.count { it.saved }
    val notChanged: List<TagSaveEntry> get() = entries.filterNot { it.saved }

    /** The user closed the consent prompt, so nothing was written. What they just chose, not a result to report. */
    val cancelled: Boolean get() = entries.isNotEmpty() && entries.all { it.status == FileWriteStatus.CANCELLED }

    /** Five strings per file, so any key survives a Bundle: key, status, refusal, new length, problem. */
    fun toSaveable(): List<String> = entries.flatMap { entry ->
        listOf(
            entry.key,
            entry.status.name,
            entry.refusal?.name.orEmpty(),
            entry.newLength?.toString().orEmpty(),
            entry.problem?.name.orEmpty(),
        )
    }

    companion object {
        fun of(report: TagWriteReport, readOnlyIsMusicLibrary: Boolean) = TagSaveResult(
            report.results.map { result ->
                TagSaveEntry(
                    key = result.key,
                    status = result.status,
                    refusal = result.refusal,
                    newLength = result.newLength,
                    problem = tagProblemOf(result.status, result.refusal, readOnlyIsMusicLibrary),
                )
            },
            cover = report.cover,
        )
    }
}

/** The result [TagSaveResult.toSaveable] wrote, or null when the list is not one. */
internal fun tagSaveResultOf(saved: List<String>): TagSaveResult? {
    if (saved.size % 5 != 0) return null
    return try {
        TagSaveResult(
            saved.chunked(5).map { (key, status, refusal, length, problem) ->
                TagSaveEntry(
                    key = key,
                    status = FileWriteStatus.valueOf(status),
                    refusal = refusal.takeIf { it.isNotEmpty() }?.let(TagRefusal::valueOf),
                    newLength = length.takeIf { it.isNotEmpty() }?.toLong(),
                    problem = problem.takeIf { it.isNotEmpty() }?.let(TagProblem::valueOf),
                )
            },
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * An editor's handle on its own saves. It remembers its request ids across recreation (the list
 * is saved by [rememberTagSaver]) and so hears back even when the report arrived while it was away:
 * that report waits in [TagWriteCoordinator.unclaimed] and is claimed here.
 *
 * Tracks this platform cannot write at all are reported READ_ONLY without being queued. The
 * editors set such tracks aside before saving (see [readTagFile]), so a request of both kinds does
 * not happen.
 */
internal class TagSaver(
    val access: TagWriteAccess,
    private val mine: SnapshotStateList<Long>,
    private val onFinished: (TagSaveResult) -> Unit,
) {
    val busy: Boolean get() = mine.isNotEmpty()

    fun owns(requestId: Long): Boolean = requestId in mine

    fun start(tracks: List<TrackDescriptor>, edits: TagEdits) {
        val keys = tracks.mapNotNull(access::keyOf).distinct()
        when {
            edits.isEmpty -> finish(keys.map { TagSaveEntry(it, FileWriteStatus.UNCHANGED) })
            keys.isEmpty() -> finish(
                tracks.map { track ->
                    TagSaveEntry(
                        key = track.id.value,
                        status = FileWriteStatus.READ_ONLY,
                        problem = tagProblemOf(FileWriteStatus.READ_ONLY, null, access.readOnlyIsMusicLibrary),
                    )
                },
            )
            else -> {
                val id = access.coordinator.enqueue(keys, edits)
                if (id == null) {
                    finish(keys.map { TagSaveEntry(it, FileWriteStatus.FAILED, problem = TagProblem.FAILED) })
                } else {
                    mine += id
                    listen(id)
                }
            }
        }
    }

    /** Stops this editor's own requests, never the one in front of them. */
    fun stop() {
        mine.toList().forEach { access.coordinator.stop(it) }
    }

    fun attach() {
        mine.toList().forEach(::listen)
    }

    fun detach() {
        mine.toList().forEach(access.coordinator::unlisten)
    }

    /**
     * Drops the saved ids the coordinator turns out not to hold once it has restored, each reported
     * as a lost save. Without this a recreated editor would show "Saving…" for ever and, since a
     * sheet cannot be closed mid-save, trap the user in it.
     */
    suspend fun forgetLost() {
        for (id in mine.toList()) {
            if (!access.coordinator.knows(id) && mine.remove(id)) onFinished(TagSaveResult(emptyList(), lost = true))
        }
    }

    /** Takes this editor's reports that were handed out while nobody listened. */
    fun claim(unclaimed: List<TagWriteReport>) {
        for (report in unclaimed) {
            if (report.id !in mine) continue
            access.coordinator.acknowledge(report)
            complete(report)
        }
    }

    private fun listen(id: Long) {
        access.coordinator.listen(id, ::complete)
    }

    private fun complete(report: TagWriteReport) {
        if (mine.remove(report.id)) onFinished(TagSaveResult.of(report, access.readOnlyIsMusicLibrary))
    }

    private fun finish(entries: List<TagSaveEntry>) = onFinished(TagSaveResult(entries))
}

/** A [TagSaver] for this editor, or null where saving is impossible. Its request ids survive recreation. */
@Composable
internal fun rememberTagSaver(onFinished: (TagSaveResult) -> Unit): TagSaver? {
    val access = rememberTagWriteAccess() ?: return null
    val current by rememberUpdatedState(onFinished)
    val mine = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<Long>()
    }
    val saver = remember(access, mine) { TagSaver(access, mine) { current(it) } }
    DisposableEffect(saver) {
        saver.attach()
        onDispose { saver.detach() }
    }
    LaunchedEffect(saver) { saver.forgetLost() }
    val unclaimed by access.coordinator.unclaimed.collectAsState()
    LaunchedEffect(saver, unclaimed) { saver.claim(unclaimed) }
    return saver
}

/** This saver's running request's progress; null while it waits its turn or when it has none. */
@Composable
internal fun TagSaver.progress(): TagWriteProgress? {
    val progress by access.coordinator.progress.collectAsState()
    return progress?.takeIf { owns(it.requestId) }
}

@Composable
internal fun tagProblemText(problem: TagProblem): String = stringResource(
    when (problem) {
        TagProblem.UNSUPPORTED_FORMAT -> Res.string.tag_problem_unsupported_format
        TagProblem.UNUSUAL_LAYOUT -> Res.string.info_edit_refused
        TagProblem.DAMAGED -> Res.string.tag_problem_damaged
        TagProblem.PROTECTED -> Res.string.tag_problem_protected
        TagProblem.TAGS_TOO_LARGE -> Res.string.tag_problem_tags_too_large
        TagProblem.BAD_NUMBER -> Res.string.tag_problem_bad_number
        TagProblem.NUMBER_UNREADABLE -> Res.string.tag_problem_number_unreadable
        TagProblem.BAD_IMAGE -> Res.string.tag_problem_bad_image
        TagProblem.READ_ONLY_STORAGE -> Res.string.tag_problem_read_only_storage
        TagProblem.MUSIC_LIBRARY -> Res.string.tag_problem_music_library
        TagProblem.UNREADABLE -> Res.string.tag_problem_unreadable
        TagProblem.NO_SPACE -> Res.string.info_edit_no_space
        TagProblem.INTERRUPTED -> Res.string.info_edit_recovery_pending
        TagProblem.NOT_ALLOWED -> Res.string.tag_problem_not_allowed
        TagProblem.MISSING -> Res.string.tag_problem_missing
        TagProblem.STOPPED -> Res.string.tag_problem_stopped
        TagProblem.CANCELLED -> Res.string.tag_problem_cancelled
        TagProblem.FAILED -> Res.string.info_edit_failed
        TagProblem.UNDONE -> Res.string.tag_problem_undone
        TagProblem.CHANGED_ELSEWHERE -> Res.string.tag_problem_changed_elsewhere
        TagProblem.LOST -> Res.string.tag_problem_lost
    },
)
