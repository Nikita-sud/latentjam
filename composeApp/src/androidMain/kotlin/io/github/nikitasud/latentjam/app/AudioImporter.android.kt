/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

internal actual val audioImportAvailable: Boolean = false

@Composable
internal actual fun rememberAudioImporter(
    onResult: (AudioImportResult) -> Unit,
): () -> Unit = {}
