/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fade entry (experimental, off by default): when the outgoing track fades out on its own, the next
 * track rises under that fade instead of the two meeting in a fixed-length crossfade. The outgoing
 * track keeps its own level — its fade does the leaving — and the incoming one rises over the
 * whole fade, from where it starts to the outgoing track's last sound.
 *
 * Where the fade starts is the one open choice (it waits for a listening round), so both readings
 * are measured and kept, and [FadeEntryRule] picks one at the seam:
 * - [FadeEntryRule.KNEE]: where the level curve bends into the fade;
 * - [FadeEntryRule.VISIBLE]: where it has fallen 3 dB and stays below for good.
 *
 * A track whose end is not a clean fade — a cold end, a dip and a recovery, a fade that comes back —
 * gets no fade entry and its seams keep the usual crossfade.
 */
public enum class FadeEntryRule { KNEE, VISIBLE }

/**
 * A track's measured ending: [lastSoundMs], its last sound on the fade-entry scale (mono, 50 ms
 * windows), and how long before it each rule's fade starts; null where that rule refused the end.
 */
public data class FadeEnd(
    public val lastSoundMs: Long,
    public val kneeLeadMs: Long?,
    public val visibleLeadMs: Long?,
) {
    public fun leadMs(rule: FadeEntryRule): Long? = when (rule) {
        FadeEntryRule.KNEE -> kneeLeadMs
        FadeEntryRule.VISIBLE -> visibleLeadMs
    }

    public fun isValid(): Boolean = lastSoundMs > 0L &&
        (kneeLeadMs == null || kneeLeadMs in 1L..lastSoundMs) &&
        (visibleLeadMs == null || visibleLeadMs in 1L..lastSoundMs)
}

/** Why a track's end gets no fade entry, or [NONE] when it does. */
internal enum class FadeRefusal { NONE, SHORT, WINDOW, NO_DECLINE, RECOVERS, COMES_BACK, SHALLOW }

/** The fade start of [fadeStart]: [startS] on the curve's clock, or a [refusal]. */
internal data class FadeStart(val startS: Double?, val refusal: FadeRefusal)

/**
 * Where a track's ending fade starts, from its level curve: [rmsDb], 0.4 s RMS windows every
 * [CURVE_HOP_S] beginning [rmsStartS] (window i is centred at rmsStartS + 0.2 + i·hop), up to the
 * track's last sound [lastS].
 *
 * Steps: a 3 s running median; the last 60 s, every 0.5 s, ended where the curve settles into its
 * final floor; the knee of the better of a dB and an amplitude hinge fit, moved later to the last
 * point still on the plateau before the point of no return; then three guards — no recovery of
 * more than 6 dB after the knee (one refit from the recovery), no return to within 3 dB of the
 * track's usual level once 6 dB below it, and at least 9 dB of fall to the end.
 */
internal fun fadeStart(rmsDb: FloatArray, rmsStartS: Double, lastS: Double, rule: FadeEntryRule): FadeStart {
    // The smoothed curve over the last 90 s.
    val allT = DoubleArray(rmsDb.size) { rmsStartS + 0.2 + it * CURVE_HOP_S }
    val keep = allT.indices.filter { allT[it] >= lastS - 90.0 && allT[it] <= lastS }
    if (keep.size < 50) return FadeStart(null, FadeRefusal.SHORT)
    val tt = DoubleArray(keep.size) { allT[keep[it]] }
    val x = DoubleArray(keep.size) { rmsDb[keep[it]].toDouble() }
    val half = (MEDIAN_S / CURVE_HOP_S).roundToInt() / 2
    val sm = DoubleArray(x.size) { i -> median(x, max(0, i - half), minOf(x.size, i + half + 1)) }

    // The track's usual level: the 90th percentile, leaving out the last 15 s.
    val body = tt.indices.filter { tt[it] <= lastS - REF_EXCLUDE_END_S }
    val ref = percentile(if (body.size >= 10) DoubleArray(body.size) { sm[body[it]] } else sm, REF_PERCENTILE)
    // The level the track ends on: the median of its last second.
    val endLevel = median(sm.filterIndexed { i, _ -> tt[i] >= lastS - 1.0 }.toDoubleArray())

    // The fitted window: the last 60 s, every 0.5 s.
    val step = (FIT_HOP_S / CURVE_HOP_S).roundToInt()
    val window = tt.indices.filter { tt[it] >= lastS - FIT_WINDOW_S }.filterIndexed { j, _ -> j % step == 0 }
    var t = DoubleArray(window.size) { tt[window[it]] }
    var c = DoubleArray(window.size) { sm[window[it]] }
    if (t.size > 6) {
        // A fade into a floor: the fit ends where the curve settles within 6 dB of its end level.
        val lastAbove = c.indices.lastOrNull { c[it] > endLevel + FLOOR_DB }
        if (lastAbove != null && lastAbove + 2 < t.size) {
            t = t.copyOf(lastAbove + 2)
            c = c.copyOf(lastAbove + 2)
        }
    }
    if (t.size < 6) return FadeStart(null, FadeRefusal.WINDOW)

    var k = 0.0
    for (attempt in 0..1) {
        k = knee(t, c) ?: return FadeStart(null, FadeRefusal.NO_DECLINE)
        val after = t.indices.filter { t[it] >= k }
        var low = Double.POSITIVE_INFINITY
        var bestRise = Double.NEGATIVE_INFINITY
        var bestAt = 0
        after.forEachIndexed { j, i ->
            low = minOf(low, c[i])
            val rise = c[i] - low
            if (rise > bestRise) {
                bestRise = rise
                bestAt = j
            }
        }
        if (bestRise > RECOVERY_DB) {
            // A recovery after the knee: the knee sat in a dip. Fit once more from the recovery's peak.
            if (attempt == 0) {
                val from = t[after[bestAt]]
                val kept = t.indices.filter { t[it] >= from }
                if (kept.size >= 6) {
                    val keptT = DoubleArray(kept.size) { t[kept[it]] }
                    c = DoubleArray(kept.size) { c[kept[it]] }
                    t = keptT
                    continue
                }
            }
            return FadeStart(null, FadeRefusal.RECOVERS)
        }
        break
    }
    // Once 6 dB below the usual level after the knee, the curve never comes back to within 3 dB of it.
    val firstLow = t.indices.firstOrNull { t[it] > k && c[it] <= ref - 6.0 }
    if (firstLow != null && (firstLow until c.size).any { c[it] >= ref - COME_BACK_DB }) {
        return FadeStart(null, FadeRefusal.COMES_BACK)
    }
    val atKnee = c[t.indices.minBy { abs(t[it] - k) }]
    if (atKnee - endLevel < MIN_FALL_DB) return FadeStart(null, FadeRefusal.SHALLOW)
    if (rule == FadeEntryRule.VISIBLE) {
        // The first point after which the curve stays 3 dB below the plateau to the end.
        val plateau = plateauBefore(t, c, k)
        var i = t.size
        while (i > 0 && c[i - 1] < plateau - VISIBLE_DB) i--
        if (i < t.size && t[i] >= k) k = t[i]
    }
    return FadeStart(k, FadeRefusal.NONE)
}

/**
 * The knee: the better (lower squared error in dB) of a dB and an amplitude hinge, then moved later
 * to the last point within [PLATEAU_DB] of the plateau before the point of no return — after which
 * the curve stays more than [NO_RETURN_DB] below the plateau to the end. Null when nothing declines.
 */
private fun knee(t: DoubleArray, c: DoubleArray): Double? {
    val (kd, sd) = hingeKnee(t, c, amplitude = false)
    val (ka, sa) = hingeKnee(t, c, amplitude = true)
    if (kd == null && ka == null) return null
    val k = if (ka == null || (kd != null && sd <= sa)) kd!! else ka
    val plateau = plateauBefore(t, c, k)
    var noReturn: Int? = null
    for (i in t.indices.reversed()) {
        if (c[i] >= plateau - NO_RETURN_DB) break
        noReturn = i
    }
    if (noReturn == null || t[noReturn] < k) return k
    val onPlateau = t.indices.filter { t[it] >= k && t[it] <= t[noReturn] && c[it] >= plateau - PLATEAU_DB }
    return if (onPlateau.isEmpty()) k else onPlateau.maxOf { t[it] }
}

/** The median level over the 10 s before [k], or the window's first level when there is none. */
private fun plateauBefore(t: DoubleArray, c: DoubleArray, k: Double): Double {
    val pre = t.indices.filter { t[it] >= k - 10.0 && t[it] < k }
    return if (pre.isEmpty()) c[0] else median(DoubleArray(pre.size) { c[pre[it]] })
}

/**
 * The continuous hinge y ≈ a + s·max(0, t − k) with s < 0 that fits best, over knees at least
 * [MIN_TAIL_S] before the window's end; y is the curve in dB or as amplitude. The error is
 * measured in dB either way, so the two fits compare on one scale. Ties keep the earliest knee.
 */
private fun hingeKnee(t: DoubleArray, c: DoubleArray, amplitude: Boolean): Pair<Double?, Double> {
    val y = if (amplitude) DoubleArray(c.size) { 10.0.pow(c[it] / 20.0) } else c
    val n = t.size
    var best = Double.POSITIVE_INFINITY
    var bestK: Double? = null
    val end = t[n - 1] - MIN_TAIL_S
    for (k in t) {
        if (k > end) continue
        // Least squares on [1, z] with z = max(0, t − k).
        var sz = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            sz += max(0.0, t[i] - k)
            sy += y[i]
        }
        val mz = sz / n
        val my = sy / n
        var szz = 0.0
        var szy = 0.0
        for (i in 0 until n) {
            val dz = max(0.0, t[i] - k) - mz
            szz += dz * dz
            szy += dz * (y[i] - my)
        }
        if (szz <= 0.0) continue
        val slope = szy / szz
        if (slope >= 0.0) continue
        val intercept = my - slope * mz
        var error = 0.0
        for (i in 0 until n) {
            val p = intercept + slope * max(0.0, t[i] - k)
            val pDb = if (amplitude) 20.0 * log10(max(p, 1e-4)) else p
            val r = c[i] - max(pDb, -80.0)
            error += r * r
        }
        if (error < best - 1e-9) {
            best = error
            bestK = k
        }
    }
    return bestK to best
}

/** numpy's median of [from, to) of [v]: the middle value, or the mean of the two middle ones. */
private fun median(v: DoubleArray, from: Int = 0, to: Int = v.size): Double {
    val s = v.copyOfRange(from, to).also { it.sort() }
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
}

/** numpy's default (linear) percentile [q] of [v]. */
private fun percentile(v: DoubleArray, q: Double): Double {
    val s = v.copyOf().also { it.sort() }
    val pos = (s.size - 1) * q / 100.0
    val lo = pos.toInt()
    val hi = minOf(lo + 1, s.size - 1)
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)
}

/**
 * The seam a fade entry gives, or null to keep the usual crossfade: the overlap starts [overlapMs]
 * before the outgoing track's last sound ([outgoingEndMs], from the seam anchors), at the rule's
 * fade start — but never more than [FADE_ENTRY_MAX_MS] before it, nor more than a third of the
 * incoming track. The outgoing track keeps its own level through it.
 */
internal fun fadeEntryOverlapMs(fade: FadeEnd?, rule: FadeEntryRule, outgoingEndMs: Long, incomingMs: Long): Long? {
    val lead = fade?.takeIf { it.isValid() }?.leadMs(rule) ?: return null
    if (outgoingEndMs <= 0L || incomingMs <= 0L) return null
    val overlap = minOf(lead, FADE_ENTRY_MAX_MS, incomingMs / 3L, outgoingEndMs / 3L)
    return overlap.takeIf { it >= FADE_ENTRY_MIN_MS }
}

/**
 * Collects a track's ending for [fadeStart] from the PCM the full-track scan already decodes: mono
 * mean squares in 50 ms blocks, the last [TAIL_KEEP_S] kept. [finish] finds the last sound — the
 * last 50 ms block above −50 dBFS, its trailing silence trimmed only from 0.3 to 30 s, with a 0.1 s
 * guard — then builds the 0.4 s/0.1 s level curve over the 90 s before it and measures both rules.
 */
internal class FadeEndTracker(sampleRate: Int, private val channels: Int) {
    private val blockFrames = (sampleRate.toLong() * BLOCK_MS / 1000L).coerceAtLeast(1L)
    private val keepBlocks = (TAIL_KEEP_S * 1000L / BLOCK_MS).toInt()
    private val ring = DoubleArray(keepBlocks)
    private var blocks = 0L
    private var framesInBlock = 0L
    private var sumInBlock = 0.0
    private var frames = 0L
    private val rate = sampleRate

    fun add(samples: FloatArray, frameCount: Int) {
        var index = 0
        repeat(frameCount) {
            var mono = 0.0
            repeat(channels) { mono += samples[index++] }
            mono /= channels
            sumInBlock += mono * mono
            frames++
            if (++framesInBlock == blockFrames) closeBlock()
        }
    }

    private fun closeBlock() {
        ring[(blocks % keepBlocks).toInt()] = sumInBlock
        blocks++
        framesInBlock = 0L
        sumInBlock = 0.0
    }

    fun finish(): FadeEnd? {
        // The partial block at the end is left out, as the research rule leaves out a partial window.
        if (blocks == 0L) return null
        val durationS = frames.toDouble() / rate
        val first = maxOf(0L, blocks - keepBlocks)
        fun block(b: Long): Double = ring[(b % keepBlocks).toInt()]
        val threshold = 10.0.pow(SOUND_THRESHOLD_DBFS / 10.0) * blockFrames
        val loud = (blocks - 1 downTo first).firstOrNull { block(it) > threshold } ?: return null
        val endS = (loud + 1).toDouble() * BLOCK_MS / 1000.0
        val trail = durationS - (endS + SOUND_EDGE_GUARD_MS / 1000.0)
        val lastS = if (trail in 0.3..30.0) durationS - trail else durationS
        val fromS = maxOf(first.toDouble() * BLOCK_MS / 1000.0, lastS - 90.0)
        // 0.4 s windows every 0.1 s from fromS (on the 50 ms block grid) up to lastS.
        val startBlock = (fromS * 1000.0 / BLOCK_MS).roundToInt().toLong().coerceAtLeast(first)
        val endBlock = minOf(blocks, (lastS * 1000.0 / BLOCK_MS).toLong())
        val windowBlocks = (CURVE_WINDOW_S * 1000.0 / BLOCK_MS).roundToInt()
        val hopBlocks = (CURVE_HOP_S * 1000.0 / BLOCK_MS).roundToInt()
        val count = if (endBlock - startBlock < windowBlocks) 0 else 1 + ((endBlock - startBlock - windowBlocks) / hopBlocks).toInt()
        if (count <= 0) return FadeEnd(msOf(lastS), null, null)
        val curve = FloatArray(count) { i ->
            var sum = 0.0
            val b0 = startBlock + i.toLong() * hopBlocks
            for (b in b0 until b0 + windowBlocks) sum += block(b)
            (20.0 * log10(sqrt(sum / (windowBlocks * blockFrames)) + 1e-12)).toFloat()
        }
        val curveStartS = startBlock.toDouble() * BLOCK_MS / 1000.0
        fun lead(rule: FadeEntryRule): Long? =
            fadeStart(curve, curveStartS, lastS, rule).startS?.let { msOf(lastS) - msOf(it) }?.takeIf { it > 0L }
        return FadeEnd(msOf(lastS), lead(FadeEntryRule.KNEE), lead(FadeEntryRule.VISIBLE))
    }

    private fun msOf(seconds: Double): Long = (seconds * 1000.0).roundToInt().toLong()
}

/** The level curve's step and window. */
internal const val CURVE_HOP_S: Double = 0.1
private const val CURVE_WINDOW_S: Double = 0.4
private const val BLOCK_MS: Long = 50L
private const val TAIL_KEEP_S: Long = 120L
private const val MEDIAN_S: Double = 3.0
private const val REF_PERCENTILE: Double = 90.0
private const val REF_EXCLUDE_END_S: Double = 15.0
private const val FIT_WINDOW_S: Double = 60.0
private const val FIT_HOP_S: Double = 0.5
private const val MIN_TAIL_S: Double = 2.0
private const val RECOVERY_DB: Double = 6.0
private const val COME_BACK_DB: Double = 3.0
private const val MIN_FALL_DB: Double = 9.0
private const val PLATEAU_DB: Double = 1.5
private const val NO_RETURN_DB: Double = 4.0
private const val VISIBLE_DB: Double = 3.0
private const val FLOOR_DB: Double = 6.0

/** A fade entry reaches at most this far before the outgoing track's last sound. */
internal const val FADE_ENTRY_MAX_MS: Long = 20_000L

/** Shorter than this, a fade entry is no different from a crossfade: the seam keeps the crossfade. */
internal const val FADE_ENTRY_MIN_MS: Long = 1_000L
