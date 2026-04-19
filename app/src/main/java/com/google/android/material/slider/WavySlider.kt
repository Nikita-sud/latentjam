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
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.Px
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import com.google.android.material.R as MR
import com.google.android.material.wave.PathPoint
import com.google.android.material.wave.RoundedBlockRenderer
import com.google.android.material.wave.WaveMotionUtils
import com.google.android.material.wave.WavePathCache
import com.google.android.material.wave.applyRampEasing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Slider with active-track wave rendering that ports MDC LinearProgressIndicator's wavy draw
 * behavior and keeps phase/amplitude transition behavior aligned with
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

    private val wavePathCache = WavePathCache()
    private val roundedBlockRenderer = RoundedBlockRenderer()

    private val startPoint = PathPoint()
    private val endPoint = PathPoint()

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
                wavePathCache.markDirty()
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
        if (wavePathCache.pathMeasure.length <= 0f || wavePathCache.adjustedWavelength <= 0f) {
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
            canvas.drawPath(wavePathCache.displayedWavePath, wavePaint)
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
        wavePathCache.calculateDisplayedWavePath(
            trackLength = trackLength,
            start = start,
            end = end,
            amplitudeFraction = amplitudeFraction,
            phaseFraction = phaseFraction,
            displayedAmplitude = displayedAmplitude,
            startPoint = startPoint,
            endPoint = endPoint,
            baseTranslationX = trackSidePadding.toFloat(),
            baseTranslationY = trackCenterY,
            scaleAroundPivotY = true,
            clampSegmentFractions = true,
            epsilon = EPSILON,
        )
    }

    private fun ensureCachedWavePath(trackLength: Float, wavelength: Int) {
        wavePathCache.ensureCachedWavePath(trackLength, wavelength)
    }

    private fun invalidateCachedWavePath(trackLength: Float, wavelength: Int) {
        wavePathCache.invalidateCachedWavePath(trackLength, wavelength)
    }

    private fun drawRoundedBlock(
        canvas: Canvas,
        drawCenter: PathPoint,
        drawWidth: Float,
        drawHeight: Float,
        drawCornerSize: Float,
    ) {
        roundedBlockRenderer.drawRoundedBlock(
            canvas = canvas,
            paint = wavePaint,
            drawCenter = drawCenter,
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            displayedTrackThickness = displayedTrackThickness,
            drawCornerSize = drawCornerSize,
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
        roundedBlockRenderer.drawRoundedBlock(
            canvas = canvas,
            paint = wavePaint,
            drawCenter = drawCenter,
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            displayedTrackThickness = displayedTrackThickness,
            drawCornerSize = drawCornerSize,
            clipCenter = clipCenter,
            clipWidth = clipWidth,
            clipHeight = clipHeight,
            clipCornerSize = clipCornerSize,
            clipRight = clipRight,
        )
    }

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
                spring =
                    WaveMotionUtils.resolveWaveTransitionSpring(
                        context = context,
                        animateOn = clampedTarget > currentAmplitudeFraction,
                    )
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

    private enum class ActiveTrackDirection {
        LEFT,
        RIGHT,
    }

    private companion object {
        const val MIN_SPRING_VISIBLE_CHANGE = 0.001f
        const val DEFAULT_WAVE_RAMP_PROGRESS_MAX = 0.03f
        const val MIN_VISIBLE_WAVE_FRACTION = 0.001f
        const val MIN_PHASE_FRACTION = 0.0001f
        const val EPSILON = 0.0001f
    }
}
