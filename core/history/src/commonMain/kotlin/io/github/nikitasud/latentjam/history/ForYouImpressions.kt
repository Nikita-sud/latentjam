/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One discovery offer the For You page actually made: which unheard track, in which section, on
 * which local day.
 *
 * Impressions exist for two reasons. Immediately: an unheard track offered for days and never
 * tapped should yield its slot — without a record of what was shown, "fresh" slots converge on
 * the same strangers forever (the documented daylist failure). Long term: knowing what was shown
 * is the precondition for honestly evaluating any recommendation offline — engagement data
 * without exposure data cannot distinguish "never wanted" from "never seen".
 */
public data class ForYouImpression(
    public val trackId: TrackId,
    /** Stable section identifier, e.g. "daypart" or "wildcard"; free-form by design. */
    public val section: String,
    /** Local calendar day of the offer, from [localTimePoint] — the page's unit of change. */
    public val epochDay: Long,
)

public interface ForYouImpressionStore {
    public suspend fun read(): List<String>
    public suspend fun write(lines: List<String>)
}

/**
 * The record of what For You offered, deduplicated per (track, day) and bounded on disk.
 *
 * A page rebuild is NOT a new offer: the page rebuilds on every history change, and recording
 * each rebuild would both bloat the file and let a pick cool itself down mid-day. One line per
 * track per local day is the honest unit — the page's own rotations are keyed to the day too.
 */
public class ForYouImpressions(
    private val store: ForYouImpressionStore,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var lines = mutableListOf<String>()
    private var seen = HashSet<String>()

    /** Records today's offers, skipping (track, day) pairs already on file. */
    public suspend fun record(impressions: List<ForYouImpression>): Unit = mutex.withLock {
        ensureLoaded()
        var appended = false
        for (impression in impressions) {
            val line = serialize(impression)
            if (!seen.add(line)) continue
            lines.add(line)
            appended = true
        }
        if (!appended) return
        if (lines.size > MAX_LINES) {
            val dropped = lines.subList(0, lines.size - MAX_LINES)
            dropped.forEach(seen::remove)
            dropped.clear()
        }
        store.write(lines)
    }

    /**
     * The most recent PRIOR-day offer for each track, before [beforeEpochDay]. Same-day offers
     * are excluded on purpose: a page must stay stable within its day, so only yesterday and
     * earlier may push a slot's occupant aside.
     */
    public suspend fun lastShownDays(beforeEpochDay: Long): Map<TrackId, Long> = mutex.withLock {
        ensureLoaded()
        val result = HashMap<TrackId, Long>()
        for (line in lines) {
            val impression = parse(line) ?: continue
            if (impression.epochDay >= beforeEpochDay) continue
            val prior = result[impression.trackId]
            if (prior == null || impression.epochDay > prior) {
                result[impression.trackId] = impression.epochDay
            }
        }
        result
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        lines = store.read().filter { parse(it) != null }.toMutableList()
        seen = lines.toHashSet()
        loaded = true
    }

    private companion object {
        /**
         * ~28 discovery slots/day × a season. Enough for cooldowns and a first offline eval,
         * small enough to never matter on disk.
         */
        const val MAX_LINES = 4000

        const val FORMAT_V1 = "v1"

        fun serialize(impression: ForYouImpression): String = listOf(
            FORMAT_V1,
            impression.trackId.value.encodeHex(),
            impression.section,
            impression.epochDay.toString(),
        ).joinToString("|")

        fun parse(line: String): ForYouImpression? {
            val parts = line.split('|')
            if (parts.size != 4 || parts[0] != FORMAT_V1) return null
            val id = parts[1].decodeHexOrNull() ?: return null
            val day = parts[3].toLongOrNull() ?: return null
            return ForYouImpression(trackId = TrackId(id), section = parts[2], epochDay = day)
        }

        fun String.encodeHex(): String = encodeToByteArray().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

        fun String.decodeHexOrNull(): String? {
            if (length % 2 != 0) return null
            return runCatching {
                ByteArray(length / 2) { index ->
                    substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }.decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
        }
    }
}
