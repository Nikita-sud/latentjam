/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldNameSource
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorlds
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

/**
 * Offline replay of the For You page over a real exported library and its real listening log.
 *
 * Diagnostic, never asserting: it reports what the page actually contains for this listener,
 * how each section's thresholds behave against the log's true age, and how much the page
 * changes day over day — the "is there ever a reason to come back" number. Worlds are
 * discovered from audio vectors (the fused space and semantic routing need the on-device
 * engine); playlist/album naming and every ForYouBuilder rule run at production fidelity.
 * Gated on SMART_PARITY_FIXTURE (library snapshot) + SMART_SIM_INPUT (ids/history/playlists).
 */
class ForYouSimulation {

    @Test
    fun `replay the page over the exported library`() {
        val fixture = System.getenv("SMART_PARITY_FIXTURE")?.let(::File)?.takeIf(File::isDirectory)
        val input = System.getenv("SMART_SIM_INPUT")?.let(::File)?.takeIf(File::isDirectory)
        if (fixture == null || input == null || !File(input, "ids.txt").isFile) {
            println("SKIP for-you sim: set SMART_PARITY_FIXTURE and SMART_SIM_INPUT")
            return
        }

        val ids = File(input, "ids.txt").readLines().filter { it.isNotBlank() }
        val meta = File(fixture, "meta.tsv").readLines().map { it.split('\t') }
        val durations = File(input, "durations.tsv").readLines().map { it.trim().toLong() }
        val added = File(input, "added.tsv").takeIf(File::isFile)
            ?.readLines()?.map { it.trim().toLong() }
        val audio = floats(File(fixture, "audio.f32"))
        val dim = 960
        val library = ids.mapIndexed { row, id ->
            TrackDescriptor(
                id = TrackId(id),
                title = meta[row][0].ifBlank { null },
                artist = meta[row][1].ifBlank { null },
                album = meta[row][2].ifBlank { null },
                genre = meta[row][3].ifBlank { null },
                year = meta[row].getOrNull(4)?.takeIf { it.isNotBlank() }?.toIntOrNull(),
                durationMs = durations[row].takeIf { it > 0 },
                addedAtMs = added?.getOrNull(row)?.takeIf { it > 0 },
            )
        }
        val vectors = ids.mapIndexed { row, id ->
            TrackId(id) to audio.copyOfRange(row * dim, (row + 1) * dim)
        }.toMap()
        val rowOf = ids.mapIndexed { row, id -> TrackId(id) to row }.toMap()

        val events = File(input, "history.log").readLines()
            .mapNotNull(ListenEvent::parse)
            .sortedBy { it.startedAtMs }
        val dayMs = 24L * 60 * 60 * 1000
        val spanDays = (events.last().startedAtMs - events.first().startedAtMs) / dayMs
        println("== log: ${events.size} events over $spanDays days ==")

        fun unhex(h: String): String = h.chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray().decodeToString()
        val playlists = File(input, "playlists.txt").readLines().mapNotNull { line ->
            val p = line.trim().split('\u001F')
            if (p.size < 5 || (p[0] != "v2" && p[0] != "v3")) return@mapNotNull null
            Playlist(
                id = unhex(p[1]),
                name = unhex(p[2]),
                trackIds = p[4].split(',').filter { it.isNotBlank() }.map(::unhex),
                createdAtMs = p[3].toLongOrNull() ?: 0L,
                includeInSmart = p.size >= 6 && p[5] == "1",
            )
        }

        val playlistGroups = playlists.map { playlist ->
            playlist.name to playlist.trackIds.mapTo(HashSet(), ::TrackId)
        }
        val albumGroups = library.filter { !it.album.isNullOrBlank() }
            .groupBy { it.album!! }
            .filterValues { it.size > 1 }
            .map { (album, tracks) -> album to tracks.mapTo(HashSet()) { it.id } }
        val worlds = LibraryWorlds.namedAfterGroups(
            LibraryWorlds.discover(library, vectors, dim),
            playlistGroups + albumGroups,
        )

        // Centered space for coherence numbers, matching the chain's geometry.
        val mean = FloatArray(dim)
        for (id in ids) for (d in 0 until dim) mean[d] += vectors.getValue(TrackId(id))[d] / ids.size
        val centered = ids.associate { id ->
            val v = vectors.getValue(TrackId(id))
            val c = FloatArray(dim) { d -> v[d] - mean[d] }
            val norm = kotlin.math.sqrt(c.sumOf { (it * it).toDouble() }).toFloat()
            TrackId(id) to FloatArray(dim) { d -> c[d] / norm }
        }
        fun cos(a: TrackId, b: TrackId): Double {
            val x = centered.getValue(a); val y = centered.getValue(b)
            var s = 0.0
            for (d in 0 until dim) s += (x[d] * y[d]).toDouble()
            return s
        }

        fun statsUpTo(cutoffMs: Long): Map<TrackId, TrackStats> {
            val out = HashMap<TrackId, TrackStats>()
            for (event in events) {
                if (event.startedAtMs > cutoffMs) break
                val prev = out[event.trackId]
                out[event.trackId] = TrackStats(
                    plays = (prev?.plays ?: 0) + 1,
                    completions = (prev?.completions ?: 0) + if (event.completed) 1 else 0,
                    skips = (prev?.skips ?: 0) + if (event.skipped) 1 else 0,
                    totalPlayedMs = (prev?.totalPlayedMs ?: 0) + event.playedMs,
                    lastPlayedAtMs = maxOf(prev?.lastPlayedAtMs ?: 0, event.startedAtMs),
                )
            }
            return out
        }

        fun buildAt(nowMs: Long): ForYouPage = ForYouBuilder.build(
            library = library,
            stats = statsUpTo(nowMs),
            recentEvents = events.filter { it.startedAtMs <= nowMs }.takeLast(500).reversed(),
            nowMs = nowMs,
            playlists = playlists,
            worlds = worlds,
            discoveryMixLabel = "Discovery mix",
        )

        val now = events.last().startedAtMs + 60_000
        val page = buildAt(now)
        println("== page at 'now' ==")
        println("hero: ${page.hero?.kicker} -> ${page.hero?.track?.title}")
        page.sections.forEach { section ->
            println("${section.kind}: ${section.cards.size} cards")
            if (section.kind == ForYouSectionKind.WORLDS) {
                section.cards.forEach { card ->
                    val mix = card.collection ?: return@forEach
                    val pairsSample = mix.tracks.take(12)
                    var c = 0.0; var pairCount = 0
                    for (i in pairsSample.indices) for (j in i + 1 until pairsSample.size) {
                        c += cos(pairsSample[i].id, pairsSample[j].id); pairCount++
                    }
                    val stats = statsUpTo(now)
                    val playsInside = mix.tracks.sumOf { stats[it.id]?.plays ?: 0 }
                    println(
                        "  mix '${mix.title}' size=${mix.tracks.size} " +
                            "coherence=%.3f playsInside=%d".format(c / pairCount, playsInside),
                    )
                }
            }
        }

        val worldStats = statsUpTo(now)
        val namedShare = worlds.count { it.nameSource != LibraryWorldNameSource.GENERIC }
        println("worlds: ${worlds.size} total, $namedShare named, sizes=${worlds.map { it.tracks.size }}")
        val topPlayed = worldStats.entries.sortedByDescending { it.value.plays }.take(20).map { it.key }
        val inAnyMix = page.sections.firstOrNull { it.kind == ForYouSectionKind.WORLDS }
            ?.cards?.flatMap { it.collection?.tracks.orEmpty() }?.mapTo(HashSet()) { it.id }
            ?: emptySet()
        println(
            "coverage: %d/20 of top-played tracks appear in the mixes row".format(
                topPlayed.count { it in inAnyMix },
            ),
        )

        println("== where the top-played 20 live ==")
        val shownWorldTracks = page.sections.firstOrNull { it.kind == ForYouSectionKind.WORLDS }
            ?.cards?.mapNotNull { it.collection }?.associate { it.title to it.tracks.mapTo(HashSet()) { t -> t.id } }
            ?: emptyMap()
        topPlayed.take(20).forEach { id ->
            val stat = worldStats.getValue(id)
            val world = worlds.indexOfFirst { w -> w.tracks.any { it.id == id } }
            val inMix = shownWorldTracks.entries.firstOrNull { id in it.value }?.key
            val title = library.firstOrNull { it.id == id }?.title?.take(28) ?: "<deleted ${id.value}>"
            println(
                "  '%s' plays=%d compl=%d skips=%d world#%d %s".format(
                    title, stat.plays, stat.completions, stat.skips, world,
                    inMix?.let { "-> shown in '" + it + "'" } ?: "(not in shown mixes)",
                ),
            )
        }

        println("== threshold sensitivity ==")
        for (quietDays in listOf(90, 30, 14)) {
            val quiet = quietDays * dayMs
            val candidates = worldStats.entries.count { (_, stat) ->
                stat.plays >= ForYouBuilder.MIN_PLAYS_TO_COUNT &&
                    stat.lastPlayedAtMs > 0 &&
                    now - stat.lastPlayedAtMs >= quiet &&
                    stat.completions >= stat.skips
            }
            println("worth-revisiting candidates at quiet=${quietDays}d: $candidates")
        }
        for (minDuration in listOf(8L * 60_000, 3L * 60_000, 0L)) {
            val resumable = events.filter { event ->
                !event.completed && event.playedMs >= ForYouBuilder.MIN_RESUME_MS &&
                    (event.trackDurationMs ?: 0) >= minDuration &&
                    (event.trackDurationMs ?: 0) > event.playedMs
            }.distinctBy { it.trackId }.size
            println("continue candidates at minDuration=${minDuration / 60_000}min: $resumable")
        }

        println("== naming containment sweep (named worlds of 17) ==")
        for (containment in listOf(0.6, 0.45, 0.35, 0.25)) {
            val named = LibraryWorlds.namedAfterGroups(
                LibraryWorlds.discover(library, vectors, dim),
                playlistGroups + albumGroups,
                minContainment = containment,
            ).count { it.nameSource != LibraryWorldNameSource.GENERIC }
            println("containment=$containment -> named=$named")
        }

        println("== churn: page vs N days earlier ==")
        fun sectionIds(p: ForYouPage, kind: ForYouSectionKind): Set<TrackId> =
            p.sections.firstOrNull { it.kind == kind }
                ?.cards?.mapTo(HashSet()) { it.track.id } ?: emptySet()
        for (daysBack in listOf(1L, 3L, 7L)) {
            val before = buildAt(now - daysBack * dayMs)
            val heroSame = before.hero?.track?.id == page.hero?.track?.id
            fun jaccard(kind: ForYouSectionKind): String {
                val a = sectionIds(page, kind); val b = sectionIds(before, kind)
                if (a.isEmpty() && b.isEmpty()) return "—"
                val union = (a + b).size
                return "%.2f".format((a intersect b).size.toDouble() / union)
            }
            println(
                "-${daysBack}d: hero same=$heroSame revisit=${jaccard(ForYouSectionKind.WORTH_REVISITING)} " +
                    "worlds=${jaccard(ForYouSectionKind.WORLDS)} neverPlayed=${jaccard(ForYouSectionKind.NEVER_PLAYED)}",
            )
        }
    }

    private fun floats(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
}
