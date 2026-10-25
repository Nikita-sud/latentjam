/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.pm.ApplicationInfo
import java.io.File

/** Debuggable builds only: `files/map_refresh_enabled` (created with `adb shell run-as <pkg> touch …`). */
internal actual fun mapRefreshOverride(): Boolean = runCatching {
    val context = AndroidAppContext.value
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
        File(context.filesDir, "map_refresh_enabled").exists()
}.getOrDefault(false)
