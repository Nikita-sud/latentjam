/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.media.MediaRouter2
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
    val router = remember(context) {
        context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
    }
    var route by remember(manager, router) { mutableStateOf(currentOutputRoute(manager, router)) }
    PlatformForegroundEffect(onReturn = { route = currentOutputRoute(manager, router) })
    DisposableEffect(manager, router) {
        fun refresh() { route = currentOutputRoute(manager, router) }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refresh()
            }
        }
        // Changing the selected output does not necessarily connect or disconnect a device.
        val routingCallback = object : MediaRouter.SimpleCallback() {
            override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) {
                refresh()
            }

            override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {
                refresh()
            }
        }
        manager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        router?.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, routingCallback)
        refresh()
        onDispose {
            manager?.unregisterAudioDeviceCallback(callback)
            router?.removeCallback(routingCallback)
        }
    }
    return route
}

/**
 * Ask for the selected media route. Connected outputs are merely candidates: a Bluetooth
 * headset can stay connected while the listener selects the phone speaker or plugs in a wire.
 * Older Android versions expose the system's selected route name through MediaRouter.
 */
private fun currentOutputRoute(manager: AudioManager?, router: MediaRouter?): AudioOutputRoute? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager?.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES)?.firstOrNull()?.let { device ->
            val kind = when (device.type) {
                in BLUETOOTH_TYPES -> AudioOutputKind.BLUETOOTH
                in WIRED_TYPES -> AudioOutputKind.WIRED
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                -> AudioOutputKind.SPEAKER
                else -> AudioOutputKind.OTHER
            }
            val name = if (kind == AudioOutputKind.SPEAKER) null else device.displayName()
            if (kind != AudioOutputKind.OTHER || name != null) return AudioOutputRoute(kind, name)
        }
    }
    val selected = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO) ?: return null
    val kind = when (selected.deviceType) {
        MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> AudioOutputKind.BLUETOOTH
        MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> AudioOutputKind.SPEAKER
        else -> AudioOutputKind.OTHER
    }
    // The default route's localized name also changes to Headphones / USB / HDMI as appropriate.
    val name = selected.name?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return AudioOutputRoute(kind, name)
}

private val MEDIA_ATTRIBUTES = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .build()

/** The product name, unless it is only the phone's own model echoed back. */
private fun AudioDeviceInfo.displayName(): String? =
    productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != Build.MODEL }

private val BLUETOOTH_TYPES: Set<Int> = buildSet {
    add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
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
    add(AudioDeviceInfo.TYPE_USB_ACCESSORY)
    add(AudioDeviceInfo.TYPE_LINE_ANALOG)
    add(AudioDeviceInfo.TYPE_LINE_DIGITAL)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(AudioDeviceInfo.TYPE_USB_HEADSET)
}

/** Android 14+ has a public output picker; earlier releases can open Bluetooth settings. */
@Composable
internal actual fun rememberAudioOutputChooser(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        {
            val opened = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                MediaRouter2.getInstance(context).showSystemOutputSwitcher()
            if (!opened) {
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
}
