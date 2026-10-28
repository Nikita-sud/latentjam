/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.PickTrace
import io.github.nikitasud.latentjam.smart.chain.SmartSnapshot
import kotlin.concurrent.Volatile
import kotlin.math.round

/**
 * Research only: when [sink] is set, every chain pick reports the candidates it scored, one JSON line each (seed, pick,
 * position, and per candidate its id, base score, correction and the Rerank features). The offline stand sets it to
 * collect hops for refitting the judged correction; the app never does, and a null sink costs nothing.
 */
public object SmartTraceHook {
    @Volatile
    public var sink: ((String) -> Unit)? = null

    internal fun line(snapshot: SmartSnapshot, seed: TrackId, t: PickTrace): String = buildString {
        append("{\"seed\":\"").append(seed.value).append("\",\"pick\":\"").append(snapshot.tracks[t.row].id.value)
        append("\",\"position\":").append(t.position).append(",\"candidates\":[")
        var c = 0
        while (c + PickTrace.CANDIDATE_WIDTH <= t.candidates.size) {
            if (c > 0) append(',')
            append("[\"").append(snapshot.tracks[t.candidates[c].toInt()].id.value).append('"')
            for (k in 1 until PickTrace.CANDIDATE_WIDTH) append(',').append(round(t.candidates[c + k] * 10000f) / 10000f)
            append(']')
            c += PickTrace.CANDIDATE_WIDTH
        }
        append("]}")
    }
}
