/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.SmartImpression
import io.github.nikitasud.latentjam.history.SmartImpressionLog
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.ranker.PersonalHistory
import io.github.nikitasud.latentjam.smart.ranker.PlanDiagnostics
import io.github.nikitasud.latentjam.smart.ranker.PlanRequest
import io.github.nikitasud.latentjam.smart.ranker.RankerFeatures
import io.github.nikitasud.latentjam.smart.ranker.SaltedKey
import io.github.nikitasud.latentjam.smart.ranker.SmartRanker

/** Writes a debug file where `adb pull` reaches it without run-as (Android: the app's external files dir); its path. */
internal expect fun writeDebugFile(name: String, text: String): String?

/** Appends one line to a debug file beside [writeDebugFile]'s (rotated past a few MB); false where unsupported. */
internal expect fun appendDebugLine(name: String, line: String): Boolean

/**
 * "Diagnose recent SMART starts" (a test-only settings action, product checks): re-plans the last [starts] ranker
 * queues the impression log recorded, each from its own seed, press, start time and model (FULL when the log names
 * the FULL model), on the history before that time — the plan the listener got, now with [PlanDiagnostics]. Each
 * replay is checked against the logged picks (`replayMatches`). One JSON line per start; names are included (the
 * file stays on the device, in the app's own folder, until pulled).
 */
internal suspend fun diagnoseRecentSmartStarts(
    log: SmartImpressionLog,
    ranker: SmartRanker,
    library: List<TrackDescriptor>,
    events: List<ListenEvent>,
    favourites: Map<TrackId, Long?>,
    useFavourites: Boolean,
    fullModelName: String?,
    starts: Int = 40,
    /** The audio index and semantic head, for the per-seed library probe (A0 neighbours, energy, mood). */
    engine: io.github.nikitasud.latentjam.smart.SimilarityEngine? = null,
): String {
    // Semantic-head energy / mood per track, read once (cached semantics only: no model is loaded for this).
    val semantics = runCatching { engine?.libraryMixFeatures(library.map { it.id })?.semantics }.getOrNull().orEmpty()
    val energyHigh = library.mapNotNull { semantics[it.id]?.probability(io.github.nikitasud.latentjam.smart.SemanticLabel.ENERGY_HIGH) }.sorted()
    val lowTercile = energyHigh.getOrNull(energyHigh.size / 3)
    // The log keeps labels as ASCII tokens (SmartImpressionLog): compare model names the same way.
    fun token(s: String?) = s?.map { c -> if (c == '|' || c.code < 0x20 || c.code > 0x7e) '_' else c }?.joinToString("")
    val fullToken = token(fullModelName)
    val plans = LinkedHashMap<String, MutableList<SmartImpression>>()
    log.forEachLine { line ->
        val i = SmartImpressionLog.parse(line) as? SmartImpression ?: return@forEachLine
        if (i.plan == 0) plans.getOrPut(i.queue) { ArrayList() } += i
    }
    val byHash = HashMap<String, TrackDescriptor>(library.size * 2)
    for (t in library) byHash[SaltedKey.contentHash(SaltedKey.contentKey(t))] = t
    val bySeed = HashMap<String, TrackDescriptor>(library.size * 2)
    for ((h, t) in byHash) bySeed[h.take(12)] = t
    val oldestFirst = events.sortedBy { it.startedAtMs }
    val libraryIds = library.mapTo(HashSet()) { it.id }
    val artistKeys = library.associate { it.id to HabitSlots.artistKey(it) }
    val names = library.associateBy { it.id }
    val out = StringBuilder()
    // Freshness as the live chooser saw it: every press's plan-0 first rows (SmartLogRecorder), newest first.
    class Press(val queue: String, val atMs: Long, val ids: Set<TrackId>)
    val presses = plans.entries.map { (queue, rows) ->
        Press(queue, rows.minOf { it.atMs }, rows.filter { it.hop <= SmartPlanLog.SHOWN_ROWS }.mapNotNullTo(HashSet()) { byHash[it.track]?.id })
    }.sortedByDescending { it.atMs }
    val chosen = plans.entries.filter { (_, rows) -> rows.firstOrNull()?.model?.let { it != "chain" && it != "none" } == true }.takeLast(starts)
    for ((queue, rows) in chosen) {
        val parts = queue.split('.')
        val seed = bySeed[parts.getOrNull(0)]
        val press = parts.getOrNull(1)?.toLongOrNull()
        val startMs = parts.getOrNull(2)?.toLongOrNull()
        if (seed == null || press == null || startMs == null) {
            out.append("{\"queue\":").append(q(queue)).append(",\"error\":\"seed not in the library\"}\n")
            continue
        }
        val personal = fullToken != null && rows.first().model == fullToken
        val ruleOnly = rows.first().model == "rule-only"
        val listens = habitListens(oldestFirst, libraryIds, startMs)
        val hearts = if (personal && useFavourites) favourites.filterValues { it == null || it <= startMs } else emptyMap()
        val history = PersonalHistory(listens, startMs, hearts) { artistKeys[it].orEmpty() }
        val diagnostics = PlanDiagnostics(topK = 10)
        val plannedAt = rows.minOf { it.atMs }
        val impressions = ranker.freshnessFor(personal, ruleOnly)?.let { f ->
            val window = f.windowMs ?: (2L * 3_600_000)
            presses.filter { it.queue != queue && it.atMs < plannedAt && plannedAt - it.atMs <= window }.take(f.presses).map { it.ids }
        }.orEmpty()
        val report = ranker.plan(
            PlanRequest(
                seed = seed, library = library, candidates = library.filter { it.id != seed.id }, length = rows.size,
                exclude = HabitSlots.novelty(listens, startMs), press = press, history = history, personal = personal,
                ruleOnly = ruleOnly, impressions = impressions,
            ),
            diagnostics,
        )
        val logged = rows.sortedBy { it.hop }.map { it.track }
        val replayed = report.ids.map { id -> names[id]?.let { SaltedKey.contentHash(SaltedKey.contentKey(it)) } }
        // What SMART-originated plays contributed to each shown track's history (question d).
        val plays = HashMap<TrackId, IntArray>()
        for (e in oldestFirst) {
            if (e.startedAtMs >= startMs || e.skipped) continue
            val c = plays.getOrPut(e.trackId) { IntArray(3) }
            c[0]++
            if (e.origin?.smartPlanPosition != null) c[1]++
            if (e.origin?.start == ListenStart.USER_PICK) c[2]++
        }
        fun track(id: TrackId): String {
            val t = names[id]
            val p = plays[id]
            return "{\"id\":${q(id.value)},\"title\":${q(t?.title)},\"artist\":${q(t?.artist)},\"genre\":${q(t?.genre)}," +
                "\"plays\":${p?.get(0) ?: 0},\"smartPlays\":${p?.get(1) ?: 0},\"userPicks\":${p?.get(2) ?: 0}," +
                "\"favourite\":${id in hearts}}"
        }
        out.append("{\"queue\":").append(q(queue)).append(",\"model\":").append(q(diagnostics.model))
            .append(",\"personal\":").append(personal).append(",\"press\":").append(press)
            .append(",\"startMs\":").append(startMs).append(",\"seed\":").append(track(seed.id))
            .append(",\"pool\":").append(diagnostics.poolSize).append(",\"poolHow\":").append(q(diagnostics.poolHow))
            .append(",\"sources\":{").append(diagnostics.sourceCounts.entries.joinToString(",") { (k, v) -> "${q(k)}:$v" }).append("}")
            .append(",\"imputed\":").append(report.imputed)
            .append(",\"impressionSets\":").append(impressions.size)
            .append(",\"impressionIds\":").append(impressions.sumOf { it.size })
            .append(",\"audio\":").append(report.audio.let { c ->
                "{\"seedVector\":${c.seedVector},\"seedEnergy\":${c.seedEnergy},\"poolVectors\":${c.poolVectors}," +
                    "\"poolEnergies\":${c.poolEnergies},\"r1Neighbours\":${c.r1Neighbours}," +
                    "\"timedOut\":[${c.timedOut.sorted().joinToString(",") { q(it) }}]}"
            })
            .append(",\"excluded\":").append(HabitSlots.novelty(listens, startMs).size)
            .append(",\"replayMatches\":").append(logged == replayed)
            .append(",\"energyCoverage\":").append(runCatching { engine?.energyCoverage() }.getOrNull()?.let { (i, e) -> "[$i,$e]" } ?: "null")
            .append(",\"loggedOverlap\":").append(logged.zip(replayed).count { (a, b) -> a == b })
            .append(",\"features\":[").append(diagnostics.features.joinToString(",") { q(it) }).append("]")
            .append(",\"rowNames\":[").append(RankerFeatures.NAMES.joinToString(",") { q(it) }).append("]")
            .append(",\"hops\":[")
        diagnostics.hops.forEachIndexed { i, h ->
            if (i > 0) out.append(',')
            out.append("{\"hop\":${h.hop},\"pick\":${q(h.pick.value)},\"sd\":${n(h.sd)},\"penalty\":${n(h.penalty)}," +
                "\"runArtist\":${q(h.runArtist)},\"runLength\":${h.runLength},\"capped\":${h.capped}," +
                "\"nearBest\":${h.nearBestSize},\"floor\":${n(h.floor)},\"entries\":[")
            h.entries.forEachIndexed { j, e ->
                if (j > 0) out.append(',')
                out.append("{\"track\":${track(e.id)},\"s\":${n(e.s)},\"t\":${n(e.t)},\"near\":${e.nearBest}," +
                    "\"p\":${n(e.probability)},\"source\":${q(e.source)},\"artistKey\":${q(e.artistKey)}," +
                    "\"penalty\":${n(e.penalty)},\"cap\":${n(e.cap)},\"reentry\":${n(e.reentry)}," +
                    "\"contrib\":[${e.contributions.joinToString(",") { n(it) }}]," +
                    "\"terms\":${e.terms?.joinToString(",", "[", "]") { n(it) } ?: "null"}," +
                    "\"row\":[${e.seedRow.joinToString(",") { n(it) }}]}")
            }
            out.append("]}")
        }
        // The library probe: tracks a listener might expect after this seed, and what became of each in this plan.
        val poolIds = diagnostics.poolIds.toHashSet()
        val excluded = HabitSlots.novelty(listens, startMs)
        val firstRank = HashMap<TrackId, Int>()
        diagnostics.firstHop.forEachIndexed { r, e -> firstRank[e.id] = r + 1 }
        val firstPick = diagnostics.firstHop.firstOrNull { it.id == diagnostics.hops.firstOrNull()?.pick }
        val seedVector = runCatching { engine?.embedding(seed.id) }.getOrNull()
        val audioNb = runCatching { engine?.audioNeighbours(seed.id, 40) }.getOrNull().orEmpty()
        val audioRank = HashMap<TrackId, Int>().also { m -> audioNb.forEachIndexed { r, s -> m[s.trackId] = r + 1 } }
        val tagRe = Regex("r&b|rnb|r'n'b|soul|slow jam|ballad", RegexOption.IGNORE_CASE)
        val probe = LinkedHashMap<TrackId, MutableSet<String>>()
        fun add(id: TrackId, why: String) { if (id != seed.id) probe.getOrPut(id) { LinkedHashSet() } += why }
        val seedAlbum = seed.album?.takeIf(String::isNotBlank)
        for (t in library) {
            if (seedAlbum != null && t.album == seedAlbum) add(t.id, "album")
            if (seed.folderPath != null && t.folderPath == seed.folderPath) add(t.id, "folder")
            if (t.genre?.let(tagRe::containsMatchIn) == true) add(t.id, "tag")
        }
        audioNb.take(25).forEach { add(it.trackId, "audio") }
        if (lowTercile != null) {
            library.filter { t ->
                val e = semantics[t.id]?.probability(io.github.nikitasud.latentjam.smart.SemanticLabel.ENERGY_HIGH)
                e != null && e <= lowTercile && (io.github.nikitasud.latentjam.smart.HabitFlow.family(t.genre) == "rap" || t.genre?.let(tagRe::containsMatchIn) == true)
            }.forEach { add(it.id, "lowEnergyRnbHipHop") }
        }
        out.append("],\"firstHopTop\":[")
        out.append(diagnostics.firstHop.take(40).joinToString(",") { e ->
            "{\"track\":${track(e.id)},\"t\":${n(e.t)},\"source\":${q(e.source)},\"contrib\":[${e.contributions.joinToString(",") { n(it) }}]}"
        })
        val poolIndex = HashMap<TrackId, Int>().also { m -> diagnostics.poolIds.forEachIndexed { i, id -> m[id] = i } }
        out.append("],\"probe\":[")
        var firstProbe = true
        for ((id, why) in probe) {
            if (!firstProbe) out.append(',')
            firstProbe = false
            val e = diagnostics.firstHop.firstOrNull { it.id == id }
            val sem = semantics[id]
            val v = runCatching { engine?.embedding(id) }.getOrNull()
            val cos = if (seedVector != null && v != null && v.size == seedVector.size) {
                var dot = 0.0; var na = 0.0; var nb = 0.0
                for (i in v.indices) { dot += v[i] * seedVector[i]; na += v[i] * v[i]; nb += seedVector[i] * seedVector[i] }
                if (na > 0 && nb > 0) dot / kotlin.math.sqrt(na * nb) else Double.NaN
            } else Double.NaN
            val status = when {
                id in poolIds && e != null -> "pool"
                id in poolIds -> "pool-blocked"
                id in excluded -> "excluded-recent"
                library.none { it.id == id } -> "not-in-library"
                else -> "not-retrieved"
            }
            out.append("{\"track\":${track(id)},\"album\":${q(names[id]?.album)},\"why\":[${why.joinToString(",") { q(it) }}]," +
                "\"status\":${q(status)},\"source\":${q(e?.source)},\"rank1\":${firstRank[id] ?: "null"},\"t1\":${n(e?.t ?: Double.NaN)}," +
                "\"audioCos\":${n(cos)},\"audioRank\":${audioRank[id] ?: "null"}," +
                "\"energyHigh\":${n(sem?.probability(io.github.nikitasud.latentjam.smart.SemanticLabel.ENERGY_HIGH)?.toDouble() ?: Double.NaN)}," +
                "\"energyLow\":${n(sem?.probability(io.github.nikitasud.latentjam.smart.SemanticLabel.ENERGY_LOW)?.toDouble() ?: Double.NaN)}," +
                "\"tender\":${n(sem?.probability(io.github.nikitasud.latentjam.smart.SemanticLabel.MOOD_TENDER)?.toDouble() ?: Double.NaN)}," +
                "\"e\":${n(runCatching { engine?.energy(id) }.getOrNull() ?: Double.NaN)}," +
                "\"family\":${q(ranker.familyOf(id))}," +
                "\"ranks\":[${poolIndex[id]?.let { pi -> diagnostics.hops.joinToString(",") { h -> h.ranks.getOrNull(pi)?.toString() ?: "0" } } ?: ""}]," +
                "\"contrib\":[${e?.contributions?.joinToString(",") { n(it) } ?: ""}]," +
                "\"gapToPick\":[${if (e != null && firstPick != null) firstPick.contributions.indices.joinToString(",") { n(firstPick.contributions[it] - e.contributions[it]) } else ""}]}")
        }
        out.append("],\"lowEnergyCut\":").append(n(lowTercile?.toDouble() ?: Double.NaN))
        out.append(",\"ladder\":[")
        out.append(diagnostics.ladder.joinToString(",") { (id, rung) -> "{\"track\":${track(id)},\"rung\":${q(rung)}}" })
        out.append("]}\n")
    }
    return out.toString()
}

private fun q(s: String?): String = if (s == null) "null" else buildString {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c.code < 0x20 -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> append(c)
    }
    append('"')
}

private fun n(x: Double): String = if (x.isFinite()) x.toString() else "null"
