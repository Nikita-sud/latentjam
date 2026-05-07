/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * DeletePlaylistDialog.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.music.decision

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import io.github.nikitasud.latentjam.R
import io.github.nikitasud.latentjam.databinding.DialogDeletePlaylistBinding
import io.github.nikitasud.latentjam.music.MusicViewModel
import io.github.nikitasud.latentjam.music.resolve
import io.github.nikitasud.latentjam.ui.ViewBindingMaterialDialogFragment
import io.github.nikitasud.latentjam.util.collectImmediately
import io.github.nikitasud.latentjam.util.unlikelyToBeNull
import org.oxycblt.musikr.Playlist
import timber.log.Timber as L

/**
 * A [ViewBindingMaterialDialogFragment] that asks the user to confirm the deletion of a [Playlist].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class DeletePlaylistDialog : ViewBindingMaterialDialogFragment<DialogDeletePlaylistBinding>() {
    private val pickerModel: PlaylistPickerViewModel by viewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    // Information about what playlist to name for is initially within the navigation arguments
    // as UIDs, as that is the only safe way to parcel playlist information.
    private val args: DeletePlaylistDialogArgs by navArgs()

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.lbl_confirm_delete_playlist)
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                // Normally the navigateUp will occur after this, which then collides with the
                // playlist view's navigation. Forcefully navigate up to stop this.
                findNavController().navigateUp()
                // Now we can delete the playlist for-real this time.
                musicModel.deletePlaylist(
                    unlikelyToBeNull(pickerModel.currentPlaylistToDelete.value),
                    rude = true,
                )
            }
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogDeletePlaylistBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: DialogDeletePlaylistBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- VIEWMODEL SETUP ---
        musicModel.playlistDecision.consume()
        pickerModel.setPlaylistToDelete(args.playlistUid)
        collectImmediately(pickerModel.currentPlaylistToDelete, ::updatePlaylistToDelete)
    }

    private fun updatePlaylistToDelete(playlist: Playlist?) {
        if (playlist == null) {
            L.d("No playlist to delete, navigating away")
            findNavController().navigateUp()
            return
        }

        requireBinding().deletionInfo.text =
            getString(R.string.fmt_deletion_info, playlist.name.resolve(requireContext()))
    }
}
