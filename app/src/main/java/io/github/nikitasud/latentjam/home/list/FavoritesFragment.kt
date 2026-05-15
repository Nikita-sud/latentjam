/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.home.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import io.github.nikitasud.latentjam.databinding.FragmentFavoritesBinding
import io.github.nikitasud.latentjam.list.SelectableListListener
import io.github.nikitasud.latentjam.list.recycler.SongViewHolder
import io.github.nikitasud.latentjam.ml.data.LikedSongRepository
import io.github.nikitasud.latentjam.music.MusicRepository
import io.github.nikitasud.latentjam.music.MusicViewModel
import io.github.nikitasud.latentjam.playback.PlaybackViewModel
import io.github.nikitasud.latentjam.util.collectImmediately
import javax.inject.Inject
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

/**
 * A song-list view backed by [LikedSongRepository]. Renders every song the user
 * has starred. Tapping a song plays the full favorites list starting there;
 * tapping the star removes the song (which removes the row).
 *
 * Not implemented as a true musikr [org.oxycblt.musikr.Playlist] because the
 * library's Playlist interface is sealed inside musikr. Instead the playlist
 * tab pins a "Favorites" tile that opens this fragment as its own destination.
 */
@AndroidEntryPoint
class FavoritesFragment : Fragment() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()

    @Inject lateinit var likedSongRepository: LikedSongRepository
    @Inject lateinit var musicRepository: MusicRepository

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private val adapter = FavoritesSongAdapter(
        onClick = { tapped, fullList ->
            val idx = fullList.indexOf(tapped).coerceAtLeast(0)
            // Rotate so the tapped song plays first; rest of favorites follow.
            playbackModel.play(fullList.subList(idx, fullList.size) + fullList.subList(0, idx))
        },
        onToggleLike = { likedSongRepository.toggle(it.uid) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.favoritesToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.favoritesRecycler.adapter = adapter

        // Also re-resolve when the library reindexes — statistics emits a new
        // value on every library replace, which is enough of a tick to invalidate
        // our UID→Song lookups.
        collectImmediately(likedSongRepository.likedSet, musicModel.statistics) { uids, _ ->
            updateList(uids)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.favoritesRecycler.adapter = null
        _binding = null
    }

    private fun updateList(likedUids: Set<Music.UID>) {
        // Resolve UIDs to Song instances via the current library snapshot. UIDs
        // that no longer index to a Song (file moved, library rescan removed it)
        // are silently dropped from the visible list — the row stays in
        // LikedSongEntity so it'll reappear if the file comes back.
        val library = musicRepository.library
        val songs: List<Song> =
            if (library == null) emptyList()
            else likedUids.mapNotNull { uid -> library.findSong(uid) }
        adapter.submitList(songs)
        binding.favoritesEmpty.isVisible = songs.isEmpty()
        binding.favoritesRecycler.isVisible = songs.isNotEmpty()
    }

    private class FavoritesSongAdapter(
        private val onClick: (Song, List<Song>) -> Unit,
        private val onToggleLike: (Song) -> Unit,
    ) : ListAdapter<Song, SongViewHolder>(SongViewHolder.DIFF_CALLBACK) {

        private val listener =
            object : SelectableListListener<Song> {
                override fun onClick(item: Song, viewHolder: RecyclerView.ViewHolder) {
                    onClick(item, currentList)
                }

                override fun onOpenMenu(item: Song) = Unit

                override fun onSelect(item: Song) = Unit

                override fun onToggleLike(item: Song) {
                    this@FavoritesSongAdapter.onToggleLike(item)
                }
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SongViewHolder.from(parent)

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(getItem(position), listener, liked = true)
        }
    }
}
