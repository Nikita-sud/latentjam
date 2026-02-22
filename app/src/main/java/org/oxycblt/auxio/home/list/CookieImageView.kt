package org.oxycblt.auxio.home.list

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import org.oxycblt.auxio.home.list.CookieShapeDrawable

/** A placeholder [androidx.appcompat.widget.AppCompatImageView] with a 6-sided cookie expressive background shape. */
class CookieImageView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    AppCompatImageView(context, attrs, defStyleAttr) {
    init {
        background = CookieShapeDrawable(context)
        scaleType = ScaleType.CENTER_INSIDE
    }
}