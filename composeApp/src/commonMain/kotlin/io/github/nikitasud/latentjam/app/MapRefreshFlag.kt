/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/**
 * Whether the Map refreshes small library changes instead of rebuilding them
 * (docs/map-cost-research-2026-10-25.md, implementation plan, piece 5). Off by default until the
 * device checks pass. A debuggable build can turn it on without UI: Android `adb shell run-as <pkg>
 * touch files/map_refresh_enabled`; iOS the `map_refresh_enabled` user default (e.g. the launch
 * argument `-map_refresh_enabled YES`).
 */
internal object MapRefreshFlag {
    const val DEFAULT: Boolean = false

    val enabled: Boolean get() = DEFAULT || mapRefreshOverride()

    /** Identity of the inputs a stored refresh state was built from; any difference rebuilds. */
    fun inputVersion(source: io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSource, dim: Int): String =
        "${io.github.nikitasud.latentjam.smart.cluster.LibraryLayout.cacheVersion}|$source|$dim|refresh-v1"
}

/** Platform debug override for [MapRefreshFlag]; false in release builds. */
internal expect fun mapRefreshOverride(): Boolean
