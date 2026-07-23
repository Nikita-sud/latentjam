/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.module.Module
import org.koin.dsl.module

internal class AndroidAppPermissions(private val context: Context) : AppPermissions {

    private val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    private val mutableAudioLibraryStatus = MutableStateFlow(readAudioStatus())
    override val audioLibraryStatus: StateFlow<AppPermissionStatus> =
        mutableAudioLibraryStatus.asStateFlow()

    private val mutableNotificationStatus = MutableStateFlow(readNotificationStatus())
    override val notificationStatus: StateFlow<AppPermissionStatus> =
        mutableNotificationStatus.asStateFlow()

    private val mutableNotificationPromptPending = MutableStateFlow(false)
    override val notificationPromptPending: StateFlow<Boolean> =
        mutableNotificationPromptPending.asStateFlow()

    override fun refresh() {
        mutableAudioLibraryStatus.value = readAudioStatus()
        mutableNotificationStatus.value = readNotificationStatus()
        if (mutableNotificationStatus.value in setOf(
                AppPermissionStatus.GRANTED,
                AppPermissionStatus.NOT_REQUIRED,
            )
        ) {
            mutableNotificationPromptPending.value = false
        }
    }

    override fun markAudioPermissionRequested() {
        preferences.edit().putBoolean(KEY_AUDIO_REQUESTED, true).apply()
        refresh()
    }

    override fun backgroundAnalysisNeedsNotifications() {
        refresh()
        if (mutableNotificationStatus.value != AppPermissionStatus.NOT_DETERMINED) return
        mutableNotificationPromptPending.value = true
    }

    override fun resolveNotificationPrompt() {
        preferences.edit().putBoolean(KEY_NOTIFICATION_PROMPT_RESOLVED, true).apply()
        mutableNotificationPromptPending.value = false
        refresh()
    }

    override fun cancelNotificationPrompt() {
        mutableNotificationPromptPending.value = false
    }

    override fun openAppSettings() {
        launchSettings(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    override fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            launchSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                },
            )
        } else {
            openAppSettings()
        }
    }

    private fun launchSettings(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                // Heavily customised devices occasionally omit the specialised screen.
                if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) openAppSettings()
            }
    }

    private fun readAudioStatus(): AppPermissionStatus {
        return permissionStatus(
            required = true,
            granted = context.checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED,
            requestResolved = preferences.getBoolean(KEY_AUDIO_REQUESTED, false),
        )
    }

    private fun readNotificationStatus(): AppPermissionStatus {
        return permissionStatus(
            required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            requestResolved = preferences.getBoolean(KEY_NOTIFICATION_PROMPT_RESOLVED, false),
        )
    }

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private companion object {
        const val PREFERENCES_FILE = "permission_state"
        const val KEY_AUDIO_REQUESTED = "audio_permission_requested"
        const val KEY_NOTIFICATION_PROMPT_RESOLVED = "notification_prompt_resolved"
    }
}

actual fun appPermissionsModule(): Module = module {
    single<AppPermissions> { AndroidAppPermissions(context = get()) }
}
