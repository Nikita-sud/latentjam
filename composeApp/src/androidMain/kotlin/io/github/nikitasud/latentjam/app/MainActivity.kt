/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.rememberLauncherForActivityResult
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.permission_audio_rationale
import io.github.nikitasud.latentjam.app.generated.resources.permission_audio_settings_rationale
import io.github.nikitasud.latentjam.app.generated.resources.permission_continue
import io.github.nikitasud.latentjam.app.generated.resources.permission_grant
import io.github.nikitasud.latentjam.app.generated.resources.permission_not_now
import io.github.nikitasud.latentjam.app.generated.resources.permission_notifications_rationale
import io.github.nikitasud.latentjam.app.generated.resources.permission_notifications_title
import io.github.nikitasud.latentjam.app.generated.resources.permission_open_settings
import org.jetbrains.compose.resources.stringResource
import org.koin.dsl.module

/**
 * Android entry point: starts the shared [AppGraph] (contributing the
 * [Context] binding the MediaStore-backed library needs), gates on the
 * audio-media permission, then hosts the shared [App] composable.
 *
 * Lives in :composeApp's androidMain (not :androidApp) because AGP 9's
 * application plugin cannot host Kotlin alongside the KMP toolchain; the
 * :androidApp packaging shell declares this activity in its manifest by
 * fully-qualified name.
 */
class MainActivity : ComponentActivity() {

    private var audioPermissionGranted by mutableStateOf(false)
    private var audioPermissionGateDismissed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyStoredWindowTheme()
        installMediaBrowseCatalog(this)
        AppGraph.start(
            platformModule = module {
                single<Context> { applicationContext }
            },
        )
        audioPermissionGranted = hasAudioPermission()
        audioPermissionGateDismissed = getSharedPreferences(
            AUDIO_GATE_PREFERENCES,
            Context.MODE_PRIVATE,
        ).getBoolean(KEY_AUDIO_GATE_DISMISSED, false)
        setContent {
            if (audioPermissionGranted || audioPermissionGateDismissed) {
                App(
                    engine = AppGraph.engine,
                    library = AppGraph.library,
                    playback = AppGraph.playback,
                )
                ContextualNotificationPermissionRequest(AppGraph.permissions)
            } else {
                AudioPermissionGate(
                    permissions = AppGraph.permissions,
                    onGranted = {
                        audioPermissionGranted = true
                        setAudioGateDismissed(false)
                    },
                    onContinueWithoutAccess = { setAudioGateDismissed(true) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system settings page is a normal part of permission recovery.
        audioPermissionGranted = hasAudioPermission()
        AppGraph.permissions.refresh()
    }

    private fun setAudioGateDismissed(dismissed: Boolean) {
        audioPermissionGateDismissed = dismissed
        getSharedPreferences(AUDIO_GATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUDIO_GATE_DISMISSED, dismissed)
            .apply()
    }
}

/** Applies the persisted palette before Compose draws, avoiding a wrong-colour launch frame. */
private fun ComponentActivity.applyStoredWindowTheme() {
    val stored = runCatching {
        getSharedPreferences(APP_SETTINGS_FILE, Context.MODE_PRIVATE).getString(APP_THEME_KEY, null)
    }.getOrNull()
    val dark = when (stored) {
        ThemeMode.DARK.name -> true
        ThemeMode.LIGHT.name -> false
        else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }
    window.setBackgroundDrawable(ColorDrawable(if (dark) 0xFF0C0C0C.toInt() else 0xFFFAFAFA.toInt()))
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}

/** Offers notification access only while a real SMART scan has progress worth surfacing. */
@Composable
private fun ContextualNotificationPermissionRequest(permissions: AppPermissions) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val pending by permissions.notificationPromptPending.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissions.refresh() }
    if (!pending) return

    AlertDialog(
        onDismissRequest = permissions::resolveNotificationPrompt,
        title = { Text(stringResource(Res.string.permission_notifications_title)) },
        text = { Text(stringResource(Res.string.permission_notifications_rationale)) },
        confirmButton = {
            TextButton(
                onClick = {
                    // Resolve first so an Activity recreation while the system sheet is open does
                    // not launch a second request. A denial is non-blocking and never re-prompted.
                    permissions.resolveNotificationPrompt()
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            ) {
                Text(stringResource(Res.string.permission_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = permissions::resolveNotificationPrompt) {
                Text(stringResource(Res.string.permission_not_now))
            }
        },
    )
}

/** The media-read permission appropriate for this API level. */
private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        @Suppress("DEPRECATION")
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun Context.hasAudioPermission(): Boolean =
    checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED

/** Permission gate with a durable recovery path after Android stops showing its dialog. */
@Composable
private fun AudioPermissionGate(
    permissions: AppPermissions,
    onGranted: () -> Unit,
    onContinueWithoutAccess: () -> Unit,
) {
    val activity = LocalContext.current as ComponentActivity
    val currentOnGranted by rememberUpdatedState(onGranted)
    var permanentlyDenied by remember {
        mutableStateOf(
            permissions.audioLibraryStatus.value == AppPermissionStatus.DENIED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, audioPermission),
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissions.refresh()
        if (granted) {
            currentOnGranted()
        } else {
            permanentlyDenied =
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, audioPermission)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(
                        if (permanentlyDenied) {
                            Res.string.permission_audio_settings_rationale
                        } else {
                            Res.string.permission_audio_rationale
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = {
                        if (permanentlyDenied) {
                            permissions.openAppSettings()
                        } else {
                            // Persist before launching: the Activity may be recreated while the
                            // operating-system permission sheet owns the screen.
                            permissions.markAudioPermissionRequested()
                            launcher.launch(audioPermission)
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (permanentlyDenied) {
                                Res.string.permission_open_settings
                            } else {
                                Res.string.permission_grant
                            },
                        ),
                    )
                }
                TextButton(onClick = onContinueWithoutAccess) {
                    Text(stringResource(Res.string.permission_not_now))
                }
            }
        }
    }
}

private const val AUDIO_GATE_PREFERENCES = "audio_permission_gate"
private const val KEY_AUDIO_GATE_DISMISSED = "dismissed"
