/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.predictor

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nikitasud.latentjam.ml.data.ListeningEventEntity
import java.io.File
import java.io.FileWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Append-only JSONL log of SMART recommendation activity. Captures two event types:
 *
 *   - `decision`: emitted on every RecommendationEngine pipeline run — records the
 *     seed track, centroid composition, filter counts, and the top-K head picks
 *     with cosine scores. Lets us audit "what did SMART pick, given what?".
 *
 *   - `outcome`: emitted on every track finalize — records whether the user kept
 *     playing or skipped, and how much of the track ran. Joins back to decisions
 *     via `(sessionId, sessionPos)`.
 *
 * File: `getExternalFilesDir("smart_sessions")/events.jsonl`. Pull from a USB-
 * connected device with:
 *
 *   adb pull /sdcard/Android/data/io.github.nikitasud.latentjam.debug/files/\
 *       smart_sessions/events.jsonl ./
 *
 * The log writes **regardless of `MlSettings.enableEventLogging`** — it's a
 * developer-facing debug log gated by being a debug build, not user-facing event
 * tracking. Privacy: paths and tag strings stay on the device's external files
 * dir, which app-private storage covers.
 *
 * JSON shape is hand-rolled (no gson/moshi dep). Records are single-line so
 * `wc -l events.jsonl` gives you the event count, and `grep '"type":"decision"'`
 * isolates pipeline outputs.
 */
@Singleton
class SmartSessionLog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    /**
     * Record one pipeline-decision event. Called from `RecommendationEngine.runPipeline`
     * after the head picks are finalized but before the queue replace fires.
     */
    fun recordDecision(
        sessionId: String,
        sessionPos: Int,
        seedUid: String,
        seedName: String?,
        seedArtists: String?,
        seedBpm: Float?,
        seedEnergy: Float?,
        seedDurationMs: Long,
        centroidSeedCount: Int,
        cooldownArtistCount: Int,
        filterLabel: String,
        poolSize: Int,
        tailSize: Int,
        headPicks: List<Pick>,
    ) {
        val nowMs = System.currentTimeMillis()
        scope.launch {
            val line = buildString(2048) {
                append('{')
                kv("type", "decision"); append(',')
                kv("ts", nowMs); append(',')
                kv("session_id", sessionId); append(',')
                kv("session_pos", sessionPos); append(',')
                kv("seed_uid", seedUid); append(',')
                kv("seed_name", seedName); append(',')
                kv("seed_artists", seedArtists); append(',')
                kv("seed_bpm", seedBpm); append(',')
                kv("seed_energy", seedEnergy); append(',')
                kv("seed_duration_ms", seedDurationMs); append(',')
                kv("centroid_seed_count", centroidSeedCount); append(',')
                kv("cooldown_artist_count", cooldownArtistCount); append(',')
                kv("filter_label", filterLabel); append(',')
                kv("pool_size", poolSize); append(',')
                kv("tail_size", tailSize); append(',')
                append("\"head\":")
                appendPickArray(headPicks)
                append('}')
            }
            appendLine(line)
        }
    }

    /**
     * Record one outcome event. Called from `ListeningEventRecorder.finalizeCurrent`
     * for every finalized track regardless of source — we filter SMART-vs-not at
     * read time using `sessionId`s present in the decision log.
     */
    fun recordOutcome(event: ListeningEventEntity, songName: String?, songArtists: String?) {
        scope.launch {
            val durationMs = event.trackDurationMs.coerceAtLeast(0L)
            val playedPct = if (durationMs > 0) event.playedMs.toDouble() / durationMs else 0.0
            val line = buildString(512) {
                append('{')
                kv("type", "outcome"); append(',')
                kv("ts", event.endedAtMs); append(',')
                kv("session_id", event.sessionId); append(',')
                kv("session_pos", event.sessionPos); append(',')
                kv("uid", event.songUid.toString()); append(',')
                kv("name", songName); append(',')
                kv("artists", songArtists); append(',')
                kv("completed", event.completed); append(',')
                kv("skipped", event.skipped); append(',')
                kv("played_ms", event.playedMs); append(',')
                kv("track_duration_ms", durationMs); append(',')
                kv("played_pct", playedPct)
                append('}')
            }
            appendLine(line)
        }
    }

    private suspend fun appendLine(line: String) {
        writeLock.withLock {
            try {
                val dir = File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }
                val file = File(dir, FILE_NAME)
                FileWriter(file, /*append=*/true).use { it.write(line + "\n") }
            } catch (e: Exception) {
                Timber.w(e, "SmartSessionLog: failed to append")
            }
        }
    }

    /**
     * A single head-pick row as captured at SMART decision time.
     *
     * - `score` — the raw cosine dot product against the query (centroid or
     *   predictor state), already post-BPM/energy-penalty. Range roughly [-1, 1].
     *   Kept for back-compat with `/tmp/format_sweep.py` and any prior JSONL
     *   readers that only know this field.
     * - `scorerScore` — the learned predictor's logit for this candidate, when
     *   the predictor pipeline ran. Null when the pipeline took the cosine
     *   fallback (predictor ONNX missing or threw at inference time).
     */
    data class Pick(
        val uid: String,
        val name: String?,
        val artists: String?,
        val bpm: Float?,
        val score: Float,
        val scorerScore: Float? = null,
    )

    private fun StringBuilder.appendPickArray(picks: List<Pick>) {
        append('[')
        picks.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append('{')
            kv("uid", p.uid); append(',')
            kv("name", p.name); append(',')
            kv("artists", p.artists); append(',')
            kv("bpm", p.bpm); append(',')
            kv("score", p.score); append(',')
            kv("scorer_score", p.scorerScore)
            append('}')
        }
        append(']')
    }

    // Lean JSON helpers. We hand-roll instead of pulling moshi/gson because this
    // file is the only place in the module that serializes JSON.
    private fun StringBuilder.kv(k: String, v: String?) {
        append('"').append(k).append("\":")
        if (v == null) append("null") else append('"').append(escape(v)).append('"')
    }
    private fun StringBuilder.kv(k: String, v: Long?) {
        append('"').append(k).append("\":").append(v?.toString() ?: "null")
    }
    private fun StringBuilder.kv(k: String, v: Int?) {
        append('"').append(k).append("\":").append(v?.toString() ?: "null")
    }
    private fun StringBuilder.kv(k: String, v: Float?) {
        append('"').append(k).append("\":").append(v?.toString() ?: "null")
    }
    private fun StringBuilder.kv(k: String, v: Double?) {
        append('"').append(k).append("\":").append(v?.toString() ?: "null")
    }
    private fun StringBuilder.kv(k: String, v: Boolean) {
        append('"').append(k).append("\":").append(if (v) "true" else "false")
    }
    private fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> out.append("\\\\")
            '"'  -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        return out.toString()
    }

    companion object {
        private const val DIR_NAME = "smart_sessions"
        private const val FILE_NAME = "events.jsonl"
    }
}
