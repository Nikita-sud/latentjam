package org.oxycblt.auxio.ui

import android.view.View
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import kotlin.math.abs

class FadingToolbarOffsetListener(
    private val toolbar: View,
    private val content: View
) : AppBarLayout.OnOffsetChangedListener {
    override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
        val range = appBarLayout.totalScrollRange
        // Fade out the toolbar as the AppBarLayout collapses. To prevent status bar overlap,
        // the alpha transition is shifted such that the Toolbar becomes fully transparent
        // when the AppBarLayout is only at half-collapsed.
        toolbar.alpha = 1f - (abs(verticalOffset.toFloat()) / (range.toFloat() / 2))
        content.updatePadding(bottom = range + verticalOffset)
    }
}