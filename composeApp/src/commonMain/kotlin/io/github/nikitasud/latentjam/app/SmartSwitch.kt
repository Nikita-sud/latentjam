/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether SMART exists for the listener ([AppSettings.smartEnabled]). False makes the app a plain player: a control that
 * starts SMART, names it or tunes it is not drawn. Read where the control is drawn, so a screen needs no extra parameter;
 * true outside the app's own roots (previews, tests).
 */
internal val LocalSmartEnabled = staticCompositionLocalOf { true }

/**
 * The pages a plain player has: the Map is laid out from the analysis SMART-off does not run, so it is not a page then.
 * The listener's saved layout is untouched, and the Map is back where it was once SMART is on again.
 */
internal fun List<StartPage>.forSmart(enabled: Boolean): List<StartPage> =
    if (enabled) this else filter { it != StartPage.MAP }.ifEmpty { listOf(StartPage.entries.first { it != StartPage.MAP }) }
