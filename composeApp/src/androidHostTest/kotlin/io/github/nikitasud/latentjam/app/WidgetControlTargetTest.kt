/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

internal class WidgetControlTargetTest {

    @Test
    fun grantedAccessKeepsTheControlsOnTheReceiver() {
        for (sdk in listOf(24, 32, 33, 37)) {
            assertEquals(WidgetControlTarget.RECEIVER, widgetControlTarget(sdk) { true }, "API $sdk")
        }
    }

    @Test
    fun missingAccessSendsEveryControlToTheApp() {
        for (sdk in listOf(24, 32, 33, 37)) {
            assertEquals(WidgetControlTarget.APP, widgetControlTarget(sdk) { false }, "API $sdk")
        }
    }

    @Test
    fun theCheckedPermissionFollowsTheApiLevel() {
        // Android 13 split audio access out of storage; an old grant must not count there.
        val onlyStorage = setOf("android.permission.READ_EXTERNAL_STORAGE")
        assertEquals(WidgetControlTarget.RECEIVER, widgetControlTarget(32) { it in onlyStorage })
        assertEquals(WidgetControlTarget.APP, widgetControlTarget(33) { it in onlyStorage })

        val onlyAudio = setOf("android.permission.READ_MEDIA_AUDIO")
        assertEquals(WidgetControlTarget.RECEIVER, widgetControlTarget(33) { it in onlyAudio })
        assertEquals(WidgetControlTarget.APP, widgetControlTarget(32) { it in onlyAudio })
    }
}
