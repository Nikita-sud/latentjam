/*
 * Copyright (c) 2025 Auxio Project
 * SwipeClipView.kt is part of Auxio.
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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class SwipeClipView(context: Context?, attrs: AttributeSet) : View(context, attrs) {

    private var backgroundPaint = Paint()

    private var widthPx = 0
    private var heightPx = 0

    private var shapePath = Path()
    private var arcSize: Float = 80f
    private var isLeft = true

    /** Progress from 0.0 to 1.0 indicating how much of the swipe area to show */
    var swipeProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updatePathShape()
        }

    init {
        requireNotNull(context) { "Context is null." }

        backgroundPaint.apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            color = 0x30000000
        }

        val dm = context.resources.displayMetrics
        widthPx = dm.widthPixels
        heightPx = dm.heightPixels

        updatePathShape()
    }

    fun updateArcSize(baseView: View) {
        val newArcSize = baseView.height / 11.4f
        if (arcSize != newArcSize) {
            arcSize = newArcSize
            updatePathShape()
        }
    }

    fun updatePosition(newIsLeft: Boolean) {
        if (isLeft != newIsLeft) {
            isLeft = newIsLeft
            updatePathShape()
        }
    }

    private fun updatePathShape() {
        // The maximum width the swipe area can take (half of the view)
        val maxSwipeWidth = widthPx * 0.5f
        // The current swipe width based on progress
        val currentSwipeWidth = maxSwipeWidth * swipeProgress

        shapePath.reset()

        if (currentSwipeWidth <= 0) {
            invalidate()
            return
        }

        if (isLeft) {
            // Draw from left edge
            shapePath.moveTo(0f, 0f)
            shapePath.lineTo(currentSwipeWidth - arcSize.coerceAtMost(currentSwipeWidth), 0f)
            shapePath.quadTo(
                currentSwipeWidth + arcSize.coerceAtMost(currentSwipeWidth),
                heightPx.toFloat() / 2,
                currentSwipeWidth - arcSize.coerceAtMost(currentSwipeWidth),
                heightPx.toFloat(),
            )
            shapePath.lineTo(0f, heightPx.toFloat())
        } else {
            // Draw from right edge
            val rightEdge = widthPx.toFloat()
            shapePath.moveTo(rightEdge, 0f)
            shapePath.lineTo(
                rightEdge - currentSwipeWidth + arcSize.coerceAtMost(currentSwipeWidth),
                0f,
            )
            shapePath.quadTo(
                rightEdge - currentSwipeWidth - arcSize.coerceAtMost(currentSwipeWidth),
                heightPx.toFloat() / 2,
                rightEdge - currentSwipeWidth + arcSize.coerceAtMost(currentSwipeWidth),
                heightPx.toFloat(),
            )
            shapePath.lineTo(rightEdge, heightPx.toFloat())
        }

        shapePath.close()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        widthPx = w
        heightPx = h
        updatePathShape()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (swipeProgress > 0) {
            canvas.clipPath(shapePath)
            canvas.drawPath(shapePath, backgroundPaint)
        }
    }
}
