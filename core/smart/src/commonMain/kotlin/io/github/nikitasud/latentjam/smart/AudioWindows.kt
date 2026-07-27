/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * Placement of the fixed-length analysis windows an encoder pools over one track.
 *
 * Shared by every platform backend so the audio index stays comparable across devices: two
 * installs that crop a track differently produce vectors that are not interchangeable, and the
 * index is keyed only by model version.
 *
 * ### Why six, and why the middle
 * Measured offline over 809 tracks against a dense 24-position grid, scoring retrieval on genre
 * (727 queries) and playlist (273 queries) judges:
 *
 * | windows | placement | genre R@1 | cosine to full 24-window vector |
 * |---------|-----------|-----------|---------------------------------|
 * | 3       | 20–80 %   | 0.298     | 0.978                           |
 * | **6**   | **20–80 %** | **0.333** | **0.990**                     |
 * | 12      | 15–100 %  | 0.340     | 0.997                           |
 * | 6       | 0–100 %   | 0.311     | 0.987                           |
 *
 * Six is the knee: it captures +11 % relative over three for twice the work, where twelve adds
 * only +2 % for four times the work — inside the run-to-run noise of the sweep. Confining the
 * spread to 20–80 % matters as much as the count, because intros, outros, fades and trailing
 * silence pull the pooled vector toward a generic "quiet" direction shared by unrelated tracks.
 */
public object AudioWindows {

    /**
     * Window start offsets in milliseconds, ascending, each fully inside the track.
     *
     * A track no longer than one window — or one whose duration is unknown, which on Android
     * means MediaStore had no value — yields a single window at the start, matching how the
     * caller zero-pads a short crop.
     */
    public fun startsMs(durationMs: Long?, windowMs: Long): List<Long> {
        if (durationMs == null || durationMs <= windowMs) return listOf(0L)
        val span = durationMs - windowMs
        // distinct(): a track only milliseconds longer than one window collapses several
        // fractions onto the same offset, and each duplicate would cost a decode and an inference
        // for a crop already pooled.
        return POSITIONS.map { fraction -> (span * fraction).toLong() }.distinct()
    }

    /** Even spread across 20–80 % of the seekable span; see the class note for the measurement. */
    private val POSITIONS = listOf(0.2, 0.32, 0.44, 0.56, 0.68, 0.8)
}
