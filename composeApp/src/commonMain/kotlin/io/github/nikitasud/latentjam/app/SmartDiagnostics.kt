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
    starts: Int = 8,
): String {
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
        val personal = rows.first().model == fullModelName
        val ruleOnly = rows.first().model == "rule-only"
        val listens = habitListens(oldestFirst, libraryIds, startMs)
        val hearts = if (personal && useFavourites) favourites.filterValues { it == null || it <= startMs } else emptyMap()
        val history = PersonalHistory(listens, startMs, hearts) { artistKeys[it].orEmpty() }
        val diagnostics = PlanDiagnostics()
        val report = ranker.plan(
            PlanRequest(
                seed = seed, library = library, candidates = library.filter { it.id != seed.id }, length = rows.size,
                exclude = HabitSlots.novelty(listens, startMs), press = press, history = history, personal = personal,
                ruleOnly = ruleOnly,
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
            .append(",\"excluded\":").append(HabitSlots.novelty(listens, startMs).size)
            .append(",\"replayMatches\":").append(logged == replayed)
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
                    "\"penalty\":${n(e.penalty)},\"cap\":${n(e.cap)}," +
                    "\"contrib\":[${e.contributions.joinToString(",") { n(it) }}]," +
                    "\"row\":[${e.seedRow.joinToString(",") { n(it) }}]}")
            }
            out.append("]}")
        }
        out.append("],\"ladder\":[")
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
