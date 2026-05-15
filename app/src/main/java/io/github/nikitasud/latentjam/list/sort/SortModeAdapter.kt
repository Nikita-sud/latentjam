/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * SortModeAdapter.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.list.sort

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.github.nikitasud.latentjam.databinding.ItemSortModeBinding
import io.github.nikitasud.latentjam.list.ClickableListListener
import io.github.nikitasud.latentjam.list.adapter.FlexibleListAdapter
import io.github.nikitasud.latentjam.list.recycler.DialogRecyclerView
import io.github.nikitasud.latentjam.util.context
import io.github.nikitasud.latentjam.util.inflater

/**
 * A [FlexibleListAdapter] that displays a list of [Sort.Mode]s.
 *
 * @param listener A [ClickableListListener] to bind interactions to.
 * @author Alexander Capehart (OxygenCobalt)
 */
class SortModeAdapter(private val listener: ClickableListListener<Sort.Mode>) :
    FlexibleListAdapter<Sort.Mode, SortModeViewHolder>(SortModeViewHolder.DIFF_CALLBACK) {
    /** The currently selected [Sort.Mode] item in this adapter. */
    var currentMode: Sort.Mode? = null
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        SortModeViewHolder.from(parent)

    override fun onBindViewHolder(holder: SortModeViewHolder, position: Int) {
        throw NotImplementedError()
    }

    override fun onBindViewHolder(holder: SortModeViewHolder, position: Int, payload: List<Any>) {
        val mode = getItem(position)
        if (payload.isEmpty()) {
            holder.bind(mode, listener)
        }
        holder.setSelected(mode == currentMode)
    }

    /**
     * Select a new [Sort.Mode] option, unselecting the prior one. Does nothing if [mode] equals
     * [currentMode].
     *
     * @param mode The new [Sort.Mode] to select. Should be in the adapter data.
     */
    fun setSelected(mode: Sort.Mode) {
        if (mode == currentMode) return
        val oldMode = currentList.indexOf(currentMode)
        val newMode = currentList.indexOf(mode)
        currentMode = mode
        if (oldMode > -1) {
            notifyItemChanged(oldMode, PAYLOAD_SELECTION_CHANGED)
        }
        notifyItemChanged(newMode, PAYLOAD_SELECTION_CHANGED)
    }

    private companion object {
        val PAYLOAD_SELECTION_CHANGED = Any()
    }
}

/**
 * A [DialogRecyclerView.ViewHolder] that displays a [Sort.Mode].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class SortModeViewHolder private constructor(private val binding: ItemSortModeBinding) :
    DialogRecyclerView.ViewHolder(binding.root) {
    /**
     * Bind new data to this instance.
     *
     * @param mode The new [Sort.Mode] to bind.
     * @param listener A [ClickableListListener] to bind interactions to.
     */
    fun bind(mode: Sort.Mode, listener: ClickableListListener<Sort.Mode>) {
        listener.bind(mode, this)
        binding.sortRadio.text = binding.context.getString(mode.stringRes)
    }

    /**
     * Set if this view should be shown as selected or not.
     *
     * @param selected True if selected, false if not.
     */
    fun setSelected(selected: Boolean) {
        binding.sortRadio.isChecked = selected
    }

    companion object {
        fun from(parent: View) =
            SortModeViewHolder(ItemSortModeBinding.inflate(parent.context.inflater))

        val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<Sort.Mode>() {
                override fun areItemsTheSame(oldItem: Sort.Mode, newItem: Sort.Mode) =
                    oldItem == newItem

                override fun areContentsTheSame(oldItem: Sort.Mode, newItem: Sort.Mode) =
                    oldItem == newItem
            }
    }
}
