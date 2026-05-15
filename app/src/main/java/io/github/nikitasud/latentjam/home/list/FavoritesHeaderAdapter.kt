/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.home.list

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.nikitasud.latentjam.R
import io.github.nikitasud.latentjam.databinding.ItemFavoritesPlaylistBinding
import io.github.nikitasud.latentjam.util.getPlural
import io.github.nikitasud.latentjam.util.inflater

/**
 * A single-item [RecyclerView.Adapter] that renders the pinned "Favorites" tile at
 * the top of the playlist list. Backed by [io.github.nikitasud.latentjam.ml.data.LikedSongRepository] —
 * the host fragment feeds the current liked-song count via [setSongCount] and
 * handles the click via [onClick].
 *
 * Always renders exactly one row, so this adapter is composed with the real playlist
 * adapter via [androidx.recyclerview.widget.ConcatAdapter].
 */
class FavoritesHeaderAdapter(private val onClick: () -> Unit) :
    RecyclerView.Adapter<FavoritesHeaderAdapter.ViewHolder>() {

    private var songCount: Int = 0

    @SuppressLint("NotifyDataSetChanged")
    fun setSongCount(count: Int) {
        if (count == songCount) return
        songCount = count
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemFavoritesPlaylistBinding.inflate(parent.context.inflater, parent, false)
        return ViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(songCount)
    }

    class ViewHolder(
        private val binding: ItemFavoritesPlaylistBinding,
        onClick: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener { onClick() }
        }

        fun bind(count: Int) {
            binding.favoritesInfo.text =
                binding.root.context.getPlural(R.plurals.fmt_song_count, count)
        }
    }
}
