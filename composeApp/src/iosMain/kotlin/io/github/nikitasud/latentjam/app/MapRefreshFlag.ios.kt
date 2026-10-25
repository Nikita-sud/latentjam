/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import platform.Foundation.NSUserDefaults

/** The `map_refresh_enabled` user default (a launch argument `-map_refresh_enabled YES` sets it). */
internal actual fun mapRefreshOverride(): Boolean = NSUserDefaults.standardUserDefaults.boolForKey("map_refresh_enabled")
