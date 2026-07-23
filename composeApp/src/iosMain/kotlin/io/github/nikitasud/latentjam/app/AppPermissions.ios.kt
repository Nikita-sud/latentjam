/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSURL
import platform.MediaPlayer.MPMediaLibrary
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusAuthorized
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusNotDetermined
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

@OptIn(ExperimentalForeignApi::class)
internal class IosAppPermissions : AppPermissions {

    private val mutableAudioLibraryStatus = MutableStateFlow(readAudioStatus())
    override val audioLibraryStatus: StateFlow<AppPermissionStatus> =
        mutableAudioLibraryStatus.asStateFlow()

    private val mutableNotificationStatus = MutableStateFlow(AppPermissionStatus.NOT_REQUIRED)
    override val notificationStatus: StateFlow<AppPermissionStatus> =
        mutableNotificationStatus.asStateFlow()

    private val mutableNotificationPromptPending = MutableStateFlow(false)
    override val notificationPromptPending: StateFlow<Boolean> =
        mutableNotificationPromptPending.asStateFlow()

    override fun refresh() {
        mutableAudioLibraryStatus.value = readAudioStatus()
    }

    override fun markAudioPermissionRequested() = Unit

    override fun backgroundAnalysisNeedsNotifications() = Unit

    override fun resolveNotificationPrompt() = Unit

    override fun cancelNotificationPrompt() = Unit

    override fun openAppSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
            @Suppress("DEPRECATION")
            UIApplication.sharedApplication.openURL(it)
        }
    }

    override fun openNotificationSettings() = openAppSettings()

    private fun readAudioStatus(): AppPermissionStatus = when (MPMediaLibrary.authorizationStatus()) {
        MPMediaLibraryAuthorizationStatusAuthorized -> AppPermissionStatus.GRANTED
        MPMediaLibraryAuthorizationStatusNotDetermined -> AppPermissionStatus.NOT_DETERMINED
        else -> AppPermissionStatus.DENIED
    }
}

actual fun appPermissionsModule(): Module = module {
    single<AppPermissions> { IosAppPermissions() }
}
