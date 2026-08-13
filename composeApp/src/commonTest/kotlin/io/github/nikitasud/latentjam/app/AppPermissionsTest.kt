/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPermissionsTest {

    @Test
    fun permissionThatPlatformDoesNotNeedIsNotRequired() {
        assertEquals(
            AppPermissionStatus.NOT_REQUIRED,
            permissionStatus(required = false, granted = false, requestResolved = true),
        )
    }

    @Test
    fun unresolvedDenialRemainsRequestable() {
        assertEquals(
            AppPermissionStatus.NOT_DETERMINED,
            permissionStatus(required = true, granted = false, requestResolved = false),
        )
    }

    @Test
    fun resolvedDenialUsesSettingsRecoveryState() {
        assertEquals(
            AppPermissionStatus.DENIED,
            permissionStatus(required = true, granted = false, requestResolved = true),
        )
    }

    @Test
    fun grantWinsAfterReturningFromSettings() {
        assertEquals(
            AppPermissionStatus.GRANTED,
            permissionStatus(required = true, granted = true, requestResolved = true),
        )
    }

    @Test
    fun onlyAvailableAccessMakesACompletedLibraryScanAuthoritative() {
        assertTrue(AppPermissionStatus.GRANTED.authorizesCompleteLibraryScan())
        assertTrue(AppPermissionStatus.NOT_REQUIRED.authorizesCompleteLibraryScan())
        assertFalse(AppPermissionStatus.NOT_DETERMINED.authorizesCompleteLibraryScan())
        assertFalse(AppPermissionStatus.DENIED.authorizesCompleteLibraryScan())
    }

    @Test
    fun grantedPermissionDoesNotMakeAFailedSourceScanAuthoritative() {
        assertFalse(
            authoritativeLibrarySnapshot(
                scanCompleted = false,
                permissionStatus = AppPermissionStatus.GRANTED,
            ),
        )
    }

    @Test
    fun aConfirmedEmptyGrantedScanRemainsAuthoritative() {
        assertTrue(
            authoritativeLibrarySnapshot(
                scanCompleted = true,
                permissionStatus = AppPermissionStatus.GRANTED,
            ),
        )
    }

    @Test
    fun permissionAuthorityTransitionChangesTheIndexingRequestEvenForSameEmptyRows() {
        val ambiguous = AutomaticIndexingRequest(
            tracks = emptyList(),
            librarySnapshotAuthoritative = false,
        )
        val confirmedEmpty = AutomaticIndexingRequest(
            tracks = emptyList(),
            librarySnapshotAuthoritative = true,
        )

        assertFalse(ambiguous == confirmedEmpty)
    }
}
