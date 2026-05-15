/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * HistoryFeatureBuilder.kt is part of LatentJam.
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
package io.github.nikitasud.latentjam.ml.predictor

import io.github.nikitasud.latentjam.ml.MlSettings
import io.github.nikitasud.latentjam.ml.data.ListeningEventDao
import io.github.nikitasud.latentjam.ml.data.ListeningEventEntity
import io.github.nikitasud.latentjam.ml.data.TrackEmbeddingDao
import io.github.nikitasud.latentjam.ml.session.SessionTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.GregorianCalendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import org.oxycblt.musikr.Music

/**
 * Build the five feature tensors the state encoder consumes, mirroring the recipe at
 * `latentjam-research/src/predictor/train_history.py`:
 * - history_small (1, K, D[+1]) — last K=4 completed plays as embeddings (+ played_pct when
 *   use_played_pct=True; scoring_v1 uses K=4, D=512).
 * - history_medium (1, D) — sum_i emb_i * exp(-days_ago_i / 30) / sum_i weight_i
 * - history_large (1, D) — sum_i emb_i * weight_i / sum_i weight_i where the weight = freq_count /
 *   (1 + days_ago/365)
 * - time_features (1, 5) — [sin(h*2π/24), cos(...), sin(dow*2π/7), cos(...), is_weekend]
 * - session_features (1, sessionFeatDim)
 */
@Singleton
class HistoryFeatureBuilder
@Inject
constructor(
    private val listeningEventDao: ListeningEventDao,
    private val trackEmbeddingDao: TrackEmbeddingDao,
    private val sessionTracker: SessionTracker,
    private val mlSettings: MlSettings,
) {

    suspend fun build(
        nowMs: Long,
        sessionId: String,
        embeddingDim: Int,
        historyTokenDim: Int,
        sessionFeatDim: Int,
    ): PredictorFeatures {
        val version = mlSettings.embeddingVersion
        // Prefer the in-memory ring buffer maintained by SessionTracker so Smart shuffle
        // works without the user having to opt in to persistent event logging. Fall back
        // to the DAO when the ring is empty (e.g. early in a session before the recorder
        // has finalized any completed plays — usually means we're still in cold-start anyway).
        val inMemory = sessionTracker.recentCompletedSnapshot()
        val historySmall =
            if (inMemory.isNotEmpty()) {
                buildHistorySmallFromMemory(
                    inMemory,
                    embeddingDim = embeddingDim,
                    tokenDim = historyTokenDim,
                    version = version,
                )
            } else {
                // Fresh app start with no in-memory history yet: pull the most recent completed
                // plays across ALL sessions, not just the current one. Smart shuffle then "warms
                // up" instantly using past listening rather than waiting for 4 fresh completions.
                // Prefer the same-session events first so the order matches the in-memory ring's
                // semantics (newest first within the active session); fall back to cross-session
                // history when the current session is empty.
                val sameSession = listeningEventDao.recentCompletedInSession(sessionId, CONTEXT_K)
                val recent =
                    if (sameSession.size >= CONTEXT_K) {
                        sameSession
                    } else {
                        listeningEventDao.recentCompleted(CONTEXT_K)
                    }
                buildHistorySmall(
                    recent,
                    embeddingDim = embeddingDim,
                    tokenDim = historyTokenDim,
                    version = version,
                )
            }

        val mediumWindowMs = MEDIUM_WINDOW_DAYS * MS_PER_DAY
        val mediumEvents =
            listeningEventDao.eventsSince(nowMs - mediumWindowMs).filter { it.completed }
        val historyMedium =
            recencyWeightedCentroid(
                events = mediumEvents,
                nowMs = nowMs,
                decayDays = MEDIUM_DECAY_DAYS,
                embeddingDim = embeddingDim,
                version = version,
            )

        val largeWindowMs = LARGE_WINDOW_DAYS * MS_PER_DAY
        val largeEvents =
            listeningEventDao.eventsSince(nowMs - largeWindowMs).filter { it.completed }
        val historyLarge =
            recencyAndFrequencyCentroid(
                events = largeEvents,
                nowMs = nowMs,
                embeddingDim = embeddingDim,
                version = version,
            )

        val timeFeatures = computeTimeFeatures(nowMs)

        val sessionSnapshot = sessionTracker.snapshot(nowMs)
        val sessionFeatures = sessionSnapshot.features.copyOf(sessionFeatDim)

        return PredictorFeatures(
            historySmall = historySmall,
            historyMedium = historyMedium,
            historyLarge = historyLarge,
            timeFeatures = timeFeatures,
            sessionFeatures = sessionFeatures,
        )
    }

    private suspend fun buildHistorySmall(
        recent: List<ListeningEventEntity>,
        embeddingDim: Int,
        tokenDim: Int,
        version: String,
    ): FloatArray {
        val out = FloatArray(CONTEXT_K * tokenDim)
        // Position 0 = most recent. recent is already ordered newest-first by the DAO.
        for ((i, event) in recent.take(CONTEXT_K).withIndex()) {
            val embEntity = trackEmbeddingDao.get(event.songUid, version) ?: continue
            val embedding = bytesToFloats(embEntity.embedding, embeddingDim)
            val rowOffset = i * tokenDim
            System.arraycopy(embedding, 0, out, rowOffset, embeddingDim)
            if (tokenDim > embeddingDim) {
                val playedPct =
                    if (event.trackDurationMs > 0) {
                        event.playedMs.toFloat() / event.trackDurationMs.toFloat()
                    } else {
                        0f
                    }
                out[rowOffset + embeddingDim] = playedPct.coerceIn(0f, 1f)
            }
        }
        return out
    }

    private suspend fun buildHistorySmallFromMemory(
        recent: List<io.github.nikitasud.latentjam.ml.session.SessionTracker.CompletedPlay>,
        embeddingDim: Int,
        tokenDim: Int,
        version: String,
    ): FloatArray {
        val out = FloatArray(CONTEXT_K * tokenDim)
        for ((i, play) in recent.take(CONTEXT_K).withIndex()) {
            val embEntity = trackEmbeddingDao.get(play.uid, version) ?: continue
            val embedding = bytesToFloats(embEntity.embedding, embeddingDim)
            val rowOffset = i * tokenDim
            System.arraycopy(embedding, 0, out, rowOffset, embeddingDim)
            if (tokenDim > embeddingDim) {
                out[rowOffset + embeddingDim] = play.playedPct.coerceIn(0f, 1f)
            }
        }
        return out
    }

    private suspend fun recencyWeightedCentroid(
        events: List<ListeningEventEntity>,
        nowMs: Long,
        decayDays: Double,
        embeddingDim: Int,
        version: String,
    ): FloatArray {
        if (events.isEmpty()) return FloatArray(embeddingDim)
        val acc = DoubleArray(embeddingDim)
        var weightSum = 0.0
        for (event in events) {
            val emb = trackEmbeddingDao.get(event.songUid, version) ?: continue
            val embedding = bytesToFloats(emb.embedding, embeddingDim)
            val daysAgo = (nowMs - event.endedAtMs).coerceAtLeast(0L) / MS_PER_DAY.toDouble()
            val weight = exp(-daysAgo / decayDays)
            weightSum += weight
            for (k in 0 until embeddingDim) {
                acc[k] += embedding[k] * weight
            }
        }
        if (weightSum <= 0.0) return FloatArray(embeddingDim)
        val out = FloatArray(embeddingDim)
        val inv = 1.0 / weightSum
        for (k in 0 until embeddingDim) out[k] = (acc[k] * inv).toFloat()
        return l2Normalize(out)
    }

    private suspend fun recencyAndFrequencyCentroid(
        events: List<ListeningEventEntity>,
        nowMs: Long,
        embeddingDim: Int,
        version: String,
    ): FloatArray {
        if (events.isEmpty()) return FloatArray(embeddingDim)
        // Aggregate per track, mimicking train_history.py: log-decay weight by recency
        // multiplied by play count.
        val freq = HashMap<Music.UID, Int>()
        val mostRecent = HashMap<Music.UID, Long>()
        for (event in events) {
            freq[event.songUid] = (freq[event.songUid] ?: 0) + 1
            val current = mostRecent[event.songUid] ?: Long.MIN_VALUE
            if (event.endedAtMs > current) mostRecent[event.songUid] = event.endedAtMs
        }
        val acc = DoubleArray(embeddingDim)
        var weightSum = 0.0
        for ((uid, count) in freq) {
            val emb = trackEmbeddingDao.get(uid, version) ?: continue
            val embedding = bytesToFloats(emb.embedding, embeddingDim)
            val daysAgo =
                (nowMs - (mostRecent[uid] ?: nowMs)).coerceAtLeast(0L) / MS_PER_DAY.toDouble()
            val weight = count.toDouble() / (1.0 + daysAgo / LARGE_DECAY_DAYS)
            weightSum += weight
            for (k in 0 until embeddingDim) {
                acc[k] += embedding[k] * weight
            }
        }
        if (weightSum <= 0.0) return FloatArray(embeddingDim)
        val out = FloatArray(embeddingDim)
        val inv = 1.0 / weightSum
        for (k in 0 until embeddingDim) out[k] = (acc[k] * inv).toFloat()
        return l2Normalize(out)
    }

    /**
     * Build a [PredictorFeatures] suitable for a one-shot dry-run pipeline call.
     *
     * The dry-run sweep picks random library tracks as seeds — there is no session history at that
     * point, so the normal [build] returns a history_small full of zeros and `hasHistorySmall()` is
     * false, which makes the predictor pipeline silently skip itself. To actually exercise the
     * predictor on those seeds, we synthesise a one-track history: position 0 is the seed embedding
     * (completed at 100 %), positions 1..K-1 stay zero.
     *
     * Time + session features default to the current real wall-clock time and a fresh session at
     * position 0.
     */
    fun buildForDryRun(
        seedEmbedding: FloatArray,
        nowMs: Long,
        embeddingDim: Int,
        historyTokenDim: Int,
        sessionFeatDim: Int,
    ): PredictorFeatures {
        require(seedEmbedding.size == embeddingDim) {
            "expected ${embeddingDim}-d seed, got ${seedEmbedding.size}"
        }
        // Training replicates the most-recent track into all K history slots
        // when the session only has 1 prior event (see _build_pairs_for_user in
        // train_history.py). Match that here — leaving slots 1..K-1 at zero is
        // far outside the training distribution and causes the scorer to
        // collapse to popularity priors regardless of the seed.
        val historySmall = FloatArray(CONTEXT_K * historyTokenDim)
        for (k in 0 until CONTEXT_K) {
            val off = k * historyTokenDim
            System.arraycopy(seedEmbedding, 0, historySmall, off, embeddingDim)
            if (historyTokenDim > embeddingDim) {
                historySmall[off + embeddingDim] = 1f // played_pct = 100 %
            }
        }
        // historyMedium / historyLarge: best-available taste proxy at cold start
        // is the seed itself. Zero vectors are OOD vs training, which always
        // L2-normalized centroids into these slots.
        return PredictorFeatures(
            historySmall = historySmall,
            historyMedium = seedEmbedding.copyOf(),
            historyLarge = seedEmbedding.copyOf(),
            timeFeatures = computeTimeFeatures(nowMs),
            sessionFeatures =
                FloatArray(sessionFeatDim).also {
                    val src =
                        floatArrayOf(
                            ln(2f), // log1p(session_pos=1)
                            0f, // last_skipped
                            ln(1.5f), // log1p(~30 s in session)
                            1f, // comp_rate
                            1f, // mean_pct (seed assumed completed)
                        )
                    System.arraycopy(src, 0, it, 0, minOf(src.size, it.size))
                },
        )
    }

    private fun computeTimeFeatures(nowMs: Long): FloatArray {
        val cal = GregorianCalendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        // Calendar.DAY_OF_WEEK: Sunday=1..Saturday=7 → convert to Monday=0..Sunday=6
        // to match Python's pandas.weekday convention used in training.
        val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val isWeekend = if (dow >= 5) 1f else 0f
        val twoPi = 2.0 * PI
        return floatArrayOf(
            sin(twoPi * hour / 24.0).toFloat(),
            cos(twoPi * hour / 24.0).toFloat(),
            sin(twoPi * dow / 7.0).toFloat(),
            cos(twoPi * dow / 7.0).toFloat(),
            isWeekend,
        )
    }

    companion object {
        const val CONTEXT_K = 4
        const val MEDIUM_WINDOW_DAYS = 30L
        const val MEDIUM_DECAY_DAYS = 30.0
        const val LARGE_WINDOW_DAYS = 365L
        const val LARGE_DECAY_DAYS = 365.0
        const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

internal fun bytesToFloats(bytes: ByteArray, expectedDim: Int): FloatArray {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(expectedDim)
    var i = 0
    while (i < expectedDim && buf.remaining() >= 4) {
        out[i] = buf.float
        i++
    }
    return out
}

internal fun l2Normalize(v: FloatArray): FloatArray {
    var sumSq = 0.0
    for (x in v) sumSq += x.toDouble() * x.toDouble()
    if (sumSq <= 0.0) return v
    val inv = (1.0 / sqrt(sumSq)).toFloat()
    for (k in v.indices) v[k] *= inv
    return v
}
