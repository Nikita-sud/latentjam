/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/**
 * A document the listener picked as a ListenBrainz export. It is read as a stream when the import
 * runs, never loaded whole: exports reach hundreds of megabytes.
 */
internal class PickedExport(
    private val opener: () -> ExportByteSource,
    private val releaser: () -> Unit = {},
) {
    /** Opens the document for one pass; the caller closes the source. */
    fun open(): ExportByteSource = opener()

    /** Gives up the document (iOS deletes its private copy). */
    fun release() = releaser()
}

/** MIME types the Android picker offers: providers label .jsonl files variously or not at all. */
internal val LISTENBRAINZ_EXPORT_MIME_TYPES: Array<String> = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/json",
    "application/jsonl",
    "application/x-ndjson",
    "text/*",
    "application/octet-stream",
)

/**
 * Remembers a launcher for the platform document picker that returns a ListenBrainz export.
 * Cancellation is reported as [LocalBackupFileResult.Cancelled], never as a failure.
 */
@Composable
internal expect fun rememberListenBrainzExportPicker(
    onResult: (LocalBackupFileResult<PickedExport>) -> Unit,
): () -> Unit
