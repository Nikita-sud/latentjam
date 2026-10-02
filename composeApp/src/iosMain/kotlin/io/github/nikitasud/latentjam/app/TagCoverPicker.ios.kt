/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSinceNow

private val tagCoverDirectory: String? by lazy {
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String)?.let { "$it/$TAG_COVER_DIRECTORY" }
}

private fun tagCoverPath(reference: String): String? =
    reference.takeIf(::isTagCoverReference)?.let { name -> tagCoverDirectory?.let { "$it/$name" } }

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberTagCoverPicker(onResult: (TagCoverPick) -> Unit): () -> Unit =
    rememberSystemCoverPicker(
        import = { url ->
            tagCoverDirectory?.let { directory ->
                pruneStaleTagCovers(directory)
                importCoverImage(url, directory, TAG_COVER_MAX_EDGE, TAG_COVER_JPEG_QUALITY / 100.0, keepSmallPng = true)
            }
        },
        remove = { reference -> tagCoverPath(reference)?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) } },
    ) { outcome ->
        onResult(
            when (outcome) {
                is CoverPickOutcome.Picked -> TagCoverPick.Picked(outcome.reference)
                CoverPickOutcome.Cancelled -> TagCoverPick.Cancelled
                CoverPickOutcome.Failed -> TagCoverPick.Failed
            },
        )
    }

/** Covers picked in an editor the process lost; a live editor's pick is always younger. */
@OptIn(ExperimentalForeignApi::class)
private fun pruneStaleTagCovers(directory: String) {
    val manager = NSFileManager.defaultManager
    val names = manager.contentsOfDirectoryAtPath(directory, null)?.filterIsInstance<String>() ?: return
    for (name in names) {
        val path = "$directory/$name"
        val modified = manager.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate ?: continue
        if (-modified.timeIntervalSinceNow * 1000 > TAG_COVER_STALE_MS) manager.removeItemAtPath(path, null)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readTagCover(reference: String): ByteArray? = withContext(Dispatchers.IO) {
    tagCoverPath(reference)?.let { NSData.dataWithContentsOfFile(it) }?.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun deleteTagCover(reference: String) {
    withContext(Dispatchers.IO) { tagCoverPath(reference)?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) } }
}

internal actual fun tagCoverUri(reference: String): String? = tagCoverPath(reference)?.let { NSURL.fileURLWithPath(it).absoluteString }
