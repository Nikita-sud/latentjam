/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.SidecarLyrics
import java.io.File

/**
 * The only provider whose document ids are file paths, which is what lets a track's MediaStore
 * path be turned into the id of the `.lrc` beside it. Trees from any other provider (Drive, a
 * USB stick app) cannot be matched to a track and are ignored.
 */
internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * The ExternalStorageProvider document id of the file at [absolutePath], or null for a path that
 * provider does not serve. `/storage/emulated/<user>/rest` is `primary:rest` — every user's own
 * shared storage is "primary" to the provider running as that user — and `/storage/<volume>/rest`
 * on an SD card or USB drive is `<VOLUME>:rest`, with the volume id upper-cased as the provider
 * spells it.
 */
internal fun externalStorageDocumentId(absolutePath: String): String? {
    val parts = absolutePath.removePrefix(STORAGE_PREFIX).takeIf { it != absolutePath } ?: return null
    val volume = parts.substringBefore('/')
    val afterVolume = parts.substringAfter('/', missingDelimiterValue = "")
    if (volume.isEmpty() || volume == "self") return null
    return if (volume == "emulated") {
        val user = afterVolume.substringBefore('/')
        val rest = afterVolume.substringAfter('/', missingDelimiterValue = "")
        if (user.isEmpty() || !user.all(Char::isDigit) || rest.isEmpty()) null else "primary:$rest"
    } else {
        if (afterVolume.isEmpty()) null else "${volume.uppercase()}:$afterVolume"
    }
}

/**
 * The document ids to try for the lyrics of [audioDocumentId] inside the granted tree rooted at
 * [treeDocumentId], in order, or empty when the song's folder is not inside that tree. A tree
 * covers its own folder and everything below it: `primary:Music` covers `primary:Music/a.mp3`
 * and `primary:Music/Album/a.mp3` but not `primary:MusicVideos/a.mp3`; a whole volume's tree
 * (`primary:`) covers every folder on it.
 */
internal fun sidecarDocumentIds(treeDocumentId: String, audioDocumentId: String): List<String> {
    val colon = audioDocumentId.indexOf(':')
    if (colon < 0) return emptyList()
    val slash = audioDocumentId.lastIndexOf('/')
    val folder = if (slash > colon) audioDocumentId.substring(0, slash) else audioDocumentId.substring(0, colon + 1)
    val fileName = audioDocumentId.substring(maxOf(slash, colon) + 1)
    val covered = folder == treeDocumentId ||
        folder.startsWith("$treeDocumentId/") ||
        (treeDocumentId.endsWith(':') && folder.startsWith(treeDocumentId))
    if (!covered) return emptyList()
    val prefix = if (folder.endsWith(':')) folder else "$folder/"
    return SidecarLyrics.candidateNames(fileName).map { prefix + it }
}

/**
 * The folder holding the document [documentId] — what a children query lists to see every
 * candidate [sidecarDocumentIds] returns for one song at once: `primary:Music/Song.lrc` is in
 * `primary:Music`, and a file at the root of a volume is in the volume itself (`primary:`).
 */
internal fun parentDocumentId(documentId: String): String {
    val colon = documentId.indexOf(':')
    val slash = documentId.lastIndexOf('/')
    return if (slash > colon) documentId.substring(0, slash) else documentId.substring(0, colon + 1)
}

/**
 * A folder's `.lrc` files from one children query, as (document id, size, last modified) rows,
 * keyed by lower-cased document id. Everything else in the folder — the songs themselves — is
 * dropped, so a pass over a large library keeps only the handful of rows it can use.
 */
internal fun sidecarListing(rows: List<Triple<String, Long, Long>>): Map<String, List<String>> {
    val listing = HashMap<String, MutableList<String>>()
    for ((documentId, size, modified) in rows) {
        if (!documentId.endsWith(".lrc", ignoreCase = true)) continue
        listing.getOrPut(documentId.lowercase()) { mutableListOf() } += "$documentId:$size:$modified"
    }
    return listing
}

/**
 * The fingerprint of the files among [candidates] that [listing] holds, "" when none. Matched
 * ignoring case: shared storage and SD cards are case-insensitive, so the reader's
 * `Song.lrc` opens a `Song.Lrc` too, and the fingerprint must follow the file it opens.
 */
internal fun listingFingerprint(candidates: List<String>, listing: Map<String, List<String>>): String =
    candidates.map { it.lowercase() }.distinct().flatMap { listing[it].orEmpty() }.joinToString("|")

/**
 * The fingerprint of the `.lrc` candidates beside the file at [audioPath] (Android 9 and older,
 * which read them directly): each present one's name, size and modification time, "" when there
 * is none. Two stats per song and no read of either file.
 */
internal fun siblingFingerprint(audioPath: String): String {
    val audio = File(audioPath)
    val folder = audio.parentFile ?: return ""
    return SidecarLyrics.candidateNames(audio.name).mapNotNull { name ->
        val sidecar = File(folder, name)
        if (sidecar.isFile) "$name:${sidecar.length()}:${sidecar.lastModified()}" else null
    }.joinToString("|")
}

private const val STORAGE_PREFIX = "/storage/"
