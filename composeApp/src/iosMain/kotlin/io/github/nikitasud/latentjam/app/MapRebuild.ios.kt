/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState

/** Must match `BGTaskSchedulerPermittedIdentifiers` in iosApp/Info.plist. */
internal const val MAP_REBUILD_TASK: String = "io.github.nikitasud.latentjam.map-rebuild"

@OptIn(ExperimentalForeignApi::class)
internal actual fun scheduleMapRebuild() {
    val request = BGProcessingTaskRequest(MAP_REBUILD_TASK)
    request.requiresExternalPower = true
    request.requiresNetworkConnectivity = false
    if (!BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)) {
        println("Map rebuild: could not schedule the background task")
    }
}

internal actual fun onExternalPower(): Boolean {
    val device = UIDevice.currentDevice
    device.batteryMonitoringEnabled = true
    return when (device.batteryState) {
        UIDeviceBatteryState.UIDeviceBatteryStateCharging, UIDeviceBatteryState.UIDeviceBatteryStateFull -> true
        else -> false
    }
}
