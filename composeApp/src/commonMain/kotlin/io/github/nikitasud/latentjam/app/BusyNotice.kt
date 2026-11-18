/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CancellationException

/**
 * Runs [work] between [setBusy] true and false, then shows the message it ended with: what [work] returns (null shows
 * nothing), or [failure] when it threw.
 *
 * The flag goes down before the snackbar comes up. showSnackbar returns only when its snackbar has left, which takes
 * seconds, and longer when another one (an Undo, which stays until answered) is on screen before it: a flag held until
 * then kept every control it disables greyed out for that long.
 */
internal suspend fun SnackbarHostState.showAfterBusy(
    setBusy: (Boolean) -> Unit,
    failure: String,
    work: suspend () -> String?,
) {
    setBusy(true)
    val message = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        failure
    } finally {
        setBusy(false)
    }
    if (message != null) showSnackbar(message)
}
