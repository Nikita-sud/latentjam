/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.SnackbarHostState
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.snack_library_refreshed
import io.github.nikitasud.latentjam.library.MusicLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/** What a finished library scan does with its result; see [LibraryScanOrder.finish]. */
internal enum class ScanOutcome { PUBLISH, SUPERSEDED, RESCAN }

/**
 * Orders what library scans publish. Scans run side by side (a return to the app, a delete, a tag save, a refresh) and
 * finish in any order, so each takes a number when it begins and asks before it publishes. Main thread only.
 */
internal class LibraryScanOrder {
    private var issued = 0L
    private var publishedScan = 0L
    private var localChange = 0L

    /** A scan begins: its number, for [finish]. */
    fun begin(): Long = ++issued

    /**
     * The shown library was changed without a scan (a track was hidden): a scan that began before this may have read
     * the state from before the change.
     */
    fun changedLocally() {
        localChange = ++issued
    }

    /**
     * What the scan numbered [token] does with its result. A scan that began later has published already:
     * [ScanOutcome.SUPERSEDED], the result is dropped. The library changed under it and no later scan has published:
     * [ScanOutcome.RESCAN], the result is dropped and the library is read again. Else [ScanOutcome.PUBLISH].
     */
    fun finish(token: Long): ScanOutcome = when {
        token < publishedScan -> ScanOutcome.SUPERSEDED
        token < localChange -> ScanOutcome.RESCAN
        else -> {
            publishedScan = token
            ScanOutcome.PUBLISH
        }
    }
}

/**
 * Runs the library refreshes Settings asks for, one at a time. A request made while one runs is not dropped: the
 * running one may already have read the library, so another pass follows it. Main thread only.
 */
internal class LibraryRefresher {
    private var running = false
    private var again = false
    private var againIndexes = false

    /**
     * [pass] refreshes the library once; its argument says whether to ask the system to index new files first, which
     * only the listener's own Refresh does. [onBusy] is told false before [done] runs: [done] shows a snackbar, which
     * lasts seconds (or waits behind another one), and the next request must not wait for it.
     */
    fun request(
        scope: CoroutineScope,
        indexNewFiles: Boolean,
        onBusy: (Boolean) -> Unit,
        pass: suspend (indexNewFiles: Boolean) -> Unit,
        done: suspend () -> Unit,
    ) {
        if (running) {
            again = true
            againIndexes = againIndexes || indexNewFiles
            return
        }
        running = true
        onBusy(true)
        scope.launch {
            try {
                var index = indexNewFiles
                while (true) {
                    pass(index)
                    if (!again) break
                    index = againIndexes
                    again = false
                    againIndexes = false
                }
            } finally {
                running = false
                again = false
                againIndexes = false
                onBusy(false)
            }
            done()
        }
    }
}

/**
 * Refreshes the library for Settings through this state's [LibraryRefresher]: [scan] reads it again, after the system
 * indexed new files when [indexNewFiles] (the Refresh row; a restored track or a toggled folder is in the index
 * already). "Refreshing" is shown while a pass runs, and the snackbar comes after it.
 */
internal fun LibraryScanState.requestRefresh(
    scope: CoroutineScope,
    library: MusicLibrary,
    snackbar: SnackbarHostState,
    indexNewFiles: Boolean,
    scan: suspend () -> Unit,
) {
    refresher.request(
        scope = scope,
        indexNewFiles = indexNewFiles,
        onBusy = { libraryRefreshing = it },
        pass = { index ->
            // Music copied in without the system being told is in no index yet (#8): the system indexes it first,
            // then the scan sees it.
            if (index) library.indexNewFiles()
            scan()
            hasHiddenTracks = library.hasHiddenTracks()
        },
        done = { snackbar.showSnackbar(getString(Res.string.snack_library_refreshed)) },
    )
}
