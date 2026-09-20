/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortLineOut
import platform.AVFAudio.AVAudioSessionPortUSBAudio
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.currentRoute
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue

@Composable
internal actual fun rememberAudioOutputRoute(): AudioOutputRoute? {
    val route = remember { mutableStateOf(currentOutputRoute()) }
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) {
            route.value = currentOutputRoute()
        }
        route.value = currentOutputRoute()
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    return route.value
}

private fun currentOutputRoute(): AudioOutputRoute? {
    val port = AVAudioSession.sharedInstance().currentRoute.outputs
        .firstOrNull() as? AVAudioSessionPortDescription
        ?: return null
    val kind = when (port.portType) {
        AVAudioSessionPortBluetoothA2DP,
        AVAudioSessionPortBluetoothLE,
        AVAudioSessionPortBluetoothHFP,
        -> AudioOutputKind.BLUETOOTH
        AVAudioSessionPortHeadphones,
        AVAudioSessionPortUSBAudio,
        AVAudioSessionPortLineOut,
        -> AudioOutputKind.WIRED
        AVAudioSessionPortBuiltInSpeaker,
        AVAudioSessionPortBuiltInReceiver,
        -> AudioOutputKind.SPEAKER
        else -> AudioOutputKind.OTHER
    }
    val name = port.portName.takeIf { kind != AudioOutputKind.SPEAKER && it.isNotBlank() }
    return AudioOutputRoute(kind, name)
}

/** iOS offers no picker an app can present from a plain tap; the status states the route. */
@Composable
internal actual fun rememberAudioOutputChooser(): (() -> Unit)? = null
