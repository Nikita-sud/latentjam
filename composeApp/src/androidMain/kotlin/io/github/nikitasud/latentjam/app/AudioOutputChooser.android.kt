/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaRouter
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.output_bluetooth
import io.github.nikitasud.latentjam.app.generated.resources.output_connect_device
import io.github.nikitasud.latentjam.app.generated.resources.output_current
import io.github.nikitasud.latentjam.app.generated.resources.output_headphones
import io.github.nikitasud.latentjam.app.generated.resources.output_phone_speaker
import io.github.nikitasud.latentjam.app.generated.resources.output_selection_failed
import io.github.nikitasud.latentjam.app.generated.resources.output_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Device selection only: media volume remains owned by Android's hardware/system controls. */
@Composable
internal actual fun rememberAudioOutputChooser(): (() -> Unit)? {
    var visible by remember { mutableStateOf(false) }
    if (visible) AudioOutputChooserSheet(onDismiss = { visible = false })
    return remember { { visible = true } }
}

private data class OutputChoice(
    val route: MediaRouter.RouteInfo,
    val name: String,
    val bluetooth: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
)

private fun MediaRouter.outputChoices(): List<OutputChoice> {
    val selected = getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
    return (0 until routeCount).map { getRouteAt(it) }
        // LatentJam plays locally. Do not advertise Cast/remote routes without a remote player.
        .filter {
            it.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_AUDIO != 0 &&
                it.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_LOCAL
        }
        .map {
            OutputChoice(
                route = it,
                name = it.name?.toString().orEmpty(),
                bluetooth = it.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH,
                selected = it === selected,
                enabled = it.isEnabled,
            )
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioOutputChooserSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val router = remember(context) {
        context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
    }
    val currentOutput = rememberAudioOutputRoute()
    var choices by remember(router) { mutableStateOf(router?.outputChoices().orEmpty()) }
    var failed by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    var dismissing by remember { mutableStateOf(false) }

    fun refresh() { choices = router?.outputChoices().orEmpty() }
    PlatformForegroundEffect(onReturn = ::refresh)
    DisposableEffect(router) {
        val callback = object : MediaRouter.SimpleCallback() {
            override fun onRouteAdded(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = refresh()
        }
        // Passive observation only while open; pairing/discovery belongs to Bluetooth settings.
        router?.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)
        refresh()
        onDispose { router?.removeCallback(callback) }
    }

    fun dismiss() {
        if (dismissing) return
        dismissing = true
        if (reduceMotion) {
            onDismiss()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !dismissing,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.output_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { dismiss() }, enabled = !dismissing) {
                    Icon(Icons.Rounded.Close, stringResource(Res.string.action_close))
                }
            }
            choices.forEach { choice ->
                val kind = if (choice.selected) currentOutput?.kind else null
                val icon = when {
                    kind == AudioOutputKind.WIRED -> Icons.Rounded.Headphones
                    kind == AudioOutputKind.BLUETOOTH || choice.bluetooth -> Icons.Rounded.Bluetooth
                    else -> Icons.Rounded.Speaker
                }
                val name = if (choice.selected && currentOutput != null) {
                    currentOutput.name ?: stringResource(when (currentOutput.kind) {
                        AudioOutputKind.SPEAKER -> Res.string.output_phone_speaker
                        AudioOutputKind.WIRED -> Res.string.output_headphones
                        AudioOutputKind.BLUETOOTH -> Res.string.output_bluetooth
                        AudioOutputKind.OTHER -> Res.string.output_current
                    })
                } else choice.name
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(name) },
                    supportingContent = if (choice.selected) {
                        { Text(stringResource(Res.string.output_current)) }
                    } else null,
                    leadingContent = { Icon(icon, null, modifier = Modifier.size(24.dp)) },
                    trailingContent = if (choice.selected) {
                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    modifier = Modifier.semantics { selected = choice.selected }
                        .clickable(enabled = choice.enabled && !dismissing, role = Role.RadioButton) {
                            // A device may disappear between the callback and this tap.
                            val available = router?.outputChoices()?.any {
                                it.route === choice.route && it.enabled
                            } == true
                            if (!available) {
                                failed = true
                                refresh()
                            } else try {
                                router?.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, choice.route)
                                failed = false
                                refresh()
                            } catch (_: SecurityException) {
                                failed = true
                            }
                        },
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(stringResource(Res.string.output_connect_device)) },
                leadingContent = { Icon(Icons.Rounded.BluetoothSearching, null) },
                trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                modifier = Modifier.clickable(enabled = !dismissing, role = Role.Button) {
                    // Leave a visible error if settings cannot open. A successful handoff removes
                    // this sheet immediately so returning from pairing cannot bring it back.
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                        onDismiss()
                    } catch (_: ActivityNotFoundException) {
                        failed = true
                    } catch (_: SecurityException) {
                        failed = true
                    }
                },
            )
            if (failed) Text(
                stringResource(Res.string.output_selection_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}
