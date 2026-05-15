/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * EditMetadataDialog.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.ml.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import io.github.nikitasud.latentjam.R
import io.github.nikitasud.latentjam.ml.data.TrackMetadataResolver
import io.github.nikitasud.latentjam.ml.predictor.RecommendationEngine
import io.github.nikitasud.latentjam.music.MusicRepository
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.tag.Name
import timber.log.Timber

/**
 * Lightweight bottom dialog for editing a song's metadata overrides. The user can set genre /
 * artist / year; the values layer over the audio file's tags via [TrackMetadataResolver] and feed
 * [io.github.nikitasud.latentjam.ml.predictor.MetadataRerank].
 *
 * No file writes — overrides live in Room. Clearing all three fields deletes the row.
 */
@AndroidEntryPoint
class EditMetadataDialog : DialogFragment() {

    @Inject lateinit var metadataResolver: TrackMetadataResolver
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var recommendationEngine: RecommendationEngine

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val uidString =
            arguments?.getString(ARG_SONG_UID) ?: error("EditMetadataDialog requires ARG_SONG_UID")
        val songUid = Music.UID.fromString(uidString) ?: error("Invalid Music.UID: $uidString")
        val song =
            musicRepository.library?.findSong(songUid) ?: error("Song not in library: $uidString")

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_metadata, null, false)
        val songName = view.findViewById<android.widget.TextView>(R.id.edit_meta_song_name)
        val genreField = view.findViewById<MaterialAutoCompleteTextView>(R.id.edit_meta_genre)
        val artistField = view.findViewById<TextInputEditText>(R.id.edit_meta_artist)
        val yearField = view.findViewById<TextInputEditText>(R.id.edit_meta_year)

        val artistName = (song.artists.firstOrNull()?.name as? Name.Known)?.raw ?: "?"
        songName.text = "$artistName — ${song.name.raw}"

        val genres = metadataResolver.knownGenres()
        genreField.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, genres))

        // Pre-fill with current effective values so the user sees what's there.
        genreField.setText(metadataResolver.effectiveGenre(song).orEmpty(), false)
        artistField.setText(metadataResolver.effectiveArtist(song).orEmpty())
        yearField.setText(metadataResolver.effectiveYear(song)?.toString().orEmpty())

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lbl_edit_metadata)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newGenre = genreField.text?.toString()
                val newArtist = artistField.text?.toString()
                val newYear = yearField.text?.toString()?.toIntOrNull()
                lifecycleScope.launch {
                    try {
                        metadataResolver.setOverride(songUid, newGenre, newArtist, newYear)
                        Timber.i(
                            "EditMetadataDialog: saved override for %s — genre=%s artist=%s year=%s",
                            songUid,
                            newGenre,
                            newArtist,
                            newYear,
                        )
                        // Force the recommender's library snapshot to rebuild so subsequent
                        // SMART fires use the new metadata.
                        recommendationEngine.invalidateLibraryCache()
                    } catch (e: Exception) {
                        Timber.w(e, "EditMetadataDialog: save failed")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.lbl_metadata_clear) { _, _ ->
                lifecycleScope.launch {
                    try {
                        metadataResolver.setOverride(songUid, null, null, null)
                        recommendationEngine.invalidateLibraryCache()
                    } catch (e: Exception) {
                        Timber.w(e, "EditMetadataDialog: clear failed")
                    }
                }
            }
            .create()
    }

    companion object {
        const val TAG = "EditMetadataDialog"
        private const val ARG_SONG_UID = "song_uid"

        fun newInstance(songUid: Music.UID): EditMetadataDialog =
            EditMetadataDialog().apply {
                arguments = Bundle().apply { putString(ARG_SONG_UID, songUid.toString()) }
            }
    }
}
