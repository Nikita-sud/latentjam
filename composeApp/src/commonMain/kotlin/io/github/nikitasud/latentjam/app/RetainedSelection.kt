/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Track selections a dialog holds while it is open. They can be the whole library (the queue offered to a new playlist,
 * "select all" handed to the tag editor), and saved instance state has room for about a megabyte for the whole
 * Activity: tens of thousands of ids in it end the process when the app leaves the screen. They are kept here, for as
 * long as the process lives, and saved state holds only whether one was held; see [rememberRetainedSelection].
 */
internal object RetainedSelections {
    val pendingPlaylistTrackIds = mutableStateOf<List<String>>(emptyList())
    val bulkEditIds = mutableStateOf<List<String>?>(null)
}

/** What a screen does at its start with a selection kept outside saved state; see [retainedSelectionAtStart]. */
internal enum class RetainedStart { KEEP, CLEAR, LOST }

/**
 * [heldWhenSaved] is what the screen's saved state says (false for a screen that restores nothing), [holdsNow] whether
 * the process still has a selection. A new screen starts with none, whatever an older one left behind: CLEAR. A
 * recreated screen in the same process takes its selection back: KEEP. After a restarted process the ids are gone, and
 * whatever was open for them must go too: LOST.
 */
internal fun retainedSelectionAtStart(heldWhenSaved: Boolean, holdsNow: Boolean): RetainedStart = when {
    !heldWhenSaved -> RetainedStart.CLEAR
    holdsNow -> RetainedStart.KEEP
    else -> RetainedStart.LOST
}

/**
 * [holder] (one of [RetainedSelections]) as this screen's state, in place of a rememberSaveable of the whole
 * selection. [empty] is the value that holds nothing. [onLost] runs once when the selection did not survive a restart
 * of the process.
 */
@Composable
internal fun <T> rememberRetainedSelection(holder: MutableState<T>, empty: T, onLost: () -> Unit = {}): MutableState<T> {
    var heldWhenSaved by rememberSaveable { mutableStateOf(false) }
    val start = remember {
        retainedSelectionAtStart(heldWhenSaved, holdsNow = holder.value != empty).also {
            if (it == RetainedStart.CLEAR) holder.value = empty
        }
    }
    val holds = holder.value != empty
    SideEffect { heldWhenSaved = holds }
    if (start == RetainedStart.LOST) {
        val lost by rememberUpdatedState(onLost)
        LaunchedEffect(Unit) { lost() }
    }
    return holder
}
