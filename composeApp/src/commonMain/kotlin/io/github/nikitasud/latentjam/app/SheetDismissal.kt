/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Animates a modal sheet away with [hide], then reports it gone through [onHidden], whose argument
 * says whether the animation ran to its end.
 *
 * Back or a scrim tap during the animation makes Material3 start a hide of its own, which cancels
 * this one, and then call the sheet's onDismissRequest — which a sheet already dismissing ignores.
 * Reporting only after an uninterrupted hide therefore left the sheet composed while hidden, and its
 * window swallowed every touch until the app was killed. The report must not depend on the hide.
 */
internal fun CoroutineScope.hideSheetThen(hide: suspend () -> Unit, onHidden: (completed: Boolean) -> Unit) {
    launch { hide() }.invokeOnCompletion { cause -> onHidden(cause == null) }
}
