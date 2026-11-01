/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal actual fun rememberLockPlayerDevice(enabled: Boolean): LockPlayerDevice {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // Back from the system page (or a revoke made there) shows at once.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canDrawOverlays = Settings.canDrawOverlays(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val lowRamGo = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }
    val vendorGated = remember { lockPlayerNeedsVendorNote(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()) }
    return LockPlayerDevice(
        access = lockPlayerAccess(enabled, Build.VERSION.SDK_INT, canDrawOverlays, lowRamGo),
        vendorGated = vendorGated,
        openOverlaySettings = {
            val packageUri = Uri.fromParts("package", context.packageName, null)
            // From Android 11 the first lands on the list of apps rather than LatentJam's page;
            // the notes say so.
            context.startFirstOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
                Intent(Settings.ACTION_SETTINGS),
            )
        },
        openAppDetails = {
            context.startFirstOf(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
                Intent(Settings.ACTION_SETTINGS),
            )
        },
    )
}

/** Some ROMs drop or guard settings pages; the next one is tried instead. */
private fun Context.startFirstOf(vararg intents: Intent) {
    for (intent in intents) {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
