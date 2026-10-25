/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/**
 * The cover the system shows for a track that has none — notification, lock screen, Now Playing,
 * a car — drawn as the app draws it (the app's `CoverPlaceholder`): one quiet tile in the theme's
 * colours under LatentJam's waveform mark, the same for every track. The system used to get a
 * square in the track's own colour, so a coverless track looked one way in the app and another
 * everywhere else.
 *
 * The colours are the app theme's surfaceVariant and onSurfaceVariant at 45 % (`Theme.kt`).
 */
public data class CoverTileColors(public val backgroundArgb: Int, public val markArgb: Int)

public fun coverTileColors(dark: Boolean): CoverTileColors =
    if (dark) CoverTileColors(0xFF2A2A2A.toInt(), 0x739A9A9A) else CoverTileColors(0xFFE3E3E8.toInt(), 0x732E2E2E)

/** Five rounded bars, tallest in the middle: heights, spacing and stroke as shares of the tile. */
public val COVER_TILE_WAVE_HEIGHTS: FloatArray = floatArrayOf(0.20f, 0.40f, 0.62f, 0.40f, 0.20f)
public const val COVER_TILE_WAVE_SPACING: Float = 0.13f
public const val COVER_TILE_WAVE_STROKE: Float = 0.055f
