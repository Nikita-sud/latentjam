/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual val lyricsFoldersNeedGrants: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

@Composable
internal actual fun rememberLyricsFolderControls(onRefused: () -> Unit): LyricsFolderControls {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { LyricsFolderStore.get(context) }
    val trees by store.trees.collectAsState()
    val currentOnRefused by rememberUpdatedState(onRefused)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        // Null is the listener backing out of the picker, not a failure.
        if (tree != null && !store.grant(tree)) currentOnRefused()
    }
    return remember(trees, picker, store) {
        LyricsFolderControls(
            folders = trees.map { LyricsFolder(it, lyricsFolderLabel(it)) },
            add = {
                try {
                    picker.launch(null)
                } catch (_: ActivityNotFoundException) {
                    currentOnRefused()
                }
            },
            remove = { store.release(it.id) },
        )
    }
}

@Composable
internal actual fun rememberLyricsSourcesRevision(): String {
    if (!lyricsFoldersNeedGrants) return LYRICS_SOURCES_VERSION
    val context = LocalContext.current.applicationContext
    val trees by remember(context) { LyricsFolderStore.get(context) }.trees.collectAsState()
    // Without a granted folder no sidecar is reachable here, so there is nothing beyond the file
    // itself to fold into the lyrics search index's key.
    if (trees.isEmpty()) return ""
    return (listOf(LYRICS_SOURCES_VERSION) + trees.sorted()).joinToString("\n")
}

/**
 * The storage path the picked folder stands for — `Music/Lyrics`, or `1A2B-3C4D/Music` on an SD
 * card — which is what the listener recognises from the picker. A whole volume shows as its id.
 */
private fun lyricsFolderLabel(tree: String): String {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrNull() ?: return tree
    val volume = documentId.substringBefore(':')
    val path = documentId.substringAfter(':', missingDelimiterValue = "")
    return when {
        path.isEmpty() -> volume
        volume == "primary" -> path
        else -> "$volume/$path"
    }
}

/**
 * The folder trees the listener granted for `.lrc` files, as tree URI strings, persisted
 * app-private. The system keeps the read permission itself (taken persistable in [grant]); this
 * list only says which of the app's persisted grants are meant for lyrics.
 */
internal class LyricsFolderStore private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    private val mutableTrees = MutableStateFlow(
        preferences.getStringSet(KEY_TREES, emptySet()).orEmpty().sorted(),
    )
    val trees: StateFlow<List<String>> = mutableTrees.asStateFlow()

    /**
     * Keeps read access to [tree] across restarts. Refuses (false) a tree from any provider other
     * than the device's own storage: only those document ids are paths a song can be matched to.
     */
    fun grant(tree: Uri): Boolean {
        if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) return false
        try {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            return false
        }
        update { it + tree.toString() }
        return true
    }

    /** Gives the folder's access back to the system and forgets it. */
    fun release(tree: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(tree),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Already revoked from outside the app; forgetting it is all that is left to do.
        }
        forget(tree)
    }

    /** Drops a grant the system revoked, noticed as a SecurityException when reading through it. */
    fun forget(tree: String) = update { it - tree }

    private fun update(change: (Set<String>) -> Set<String>) = synchronized(this) {
        val next = change(mutableTrees.value.toSet())
        if (next == mutableTrees.value.toSet()) return@synchronized
        preferences.edit().putStringSet(KEY_TREES, next).apply()
        mutableTrees.value = next.sorted()
    }

    companion object {
        private const val PREFERENCES_FILE = "lyrics-folders"
        private const val KEY_TREES = "trees"

        @Volatile
        private var instance: LyricsFolderStore? = null

        fun get(context: Context): LyricsFolderStore = instance ?: synchronized(this) {
            instance ?: LyricsFolderStore(context.applicationContext).also { instance = it }
        }
    }
}
