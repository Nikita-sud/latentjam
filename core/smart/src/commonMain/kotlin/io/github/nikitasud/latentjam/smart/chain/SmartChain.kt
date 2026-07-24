/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

/** Every tuned constant of the chain, in one place. */
internal object ChainConfig {

    /** Bounds the scorer's vote so it can shape ordering but never veto the user's pick. */
    const val SCORER_SQUASH = 1.5f
    const val SCORER_TEMP = 2.0f

    /** Local coherence toward the previous pick. */
    const val COSINE_BLEND_WEIGHT = 3.0f

    /** Pull toward the seed for the whole walk, so one off-genre hop can't capture the chain. */
    const val CHAIN_SEED_GRAVITY = 2.5f

    /**
     * Semantic gravity/blend: the descriptor+text analogue of [CHAIN_SEED_GRAVITY] and
     * [COSINE_BLEND_WEIGHT], measured as pool-relative z-scores (see [SemanticZ]) rather than raw
     * cosine so a niche seed's true in-cluster candidates — semantic outliers within the retrieved
     * pool that the audio scorer parks near its tanh floor — are lifted exactly where the audio
     * geometry cannot see them. Seed gravity dominates the blend, same shape as the audio terms.
     */
    const val SEM_CHAIN_SEED_GRAVITY = 2.0f
    const val SEM_CHAIN_PREV_BLEND = 1.0f

    const val HUB_CHAIN_DAMP = 0.6f
    const val HUB_PENALTY_BETA = 1.0f

    const val CHAIN_ARTIST_SPACING = 3
    const val CHAIN_ARTIST_QUEUE_CAP = 3

    const val ENERGY_DEADBAND = 0.2f
    const val ENERGY_FLOOR = 0.7f

    const val MULTIPLIER_MIN = 0.05f
    const val MULTIPLIER_MAX = 2.0f
}

/**
 * A built queue plus the candidate pool it was drawn from.
 *
 * @param rows snapshot rows to play, in order
 * @param pool the rows considered, in retrieval order — kept for diagnostics and parity checks
 */
internal data class ChainResult(val rows: List<Int>, val pool: List<Int>) {
    companion object {
        val EMPTY = ChainResult(emptyList(), emptyList())
    }
}

/**
 * Builds a SMART queue: a coherent walk through the library starting from the user's pick.
 *
 * The shape of the score is the whole design. The learned scorer is squashed into a bounded band
 * because raw logits span ±5 while the cosine terms are bounded by ~±3 — unbounded, the model's
 * taste vote simply outshouted the user's explicit choice. Around it sit local coherence (toward
 * the previous pick), seed gravity (toward the original pick, for the whole walk), and metadata
 * multipliers applied in log space so a neutral verdict contributes exactly zero. Text is optional
 * scorer conditioning; it is not added again through a hand-tuned score weight.
 *
 * The runtime is optional: without it the chain still walks on its geometric terms alone, which is
 * how iOS and any pre-download state behave.
 */
internal class SmartChain(
    private val snapshot: SmartSnapshot,
    private val runtime: PredictorRuntime?,
    /** Rows allowed in the output; history-only rows may inform state without becoming candidates. */
    private val eligibleRows: BooleanArray = BooleanArray(snapshot.size) { true },
) {

    init {
        require(eligibleRows.size == snapshot.size) { "eligible row mask must match snapshot" }
    }

    /**
     * @param seedId the track the user picked
     * @param length how many tracks to queue after the seed
     * @param timeFeatures from [PredictorRuntime.timeFeatures] on the real clock
     */
    fun build(
        seedId: TrackId,
        length: Int,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray = PredictorRuntime.SESSION_FEATURES_COLD,
        historyEvents: List<SmartHistoryEvent> = emptyList(),
    ): ChainResult {
        val seedRow = snapshot.rowOf(seedId)
        if (seedRow < 0 || length <= 0) return ChainResult.EMPTY

        val dim = PredictorRuntime.STATE_DIM
        val tokenDim = PredictorRuntime.TOKEN_DIM

        val context = prepareContext(seedRow, historyEvents, sessionFeatures)
        val history = context.history
        val medium = context.medium
        val large = context.large
        val liveSessionFeatures = context.session

        var live = runtime != null
        var state = if (live) {
            runCatching { encode(history, medium, large, timeFeatures, liveSessionFeatures) }
                .onFailure { live = false }
                .getOrDefault(FloatArray(0))
        } else {
            FloatArray(0)
        }

        val pool = buildPool(seedRow, state)
        if (pool.isEmpty()) return ChainResult.EMPTY
        // Short pools stay zero-padded: the scorer graph has the pool size baked in.
        val candidates = FloatArray(PredictorRuntime.POOL_SIZE * dim)
        val textCandidates = FloatArray(PredictorRuntime.POOL_SIZE * SmartSnapshot.TEXT_DIM)
        val textMask = FloatArray(PredictorRuntime.POOL_SIZE)
        for (i in pool.indices) {
            if (i >= PredictorRuntime.POOL_SIZE) break
            snapshot.rawAudio.copyInto(candidates, i * dim, pool[i] * dim, (pool[i] + 1) * dim)
            val rawText = snapshot.rawText
            if (rawText != null && snapshot.hasText?.get(pool[i]) == true) {
                rawText.copyInto(
                    textCandidates,
                    i * SmartSnapshot.TEXT_DIM,
                    pool[i] * SmartSnapshot.TEXT_DIM,
                    (pool[i] + 1) * SmartSnapshot.TEXT_DIM,
                )
                textMask[i] = 1f
            }
        }

        val textHistory = context.textRows
        val textWeights = context.textWeights
        var textState = textState(textHistory, textWeights)
        val noLogits = FloatArray(PredictorRuntime.POOL_SIZE)

        val chain = ArrayList<Int>(length)
        val used = HashSet<Int>()
        var anchorRow = seedRow
        val seedGenre = MetadataRerank.normalizeGenre(snapshot.tracks[seedRow].meta.genre)
        val seedGenreSupport = MetadataRerank.seedGenreSupport(
            seedGenre,
            pool.asSequence().map { snapshot.tracks[it].meta }.asIterable(),
        )
        val recency = RecencyRerank(historyEvents)
        var seedFamilyPicks = 0
        val recentArtists = ArrayDeque<String>()
        // The listener's seed is allowed to lead into one closely related track by the same
        // artist. Once such a track is picked it enters this window normally, preventing an
        // artist/album dump while avoiding the old behaviour where the best neighbour was
        // forbidden merely because the user started from it.
        val seenTitles = HashSet<String>()
        seenTitles.add(snapshot.tracks[seedRow].meta.normalizedTitle)
        val artistPlays = HashMap<String, Int>()

        // Pool positions carry snapshot rows; the semantic z-scores are computed per pool position
        // so zSeed[i]/zPrev[i] line up with candidate `pool[i]` in the scoring loop below.
        val poolRows = pool.toIntArray()
        // Seed-relative semantic z: computed once against the ORIGINAL pick, constant for the walk.
        val zSeed = chainSemanticZ(seedRow, poolRows)

        // A pool position still in the running this hop: not already picked, and past the artist
        // spacing/cap and repeated-title filters. Shared by the scoring loop and the tail re-anchor
        // so the niche count sees exactly the candidates the scorer would.
        fun isEligible(i: Int): Boolean {
            if (i in used) return false
            val meta = snapshot.tracks[pool[i]].meta
            if (meta.artistKey in recentArtists) return false
            if (meta.normalizedTitle in seenTitles) return false
            if ((artistPlays[meta.artistKey] ?: 0) >= ChainConfig.CHAIN_ARTIST_QUEUE_CAP) return false
            return true
        }

        while (chain.size < length) {
            // Previous-pick-relative semantic z: recomputed each hop against the current anchor.
            val zPrev = chainSemanticZ(anchorRow, poolRows)

            // Tail-exhaustion re-anchor. From output position REANCHOR MIN_IDX on, once too few
            // eligible candidates sit within NICHE_COS fused cosine of the ORIGINAL seed the niche
            // is spent, so the seed-anchored gravity + semantic-seed terms re-anchor to the chain's
            // own centroid (keeping SEED_KEEP of the intent). Recomputed per hop; hops before
            // MIN_IDX, and any hop that never exhausts, leave the score byte-identical.
            var effSeed: FloatArray? = null
            var zSeedActive = zSeed
            if (Reanchor.ENABLED && seedRow >= 0 && chain.size >= Reanchor.MIN_IDX) {
                var onNiche = 0
                for (i in pool.indices) {
                    if (!isEligible(i)) continue
                    val row = pool[i]
                    val fused = Reanchor.fusedCos(
                        snapshot.centeredCosine(seedRow, row),
                        snapshot.descriptorCosine(seedRow, row),
                    )
                    if (fused >= Reanchor.NICHE_COS) onNiche++
                }
                if (Reanchor.reanchorExhausted(onNiche)) {
                    val dim = SmartSnapshot.AUDIO_DIM
                    val centroid = FloatArray(dim)
                    for (pickedRow in chain) {
                        val base = pickedRow * dim
                        for (d in 0 until dim) centroid[d] += snapshot.centeredAudio[base + d]
                    }
                    var sumSq = 0.0
                    for (x in centroid) sumSq += x.toDouble() * x
                    val cnorm = sqrt(sumSq).toFloat()
                    if (cnorm > 1e-9f) {
                        val inv = 1f / cnorm
                        for (d in 0 until dim) centroid[d] *= inv
                        val seedVec = snapshot.centeredAudio.copyOfRange(
                            seedRow * dim,
                            (seedRow + 1) * dim,
                        )
                        effSeed = Reanchor.reanchorBlend(seedVec, centroid, Reanchor.SEED_KEEP)
                        // zSeed reference -> the played pick nearest the centroid (its medoid).
                        var medoidRow = -1
                        var bestDot = Float.NEGATIVE_INFINITY
                        for (pickedRow in chain) {
                            var dot = 0f
                            val base = pickedRow * dim
                            for (d in 0 until dim) dot += centroid[d] * snapshot.centeredAudio[base + d]
                            if (dot > bestDot) {
                                bestDot = dot
                                medoidRow = pickedRow
                            }
                        }
                        if (medoidRow >= 0) zSeedActive = chainSemanticZ(medoidRow, poolRows)
                    }
                }
            }
            val logits = if (live) {
                runCatching {
                    runtime!!.score(state, candidates, textState, textCandidates, textMask)
                }
                    .onFailure { live = false }
                    .getOrDefault(noLogits)
            } else {
                noLogits
            }
            val anchorMeta = snapshot.tracks[anchorRow].meta

            var bestIndex = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in pool.indices) {
                if (!isEligible(i)) continue
                val row = pool[i]
                val meta = snapshot.tracks[row].meta

                var score = ChainConfig.SCORER_SQUASH * tanh(logits[i] / ChainConfig.SCORER_TEMP)
                score += ChainConfig.COSINE_BLEND_WEIGHT * snapshot.centeredCosine(anchorRow, row)
                // Seed gravity toward the user's actual pick — or, on an exhausted tail hop, toward
                // the re-anchored effective seed (effSeed).
                score += ChainConfig.CHAIN_SEED_GRAVITY * seedGravityCos(effSeed, seedRow, row)
                // Semantic gravity/blend: same shape as the audio terms, in the descriptor+text
                // spaces and pool-normalized (see zSeed/zPrev construction). Zero when the seed or
                // anchor has no semantic vector or too few pool members do, leaving score unchanged.
                // zSeedActive == zSeed except on an exhausted tail hop (medoid reference).
                score += ChainConfig.SEM_CHAIN_SEED_GRAVITY * zSeedActive[i] +
                    ChainConfig.SEM_CHAIN_PREV_BLEND * zPrev[i]
                var multiplier = MetadataRerank.adjustMultiplier(anchorMeta, meta)
                    .coerceIn(ChainConfig.MULTIPLIER_MIN, ChainConfig.MULTIPLIER_MAX)
                multiplier *= MetadataRerank.seedIntentMultiplier(
                    seedGenre = seedGenre,
                    poolSupport = seedGenreSupport,
                    seedFamilyPicks = seedFamilyPicks,
                    candidate = meta,
                )
                // Novelty is intentionally a prior, not an exclusion. A track heard this session
                // needs a much stronger fit to repeat, then regains a neutral score over 90 days.
                multiplier *= recency.multiplier(snapshot.tracks[row].id)
                // The raw-cosine pool carries no CSLS correction, so the dense cinematic/game/anime
                // cluster leaks into chains from sparse seeds unless damped here.
                if (meta.isHub && !anchorMeta.isHub) {
                    multiplier = (multiplier * ChainConfig.HUB_CHAIN_DAMP)
                        .coerceAtLeast(ChainConfig.MULTIPLIER_MIN)
                }
                multiplier = (multiplier * energySmoothness(anchorRow, row))
                    .coerceAtLeast(ChainConfig.MULTIPLIER_MIN)
                score += ln(multiplier)

                if (score > bestScore) {
                    bestScore = score
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break

            val pickedRow = pool[bestIndex]
            chain.add(pickedRow)
            used.add(bestIndex)
            val pickedMeta = snapshot.tracks[pickedRow].meta
            if (MetadataRerank.normalizeGenre(pickedMeta.genre) == seedGenre) seedFamilyPicks++
            seenTitles.add(pickedMeta.normalizedTitle)
            artistPlays[pickedMeta.artistKey] = (artistPlays[pickedMeta.artistKey] ?: 0) + 1
            recentArtists.addLast(pickedMeta.artistKey)
            while (recentArtists.size > ChainConfig.CHAIN_ARTIST_SPACING) recentArtists.removeFirst()
            anchorRow = pickedRow

            // The final pick needs no state advance — nothing consumes it, and the encoder is the
            // chain's dominant cost.
            if (chain.size >= length || !live) continue

            history.copyInto(history, 0, tokenDim, PredictorRuntime.CONTEXT_K * tokenDim)
            val offset = (PredictorRuntime.CONTEXT_K - 1) * tokenDim
            snapshot.rawAudio.copyInto(history, offset, pickedRow * dim, (pickedRow + 1) * dim)
            history[offset + dim] = 1f
            textHistory.copyInto(textHistory, 0, 1, PredictorRuntime.CONTEXT_K)
            textHistory[PredictorRuntime.CONTEXT_K - 1] = pickedRow
            textWeights.copyInto(textWeights, 0, 1, PredictorRuntime.CONTEXT_K)
            textWeights[PredictorRuntime.CONTEXT_K - 1] = 1f
            textState = textState(textHistory, textWeights)
            state = runCatching {
                encode(history, medium, large, timeFeatures, liveSessionFeatures)
            }
                .onFailure { live = false }
                .getOrDefault(state)
        }
        return ChainResult(chain, pool)
    }

    private fun encode(
        history: FloatArray,
        medium: FloatArray,
        large: FloatArray,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray,
    ): FloatArray = runtime!!.encodeState(history, medium, large, timeFeatures, sessionFeatures)

    /**
     * Reconstructs exactly the model inputs that are available privately on the phone: the current
     * session, 30/365-day taste centroids, completion/skip signals, and trusted metadata text. A
     * truly empty history takes the separately validated cold-start path.
     */
    private fun prepareContext(
        seedRow: Int,
        events: List<SmartHistoryEvent>,
        coldSession: FloatArray,
    ): PreparedContext {
        val known = events.mapNotNull { event ->
            snapshot.rowOf(event.trackId).takeIf { it >= 0 }?.let { row ->
                ContextEvent(
                    row = row,
                    timestamp = event.startedAtMs,
                    played = event.playedFraction.coerceIn(0f, 1f),
                    skipped = event.skipped,
                )
            }
        }.sortedBy { it.timestamp }
        if (known.isEmpty()) return coldContext(seedRow, coldSession)

        // A caller supplying history should include the current seed. Keep the engine robust if it
        // does not: append the seed without inventing a wall-clock gap or a negative preference.
        val aligned = if (known.last().row == seedRow) known else known + ContextEvent(
            row = seedRow,
            timestamp = known.last().timestamp + 1,
            played = 1f,
            skipped = false,
        )
        var sessionStart = aligned.lastIndex
        while (sessionStart > 0) {
            val gap = aligned[sessionStart].timestamp - aligned[sessionStart - 1].timestamp
            if (gap < 0 || gap > SESSION_GAP_MS) break
            sessionStart--
        }
        val sessionEvents = aligned.subList(sessionStart, aligned.size)
        val recent = sessionEvents.takeLast(PredictorRuntime.CONTEXT_K)
        val padded = ArrayList<ContextEvent>(PredictorRuntime.CONTEXT_K)
        repeat(PredictorRuntime.CONTEXT_K - recent.size) { padded += recent.first() }
        padded += recent

        val history = FloatArray(PredictorRuntime.CONTEXT_K * PredictorRuntime.TOKEN_DIM)
        val textRows = IntArray(PredictorRuntime.CONTEXT_K)
        val textWeights = FloatArray(PredictorRuntime.CONTEXT_K)
        for (i in padded.indices) {
            val event = padded[i]
            snapshot.rawAudio.copyInto(
                history,
                i * PredictorRuntime.TOKEN_DIM,
                event.row * PredictorRuntime.STATE_DIM,
                (event.row + 1) * PredictorRuntime.STATE_DIM,
            )
            history[i * PredictorRuntime.TOKEN_DIM + PredictorRuntime.STATE_DIM] = event.played
            textRows[i] = event.row
            textWeights[i] = event.played.coerceAtLeast(0.05f)
        }

        val window = sessionEvents.takeLast(10)
        val meanPlayed = window.sumOf { it.played.toDouble() }.toFloat() / window.size
        val completionRate = window.count { it.played >= 0.8f }.toFloat() / window.size
        val elapsedMinutes = ((aligned.last().timestamp - sessionEvents.first().timestamp)
            .coerceAtLeast(0L) / 60_000f).coerceAtLeast(0.5f)
        val session = floatArrayOf(
            ln(sessionEvents.size + 1f),
            if (sessionEvents.last().skipped) 1f else 0f,
            ln(elapsedMinutes + 1f),
            completionRate,
            meanPlayed,
        )
        return PreparedContext(
            history = history,
            medium = tasteCentroid(aligned, seedRow, MEDIUM_HALF_LIFE_DAYS),
            large = tasteCentroid(aligned, seedRow, LARGE_HALF_LIFE_DAYS),
            session = session,
            textRows = textRows,
            textWeights = textWeights,
        )
    }

    private fun coldContext(seedRow: Int, session: FloatArray): PreparedContext {
        val history = FloatArray(PredictorRuntime.CONTEXT_K * PredictorRuntime.TOKEN_DIM)
        for (k in 0 until PredictorRuntime.CONTEXT_K) {
            val offset = k * PredictorRuntime.TOKEN_DIM
            snapshot.rawAudio.copyInto(
                history,
                offset,
                seedRow * PredictorRuntime.STATE_DIM,
                (seedRow + 1) * PredictorRuntime.STATE_DIM,
            )
            history[offset + PredictorRuntime.STATE_DIM] = 1f
        }
        val seed = snapshot.rawAudio.copyOfRange(
            seedRow * PredictorRuntime.STATE_DIM,
            (seedRow + 1) * PredictorRuntime.STATE_DIM,
        )
        return PreparedContext(
            history = history,
            medium = seed.copyOf(),
            large = seed.copyOf(),
            session = session,
            textRows = IntArray(PredictorRuntime.CONTEXT_K) { seedRow },
            textWeights = FloatArray(PredictorRuntime.CONTEXT_K) { 1f },
        )
    }

    private fun tasteCentroid(
        events: List<ContextEvent>,
        seedRow: Int,
        halfLifeDays: Float,
    ): FloatArray {
        val centroid = FloatArray(PredictorRuntime.STATE_DIM)
        val newest = events.last().timestamp
        var totalWeight = 0.0
        for (event in events) {
            val reward = ((event.played - 0.2f) / 0.6f).coerceIn(0f, 1f)
            if (reward <= 0f) continue
            val ageDays = (newest - event.timestamp).coerceAtLeast(0L) / MILLIS_PER_DAY
            val weight = reward * exp(-ageDays * LN_2 / halfLifeDays)
            val base = event.row * PredictorRuntime.STATE_DIM
            for (d in centroid.indices) centroid[d] += snapshot.rawAudio[base + d] * weight.toFloat()
            totalWeight += weight
        }
        if (totalWeight <= 1e-9) {
            return snapshot.rawAudio.copyOfRange(
                seedRow * PredictorRuntime.STATE_DIM,
                (seedRow + 1) * PredictorRuntime.STATE_DIM,
            )
        }
        return normalized(centroid, PredictorRuntime.STATE_DIM)
    }

    private data class ContextEvent(
        val row: Int,
        val timestamp: Long,
        val played: Float,
        val skipped: Boolean,
    )

    private data class PreparedContext(
        val history: FloatArray,
        val medium: FloatArray,
        val large: FloatArray,
        val session: FloatArray,
        val textRows: IntArray,
        val textWeights: FloatArray,
    )

    /**
     * Candidate generation: interleave three rankings, anchor first.
     *
     * The anchor ranking is centered cosine to the seed minus the hub penalty (what sounds like the
     * pick, with hubs suppressed). State-audio and seed-text are separate rankings. Round-robin
     * interleaving gives each channel representation without a hand-tuned cross-modal weight; the
     * scorer learns how much optional text should affect ordering.
     */
    private fun buildPool(seedRow: Int, state: FloatArray): List<Int> {
        val n = snapshot.size
        val dim = PredictorRuntime.STATE_DIM
        val anchorScores = FloatArray(n) { row ->
            snapshot.centeredCosine(seedRow, row) -
                ChainConfig.HUB_PENALTY_BETA * snapshot.hubPenalty[row]
        }

        val stateScores = FloatArray(n)
        val textScores = FloatArray(n) { Float.NEGATIVE_INFINITY }
        if (state.size >= dim) {
            val query = normalized(state, dim)
            val textDim = SmartSnapshot.TEXT_DIM
            val rawText = snapshot.rawText
            val seedText = if (rawText != null && snapshot.hasText?.get(seedRow) == true) {
                rawText.copyOfRange(seedRow * textDim, (seedRow + 1) * textDim)
            } else {
                null
            }
            for (row in 0 until n) {
                var audio = 0f
                val base = row * dim
                for (d in 0 until dim) audio += snapshot.rawAudio[base + d] * query[d]
                stateScores[row] = audio
                if (seedText != null && snapshot.hasText?.get(row) == true) {
                    var text = 0f
                    val textBase = row * textDim
                    for (d in 0 until textDim) text += rawText!![textBase + d] * seedText[d]
                    textScores[row] = text
                }
            }
        } else {
            anchorScores.copyInto(stateScores)
        }

        val anchorOrder = order(anchorScores, seedRow)
        val stateOrder = order(stateScores, seedRow)
        val textOrder = textScores.indices
            .filter { it != seedRow && eligibleRows[it] && textScores[it].isFinite() }
            .sortedByDescending { textScores[it] }
            .toIntArray()
        val pool = ArrayList<Int>(PredictorRuntime.POOL_SIZE)
        val seen = HashSet<Int>()
        var i = 0
        while (pool.size < PredictorRuntime.POOL_SIZE && i < anchorOrder.size) {
            if (seen.add(anchorOrder[i])) pool.add(anchorOrder[i])
            if (pool.size < PredictorRuntime.POOL_SIZE && seen.add(stateOrder[i])) pool.add(stateOrder[i])
            if (pool.size < PredictorRuntime.POOL_SIZE && i < textOrder.size && seen.add(textOrder[i])) {
                pool.add(textOrder[i])
            }
            i++
        }
        return pool
    }

    /**
     * Combined semantic z-scores of every pool candidate against [refRow], averaging the offline
     * descriptor space and the on-device metadata-text space where each is available (see
     * [SemanticZ] for why pool-relative z rather than raw cosine). Zeros when [refRow] has no
     * vectors or a space is not loaded, leaving the chain score exactly as it was before this term.
     */
    /**
     * Seed-gravity cosine for a candidate row: centered cosine to the original seed, or — on an
     * exhausted tail hop — to the re-anchored effective seed ([effSeed], a unit centered-audio
     * vector). Kept in one place so both branches measure in the same space.
     */
    private fun seedGravityCos(effSeed: FloatArray?, seedRow: Int, row: Int): Float {
        if (effSeed == null) return snapshot.centeredCosine(seedRow, row)
        var dot = 0f
        val base = row * SmartSnapshot.AUDIO_DIM
        for (d in 0 until SmartSnapshot.AUDIO_DIM) dot += effSeed[d] * snapshot.centeredAudio[base + d]
        return dot
    }

    private fun chainSemanticZ(refRow: Int, poolRows: IntArray): FloatArray {
        val zDesc = SemanticZ.poolZ(
            snapshot.centeredDescriptor,
            snapshot.hasDescriptor,
            SmartSnapshot.DESCRIPTOR_DIM,
            refRow,
            poolRows,
        )
        val zText = SemanticZ.poolZ(
            snapshot.centeredText,
            snapshot.hasText,
            SmartSnapshot.TEXT_DIM,
            refRow,
            poolRows,
        )
        return SemanticZ.combine(zDesc, zText, poolRows.size)
    }

    private fun textState(rows: IntArray, weights: FloatArray): FloatArray {
        val output = FloatArray(SmartSnapshot.TEXT_DIM)
        val rawText = snapshot.rawText ?: return output
        var totalWeight = 0f
        for (i in rows.indices) {
            val row = rows[i]
            if (snapshot.hasText?.get(row) != true) continue
            val base = row * SmartSnapshot.TEXT_DIM
            val weight = weights[i]
            for (d in output.indices) output[d] += rawText[base + d] * weight
            totalWeight += weight
        }
        return if (totalWeight > 0f) normalized(output, SmartSnapshot.TEXT_DIM) else output
    }

    /**
     * Energy smoothness: penalise a large jump from the previous track, so a mellow→slammer
     * whiplash needs a real score advantage. The one audio feature worth sequencing on when cuts
     * are hard (BPM and key only matter with crossfades).
     */
    private fun energySmoothness(anchorRow: Int, candidateRow: Int): Float {
        val previous = snapshot.tracks[anchorRow].energy
        val candidate = snapshot.tracks[candidateRow].energy
        if (previous.isNaN() || candidate.isNaN()) return 1f
        val over = abs(previous - candidate) - ChainConfig.ENERGY_DEADBAND
        return if (over <= 0f) 1f else (1f - over).coerceAtLeast(ChainConfig.ENERGY_FLOOR)
    }

    private fun order(scores: FloatArray, exclude: Int): IntArray =
        scores.indices.filter { it != exclude && eligibleRows[it] }
            .sortedByDescending { scores[it] }
            .toIntArray()

    private fun normalized(v: FloatArray, dim: Int): FloatArray {
        var sumSq = 0.0
        for (i in 0 until dim) sumSq += v[i].toDouble() * v[i]
        val scale = 1f / sqrt(sumSq).toFloat().coerceAtLeast(1e-9f)
        return FloatArray(dim) { v[it] * scale }
    }

    private companion object {
        const val SESSION_GAP_MS = 30L * 60L * 1_000L
        const val MEDIUM_HALF_LIFE_DAYS = 30f
        const val LARGE_HALF_LIFE_DAYS = 365f
        const val MILLIS_PER_DAY = 86_400_000.0
        const val LN_2 = 0.6931471805599453
    }
}
