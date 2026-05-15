/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * AlbumSongSortDialog.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.detail.sort

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.nikitasud.latentjam.databinding.DialogSortBinding
import io.github.nikitasud.latentjam.detail.DetailViewModel
import io.github.nikitasud.latentjam.list.sort.Sort
import io.github.nikitasud.latentjam.list.sort.SortDialog
import io.github.nikitasud.latentjam.util.collectImmediately
import org.oxycblt.musikr.Album
import timber.log.Timber as L

/**
 * A [SortDialog] that controls the [Sort] of [DetailViewModel.albumSongSort].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class AlbumSongSortDialog : SortDialog() {
    private val detailModel: DetailViewModel by activityViewModels()

    override fun onBindingCreated(binding: DialogSortBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- VIEWMODEL SETUP ---
        collectImmediately(detailModel.currentAlbum, ::updateAlbum)
    }

    override fun getInitialSort() = detailModel.albumSongSort

    override fun applyChosenSort(sort: Sort) {
        detailModel.applyAlbumSongSort(sort)
    }

    override fun getModeChoices() = listOf(Sort.Mode.ByDisc, Sort.Mode.ByTrack)

    private fun updateAlbum(album: Album?) {
        if (album == null) {
            L.d("No album to sort, navigating away")
            findNavController().navigateUp()
        }
    }
}
