/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * No-op until the iOS library source exists — there is nothing this app owns
 * to delete yet. Deliberately silent rather than pretending to succeed.
 */
@Composable
actual fun rememberTrackDeleter(onDeleted: () -> Unit): (TrackDescriptor) -> Unit = { }
