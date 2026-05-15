/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * RecommendationEngine.kt is part of LatentJam.
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
import io.github.nikitasud.latentjam.ml.data.TrackEmbeddingDao
import io.github.nikitasud.latentjam.ml.session.SessionTracker
import io.github.nikitasud.latentjam.music.MusicRepository
import io.github.nikitasud.latentjam.playback.state.PlaybackStateManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber

/**
 * Picks the next tracks via the **Phase 0 recipe**: centroid retrieval + artist cooldown
 * + temperature-sampled head. No learned scorer involved — `scoring_v1.pt` produced uncalibrated
 *   scores on real personal libraries (e.g. SpongeBob production music surfaced for rap seeds), so
 *   we lean on the audio encoder's neighbors and shape them with three cheap rules:
 *     1. **Centroid seed** — average the embeddings of the current track + last K=4 completed
 *        plays. One outlier in the recent window can't pull the chain off course.
 *     2. **Artist cooldown** — drop any candidate whose artist appears in the recent window.
 *        Without this, nearest-neighbor lookups collapse to 4–5 tracks from one artist in a row.
 *     3. **BPM cooldown** — drop candidates whose tagged BPM differs from the current track's by
 *        more than 15 %. MusicNN embeddings are timbre-heavy and don't always encode tempo, so
 *        without this rule SMART occasionally yo-yos between 140 BPM phonk and 90 BPM chillout
 *        because the model thinks they "sound similar". Filter is a no-op when either track lacks a
 *        TBPM/BPM tag.
 *     4. **Deterministic head** — top TEMP_TOP_K candidates by BPM-penalty-adjusted cosine. **Was
 *        originally temperature-sampled at T=0.3** but the 150-decision ablation showed that the
 *        inter-pick cosine gaps in our library (~0.01-0.03) were dwarfed by Gumbel(0,1) noise (std
 *        1.28), making every activation reshuffle essentially randomly (0/50 seeds stable across 3
 *        runs, Jaccard 0.17). Same seed now produces the same queue.
 *
 * Triggering on `onIndexMoved` is the only reliable signal: `onProgressionChanged` fires only on
 * play/pause/seek and is silent during steady-state playback. Queuing at index-move time is
 * functionally equivalent — `playNext()` inserts at `index + 1`, and when the current track ends
 * ExoPlayer advances to that slot.
 *
 * Cold-start fallback (silent — playback proceeds in queue order):
 *     - Library has fewer than `MIN_LIBRARY_FOR_PREDICTOR` embedded tracks.
 * - No completed-play seed embeddings are available (in-memory ring AND DB are empty).
 */
@Singleton
class RecommendationEngine
@Inject
constructor(
    private val playbackStateManager: PlaybackStateManager,
    private val musicRepository: MusicRepository,
    private val trackEmbeddingDao: TrackEmbeddingDao,
    private val mlSettings: MlSettings,
    private val retrieval: Retrieval,
    private val metadataRerank: MetadataRerank,
    private val sessionTracker: SessionTracker,
    private val smartSessionLog: SmartSessionLog,
    private val predictorRuntime: PredictorRuntime,
    private val historyFeatureBuilder: HistoryFeatureBuilder,
) : PlaybackStateManager.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var inFlight: Job? = null
    // True once we've replaced the upcoming queue with a smart ordering for the current
    // playback. Reset on onNewPlayback (user picked something fresh) so we re-fire then.
    @Volatile private var queueIsSmart: Boolean = false
    // Snapshot of the upcoming queue (UIDs only) taken right before SMART overwrote it.
    // Used to restore the original linear order when the user toggles SMART back off.
    @Volatile private var preSmartUpcoming: List<Music.UID>? = null

    fun attach() {
        playbackStateManager.addListener(this)
        Timber.d("RecommendationEngine attached")
        // Kick off the offline judgement sweep — runs the pipeline against random
        // library seeds and writes the picks to `events.jsonl`. Lets us review Phase 0
        // quality without needing the user to actually play anything.
        launchDryRunSweep()
    }

    fun detach() {
        playbackStateManager.removeListener(this)
        scope.coroutineContext[Job]?.cancel()
    }

    /** Drop the cached embedding matrix when the library changes. */
    fun invalidateLibraryCache() {
        retrieval.invalidate()
    }

    override fun onIndexMoved(index: Int) {
        // Within a smart-filled queue we don't need to refire — every upcoming slot was
        // placed by us. Only fire when the queue isn't ours yet (e.g. resumed from disk,
        // or SMART was just enabled mid-playback and the toggle handler missed).
        if (queueIsSmart) return
        maybeInject(reason = "onIndexMoved")
    }

    override fun onNewPlayback(
        parent: org.oxycblt.musikr.MusicParent?,
        queue: List<Song>,
        index: Int,
        shuffleMode: io.github.nikitasud.latentjam.playback.state.ShuffleMode,
    ) {
        // Fresh playback (user tapped a song / album / playlist). Any pre-SMART snapshot
        // from a previous playback is no longer relevant; clear it. If SMART is on the
        // engine will snapshot anew during the onQueueReordered handler.
        queueIsSmart = false
        preSmartUpcoming = null
        if (shuffleMode == io.github.nikitasud.latentjam.playback.state.ShuffleMode.SMART) {
            snapshotPreSmartUpcoming(queue, index)
            maybeInject(reason = "onNewPlayback")
        }
    }

    override fun onQueueReordered(
        queue: List<Song>,
        index: Int,
        shuffleMode: io.github.nikitasud.latentjam.playback.state.ShuffleMode,
    ) {
        val SmartMode = io.github.nikitasud.latentjam.playback.state.ShuffleMode.SMART
        val isSmart = shuffleMode == SmartMode

        if (isSmart && !queueIsSmart) {
            // SMART just activated. Snapshot the current upcoming queue before we wipe it
            // with smart picks, so toggling off can restore the original linear order.
            snapshotPreSmartUpcoming(queue, index)
            maybeInject(reason = "onQueueReordered")
        } else if (!isSmart && preSmartUpcoming != null) {
            // Leaving SMART. Put the upcoming queue back to what it was when SMART was
            // activated (continuing from current track onward in the original order).
            restorePreSmartUpcoming()
        }
    }

    private fun snapshotPreSmartUpcoming(queue: List<Song>, index: Int) {
        val start = (index + 1).coerceAtMost(queue.size)
        preSmartUpcoming = queue.subList(start, queue.size).map { it.uid }
    }

    private fun restorePreSmartUpcoming() {
        val saved = preSmartUpcoming ?: return
        preSmartUpcoming = null
        queueIsSmart = false
        val library = musicRepository.library ?: return
        if (saved.isEmpty()) return
        val songs = saved.mapNotNull { library.findSong(it) }
        if (songs.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.Main) { playbackStateManager.replaceUpcoming(songs) }
        }
    }

    private fun maybeInject(reason: String) {
        if (
            playbackStateManager.shuffleMode !=
                io.github.nikitasud.latentjam.playback.state.ShuffleMode.SMART
        )
            return
        if (queueIsSmart) return
        if (inFlight?.isActive == true) return

        val currentSong = playbackStateManager.currentSong ?: return

        inFlight =
            scope.launch {
                try {
                    runPipeline(currentSong)
                } catch (e: Exception) {
                    Timber.w(e, "RecommendationEngine: pipeline failed, falling back to shuffle")
                }
            }
    }

    /**
     * Toggles for the BPM / energy filters. Used by the ablation sweep to attribute pick-quality
     * changes to each filter individually instead of looking at both stacked. **Production uses
     * [PRODUCTION_FILTER_CONFIG], not BOTH** — the ablation (15 seeds × 4 configs on a 700-track
     * library) showed bpm-only at 87 % "on vibe" vs both-filters at 60 %. The RMS-based energy
     * signal hurts more than it helps: tracks with similar loudness aren't necessarily similar
     * tracks, so a multiplicative energy penalty drags weak picks into the head. Revisit when we
     * have a better energy signal (e.g. learned from feedback events).
     */
    data class FilterConfig(
        val bpmEnabled: Boolean,
        val energyEnabled: Boolean,
        val label: String,
    ) {
        companion object {
            val NEITHER = FilterConfig(false, false, "neither")
            val BPM_ONLY = FilterConfig(true, false, "bpm-only")
            val ENERGY_ONLY = FilterConfig(false, true, "energy-only")
            val BOTH = FilterConfig(true, true, "both")

            /** What real SMART activations use. See class doc for why this isn't BOTH. */
            val PRODUCTION = BPM_ONLY
        }
    }

    private suspend fun runPipeline(
        currentSong: Song,
        dryRun: Boolean = false,
        filterConfig: FilterConfig = FilterConfig.PRODUCTION,
    ) =
        withContext(Dispatchers.IO) {
            val library = musicRepository.library ?: return@withContext
            val version = mlSettings.embeddingVersion
            val embeddedCount = trackEmbeddingDao.count(version)
            if (embeddedCount < MIN_LIBRARY_FOR_PREDICTOR) {
                Timber.v(
                    "cold-start: %d embedded < %d, skip",
                    embeddedCount,
                    MIN_LIBRARY_FOR_PREDICTOR,
                )
                return@withContext
            }

            // Phase 0.1 — Build a centroid seed from the current track + the last
            // CENTROID_SIZE-1 completed plays in the session. Anchoring on a short window
            // rather than the single current track makes the pipeline robust to one
            // outlier track in the history (an accidental skip into chillout during a
            // phonk session would otherwise pull the chain off-course). We re-normalize
            // to unit length so cosine scores below stay in [-1, 1] — the softmax
            // temperature is calibrated against that range.
            val recentCompleted = sessionTracker.recentCompletedSnapshot()
            val seedUids =
                LinkedHashSet<Music.UID>()
                    .apply {
                        add(currentSong.uid)
                        recentCompleted.forEach { add(it.uid) }
                    }
                    .toList()
                    .take(CENTROID_SIZE)

            val seedEmbeddings = ArrayList<FloatArray>(seedUids.size)
            for (uid in seedUids) {
                val emb =
                    trackEmbeddingDao.get(uid, version)?.embedding?.let {
                        bytesToFloats(it, Retrieval.EMBEDDING_DIM)
                    }
                if (emb != null) seedEmbeddings.add(emb)
            }
            if (seedEmbeddings.isEmpty()) {
                Timber.v("cold-start: no seed embeddings available, skip")
                return@withContext
            }
            val centroid = FloatArray(Retrieval.EMBEDDING_DIM)
            for (e in seedEmbeddings) for (k in centroid.indices) centroid[k] += e[k]
            val invN = 1f / seedEmbeddings.size
            for (k in centroid.indices) centroid[k] *= invN

            val snapshot = retrieval.ensureLoaded(version) ?: return@withContext

            // Exclude every track currently in the centroid window so the pipeline doesn't
            // immediately re-queue something we just played.
            val excludeUids = HashSet<Music.UID>(seedUids.size).apply { addAll(seedUids) }

            // Try the learned-predictor path: HistoryFeatureBuilder → encodeState → state
            // becomes the query for cosine pre-rank (against raw embeddings, since
            // the predictor was trained on raw not mean-centered ones). On any failure
            // — asset missing, ORT throws, feature dims mismatched — fall back to the
            // mean-centered centroid path so playback never breaks.
            val sessionSnapshotForFeatures = sessionTracker.snapshot(System.currentTimeMillis())
            var predictorState: FloatArray? = null
            try {
                val features =
                    if (dryRun) {
                        // Dry-run sweeps pick random library seeds with no session
                        // history, so the normal build returns history_small = zeros
                        // and the predictor would silently skip itself. Synthesise a
                        // one-track history (the seed itself, completed) so the sweep
                        // actually exercises the predictor path. Production code keeps
                        // the strict "needs real history" gate below.
                        historyFeatureBuilder.buildForDryRun(
                            seedEmbedding = seedEmbeddings[0],
                            nowMs = System.currentTimeMillis(),
                            embeddingDim = Retrieval.EMBEDDING_DIM,
                            historyTokenDim = predictorRuntime.historyTokenDim(),
                            sessionFeatDim = predictorRuntime.sessionFeatDim(),
                        )
                    } else {
                        historyFeatureBuilder.build(
                            nowMs = System.currentTimeMillis(),
                            sessionId = sessionSnapshotForFeatures.sessionId,
                            embeddingDim = Retrieval.EMBEDDING_DIM,
                            historyTokenDim = predictorRuntime.historyTokenDim(),
                            sessionFeatDim = predictorRuntime.sessionFeatDim(),
                        )
                    }
                if (features.hasHistorySmall()) {
                    predictorState = predictorRuntime.encodeState(features)
                } else {
                    Timber.v("predictor: no history available, falling back to centroid")
                }
            } catch (e: Throwable) {
                Timber.w(e, "predictor unavailable, falling back to centroid+cosine")
            }

            val sorted =
                if (predictorState != null) {
                    retrieval.cosineSortAllRaw(snapshot, predictorState, excludeUids)
                } else {
                    // Center + L2-normalize the centroid into the same space as `snapshot.flat`
                    // (the corpus mean is subtracted out at load time). `prepareQuery` handles
                    // both the subtraction and the renormalization in one pass.
                    val centeredCentroid = retrieval.prepareQuery(snapshot, centroid)
                    retrieval.cosineSortAll(snapshot, centeredCentroid, excludeUids)
                }
            if (sorted.isEmpty()) return@withContext

            // Phase 0.3 — Artist cooldown. Drop any candidate that shares an artist with
            // the recent window (current track + recent completed plays). Without this,
            // nearest-neighbor lookups frequently collapse to the same artist's catalogue
            // and SMART gets stuck on 4–5 tracks by one act in a row.
            val cooldownArtistIds =
                HashSet<Music.UID>().apply {
                    currentSong.artists.forEach { add(it.uid) }
                    recentCompleted.forEach { play ->
                        library.findSong(play.uid)?.artists?.forEach { add(it.uid) }
                    }
                }

            // Phase 0.2 — BPM + energy as soft multiplicative score penalties (no hard
            // filter). The reviewer's note: a hard ±15 % BPM gate cut ~500 of ~700 candidates
            // every decision on this library, AND the autocorrelation BPM estimator's
            // octave errors meant the gate was as likely to cut similar tracks as different
            // ones. Re-ranking via penalty stops eliminating candidates and just nudges
            // the head toward tempo / energy matches. Both reads come from the parallel
            // arrays in `Retrieval.Snapshot` (NaN = "unknown, no penalty").
            val seedIdx = snapshot.uids.indexOf(currentSong.uid)
            val seedTempo =
                if (seedIdx >= 0) snapshot.tempos[seedIdx].takeIf { !it.isNaN() } else null
            val seedEnergy =
                if (seedIdx >= 0) snapshot.energies[seedIdx].takeIf { !it.isNaN() } else null

            // Walk the sorted hits once, partitioning into a HEAD pool (top POOL_SIZE
            // candidates that survive cooldown + BPM + within-head dedup — the
            // material for temperature sampling) and a TAIL reserve (everything
            // beyond it, kept for queue padding). The tail intentionally ignores
            // cooldown / BPM / dedup so the queue stays long enough for a session;
            // those filters only matter for the immediate next few tracks.
            //
            // Within-head dedup: nearest-neighbor lookups produce duplicates in two
            // shapes — (a) several tracks by the same artist (covered already by
            // the per-session cooldown, but the cooldown only catches artists in
            // recent history, not multiple by-the-same-artist hits clustering at
            // the top of the same cosine sort), and (b) different *versions* of the
            // same track ("Forget Me Nots" + "Forget Me Nots (album version)",
            // "Iron Man" + "Iron Man 3"). We hash on (artist-set, normalized title)
            // so both collapse before the temperature sampler sees them.
            val poolSongs = ArrayList<Song>(POOL_SIZE)
            val poolScores = ArrayList<Float>(POOL_SIZE)
            // Parallel to `poolSongs`: index into snapshot.rawFlat for each pool member.
            // Lets the scorer pack raw embeddings without a DB round-trip per candidate.
            val poolUidIndices = ArrayList<Int>(POOL_SIZE)
            val seenHeadArtists = HashSet<Music.UID>()
            val seenHeadTitles = HashSet<String>()
            val tail = ArrayList<Song>()
            var droppedByDedup = 0
            for (hit in sorted) {
                val uid = snapshot.uids[hit.uidIndex]
                val song = library.findSong(uid) ?: continue

                val candidateTempo = snapshot.tempos[hit.uidIndex].takeIf { !it.isNaN() }
                val candidateEnergy = snapshot.energies[hit.uidIndex].takeIf { !it.isNaN() }

                val passesArtist = song.artists.none { it.uid in cooldownArtistIds }

                // BPM soft penalty (was a hard filter — see commit). Octave-folded ratio:
                // tracks one octave apart (60 ↔ 120) get a ×1.0 penalty since they're musically
                // related. Beyond 50 % tempo gap the penalty floors at FLOOR_PENALTY so even
                // the worst BPM mismatch still has a chance — re-ranking, not eliminating.
                val bpmPenalty =
                    if (filterConfig.bpmEnabled && seedTempo != null && candidateTempo != null) {
                        bpmPenalty(seedTempo, candidateTempo)
                    } else 1f

                // Energy soft penalty — multiplicative on the cosine score. With
                // ENERGY_PENALTY = 0.5, two tracks identical timbrally but opposite-energy
                // (Δ=1.0) lose half their similarity. Skipped when either side is NaN.
                val energyPenalty =
                    if (
                        filterConfig.energyEnabled && seedEnergy != null && candidateEnergy != null
                    ) {
                        (1f - ENERGY_PENALTY * kotlin.math.abs(seedEnergy - candidateEnergy))
                            .coerceAtLeast(0f)
                    } else 1f
                val adjustedScore = hit.score * bpmPenalty * energyPenalty

                val candidateArtist = song.artists.firstOrNull()?.uid
                val candidateTitle = normalizeTitle(song.name.raw)
                val passesDedup =
                    poolSongs.size >= POOL_SIZE ||
                        ((candidateArtist == null || candidateArtist !in seenHeadArtists) &&
                            candidateTitle !in seenHeadTitles)
                if (poolSongs.size < POOL_SIZE && passesArtist && passesDedup) {
                    poolSongs.add(song)
                    poolScores.add(adjustedScore)
                    poolUidIndices.add(hit.uidIndex)
                    if (candidateArtist != null) seenHeadArtists.add(candidateArtist)
                    seenHeadTitles.add(candidateTitle)
                } else {
                    if (passesArtist && !passesDedup) droppedByDedup++
                    tail.add(song)
                }
            }

            // After applying the energy penalty per candidate the pool is no longer
            // strictly cosine-sorted. Re-sort by adjusted score so the head sees the
            // actually-best survivors first.
            val poolOrder = poolScores.indices.sortedByDescending { poolScores[it] }
            val poolSongsSorted = poolOrder.map { poolSongs[it] }
            val poolScoresSorted = poolOrder.map { poolScores[it] }
            val poolUidIndicesSorted = poolOrder.map { poolUidIndices[it] }
            poolSongs.clear()
            poolSongs.addAll(poolSongsSorted)
            poolScores.clear()
            poolScores.addAll(poolScoresSorted)
            poolUidIndices.clear()
            poolUidIndices.addAll(poolUidIndicesSorted)
            if (poolSongs.isEmpty()) {
                Timber.v("artist cooldown left zero candidates, skipping smart pick")
                return@withContext
            }

            // Phase 0.5 — Predictor scorer re-rank. When the state-encoder path ran
            // successfully above, pack the top-POOL_SIZE pool members' raw embeddings
            // into a (1, 100, D) tensor and let `predictor_scorer_n100.onnx` produce a
            // learned logit per candidate. Re-sort the pool by these logits so the
            // head selection downstream prefers what the scorer (not raw cosine) ranks
            // highest. Pool members beyond SCORER_N stay at the cosine-adjusted order.
            // On any scorer error we just leave the pool in its cosine-adjusted order
            // — the result is the previous (predictor-free) Phase 0 pipeline.
            val scorerScores: FloatArray? =
                if (predictorState != null) {
                    try {
                        val n = PredictorRuntime.SCORER_N
                        val candidates =
                            Array(n) { i ->
                                if (i < poolUidIndices.size) {
                                    val base = poolUidIndices[i] * Retrieval.EMBEDDING_DIM
                                    FloatArray(Retrieval.EMBEDDING_DIM) { d ->
                                        snapshot.rawFlat[base + d]
                                    }
                                } else {
                                    // Padding for the unused tail of the (1, 100, D) tensor.
                                    FloatArray(Retrieval.EMBEDDING_DIM)
                                }
                            }
                        val logits = predictorRuntime.score(predictorState, candidates)
                        // Mask padded slots so they can never beat real candidates.
                        for (i in poolUidIndices.size until n) logits[i] = Float.NEGATIVE_INFINITY
                        logits
                    } catch (e: Throwable) {
                        Timber.w(e, "predictor scorer failed; keeping cosine-adjusted pool order")
                        null
                    }
                } else null

            // Per-pool-member scorer logit aligned with `poolSongs`, or null when the
            // scorer didn't run for this decision.
            val poolScorerScores: FloatArray? =
                scorerScores?.let { logits ->
                    FloatArray(poolSongs.size) { i ->
                        if (i < logits.size) logits[i] else Float.NEGATIVE_INFINITY
                    }
                }

            // If the scorer produced logits, re-order the pool by them. Cosine-adjusted
            // scores stay aligned with songs for logging.
            if (poolScorerScores != null) {
                val rerank = poolScorerScores.indices.sortedByDescending { poolScorerScores[it] }
                val s2 = rerank.map { poolSongs[it] }
                val c2 = rerank.map { poolScores[it] }
                val u2 = rerank.map { poolUidIndices[it] }
                val z2 = FloatArray(rerank.size) { poolScorerScores[rerank[it]] }
                poolSongs.clear()
                poolSongs.addAll(s2)
                poolScores.clear()
                poolScores.addAll(c2)
                poolUidIndices.clear()
                poolUidIndices.addAll(u2)
                for (i in z2.indices) poolScorerScores[i] = z2[i]
            }

            // Phase 0.4 — DETERMINISTIC head selection. Take top TEMP_TOP_K candidates by
            // adjusted score, in score order. Previously this used temperature sampling
            // (Gumbel-Top-K trick, T=0.3) but the 150-decision ablation revealed that with
            // our actual score distribution (top-20 differs by only ~0.01-0.03 in cosine),
            // the Gumbel(0,1) noise had std ~40× the inter-pick gap. Result: zero seeds
            // stable across runs (Jaccard 0.17 mean), same seed produced wildly different
            // chains every time the user toggled SMART. Going deterministic gives same
            // seed → same queue, which matches user expectation.
            //
            // Variety still comes from (a) the seed changing as the user plays through the
            // queue, (b) the centroid drifting as session history accumulates.
            val headTake = minOf(TEMP_TOP_K, poolSongs.size)
            val sampledHead = poolSongs.take(headTake)
            val sampledHeadWithScores =
                (0 until headTake).map { i ->
                    Triple(poolSongs[i], poolScores[i].toDouble(), poolScores[i])
                }

            // Final queue: sampled head, then the remaining (pool ∪ tail) in their
            // original cosine order. The head is what the user sees as "up next" in
            // the queue UI; the tail provides material for longer sessions.
            val sampledHeadUids =
                HashSet<Music.UID>(sampledHead.size).apply { sampledHead.forEach { add(it.uid) } }
            val picks = ArrayList<Song>(sorted.size)
            picks.addAll(sampledHead)
            for (i in headTake until poolSongs.size) picks.add(poolSongs[i])
            picks.addAll(tail.filter { it.uid !in sampledHeadUids })
            if (picks.isEmpty()) return@withContext

            Timber.i(
                "Phase0 SMART[%s]: predictor=%s seeds=%d cooldownArtists=%d seedBpm=%s " +
                    "seedEnergy=%s droppedByDedup=%d pool=%d head=%d tail=%d top1=%s " +
                    "top1Cos=%.3f top1Scorer=%s",
                filterConfig.label,
                if (poolScorerScores != null) "on" else "off",
                seedEmbeddings.size,
                cooldownArtistIds.size,
                seedTempo?.let { "%.1f".format(it) } ?: "?",
                seedEnergy?.let { "%.3f".format(it) } ?: "?",
                droppedByDedup,
                poolSongs.size,
                sampledHead.size,
                tail.size,
                sampledHead.firstOrNull()?.uid,
                poolScores.firstOrNull() ?: 0f,
                poolScorerScores?.firstOrNull()?.let { "%.3f".format(it) } ?: "?",
            )

            // Persist a structured snapshot of this pipeline run so we can audit
            // pipeline quality after the fact (see SmartSessionLog kdoc for the on-disk path).
            val sessionSnapshot = sessionTracker.snapshot(System.currentTimeMillis())
            smartSessionLog.recordDecision(
                sessionId = sessionSnapshot.sessionId,
                sessionPos = sessionSnapshot.sessionPos,
                seedUid = currentSong.uid.toString(),
                seedName = currentSong.name.raw.takeIf { it.isNotBlank() },
                seedArtists =
                    currentSong.artists
                        .joinToString(", ") { artist ->
                            (artist.name as? org.oxycblt.musikr.tag.Name.Known)?.raw
                                ?: artist.name.toString()
                        }
                        .takeIf { it.isNotBlank() },
                seedBpm = seedTempo,
                seedEnergy = seedEnergy,
                seedDurationMs = currentSong.durationMs,
                centroidSeedCount = seedEmbeddings.size,
                cooldownArtistCount = cooldownArtistIds.size,
                filterLabel = filterConfig.label,
                poolSize = poolSongs.size,
                tailSize = tail.size,
                headPicks =
                    sampledHeadWithScores.mapIndexed { i, (song, _, _) ->
                        // Read tempo/energy from the snapshot (per-song features), not from the
                        // musikr TBPM tag which is almost never present in this library.
                        val idx = snapshot.uids.indexOf(song.uid)
                        val songTempo =
                            if (idx >= 0) snapshot.tempos[idx].takeIf { !it.isNaN() } else null
                        val scorerLogit =
                            poolScorerScores?.getOrNull(i)?.takeIf { it != Float.NEGATIVE_INFINITY }
                        SmartSessionLog.Pick(
                            uid = song.uid.toString(),
                            name = song.name.raw.takeIf { it.isNotBlank() },
                            artists =
                                song.artists
                                    .joinToString(", ") { artist ->
                                        (artist.name as? org.oxycblt.musikr.tag.Name.Known)?.raw
                                            ?: artist.name.toString()
                                    }
                                    .takeIf { it.isNotBlank() },
                            bpm = songTempo,
                            score = poolScores[i],
                            scorerScore = scorerLogit,
                        )
                    },
            )

            // Dry-run paths short-circuit BEFORE touching playback. The decision log is
            // already written above; we just don't replace the queue or mark anything as
            // smart-driven. Used by `dryRunSweep` to populate `events.jsonl` against random
            // library seeds without disturbing whatever the user is actually listening to.
            if (dryRun) return@withContext

            // Mark the queue as smart-filled before issuing the replace so the ack-driven
            // onQueueReordered callback that follows doesn't re-enter and refire.
            queueIsSmart = true

            // Use `replaceQueueAroundCurrent` (not `replaceUpcoming`) so the previously-played
            // portion of the queue is dropped too. This lands the currently-playing track at
            // queue index 0 with smart picks below — without this, a user who's already played
            // 100 tracks linearly would see their current song buried 100 slots deep in the UI.
            // Replacing must run on the main thread (listener callbacks dispatch to UI).
            withContext(Dispatchers.Main) { playbackStateManager.replaceQueueAroundCurrent(picks) }
        }

    /**
     * Comprehensive dry-run sweep: pick [SWEEP_SIZE] random library seeds and run each through
     * [SWEEP_REPEATS] independent passes under the production filter config (BPM-only). Same code
     * path as live SMART so the picks emitted here mirror what the user actually sees in the app
     * for those seeds.
     *
     * Why N repeats per seed: temperature sampling uses fresh Gumbel(0,1) noise on every
     * activation. For an encoder-confident seed (anime, eurodance, classic rock) the pool's top
     * scores are widely separated and the noise can't reorder them — three runs produce nearly the
     * same head. For an encoder-uncertain seed (hip-hop in this library, anything cross-cluster)
     * the pool scores are flat and the noise dominates, producing different heads each run.
     * Comparing the 3 runs per seed surfaces *which* seeds are genuinely well-served by the encoder
     * vs which are being shuffled by noise.
     *
     * No-op below the [MIN_LIBRARY_FOR_PREDICTOR] cold-start gate.
     */
    private fun launchDryRunSweep() {
        scope.launch {
            val deadlineMs = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadlineMs) {
                val lib = musicRepository.library
                if (
                    lib != null &&
                        trackEmbeddingDao.count(mlSettings.embeddingVersion) >=
                            MIN_LIBRARY_FOR_PREDICTOR
                )
                    break
                kotlinx.coroutines.delay(2_000L)
            }
            val library = musicRepository.library ?: return@launch
            val version = mlSettings.embeddingVersion
            val embeddedUids = trackEmbeddingDao.knownUids(version).toHashSet()
            val candidateSongs = library.songs.filter { it.uid in embeddedUids }
            if (candidateSongs.size < MIN_LIBRARY_FOR_PREDICTOR) {
                Timber.i("dry-run sweep: only %d embedded songs, skipping", candidateSongs.size)
                return@launch
            }
            val sample = candidateSongs.shuffled().take(SWEEP_SIZE)
            // Same flag set as production (FilterConfig.PRODUCTION = BPM_ONLY) — only
            // the label differs across the three repeats so the JSONL analyzer can
            // group `(seed, run)` to measure within-seed variance.
            val runs =
                (1..SWEEP_REPEATS).map { i ->
                    FilterConfig(bpmEnabled = true, energyEnabled = false, label = "run-$i")
                }
            Timber.i(
                "dry-run sweep: %d seeds × %d repeats = %d decisions queued",
                sample.size,
                runs.size,
                sample.size * runs.size,
            )
            for (run in runs) {
                for (seed in sample) {
                    try {
                        runPipeline(seed, dryRun = true, filterConfig = run)
                    } catch (e: Throwable) {
                        Timber.w(
                            e,
                            "dry-run sweep: pipeline failed for %s under %s",
                            seed.uid,
                            run.label,
                        )
                    }
                }
            }
            Timber.i("dry-run sweep: done")
        }
    }

    /**
     * Multiplicative score penalty as a function of the tempo gap between seed and candidate.
     * Octave-folded: ratios above 1.5 get divided by 2 first, so tracks one octave apart (60 ↔ 120)
     * match perfectly. Falls off linearly with the gap and floors at [BPM_PENALTY_FLOOR] so even
     * maximum-mismatch candidates remain eligible (no eliminations — just re-ranking).
     *
     * ratio 1.00 → 1.0 (exact match) ratio 1.10 → 0.8 (10 % off) ratio 1.25 → 0.5 (25 % off) ratio
     * 1.50 → 0.3 (50 % off — floor)
     */
    private fun bpmPenalty(seed: Float, cand: Float): Float {
        val a = kotlin.math.max(seed, cand)
        val b = kotlin.math.min(seed, cand)
        if (b <= 0f) return 1f
        var ratio = a / b
        if (ratio > 1.5f) ratio /= 2f
        val gap = kotlin.math.abs(ratio - 1f)
        return (1f - 2f * gap).coerceAtLeast(BPM_PENALTY_FLOOR)
    }

    /**
     * Title normalization for within-head dedup. Strips parenthetical version markers, common
     * suffixes ("- radio edit", "feat. X", "(slowed + reverb)"), and trailing digits so different
     * mixes / sequels of the same track collapse. Aggressive on purpose — false-positive merges are
     * OK at the dedup level because we still have 50 candidates in the pool; false negatives are
     * what we're fixing.
     */
    private fun normalizeTitle(title: String): String {
        var s = title.lowercase().trim()
        // Drop parenthetical content — covers (album version), (radio edit),
        // (slowed + reverb), (remastered 2009), (feat. ...), (live), etc.
        s = s.replace(Regex("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]"), " ")
        // Drop "- something" trailing tail (dash-separated edit markers).
        s = s.replace(Regex("\\s+[-–—]\\s+.*$"), "")
        // Drop "feat./featuring/ft. ..." not in parens.
        s = s.replace(Regex("\\b(feat|featuring|ft)\\.?\\s+.*$"), "")
        // Drop trailing sequel digits ("Iron Man 3" -> "Iron Man").
        s = s.replace(Regex("\\s+\\d+\\s*$"), "")
        // Collapse whitespace.
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    companion object {
        const val MIN_LIBRARY_FOR_PREDICTOR = 100

        // Phase 0 tuning knobs — see the long-form recipe at the top of the file.
        /** Centroid window: current track + last N-1 completed plays. */
        private const val CENTROID_SIZE = 5
        /**
         * Top-N cosine-sorted survivors kept as the head pool. Pinned at the scorer's static input
         * size so we can feed the full pool through `PredictorRuntime.score` without zero-padding
         * (pad rows still go in to fill the (1, 100, D) tensor when cooldown leaves < 100
         * candidates, but their scores are masked to -inf afterwards).
         */
        private const val POOL_SIZE = PredictorRuntime.SCORER_N
        /** From the pool, the first K go into the queue head (deterministic, in score order). */
        private const val TEMP_TOP_K = 20

        /**
         * BPM filter tolerance — candidates whose tagged BPM falls outside `[curr × (1 - T), curr ×
         * (1 + T)]` are dropped. Only fires when both the current track AND the candidate have a
         * BPM tag; missing tags pass through so the filter degrades to a no-op on un-tagged
         * libraries.
         */
        /**
         * Multiplier on the energy distance penalty applied to cosine scores. With weight `W`, a
         * candidate at full energy distance (Δ=1.0) gets its score reduced by `W`. Set to 0.5 so
         * the strongest energy-distant candidates still survive but the head favors energy-matched
         * picks. 0 disables the penalty.
         */
        private const val ENERGY_PENALTY = 0.5f

        /**
         * Floor for the BPM penalty multiplier — even the worst tempo mismatch never zeroes a
         * candidate. The reviewer's recipe: 0.3 keeps badly-mismatched candidates "still in the
         * running, just heavily penalized" so the cosine signal can still win on a
         * strong-similarity pair.
         */
        private const val BPM_PENALTY_FLOOR = 0.3f

        /** Number of random seeds the dry-run sweep evaluates at app startup. */
        private const val SWEEP_SIZE = 50
        /**
         * Repeats per seed in the sweep. Each repeat re-rolls the temperature sampler's Gumbel
         * noise, so comparing the 3 picks per seed shows whether the encoder is confident (3
         * nearly-identical heads) or guessing (3 wildly different heads).
         */
        private const val SWEEP_REPEATS = 3
    }
}
