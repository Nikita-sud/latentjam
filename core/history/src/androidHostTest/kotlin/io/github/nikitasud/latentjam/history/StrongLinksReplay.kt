/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalModel
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * A diagnostic, not a test of anything: the Statistics page's strong links on a copied phone state, under the card's
 * old rule (hand picks only) and the new one (SMART's personal evidence). Skipped unless LJ_LINKS_DIR names a copy with
 * files/listening_history.log and mediastore.txt. The library is the MediaStore rows, with plain artist keys and track
 * numbers from the file names, so it approximates the app's SMART library.
 */
class StrongLinksReplay {
    @Test
    fun replay() = runBlocking {
        val dir = File(System.getenv("LJ_LINKS_DIR") ?: return@runBlocking)
        val row = Regex("""(\w+)=(.*?)(?=, \w+=|$)""")
        val library = File(dir, "mediastore.txt").readLines().mapNotNull { line ->
            val f = row.findAll(line.substringAfter(' ').substringAfter(' ')).associate { it.groupValues[1] to it.groupValues[2] }
            if (f["is_music"] != "1") return@mapNotNull null
            fun v(k: String) = f[k]?.takeIf { it != "NULL" && it != "<unknown>" }
            TrackDescriptor(
                id = TrackId(f.getValue("_id")),
                title = v("title"),
                artist = v("artist"),
                album = v("album"),
                albumArtist = v("album_artist"),
                durationMs = v("duration")?.toLongOrNull(),
                trackNumber = v("_data")?.substringAfterLast('/')?.takeWhile(Char::isDigit)?.toIntOrNull(),
            )
        }
        val byId = library.associateBy { it.id }
        val lines = File(dir, "files/listening_history.log").readLines().filter(String::isNotEmpty)
        val events = DefaultListeningHistory(object : HistoryStore {
            override suspend fun append(line: String) = Unit
            override suspend fun readAll(): List<String> = lines
            override suspend fun clear() = Unit
        }).allEvents().sortedBy { it.startedAtMs }
        val now = events.last().startedAtMs + 60_000
        val zone = ZoneId.of("Europe/Chisinau")
        val localDay = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay() }
        fun keys(t: TrackDescriptor) = t.artist?.split(", ", " & ", " feat. ", " ft. ")?.map { it.trim().lowercase() }?.filter(String::isNotEmpty).orEmpty()
        val model = PersonalModel.build(events.map(::personalListen), library.map { personalTrack(it, keys(it)) }, localDay, now)
        val normalized = ListeningNormalization.normalize(events, now)
        fun name(id: TrackId) = byId[id]?.let { "${it.artist} — ${it.title}" } ?: id.value

        println("LINKS: ${events.size} listens, ${library.size} library tracks, ${model.sizes()}")
        // The card's old rule (0.9.4): USER_PICK right after a play of the same session, ≥ 3 times in ≥ 3 sessions, ≥ 40 %.
        run {
            val count = HashMap<Pair<TrackId, TrackId>, Int>()
            val sessions = HashMap<Pair<TrackId, TrackId>, MutableSet<Int>>()
            val steps = HashMap<TrackId, Int>()
            var previous = -1
            var previousSession = -1
            normalized.events.forEachIndexed { i, e ->
                val s = normalized.sessionIds[i]
                if (s != previousSession) previous = -1
                previousSession = s
                if (e.skipped) return@forEachIndexed
                if (previous >= 0 && e.origin?.start == ListenStart.USER_PICK) {
                    val a = normalized.events[previous].trackId
                    if (a != e.trackId) {
                        steps[a] = (steps[a] ?: 0) + 1
                        count[a to e.trackId] = (count[a to e.trackId] ?: 0) + 1
                        sessions.getOrPut(a to e.trackId) { HashSet() } += s
                    }
                }
                previous = i
            }
            count.filter { (k, c) -> c >= 3 && sessions.getValue(k).size >= 3 && c >= 0.4f * steps.getValue(k.first) }
                .entries.sortedByDescending { it.value }
                .forEach { (k, c) -> println("LINKS old: ${name(k.first)} → ${name(k.second)}  $c/${steps[k.first]} picks") }
        }
        val shown = ListeningInsights.habits(normalized, byId::get, library.size, now, personal = model).sequences
        shown.forEach { println("LINKS card: ${name(it.from)} → ${name(it.to)}  ${it.sessions} sessions${if (it.smartPlaced) " · placed by SMART" else ""}") }
        // Everything that passes the rule (the card shows the first three), and the near misses SMART counts as tier ≥ 1.
        val tracks = library.groupBy { model.songOf(it.id) }
        fun song(s: Int) = tracks[s]?.first()?.let { name(it.id) } ?: "song $s"
        model.strongLinks().forEach { println("LINKS rule: ${song(it.from)} → ${song(it.to)}  sessions=${it.sessions} smart=${it.smartPlaced} jar=${"%.2f".format(it.jar)}") }
        for (a in tracks.keys) for (b in tracks.keys) {
            if (a < 0 || b < 0 || model.sessions(a, b) < PersonalModel.MIN_SESSIONS) continue
            println(
                "LINKS pair: ${song(a)} → ${song(b)}  own=${model.sessions(a, b)} chosen=${model.chosenSessions(a, b)} " +
                    "lift=${"%.2f".format(model.lift(a, b))} jar=${"%.2f".format(model.jar(a, b))} rejections=${model.rejectionSessions(a, b)} " +
                    "strong=${model.strong(a, b)}",
            )
        }
        // What the listener heard to the end after A in SMART mode, placed by SMART itself (no own evidence by the rules).
        run {
            val listens = events.filter { it.trackId in byId }
            val smartSessions = HashMap<Pair<TrackId, TrackId>, MutableSet<Int>>()
            var session = 0
            var lastEnd = Long.MIN_VALUE
            var anchor: ListenEvent? = null
            for (e in listens) {
                val heard = e.listenedMs ?: e.playedMs
                if (lastEnd != Long.MIN_VALUE && e.startedAtMs - lastEnd > PersonalModel.SESSION_GAP_MS) { session++; anchor = null }
                lastEnd = maxOf(lastEnd, e.startedAtMs + heard)
                if (e.skipped) continue
                val a = anchor
                if (a != null && a.trackId != e.trackId && e.completed && personalListen(e).smartSourced &&
                    e.startedAtMs - (a.startedAtMs + (a.listenedMs ?: a.playedMs)) <= PersonalModel.TRANSITION_GAP_MS
                ) smartSessions.getOrPut(a.trackId to e.trackId) { HashSet() } += session
                anchor = e
            }
            val repeated = smartSessions.filter { it.value.size >= PersonalModel.D1_SESSIONS }.entries.sortedByDescending { it.value.size }
            println("LINKS smart-only: ${repeated.size} pairs heard to the end in >= 3 sessions after SMART placed B")
            repeated.take(10).forEach { (k, v) -> println("LINKS smart-only: ${name(k.first)} → ${name(k.second)}  ${v.size} sessions") }
        }
        val ysr = library.firstOrNull { it.title == "You Say Run" }
        val union = library.firstOrNull { it.title == "UNION" }
        if (ysr != null && union != null) {
            val a = model.songOf(ysr.id)
            val b = model.songOf(union.id)
            println(
                "LINKS YSR→UNION: own=${model.sessions(a, b)} chosen=${model.chosenSessions(a, b)} lift=${"%.2f".format(model.lift(a, b))} " +
                    "jar=${"%.2f".format(model.jar(a, b))} S+=${"%.2f".format(model.positive(a, b))} strong=${model.strong(a, b)}",
            )
        }
    }
}
