/*
 * Copyright (c) 2024 Auxio Project
 * Animations.kt is part of Auxio.
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

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.view.isInvisible
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.R as MR
import com.google.android.material.motion.MotionUtils

class AnimConfig(
    context: Context,
    @AttrRes interpolatorRes: Int,
    @AttrRes durationRes: Int,
    defaultDuration: Int,
) {
    val interpolator: TimeInterpolator =
        MotionUtils.resolveThemeInterpolator(context, interpolatorRes, FastOutSlowInInterpolator())
    val duration: Long =
        MotionUtils.resolveThemeDuration(context, durationRes, defaultDuration).toLong()

    companion object {
        val STANDARD = MR.attr.motionEasingStandardInterpolator
        val MEDIUM2 = MR.attr.motionDurationMedium2 to 300

        fun of(context: Context, @AttrRes interpolator: Int, duration: Pair<Int, Int>) =
            AnimConfig(context, interpolator, duration.first, duration.second)
    }

    inline fun genericFloat(
        from: Float,
        to: Float,
        delayMs: Long = 0,
        crossinline update: (Float) -> Unit,
    ): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            startDelay = delayMs
            duration = this@AnimConfig.duration
            interpolator = this@AnimConfig.interpolator
            addUpdateListener { update(animatedValue as Float) }
        }
}

interface MotionHandle {
    fun start()

    fun cancel()
}

private object NoOpMotionHandle : MotionHandle {
    override fun start() = Unit

    override fun cancel() = Unit
}

private class SnapMotionHandle(private val action: () -> Unit) : MotionHandle {
    private var canceled = false

    override fun start() {
        if (!canceled) {
            action()
        }
    }

    override fun cancel() {
        canceled = true
    }
}

private class CompositeMotionHandle(
    private val handles: List<MotionHandle>,
    private val onStart: (() -> Unit)? = null,
) : MotionHandle {
    override fun start() {
        onStart?.invoke()
        for (handle in handles) {
            handle.start()
        }
    }

    override fun cancel() {
        for (handle in handles) {
            handle.cancel()
        }
    }
}

private data class SpringToken(@AttrRes val attr: Int, @StyleRes val defaultStyle: Int)

private object MotionSpringTokens {
    val FAST_EFFECTS =
        SpringToken(
            MR.attr.motionSpringFastEffects,
            MR.style.Motion_Material3_Spring_Standard_Fast_Effects,
        )
//    val DEFAULT_EFFECTS =
//        SpringToken(
//            MR.attr.motionSpringDefaultEffects,
//            MR.style.Motion_Material3_Spring_Standard_Default_Effects,
//        )
//    val SLOW_EFFECTS =
//        SpringToken(
//            MR.attr.motionSpringSlowEffects,
//            MR.style.Motion_Material3_Spring_Standard_Slow_Effects,
//        )
    val FAST_SPATIAL =
        SpringToken(
            MR.attr.motionSpringFastSpatial,
            MR.style.Motion_Material3_Spring_Standard_Fast_Spatial,
        )
    val DEFAULT_SPATIAL =
        SpringToken(
            MR.attr.motionSpringDefaultSpatial,
            MR.style.Motion_Material3_Spring_Standard_Default_Spatial,
        )
//    val SLOW_SPATIAL =
//        SpringToken(
//            MR.attr.motionSpringSlowSpatial,
//            MR.style.Motion_Material3_Spring_Standard_Slow_Spatial,
//        )
}

private class MotionSpring(
    context: Context,
    token: SpringToken,
    private val dampingRatioOverride: Float? = null,
) {
    private val springTemplate =
        MotionUtils.resolveThemeSpringForce(context, token.attr, token.defaultStyle)

    fun create(
        startValue: Float,
        finalValue: Float,
        minimumVisibleChange: Float,
        update: (Float) -> Unit,
        onEnd: ((canceled: Boolean) -> Unit)? = null,
    ): MotionHandle {
        val animation =
            SpringAnimation(FloatValueHolder(startValue)).apply {
                spring =
                    SpringForce().apply {
                        dampingRatio = dampingRatioOverride ?: springTemplate.dampingRatio
                        stiffness = springTemplate.stiffness
                        finalPosition = finalValue
                    }
                setStartValue(startValue)
                setMinimumVisibleChange(minimumVisibleChange)
                addUpdateListener { _, value, _ -> update(value) }
                addEndListener { _, canceled, value, _ ->
                    update(if (canceled) value else finalValue)
                    onEnd?.invoke(canceled)
                }
            }
        return object : MotionHandle {
            override fun start() {
                animation.animateToFinalPosition(finalValue)
            }

            override fun cancel() {
                animation.cancel()
            }
        }
    }
}

private fun View.resolveSlideTarget(x: Int?): Float {
    if (x != null) {
        return x.toFloat()
    }

    val resolvedWidth = width.takeIf { it > 0 } ?: measuredWidth.takeIf { it > 0 }
    return (resolvedWidth ?: OFFSCREEN_TRANSLATION_PX.toInt()).toFloat()
}

class MaterialFader
private constructor(
    context: Context,
    private val scale: Float,
    alphaOutToken: SpringToken,
    scaleOutToken: SpringToken,
    alphaInToken: SpringToken,
    scaleInToken: SpringToken,
    scaleOutDampingRatioOverride: Float? = null,
    scaleInDampingRatioOverride: Float? = null,
) {
    private val alphaOutSpring = MotionSpring(context, alphaOutToken)
    private val scaleOutSpring =
        MotionSpring(
            context,
            scaleOutToken,
            dampingRatioOverride = scaleOutDampingRatioOverride,
        )
    private val alphaInSpring = MotionSpring(context, alphaInToken)
    private val scaleInSpring =
        MotionSpring(
            context,
            scaleInToken,
            dampingRatioOverride = scaleInDampingRatioOverride,
        )

    fun jumpToFadeOut(view: View) {
        view.apply {
            alpha = 0f
            scaleX = scale
            scaleY = scale
            isInvisible = true
        }
    }

    private fun jumpToFadeIn(view: View) {
        view.apply {
            alpha = 1f
            scaleX = 1.0f
            scaleY = 1.0f
            isInvisible = false
        }
    }

    fun fadeOut(view: View, onEnd: (() -> Unit)? = null): MotionHandle {
        if (!view.isLaidOut) {
            return SnapMotionHandle {
                jumpToFadeOut(view)
                onEnd?.invoke()
            }
        }

        val alphaMotion =
            alphaOutSpring.create(
                view.alpha,
                0f,
                ALPHA_MIN_VISIBLE_CHANGE,
                update = { view.alpha = it },
                onEnd = { canceled ->
                    view.isInvisible = !canceled
                    if (!canceled) {
                        onEnd?.invoke()
                    }
                },
            )
        val scaleXMotion =
            scaleOutSpring.create(
                view.scaleX,
                scale,
                SCALE_MIN_VISIBLE_CHANGE,
                update = { view.scaleX = it },
            )
        val scaleYMotion =
            scaleOutSpring.create(
                view.scaleY,
                scale,
                SCALE_MIN_VISIBLE_CHANGE,
                update = { view.scaleY = it },
            )
        return CompositeMotionHandle(
            listOf(alphaMotion, scaleXMotion, scaleYMotion),
            onStart = { view.isInvisible = false },
        )
    }

    fun fadeIn(view: View, onEnd: (() -> Unit)? = null): MotionHandle {
        if (!view.isLaidOut) {
            return SnapMotionHandle {
                jumpToFadeIn(view)
                onEnd?.invoke()
            }
        }
        val alphaMotion =
            alphaInSpring.create(
                view.alpha,
                1f,
                ALPHA_MIN_VISIBLE_CHANGE,
                update = { view.alpha = it },
                onEnd = { canceled ->
                    if (!canceled) {
                        onEnd?.invoke()
                    }
                },
            )
        val scaleXMotion =
            scaleInSpring.create(
                view.scaleX,
                1.0f,
                SCALE_MIN_VISIBLE_CHANGE,
                update = { view.scaleX = it },
            )
        val scaleYMotion =
            scaleInSpring.create(
                view.scaleY,
                1.0f,
                SCALE_MIN_VISIBLE_CHANGE,
                update = { view.scaleY = it },
            )
        return CompositeMotionHandle(
            listOf(alphaMotion, scaleXMotion, scaleYMotion),
            onStart = { view.isInvisible = false },
        )
    }

    companion object {
        fun new(context: Context) =
            MaterialFader(
                context,
                0.9f,
                MotionSpringTokens.FAST_EFFECTS,
                MotionSpringTokens.FAST_SPATIAL,
                MotionSpringTokens.FAST_EFFECTS,
                MotionSpringTokens.FAST_SPATIAL,
                // Material's standard spatial springs are only slightly underdamped (0.9),
                // which is too restrained for the toolbar swap to read as springy.
                scaleOutDampingRatioOverride = 0.6f,
                scaleInDampingRatioOverride = 0.6f,
            )
    }
}

class MaterialFlipper(context: Context) {
    private val fader = MaterialFader.new(context)

    fun jump(from: View) {
        fader.jumpToFadeOut(from)
    }

    fun flip(from: View, to: View): MotionHandle {
        var canceled = false
        var inMotion: MotionHandle = NoOpMotionHandle
        val outMotion =
            fader.fadeOut(from) {
                if (!canceled) {
                    inMotion = fader.fadeIn(to)
                    inMotion.start()
                }
            }
        return object : MotionHandle {
            override fun start() {
                canceled = false
                outMotion.start()
            }

            override fun cancel() {
                canceled = true
                outMotion.cancel()
                inMotion.cancel()
            }
        }
    }
}

class MaterialSlider
private constructor(
    context: Context,
    private val x: Int?,
    inSpringToken: SpringToken,
    outSpringToken: SpringToken,
) {
    private val outSpring = MotionSpring(context, outSpringToken)
    private val inSpring = MotionSpring(context, inSpringToken)

    fun jumpOut(view: View) {
        view.translationX = view.resolveSlideTarget(x)
    }

    fun slideOut(view: View): MotionHandle {
        val target = view.resolveSlideTarget(x)
        if (!view.isLaidOut) {
            return SnapMotionHandle { view.translationX = target }
        }
        if (view.translationX > target) {
            view.translationX = target
        }
        return outSpring.create(
            view.translationX,
            target,
            SPATIAL_MIN_VISIBLE_CHANGE,
            update = { view.translationX = it },
        )
    }

    fun slideIn(view: View): MotionHandle {
        if (!view.isLaidOut) {
            return SnapMotionHandle { view.translationX = 0f }
        }
        return inSpring.create(
            view.translationX,
            0f,
            SPATIAL_MIN_VISIBLE_CHANGE,
            update = { view.translationX = it },
        )
    }

    companion object {
        fun small(context: Context, x: Int?) =
            MaterialSlider(
                context,
                x,
                MotionSpringTokens.FAST_SPATIAL,
                MotionSpringTokens.DEFAULT_SPATIAL,
            )
    }
}

private const val ALPHA_MIN_VISIBLE_CHANGE = 1f / 512f
private const val SCALE_MIN_VISIBLE_CHANGE = 0.0001f
private const val SPATIAL_MIN_VISIBLE_CHANGE = 0.01f
private const val OFFSCREEN_TRANSLATION_PX = 100000f
