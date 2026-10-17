/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** How strongly the accent cloud colours pages and the player. */
enum class CloudStrength(internal val persistedValue: String, internal val alphaScale: Float) {
    OFF("off", 0f),
    SOFT("soft", 0.5f),
    NORMAL("normal", 1f),
    VIVID("vivid", 1.45f),
}

/** What fills the player behind the cover. */
enum class PlayerBackground(internal val persistedValue: String) {
    CLOUD("cloud"),
    BLURRED_COVER("cover"),
}

/**
 * One corner language for the whole app. Every rounded surface multiplies its radius by
 * [cornerScale], so menus, sheets, rows, covers and the mini player stay in proportion; circles
 * (artists, the shuffle button, a paused Play) stay circles.
 */
enum class ShapeStyle(internal val persistedValue: String, internal val cornerScale: Float) {
    ROUND("round", 1f),
    SOFT("soft", 0.6f),
    SHARP("sharp", 0.25f),
}

enum class MiniPlayerStyle(internal val persistedValue: String) {
    /** A floating card inset by 8 dp, the player's own corner. */
    CARD("card"),

    /** A full-width bar sitting on the bottom edge. */
    BAR("bar"),
}

/** The album grid's column count when the listener has pinched it; 0 keeps the adaptive grid. */
internal val ALBUM_COLUMN_CHOICES = listOf(2, 3, 4)

/** The custom accent palette: ten hues of like lightness, each readable against the dark floor. */
internal val CUSTOM_ACCENT_PALETTE: List<Long> = listOf(
    0xFFD8343F, 0xFFE8613C, 0xFFE39B2D, 0xFFD9C23A, 0xFF5DBB63,
    0xFF2FB4A6, 0xFF3E8EDE, 0xFF5B6CF0, 0xFF8B5CF6, 0xFFD45AA8,
)

/** Everything about how the app looks that the listener chooses, beyond theme and page layout. */
data class Appearance(
    val customAccent: Long = CUSTOM_ACCENT_PALETTE.first(),
    val pureBlack: Boolean = false,
    val cloud: CloudStrength = CloudStrength.NORMAL,
    val cloudMotion: Boolean = true,
    val playerBackground: PlayerBackground = PlayerBackground.CLOUD,
    val shape: ShapeStyle = ShapeStyle.ROUND,
    val miniPlayer: MiniPlayerStyle = MiniPlayerStyle.CARD,
    val miniPlayerPrevious: Boolean = true,
    val leftHanded: Boolean = false,
    val albumColumns: Int = 0,
)

internal val LocalAppearance = staticCompositionLocalOf { Appearance() }

/**
 * The ink of the app's controls (switches, chips, sliders, progress, Play) when the listener picked
 * their own accent; null leaves them on the neutral onSurface they use otherwise.
 */
internal val LocalControlInk = staticCompositionLocalOf<Color?> { null }

/** This radius in the listener's [ShapeStyle]. */
@Composable
@ReadOnlyComposable
internal fun Dp.corner(): Dp = this * LocalAppearance.current.shape.cornerScale

private const val APPEARANCE_PREFIX = "LJAP1|"

/**
 * One line, `key=value` pairs. Unknown keys and values are skipped, so a later build's settings
 * read safely in an older one and the other way round.
 */
internal fun encodeAppearance(appearance: Appearance): String = APPEARANCE_PREFIX + listOf(
    "accent" to appearance.customAccent.toString(16),
    "black" to appearance.pureBlack.toString(),
    "cloud" to appearance.cloud.persistedValue,
    "motion" to appearance.cloudMotion.toString(),
    "player" to appearance.playerBackground.persistedValue,
    "shape" to appearance.shape.persistedValue,
    "mini" to appearance.miniPlayer.persistedValue,
    "previous" to appearance.miniPlayerPrevious.toString(),
    "left" to appearance.leftHanded.toString(),
    "albums" to appearance.albumColumns.toString(),
).joinToString(";") { (key, value) -> "$key=$value" }

internal fun decodeAppearance(value: String?): Appearance {
    if (value == null || !value.startsWith(APPEARANCE_PREFIX)) return Appearance()
    val fields = value.removePrefix(APPEARANCE_PREFIX).split(';').mapNotNull { pair ->
        val at = pair.indexOf('=')
        if (at <= 0) null else pair.substring(0, at) to pair.substring(at + 1)
    }.toMap()
    val defaults = Appearance()
    return Appearance(
        customAccent = fields["accent"]?.toLongOrNull(16)?.takeIf { it in CUSTOM_ACCENT_PALETTE }
            ?: defaults.customAccent,
        pureBlack = fields["black"]?.toBooleanStrictOrNull() ?: defaults.pureBlack,
        cloud = CloudStrength.entries.firstOrNull { it.persistedValue == fields["cloud"] } ?: defaults.cloud,
        cloudMotion = fields["motion"]?.toBooleanStrictOrNull() ?: defaults.cloudMotion,
        playerBackground = PlayerBackground.entries.firstOrNull { it.persistedValue == fields["player"] }
            ?: defaults.playerBackground,
        shape = ShapeStyle.entries.firstOrNull { it.persistedValue == fields["shape"] } ?: defaults.shape,
        miniPlayer = MiniPlayerStyle.entries.firstOrNull { it.persistedValue == fields["mini"] }
            ?: defaults.miniPlayer,
        miniPlayerPrevious = fields["previous"]?.toBooleanStrictOrNull() ?: defaults.miniPlayerPrevious,
        leftHanded = fields["left"]?.toBooleanStrictOrNull() ?: defaults.leftHanded,
        albumColumns = fields["albums"]?.toIntOrNull()?.takeIf { it == 0 || it in ALBUM_COLUMN_CHOICES }
            ?: defaults.albumColumns,
    )
}
