/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_body
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_finish
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_later
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The one place tag saves meet the user: it launches consent prompts, hands finished saves to
 * whichever editor asked for them, and offers to finish saves a crash interrupted. Placed once, in
 * [App] — two hosts would each launch the same prompt.
 */
@Composable
expect fun TagWriteHost()

/**
 * Offers to finish the saves a crash interrupted. Android only: iOS finishes them on its own, so its
 * host never places this. "Later" lasts the session; the offer returns at the next launch.
 */
@Composable
internal fun <C> TagRecoveryPrompt(coordinator: TagWriteCoordinator<C>) {
    val pending by coordinator.pendingRecovery.collectAsState()
    val progress by coordinator.progress.collectAsState()
    val prompt by coordinator.prompt.collectAsState()
    val active by coordinator.active.collectAsState()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val busy = active || progress != null || prompt != null
    if (!recoveryPromptVisible(pending.size, busy, dismissed)) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(Res.string.tag_recovery_title)) },
        text = { Text(pluralStringResource(Res.plurals.tag_recovery_body, pending.size, pending.size)) },
        confirmButton = {
            TextButton(
                onClick = {
                    dismissed = true
                    coordinator.enqueueRecovery()
                },
            ) { Text(stringResource(Res.string.tag_recovery_finish)) }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text(stringResource(Res.string.tag_recovery_later)) }
        },
    )
}

/** Offer to finish interrupted saves when some are waiting, nothing is being saved, and the user has not said "Later" this session. */
internal fun recoveryPromptVisible(pending: Int, busy: Boolean, dismissed: Boolean): Boolean =
    pending > 0 && !busy && !dismissed
