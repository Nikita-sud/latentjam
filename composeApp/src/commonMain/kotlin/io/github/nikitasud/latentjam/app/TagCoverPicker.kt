/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/**
 * A cover picked for writing into tags (spec §3.4, §6.4). [Picked.reference] names a file in the
 * app's private `tag-covers` directory: saveable across rotation, read as bytes when the save starts.
 */
internal sealed interface TagCoverPick {
    data class Picked(val reference: String) : TagCoverPick
    data object Cancelled : TagCoverPick
    data object Failed : TagCoverPick
}

internal const val TAG_COVER_MAX_EDGE = 1000
internal const val TAG_COVER_JPEG_QUALITY = 90
internal const val TAG_COVER_PNG_KEEP_BYTES = 500L * 1024
internal const val TAG_COVER_DIRECTORY = "tag-covers"

/** A picked cover nobody saved (the process died with the editor open) is pruned after this long. */
internal const val TAG_COVER_STALE_MS = 24L * 60 * 60 * 1000

/**
 * A PNG already within 1000×1000 and under 500 KB is written byte for byte. Every other image is
 * re-encoded as JPEG q90, which also drops its EXIF, including GPS.
 */
internal fun keepsPickedPng(width: Int, height: Int, byteSize: Long): Boolean =
    width in 1..TAG_COVER_MAX_EDGE && height in 1..TAG_COVER_MAX_EDGE && byteSize in 1 until TAG_COVER_PNG_KEEP_BYTES

/**
 * [bytes] is the picked file read up to [TAG_COVER_PNG_KEEP_BYTES]: it is kept only when it is exactly
 * the [statedSize] the provider promised and still a PNG [keepsPickedPng] accepts, so a provider that
 * under-reports its length can never get an over-limit file stored as it is.
 */
internal fun keepsPickedPngBytes(bytes: ByteArray, statedSize: Long): Boolean {
    if (bytes.size.toLong() != statedSize) return false
    val size = pngSize(bytes) ?: return false
    return keepsPickedPng(size.first, size.second, statedSize)
}

private val TAG_COVER_REFERENCE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)")

internal fun isTagCoverReference(reference: String?): Boolean = reference != null && TAG_COVER_REFERENCE.matches(reference)

internal fun tagCoverMime(reference: String): String = if (reference.endsWith(".png")) "image/png" else "image/jpeg"

/** What a platform's system picker reports; each picker maps it to its own result type. */
internal sealed interface CoverPickOutcome {
    data class Picked(val reference: String) : CoverPickOutcome
    data object Cancelled : CoverPickOutcome
    data object Failed : CoverPickOutcome
}

/** Opens the system photo picker on explicit invocation; results arrive on the UI thread. */
@Composable
internal expect fun rememberTagCoverPicker(onResult: (TagCoverPick) -> Unit): () -> Unit

/** The picked cover's bytes, or null when the reference is not one or its file is gone. */
internal expect suspend fun readTagCover(reference: String): ByteArray?

internal expect suspend fun deleteTagCover(reference: String)

/** A URI the image loader can show the picked cover from. */
internal expect fun tagCoverUri(reference: String): String?
