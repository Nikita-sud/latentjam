/*
 * Copyright (c) 2026 Auxio Project
 * PatchedLinearProgressIndicator.kt is part of Auxio.
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
 
package com.google.android.material.progressindicator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.Px
import androidx.core.graphics.ColorUtils
import androidx.core.math.MathUtils
import com.google.android.material.R as MR
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom playback progress renderer with deterministic wave behavior.
 *
 * The base line/segment geometry is a direct port of MDC's linear determinate draw delegate, while
 * wave amplitude/phase control remains local so playback can toggle waves without touching MDC
 * internals.
 */
class PatchedLinearProgressIndicator
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = MR.attr.linearProgressIndicatorStyle,
) : View(context, attrs, defStyleAttr) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val cachedWavePath = Path()
    private val displayedWavePath = Path()
    private val pathMeasure = PathMeasure()
    private val waveTransform = Matrix()

    // Pre-allocated objects used in drawRoundedBlock.
    private val startPoint = PathPoint()
    private val endPoint = PathPoint()
    private val drawRect = RectF()
    private val patchRect = RectF()
    private val clipRect = RectF()
    private val roundedRectPath = Path()

    private var trackThicknessPx =
        context.resources.getDimensionPixelSize(MR.dimen.mtrl_progress_track_thickness)
    private var trackCornerRadiusPx = trackThicknessPx / 2f
    private var trackInnerCornerRadiusPx = trackCornerRadiusPx
    private var indicatorTrackGapPx = 0

    private var displayedTrackThickness = trackThicknessPx.toFloat()
    private var displayedCornerRadius = trackCornerRadiusPx
    private var displayedInnerCornerRadius = trackInnerCornerRadiusPx
    private var displayedAmplitude = 0f

    private var waveRampProgressMin = 0f
    private var waveRampProgressMax = 1f

    private var trackLength = 0f
    private var totalTrackLengthFraction = 1f

    private var cachedWavelength = -1
    private var cachedTrackLength = -1f
    private var adjustedWavelength = 0f

    private var configuredWavelengthPx = 0
    private var configuredAmplitudePx = 0
    private var configuredSpeedPx = 0

    private var waveEnabled = false
    private var waveTransitionAnimator: ValueAnimator? = null
    private var currentAmplitudeFraction = 0f

    private var phaseFraction = 0f
    private var lastPhaseFrameNanos = 0L
    private var phaseTickerScheduled = false

    private val phaseTicker =
        object : Runnable {
            override fun run() {
                phaseTickerScheduled = false
                if (!shouldTickPhase() && waveTransitionAnimator == null) {
                    return
                }

                if (shouldTickPhase()) {
                    updatePhaseFraction()
                }
                invalidate()
                schedulePhaseTicker()
            }
        }

    init {
        val defaultIndicatorColor = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        val defaultTrackColor = ColorUtils.setAlphaComponent(defaultIndicatorColor, 0x33)

        val styledAttrs =
            context.obtainStyledAttributes(
                attrs,
                MR.styleable.BaseProgressIndicator,
                defStyleAttr,
                MR.style.Widget_MaterialComponents_LinearProgressIndicator,
            )

        trackThicknessPx =
            styledAttrs.getDimensionPixelSize(
                MR.styleable.BaseProgressIndicator_trackThickness,
                trackThicknessPx,
            )

        val requestedCorner =
            styledAttrs
                .getDimensionPixelSize(
                    MR.styleable.BaseProgressIndicator_trackCornerRadius,
                    trackThicknessPx / 2,
                )
                .toFloat()
        trackCornerRadiusPx = min(trackThicknessPx / 2f, requestedCorner)

        activePaint.color =
            styledAttrs.getColor(
                MR.styleable.BaseProgressIndicator_indicatorColor,
                defaultIndicatorColor,
            )
        trackPaint.color =
            styledAttrs.getColor(MR.styleable.BaseProgressIndicator_trackColor, defaultTrackColor)

        indicatorTrackGapPx =
            abs(
                styledAttrs.getDimensionPixelSize(
                    MR.styleable.BaseProgressIndicator_indicatorTrackGapSize,
                    0,
                )
            )

        val fallbackWavelength =
            abs(styledAttrs.getDimensionPixelSize(MR.styleable.BaseProgressIndicator_wavelength, 0))
        configuredWavelengthPx =
            abs(
                styledAttrs.getDimensionPixelSize(
                    MR.styleable.BaseProgressIndicator_wavelengthDeterminate,
                    fallbackWavelength,
                )
            )
        configuredAmplitudePx =
            abs(
                styledAttrs.getDimensionPixelSize(
                    MR.styleable.BaseProgressIndicator_waveAmplitude,
                    0,
                )
            )
        configuredSpeedPx =
            styledAttrs.getDimensionPixelSize(MR.styleable.BaseProgressIndicator_waveSpeed, 0)

        waveRampProgressMin =
            styledAttrs
                .getFloat(MR.styleable.BaseProgressIndicator_waveAmplitudeRampProgressMin, 0f)
                .coerceIn(0f, 1f)
        waveRampProgressMax =
            styledAttrs
                .getFloat(MR.styleable.BaseProgressIndicator_waveAmplitudeRampProgressMax, 1f)
                .coerceIn(0f, 1f)
        if (waveRampProgressMax - waveRampProgressMin <= EPSILON) {
            waveRampProgressMax = min(1f, waveRampProgressMin + 0.01f)
        }

        styledAttrs.recycle()

        val linearAttrs =
            context.obtainStyledAttributes(
                attrs,
                MR.styleable.LinearProgressIndicator,
                defStyleAttr,
                MR.style.Widget_MaterialComponents_LinearProgressIndicator,
            )
        trackInnerCornerRadiusPx = resolveInnerCornerRadius(linearAttrs)
        linearAttrs.recycle()

        displayedAmplitude = configuredAmplitudePx.toFloat()
        displayedCornerRadius = trackCornerRadiusPx
        displayedInnerCornerRadius = trackInnerCornerRadiusPx
        displayedTrackThickness = trackThicknessPx.toFloat()
    }

    var max: Int = 100
        set(value) {
            val sanitized = value.coerceAtLeast(1)
            if (field == sanitized) {
                return
            }
            field = sanitized
            if (progress > sanitized) {
                progress = sanitized
            }
            invalidate()
        }

    var progress: Int = 0
        set(value) {
            val sanitized = value.coerceIn(0, max)
            if (field == sanitized) {
                return
            }
            field = sanitized
            invalidate()
        }

    fun setWaveEnabled(
        enabled: Boolean,
        @Px wavelengthPx: Int,
        @Px amplitudePx: Int,
        @Px speedPx: Int,
    ) {
        if (wavelengthPx > 0) {
            configuredWavelengthPx = abs(wavelengthPx)
            cachedWavelength = -1
        }

        if (amplitudePx > 0) {
            val sanitized = abs(amplitudePx)
            if (configuredAmplitudePx != sanitized) {
                configuredAmplitudePx = sanitized
                displayedAmplitude = sanitized.toFloat()
                requestLayout()
            }
        }

        if (speedPx != 0) {
            configuredSpeedPx = speedPx
        }

        waveTransitionAnimator?.cancel()
        waveTransitionAnimator = null

        val shouldEnable = enabled && canAnimateWave()
        waveEnabled = shouldEnable

        if (shouldEnable) {
            resetPhaseClock()
            transitionAmplitudeTo(1f)
        } else {
            transitionAmplitudeTo(0f) {
                clearPhaseClock()
                ensurePhaseTickerState()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensurePhaseTickerState()
    }

    override fun onDetachedFromWindow() {
        waveTransitionAnimator?.cancel()
        waveTransitionAnimator = null
        removeCallbacks(phaseTicker)
        phaseTickerScheduled = false
        clearPhaseClock()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        ensurePhaseTickerState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        ensurePhaseTickerState()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight =
            (trackThicknessPx + configuredAmplitudePx * 2 + paddingTop + paddingBottom)
                .coerceAtLeast(suggestedMinimumHeight)
        val measuredWidth =
            resolveSize(suggestedMinimumWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val contentLeft = paddingLeft.toFloat()
        val contentRight = (width - paddingRight).toFloat()
        val contentTop = paddingTop.toFloat()
        val contentBottom = (height - paddingBottom).toFloat()

        val availableWidth = contentRight - contentLeft
        val availableHeight = contentBottom - contentTop
        if (availableWidth <= 0f || availableHeight <= 0f) {
            return
        }

        if (abs(trackLength - availableWidth) > EPSILON) {
            trackLength = availableWidth
            invalidateCachedWavePath(trackLength, configuredWavelengthPx)
        }

        val preferredHeight = getPreferredHeight()
        val centerX = contentLeft + availableWidth / 2f
        val centerY =
            contentTop + availableHeight / 2f + max(0f, (availableHeight - preferredHeight) / 2f)

        canvas.save()
        canvas.translate(centerX, centerY)
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            canvas.scale(-1f, 1f)
        }

        val halfPreferredHeight = preferredHeight / 2f
        canvas.clipRect(
            -trackLength / 2f,
            -halfPreferredHeight,
            trackLength / 2f,
            halfPreferredHeight,
        )

        displayedTrackThickness = trackThicknessPx.toFloat()
        displayedCornerRadius = min(trackThicknessPx / 2f, trackCornerRadiusPx)
        displayedInnerCornerRadius = min(trackThicknessPx / 2f, trackInnerCornerRadiusPx)
        displayedAmplitude = configuredAmplitudePx.toFloat()
        totalTrackLengthFraction = 1f

        val progressFraction = (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f)

        drawTrack(canvas, progressFraction)

        if (progressFraction > 0f) {
            val activeLength = progressFraction * trackLength
            val shouldDrawWave =
                canDrawWave(trackLength, activeLength) &&
                    (waveEnabled || waveTransitionAnimator != null)
            val ramp =
                applyRampEasing(
                    calculateStartRampFraction(
                        progressFraction = progressFraction,
                        activeLength = activeLength,
                        trackLength = trackLength,
                    )
                )
            val amplitudeFraction = if (shouldDrawWave) currentAmplitudeFraction * ramp else 0f

            drawLine(
                canvas = canvas,
                paint = activePaint,
                startFraction = 0f,
                endFraction = progressFraction,
                color = activePaint.color,
                startGapSize = 0,
                endGapSize = 0,
                amplitudeFraction = amplitudeFraction,
                phaseFraction = phaseFraction,
                allowWave = shouldDrawWave,
            )
        }

        canvas.restore()
    }

    private fun drawTrack(canvas: Canvas, progressFraction: Float) {
        if (indicatorTrackGapPx > 0) {
            drawLine(
                canvas = canvas,
                paint = trackPaint,
                startFraction = progressFraction,
                endFraction = 1f,
                color = trackPaint.color,
                startGapSize = indicatorTrackGapPx,
                endGapSize = indicatorTrackGapPx,
                amplitudeFraction = 0f,
                phaseFraction = 0f,
                allowWave = false,
            )
            return
        }

        drawLine(
            canvas = canvas,
            paint = trackPaint,
            startFraction = 0f,
            endFraction = 1f,
            color = trackPaint.color,
            startGapSize = 0,
            endGapSize = 0,
            amplitudeFraction = 0f,
            phaseFraction = 0f,
            allowWave = false,
        )
    }

    private fun drawLine(
        canvas: Canvas,
        paint: Paint,
        startFraction: Float,
        endFraction: Float,
        color: Int,
        startGapSize: Int,
        endGapSize: Int,
        amplitudeFraction: Float,
        phaseFraction: Float,
        allowWave: Boolean,
    ) {
        var clampedStart = clamp(startFraction, 0f, 1f)
        var clampedEnd = clamp(endFraction, 0f, 1f)

        clampedStart = lerp(1f - totalTrackLengthFraction, 1f, clampedStart)
        clampedEnd = lerp(1f - totalTrackLengthFraction, 1f, clampedEnd)

        val adjustedStartGapSize =
            (startGapSize * clamp(clampedStart, 0f, GAP_TRANSITION_FRACTION) /
                    GAP_TRANSITION_FRACTION)
                .roundToInt()
        val adjustedEndGapSize =
            (endGapSize * (1f - clamp(clampedEnd, 1f - GAP_TRANSITION_FRACTION, 1f)) /
                    GAP_TRANSITION_FRACTION)
                .roundToInt()

        val displayedStartX = (clampedStart * trackLength + adjustedStartGapSize).roundToInt()
        val displayedEndX = (clampedEnd * trackLength - adjustedEndGapSize).roundToInt()

        var startCornerRadius = displayedCornerRadius
        var endCornerRadius = displayedCornerRadius

        if (
            abs(displayedCornerRadius - displayedInnerCornerRadius) > EPSILON &&
                trackLength > EPSILON
        ) {
            val maxCornerScale =
                max(displayedCornerRadius, displayedInnerCornerRadius) / trackLength
            if (maxCornerScale > EPSILON) {
                startCornerRadius =
                    lerp(
                        displayedCornerRadius,
                        displayedInnerCornerRadius,
                        clamp(displayedStartX / trackLength, 0f, maxCornerScale) / maxCornerScale,
                    )
                endCornerRadius =
                    lerp(
                        displayedCornerRadius,
                        displayedInnerCornerRadius,
                        clamp((trackLength - displayedEndX) / trackLength, 0f, maxCornerScale) /
                            maxCornerScale,
                    )
            }
        }

        val trackStartX = -trackLength / 2f

        if (displayedStartX > displayedEndX) {
            return
        }

        val startBlockCenterX = displayedStartX + startCornerRadius
        val endBlockCenterX = displayedEndX - endCornerRadius
        val startBlockWidth = startCornerRadius * 2f
        val endBlockWidth = endCornerRadius * 2f

        paint.color = color
        paint.isAntiAlias = true
        paint.strokeWidth = displayedTrackThickness

        startPoint.reset()
        endPoint.reset()
        startPoint.translate(startBlockCenterX + trackStartX, 0f)
        endPoint.translate(endBlockCenterX + trackStartX, 0f)

        if (
            displayedStartX == 0 &&
                endBlockCenterX + endCornerRadius < startBlockCenterX + startCornerRadius
        ) {
            drawRoundedBlock(
                canvas = canvas,
                paint = paint,
                drawCenter = startPoint,
                drawWidth = startBlockWidth,
                drawHeight = displayedTrackThickness,
                drawCornerSize = startCornerRadius,
                clipCenter = endPoint,
                clipWidth = endBlockWidth,
                clipHeight = displayedTrackThickness,
                clipCornerSize = endCornerRadius,
                clipRight = true,
            )
            return
        }

        if (startBlockCenterX - startCornerRadius > endBlockCenterX - endCornerRadius) {
            drawRoundedBlock(
                canvas = canvas,
                paint = paint,
                drawCenter = endPoint,
                drawWidth = endBlockWidth,
                drawHeight = displayedTrackThickness,
                drawCornerSize = endCornerRadius,
                clipCenter = startPoint,
                clipWidth = startBlockWidth,
                clipHeight = displayedTrackThickness,
                clipCornerSize = startCornerRadius,
                clipRight = false,
            )
            return
        }

        paint.style = Paint.Style.STROKE
        val useStrokeCap = useStrokeCap()
        paint.strokeCap = if (useStrokeCap) Paint.Cap.ROUND else Paint.Cap.BUTT

        var drawWavyPath =
            allowWave &&
                amplitudeFraction > EPSILON &&
                configuredWavelengthPx > 0 &&
                configuredAmplitudePx > 0

        if (drawWavyPath) {
            ensureCachedWavePath(trackLength, configuredWavelengthPx)
            if (adjustedWavelength <= EPSILON || pathMeasure.length <= EPSILON) {
                drawWavyPath = false
            }
        }

        if (!drawWavyPath) {
            canvas.drawLine(
                startPoint.posVec[0],
                startPoint.posVec[1],
                endPoint.posVec[0],
                endPoint.posVec[1],
                paint,
            )
        } else {
            calculateDisplayedWavePath(
                start = startBlockCenterX / trackLength,
                end = endBlockCenterX / trackLength,
                amplitudeFraction = amplitudeFraction,
                phaseFraction = phaseFraction,
            )
            canvas.drawPath(displayedWavePath, paint)
        }

        if (!useStrokeCap) {
            if (startBlockCenterX > 0f && startCornerRadius > 0f) {
                drawRoundedBlock(
                    canvas = canvas,
                    paint = paint,
                    drawCenter = startPoint,
                    drawWidth = startBlockWidth,
                    drawHeight = displayedTrackThickness,
                    drawCornerSize = startCornerRadius,
                )
            }
            if (endBlockCenterX < trackLength && endCornerRadius > 0f) {
                drawRoundedBlock(
                    canvas = canvas,
                    paint = paint,
                    drawCenter = endPoint,
                    drawWidth = endBlockWidth,
                    drawHeight = displayedTrackThickness,
                    drawCornerSize = endCornerRadius,
                )
            }
        }
    }

    private fun calculateDisplayedWavePath(
        start: Float,
        end: Float,
        amplitudeFraction: Float,
        phaseFraction: Float,
    ) {
        displayedWavePath.rewind()
        if (pathMeasure.length <= EPSILON) {
            return
        }

        var adjustedStart = start
        var adjustedEnd = end
        var trackStartX = -trackLength / 2f

        if (adjustedWavelength > EPSILON) {
            val cycleCount = trackLength / adjustedWavelength
            if (cycleCount > EPSILON) {
                val phaseFractionInPath = phaseFraction / cycleCount
                val ratio = cycleCount / (cycleCount + 1f)
                adjustedStart = (adjustedStart + phaseFractionInPath) * ratio
                adjustedEnd = (adjustedEnd + phaseFractionInPath) * ratio
            }
            trackStartX -= phaseFraction * adjustedWavelength
        }

        val startDistance = adjustedStart * pathMeasure.length
        val endDistance = adjustedEnd * pathMeasure.length
        pathMeasure.getSegment(startDistance, endDistance, displayedWavePath, true)

        startPoint.reset()
        pathMeasure.getPosTan(startDistance, startPoint.posVec, startPoint.tanVec)
        endPoint.reset()
        pathMeasure.getPosTan(endDistance, endPoint.posVec, endPoint.tanVec)

        waveTransform.reset()
        waveTransform.setTranslate(trackStartX, 0f)
        startPoint.translate(trackStartX, 0f)
        endPoint.translate(trackStartX, 0f)

        val scaledAmplitude = displayedAmplitude * amplitudeFraction
        waveTransform.postScale(1f, scaledAmplitude)
        startPoint.scale(1f, scaledAmplitude)
        endPoint.scale(1f, scaledAmplitude)

        displayedWavePath.transform(waveTransform)
    }

    private fun ensureCachedWavePath(trackLength: Float, wavelength: Int) {
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

    private fun invalidateCachedWavePath(trackLength: Float, wavelength: Int) {
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

        waveTransform.reset()
        waveTransform.setScale(adjustedWavelength / 2f, -2f)
        waveTransform.postTranslate(0f, 1f)
        cachedWavePath.transform(waveTransform)
        pathMeasure.setPath(cachedWavePath, false)
    }

    private fun canAnimateWave(): Boolean {
        return configuredWavelengthPx > 0 && configuredAmplitudePx > 0 && configuredSpeedPx != 0
    }

    private fun canDrawWave(trackLength: Float, activeLength: Float): Boolean {
        if (!canAnimateWave() || currentAmplitudeFraction <= EPSILON) {
            return false
        }

        val minLength = calculateWaveStartLengthPx()
        return trackLength > 0f && activeLength > minLength
    }

    private fun calculateStartRampFraction(
        progressFraction: Float,
        activeLength: Float,
        trackLength: Float,
    ): Float {
        val progressRamp = calculateProgressRamp(progressFraction)

        val edgeRamp =
            if (trackLength <= EPSILON) {
                1f
            } else {
                val thresholdPx = displayedCornerRadius + indicatorTrackGapPx
                if (thresholdPx <= EPSILON) {
                    1f
                } else {
                    val thresholdFraction = (thresholdPx / trackLength).coerceIn(0f, 1f)
                    val fullFraction = (thresholdFraction * 2f).coerceIn(0f, 1f)
                    if (fullFraction - thresholdFraction <= EPSILON) {
                        1f
                    } else {
                        ((progressFraction - thresholdFraction) /
                                (fullFraction - thresholdFraction))
                            .coerceIn(0f, 1f)
                    }
                }
            }

        val lengthRamp =
            ((activeLength - calculateWaveStartLengthPx()) / calculateWaveRampLengthPx()).coerceIn(
                0f,
                1f,
            )

        return min(progressRamp, min(edgeRamp, lengthRamp))
    }

    private fun calculateProgressRamp(progressFraction: Float): Float {
        if (progressFraction <= waveRampProgressMin) {
            return 0f
        }

        val configuredSpan = (waveRampProgressMax - waveRampProgressMin).coerceAtLeast(EPSILON)
        val effectiveSpan =
            if (waveRampProgressMin <= EPSILON && waveRampProgressMax >= 1f - EPSILON) {
                DEFAULT_PROGRESS_RAMP_SPAN
            } else {
                configuredSpan
            }

        return ((progressFraction - waveRampProgressMin) / effectiveSpan).coerceIn(0f, 1f)
    }

    private fun calculateWaveStartLengthPx(): Float {
        return max(displayedCornerRadius * 2f + indicatorTrackGapPx + 1f, displayedTrackThickness)
    }

    private fun calculateWaveRampLengthPx(): Float {
        val wavelengthRamp = configuredWavelengthPx * WAVELENGTH_RAMP_MULTIPLIER
        return max(displayedCornerRadius * 2f + indicatorTrackGapPx, wavelengthRamp)
            .coerceAtLeast(1f)
    }

    private fun useStrokeCap(): Boolean {
        val fullyRounded = abs(displayedCornerRadius - displayedTrackThickness / 2f) < EPSILON
        val sameInnerOuter = abs(displayedInnerCornerRadius - displayedCornerRadius) < EPSILON
        return fullyRounded && sameInnerOuter
    }

    private fun applyRampEasing(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    private fun drawRoundedBlock(
        canvas: Canvas,
        paint: Paint,
        drawCenter: PathPoint,
        drawWidth: Float,
        drawHeight: Float,
        drawCornerSize: Float,
    ) {
        drawRoundedBlock(
            canvas = canvas,
            paint = paint,
            drawCenter = drawCenter,
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            drawCornerSize = drawCornerSize,
            clipCenter = null,
            clipWidth = 0f,
            clipHeight = 0f,
            clipCornerSize = 0f,
            clipRight = false,
        )
    }

    private fun drawRoundedBlock(
        canvas: Canvas,
        paint: Paint,
        drawCenter: PathPoint,
        drawWidth: Float,
        drawHeight: Float,
        drawCornerSize: Float,
        clipCenter: PathPoint?,
        clipWidth: Float,
        clipHeight: Float,
        clipCornerSize: Float,
        clipRight: Boolean,
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

    private fun transitionAmplitudeTo(target: Float, onFinished: (() -> Unit)? = null) {
        val clampedTarget = MathUtils.clamp(target, 0f, 1f)
        if (abs(currentAmplitudeFraction - clampedTarget) < EPSILON) {
            currentAmplitudeFraction = clampedTarget
            ensurePhaseTickerState()
            invalidate()
            onFinished?.invoke()
            return
        }

        val animator =
            ValueAnimator.ofFloat(currentAmplitudeFraction, clampedTarget).apply {
                duration =
                    if (clampedTarget > currentAmplitudeFraction) {
                        WAVE_ON_DURATION_MS
                    } else {
                        WAVE_OFF_DURATION_MS
                    }
                addUpdateListener { animation ->
                    currentAmplitudeFraction = animation.animatedValue as Float
                    ensurePhaseTickerState()
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: Animator) {
                            waveTransitionAnimator = null
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            waveTransitionAnimator = null
                            currentAmplitudeFraction = clampedTarget
                            ensurePhaseTickerState()
                            invalidate()
                            onFinished?.invoke()
                        }
                    }
                )
            }

        waveTransitionAnimator = animator
        ensurePhaseTickerState()
        animator.start()
    }

    private fun shouldTickPhase(): Boolean {
        return currentAmplitudeFraction > 0f &&
            configuredSpeedPx != 0 &&
            visibility == View.VISIBLE &&
            windowVisibility == View.VISIBLE &&
            isShown &&
            alpha > 0f
    }

    private fun updatePhaseFraction() {
        val wavelength = configuredWavelengthPx
        if (wavelength <= 0 || configuredSpeedPx == 0) {
            return
        }

        if (trackLength <= 0f) {
            return
        }

        val progressFraction = (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val activeLength = progressFraction * trackLength

        val rampScale =
            applyRampEasing(
                calculateStartRampFraction(
                    progressFraction = progressFraction,
                    activeLength = activeLength,
                    trackLength = trackLength,
                )
            )

        val phaseScale = MIN_PHASE_SPEED_SCALE + (1f - MIN_PHASE_SPEED_SCALE) * rampScale
        val amplitudeScale =
            MIN_PHASE_AMPLITUDE_SCALE + (1f - MIN_PHASE_AMPLITUDE_SCALE) * currentAmplitudeFraction
        val effectiveSpeed = configuredSpeedPx.toFloat() * phaseScale * amplitudeScale
        if (effectiveSpeed == 0f) {
            return
        }

        val nowNanos = System.nanoTime()
        if (lastPhaseFrameNanos != 0L) {
            val deltaSeconds = (nowNanos - lastPhaseFrameNanos) / 1_000_000_000f
            val delta = deltaSeconds * (effectiveSpeed / wavelength.toFloat())
            phaseFraction = ((phaseFraction + delta) % 1f + 1f) % 1f
        }
        lastPhaseFrameNanos = nowNanos

        if (phaseFraction <= 0f) {
            phaseFraction = MIN_PHASE_FRACTION
        }
    }

    private fun ensurePhaseTickerState() {
        if (shouldTickPhase() || waveTransitionAnimator != null) {
            schedulePhaseTicker()
        } else {
            removeCallbacks(phaseTicker)
            phaseTickerScheduled = false
        }
    }

    private fun schedulePhaseTicker() {
        if (phaseTickerScheduled) {
            return
        }

        phaseTickerScheduled = true
        postOnAnimation(phaseTicker)
    }

    private fun resetPhaseClock() {
        phaseFraction = MIN_PHASE_FRACTION
        lastPhaseFrameNanos = 0L
    }

    private fun clearPhaseClock() {
        phaseFraction = 0f
        lastPhaseFrameNanos = 0L
    }

    private fun resolveInnerCornerRadius(linearAttrs: TypedArray): Float {
        val typedValue =
            linearAttrs.peekValue(MR.styleable.LinearProgressIndicator_trackInnerCornerRadius)
                ?: return trackCornerRadiusPx

        return when (typedValue.type) {
            TypedValue.TYPE_DIMENSION -> {
                val sizePx =
                    TypedValue.complexToDimensionPixelSize(
                        typedValue.data,
                        linearAttrs.resources.displayMetrics,
                    )
                min(trackThicknessPx / 2f, sizePx.toFloat())
            }
            TypedValue.TYPE_FRACTION -> {
                val fraction = min(0.5f, typedValue.getFraction(1f, 1f))
                min(trackThicknessPx / 2f, trackThicknessPx * fraction)
            }
            else -> trackCornerRadiusPx
        }
    }

    private fun getPreferredHeight(): Float {
        return (trackThicknessPx + configuredAmplitudePx * 2).toFloat()
    }

    private fun Context.getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        val resolved = theme.resolveAttribute(attr, typedValue, true)
        if (!resolved) {
            return Color.WHITE
        }

        return if (typedValue.resourceId != 0) {
            context.getColor(typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private data class PathPoint(
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

        fun scale(sx: Float, sy: Float) {
            posVec[0] *= sx
            posVec[1] *= sy
            tanVec[0] *= sx
            tanVec[1] *= sy
        }
    }

    private companion object {
        const val WAVE_SMOOTHNESS = 0.48f
        const val WAVE_ON_DURATION_MS = 220L
        const val WAVE_OFF_DURATION_MS = 160L
        const val MIN_PHASE_FRACTION = 0.0001f
        const val EPSILON = 0.0001f
        const val GAP_TRANSITION_FRACTION = 0.01f

        const val WAVELENGTH_RAMP_MULTIPLIER = 0.15f
        const val MIN_PHASE_SPEED_SCALE = 0.35f
        const val MIN_PHASE_AMPLITUDE_SCALE = 0.25f
        const val DEFAULT_PROGRESS_RAMP_SPAN = 0.12f

        fun clamp(value: Float, min: Float, max: Float): Float {
            return when {
                value < min -> min
                value > max -> max
                else -> value
            }
        }

        fun lerp(start: Float, end: Float, fraction: Float): Float {
            return start + (end - start) * fraction
        }
    }
}
