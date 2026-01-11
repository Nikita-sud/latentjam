/*
 * Copyright (c) 2025 Auxio Project
 * PlayerSwipeIndicatorOverlay.kt is part of Auxio.
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
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.isRtl

@AndroidEntryPoint
class PlayerSwipeIndicatorOverlay(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs) {

    @Inject lateinit var uiSettings: UISettings

    private var leftSwipeIndicator: SwipeIndicatorView
    private var rightSwipeIndicator: SwipeIndicatorView
    private var leftSwipeClipView: SwipeClipView
    private var rightSwipeClipView: SwipeClipView
    private var rootConstraintLayout: ConstraintLayout

    private val shapeAppearance: ShapeAppearanceModel

    /** The width used for calculating full slide translation */
    private var indicatorSlideDistance = 100f

    /** Current swipe direction, null if no swipe in progress */
    private var currentSwipeDirection: SwipeDirection? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.player_swipe_indicator_overlay, this, true)

        // Set up shape appearance based on UISettings similar to CoverView
        val styledAttrs =
            context.obtainStyledAttributes(attrs, R.styleable.PlayerSwipeIndicatorOverlay)
        val shapeAppearanceRes =
            styledAttrs.getResourceId(R.styleable.PlayerSwipeIndicatorOverlay_shapeAppearance, 0)

        shapeAppearance =
            if (uiSettings.roundMode) {
                if (shapeAppearanceRes != 0) {
                    ShapeAppearanceModel.builder(context, shapeAppearanceRes, -1).build()
                } else {
                    ShapeAppearanceModel.builder(
                            context,
                            com.google.android.material.R.style
                                .ShapeAppearance_Material3_Corner_Medium,
                            -1,
                        )
                        .build()
                }
            } else {
                ShapeAppearanceModel.builder().build()
            }

        styledAttrs.recycle()

        leftSwipeIndicator = findViewById(R.id.left_swipe_indicator)
        rightSwipeIndicator = findViewById(R.id.right_swipe_indicator)
        leftSwipeClipView = findViewById(R.id.left_swipe_clip_view)
        rightSwipeClipView = findViewById(R.id.right_swipe_clip_view)
        rootConstraintLayout = findViewById(R.id.root_constraint_layout)

        // Apply shape clipping to the overlay
        clipToOutline = true
        background =
            MaterialShapeDrawable().apply {
                shapeAppearanceModel = shapeAppearance
                fillColor =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            }

        // Hide overlay elements by default
        leftSwipeIndicator.alpha = 0f
        rightSwipeIndicator.alpha = 0f
        leftSwipeClipView.alpha = 0f
        rightSwipeClipView.alpha = 0f
        leftSwipeIndicator.visibility = INVISIBLE
        rightSwipeIndicator.visibility = INVISIBLE
        leftSwipeClipView.visibility = INVISIBLE
        rightSwipeClipView.visibility = INVISIBLE

        addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            leftSwipeClipView.updateArcSize(view)
            rightSwipeClipView.updateArcSize(view)
            // Set slide distance based on indicator width plus some padding
            indicatorSlideDistance = view.width * 0.3f
        }

        // Initialize directions for each side
        // In LTR: left side = previous, right side = next
        // In RTL: left side = next, right side = previous
        leftSwipeClipView.updatePosition(true)
        rightSwipeClipView.updatePosition(false)
        updateIndicatorDirections()
    }

    private fun updateIndicatorDirections() {
        val isRtl = isRtl
        // Left indicator: previous in LTR, next in RTL
        leftSwipeIndicator.setIsNext(isRtl)
        // Right indicator: next in LTR, previous in RTL
        rightSwipeIndicator.setIsNext(!isRtl)
    }

    /**
     * Called when a swipe gesture starts.
     *
     * @param direction The direction of the swipe (LEFT for swiping left, RIGHT for swiping right)
     */
    fun onSwipeStarted(direction: SwipeDirection) {
        // Cancel any pending animations on ALL indicators (in case previous swipe
        // was in a different direction and its fade-out animation is still running)
        cancelAllAnimations()

        currentSwipeDirection = direction

        val indicatorView = getIndicatorForDirection(direction)
        val clipView = getClipViewForDirection(direction)

        indicatorView.visibility = VISIBLE
        clipView.visibility = VISIBLE
    }

    private fun cancelAllAnimations() {
        // Cancel animations and reset state for both indicators
        leftSwipeIndicator.animate().cancel()
        rightSwipeIndicator.animate().cancel()
        leftSwipeClipView.animate().cancel()
        rightSwipeClipView.animate().cancel()

        // Reset the non-active indicator to hidden state
        leftSwipeIndicator.alpha = 0f
        leftSwipeIndicator.translationX = 0f
        leftSwipeIndicator.visibility = INVISIBLE
        leftSwipeClipView.alpha = 0f
        leftSwipeClipView.swipeProgress = 0f
        leftSwipeClipView.visibility = INVISIBLE

        rightSwipeIndicator.alpha = 0f
        rightSwipeIndicator.translationX = 0f
        rightSwipeIndicator.visibility = INVISIBLE
        rightSwipeClipView.alpha = 0f
        rightSwipeClipView.swipeProgress = 0f
        rightSwipeClipView.visibility = INVISIBLE
    }

    /**
     * Called as the user swipes to update the visual progress.
     *
     * @param progress Normalized progress of the swipe (0 to 1+, where 1 is the commit threshold)
     * @param direction The direction of the swipe
     */
    fun onSwipeProgress(progress: Float, direction: SwipeDirection) {
        val indicatorView = getIndicatorForDirection(direction)
        val clipView = getClipViewForDirection(direction)

        // Clamp progress for visual calculations (allow slight overshoot)
        val visualProgress = progress.coerceIn(0f, 1.2f)

        // Alpha: fade in from 0 to 1 as progress goes from 0 to 1
        val alpha = (visualProgress).coerceIn(0f, 1f)
        indicatorView.alpha = alpha
        clipView.alpha = alpha

        // Translation: slide in from the edge
        // At progress 0: fully translated off screen
        // At progress 1: at final position (translation = 0)
        val translationFactor = 1f - visualProgress.coerceIn(0f, 1f)
        val translation =
            when (direction) {
                SwipeDirection.LEFT -> indicatorSlideDistance * translationFactor
                SwipeDirection.RIGHT -> -indicatorSlideDistance * translationFactor
            }
        indicatorView.translationX = translation

        // Update clip view progress
        clipView.swipeProgress = visualProgress.coerceIn(0f, 1f)
    }

    /**
     * Called when the swipe gesture is released.
     *
     * @param committed True if the swipe passed the commit threshold and the action should execute
     * @param direction The direction of the swipe
     */
    fun onSwipeReleased(committed: Boolean, direction: SwipeDirection) {
        val indicatorView = getIndicatorForDirection(direction)
        val clipView = getClipViewForDirection(direction)

        if (committed) {
            // Committed: fade out immediately without sliding
            indicatorView
                .animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { indicatorView.visibility = INVISIBLE }
                .start()
            clipView
                .animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    clipView.visibility = INVISIBLE
                    clipView.swipeProgress = 0f
                }
                .start()
        } else {
            // Not committed: slide back out and fade out
            val slideOutTranslation =
                when (direction) {
                    SwipeDirection.LEFT -> indicatorSlideDistance
                    SwipeDirection.RIGHT -> -indicatorSlideDistance
                }
            indicatorView
                .animate()
                .alpha(0f)
                .translationX(slideOutTranslation)
                .setDuration(200)
                .withEndAction {
                    indicatorView.visibility = INVISIBLE
                    indicatorView.translationX = 0f
                }
                .start()
            // Animate clip view progress back to 0
            val startProgress = clipView.swipeProgress
            clipView
                .animate()
                .alpha(0f)
                .setDuration(200)
                .setUpdateListener { animator ->
                    val fraction = animator.animatedFraction
                    clipView.swipeProgress = startProgress * (1f - fraction)
                }
                .withEndAction {
                    clipView.visibility = INVISIBLE
                    clipView.swipeProgress = 0f
                }
                .start()
        }

        currentSwipeDirection = null
    }

    /** Cancels any in-progress swipe indicator without animation. */
    fun cancelSwipe() {
        cancelAllAnimations()
        currentSwipeDirection = null
    }

    private fun getIndicatorForDirection(direction: SwipeDirection): View {
        // When swiping left, we show the right indicator (next)
        // When swiping right, we show the left indicator (previous)
        return when (direction) {
            SwipeDirection.LEFT -> rightSwipeIndicator
            SwipeDirection.RIGHT -> leftSwipeIndicator
        }
    }

    private fun getClipViewForDirection(direction: SwipeDirection): SwipeClipView {
        return when (direction) {
            SwipeDirection.LEFT -> rightSwipeClipView
            SwipeDirection.RIGHT -> leftSwipeClipView
        }
    }

    enum class SwipeDirection {
        LEFT,
        RIGHT,
    }
}
