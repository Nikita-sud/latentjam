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

/**
 * Whether the absolute [path] names something inside [directory], compared by whole path components: `/tmp-old/a` is
 * not inside `/tmp`, and the directory is not inside itself. The iOS importer asks this before it removes a picked
 * file, which it may only do with the private copy the system's picker left in the app's temporary directory; any
 * doubt (a missing path, the root directory, a `..` on the way) is a no.
 */
internal fun isInsideDirectory(path: String?, directory: String?): Boolean {
    if (path.isNullOrEmpty() || directory.isNullOrEmpty()) return false
    val root = directory.trimEnd('/')
    if (root.isEmpty() || !root.startsWith('/')) return false
    if (path.split('/').any { it == ".." }) return false
    return path.startsWith("$root/") && path.trimEnd('/').length > root.length
}
