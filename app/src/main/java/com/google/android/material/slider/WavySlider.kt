/*
 * Copyright (c) 2026 Auxio Project
 * WavySlider.kt is part of Auxio.
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
 
package com.google.android.material.slider

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.Px
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.R as MR
import com.google.android.material.motion.MotionUtils
import com.google.android.material.progressindicator.PatchedLinearProgressIndicator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Slider with active-track wave rendering that ports MDC LinearProgressIndicator's wavy draw
 * behavior and keeps phase/amplitude transition behavior aligned with
 * [PatchedLinearProgressIndicator].
 */
class WavySlider
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = MR.attr.sliderStyle,
) : Slider(context, attrs, defStyleAttr) {
    private val wavePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }

    private val cachedWavePath = Path()
    private val displayedWavePath = Path()
    private val pathMeasure = PathMeasure()
    private val transform = Matrix()

    // Pre-allocates objects used in draw.
    private val startPoint = PathPoint()
    private val endPoint = PathPoint()
    private val cornerRect = RectF()
    private val patchRect = RectF()
    private val clipRect = RectF()
    private val drawRect = RectF()
    private val roundedRectPath = Path()

    private var cachedWavelength = -1
    private var cachedTrackLength = -1f
    private var adjustedWavelength = 0f

    private val transparentTrackTint = ColorStateList.valueOf(Color.TRANSPARENT)
    private var waveTrackTintList: ColorStateList = trackActiveTintList
    private var linearActiveTrackSuppressed = false

    private var waveTransitionAnimation: SpringAnimation? = null
    private var currentAmplitudeFraction = MIN_VISIBLE_WAVE_FRACTION
    private var waveEnabled = false

    private var phaseFraction = 0f
    private var lastPhaseFrameNanos = 0L
    private var phaseTickerScheduled = false

    private var configuredWavelengthPx = 0
    private var configuredAmplitudePx = 0
    private var configuredSpeedPx = 0

    private var displayedTrackThickness = 0f
    private var displayedCornerRadius = 0f
    private var displayedInnerCornerRadius = 0f
    private var displayedAmplitude = 0f

    private var waveRampProgressMin = 0f
    private var waveRampProgressMax = DEFAULT_WAVE_RAMP_PROGRESS_MAX

    private val phaseTicker =
        object : Runnable {
            override fun run() {
                phaseTickerScheduled = false
                if (!shouldTickPhase() && waveTransitionAnimation == null) {
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
        val rampAttrs = context.obtainStyledAttributes(attrs, MR.styleable.BaseProgressIndicator)
        waveRampProgressMin =
            rampAttrs
                .getFloat(MR.styleable.BaseProgressIndicator_waveAmplitudeRampProgressMin, 0f)
                .coerceIn(0f, 1f)
        waveRampProgressMax =
            rampAttrs
                .getFloat(
                    MR.styleable.BaseProgressIndicator_waveAmplitudeRampProgressMax,
                    DEFAULT_WAVE_RAMP_PROGRESS_MAX,
                )
                .coerceIn(0f, 1f)
        if (waveRampProgressMax - waveRampProgressMin <= EPSILON) {
            waveRampProgressMax = min(1f, waveRampProgressMin + 0.01f)
        }
        rampAttrs.recycle()
    }

    /** Wave amplitude in pixels. */
    @Px
    var waveAmplitude: Int = 0
        set(value) {
            val sanitized = abs(value)
            if (field != sanitized) {
                field = sanitized
                if (sanitized > 0) {
                    configuredAmplitudePx = sanitized
                }
                updateActiveTrackSuppression()
                ensurePhaseTickerState()
                invalidate()
            }
        }

    /** Wavelength in pixels for determinate rendering. */
    @Px
    var wavelengthDeterminate: Int = 0
        set(value) {
            val sanitized = abs(value)
            if (field != sanitized) {
                field = sanitized
                if (sanitized > 0) {
                    configuredWavelengthPx = sanitized
                }
                cachedWavelength = -1
                updateActiveTrackSuppression()
                ensurePhaseTickerState()
                invalidate()
            }
        }

    /** Wave speed in px/s. Positive towards 100%, negative towards 0%. */
    @Px
    var waveSpeed: Int = 0
        set(value) {
            if (field != value) {
                field = value
                if (value != 0) {
                    configuredSpeedPx = value
                }
                ensurePhaseTickerState()
                invalidate()
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensurePhaseTickerState()
    }

    override fun onDetachedFromWindow() {
        waveTransitionAnimation?.cancel()
        waveTransitionAnimation = null
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

    override fun setTrackActiveTintList(trackColor: ColorStateList) {
        waveTrackTintList = trackColor
        if (!linearActiveTrackSuppressed) {
            super.setTrackActiveTintList(trackColor)
        }
    }

    /**
     * Mirrors [PatchedLinearProgressIndicator.setWaveEnabled]: keeps geometry stable and animates
     * internal amplitude fraction.
     */
    fun setWaveEnabled(
        enabled: Boolean,
        @Px wavelengthPx: Int,
        @Px amplitudePx: Int,
        @Px speedPx: Int,
    ) {
        if (wavelengthPx > 0) {
            configuredWavelengthPx = abs(wavelengthPx)
        }
        if (amplitudePx > 0) {
            configuredAmplitudePx = abs(amplitudePx)
        }
        if (speedPx != 0) {
            configuredSpeedPx = speedPx
        }

        waveTransitionAnimation?.cancel()
        waveTransitionAnimation = null

        applyWaveGeometryIfConfigured()

        val canEnableWave = enabled && canAnimateWave()
        waveEnabled = canEnableWave
        updateActiveTrackSuppression()
        if (canEnableWave) {
            waveSpeed = configuredSpeedPx
            resetPhaseClock()
            transitionToAmplitudeFraction(1f)
        } else {
            if (abs(currentAmplitudeFraction - MIN_VISIBLE_WAVE_FRACTION) < EPSILON) {
                waveSpeed = 0
                clearPhaseClock()
                ensurePhaseTickerState()
                invalidate()
            } else {
                transitionToAmplitudeFraction(MIN_VISIBLE_WAVE_FRACTION) {
                    waveSpeed = 0
                    clearPhaseClock()
                    ensurePhaseTickerState()
                    invalidate()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawWavyActiveTrack(canvas)
    }

    private fun drawWavyActiveTrack(canvas: Canvas) {
        if (!shouldDrawWave()) {
            return
        }
        val trackLength = trackWidth.toFloat()
        if (trackLength <= 0f) {
            return
        }
        val range = valueTo - valueFrom
        if (range <= 0f) {
            return
        }
        val progressFraction = ((value - valueFrom) / range).coerceIn(0f, 1f)
        if (progressFraction <= 0f) {
            return
        }

        displayedTrackThickness = trackHeight.toFloat()
        displayedCornerRadius = min(displayedTrackThickness / 2f, getTrackCornerSize().toFloat())
        displayedInnerCornerRadius =
            min(displayedTrackThickness / 2f, getTrackInsideCornerSize().toFloat())
        displayedAmplitude = waveAmplitude.toFloat()

        ensureCachedWavePath(trackLength, wavelengthDeterminate)
        if (pathMeasure.length <= 0f || adjustedWavelength <= 0f) {
            return
        }

        val activeTrackColor =
            waveTrackTintList.getColorForState(drawableState, waveTrackTintList.defaultColor)
        wavePaint.color = activeTrackColor
        wavePaint.strokeWidth = displayedTrackThickness
        val startRampFraction = calculateStartRampFraction(progressFraction, trackLength)
        val rampedAmplitudeFraction =
            applyStartWaveRamp(
                amplitudeFraction = currentAmplitudeFraction,
                startRampFraction = startRampFraction,
            )
        drawActiveWaveSegment(
            canvas = canvas,
            trackLength = trackLength,
            progressFraction = progressFraction,
            paintColor = activeTrackColor,
            amplitudeFraction = rampedAmplitudeFraction,
            phaseFraction = phaseFraction,
        )
    }

    private fun drawActiveWaveSegment(
        canvas: Canvas,
        trackLength: Float,
        progressFraction: Float,
        paintColor: Int,
        amplitudeFraction: Float,
        phaseFraction: Float,
    ) {
        val clampedProgress = progressFraction.coerceIn(0f, 1f)
        val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val direction = if (isRtl) ActiveTrackDirection.RIGHT else ActiveTrackDirection.LEFT
        val thumbTrackPosition =
            if (isRtl) {
                (1f - clampedProgress) * trackLength
            } else {
                clampedProgress * trackLength
            }

        val startBound =
            if (direction == ActiveTrackDirection.LEFT) {
                -displayedCornerRadius
            } else {
                thumbTrackPosition + thumbTrackGapSize
            }
        val endBound =
            if (direction == ActiveTrackDirection.LEFT) {
                thumbTrackPosition - thumbTrackGapSize
            } else {
                trackLength + displayedCornerRadius
            }
        if (startBound >= endBound) {
            return
        }

        val startCornerRadius = calculateStartTrackCornerSize(thumbTrackPosition)
        val endCornerRadius = calculateEndTrackCornerSize(trackLength, thumbTrackPosition)

        drawSegment(
            canvas = canvas,
            trackLength = trackLength,
            startBound = startBound,
            endBound = endBound,
            startCornerRadius = startCornerRadius,
            endCornerRadius = endCornerRadius,
            paintColor = paintColor,
            amplitudeFraction = amplitudeFraction,
            phaseFraction = phaseFraction,
        )
    }

    private fun drawSegment(
        canvas: Canvas,
        trackLength: Float,
        startBound: Float,
        endBound: Float,
        startCornerRadius: Float,
        endCornerRadius: Float,
        paintColor: Int,
        amplitudeFraction: Float,
        phaseFraction: Float,
    ) {
        val originX = trackSidePadding.toFloat()
        val trackCenterY = height / 2f

        val startBlockCenterX = startBound + startCornerRadius
        val endBlockCenterX = endBound - endCornerRadius
        val startBlockWidth = startCornerRadius * 2f
        val endBlockWidth = endCornerRadius * 2f

        wavePaint.color = paintColor
        wavePaint.isAntiAlias = true
        wavePaint.strokeWidth = displayedTrackThickness

        startPoint.reset()
        endPoint.reset()
        startPoint.translate(startBlockCenterX + originX, trackCenterY)
        endPoint.translate(endBlockCenterX + originX, trackCenterY)

        val drawWavyPath = amplitudeFraction > 0f
        if (
            startBound <= 0f &&
                endBlockCenterX + endCornerRadius < startBlockCenterX + startCornerRadius
        ) {
            drawRoundedBlock(
                canvas = canvas,
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

        wavePaint.style = Paint.Style.STROKE
        wavePaint.strokeCap = if (useStrokeCap()) Paint.Cap.ROUND else Paint.Cap.BUTT

        if (!drawWavyPath) {
            canvas.drawLine(
                startPoint.posVec[0],
                startPoint.posVec[1],
                endPoint.posVec[0],
                endPoint.posVec[1],
                wavePaint,
            )
        } else {
            calculateDisplayedWavePath(
                trackLength = trackLength,
                start = startBlockCenterX / trackLength,
                end = endBlockCenterX / trackLength,
                amplitudeFraction = amplitudeFraction,
                phaseFraction = phaseFraction,
                trackCenterY = trackCenterY,
            )
            canvas.drawPath(displayedWavePath, wavePaint)
        }

        if (!useStrokeCap()) {
            if (startCornerRadius > 0f) {
                drawRoundedBlock(
                    canvas = canvas,
                    drawCenter = startPoint,
                    drawWidth = startBlockWidth,
                    drawHeight = displayedTrackThickness,
                    drawCornerSize = startCornerRadius,
                )
            }
            if (endCornerRadius > 0f) {
                drawRoundedBlock(
                    canvas = canvas,
                    drawCenter = endPoint,
                    drawWidth = endBlockWidth,
                    drawHeight = displayedTrackThickness,
                    drawCornerSize = endCornerRadius,
                )
            }
        }
    }

    private fun calculateDisplayedWavePath(
        trackLength: Float,
        start: Float,
        end: Float,
        amplitudeFraction: Float,
        phaseFraction: Float,
        trackCenterY: Float,
    ) {
        var adjustedStart = start
        var adjustedEnd = end
        var resultTranslationX = trackSidePadding.toFloat()
        val hasWavyEffect = wavelengthDeterminate > 0 && waveAmplitude > 0

        if (hasWavyEffect) {
            val cycleCount = trackLength / adjustedWavelength
            val phaseFractionInPath = phaseFraction / cycleCount
            val ratio = cycleCount / (cycleCount + 1f)
            adjustedStart = (adjustedStart + phaseFractionInPath) * ratio
            adjustedEnd = (adjustedEnd + phaseFractionInPath) * ratio
            resultTranslationX -= phaseFraction * adjustedWavelength
        }

        val clampedStart = clamp(adjustedStart, 0f, 1f)
        val clampedEnd = clamp(adjustedEnd, 0f, 1f)
        if (clampedEnd <= clampedStart) {
            displayedWavePath.rewind()
            return
        }

        displayedWavePath.rewind()
        val startDistance = clampedStart * pathMeasure.length
        val endDistance = clampedEnd * pathMeasure.length
        pathMeasure.getSegment(startDistance, endDistance, displayedWavePath, true)
        pathMeasure.getPosTan(startDistance, startPoint.posVec, startPoint.tanVec)
        pathMeasure.getPosTan(endDistance, endPoint.posVec, endPoint.tanVec)

        transform.reset()
        transform.setTranslate(resultTranslationX, trackCenterY)
        startPoint.translate(resultTranslationX, trackCenterY)
        endPoint.translate(resultTranslationX, trackCenterY)
        if (hasWavyEffect) {
            val scaleY = displayedAmplitude * amplitudeFraction
            transform.postScale(1f, scaleY, resultTranslationX, trackCenterY)
            startPoint.scale(1f, scaleY, trackCenterY)
            endPoint.scale(1f, scaleY, trackCenterY)
        }
        displayedWavePath.transform(transform)
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
        transform.reset()
        transform.setScale(adjustedWavelength / 2f, -2f)
        transform.postTranslate(0f, 1f)
        cachedWavePath.transform(transform)
        pathMeasure.setPath(cachedWavePath, false)
    }

    private fun drawRoundedBlock(
        canvas: Canvas,
        drawCenter: PathPoint,
        drawWidth: Float,
        drawHeight: Float,
        drawCornerSize: Float,
    ) {
        drawRoundedBlock(
            canvas = canvas,
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
        wavePaint.style = Paint.Style.FILL
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
            canvas.drawRect(patchRect, wavePaint)
            canvas.drawRoundRect(drawRect, drawCornerSize, drawCornerSize, wavePaint)
        } else {
            canvas.translate(drawCenter.posVec[0], drawCenter.posVec[1])
            canvas.rotate(vectorToCanvasRotation(drawCenter.tanVec))
            canvas.drawRoundRect(drawRect, drawCornerSize, drawCornerSize, wavePaint)
        }
        canvas.restore()
    }

    private fun vectorToCanvasRotation(vector: FloatArray): Float =
        Math.toDegrees(atan2(vector[1], vector[0]).toDouble()).toFloat()

    private fun calculateStartTrackCornerSize(thumbTrackPosition: Float): Float {
        if (thumbTrackGapSize <= 0) {
            return displayedCornerRadius
        }
        return if (thumbTrackPosition < displayedCornerRadius) {
            max(thumbTrackPosition, displayedInnerCornerRadius)
        } else {
            displayedCornerRadius
        }
    }

    private fun calculateEndTrackCornerSize(trackLength: Float, thumbTrackPosition: Float): Float {
        if (thumbTrackGapSize <= 0) {
            return displayedCornerRadius
        }
        return if (thumbTrackPosition > trackLength - displayedCornerRadius) {
            max(trackLength - thumbTrackPosition, displayedInnerCornerRadius)
        } else {
            displayedCornerRadius
        }
    }

    private fun useStrokeCap(): Boolean {
        val fullyRounded = abs(displayedCornerRadius - displayedTrackThickness / 2f) < EPSILON
        val sameInnerOuter = abs(displayedInnerCornerRadius - displayedCornerRadius) < EPSILON
        return fullyRounded && sameInnerOuter
    }

    private fun applyStartWaveRamp(amplitudeFraction: Float, startRampFraction: Float): Float {
        if (amplitudeFraction <= 0f) {
            return 0f
        }
        val easedRamp = applyRampEasing(startRampFraction)
        return amplitudeFraction * easedRamp
    }

    private fun calculateStartRampFraction(progressFraction: Float, trackLength: Float): Float {
        val progressRamp = calculateProgressRamp(progressFraction)
        if (trackLength <= 0f) {
            return progressRamp
        }
        val thresholdPx = displayedCornerRadius + thumbTrackGapSize
        if (thresholdPx <= 0f) {
            return progressRamp
        }
        val thresholdFraction = (thresholdPx / trackLength).coerceIn(0f, 1f)
        val fullFraction = (thresholdFraction * 2f).coerceIn(0f, 1f)
        if (fullFraction - thresholdFraction <= EPSILON) {
            return progressRamp
        }
        val edgeRamp =
            ((progressFraction - thresholdFraction) / (fullFraction - thresholdFraction)).coerceIn(
                0f,
                1f,
            )
        return min(edgeRamp, progressRamp)
    }

    private fun calculateProgressRamp(progressFraction: Float): Float {
        if (progressFraction <= waveRampProgressMin) {
            return 0f
        }
        val span = (waveRampProgressMax - waveRampProgressMin).coerceAtLeast(EPSILON)
        return ((progressFraction - waveRampProgressMin) / span).coerceIn(0f, 1f)
    }

    private fun applyRampEasing(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    private fun canAnimateWave(): Boolean =
        configuredWavelengthPx > 0 && configuredAmplitudePx > 0 && configuredSpeedPx != 0

    private fun applyWaveGeometryIfConfigured() {
        if (configuredWavelengthPx > 0 && wavelengthDeterminate != configuredWavelengthPx) {
            wavelengthDeterminate = configuredWavelengthPx
        }
        if (configuredAmplitudePx > 0 && waveAmplitude != configuredAmplitudePx) {
            waveAmplitude = configuredAmplitudePx
        }
    }

    private fun transitionToAmplitudeFraction(target: Float, onFinished: (() -> Unit)? = null) {
        val clampedTarget = target.coerceIn(MIN_VISIBLE_WAVE_FRACTION, 1f)
        if (abs(currentAmplitudeFraction - clampedTarget) < EPSILON) {
            currentAmplitudeFraction = clampedTarget
            updateActiveTrackSuppression()
            ensurePhaseTickerState()
            onFinished?.invoke()
            return
        }

        waveTransitionAnimation?.cancel()
        waveTransitionAnimation = null

        val springAnimation =
            SpringAnimation(FloatValueHolder(currentAmplitudeFraction)).apply {
                spring = resolveWaveTransitionSpring(clampedTarget)
                setStartValue(currentAmplitudeFraction)
                setMinimumVisibleChange(MIN_SPRING_VISIBLE_CHANGE)
                addUpdateListener { _, value, _ ->
                    currentAmplitudeFraction = value.coerceIn(MIN_VISIBLE_WAVE_FRACTION, 1f)
                    ensurePhaseTickerState()
                    invalidate()
                }
                addEndListener { _, canceled, value, _ ->
                    if (waveTransitionAnimation === this) {
                        waveTransitionAnimation = null
                    }
                    currentAmplitudeFraction =
                        if (canceled) {
                            value.coerceIn(MIN_VISIBLE_WAVE_FRACTION, 1f)
                        } else {
                            clampedTarget
                        }
                    updateActiveTrackSuppression()
                    ensurePhaseTickerState()
                    invalidate()
                    if (!canceled) {
                        onFinished?.invoke()
                    }
                }
            }

        waveTransitionAnimation = springAnimation
        updateActiveTrackSuppression()
        springAnimation.animateToFinalPosition(clampedTarget)
    }

    private fun ensurePhaseTickerState() {
        if (shouldTickPhase() || waveTransitionAnimation != null) {
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

    private fun shouldTickPhase(): Boolean {
        return shouldDrawWave() &&
            waveSpeed != 0 &&
            visibility == View.VISIBLE &&
            windowVisibility == View.VISIBLE &&
            isShown &&
            alpha > 0f
    }

    private fun updatePhaseFraction() {
        val wavelength = wavelengthDeterminate
        val speed = waveSpeed
        if (wavelength <= 0 || speed == 0) {
            return
        }

        val trackLength = trackWidth.toFloat()
        val range = valueTo - valueFrom
        if (trackLength <= 0f || range <= 0f) {
            return
        }
        val progressFraction = ((value - valueFrom) / range).coerceIn(0f, 1f)
        val phaseSpeedScale =
            applyRampEasing(calculateStartRampFraction(progressFraction, trackLength))
        val effectiveSpeed = speed.toFloat() * phaseSpeedScale
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

    private fun resetPhaseClock() {
        phaseFraction = MIN_PHASE_FRACTION
        lastPhaseFrameNanos = 0L
    }

    private fun clearPhaseClock() {
        phaseFraction = 0f
        lastPhaseFrameNanos = 0L
    }

    private fun resolveWaveTransitionSpring(target: Float): SpringForce {
        val animateOn = target > currentAmplitudeFraction
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

    private fun shouldDrawWave(): Boolean =
        waveAmplitude > 0 &&
            wavelengthDeterminate > 0 &&
            currentAmplitudeFraction > 0f &&
            (waveEnabled || waveTransitionAnimation != null)

    private fun updateActiveTrackSuppression() {
        val suppress =
            configuredAmplitudePx > 0 &&
                configuredWavelengthPx > 0 &&
                (waveEnabled || waveTransitionAnimation != null)
        if (suppress == linearActiveTrackSuppressed) {
            return
        }
        if (suppress) {
            waveTrackTintList = trackActiveTintList
            super.setTrackActiveTintList(transparentTrackTint)
        } else {
            super.setTrackActiveTintList(waveTrackTintList)
        }
        linearActiveTrackSuppressed = suppress
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

        fun scale(sx: Float, sy: Float, pivotY: Float) {
            posVec[0] *= sx
            posVec[1] = (posVec[1] - pivotY) * sy + pivotY
            tanVec[0] *= sx
            tanVec[1] *= sy
        }
    }

    private enum class ActiveTrackDirection {
        LEFT,
        RIGHT,
    }

    private companion object {
        const val WAVE_SMOOTHNESS = 0.48f
        const val MIN_SPRING_VISIBLE_CHANGE = 0.001f
        const val DEFAULT_WAVE_RAMP_PROGRESS_MAX = 0.03f
        const val MIN_VISIBLE_WAVE_FRACTION = 0.001f
        const val MIN_PHASE_FRACTION = 0.0001f
        const val EPSILON = 0.0001f

        fun clamp(value: Float, min: Float, max: Float): Float {
            return when {
                value < min -> min
                value > max -> max
                else -> value
            }
        }
    }
}
