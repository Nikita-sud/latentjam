/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.rememberLauncherForActivityResult
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.permission_audio_rationale
import io.github.nikitasud.latentjam.app.generated.resources.permission_grant
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.start(
            platformModule = module {
                single<Context> { applicationContext }
            },
        )
        setContent {
            var granted by remember { mutableStateOf(hasAudioPermission()) }
            if (granted) {
                NotificationPermissionRequest()
                App(
                    engine = AppGraph.engine,
                    library = AppGraph.library,
                    playback = AppGraph.playback,
                )
            } else {
                AudioPermissionGate(onGranted = { granted = true })
            }
        }
    }
}

/**
 * Asks once for notification permission, and gates nothing on the answer.
 *
 * Analysis progress and playback controls use notifications, but a refusal
 * costs visibility and nothing else — blocking the library behind it, the way
 * the audio permission legitimately is blocked, would be extortion for a
 * progress bar. Asked here rather than at the moment analysis starts because a
 * permission dialog landing on top of a screen the user just tapped a button on
 * reads as a failure of that button.
 */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Declining is a valid answer; there is nothing to fall back to. */ }
    LaunchedEffect(Unit) {
        val already = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!already) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
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

/** Minimal one-button gate; the system permission dialog does the real work. */
@Composable
private fun AudioPermissionGate(onGranted: () -> Unit) {
    val currentOnGranted by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) currentOnGranted() }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.permission_audio_rationale),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { launcher.launch(audioPermission) }) {
                    Text(stringResource(Res.string.permission_grant))
                }
            }
        }
    }
}
