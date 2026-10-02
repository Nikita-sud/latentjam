/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * The order background analysis embeds a library in: a fixed pseudo-random shuffle.
 *
 * The scan hands the library over in title order, and analysing a big one takes hours (about
 * 2.5 h for 10,000 tracks on an emulator). Embedding in that order meant that for those hours
 * SMART could only choose among titles starting with B–D, whatever the seed. Ordering by a salted
 * hash of the track id spreads the analysed share evenly over the library from the first minute.
 *
 * The order depends on the ids alone — not on the scan's order or the library's size — so every
 * launch walks the same sequence: the engine skips what an earlier run already analysed, and a
 * track added later slots in without reshuffling the rest.
 */
internal fun analysisOrder(tracks: List<TrackDescriptor>): List<TrackDescriptor> =
    tracks.map { track -> AnalysisSlot(analysisRank(track.id), track) }
        .sortedWith(compareBy<AnalysisSlot> { it.rank }.thenBy { it.track.id.value })
        .map(AnalysisSlot::track)

private class AnalysisSlot(val rank: ULong, val track: TrackDescriptor)

/**
 * FNV-1a over the salted id, then a 64-bit finaliser: MediaStore ids are sequential numbers that
 * differ only in their last digits, and plain FNV leaves those too close to sort apart well.
 */
private fun analysisRank(id: TrackId): ULong {
    var hash = FNV_OFFSET_BASIS
    for (byte in (ANALYSIS_ORDER_SALT + id.value).encodeToByteArray()) {
        hash = (hash xor (byte.toULong() and 0xFFuL)) * FNV_PRIME
    }
    hash = (hash xor (hash shr 33)) * 0xFF51AFD7ED558CCDuL
    hash = (hash xor (hash shr 33)) * 0xC4CEB9FE1A85EC53uL
    return hash xor (hash shr 33)
}

/** Changing this reshuffles every library's analysis order once; there is no reason to. */
private const val ANALYSIS_ORDER_SALT = "latentjam-analysis-order-v1\u0000"
private const val FNV_OFFSET_BASIS = 0xCBF29CE484222325uL
private const val FNV_PRIME = 0x100000001B3uL
