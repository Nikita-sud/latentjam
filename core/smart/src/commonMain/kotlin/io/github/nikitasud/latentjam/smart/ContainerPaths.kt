/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * iOS file URLs inside the app's data container, without the container's UUID (`…/Containers/Data/Application/<UUID>/`).
 *
 * iOS gives the container a new UUID across app updates, reinstalls and restores, and every imported file's URL changes
 * with it, while the file does not. An identity built from the absolute URL therefore marked the whole imported
 * library as changed, and it was analysed again. Track ids were already the path relative to Documents
 * (IosMusicLibrary); identities follow with [relative]. Any other URI (Android's content:// and file paths, the iOS
 * media library's `ipod-library://`) is returned unchanged, so its identity is unchanged too.
 */
public object ContainerPaths {
    private const val UUID = "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"

    /** A device's container (`/var/mobile`, through `/private` or not) or a simulator's (`…/Devices/<UUID>/data`). */
    private const val ROOT_PATTERN =
        "file://(?:/private)?/var/mobile/Containers/Data/Application/$UUID/" +
            "|file:///Users/[^/?#]+/Library/Developer/CoreSimulator/Devices/$UUID/data/Containers/Data/Application/$UUID/"
    private val ROOT = Regex("^(?:$ROOT_PATTERN)")
    private const val MARK = "/Containers/Data/Application/"

    /** [uri] with its container root replaced by a fixed marker; any other URI as it is. */
    public fun relative(uri: String?): String? {
        if (uri == null || !uri.startsWith("file://") || MARK !in uri) return uri
        val root = ROOT.find(uri) ?: return uri
        return CONTAINER_MARKER + uri.substring(root.range.last + 1)
    }

    /** The container root [uri] lies in (`file://…/Application/<UUID>/`), or null. */
    public fun root(uri: String): String? = if (MARK in uri) ROOT.find(uri)?.value else null

    /** [uri] moved into the container at [root], or null when [uri] is in no container. */
    public fun moved(uri: String, root: String): String? {
        if (MARK !in uri) return null
        val current = ROOT.find(uri) ?: return null
        return root + uri.substring(current.range.last + 1)
    }

    /** Every container root named in [text]: the absolute URLs an older build kept (its loudness cache, say). */
    public fun rootsIn(text: String): Set<String> =
        if (MARK !in text) emptySet() else Regex(ROOT_PATTERN).findAll(text).mapTo(LinkedHashSet()) { it.value }

    private const val CONTAINER_MARKER = "app-container:/"
}
