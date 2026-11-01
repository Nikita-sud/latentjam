/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.lock_player_overlay_allow
import io.github.nikitasud.latentjam.app.generated.resources.lock_player_overlay_needed
import io.github.nikitasud.latentjam.app.generated.resources.lock_player_overlay_notice
import io.github.nikitasud.latentjam.app.generated.resources.lock_player_overlay_unsupported
import io.github.nikitasud.latentjam.app.generated.resources.lock_player_vendor_done
import io.github.nikitasud.latentjam.app.generated.resources.lock_player_vendor_note
import io.github.nikitasud.latentjam.app.generated.resources.lock_player_vendor_open
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Under the "Player on the lock screen" switch: what still stands between the setting and a player
 * that opens from any app, with the way into the system page that fixes it.
 */
@Composable
internal fun LockPlayerAccessNotes(
    device: LockPlayerDevice,
    vendorNoteHidden: Boolean,
    onHideVendorNote: () -> Unit,
    onOverlayNoteShown: () -> Unit,
) {
    val access = device.access
    val currentOnOverlayNoteShown by rememberUpdatedState(onOverlayNoteShown)
    LaunchedEffect(access) {
        if (access == LockPlayerAccess.NeedsOverlay) currentOnOverlayNoteShown()
    }
    when {
        access == LockPlayerAccess.NeedsOverlay -> LockPlayerNote(
            text = stringResource(Res.string.lock_player_overlay_needed),
        ) {
            TextButton(onClick = device.openOverlaySettings) {
                Text(stringResource(Res.string.lock_player_overlay_allow))
            }
        }
        access == LockPlayerAccess.Unsupported -> LockPlayerNote(
            text = stringResource(Res.string.lock_player_overlay_unsupported),
        )
        lockPlayerShowsVendorNote(access, device.vendorGated, vendorNoteHidden) -> LockPlayerNote(
            text = stringResource(Res.string.lock_player_vendor_note),
        ) {
            TextButton(onClick = device.openAppDetails) {
                Text(stringResource(Res.string.lock_player_vendor_open))
            }
            TextButton(onClick = onHideVendorNote) {
                Text(stringResource(Res.string.lock_player_vendor_done))
            }
        }
    }
}

@Composable
private fun LockPlayerNote(text: String, buttons: (@Composable () -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp)) {
        Text(
            text,
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (buttons != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { buttons() }
        }
    }
}

/**
 * Once per install, at launch: listeners who turned the setting on before the app asked for
 * anything learn why the player shows only from inside LatentJam. The flag is written before the
 * snackbar so a killed process cannot show it twice, and the snackbar waits for the notification
 * prompt, which would otherwise cover it until it expired.
 */
@Composable
internal fun LockPlayerOverlayNotice(snackbar: SnackbarHostState) {
    val settings = AppGraph.settings
    val enabled by settings.lockScreenPlayer.collectAsState()
    val device = rememberLockPlayerDevice(enabled)
    val currentDevice by rememberUpdatedState(device)
    LaunchedEffect(Unit) {
        if (!lockPlayerShouldNudge(currentDevice.access, settings.lockPlayerOverlayNoticeShown.value)) return@LaunchedEffect
        settings.setLockPlayerOverlayNoticeShown(true)
        AppGraph.permissions.notificationPromptPending.first { pending -> !pending }
        val result = snackbar.showSnackbar(
            message = getString(Res.string.lock_player_overlay_notice),
            actionLabel = getString(Res.string.lock_player_overlay_allow),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) currentDevice.openOverlaySettings()
    }
}
