/*
 * Copyright (c) 2026 Auxio Project
 * ButtonGroupToolbar.kt is part of Auxio.
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

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.annotation.MenuRes
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.R as MR
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonGroup
import org.oxycblt.auxio.R

/**
 * A [MaterialToolbar] that replaces the standard
 * [ActionMenuView][androidx.appcompat.widget.ActionMenuView] action items with a
 * [MaterialButtonGroup] containing [RippleFixMaterialButton] instances. This enables M3 Expressive
 * shape morphing and width animation behavior for toolbar action buttons.
 *
 * Since [MaterialToolbar] has no integration with [MaterialButtonGroup], this class intercepts menu
 * inflation and constructs its own button group from the menu resource, hiding the internal
 * [ActionMenuView][androidx.appcompat.widget.ActionMenuView] entirely. This is fragile, but the
 * relevant internal APIs haven't changed in years.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class ButtonGroupToolbar
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = androidx.appcompat.R.attr.toolbarStyle,
) : MaterialToolbar(context, attrs, defStyleAttr) {
    private var menuItemClickListener: OnMenuItemClickListener? = null
    private var overflowClickListener: ((View) -> Unit)? = null
    private var buttonGroup: MaterialButtonGroup? = null
    @SuppressLint("RestrictedApi") private var menuBuilder: MenuBuilder? = null
    private var isInitialized = false

    // Intercept inflateMenu calls from the super constructor to prevent ActionMenuView creation.
    // During the super constructor, isInitialized is false so this is a no-op. After
    // construction, this delegates to buildButtonGroup.
    override fun inflateMenu(@MenuRes resId: Int) {
        if (isInitialized) {
            buildButtonGroup(resId)
        }
    }

    init {
        // Read the menu attribute ourselves since we suppressed inflateMenu during the super
        // constructor. This uses the same Toolbar_menu styleable that Toolbar reads internally.
        val a =
            context.obtainStyledAttributes(
                attrs,
                androidx.appcompat.R.styleable.Toolbar,
                defStyleAttr,
                0,
            )
        val menuResId = a.getResourceId(androidx.appcompat.R.styleable.Toolbar_menu, 0)
        a.recycle()

        isInitialized = true
        if (menuResId != 0) {
            buildButtonGroup(menuResId)
        }
    }

    @SuppressLint("RestrictedApi")
    override fun getMenu(): Menu {
        return menuBuilder ?: super.getMenu()
    }

    override fun setOnMenuItemClickListener(listener: OnMenuItemClickListener?) {
        menuItemClickListener = listener
    }

    /**
     * Override the overflow button's click behavior. When set, the overflow button will call
     * [block] instead of showing the default popup menu.
     */
    fun overrideOnOverflowMenuClick(block: (View) -> Unit) {
        overflowClickListener = block
    }

    @SuppressLint("RestrictedApi")
    private fun buildButtonGroup(@MenuRes resId: Int) {
        // Inflate the menu into a MenuBuilder, re-using the same pattern as MenuDialogFragment.
        // Since we don't have (and don't want) a dummy view to inflate this menu, just
        // depend on the AndroidX Toolbar internal API and hope for the best.
        val builder = MenuBuilder(context)
        SupportMenuInflater(context).inflate(resId, builder)
        menuBuilder = builder

        // Separate action items from overflow items based on showAsAction flags.
        val actionItems = mutableListOf<MenuItemImpl>()
        val overflowItems = mutableListOf<MenuItemImpl>()
        for (i in 0 until builder.size()) {
            val item = builder.getItem(i) as MenuItemImpl
            if (!item.isVisible) continue
            if (item.requiresActionButton() || item.requestsActionButton()) {
                actionItems.add(item)
            } else {
                overflowItems.add(item)
            }
        }

        // Remove old button group if one exists.
        buttonGroup?.let { removeView(it) }

        // Create new button group using the theme-configured style.
        val group =
            MaterialButtonGroup(context, null, MR.attr.materialButtonGroupStyle).apply {
                layoutParams =
                    LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                        Gravity.END or Gravity.CENTER_VERTICAL,
                    )
                spacing = 0
            }

        // Create a MaterialButton for each action item.
        for (item in actionItems) {
            val button =
                RippleFixMaterialButton(context, null, MR.attr.materialIconButtonStyle).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    id = item.itemId
                    icon = item.icon
                    contentDescription = item.title
                    setOnClickListener { menuItemClickListener?.onMenuItemClick(item) }
                }
            group.addView(button)
        }

        // Add an overflow button if there are overflow items.
        if (overflowItems.isNotEmpty()) {
            val overflowButton =
                RippleFixMaterialButton(context, null, MR.attr.materialIconButtonStyle).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    setIconResource(R.drawable.ic_more_vert_24)
                    contentDescription = context.getString(R.string.lbl_more)
                    setOnClickListener { view ->
                        val customListener = overflowClickListener
                        if (customListener != null) {
                            customListener(view)
                        } else {
                            showOverflowPopup(view, overflowItems)
                        }
                    }
                }
            group.addView(overflowButton)
        }

        addView(group)
        buttonGroup = group
    }

    private fun showOverflowPopup(anchor: View, items: List<MenuItem>) {
        val popup = PopupMenu(context, anchor)
        for (item in items) {
            popup.menu.add(item.groupId, item.itemId, item.order, item.title).apply {
                icon = item.icon
                isEnabled = item.isEnabled
            }
        }
        popup.setOnMenuItemClickListener { clickedItem ->
            menuItemClickListener?.onMenuItemClick(clickedItem) ?: false
        }
        popup.show()
    }
}
