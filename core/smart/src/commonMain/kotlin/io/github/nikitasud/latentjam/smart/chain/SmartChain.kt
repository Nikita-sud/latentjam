/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.Genres
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

    /**
     * Additive nudge toward candidates sharing a listener-marked group (an opted-in playlist)
     * with the current anchor. Sized as ~0.3 standard deviations of a typical library's cosine
     * spread (~0.056 raw, measured on a 1063-track device library) times [COSINE_BLEND_WEIGHT]:
     * enough to decide ties and keep a marked mood together, never enough to outweigh genuine
     * acoustic distance. Applies ONLY when the caller passes groups — the empty default is the
     * shipped chain, byte-identical, which is what the recorded parity fixtures pin.
     */
    // 0.17 -> 0.50 (2026-08-14): two full-chain sweeps, first over an 847-track snapshot and
    // then over the live 1062-track export (33 seeds x 24 hops). On fresh data theme share
    // climbs 0.448 -> 0.470 -> 0.498 for 0.17 -> 0.30 -> 0.50 while seed-cos loses only 0.012
    // and step-cos 0.009 in total — both within the +-0.01 band accepted for the text channel.
    // 0.80 still gains theme but starts re-admitting short OST cues (9.2% vs 8.2%), and 1.20
    // visibly breaks step coherence (0.598 -> 0.569): that is the knee, so 0.50 is the last
    // step where sound stays first. The same sweeps REJECTED a competitive quota margin twice
    // (0.6 collapsed theme share to ~0.29 on fresh data) — the unconditional quota carries the
    // thematic axis and stays.
    const val COMPANION_BONUS = 0.50f

    /**
     * How many of the seed's marked-group members may be injected into the candidate pool when
     * the retrieval channels left them out. Bounded so companions enrich the pool rather than
     * replace it: the scorer still sees mostly what actually sounds like the seed.
     */
    const val COMPANION_POOL_SLOTS = 16

    /**
     * The bonus scales with the SPECIFICITY of the smallest group two tracks share:
     * `1 - groupSize/librarySize`, floored here. A 19-track Phonk speaks almost at full
     * weight; a marked super-playlist holding half the library says nearly nothing about any
     * particular pair — without this, marking "Anime" (458 of 1064 on a real device) handed
     * the same bonus to every anime-adjacent track and drowned the sub-playlists inside it.
     */
    const val COMPANION_SPECIFICITY_FLOOR = 0.25f

    /**
     * A marked group whose members (at least this share of them) sit inside another, larger
     * marked group of the same seed makes that larger group redundant for quota purposes: the
     * nested playlist is the specific claim, the super-playlist adds no new statement about
     * these tracks. Without this, marking "Anime" over an already-marked "JoJo" quietly handed
     * half of a JoJo seed's guaranteed hops to arbitrary anime tracks — a mechanic no listener
     * should have to understand, let alone manage by un-flagging playlists.
     */
    const val COMPANION_NESTING_CONTAINMENT = 0.9f

    /**
     * Every this-many hops is GUARANTEED to the seed's marked groups when any member is still
     * available: the bonus orders, the quota represents. Without it, a marked playlist whose
     * members sit far from the seed acoustically could lose every single hop to closer
     * outsiders — "keep together" that never actually shows the playlist. Members compete
     * among themselves on the full score, so the best-sounding companion goes first.
     */
    const val COMPANION_QUOTA_STRIDE = 3

    /**
     * Score distance within which a marked group's champion may claim its quota turn; see
     * ChainTuning.quotaMargin. Positive infinity = the unconditional quota this shipped with.
     */
    val COMPANION_QUOTA_MARGIN = Float.POSITIVE_INFINITY

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
    /**
     * Weight on [SmartSnapshot.typicality], in standard deviations of this library. Zero
     * reproduces the shipped chain exactly, which is what the recorded parity fixtures pin.
     * Set from `SmartEngineConfig.typicalityWeight`; see TypicalityTest for the evidence.
     */
    private val typicalityWeight: Float = 0f,
    /**
     * Track groups the listener explicitly asked to keep together — opted-in playlists, passed
     * as bare id sets so the chain stays ignorant of what a playlist is. Empty (the default)
     * reproduces the shipped chain exactly.
     */
    companionGroups: List<Set<TrackId>> = emptyList(),
    /**
     * Experiment/simulation knobs. Production uses the defaults, which reproduce the shipped
     * chain exactly; the offline harness sweeps them over real libraries before any constant
     * changes. See ChainTuning.
     */
    private val tuning: ChainTuning = ChainTuning(),
) {

    init {
        require(eligibleRows.size == snapshot.size) { "eligible row mask must match snapshot" }
    }

    private val companions = CompanionMembership.build(
        rowIds = snapshot.tracks.map { it.id },
        groups = companionGroups,
    )

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

        val dim = PredictorRuntime.EMBEDDING_DIM
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

        val excludedRows = sessionExclusions(seedRow, context.sessionRows, length)
        val pool = buildPool(seedRow, state, excludedRows)
        if (pool.isEmpty()) return ChainResult.EMPTY
        // Pool positions carry snapshot rows; the semantic z-scores and fused candidate block are
        // computed per pool position so everything lines up with candidate `pool[i]` below.
        val poolRows = pool.toIntArray()
        // Fused candidate block, packed ONCE: `[960 raw audio ⊕ 384 unit-norm text]` per row, the
        // text half zero-filled where a track has none (a trained text-dropout path). It never
        // changes across the walk — only the state's session-text-centroid does — so the scorer is
        // called against this same block every hop. Short pools stay zero-padded: the scorer graph
        // has the pool size baked in.
        val hasText = snapshot.hasText ?: BooleanArray(snapshot.size)
        val candidates = FloatArray(PredictorRuntime.POOL_SIZE * PredictorRuntime.SCORER_INPUT_DIM)
        ScorerPacking.packCandidates(
            audioFlat = snapshot.rawAudio,
            textFlat = snapshot.rawText,
            hasText = hasText,
            poolRows = poolRows,
            scorerN = PredictorRuntime.POOL_SIZE,
            out = candidates,
        )

        // Session-text-centroid: unit-norm, de-duplicated mean of the context tracks' text vectors
        // (A HEAD `_text_centroid` semantics), recomputed each hop as the context window advances.
        val textHistory = context.textRows
        var centroid = ScorerPacking.textCentroid(snapshot.rawText, hasText, textHistory)
        val noLogits = FloatArray(PredictorRuntime.POOL_SIZE)

        val chain = ArrayList<Int>(length)
        val used = HashSet<Int>()
        var anchorRow = seedRow
        val seedGenres = Genres.families(snapshot.tracks[seedRow].meta.genre)
        val seedGenreSupport = MetadataRerank.seedGenreSupport(
            seedGenres,
            pool.asSequence().map { snapshot.tracks[it].meta }.asIterable(),
        )
        val recency = RecencyRerank(historyEvents)
        var seedFamilyPicks = 0
        // Quota turns rotate through the seed's marked groups. A single union-wide best candidate
        // lets one acoustically strong playlist win every quota and starve another forever.
        val seedCompanionGroups = companions.quotaGroupsOf(seedRow)
        val quotaPositionByGroup = IntArray(companions.groupCount) { -1 }
        seedCompanionGroups.forEachIndexed { position, group ->
            quotaPositionByGroup[group] = position
        }
        var nextQuotaGroupPosition = 0
        val recentArtists = ArrayDeque<String>()
        // The listener's seed is allowed to lead into one closely related track by the same
        // artist. Once such a track is picked it enters this window normally, preventing an
        // artist/album dump while avoiding the old behaviour where the best neighbour was
        // forbidden merely because the user started from it.
        val seenTitles = HashSet<String>()
        snapshot.tracks[seedRow].meta.normalizedTitle
            .takeIf(String::isNotEmpty)
            ?.let(seenTitles::add)
        val artistPlays = HashMap<String, Int>()

        // Seed-relative semantic z: computed once against the ORIGINAL pick, constant for the walk.
        // (poolRows was built above alongside the fused candidate block.)
        val zSeed = chainSemanticZ(seedRow, poolRows)

        // A pool position still in the running this hop: not already picked, and past the artist
        // spacing/cap and repeated-title filters. Shared by the scoring loop and the tail re-anchor
        // so the niche count sees exactly the candidates the scorer would.
        fun isEligible(i: Int): Boolean {
            if (i in used) return false
            val meta = snapshot.tracks[pool[i]].meta
            if (meta.artistKey in recentArtists) return false
            if (meta.normalizedTitle.isNotEmpty() && meta.normalizedTitle in seenTitles) return false
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
                // Fused state = 960 encoder state ⊕ 384 unit-norm session-text-centroid.
                val scorerState = ScorerPacking.packState(state, centroid)
                runCatching { runtime!!.score(scorerState, candidates) }
                    .onFailure { live = false }
                    .getOrDefault(noLogits)
            } else {
                noLogits
            }
            val anchorMeta = snapshot.tracks[anchorRow].meta

            var bestIndex = -1
            var bestScore = Float.NEGATIVE_INFINITY
            val bestCompanionIndexes = IntArray(seedCompanionGroups.size) { -1 }
            val bestCompanionScores = FloatArray(seedCompanionGroups.size) {
                Float.NEGATIVE_INFINITY
            }
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
                // Typicality: the axis centering removes. Off (0f) unless the caller opts in, so
                // the recorded parity fixtures and every shipped queue are unchanged by default.
                if (typicalityWeight != 0f) {
                    score += typicalityWeight * snapshot.typicality[row]
                }
                // The listener's own "keep these together": a nudge for candidates sharing a
                // marked group with the current anchor, weighted by how SPECIFIC the smallest
                // shared group is. No groups — no term, see COMPANION_BONUS.
                if (companions.sharesGroup(anchorRow, row)) {
                    score += tuning.companionBonus * companions.weight(anchorRow, row)
                }
                // Experimental personal term, harness-only today (see ChainTuning): default
                // null/0 keeps every shipped queue and parity replay unchanged.
                val personalAffinity = tuning.personalAffinity
                if (personalAffinity != null && tuning.personalWeight != 0f) {
                    score += tuning.personalWeight * personalAffinity(row)
                }
                var multiplier = MetadataRerank.adjustMultiplier(anchorMeta, meta)
                    .coerceIn(ChainConfig.MULTIPLIER_MIN, ChainConfig.MULTIPLIER_MAX)
                if (tuning.durationSanity) {
                    multiplier *= durationSanityMultiplier(meta.durationMs)
                }
                multiplier *= MetadataRerank.seedIntentMultiplier(
                    seedGenres = seedGenres,
                    poolSupport = seedGenreSupport,
                    seedFamilyPicks = seedFamilyPicks,
                    candidate = meta,
                )
                // Outside the session, novelty is still a prior, decaying back to neutral over 90
                // days. Inside the session it is an exclusion applied at pool construction (see
                // sessionExclusions), so this multiplier only orders rows that survived that
                // exclusion — including any the starvation guard re-admitted.
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
                for (group in companions.groupsOf(row)) {
                    val position = quotaPositionByGroup[group]
                    if (position >= 0 && score > bestCompanionScores[position]) {
                        bestCompanionScores[position] = score
                        bestCompanionIndexes[position] = i
                    }
                }
            }
            if (bestIndex < 0) break

            // The quota hop: see COMPANION_QUOTA_STRIDE. Falls through to the normal pick when
            // the seed has no marked groups or no member survived this hop's eligibility.
            val quotaHop = seedCompanionGroups.isNotEmpty() &&
                (chain.size + 1) % ChainConfig.COMPANION_QUOTA_STRIDE == 0
            var chosenIndex = bestIndex
            if (quotaHop) {
                for (offset in seedCompanionGroups.indices) {
                    val position = (nextQuotaGroupPosition + offset) % seedCompanionGroups.size
                    val companionIndex = bestCompanionIndexes[position]
                    // Competitive quota: the group is guaranteed representation only while its
                    // champion remains objectively close to the best candidate. A thematic
                    // relative that no longer resembles the walk does not get forced in.
                    if (companionIndex >= 0 &&
                        bestCompanionScores[position] >= bestScore - tuning.quotaMargin
                    ) {
                        chosenIndex = companionIndex
                        nextQuotaGroupPosition = (position + 1) % seedCompanionGroups.size
                        break
                    }
                }
            }

            val pickedRow = pool[chosenIndex]
            chain.add(pickedRow)
            used.add(chosenIndex)
            val pickedMeta = snapshot.tracks[pickedRow].meta
            if (Genres.families(pickedMeta.genre).any { it in seedGenres }) seedFamilyPicks++
            if (pickedMeta.normalizedTitle.isNotEmpty()) seenTitles.add(pickedMeta.normalizedTitle)
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
            centroid = ScorerPacking.textCentroid(snapshot.rawText, hasText, textHistory)
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
        // The queue's notion of "this session" is the SAME walk that produces session_features
        // below, so what the encoder sees and what the queue excludes can never drift apart.
        // LinkedHashMap is load-bearing: sessionExclusions' starvation guard sorts these entries
        // by skip flag then timestamp with a stable sort, and ties (same group, same-millisecond
        // plays) must resolve to insertion order — i.e. session play order — rather than to a
        // HashMap's unspecified iteration order, which Kotlin common code cannot guarantee matches
        // across platforms.
        val sessionRows = LinkedHashMap<Int, SessionPlay>()
        for (event in sessionEvents) {
            val previous = sessionRows[event.row]
            if (previous == null || event.timestamp > previous.timestamp) {
                sessionRows[event.row] = SessionPlay(event.skipped, event.timestamp)
            }
        }
        val recent = sessionEvents.takeLast(PredictorRuntime.CONTEXT_K)
        val padded = ArrayList<ContextEvent>(PredictorRuntime.CONTEXT_K)
        repeat(PredictorRuntime.CONTEXT_K - recent.size) { padded += recent.first() }
        padded += recent

        val history = FloatArray(PredictorRuntime.CONTEXT_K * PredictorRuntime.TOKEN_DIM)
        val textRows = IntArray(PredictorRuntime.CONTEXT_K)
        for (i in padded.indices) {
            val event = padded[i]
            snapshot.rawAudio.copyInto(
                history,
                i * PredictorRuntime.TOKEN_DIM,
                event.row * PredictorRuntime.EMBEDDING_DIM,
                (event.row + 1) * PredictorRuntime.EMBEDDING_DIM,
            )
            history[i * PredictorRuntime.TOKEN_DIM + PredictorRuntime.EMBEDDING_DIM] = event.played
            textRows[i] = event.row
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
            sessionRows = sessionRows,
        )
    }

    /**
     * Rows the listener already heard this sitting, which the queue must not serve again.
     *
     * "I just heard this" is a fact, not a preference to be weighed against acoustic similarity,
     * so it is enforced as eligibility rather than as another score term. As a term it loses:
     * [RecencyRerank] tops out at ln(0.10) = -2.30 for a track played minutes ago, while the
     * semantic gravity added to the chain score reaches ±9 and measures +3.6 on average, so a
     * just-played track in the seed's own cluster still wins its hop.
     *
     * Cluster exhaustion is deliberately NOT guarded. Having heard most of a niche, the right
     * queue is what remains followed by a drift outward, which is [Reanchor]'s job.
     *
     * A long session in a small library could leave too little to build from, so when fewer than
     * [length] rows would remain selectable, tracks the listener actually listened to are
     * re-admitted before ones they skipped — oldest first within each group — until [length]
     * selectable rows are restored, degrading to the previous behaviour rather than returning a
     * stub queue. A skipped track is at least as unwelcome as a completed play, so re-admission
     * must not put it back in front of someone ahead of a track they genuinely listened to. This
     * count is taken over the whole library, before the per-hop artist-spacing, artist-cap and
     * duplicate-title filters run, so it does not guarantee a filled queue of exactly [length]
     * tracks — only that this many rows are eligible to be picked from.
     */
    private fun sessionExclusions(
        seedRow: Int,
        sessionRows: Map<Int, SessionPlay>,
        length: Int,
    ): Set<Int> {
        if (sessionRows.isEmpty()) return emptySet()
        val excluded = HashSet(sessionRows.keys)
        excluded.remove(seedRow)
        if (excluded.isEmpty()) return excluded

        // Counted over the whole library BEFORE the pool is assembled, so the decision is made
        // once and cannot oscillate as pool slots fill.
        var available = 0
        for (row in 0 until snapshot.size) {
            if (row == seedRow || !eligibleRows[row] || row in excluded) continue
            available++
        }
        if (available >= length) return excluded

        // Tracks the listener actually listened to come back before ones they skipped — a skip
        // is at least as unwelcome as a completed play, so re-admission must not invert that.
        // Within each group, oldest first: those are the ones least likely to still be fresh in
        // mind.
        var needed = length - available
        for ((row, _) in sessionRows.entries.sortedWith(
            compareBy({ it.value.skipped }, { it.value.timestamp }),
        )) {
            if (needed <= 0) break
            if (row == seedRow || !eligibleRows[row]) continue
            if (excluded.remove(row)) needed--
        }
        return excluded
    }

    private fun coldContext(seedRow: Int, session: FloatArray): PreparedContext {
        val history = FloatArray(PredictorRuntime.CONTEXT_K * PredictorRuntime.TOKEN_DIM)
        for (k in 0 until PredictorRuntime.CONTEXT_K) {
            val offset = k * PredictorRuntime.TOKEN_DIM
            snapshot.rawAudio.copyInto(
                history,
                offset,
                seedRow * PredictorRuntime.EMBEDDING_DIM,
                (seedRow + 1) * PredictorRuntime.EMBEDDING_DIM,
            )
            history[offset + PredictorRuntime.EMBEDDING_DIM] = 1f
        }
        val seed = snapshot.rawAudio.copyOfRange(
            seedRow * PredictorRuntime.EMBEDDING_DIM,
            (seedRow + 1) * PredictorRuntime.EMBEDDING_DIM,
        )
        return PreparedContext(
            history = history,
            medium = seed.copyOf(),
            large = seed.copyOf(),
            session = session,
            textRows = IntArray(PredictorRuntime.CONTEXT_K) { seedRow },
            sessionRows = emptyMap(),
        )
    }

    private fun tasteCentroid(
        events: List<ContextEvent>,
        seedRow: Int,
        halfLifeDays: Float,
    ): FloatArray {
        val centroid = FloatArray(PredictorRuntime.EMBEDDING_DIM)
        val newest = events.last().timestamp
        var totalWeight = 0.0
        for (event in events) {
            val reward = ((event.played - 0.2f) / 0.6f).coerceIn(0f, 1f)
            if (reward <= 0f) continue
            val ageDays = (newest - event.timestamp).coerceAtLeast(0L) / MILLIS_PER_DAY
            val weight = reward * exp(-ageDays * LN_2 / halfLifeDays)
            val base = event.row * PredictorRuntime.EMBEDDING_DIM
            for (d in centroid.indices) centroid[d] += snapshot.rawAudio[base + d] * weight.toFloat()
            totalWeight += weight
        }
        if (totalWeight <= 1e-9) {
            return snapshot.rawAudio.copyOfRange(
                seedRow * PredictorRuntime.EMBEDDING_DIM,
                (seedRow + 1) * PredictorRuntime.EMBEDDING_DIM,
            )
        }
        return normalized(centroid, PredictorRuntime.EMBEDDING_DIM)
    }

    private data class ContextEvent(
        val row: Int,
        val timestamp: Long,
        val played: Float,
        val skipped: Boolean,
    )

    /** A track's most recent play within the current session. */
    private data class SessionPlay(val skipped: Boolean, val timestamp: Long)

    private data class PreparedContext(
        val history: FloatArray,
        val medium: FloatArray,
        val large: FloatArray,
        val session: FloatArray,
        val textRows: IntArray,
        /** Snapshot row → most recent play within the current session. */
        val sessionRows: Map<Int, SessionPlay>,
    )

    /**
     * Candidate generation: interleave three rankings, anchor first.
     *
     * The anchor ranking is centered cosine to the seed minus the hub penalty (what sounds like the
     * pick, with hubs suppressed). State-audio and seed-text are separate rankings. Round-robin
     * interleaving gives each channel representation without a hand-tuned cross-modal weight; the
     * scorer learns how much optional text should affect ordering.
     */
    private fun buildPool(seedRow: Int, state: FloatArray, excluded: Set<Int>): List<Int> {
        val n = snapshot.size
        val dim = PredictorRuntime.EMBEDDING_DIM
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

        val anchorOrder = order(anchorScores, seedRow, excluded)
        val stateOrder = order(stateScores, seedRow, excluded)
        val textOrder = textScores.indices
            .filter {
                it != seedRow && eligibleRows[it] && it !in excluded && textScores[it].isFinite()
            }
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

        // A companion the bonus never sees is a companion the bonus cannot keep: members of the
        // seed's marked groups that the three rankings left out are injected over the pool's
        // tail (best-sounding first, bounded), so "keep together" reaches tracks the retrieval
        // channels alone would never surface. No marked groups — untouched pool, which is what
        // the parity fixtures pin.
        val seedGroups = companions.quotaGroupsOf(seedRow)
        if (seedGroups.isNotEmpty()) {
            // Allocate the bounded companion tail round-robin across the seed's groups. Filling it
            // from one union-wide ranking lets a large/strong first playlist consume all 16 slots
            // before a tighter second playlist gets even one candidate.
            val rankedByGroup = seedGroups.map { group ->
                companions.rowsOf(group)
                    .asSequence()
                    .filter { row ->
                        row != seedRow && eligibleRows[row] && row !in excluded && row !in seen
                    }
                    .sortedByDescending { anchorScores[it] }
                    .toList()
            }
            val cursors = IntArray(rankedByGroup.size)
            val missingCompanions = ArrayList<Int>(ChainConfig.COMPANION_POOL_SLOTS)
            while (missingCompanions.size < ChainConfig.COMPANION_POOL_SLOTS) {
                var addedThisRound = false
                for (groupPosition in rankedByGroup.indices) {
                    val ranked = rankedByGroup[groupPosition]
                    while (
                        cursors[groupPosition] < ranked.size &&
                        ranked[cursors[groupPosition]] in seen
                    ) {
                        cursors[groupPosition]++
                    }
                    if (cursors[groupPosition] < ranked.size) {
                        val row = ranked[cursors[groupPosition]++]
                        if (seen.add(row)) {
                            missingCompanions += row
                            addedThisRound = true
                            if (missingCompanions.size >= ChainConfig.COMPANION_POOL_SLOTS) break
                        }
                    }
                }
                if (!addedThisRound) break
            }
            if (missingCompanions.isNotEmpty()) {
                val keep = (pool.size - missingCompanions.size).coerceAtLeast(0)
                while (pool.size > keep) pool.removeAt(pool.size - 1)
                pool.addAll(missingCompanions)
            }
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

    private fun order(scores: FloatArray, exclude: Int, excluded: Set<Int>): IntArray =
        scores.indices.filter { it != exclude && eligibleRows[it] && it !in excluded }
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
