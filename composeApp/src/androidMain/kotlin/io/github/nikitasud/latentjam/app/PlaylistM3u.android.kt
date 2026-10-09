/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/** MediaStore keeps each track's path, and [createdDocumentDirectory] reads the export's folder. */
internal actual val relativePlaylistPathsAvailable: Boolean = true

/** The Downloads provider. Only its `raw:` document ids spell out a file path. */
internal const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

/**
 * The folder a document created through the system picker landed in, as an absolute path in the
 * same form MediaStore gives track paths, or null when the provider does not reveal one.
 *
 * Every call is guarded: a provider's id is foreign input, and not knowing the folder only means
 * the playlist keeps absolute paths. [Environment.getExternalStorageDirectory] is deprecated for
 * file access, which this is not: it names the primary volume's root as this user sees it
 * (`/storage/emulated/<user>`), the root MediaStore's track paths start with.
 */
@Suppress("DEPRECATION")
internal fun createdDocumentDirectory(document: Uri): String? {
    val documentId = try {
        DocumentsContract.getDocumentId(document)
    } catch (_: Exception) {
        return null
    }
    val authority = try {
        document.authority
    } catch (_: Exception) {
        return null
    }
    val primaryStorageRoot = try {
        Environment.getExternalStorageDirectory().absolutePath
    } catch (_: Exception) {
        return null
    }
    return documentDirectoryPath(authority, documentId, primaryStorageRoot)
}

/**
 * The folder of the document [documentId] served by [authority], given the primary volume's
 * root, or null when the id is not a path the provider spells out.
 *
 * The device-storage provider names a file `primary:Music/Playlists/Mix.m3u8` on the phone's own
 * storage and `1A2B-3C4D:Playlists/Mix.m3u8` on an SD card or USB drive, which is mounted at
 * `/storage/1A2B-3C4D`. Its other roots (`home:` on some versions) and every cloud or app
 * provider use ids that are no path at all; so do the Downloads provider's `msf:` and numeric ids,
 * while its `raw:` ids carry the absolute path itself.
 */
internal fun documentDirectoryPath(
    authority: String?,
    documentId: String?,
    primaryStorageRoot: String,
): String? {
    if (documentId == null) return null
    val file = when (authority) {
        EXTERNAL_STORAGE_AUTHORITY -> {
            val volume = documentId.substringBefore(':', missingDelimiterValue = "")
            val path = documentId.substringAfter(':', missingDelimiterValue = "")
            when {
                path.isEmpty() -> null
                volume == PRIMARY_VOLUME && primaryStorageRoot.startsWith('/') ->
                    "${primaryStorageRoot.trimEnd('/')}/$path"
                volume.isVolumeUuid() -> "/storage/$volume/$path"
                else -> null
            }
        }
        DOWNLOADS_AUTHORITY -> documentId.removePrefix(RAW_DOWNLOAD_PREFIX)
            .takeIf { it != documentId && it.startsWith('/') }
        else -> null
    } ?: return null
    // "/Mix.m3u8" sits in the file-system root, which no track path can be relative to anyway.
    return file.trimEnd('/').substringBeforeLast('/').ifEmpty { null }
}

private const val PRIMARY_VOLUME = "primary"
private const val RAW_DOWNLOAD_PREFIX = "raw:"

/** A removable volume's id: its file-system UUID, hex digits and dashes (`1A2B-3C4D`). */
private fun String.isVolumeUuid(): Boolean =
    any { it != '-' } && all { it == '-' || it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
