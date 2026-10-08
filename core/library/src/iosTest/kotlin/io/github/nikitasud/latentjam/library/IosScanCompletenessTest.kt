/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the rule that decides whether one iOS scan may authorize pruning.
 *
 * The rule takes no Documents evidence by design: an empty `MPMediaQuery` answer is
 * indistinguishable from a query that has not loaded yet, so `complete = true` requires a nonempty
 * device result. Imports cannot vouch for Music.app, and a library that never held imports is
 * exactly the case where an empty snapshot used to look like confirmation that everything had been
 * deleted.
 */
class IosScanCompletenessTest {

    @Test
    fun aDeviceQueryThatAnsweredWithSongsIsComplete() {
        // The device half answered, so its rows and the app-owned ones may both be reconciled.
        assertTrue(
            isComplete(
                documents = listOf(track("a.mp3")),
                device = listOf(track("ios-media:1")),
            ),
        )
    }

    @Test
    fun anEmptyDeviceQueryIsIncompleteWhenNothingWasEverImported() {
        // The trigger from the audit: Documents is empty (no imports to contrast the answer with)
        // and Music.app returned no items while its query was still loading after a grant. Under
        // the previous evidence rule this snapshot claimed complete = true and authorized pruning
        // of audio/text vectors, remembered failures and queue rows for tracks nobody deleted.
        assertFalse(
            isComplete(
                documents = emptyList(),
                device = emptyList(),
            ),
        )
    }

    @Test
    fun anEmptyDeviceQueryStaysIncompleteWhenImportsExist() {
        // An import proves the files half ran, never that Music.app answered: the same empty
        // answer while the query loads must not drop the device rows an earlier scan indexed.
        assertFalse(
            isComplete(
                documents = listOf(track("a.mp3")),
                device = emptyList(),
            ),
        )
    }

    @Test
    fun documentsThatCouldNotBeEnumeratedAreIncomplete() {
        // A device answer cannot vouch for the app-owned half either: unreadable Documents may be
        // hiding deleted imports, so no snapshot taken now is authoritative.
        assertFalse(
            isComplete(
                documents = emptyList(),
                device = listOf(track("ios-media:1")),
                documentsReadable = false,
            ),
        )
    }

    @Test
    fun unreadableDocumentsStayIncompleteEvenWithTheDeviceSourceDisabled() {
        // Nothing was checked in the app-owned half, so no source setting can make this scan
        // authoritative.
        assertFalse(
            isComplete(
                documents = emptyList(),
                device = emptyList(),
                deviceSourceEnabled = false,
                documentsReadable = false,
            ),
        )
    }

    @Test
    fun aDisabledDeviceSourceIsNotExpectedToAnswer() {
        assertTrue(
            isComplete(
                documents = listOf(track("a.mp3")),
                device = emptyList(),
                deviceSourceEnabled = false,
            ),
        )
    }

    @Test
    fun aDisabledDeviceSourceAcceptsAnEmptyDocumentsFolder() {
        // The one deliberate exception: with Music.app switched off the user asked for an
        // app-owned library, and an empty readable Documents folder is then a real answer rather
        // than a pending query. The cost is that a device library which is genuinely empty never
        // authorizes pruning until that source is switched off.
        assertTrue(
            isComplete(
                documents = emptyList(),
                device = emptyList(),
                deviceSourceEnabled = false,
            ),
        )
    }

    /**
     * One case's verdict.
     *
     * [documents] names what the app-owned half holds in the scenario; it is not passed to the rule
     * on purpose, since after audit ios-scan-never-incomplete the verdict must not depend on it. A
     * scenario claiming imports while Documents cannot be enumerated is not a real one, so it is
     * rejected here instead of silently changing the case.
     */
    private fun isComplete(
        documents: List<TrackDescriptor> = emptyList(),
        device: List<TrackDescriptor> = emptyList(),
        deviceSourceEnabled: Boolean = true,
        documentsReadable: Boolean = true,
    ): Boolean {
        check(documents.isEmpty() || documentsReadable) {
            "Documents cannot both hold imports and be unenumerable"
        }
        return snapshotIsComplete(
            documentsReadable = documentsReadable,
            device = device,
            deviceSourceEnabled = deviceSourceEnabled,
        )
    }

    private fun track(id: String): TrackDescriptor = TrackDescriptor(id = TrackId(id), title = id)
}
