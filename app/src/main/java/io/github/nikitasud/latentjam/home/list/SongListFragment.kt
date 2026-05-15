/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * SongListFragment.kt is part of LatentJam.
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
import io.github.nikitasud.latentjam.home.HomeViewModel
import io.github.nikitasud.latentjam.list.ListFragment
import io.github.nikitasud.latentjam.list.ListViewModel
import io.github.nikitasud.latentjam.list.SelectableListListener
import io.github.nikitasud.latentjam.list.adapter.SelectionIndicatorAdapter
import io.github.nikitasud.latentjam.list.recycler.FastScrollRecyclerView
import io.github.nikitasud.latentjam.list.recycler.SongViewHolder
import io.github.nikitasud.latentjam.list.sort.Sort
import io.github.nikitasud.latentjam.ml.data.LikedSongRepository
import io.github.nikitasud.latentjam.music.IndexingState
import io.github.nikitasud.latentjam.music.MusicViewModel
import io.github.nikitasud.latentjam.playback.PlaybackViewModel
import io.github.nikitasud.latentjam.playback.formatDurationMsPopup
import io.github.nikitasud.latentjam.util.collectImmediately
import java.util.Calendar
import javax.inject.Inject
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] that shows a list of [Song]s.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class SongListFragment :
    ListFragment<Song, FragmentHomeListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener {
    private val homeModel: HomeViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    @Inject lateinit var likedSongRepository: LikedSongRepository
    private val songAdapter = SongAdapter(this)

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeRecycler.apply {
            id = R.id.home_song_recycler
            adapter = songAdapter
            popupProvider = this@SongListFragment
            listener = this@SongListFragment
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_song_48)
            contentDescription = getString(R.string.lbl_songs)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_songs)

        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.songList, ::updateSongs)
        collectImmediately(homeModel.empty, musicModel.indexingState, ::updateNoMusicIndicator)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
        collectImmediately(likedSongRepository.likedSet) { uids ->
            songAdapter.setLikedUids(uids)
        }
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
        val song = homeModel.songList.value.getOrNull(pos) ?: return null
        // Change how we display the popup depending on the current sort mode.
        // Note: We don't use the more correct individual artist name here, as sorts are largely
        // based off the names of the parent objects and not the child objects.
        return when (homeModel.songSort.mode) {
            // Name -> Use name
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(song.name.thumb() ?: "?")

            // Artist -> Use name of first artist
            is Sort.Mode.ByArtist ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    song.album.artists[0].name.thumb() ?: "?"
                )

            // Album -> Use Album Name
            is Sort.Mode.ByAlbum ->
                FastScrollRecyclerView.PopupProvider.PopupData(song.album.name.thumb() ?: "?")

            // Date -> Use year of the range minimum
            is Sort.Mode.ByDate -> {
                val year = song.album.dates?.min?.year ?: return null
                FastScrollRecyclerView.PopupProvider.PopupData(getString(R.string.fmt_number, year))
            }

            // Duration -> Use compact bucket duration
            is Sort.Mode.ByDuration ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    song.durationMs.formatDurationMsPopup()
                )

            // Last added -> Use year
            is Sort.Mode.ByDateAdded -> {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = song.addedMs
                FastScrollRecyclerView.PopupProvider.PopupData(
                    getString(R.string.fmt_number, calendar.get(Calendar.YEAR))
                )
            }

            // Unsupported sort, error gracefully
            else -> null
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Song) {
        playbackModel.play(item, homeModel.playWith)
    }

    override fun onOpenMenu(item: Song) {
        listModel.openMenu(R.menu.song, item, homeModel.playWith)
    }

    override fun onToggleLike(item: Song) {
        likedSongRepository.toggle(item.uid)
    }

    private fun updateSongs(songs: List<Song>) {
        songAdapter.update(songs, homeModel.songInstructions.consume())
    }

    private fun updateNoMusicIndicator(empty: Boolean, indexingState: IndexingState?) {
        val binding = requireBinding()
        binding.homeRecycler.isInvisible = empty
        binding.homeNoMusic.isInvisible = !empty
        binding.homeNoMusicAction.isVisible =
            indexingState == null || (empty && indexingState is IndexingState.Completed)
    }

    private fun updateSelection(selection: List<Music>) {
        songAdapter.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        // Only indicate playback that is from all songs
        songAdapter.setPlaying(song.takeIf { parent == null }, isPlaying)
    }

    /**
     * A [SelectionIndicatorAdapter] that shows a list of [Song]s using [SongViewHolder].
     *
     * @param listener An [SelectableListListener] to bind interactions to.
     */
    private class SongAdapter(private val listener: SelectableListListener<Song>) :
        SelectionIndicatorAdapter<Song, SongViewHolder>(SongViewHolder.DIFF_CALLBACK) {

        private var likedUids: Set<Music.UID> = emptySet()

        fun setLikedUids(uids: Set<Music.UID>) {
            // notifyDataSetChanged is heavy but cheap on this list size and avoids
            // tracking per-row deltas — the underlying paged list is small enough
            // (typically <2k songs). Refine to per-item only if profiling shows it
            // matters.
            likedUids = uids
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SongViewHolder.from(parent)

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            val song = getItem(position)
            holder.bind(song, listener, liked = song.uid in likedUids)
        }
    }
}
