/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * SongDetailDialog.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.detail

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import io.github.nikitasud.latentjam.R
import io.github.nikitasud.latentjam.databinding.DialogSongDetailBinding
import io.github.nikitasud.latentjam.detail.list.SongProperty
import io.github.nikitasud.latentjam.detail.list.SongPropertyAdapter
import io.github.nikitasud.latentjam.list.adapter.UpdateInstructions
import io.github.nikitasud.latentjam.ml.ui.EditMetadataDialog
import io.github.nikitasud.latentjam.ui.ViewBindingMaterialDialogFragment
import io.github.nikitasud.latentjam.util.collectImmediately
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingMaterialDialogFragment] that shows information about a Song.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class SongDetailDialog : ViewBindingMaterialDialogFragment<DialogSongDetailBinding>() {
    private val detailModel: DetailViewModel by activityViewModels()
    // Information about what song to display is initially within the navigation arguments
    // as a UID, as that is the only safe way to parcel an song.
    private val args: SongDetailDialogArgs by navArgs()
    private val detailAdapter = SongPropertyAdapter()

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogSongDetailBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        super.onConfigDialog(builder)
        builder
            .setTitle(R.string.lbl_props)
            .setPositiveButton(R.string.lbl_ok, null)
            .setNeutralButton(R.string.lbl_edit_metadata) { _, _ ->
                val uid = args.songUid
                EditMetadataDialog.newInstance(uid)
                    .show(parentFragmentManager, EditMetadataDialog.TAG)
            }
    }

    override fun onBindingCreated(binding: DialogSongDetailBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.detailProperties.adapter = detailAdapter
        // DetailViewModel handles most initialization from the navigation argument.
        detailModel.setSong(args.songUid)
        detailModel.toShow.consume()
        collectImmediately(detailModel.currentSong, ::updateSong)
        collectImmediately(detailModel.currentSongProperties, ::updateSongProperties)
    }

    private fun updateSong(song: Song?) {
        L.d("No song to show, navigating away")
        if (song == null) {
            findNavController().navigateUp()
            return
        }
    }

    private fun updateSongProperties(songProperties: List<SongProperty>) {
        detailAdapter.update(songProperties, UpdateInstructions.Replace(0))
    }
}
