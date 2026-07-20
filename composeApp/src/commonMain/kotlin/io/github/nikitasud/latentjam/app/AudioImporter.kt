/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/** Outcome of copying user-selected audio into LatentJam's on-device library. */
internal data class AudioImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
)

/** Android already discovers shared audio through MediaStore; iOS needs a Files picker. */
internal expect val audioImportAvailable: Boolean

/**
 * Returns a stable launcher for the platform's audio-file picker.
 *
 * Imported files remain on the device. iOS copies them into LatentJam's user-visible Documents
 * folder so playback, tag reading, and waveform embeddings do not depend on a temporary security
 * grant from another Files provider.
 */
@Composable
internal expect fun rememberAudioImporter(
    onResult: (AudioImportResult) -> Unit,
): () -> Unit
