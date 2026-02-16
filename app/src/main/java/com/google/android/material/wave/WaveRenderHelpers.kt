/*
 * Copyright (c) 2026 Auxio Project
 * WaveRenderHelpers.kt is part of Auxio.
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
 
package com.google.android.material.wave

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.R as MR
import com.google.android.material.motion.MotionUtils
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

internal data class PathPoint(
    val posVec: FloatArray = floatArrayOf(0f, 0f),
    val tanVec: FloatArray = floatArrayOf(1f, 0f),
) {
    fun reset() {
        posVec[0] = 0f
        posVec[1] = 0f
        tanVec[0] = 1f
        tanVec[1] = 0f
    }

    fun translate(dx: Float, dy: Float) {
        posVec[0] += dx
        posVec[1] += dy
    }

    fun scale(sx: Float, sy: Float, pivotY: Float = 0f) {
        posVec[0] *= sx
        posVec[1] = (posVec[1] - pivotY) * sy + pivotY
        tanVec[0] *= sx
        tanVec[1] *= sy
    }
}

internal class WavePathCache {
    val displayedWavePath = Path()
    val pathMeasure = PathMeasure()
    var adjustedWavelength = 0f
        private set

    private val cachedWavePath = Path()
    private val transform = Matrix()
    private var cachedWavelength = -1
    private var cachedTrackLength = -1f

    fun markDirty() {
        cachedWavelength = -1
        cachedTrackLength = -1f
        adjustedWavelength = 0f
    }

    fun ensureCachedWavePath(trackLength: Float, wavelength: Int) {
        if (
            cachedTrackLength == trackLength &&
                cachedWavelength == wavelength &&
                adjustedWavelength > 0f
        ) {
            return
        }
        cachedTrackLength = trackLength
        cachedWavelength = wavelength
        invalidateCachedWavePath(trackLength, wavelength)
    }

    fun invalidateCachedWavePath(trackLength: Float, wavelength: Int) {
        cachedWavePath.rewind()
        adjustedWavelength = 0f
        if (trackLength <= 0f || wavelength <= 0) {
            pathMeasure.setPath(cachedWavePath, false)
            return
        }

        val cycleCount = max(1, (trackLength / wavelength).toInt())
        adjustedWavelength = trackLength / cycleCount
        for (i in 0..cycleCount) {
            val cycle = i.toFloat()
            cachedWavePath.cubicTo(
                2 * cycle + WAVE_SMOOTHNESS,
                0f,
                2 * cycle + 1 - WAVE_SMOOTHNESS,
                1f,
                2 * cycle + 1,
                1f,
            )
            cachedWavePath.cubicTo(
                2 * cycle + 1 + WAVE_SMOOTHNESS,
                1f,
                2 * cycle + 2 - WAVE_SMOOTHNESS,
                0f,
                2 * cycle + 2,
                0f,
            )
        }

        transform.reset()
        transform.setScale(adjustedWavelength / 2f, -2f)
        transform.postTranslate(0f, 1f)
        cachedWavePath.transform(transform)
        pathMeasure.setPath(cachedWavePath, false)
    }

    fun calculateDisplayedWavePath(
        trackLength: Float,
        start: Float,
        end: Float,
        amplitudeFraction: Float,
        phaseFraction: Float,
        displayedAmplitude: Float,
        startPoint: PathPoint,
        endPoint: PathPoint,
        baseTranslationX: Float,
        baseTranslationY: Float,
        scaleAroundPivotY: Boolean,
        clampSegmentFractions: Boolean,
        epsilon: Float,
    ): Boolean {
        displayedWavePath.rewind()
        if (pathMeasure.length <= epsilon) {
            return false
        }

        var adjustedStart = start
        var adjustedEnd = end
        var resultTranslationX = baseTranslationX

        if (adjustedWavelength > epsilon) {
            val cycleCount = trackLength / adjustedWavelength
            if (cycleCount > epsilon) {
                val phaseFractionInPath = phaseFraction / cycleCount
                val ratio = cycleCount / (cycleCount + 1f)
                adjustedStart = (adjustedStart + phaseFractionInPath) * ratio
                adjustedEnd = (adjustedEnd + phaseFractionInPath) * ratio
            }
            resultTranslationX -= phaseFraction * adjustedWavelength
        }

        if (clampSegmentFractions) {
            adjustedStart = adjustedStart.coerceIn(0f, 1f)
            adjustedEnd = adjustedEnd.coerceIn(0f, 1f)
            if (adjustedEnd <= adjustedStart) {
                return false
            }
        }

        val startDistance = adjustedStart * pathMeasure.length
        val endDistance = adjustedEnd * pathMeasure.length
        pathMeasure.getSegment(startDistance, endDistance, displayedWavePath, true)

        startPoint.reset()
        pathMeasure.getPosTan(startDistance, startPoint.posVec, startPoint.tanVec)
        endPoint.reset()
        pathMeasure.getPosTan(endDistance, endPoint.posVec, endPoint.tanVec)

        transform.reset()
        transform.setTranslate(resultTranslationX, baseTranslationY)
        startPoint.translate(resultTranslationX, baseTranslationY)
        endPoint.translate(resultTranslationX, baseTranslationY)

        val scaleY = displayedAmplitude * amplitudeFraction
        if (scaleY > 0f) {
            if (scaleAroundPivotY) {
                transform.postScale(1f, scaleY, resultTranslationX, baseTranslationY)
                startPoint.scale(1f, scaleY, baseTranslationY)
                endPoint.scale(1f, scaleY, baseTranslationY)
            } else {
                transform.postScale(1f, scaleY)
                startPoint.scale(1f, scaleY)
                endPoint.scale(1f, scaleY)
            }
        }

        displayedWavePath.transform(transform)
        return true
    }
}

internal class RoundedBlockRenderer {
    private val drawRect = RectF()
    private val patchRect = RectF()
    private val clipRect = RectF()
    private val roundedRectPath = Path()

    fun drawRoundedBlock(
        canvas: Canvas,
        paint: Paint,
        drawCenter: PathPoint,
        drawWidth: Float,
        drawHeight: Float,
        displayedTrackThickness: Float,
        drawCornerSize: Float,
        clipCenter: PathPoint? = null,
        clipWidth: Float = 0f,
        clipHeight: Float = 0f,
        clipCornerSize: Float = 0f,
        clipRight: Boolean = false,
    ) {
        var localDrawHeight = min(drawHeight, displayedTrackThickness)
        var localClipWidth = clipWidth
        var localClipHeight = clipHeight
        var localClipCornerSize = clipCornerSize

        drawRect.set(-drawWidth / 2f, -localDrawHeight / 2f, drawWidth / 2f, localDrawHeight / 2f)
        paint.style = Paint.Style.FILL
        canvas.save()
        if (clipCenter != null) {
            localClipHeight = min(localClipHeight, displayedTrackThickness)
            localClipCornerSize =
                min(
                    localClipWidth / 2f,
                    localClipCornerSize * localClipHeight / displayedTrackThickness,
                )
            if (clipRight) {
                val leftEdgeDiff =
                    (clipCenter.posVec[0] - localClipCornerSize) -
                        (drawCenter.posVec[0] - drawCornerSize)
                if (leftEdgeDiff > 0f) {
                    clipCenter.translate(-leftEdgeDiff / 2f, 0f)
                    localClipWidth += leftEdgeDiff
                }
                patchRect.set(0f, -localDrawHeight / 2f, drawWidth / 2f, localDrawHeight / 2f)
            } else {
                val rightEdgeDiff =
                    (clipCenter.posVec[0] + localClipCornerSize) -
                        (drawCenter.posVec[0] + drawCornerSize)
                if (rightEdgeDiff < 0f) {
                    clipCenter.translate(-rightEdgeDiff / 2f, 0f)
                    localClipWidth -= rightEdgeDiff
                }
                patchRect.set(-drawWidth / 2f, -localDrawHeight / 2f, 0f, localDrawHeight / 2f)
            }

            clipRect.set(
                -localClipWidth / 2f,
                -localClipHeight / 2f,
                localClipWidth / 2f,
                localClipHeight / 2f,
            )
            canvas.translate(clipCenter.posVec[0], clipCenter.posVec[1])
            canvas.rotate(vectorToCanvasRotation(clipCenter.tanVec))
            roundedRectPath.reset()
            roundedRectPath.addRoundRect(
                clipRect,
                localClipCornerSize,
                localClipCornerSize,
                Path.Direction.CCW,
            )
            canvas.clipPath(roundedRectPath)

            canvas.rotate(-vectorToCanvasRotation(clipCenter.tanVec))
            canvas.translate(-clipCenter.posVec[0], -clipCenter.posVec[1])
            canvas.translate(drawCenter.posVec[0], drawCenter.posVec[1])
            canvas.rotate(vectorToCanvasRotation(drawCenter.tanVec))
            canvas.drawRect(patchRect, paint)
            canvas.drawRoundRect(drawRect, drawCornerSize, drawCornerSize, paint)
        } else {
            canvas.translate(drawCenter.posVec[0], drawCenter.posVec[1])
            canvas.rotate(vectorToCanvasRotation(drawCenter.tanVec))
            canvas.drawRoundRect(drawRect, drawCornerSize, drawCornerSize, paint)
        }
        canvas.restore()
    }

    private fun vectorToCanvasRotation(vector: FloatArray): Float =
        Math.toDegrees(atan2(vector[1], vector[0]).toDouble()).toFloat()
}

internal object WaveMotionUtils {
    fun resolveWaveTransitionSpring(context: Context, animateOn: Boolean): SpringForce {
        val attr =
            if (animateOn) {
                MR.attr.motionSpringFastEffects
            } else {
                MR.attr.motionSpringDefaultEffects
            }
        val defaultStyle =
            if (animateOn) {
                MR.style.Motion_Material3_Spring_Standard_Fast_Effects
            } else {
                MR.style.Motion_Material3_Spring_Standard_Default_Effects
            }
        return MotionUtils.resolveThemeSpringForce(context, attr, defaultStyle)
    }
}

internal fun applyRampEasing(fraction: Float): Float {
    val clamped = fraction.coerceIn(0f, 1f)
    return clamped * clamped * (3f - 2f * clamped)
}

private const val WAVE_SMOOTHNESS = 0.48f
