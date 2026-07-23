/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
