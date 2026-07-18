/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** The fields a user may correct. Null means "leave as it was". */
data class TrackEdits(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
)

/**
 * Saves corrected tags for a track.
 *
 * ### What this changes, and what it does not
 * The edit is applied to the system media index, not written back into the audio file. In practice
 * that is durable — the index keeps the corrected values, and this app only ever re-reads the index.
 * But it is the file that is authoritative: if the file itself is modified, or the system performs a
 * full media re-scan, the original tags win and the correction is lost. Writing real tags would mean
 * shipping a tag-writing library for every container format, which is a much larger commitment.
 *
 * Modifying media the app does not own needs the user's consent, so saving may raise a system
 * dialog; [onSaved] runs only once the write has actually gone through.
 */
@Composable
expect fun rememberMetadataEditor(onSaved: () -> Unit): (TrackDescriptor, TrackEdits) -> Unit
