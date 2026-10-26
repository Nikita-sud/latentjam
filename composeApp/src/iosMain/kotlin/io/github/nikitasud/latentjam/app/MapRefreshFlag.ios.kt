/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSUserDefaults

/**
 * Debug binaries only: the `map_refresh_enabled` user default (a launch argument `-map_refresh_enabled YES`
 * sets it). A release binary ignores it, as the expect declaration says.
 */
@OptIn(ExperimentalNativeApi::class)
internal actual fun mapRefreshOverride(): Boolean =
    Platform.isDebugBinary && NSUserDefaults.standardUserDefaults.boolForKey("map_refresh_enabled")
