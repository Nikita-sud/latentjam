/*
 * Copyright (c) 2021 LatentJam Project
 * ArtistListFragment.kt is part of LatentJam.
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
 
package io.github.nikitasud.latentjam.home.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import io.github.nikitasud.latentjam.R
import io.github.nikitasud.latentjam.databinding.FragmentHomeListBinding
import io.github.nikitasud.latentjam.detail.DetailViewModel
import io.github.nikitasud.latentjam.home.HomeViewModel
import io.github.nikitasud.latentjam.list.ListFragment
import io.github.nikitasud.latentjam.list.ListViewModel
import io.github.nikitasud.latentjam.list.SelectableListListener
import io.github.nikitasud.latentjam.list.adapter.SelectionIndicatorAdapter
import io.github.nikitasud.latentjam.list.recycler.ArtistViewHolder
import io.github.nikitasud.latentjam.list.recycler.FastScrollRecyclerView
import io.github.nikitasud.latentjam.list.sort.Sort
import io.github.nikitasud.latentjam.music.IndexingState
import io.github.nikitasud.latentjam.music.MusicViewModel
import io.github.nikitasud.latentjam.playback.PlaybackViewModel
import io.github.nikitasud.latentjam.playback.formatDurationMsPopup
import io.github.nikitasud.latentjam.util.collectImmediately
import io.github.nikitasud.latentjam.util.positiveOrNull
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] that shows a list of [Artist]s.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class ArtistListFragment :
    ListFragment<Artist, FragmentHomeListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener {
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val artistAdapter = ArtistAdapter(this)

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeRecycler.apply {
            id = R.id.home_artist_recycler
            adapter = artistAdapter
            popupProvider = this@ArtistListFragment
            listener = this@ArtistListFragment
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_artist_48)
            contentDescription = getString(R.string.lbl_artists)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_artists)

        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.artistList, ::updateArtists)
        collectImmediately(homeModel.empty, musicModel.indexingState, ::updateNoMusicIndicator)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
    }

    override fun getPopupData(pos: Int): FastScrollRecyclerView.PopupProvider.PopupData? {
        val artist = homeModel.artistList.value.getOrNull(pos) ?: return null
        // Change how we display the popup depending on the current sort mode.
        return when (homeModel.artistSort.mode) {
            // By Name -> Use Name
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(artist.name.thumb() ?: "?")

            // Duration -> Use compact bucket duration
            is Sort.Mode.ByDuration ->
                artist.durationMs?.formatDurationMsPopup()?.let {
                    FastScrollRecyclerView.PopupProvider.PopupData(it)
                }

            // Count -> Use song count
            is Sort.Mode.ByCount ->
                artist.songs.size.positiveOrNull()?.toString()?.let {
                    FastScrollRecyclerView.PopupProvider.PopupData(it)
                }

            // Unsupported sort, error gracefully
            else -> null
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Artist) {
        detailModel.showArtist(item)
    }

    override fun onOpenMenu(item: Artist) {
        listModel.openMenu(R.menu.parent, item)
    }

    private fun updateArtists(artists: List<Artist>) {
        artistAdapter.update(artists, homeModel.artistInstructions.consume())
    }

    private fun updateNoMusicIndicator(empty: Boolean, indexingState: IndexingState?) {
        val binding = requireBinding()
        binding.homeRecycler.isInvisible = empty
        binding.homeNoMusic.isInvisible = !empty
        binding.homeNoMusicAction.isVisible =
            indexingState == null || (empty && indexingState is IndexingState.Completed)
    }

    private fun updateSelection(selection: List<Music>) {
        artistAdapter.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        // Only highlight the artist if it is currently playing, and if the currently
        // playing song is also contained within.
        val artist = (parent as? Artist)?.takeIf { song?.run { artists.contains(it) } ?: false }
        artistAdapter.setPlaying(artist, isPlaying)
    }

    /**
     * A [SelectionIndicatorAdapter] that shows a list of [Artist]s using [ArtistViewHolder].
     *
     * @param listener An [SelectableListListener] to bind interactions to.
     */
    private class ArtistAdapter(private val listener: SelectableListListener<Artist>) :
        SelectionIndicatorAdapter<Artist, ArtistViewHolder>(ArtistViewHolder.DIFF_CALLBACK) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ArtistViewHolder.from(parent)

        override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }
}
