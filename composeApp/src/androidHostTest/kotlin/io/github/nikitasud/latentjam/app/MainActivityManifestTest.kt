/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Element

/**
 * What the main activity handles itself instead of being recreated. A recreation drops every
 * `remember`ed screen: a rotation closed the open album page and the player and went back to the
 * first tab. A new per-app language must still recreate it, so locale and layout direction stay out.
 */
class MainActivityManifestTest {

    private val handled: Set<String> by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest())
        val activities = document.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(ANDROID, "name").endsWith(".MainActivity") }
        main.getAttributeNS(ANDROID, "configChanges").split('|').filter(String::isNotEmpty).toSet()
    }

    @Test
    fun rotationAndResizingKeepTheActivity() {
        for (change in listOf("orientation", "screenSize", "screenLayout", "smallestScreenSize")) {
            assertTrue(change in handled, "$change recreates the activity: $handled")
        }
    }

    @Test
    fun keyboardsNavigationAndNightModeKeepTheActivity() {
        for (change in listOf("keyboardHidden", "keyboard", "navigation", "uiMode")) {
            assertTrue(change in handled, "$change recreates the activity: $handled")
        }
    }

    @Test
    fun aNewLanguageStillRecreatesTheActivity() {
        assertEquals(emptySet(), handled intersect setOf("locale", "layoutDirection"))
    }

    private fun manifest(): File {
        var dir: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (dir != null) {
            File(dir, "androidApp/src/main/AndroidManifest.xml").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        fail("Could not find androidApp's manifest from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
