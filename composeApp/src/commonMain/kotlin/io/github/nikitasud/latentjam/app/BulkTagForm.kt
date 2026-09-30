/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** The fields every selected file can share: per-track ones (title, track number, lyrics) are not offered. */
internal enum class BulkField { ARTIST, ALBUM, ALBUM_ARTIST, GENRE, YEAR, TRACK_TOTAL, DISC_NUMBER, DISC_TOTAL }

/** What the selected files hold for one field: one value (possibly none, as ""), or different values. */
internal sealed interface SharedValue {
    data class Same(val value: String) : SharedValue
    data object Different : SharedValue
}

internal class BulkBaseline(val shared: Map<BulkField, SharedValue>) {

    /** What a field shows before the user touches it: the shared value, or empty over different ones. */
    fun initialText(field: BulkField): String = (shared[field] as? SharedValue.Same)?.value.orEmpty()

    companion object {
        fun of(snapshots: List<TagSnapshot>): BulkBaseline = BulkBaseline(
            BulkField.entries.associateWith { field ->
                val values = snapshots.map { valueOf(it, field)?.trim().orEmpty() }.distinct()
                values.singleOrNull()?.let(SharedValue::Same) ?: SharedValue.Different
            },
        )

        private fun valueOf(snapshot: TagSnapshot, field: BulkField): String? = when (field) {
            BulkField.ARTIST -> snapshot.artist
            BulkField.ALBUM -> snapshot.album
            BulkField.ALBUM_ARTIST -> snapshot.albumArtist
            BulkField.GENRE -> snapshot.genre
            BulkField.YEAR -> snapshot.year
            BulkField.TRACK_TOTAL -> snapshot.trackTotal?.toString()
            BulkField.DISC_NUMBER -> snapshot.discNumber?.toString()
            BulkField.DISC_TOTAL -> snapshot.discTotal?.toString()
        }
    }
}

internal data class BulkFieldState(val text: String, val removed: Boolean = false)

/**
 * The N-track editor's choices (spec §6.2). A field absent from [fields] is Keep. A typed field is
 * Set, unless its box is empty, which is Keep again: an empty box never removes anything. A
 * removed field is Remove, which only the field's clear control produces.
 */
internal data class BulkTagForm(
    val fields: Map<BulkField, BulkFieldState> = emptyMap(),
    val cover: CoverChoice = CoverChoice.Keep,
) {
    fun text(field: BulkField, baseline: BulkBaseline): String = fields[field]?.text ?: baseline.initialText(field)

    fun removed(field: BulkField): Boolean = fields[field]?.removed == true

    fun typed(field: BulkField, text: String) = copy(fields = fields + (field to BulkFieldState(text)))

    fun remove(field: BulkField) = copy(fields = fields + (field to BulkFieldState("", removed = true)))

    fun keep(field: BulkField) = copy(fields = fields - field)

    fun edits(baseline: BulkBaseline, cover: CoverEdit = CoverEdit.Keep): TagEdits = TagEdits(
        artist = edit(BulkField.ARTIST, baseline),
        album = edit(BulkField.ALBUM, baseline),
        albumArtist = edit(BulkField.ALBUM_ARTIST, baseline),
        genre = edit(BulkField.GENRE, baseline),
        year = edit(BulkField.YEAR, baseline),
        trackTotal = edit(BulkField.TRACK_TOTAL, baseline),
        discNumber = edit(BulkField.DISC_NUMBER, baseline),
        discTotal = edit(BulkField.DISC_TOTAL, baseline),
        cover = cover,
    )

    /** How many fields a save would write, the cover included: the "3 fields" of "Change 3 fields in 12 files". */
    fun changedFieldCount(baseline: BulkBaseline): Int =
        BulkField.entries.count { edit(it, baseline) != null } + (if (cover == CoverChoice.Keep) 0 else 1)

    private fun edit(field: BulkField, baseline: BulkBaseline): String? {
        val state = fields[field] ?: return null
        if (state.removed) return ""
        val typed = state.text.trim()
        if (typed.isEmpty()) return null
        val shared = baseline.shared[field]
        return typed.takeIf { shared !is SharedValue.Same || it != shared.value }
    }

    /** The cover first ("keep", "remove", "replace:<reference>"), then name, text and "1"/"0" for each touched field. */
    fun toSaveable(): List<String> = buildList {
        add(
            when (cover) {
                CoverChoice.Keep -> "keep"
                CoverChoice.Remove -> "remove"
                is CoverChoice.Replace -> "replace:" + cover.reference
            },
        )
        for ((field, state) in fields) {
            add(field.name)
            add(state.text)
            add(if (state.removed) "1" else "0")
        }
    }

    companion object {
        fun fromSaveable(saved: List<String>): BulkTagForm? {
            if (saved.isEmpty() || (saved.size - 1) % 3 != 0) return null
            val cover = when (val value = saved[0]) {
                "keep" -> CoverChoice.Keep
                "remove" -> CoverChoice.Remove
                else -> value.removePrefix("replace:").takeIf { value.startsWith("replace:") && isTagCoverReference(it) }
                    ?.let(CoverChoice::Replace) ?: return null
            }
            val fields = saved.drop(1).chunked(3).associate { (name, text, removed) ->
                val field = BulkField.entries.firstOrNull { it.name == name } ?: return null
                field to BulkFieldState(text, removed == "1")
            }
            return BulkTagForm(fields, cover)
        }
    }
}

/** The selected tracks, split by what their files allow: edited, or set aside with the reason (spec §6.2). */
internal class BulkTargets(
    val editable: List<Pair<TrackDescriptor, TagSnapshot>>,
    val notEditable: List<Pair<TrackDescriptor, TagProblem>>,
)

internal fun bulkTargets(tracks: List<TrackDescriptor>, reads: List<TagFileRead>): BulkTargets {
    val editable = ArrayList<Pair<TrackDescriptor, TagSnapshot>>()
    val notEditable = ArrayList<Pair<TrackDescriptor, TagProblem>>()
    tracks.zip(reads).forEach { (track, read) ->
        when (read) {
            is TagFileRead.Ready -> editable += track to read.snapshot
            is TagFileRead.NotEditable -> notEditable += track to read.problem
        }
    }
    return BulkTargets(editable, notEditable)
}

/** How a file is named in a result: its title, else its file name, else its key. */
internal fun trackLabel(track: TrackDescriptor?, key: String): String =
    track?.title?.takeIf(String::isNotBlank) ?: track?.fileName?.takeIf(String::isNotBlank) ?: key

/** How many files a list of set-aside or unchanged files names before "and N more". */
internal const val BULK_LISTED_FILES = 20

/**
 * A finished N-track save as its result screen shows it (spec §6.3): the counts, and the files not
 * changed up to the ones it lists. Saved state holds only this, so a batch of thousands of files
 * cannot outgrow it; the files that were saved are never listed, so they are not kept.
 */
internal data class BulkResult(val savedCount: Int, val total: Int, val notChanged: List<TagSaveEntry>) {
    val notChangedCount: Int get() = total - savedCount

    /** The two counts, then five strings for each listed file ([TagSaveResult.toSaveable]). */
    fun toSaveable(): List<String> =
        listOf(savedCount.toString(), total.toString()) + TagSaveResult(notChanged).toSaveable()

    companion object {
        fun of(result: TagSaveResult, listed: Int = BULK_LISTED_FILES) =
            BulkResult(result.savedCount, result.entries.size, result.notChanged.take(listed))

        fun fromSaveable(saved: List<String>): BulkResult? {
            val savedCount = saved.getOrNull(0)?.toIntOrNull() ?: return null
            val total = saved.getOrNull(1)?.toIntOrNull() ?: return null
            if (savedCount !in 0..total) return null
            val listed = tagSaveResultOf(saved.drop(2))?.entries ?: return null
            if (listed.size > total - savedCount || listed.any { it.saved }) return null
            return BulkResult(savedCount, total, listed)
        }
    }
}
