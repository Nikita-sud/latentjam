/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.library.DefaultPlaylists
import io.github.nikitasud.latentjam.library.PlaylistStore
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * General-stand helper (synthetic FMA listeners, evaluation only): turns make_history.py's plan (events.tsv,
 * playlists.tsv) into files/listening_history.log and files/playlists.txt with the app's own serializers.
 * Runs only when LJ_GENSTAND_HISTORY is set: colon-separated directories.
 */
class GenStandHistoryWriter {
    @Test
    fun writeHistories() = runBlocking {
        val dirs = System.getenv("LJ_GENSTAND_HISTORY")?.split(':')?.filter(String::isNotEmpty) ?: return@runBlocking
        for (dir in dirs.map(::File)) {
            val lines = File(dir, "events.tsv").readLines().filter(String::isNotEmpty).map { l ->
                val f = l.split('\t')
                ListenEvent(
                    trackId = TrackId(f[0]), startedAtMs = f[1].toLong(), playedMs = f[2].toLong(), trackDurationMs = f[3].toLong(),
                    completed = f[4] == "1", skipped = f[5] == "1",
                    // PLAN_TRANSGRAPH: an empty start column is an imported listen (no origin, no listen time).
                    listenedMs = if (f[6].isEmpty()) null else f[2].toLong(),
                    origin = if (f[6].isEmpty()) null else ListenOrigin(start = ListenStart.valueOf(f[6])),
                ).serialize()
            }
            File(dir, "files/listening_history.log").writeText(lines.joinToString("\n", postfix = "\n"))
            var stored = emptyList<String>()
            val playlists = DefaultPlaylists(object : PlaylistStore {
                override suspend fun read(): List<String> = stored
                override suspend fun write(lines: List<String>) { stored = lines }
            })
            for (l in File(dir, "playlists.tsv").readLines().filter(String::isNotEmpty)) {
                val (name, smart, ids) = l.split('\t')
                val p = playlists.create(name, ids.split(',').filter(String::isNotEmpty).map(::TrackId))
                if (smart == "1") playlists.toggleIncludeInSmart(p.id)
            }
            File(dir, "files/playlists.txt").writeText(stored.joinToString("\n", postfix = "\n"))
            println("genstand history: ${dir.name} ${lines.size} listens")
        }
    }
}
