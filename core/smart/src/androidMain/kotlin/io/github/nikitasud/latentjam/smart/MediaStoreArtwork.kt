/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * MediaStore's cover locators for a song: its own, falling back to its album's.
 *
 * MediaStore files songs into one album by album title and folder, and `…/audio/albumart/<album>`
 * is the cover of one file of that album. Songs with no album tag (MediaStore files them under
 * their folder's name) and same-titled albums of different artists in one folder therefore all
 * showed one song's cover (issue #12). `…/audio/media/<song>/albumart` is the song's own cover on
 * Android 10 and later; older releases answer it with the album's cached cover, or with nothing
 * before one is cached. Keeping the song id in this locator lets readers also try its embedded
 * picture when the provider has none.
 *
 * A song with no cover of its own has nothing there, while its album may well have one (a rip
 * that embeds the cover in its first file only), so the locator names its album too, and every
 * provider reader can open the album's cover when the song's own is missing: [open],
 * [openDescriptor], [albumFallback]. The library's artwork loader tries embedded bytes before
 * this album fallback. MediaProvider opens both locators by path alone, so the query
 * is invisible to it and to any other app reading the locator.
 */
public object MediaStoreArtwork {
    private const val ALBUM_COVERS = "content://media/external/audio/albumart/"
    private const val SONGS = "content://media/external/audio/media/"
    private const val SONG_COVER = "/albumart"
    private const val ALBUM = "album"
    private const val VERSION = "v"

    /** The cover MediaStore keeps for [albumId]: one file's, shared by every song filed under it. */
    public fun albumCover(albumId: Long): String = "$ALBUM_COVERS$albumId"

    /** [trackId]'s own cover, falling back to [albumId]'s. */
    public fun trackCover(trackId: Long, albumId: Long): String =
        "$SONGS$trackId$SONG_COVER" + if (albumId > 0) "?$ALBUM=$albumId" else ""

    /**
     * [uri] without its album fallback: a song whose file names no album was filed by MediaStore
     * under its folder's name, and that "album's" cover is another song's (issue #12). Any other
     * locator stays as it is.
     *
     * With [revision] (the song's own file revision) the locator's version becomes that file's
     * alone. The version a scan gives is its album's, and the "album" of such a song is its whole
     * folder: one new file there gave every song of the folder a new locator, and every cache keyed
     * by it read all those covers again. Without [revision] the version stays.
     */
    public fun ownCoverOnly(uri: String, revision: String? = null): String {
        if (audioFallback(uri) == null) return uri
        val queryStart = uri.indexOf('?')
        val path = if (queryStart < 0) uri else uri.substring(0, queryStart)
        val kept = if (queryStart < 0) emptyList() else uri.substring(queryStart + 1).split('&').filterNot {
            val name = it.substringBefore('=')
            it.isEmpty() || name == ALBUM || (revision != null && name == VERSION)
        }
        val query = if (revision == null) kept else kept + "$VERSION=${ownVersion(revision)}"
        return if (query.isEmpty()) path else path + "?" + query.joinToString("&")
    }

    /** A short stable name for [revision] (64-bit FNV-1a): equal revisions give equal versions. */
    private fun ownVersion(revision: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (char in revision) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }

    /** The audio file named by a song-cover locator; never an arbitrary external or override URI. */
    public fun audioFallback(uri: String): String? {
        if (!uri.startsWith(SONGS)) return null
        val path = uri.substringBefore('?').substring(SONGS.length)
        if (!path.endsWith(SONG_COVER)) return null
        val id = path.removeSuffix(SONG_COVER)
        return if (id.isDecimal()) "$SONGS$id" else null
    }

    /** [uri] carrying [version], so every cache keyed by the string drops a cover that changed. */
    public fun versioned(uri: String, version: String): String =
        uri + (if ('?' in uri) '&' else '?') + "$VERSION=$version"

    /**
     * The album cover a [trackCover] falls back to, at the same version; null for any other
     * locator, which has nothing to fall back to.
     */
    public fun albumFallback(uri: String): String? {
        if (audioFallback(uri) == null) return null
        val queryStart = uri.indexOf('?')
        if (queryStart < 0) return null
        val path = uri.substring(SONGS.length, queryStart)
        if (!path.endsWith(SONG_COVER) || !path.removeSuffix(SONG_COVER).isDecimal()) return null
        val parameters = uri.substring(queryStart + 1).split('&').associate { parameter ->
            parameter.substringBefore('=') to parameter.substringAfter('=', missingDelimiterValue = "")
        }
        val album = parameters[ALBUM]?.takeIf { it.isDecimal() && it.toLongOrNull()?.let { id -> id > 0 } == true } ?: return null
        val cover = "$ALBUM_COVERS$album"
        return parameters[VERSION]?.let { versioned(cover, it) } ?: cover
    }

    /** Opens [uri]'s cover, or its album's when the song has none of its own. */
    public fun open(resolver: ContentResolver, uri: Uri): InputStream? =
        openWithFallback(uri.toString()) { resolver.openInputStream(Uri.parse(it)) }

    /** [open] as a descriptor, for readers that need the cover's length. */
    public fun openDescriptor(resolver: ContentResolver, uri: Uri): AssetFileDescriptor? =
        openWithFallback(uri.toString()) { resolver.openAssetFileDescriptor(Uri.parse(it), "r") }

    /**
     * [open]s [uri], or the album cover it falls back to when the song has none of its own.
     *
     * MediaProvider answers a song without a cover with FileNotFoundException, and a provider may
     * answer with no file at all; both fall back. Any other failure (a revoked permission, a volume
     * that went away) is not a missing cover and propagates.
     */
    internal fun <T : Any> openWithFallback(uri: String, open: (String) -> T?): T? {
        val missing = try {
            open(uri)?.let { return it }
            null
        } catch (notFound: FileNotFoundException) {
            notFound
        }
        val fallback = albumFallback(uri) ?: missing?.let { throw it } ?: return null
        return open(fallback)
    }

    private fun String.isDecimal(): Boolean = isNotEmpty() && all { it in '0'..'9' }
}
