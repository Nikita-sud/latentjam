/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/** iOS has no lock-screen player ([lockScreenPlayerAvailable] is false), so nothing to ask for. */
@Composable
internal actual fun rememberLockPlayerDevice(enabled: Boolean): LockPlayerDevice =
    LockPlayerDevice(access = null, vendorGated = false, openOverlaySettings = {}, openAppDetails = {})
