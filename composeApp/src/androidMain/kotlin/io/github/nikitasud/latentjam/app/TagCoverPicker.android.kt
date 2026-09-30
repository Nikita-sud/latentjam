/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.net.Uri
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun tagCoverDirectory(): File = File(AndroidAppContext.value.filesDir, TAG_COVER_DIRECTORY)

private fun tagCoverFile(reference: String): File? =
    reference.takeIf(::isTagCoverReference)?.let { File(tagCoverDirectory(), it) }

@Composable
internal actual fun rememberTagCoverPicker(onResult: (TagCoverPick) -> Unit): () -> Unit {
    val pick = rememberSystemCoverPicker(
        key = "tag-cover",
        import = { resolver, uri ->
            pruneStaleTagCovers()
            importCoverImage(resolver, uri, tagCoverDirectory(), TAG_COVER_RULE)
        },
        isReference = ::isTagCoverReference,
        delete = { deleteTagCover(it) },
    ) { outcome ->
        onResult(
            when (outcome) {
                is CoverPickOutcome.Picked -> TagCoverPick.Picked(outcome.reference)
                CoverPickOutcome.Cancelled -> TagCoverPick.Cancelled
                CoverPickOutcome.Failed -> TagCoverPick.Failed
            },
        )
    }
    return pick
}

/** Covers picked in an editor the process lost; a live editor's pick is always younger. */
private fun pruneStaleTagCovers() {
    val cutoff = System.currentTimeMillis() - TAG_COVER_STALE_MS
    tagCoverDirectory().listFiles()?.forEach { file ->
        if (file.isFile && file.lastModified() < cutoff) file.delete()
    }
}

internal actual suspend fun readTagCover(reference: String): ByteArray? = withContext(Dispatchers.IO) {
    tagCoverFile(reference)?.takeIf { it.isFile }?.readBytes()
}

internal actual suspend fun deleteTagCover(reference: String) {
    withContext(Dispatchers.IO) { tagCoverFile(reference)?.delete() }
}

internal actual fun tagCoverUri(reference: String): String? = tagCoverFile(reference)?.let { Uri.fromFile(it).toString() }
