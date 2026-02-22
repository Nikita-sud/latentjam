/*
 * Copyright (c) 2026 Auxio Project
 * WidthFixMaterialButton.kt is part of Auxio.
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
 
package org.oxycblt.auxio.ui

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import com.google.android.material.R

/**
 * A [RippleFixMaterialButton] that works around a bug where changing the icon while the button's
 * ripple/press animation is active causes a width change that "sticks". The workaround defers
 * re-applying the icon until the button is no longer pressed.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class WidthFixMaterialButton
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = R.attr.materialButtonStyle,
) : RippleFixMaterialButton(context, attrs, defStyleAttr) {
    private var pendingIconRes: Int? = null
    private var appliedIconRes: Int? = null

    private val applyPendingIconRunnable =
        object : Runnable {
            override fun run() {
                val iconRes = pendingIconRes ?: return
                if (isPressed) {
                    postOnAnimation(this)
                    return
                }
                if (appliedIconRes != iconRes) {
                    super@WidthFixMaterialButton.setIconResource(iconRes)
                    appliedIconRes = iconRes
                }
                pendingIconRes = null
            }
        }

    override fun setIconResource(@DrawableRes iconRes: Int) {
        super.setIconResource(iconRes)
        pendingIconRes = iconRes
        removeCallbacks(applyPendingIconRunnable)
        postOnAnimation(applyPendingIconRunnable)
    }

    fun clearPendingIcon() {
        removeCallbacks(applyPendingIconRunnable)
        pendingIconRes = null
        appliedIconRes = null
    }
}
