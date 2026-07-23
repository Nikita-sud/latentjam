/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Deletes a track's file from the device.
 *
 * Platform-specific because deletion is a privileged, user-confirmed
 * operation: on Android 11+ the system itself presents the confirmation and
 * performs the delete, which is exactly the guarantee we want for something
 * irreversible. [onDeleted] fires only when a delete actually happened, so
 * the caller can refresh its library snapshot.
 */
@Composable
expect fun rememberTrackDeleter(onDeleted: () -> Unit): (List<TrackDescriptor>) -> Unit
