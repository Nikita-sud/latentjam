/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.pm.ApplicationInfo
import java.io.File

/**
 * Debuggable builds only: `files/map_refresh_enabled` (created with `adb shell run-as <pkg> touch …`), or a debug
 * test build made with `-Platentjam.mapRefreshTestBuild=true` (resource `map_refresh_test_build`), which is on from
 * the first launch.
 */
internal actual fun mapRefreshOverride(): Boolean = runCatching {
    val context = AndroidAppContext.value
    debuggable() && (File(context.filesDir, "map_refresh_enabled").exists() || testBuild(context))
}.getOrDefault(false)

/** Debuggable builds only: `files/map_region_identity_off` turns region identity off while the refresh stays on. */
internal actual fun regionIdentityOffOverride(): Boolean = runCatching {
    debuggable() && File(AndroidAppContext.value.filesDir, "map_region_identity_off").exists()
}.getOrDefault(false)

private fun debuggable(): Boolean = (AndroidAppContext.value.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private fun testBuild(context: android.content.Context): Boolean {
    val id = context.resources.getIdentifier("map_refresh_test_build", "bool", context.packageName)
    return id != 0 && context.resources.getBoolean(id)
}
