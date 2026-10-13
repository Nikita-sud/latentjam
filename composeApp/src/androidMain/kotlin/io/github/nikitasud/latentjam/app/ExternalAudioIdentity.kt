/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.FolderTree
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.net.URI
import java.net.URLDecoder

internal data class ExternalAudioTarget(
    val uri: String,
    val scheme: String,
    val media: ExternalMediaIdentity? = null,
    val file: ExternalFileIdentity? = null,
    val absolutePath: String? = null,
)

internal data class ExternalMediaIdentity(val volume: String, val id: Long)
internal data class ExternalFileIdentity(val volume: String, val relativePath: String)

/** Only known system providers give IDs meaning. An arbitrary provider's final number is no ID. */
internal fun externalAudioTarget(raw: String): ExternalAudioTarget? {
    val uri = try { URI(raw) } catch (_: Exception) { return null }
    val scheme = uri.scheme ?: return null
    if (scheme != "content" && scheme != "file") return null
    if (scheme == "file") {
        if (!uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
        val path = canonicalAudioPath(uri.path) ?: return null
        return ExternalAudioTarget(raw, scheme, absolutePath = path)
    }
    val authority = uri.authority ?: return null
    val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty).map {
        // Unlike form encoding, a literal '+' in a URI path means '+', never a space.
        URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
    }
    val media = if (authority == "media") mediaIdentity(segments) else null
    val documentId = when {
        segments.size == 2 && segments[0] == "document" -> segments[1]
        segments.size == 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
        else -> null
    }
    val documentMedia = if (authority == "com.android.providers.media.documents" &&
        documentId?.startsWith("audio:") == true
    ) documentId.substringAfter(':').toLongOrNull()?.takeIf { it >= 0 }?.let {
        ExternalMediaIdentity("external", it)
    } else null
    val file = if (authority == "com.android.externalstorage.documents") {
        documentId?.let(::documentAudioIdentity)
    } else null
    val absolutePath = if (authority == "com.android.providers.downloads.documents" &&
        documentId?.startsWith("raw:") == true
    ) canonicalAudioPath(documentId.removePrefix("raw:")) else null
    return ExternalAudioTarget(raw, scheme, media ?: documentMedia, file, absolutePath)
}

/** Ambiguous matches never pick the first row, even when the visible library is malformed. */
internal fun matchExternalAudio(
    target: ExternalAudioTarget,
    tracks: List<TrackDescriptor>,
    paths: Map<TrackId, String> = emptyMap(),
): TrackDescriptor? = tracks.filter { track ->
    track.audioUri == target.uri ||
        target.media?.let { wanted ->
            val known = track.audioUri?.let(::externalAudioTarget)?.media
            known != null && known.id == wanted.id && sameMediaVolume(
                wanted.volume, known.volume, track.storageVolume,
            )
        } == true ||
        target.file?.let { wanted ->
            val place = FolderTree.locate(track.storageVolume, track.folderPath)
            val fileName = track.fileName
            !fileName.isNullOrBlank() && place.volume?.lowercase() == wanted.volume &&
                (place.path.trimEnd('/') + "/" + fileName).trimStart('/') == wanted.relativePath
        } == true ||
        target.absolutePath?.let { wanted -> paths[track.id]?.let(::canonicalAudioPath) == wanted } == true
}.singleOrNull()

private fun sameMediaVolume(requested: String, known: String, storageVolume: String?): Boolean {
    val actual = storageVolume?.lowercase() ?: known
    if (requested == "external") return known != "internal" && actual != "internal"
    return requested == actual
}

private fun mediaIdentity(parts: List<String>): ExternalMediaIdentity? {
    val id = when {
        parts.size == 4 && parts[1] == "audio" && parts[2] == "media" -> parts[3]
        parts.size == 3 && parts[1] == "file" -> parts[2]
        else -> return null
    }.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return ExternalMediaIdentity(parts[0].lowercase(), id)
}

private fun documentAudioIdentity(id: String): ExternalFileIdentity? {
    if (':' !in id) return null
    val volume = id.substringBefore(':').lowercase().let {
        if (it == "primary") FolderTree.PRIMARY_VOLUME else it
    }
    if (volume != FolderTree.PRIMARY_VOLUME && !Regex("[0-9a-f]{4}-[0-9a-f]{4}").matches(volume)) return null
    val path = id.substringAfter(':')
    if (path.isBlank() || path.startsWith('/') || path.split('/').any { it == "." || it == ".." }) return null
    return ExternalFileIdentity(volume, path)
}

private fun canonicalAudioPath(path: String?): String? {
    if (path.isNullOrBlank() || !path.startsWith('/')) return null
    return try { File(path).canonicalPath } catch (_: Exception) { null }
}
