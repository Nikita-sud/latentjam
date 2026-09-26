/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

/** Where a track sits on its release: disc and position, each null when the tags say nothing. */
public data class TrackPosition(
    public val discNumber: Int? = null,
    public val trackNumber: Int? = null,
)

/**
 * Reads track and disc numbers from the forms platforms and tags hand them over in.
 *
 * Kept apart from the scanners so both platforms agree on what counts as a number: a tag's
 * "03/12" is track 3, "0" is no track at all, and anything past [MAX_NUMBER] is garbage rather
 * than the thousandth song of a box set.
 */
public object TrackNumbers {

    /** Far above any real release; rejects the "9999" placeholders some taggers write. */
    private const val MAX_NUMBER = 999

    /**
     * MediaStore's `TRACK` column: `disc × 1000 + track`, so disc 2 track 5 is 2005 and a
     * single-disc track 7 is plain 7. Both the legacy and the modern Android scanner encode it
     * this way. A lone disc with no track (2000) keeps its disc.
     */
    public fun fromMediaStore(raw: Int): TrackPosition {
        if (raw <= 0) return TrackPosition()
        val disc = raw / 1000
        val track = raw % 1000
        return TrackPosition(
            discNumber = disc.takeIf { it in 1..MAX_NUMBER },
            trackNumber = track.takeIf { it in 1..MAX_NUMBER },
        )
    }

    /**
     * A tag value: the leading number of "3", "03", "3/12" or "3 of 12". Null for blanks, zero
     * and values that do not start with a number ("A1" vinyl sides stay unordered, not track 1).
     */
    public fun parse(value: String?): Int? {
        val text = value?.trim() ?: return null
        val digits = text.takeWhile { it in '0'..'9' }
        if (digits.isEmpty() || digits.length > 6) return null
        return digits.toInt().takeIf { it in 1..MAX_NUMBER }
    }
}
