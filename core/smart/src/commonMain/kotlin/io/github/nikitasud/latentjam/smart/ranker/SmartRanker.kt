/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.HabitFlow
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.chain.MetadataRerank
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.ArtistNeighbours
import io.github.nikitasud.latentjam.smart.text.ArtistSets
import io.github.nikitasud.latentjam.smart.text.ArtistVectors
import io.github.nikitasud.latentjam.smart.text.KnownArtists
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * The hop loop of tools/research/listenbrainz_eval/proto_ranker2.py (`Query.queue`) with PLAN_FINAL §8's variety rule,
 * over pool indices.
 *
 * Hop 1 scores the pool against the seed; from hop 2 the anchor is [RankerModel.anchor]: the seed, the previous pick,
 * or both mixed. Each hop's penalised score is the score − p · runLength · sd(open rows' scores) for rows of the artist
 * the queue ends with (the seed counts). The open, unblocked rows within ε = [RankerModel.epsilon] · sd of the best form
 * the hop's near-best set, ordered by [tieOrder]; [choose] picks one of them (the salted key on device; the first, the
 * research's lowest-key rule, by default). Features are always computed over the whole pool, as the research does. The
 * loop stops early when no row may be taken or [stop] says the time budget is spent.
 */
public object RankerQueue {
    public fun build(
        model: RankerModel,
        poolSize: Int,
        artistKeys: List<String>,
        seedArtist: String,
        tieOrder: IntArray,
        length: Int,
        /** Picks one of the hop's near-best rows (ordered by [tieOrder]); hop 0 is the first pick. */
        choose: (hop: Int, nearBest: List<Int>) -> Int = { _, nearBest -> nearBest.first() },
        /** Test hook: given the hop, its chosen row and the penalised scores, the row to take (golden teacher forcing). */
        take: ((hop: Int, best: Int, scores: (Int) -> Double) -> Int)? = null,
        /** Rows the hop may not take (song-version dedup), given the picks so far. */
        blocked: (row: Int, picks: List<Int>) -> Boolean = { _, _ -> false },
        /** True when the time budget is spent: the loop returns what it has. */
        stop: () -> Boolean = { false },
        features: (anchor: Int) -> Array<DoubleArray>,
    ): List<Int> {
        if (poolSize == 0 || length <= 0) return emptyList()
        fun scores(anchor: Int) = features(anchor).let { rows -> DoubleArray(poolSize) { model.score(rows[it]) } }
        val seedScores = scores(SEED)
        val open = BooleanArray(poolSize) { true }
        val picks = ArrayList<Int>(length)
        var runArtist = seedArtist
        var runLength = if (runArtist.isEmpty()) 0 else 1
        repeat(minOf(length, poolSize)) {
            if (picks.isNotEmpty() && stop()) return picks
            val s = when {
                picks.isEmpty() || model.anchor == RankerModel.Anchor.SEED -> seedScores
                model.anchor == RankerModel.Anchor.PREVIOUS -> scores(picks.last())
                else -> {
                    val previous = scores(picks.last())
                    val total = model.seedWeight + model.previousWeight
                    DoubleArray(poolSize) { (model.seedWeight * seedScores[it] + model.previousWeight * previous[it]) / total }
                }
            }
            val sd = spread(s, open)
            val penalty = if (model.runPenalty > 0.0 && runLength > 0) model.runPenalty * runLength * sd else 0.0
            val runAt = runArtist
            fun penalised(i: Int) = if (penalty != 0.0 && artistKeys[i] == runAt) s[i] - penalty else s[i]
            val allowed = BooleanArray(poolSize) { open[it] && penalised(it).isFinite() && !blocked(it, picks) }
            var bestScore = Double.NEGATIVE_INFINITY
            var any = false
            for (i in 0 until poolSize) if (allowed[i]) {
                any = true
                bestScore = maxOf(bestScore, penalised(i))
            }
            if (!any) return picks
            val floor = bestScore - model.epsilon * sd
            val nearBest = (0 until poolSize)
                .filter { allowed[it] && penalised(it) >= floor }
                .sortedBy { tieOrder[it] }
            var best = if (nearBest.size == 1) nearBest[0] else choose(picks.size, nearBest)
            if (take != null) best = take(picks.size, best, ::penalised)
            open[best] = false
            picks += best
            val artist = artistKeys[best]
            if (runArtist.isNotEmpty() && artist == runArtist) runLength++ else {
                runArtist = artist
                runLength = if (artist.isEmpty()) 0 else 1
            }
        }
        return picks
    }

    /** The anchor index [build] passes for the seed. */
    public const val SEED: Int = -1

    private fun spread(s: DoubleArray, open: BooleanArray): Double {
        var n = 0
        var sum = 0.0
        for (i in s.indices) if (open[i] && s[i].isFinite()) { n++; sum += s[i] }
        if (n == 0) return 0.0
        val mean = sum / n
        var squares = 0.0
        for (i in s.indices) if (open[i] && s[i].isFinite()) squares += (s[i] - mean) * (s[i] - mean)
        return sqrt(squares / n)
    }
}

/**
 * One plan request (§7.2b). [candidates] are the tracks the queue may take (playable, not queued, not removed);
 * [recentIds] the queue's rows before [seed], newest last (the dedup window); [exclude] what was heard in the last 24
 * hours or earlier in this session; [history] the listener's plays (FULL and the ladder's recency rung).
 */
public class PlanRequest(
    public val seed: TrackDescriptor,
    public val library: List<TrackDescriptor>,
    public val candidates: List<TrackDescriptor>,
    public val length: Int,
    public val exclude: Set<TrackId> = emptySet(),
    public val recentIds: List<TrackId> = emptyList(),
    public val press: Long = 0,
    public val history: PersonalHistory = PersonalHistory.EMPTY,
    /** Use the FULL model when it is loaded ("Learn from my habits"). */
    public val personal: Boolean = false,
    /** Plan with the rule-only path (§7.2: asset failure, heap bound, circuit breaker, kill switch). */
    public val ruleOnly: Boolean = false,
)

/** How a plan was made, for the aggregate log line and the circuit breaker. */
public class PlanReport(
    public val ids: List<TrackId>,
    public val model: String,
    public val poolSize: Int,
    public val fromModel: Int,
    public val fromLadder: Int,
    public val timedOut: Boolean,
    public val imputed: Int,
)

/**
 * SMART's learned ranker (docs/smart-ranker.md, PLAN_FINAL §1, §7, §8): exclusions, candidates (the whole eligible
 * pool when small, else the retrievers' quotas), features with audio-tagger imputation, the [RankerQueue] decoder with
 * song-version dedup and a time budget, then the ladder until the queue is full. Never throws for bad metadata and
 * never returns an empty plan while another eligible track exists.
 *
 * Knowledge per track is read once per library list; imputations are kept per track. Not thread-safe; the chooser
 * calls it under its lock.
 */
public class SmartRanker(
    private val model: RankerModel,
    private val knowledge: ArtistKnowledge?,
    private val neighbours: ArtistNeighbours?,
    private val vectors: ArtistVectors? = null,
    private val known: KnownArtists = KnownArtists.NONE,
    private val tagger: AudioTagger? = null,
    private val audio: AudioSource = AudioSource.NONE,
    private val fullModel: RankerModel? = null,
) {
    /**
     * One track's knowledge. The descriptor and the LightFM vector are read on first use (a 10k library would hold
     * ~15 MB of descriptors otherwise); imputed blocks replace missing ones and carry their flag.
     */
    private inner class Known(
        val track: TrackDescriptor,
        packFamily: String?,
        val language: String?,
        val decade: Int?,
        val engineArtist: String,
        val setArtist: String,
        val artists: List<Long>,
        val entity: Int,
        val version: String,
        val durationMs: Long?,
    ) {
        private val packDescriptor: FloatArray? by lazy {
            runCatching {
                knowledge?.descriptor(track.artist, track.artists.firstOrNull(), text = null)?.let(::normalized)
            }.getOrNull()
        }
        val lfm: FloatArray? by lazy {
            if (entity >= 0) runCatching { vectors?.vector(entity)?.let(::normalized) }.getOrNull() else null
        }
        var imputedDescriptor: FloatArray? = null
        var family: String? = packFamily
        var familyImputed: Boolean = false
        var imputationTried: Boolean = false
        val descriptor: FloatArray? get() = packDescriptor ?: imputedDescriptor
        val descriptorImputed: Boolean get() = packDescriptor == null && imputedDescriptor != null
    }

    private var knownFor: List<TrackDescriptor>? = null
    private var knownById: Map<TrackId, Known> = emptyMap()

    /** Library tracks whose artist has a pack entity / a co-listening list / a LightFM vector (build notes). */
    public var entityCoverage: Triple<Int, Int, Int> = Triple(0, 0, 0)
        private set

    public val modelName: String get() = model.name
    public val hasFullModel: Boolean get() = fullModel != null

    private fun know(track: TrackDescriptor): Known {
        val primary = track.artists.firstOrNull()
        val facts = runCatching { knowledge?.facts(track.artist, primary) }.getOrNull()
        val entity = runCatching { knowledge?.entity(track.artist, primary) }.getOrNull() ?: -1
        val family = HabitFlow.family(track.genre)
        val set = runCatching { ArtistSets.parse(track.artist, track.title, known, genreFamily = family) }.getOrNull()
        val engineArtist = HabitSlots.artistKey(track)
        return Known(
            track = track,
            packFamily = family,
            language = facts?.language,
            decade = facts?.decade,
            engineArtist = engineArtist,
            setArtist = set?.capKey?.toString().orEmpty(),
            artists = set?.artists.orEmpty(),
            entity = entity,
            version = versionKey(track, engineArtist),
            durationMs = track.durationMs?.takeIf { it in 1..MAX_DURATION_MS },
        )
    }

    private fun knownOf(library: List<TrackDescriptor>): Map<TrackId, Known> {
        if (knownFor !== library) {
            knownById = library.associate { it.id to know(it) }
            knownFor = library
            val values = knownById.values
            entityCoverage = Triple(
                values.count { it.entity >= 0 },
                neighbours?.let { n -> values.count { it.entity >= 0 && n.has(it.entity) } } ?: 0,
                vectors?.let { v -> values.count { it.entity >= 0 && v.has(it.entity) } } ?: 0,
            )
        }
        return knownById
    }

    /** Fills a missing descriptor or family from the audio tagger (flagged imputed); counts the tracks it filled. */
    private suspend fun impute(rows: List<Known>): Int {
        val head = tagger ?: return 0
        var filled = 0
        // The audio index may be busy indexing: imputation gets its own budget, what it misses reads as unknown.
        withTimeoutOrNull(AUDIO_BUDGET_MS) { for (k in rows) {
            if (k.imputationTried || (k.descriptor != null && k.family != null)) continue
            val embedding = runCatching { audio.vector(k.track.id) }.getOrNull() ?: continue
            k.imputationTried = true
            val out = head.impute(embedding) ?: continue
            if (k.descriptor == null) k.imputedDescriptor = out.descriptor
            if (k.family == null && out.family != null) { k.family = out.family; k.familyImputed = true }
            filled++
        } }
        return filled
    }

    public suspend fun plan(request: PlanRequest): PlanReport {
        val active = when {
            request.ruleOnly -> RankerModel.RULE_SET
            request.personal && fullModel != null -> fullModel
            else -> model
        }
        val serving = active.serving
        val cache = knownOf(request.library)
        val seedKnown = cache[request.seed.id] ?: know(request.seed)
        val eligible = request.candidates
            .filter { it.id != request.seed.id && !isNonMusic(it) }
            .distinctBy { it.id }
        val n = eligible.size
        if (n == 0 || request.length <= 0) return PlanReport(emptyList(), active.name, 0, 0, 0, false, 0)
        val eligibleKnown = eligible.map { cache[it.id] ?: know(it) }
        val byId = eligibleKnown.associateBy { it.track.id }
        val draws = HopDraws(SaltedKey.seed(SaltedKey.SALT, SaltedKey.contentKey(request.seed), request.press))
        val salted = saltedOrder(eligibleKnown, request)
        val notExcluded = eligibleKnown.filter { it.track.id !in request.exclude }
        // Novelty can empty a small library; then the ladder may still take recently heard tracks.
        val poolSource = notExcluded.ifEmpty { eligibleKnown }
        val pool = when {
            request.ruleOnly -> ruleOnlyPool(poolSource, seedKnown, salted)
            poolSource.size <= serving.wholeLibraryUpTo -> poolSource
            else -> retrieve(poolSource, seedKnown, request, serving, salted)
        }.sortedWith { a, b -> SaltedKey.idOrder.compare(a.track.id.value, b.track.id.value) }
        val imputed = impute(pool + seedKnown)
        val window = minOf(serving.recentWindow, n / 2).coerceAtLeast(1)
        val previousIds = request.recentIds + request.seed.id
        fun versionOf(id: TrackId) = (byId[id] ?: cache[id])?.version ?: id.value
        val artistKeyOf = { k: Known -> if (serving.artistKey == "artistSet") k.setArtist else k.engineArtist }
        val audioVectors = HashMap<TrackId, FloatArray?>()
        suspend fun audioOf(k: Known): FloatArray? =
            if (k.track.id in audioVectors) audioVectors[k.track.id] else runCatching { audio.vector(k.track.id) }.getOrNull()?.let(::normalized).also { audioVectors[k.track.id] = it }
        // Audio vectors for the pool and the seed are read before the (non-suspending) decoder runs.
        val usesAudio = active.features.contains("audio_cos") || active.audioWeight != 0.0
        if (usesAudio) withTimeoutOrNull(AUDIO_BUDGET_MS) { audioOf(seedKnown); for (k in pool) audioOf(k) }
        val personalRows = if (active.features.any { it in RankerFeatures.PERSONAL }) request.history else null
        val budgetMs = serving.hopBudgetMs * request.length
        // The decoder's own budget: preparation (knowledge, retrieval, imputation) has its own limits above.
        val decoding = TimeSource.Monotonic.markNow()
        val modelPicks = RankerQueue.build(
            model = active,
            poolSize = pool.size,
            artistKeys = pool.map(artistKeyOf),
            seedArtist = artistKeyOf(seedKnown),
            tieOrder = IntArray(pool.size) { it },
            length = request.length,
            choose = { hop, nearBest -> nearBest[draws.pick(hop + 1, nearBest.size)] },
            blocked = { row, picks ->
                blockedVersion(pool[row].version, picks.map { pool[it].track.id }, previousIds, window, ::versionOf)
            },
            stop = { decoding.elapsedNow().inWholeMilliseconds > budgetMs },
        ) { anchor ->
            val a = if (anchor == RankerQueue.SEED) seedKnown else pool[anchor]
            features(a, pool, artistKeyOf, audioVectors, personalRows, request.seed.id)
        }.map { pool[it].track.id }
        val timedOut = modelPicks.size < minOf(request.length, pool.size) && decoding.elapsedNow().inWholeMilliseconds > budgetMs
        val picks = ArrayList(modelPicks)
        val ladder = Ladder(eligibleKnown, byId, previousIds, window, salted, request.history, ::versionOf)
        while (picks.size < request.length) {
            val next = ladder.next(picks, audio) ?: break
            picks += next
        }
        return PlanReport(picks, active.name, pool.size, modelPicks.size, picks.size - modelPicks.size, timedOut, imputed)
    }

    /** Back-compatible entry point: the plan's ids. */
    public suspend fun plan(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        candidates: List<TrackDescriptor>,
        exclude: Set<TrackId>,
        length: Int,
        press: Long = 0,
    ): List<TrackId> = plan(PlanRequest(seed, library, candidates, length, exclude, press = press)).ids

    private fun features(
        anchor: Known,
        pool: List<Known>,
        artistKeyOf: (Known) -> String,
        audioVectors: Map<TrackId, FloatArray?>,
        personal: PersonalHistory?,
        seedId: TrackId,
    ): Array<DoubleArray> {
        val related = HashMap<Int, Double>()
        if (anchor.entity >= 0) neighbours?.forEachNeighbour(anchor.entity) { n, s -> related[n] = s.toDouble() }
        val anchorAudio = audioVectors[anchor.track.id]
        val anchorArtists = anchor.artists
        val rows = pool.map { c ->
            RankerRow(
                cosine = cosine(anchor.descriptor, c.descriptor),
                family = c.family,
                language = c.language,
                decade = c.decade,
                artistKey = artistKeyOf(c),
                colisten = if (c.entity >= 0) related[c.entity] ?: 0.0 else 0.0,
                lfm = cosine(anchor.lfm, c.lfm),
                durationMs = c.durationMs,
                descriptorImputed = c.descriptorImputed,
                familyImputed = c.familyImputed,
                audio = cosine(anchorAudio, audioVectors[c.track.id]),
                artistOverlap = overlap(anchorArtists, c.artists),
            )
        }
        val personalRows = personal?.let { h ->
            val anchorId = anchor.track.id.takeIf { anchor.track.id != seedId } ?: seedId
            pool.map { c -> h.row(c.track.id, anchorId, anchor.engineArtist) }
        }
        return RankerFeatures.of(
            RankerRow(
                family = anchor.family, language = anchor.language, decade = anchor.decade, artistKey = artistKeyOf(anchor),
                descriptorImputed = anchor.descriptorImputed, familyImputed = anchor.familyImputed,
            ),
            rows,
            personalRows,
        )
    }

    /** §1.1's retrievers, each up to its quota, unioned in retriever order (R1, R2, R4, R5, then FULL's R6 / R7). */
    private suspend fun retrieve(
        pool: List<Known>,
        seed: Known,
        request: PlanRequest,
        serving: RankerModel.Serving,
        salted: Map<TrackId, Long>,
    ): List<Known> {
        val byId = pool.associateBy { it.track.id }
        val out = LinkedHashMap<TrackId, Known>()
        fun take(ids: List<TrackId>, quota: Int) {
            var added = 0
            for (id in ids) {
                if (added >= quota) break
                val k = byId[id] ?: continue
                if (out.put(id, k) == null) added++
            }
        }
        val quota = { name: String -> serving.quotas[name] ?: 0 }
        take(boundedNeighbours(seed.track.id, quota("audio") * 2, R1_BUDGET_MS), quota("audio"))
        if (seed.entity >= 0 && neighbours != null) {
            val rank = HashMap<Int, Double>()
            neighbours.forEachNeighbour(seed.entity) { e, s -> rank[e] = s.toDouble() }
            take(pool.filter { it.entity in rank }.sortedWith(compareByDescending<Known> { rank[it.entity] ?: 0.0 }.thenBy { salted[it.track.id] })
                .map { it.track.id }, quota("colisten"))
        }
        val album = seed.track.album?.takeIf(String::isNotBlank)
        take(pool.filter { (seed.setArtist.isNotEmpty() && it.setArtist == seed.setArtist) || (album != null && it.track.album == album) }
            .sortedBy { salted[it.track.id] }.map { it.track.id }, quota("artist"))
        if (seed.family != null) take(pool.filter { it.family == seed.family }.sortedBy { salted[it.track.id] }.map { it.track.id }, quota("family"))
        if (request.personal && !request.history.isEmpty) {
            take(request.history.followers(seed.track.id), quota("own") / 2)
            take(request.history.favourites(), quota("own") - quota("own") / 2)
        }
        // Too few retrieved for a full queue: a salted sample tops the pool up (never a smaller pool than needed).
        if (out.size < request.length * 2) take(pool.sortedBy { salted[it.track.id] }.map { it.track.id }, request.length * 2)
        return out.values.toList()
    }

    /** §7.2b's rule-only candidates: R4 from tags (quota 100) plus a 500-track sample in salted order. */
    private fun ruleOnlyPool(pool: List<Known>, seed: Known, salted: Map<TrackId, Long>): List<Known> {
        val ordered = pool.sortedBy { salted[it.track.id] }
        val sameArtist = ordered.filter {
            (seed.setArtist.isNotEmpty() && it.setArtist == seed.setArtist) ||
                (seed.track.album?.isNotBlank() == true && it.track.album == seed.track.album)
        }.take(100)
        return (sameArtist + ordered.take(500)).distinctBy { it.track.id }
    }

    /** Salted order key per track: the first 8 bytes of SHA-256(salt|query|press|id) (§7.2b). */
    private fun saltedOrder(rows: List<Known>, request: PlanRequest): Map<TrackId, Long> {
        val query = SaltedKey.contentKey(request.seed)
        return rows.associate { k ->
            val d = Sha256.digest("${SaltedKey.SALT}|$query|${request.press}|${k.track.id.value}".encodeToByteArray())
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xff)
            k.track.id to (v xor Long.MIN_VALUE) // unsigned order as a signed Long
        }
    }

    /**
     * §7.2's ladder: each call adds one track, from the first rung that has one — audio neighbours of the last
     * pick, descriptor neighbours, the same genre family (salted), then the least-recently-played track outside the
     * recency window; on that last rung the dedup window relaxes to 1, then the recency window to 1. A version of the
     * previous pick is never taken while any other track exists.
     */
    private inner class Ladder(
        private val eligible: List<Known>,
        private val byId: Map<TrackId, Known>,
        private val before: List<TrackId>,
        private val window: Int,
        private val salted: Map<TrackId, Long>,
        private val history: PersonalHistory,
        private val versionOf: (TrackId) -> String,
    ) {
        suspend fun next(picks: List<TrackId>, audio: AudioSource): TrackId? {
            val taken = picks.toHashSet()
            val previous = picks.lastOrNull() ?: before.last()
            val previousKnown = byId[previous] ?: knownById[previous]
            fun ok(k: Known, dedup: Int) =
                k.track.id !in taken && !blockedVersion(k.version, picks, before, dedup, versionOf)
            boundedNeighbours(previous, 50, LADDER_AUDIO_BUDGET_MS)
                .firstNotNullOfOrNull { id -> byId[id]?.takeIf { ok(it, window) } }?.let { return it.track.id }
            previousKnown?.descriptor?.let { d ->
                // A salted sample bounds the rung's work and memory on a large library.
                eligible.sortedBy { salted[it.track.id] }.take(LADDER_SAMPLE).filter { it.descriptor != null && ok(it, window) }
                    .maxWithOrNull(compareBy<Known> { cosine(d, it.descriptor) }.thenByDescending { salted[it.track.id] })
                    ?.let { return it.track.id }
            }
            previousKnown?.family?.let { f ->
                eligible.filter { it.family == f && ok(it, window) }.minByOrNull { salted[it.track.id] ?: 0L }?.let { return it.track.id }
            }
            val recent = (before + picks).takeLast(window).toHashSet()
            fun lastRung(dedup: Int, recency: Set<TrackId>) = eligible
                .filter { ok(it, dedup) && it.track.id !in recency }
                .minWithOrNull(compareBy<Known> { history.lastPlayIndex(it.track.id) ?: -1 }.thenBy { salted[it.track.id] })
            return (lastRung(window, recent) ?: lastRung(1, recent) ?: lastRung(1, setOf(previous))
                ?: eligible.filter { it.track.id !in taken && it.track.id != previous }.minByOrNull { salted[it.track.id] ?: 0L })
                ?.track?.id
        }
    }

    /**
     * Whether a track of [version] may not follow: it is a version of the previous pick, or of one of the last
     * [window] picks (the queue's rows before the plan included).
     */
    private fun blockedVersion(version: String, picks: List<TrackId>, before: List<TrackId>, window: Int, versionOf: (TrackId) -> String): Boolean {
        val recent = (before + picks).takeLast(maxOf(1, window))
        return recent.any { versionOf(it) == version }
    }

    /** Audio neighbours within [budgetMs]; a busy or failing index gives none (the next rung or retriever runs). */
    private suspend fun boundedNeighbours(id: TrackId, k: Int, budgetMs: Long): List<TrackId> =
        withTimeoutOrNull(budgetMs) { runCatching { audio.neighbours(id, k) }.getOrNull() }.orEmpty()

    private fun overlap(anchor: List<Long>, candidate: List<Long>): Double {
        if (anchor.isEmpty() || candidate.isEmpty()) return 0.0
        if (anchor[0] == candidate[0]) return 1.0
        return if (candidate.any { it in anchor }) 0.5 else 0.0
    }

    private fun normalized(v: FloatArray): FloatArray? {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        if (!(norm > 0.0) || !norm.isFinite()) return null
        val scale = 1.0 / sqrt(norm)
        return FloatArray(v.size) { (v[it] * scale).toFloat() }
    }

    private fun cosine(a: FloatArray?, b: FloatArray?): Double {
        if (a == null || b == null || a.size != b.size) return Double.NaN
        var dot = 0.0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.toDouble()
    }

    public companion object {
        /** §7.2b's time limits: R1 (100 ms), the ladder's audio rung (20 ms), audio reads for features and imputation. */
        private const val R1_BUDGET_MS = 100L
        private const val LADDER_AUDIO_BUDGET_MS = 20L
        private const val AUDIO_BUDGET_MS = 100L

        /** Tracks the ladder's descriptor rung compares at most. */
        private const val LADDER_SAMPLE = 2_000

        /** Longer than a day is not a duration (corrupt tags): unknown. */
        public const val MAX_DURATION_MS: Long = 24L * 3_600_000L

        private val NON_MUSIC_FOLDERS = setOf("ringtones", "notifications", "alarms", "recordings", "call recordings")

        /** §7.2's non-music filter: ringtone, notification, alarm and recording folders (MediaStore IS_MUSIC is upstream). */
        public fun isNonMusic(track: TrackDescriptor): Boolean =
            track.folderPath.orEmpty().split('/', '\\').any { it.trim().lowercase() in NON_MUSIC_FOLDERS }

        /** The app's song-version key: the artist key and the title without version qualifiers; a track is a version of itself. */
        public fun versionKey(track: TrackDescriptor, artistKey: String = HabitSlots.artistKey(track)): String {
            val title = MetadataRerank.normalizeTitle(track.title)
            return if (title.isEmpty() || artistKey.isEmpty()) "id:${track.id.value}" else "$artistKey|$title"
        }
    }
}

/** Hop h's draw is the h-th SplitMix64 output from the press's seed (h = 1 for the first pick). */
internal class HopDraws(seed: Long) {
    private val generator = SplitMix64(seed)
    private val outputs = ArrayList<Long>()

    fun output(hop: Int): Long {
        while (outputs.size < hop) outputs += generator.next()
        return outputs[hop - 1]
    }

    /** An index below [size] from hop [hop]'s output, as an unsigned remainder. */
    fun pick(hop: Int, size: Int): Int = (output(hop).toULong() % size.toULong()).toInt()
}
