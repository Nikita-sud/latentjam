/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.pm.ApplicationInfo

internal actual fun isDebuggableBuild(): Boolean = runCatching {
    (AndroidAppContext.value.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}.getOrDefault(false)
