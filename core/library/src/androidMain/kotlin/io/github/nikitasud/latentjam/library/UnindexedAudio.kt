/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import java.io.File
import java.io.IOException

/**
 * Audio files under [roots] that MediaStore has no row for, by absolute path, for a Refresh on
 * Android 9 and older: there the media scanner takes single files only, so LatentJam finds what
 * it missed itself and hands it exactly those.
 *
 * Skips what the scanner skips: hidden files and folders, a folder holding a `.nomedia` entry
 * together with everything below it, and also `Android/data` and `Android/obb` at a volume's root,
 * which belong to other apps. [known] holds the paths MediaStore already has, in its own
 * spelling; a file found there is left alone. A walk stops at [limit] files, so one Refresh can
 * never hand the scanner more than that.
 */
internal fun unindexedAudioFiles(
    roots: List<File>,
    known: Set<String>,
    limit: Int = MAX_UNINDEXED_FILES,
): List<String> {
    val found = mutableListOf<String>()
    // Canonical paths already walked: a symbolic link back up the tree must not loop forever.
    val visited = HashSet<String>()
    val pending = ArrayDeque<Pair<File, Boolean>>()
    roots.forEach { pending.addLast(it to true) }
    while (pending.isNotEmpty() && found.size < limit) {
        val (directory, isRoot) = pending.removeFirst()
        val canonical = try {
            directory.canonicalPath
        } catch (_: IOException) {
            continue
        }
        if (!visited.add(canonical)) continue
        val entries = directory.listFiles() ?: continue
        if (entries.any { it.name == NO_MEDIA }) continue
        for (entry in entries.sortedBy(File::getName)) {
            val name = entry.name
            if (name.startsWith('.')) continue
            if (entry.isDirectory) {
                if (isRoot && name == ANDROID_FOLDER) {
                    // Android/media holds music some apps save; data and obb are other apps' own.
                    entry.listFiles()
                        ?.filter { it.isDirectory && it.name !in APP_PRIVATE_FOLDERS && !it.name.startsWith('.') }
                        ?.sortedBy(File::getName)
                        ?.forEach { pending.addLast(it to false) }
                } else {
                    pending.addLast(entry to false)
                }
            } else if (isScannableAudio(name) && entry.path !in known && entry.length() > 0) {
                found += entry.path
                if (found.size >= limit) break
            }
        }
    }
    return found
}

/** Whether Android's media scanner may file a file named [name] as audio LatentJam plays. */
internal fun isScannableAudio(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in SCANNABLE_AUDIO_EXTENSIONS

/**
 * A MediaStore selection over [column] for the paths [isScannableAudio] accepts, with its
 * arguments. SQLite's LIKE ignores ASCII case, as [isScannableAudio] does.
 */
internal fun audioPathSelection(column: String): Pair<String, Array<String>> {
    val extensions = SCANNABLE_AUDIO_EXTENSIONS.sorted()
    return extensions.joinToString(" OR ") { "$column LIKE ?" } to
        extensions.map { "%.$it" }.toTypedArray()
}

/**
 * The root of the storage volume holding [appSpecificDirectory], one of
 * `Context.getExternalFilesDirs`: `/storage/1234-ABCD` for `/storage/1234-ABCD/Android/data/…`.
 * Null for a path that is not inside a volume's `Android/data`.
 */
internal fun storageVolumeRoot(appSpecificDirectory: String): String? =
    appSpecificDirectory.substringBefore("/$ANDROID_FOLDER/data/", missingDelimiterValue = "")
        .takeIf(String::isNotEmpty)

private const val NO_MEDIA = ".nomedia"
private const val ANDROID_FOLDER = "Android"
private val APP_PRIVATE_FOLDERS = setOf("data", "obb")
private const val MAX_UNINDEXED_FILES = 20_000

/** Audio formats Android's media scanner knows and LatentJam plays. */
private val SCANNABLE_AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka", "aif", "aiff", "amr",
)
