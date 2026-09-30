/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagSnapshot

/** What the editor will do with the cover: nothing, remove it, or put a picked image in its place. */
internal sealed interface CoverChoice {
    data object Keep : CoverChoice
    data object Remove : CoverChoice
    data class Replace(val reference: String) : CoverChoice
}

/**
 * The single-track editor's fields as the user sees them: text, with numbers as digits.
 *
 * [edits] compares against the form built from the file ([of]), so only what the user changed is
 * written. Whitespace alone is not a change, and an emptied field is removed (`""`, spec §3.1).
 * Fields are compared trimmed because every codec stores them trimmed; a stray space typed at the
 * end must not rewrite a file.
 */
internal data class TagEditorForm(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val trackTotal: String = "",
    val discNumber: String = "",
    val discTotal: String = "",
    val lyrics: String = "",
    val cover: CoverChoice = CoverChoice.Keep,
) {
    fun edits(baseline: TagEditorForm, cover: CoverEdit = CoverEdit.Keep): TagEdits = TagEdits(
        title = changed(title, baseline.title),
        artist = changed(artist, baseline.artist),
        album = changed(album, baseline.album),
        albumArtist = changed(albumArtist, baseline.albumArtist),
        genre = changed(genre, baseline.genre),
        year = changed(year, baseline.year),
        trackNumber = changed(trackNumber, baseline.trackNumber),
        trackTotal = changed(trackTotal, baseline.trackTotal),
        discNumber = changed(discNumber, baseline.discNumber),
        discTotal = changed(discTotal, baseline.discTotal),
        lyrics = changed(lyrics, baseline.lyrics),
        cover = cover,
    )

    fun hasChanges(baseline: TagEditorForm): Boolean = !edits(baseline).isEmpty || cover != CoverChoice.Keep

    /** Twelve strings, so any text survives a Bundle; the cover as "keep", "remove" or "replace:<reference>". */
    fun toSaveable(): List<String> = listOf(
        title, artist, album, albumArtist, genre, year, trackNumber, trackTotal, discNumber, discTotal, lyrics,
        when (cover) {
            CoverChoice.Keep -> KEEP
            CoverChoice.Remove -> REMOVE
            is CoverChoice.Replace -> REPLACE + cover.reference
        },
    )

    companion object {
        private const val KEEP = "keep"
        private const val REMOVE = "remove"
        private const val REPLACE = "replace:"

        fun of(snapshot: TagSnapshot) = TagEditorForm(
            title = snapshot.title.orEmpty(),
            artist = snapshot.artist.orEmpty(),
            album = snapshot.album.orEmpty(),
            albumArtist = snapshot.albumArtist.orEmpty(),
            genre = snapshot.genre.orEmpty(),
            year = snapshot.year.orEmpty(),
            trackNumber = snapshot.trackNumber?.toString().orEmpty(),
            trackTotal = snapshot.trackTotal?.toString().orEmpty(),
            discNumber = snapshot.discNumber?.toString().orEmpty(),
            discTotal = snapshot.discTotal?.toString().orEmpty(),
            lyrics = snapshot.lyrics.orEmpty(),
        )

        fun fromSaveable(saved: List<String>): TagEditorForm? {
            if (saved.size != 12) return null
            val cover = when (val value = saved[11]) {
                KEEP -> CoverChoice.Keep
                REMOVE -> CoverChoice.Remove
                else -> value.removePrefix(REPLACE).takeIf { value.startsWith(REPLACE) && isTagCoverReference(it) }
                    ?.let(CoverChoice::Replace) ?: return null
            }
            return TagEditorForm(
                saved[0], saved[1], saved[2], saved[3], saved[4], saved[5],
                saved[6], saved[7], saved[8], saved[9], saved[10], cover,
            )
        }
    }
}

private fun changed(value: String, baseline: String): String? = value.trim().takeIf { it != baseline.trim() }

/** Digits only, at most three: a tag number runs from 1 to 999 ([TagEdits.numbersAreValid] checks the rest). */
internal fun numberInput(text: String): String = text.filter { it in '0'..'9' }.take(3)

/** A year or a full date ("2001-05-03"), which an untouched tag keeps (spec §3.4). */
internal fun yearInput(text: String): String = text.filter { it in '0'..'9' || it == '-' }.take(10)
