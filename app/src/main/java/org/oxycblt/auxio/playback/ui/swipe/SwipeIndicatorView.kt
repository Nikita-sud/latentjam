/*
 * Copyright (c) 2025 Auxio Project
 * SwipeIndicatorView.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui.swipe

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.PlayerSwipeIndicatorViewBinding

class SwipeIndicatorView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    val binding = PlayerSwipeIndicatorViewBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    fun setIsNext(isNext: Boolean) {
        if (isNext) {
            // For "next": Text on left, icon on right
            binding.swipeIcon.setImageResource(R.drawable.ic_skip_next_24)
            binding.swipeText.setText(R.string.desc_skip_next)
            // Reorder views: text first, then icon
            removeAllViews()
            addView(binding.swipeText)
            addView(binding.swipeIcon)
        } else {
            // For "previous": Icon on left, text on right
            binding.swipeIcon.setImageResource(R.drawable.ic_skip_prev_24)
            binding.swipeText.setText(R.string.desc_skip_prev)
            // Reorder views: icon first, then text
            removeAllViews()
            addView(binding.swipeIcon)
            addView(binding.swipeText)
        }
    }
}
