/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * iOS has no writable shared media index equivalent to MediaStore — tracks come from the app's own
 * sandbox, so correcting tags means writing the file, which waits on a tag-writing library.
 */
@Composable
actual fun rememberMetadataEditor(onSaved: () -> Unit): (TrackDescriptor, TrackEdits) -> Unit =
    { _, _ -> }
