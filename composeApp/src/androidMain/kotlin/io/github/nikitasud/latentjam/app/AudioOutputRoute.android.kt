/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberAudioOutputRoute(): AudioOutputRoute? {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var route by remember(manager) { mutableStateOf(manager?.let(::currentOutputRoute)) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                route = currentOutputRoute(manager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                route = currentOutputRoute(manager)
            }
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        route = currentOutputRoute(manager)
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    return route
}

/**
 * Bluetooth wins over a wire, a wire over the speaker: that is the order Android itself routes
 * media in, so the status names the device that is actually sounding.
 */
private fun currentOutputRoute(manager: AudioManager): AudioOutputRoute {
    val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    devices.firstOrNull { it.type in BLUETOOTH_TYPES }?.let { device ->
        return AudioOutputRoute(AudioOutputKind.BLUETOOTH, device.displayName())
    }
    devices.firstOrNull { it.type in WIRED_TYPES }?.let { device ->
        return AudioOutputRoute(AudioOutputKind.WIRED, device.displayName())
    }
    return AudioOutputRoute(AudioOutputKind.SPEAKER, null)
}

/** The product name, unless it is only the phone's own model echoed back. */
private fun AudioDeviceInfo.displayName(): String? =
    productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != Build.MODEL }

private val BLUETOOTH_TYPES: Set<Int> = buildSet {
    add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
}

private val WIRED_TYPES: Set<Int> = buildSet {
    add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
    add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
    add(AudioDeviceInfo.TYPE_USB_DEVICE)
    add(AudioDeviceInfo.TYPE_LINE_ANALOG)
    add(AudioDeviceInfo.TYPE_LINE_DIGITAL)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(AudioDeviceInfo.TYPE_USB_HEADSET)
}

/** Android has no public output picker; Bluetooth settings is where a listener changes it. */
@Composable
internal actual fun rememberAudioOutputChooser(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // A device without Bluetooth settings has nowhere else to send this.
            }
        }
    }
}
