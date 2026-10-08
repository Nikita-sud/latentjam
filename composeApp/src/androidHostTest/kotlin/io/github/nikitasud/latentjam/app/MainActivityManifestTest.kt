/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The manifest answers two questions no other test asks: what the activity handles instead of being
 * recreated, and what the app is allowed to reach.
 *
 * Recreation: a rotation used to drop every `remember`ed screen and closed the open album page and
 * the player. A new per-app language must still recreate the activity, so locale and layout
 * direction stay out of `android:configChanges`.
 *
 * Exposure and permissions: README.md and PRIVACY.md tell the user that the Android app has no
 * internet permission and that nothing leaves the device. Those promises hold only while the
 * manifest keeps them, so every component has to state whether other apps may reach it, the
 * internet permission must never appear, Media3's ACCESS_NETWORK_STATE must stay removed at merge
 * time, the legacy storage permissions must stay capped at the API levels that need them, and every
 * foreground service type must carry the permission Android 14 requires for it.
 *
 * The manifest is read as XML from androidApp and never from a build output, so these expectations
 * describe what the source declares before any library manifest is merged into it.
 */
class MainActivityManifestTest {

    private val document: Document by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(manifest())
    }

    private val handled: Set<String> by lazy {
        mainActivity().getAttributeNS(ANDROID, "configChanges").split('|').filter(String::isNotEmpty).toSet()
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

    /**
     * Android demands `android:exported` from components that have an intent filter, but the
     * attribute is what decides whether another app may start the component, so every activity,
     * receiver, service and provider states it. A filter added later then cannot silently expose a
     * component that never said `false`.
     */
    @Test
    fun everyComponentStatesItsExposure() {
        val allComponents = components()
        assertTrue(allComponents.isNotEmpty(), "no components found in ${manifest().path}")
        for (component in allComponents) {
            val exported = component.getAttributeNS(ANDROID, "exported")
            val filtered = if (component.hasIntentFilter()) " and has an intent filter" else ""
            assertTrue(
                exported == "true" || exported == "false",
                "${component.simpleName()} must declare android:exported as true or false$filtered, was '$exported'",
            )
        }
    }

    /**
     * Being exported is a decision, not a default. Only the components another app or the system
     * must reach are exported, and [EXPORTED_COMPONENTS] names the caller that forces each of them.
     * Everything the app talks to itself — the indexing service and the four widget receivers, the
     * action receiver included — stays private, and a new component that flips to `true` fails here
     * until someone writes down why it has to be reachable.
     */
    @Test
    fun onlyComponentsWithAnExternalCallerAreExported() {
        val exported = components()
            .filter { it.getAttributeNS(ANDROID, "exported") == "true" }
            .map { it.simpleName() }
            .toSet()
        assertEquals(
            EXPORTED_COMPONENTS.keys,
            exported,
            "the exported set changed: $EXPORTED_COMPONENTS lists the components that need it and why",
        )
    }

    /**
     * The widget receivers are reached through the app's own broadcasts, and the action receiver
     * accepts explicit-only commands; none of them may become visible to other apps.
     */
    @Test
    fun widgetReceiversStayPrivate() {
        for (name in PRIVATE_WIDGET_RECEIVERS) {
            val receiver = components("receiver").singleOrNull { it.simpleName() == name }
                ?: fail("receiver $name is missing from the manifest")
            assertEquals(
                "false",
                receiver.getAttributeNS(ANDROID, "exported"),
                "$name must not be reachable from other apps",
            )
        }
    }

    /**
     * "No internet permission" is what README.md and PRIVACY.md promise the user, and it is the
     * reason nothing can leave the device. A dependency that declares it must be answered with
     * tools:node="remove" instead of being accepted.
     */
    @Test
    fun theManifestNeverRequestsInternetAccess() {
        val names = permissionNames()
        assertTrue(FOREGROUND_SERVICE in names, "the manifest parsed no known permission: $names")
        // A tools:node="remove" line names the permission only to strip it from a library's
        // manifest: it is the answer this test asks for, not a request.
        val requested = permissions().filter { it.removal != "remove" }.map { it.name }.toSet()
        assertTrue(INTERNET !in requested, "the app must not be able to reach the network: $requested")
    }

    /**
     * Media3 declares ACCESS_NETWORK_STATE for its bandwidth meter. The player only ever reads
     * local files, so the permission is removed while manifests are merged; without the removal the
     * merged manifest ships a permission that PRIVACY.md does not list.
     */
    @Test
    fun networkStateIsRemovedAtMergeTime() {
        val networkState = permissions().singleOrNull { it.name == NETWORK_STATE }
            ?: fail("$NETWORK_STATE must stay declared so it can be removed, but the declaration is gone")
        assertEquals("remove", networkState.removal, "$NETWORK_STATE must keep tools:node=\"remove\"")
    }

    /**
     * The legacy storage permissions cover the API levels that have no per-app or per-file consent
     * to write. Above the cap the app asks for READ_MEDIA_AUDIO instead, so an uncapped declaration
     * would request far more than saving tags or deleting a file needs.
     */
    @Test
    fun legacyStoragePermissionsStopAtTheApiLevelThatNeedsThem() {
        for ((name, maxSdkVersion) in CAPPED_STORAGE_PERMISSIONS) {
            val permission = permissions().singleOrNull { it.name == name }
                ?: fail("$name is missing from the manifest")
            assertEquals(maxSdkVersion, permission.maxSdkVersion, "$name must be capped with maxSdkVersion")
            assertNull(permission.removal, "$name must stay declared for older devices, not be removed")
        }
    }

    /**
     * Android 14 kills a foreground service whose type has no matching FOREGROUND_SERVICE_*
     * permission, and the type is what the system shows the user. Playback is a `mediaPlayback`
     * service; analysis runs for minutes and says nothing about playback, so it is `dataSync`.
     */
    @Test
    fun foregroundServicesDeclareTheExpectedTypes() {
        val services = components("service").associateBy { it.simpleName() }
        for ((name, type) in FOREGROUND_SERVICE_TYPES) {
            val service = services[name] ?: fail("service $name is missing from the manifest")
            assertEquals(
                type,
                service.getAttributeNS(ANDROID, "foregroundServiceType"),
                "$name must declare its foreground service type",
            )
        }
    }

    /** A new type has to bring its permission along, or the service is killed on Android 14. */
    @Test
    fun everyDeclaredForegroundServiceTypeHasItsPermission() {
        val declared = components().flatMap { component ->
            component.getAttributeNS(ANDROID, "foregroundServiceType").split('|')
                .filter(String::isNotEmpty)
                .map { component.simpleName() to it }
        }
        assertTrue(
            declared.isNotEmpty(),
            "no foreground service type is declared although playback and analysis run in the background",
        )
        for ((component, type) in declared) {
            val permission = "android.permission.FOREGROUND_SERVICE_${type.toPermissionSuffix()}"
            assertTrue(permission in permissionNames(), "$component declares type '$type' without $permission")
        }
    }

    private fun manifest(): File {
        var dir: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (dir != null) {
            File(dir, "androidApp/src/main/AndroidManifest.xml").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        fail("Could not find androidApp's manifest from ${System.getProperty("user.dir")}")
    }

    private fun mainActivity(): Element =
        components("activity").singleOrNull { it.simpleName() == "MainActivity" }
            ?: fail("MainActivity is missing from ${manifest().path}")

    private fun components(): List<Element> = COMPONENT_TAGS.flatMap(::elements)

    /** Components of one kind, for the checks that name a single receiver, service or activity. */
    private fun components(tag: String): List<Element> = elements(tag)

    private fun elements(tag: String): List<Element> {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun permissions(): List<Permission> = elements("uses-permission").map { element ->
        Permission(
            name = element.getAttributeNS(ANDROID, "name"),
            maxSdkVersion = element.getAttributeNS(ANDROID, "maxSdkVersion").ifEmpty { null },
            removal = element.getAttributeNS(TOOLS, "node").ifEmpty { null },
        )
    }

    private fun permissionNames(): Set<String> = permissions().map { it.name }.toSet()

    private fun Element.simpleName(): String = getAttributeNS(ANDROID, "name").substringAfterLast('.')

    private fun Element.hasIntentFilter(): Boolean = childElements().any { it.elementName() == "intent-filter" }

    private fun Element.childElements(): List<Element> {
        val children = childNodes
        return (0 until children.length)
            .filter { children.item(it).nodeType == Node.ELEMENT_NODE }
            .map { children.item(it) as Element }
    }

    private fun Node.elementName(): String = localName ?: nodeName

    /** `mediaPlayback` becomes `MEDIA_PLAYBACK`, the tail of FOREGROUND_SERVICE_MEDIA_PLAYBACK. */
    private fun String.toPermissionSuffix(): String = buildString {
        for (character in this@toPermissionSuffix) {
            if (character.isUpperCase()) append('_')
            append(character.uppercaseChar())
        }
    }

    /** A `uses-permission` exactly as androidApp writes it, before any manifest merger runs. */
    private data class Permission(val name: String, val maxSdkVersion: String?, val removal: String?)

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val TOOLS = "http://schemas.android.com/tools"
        const val INTERNET = "android.permission.INTERNET"
        const val NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"
        const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"

        val COMPONENT_TAGS = listOf("activity", "receiver", "service", "provider")

        /** Exported components, and the external caller that forces each of them to be reachable. */
        val EXPORTED_COMPONENTS = mapOf(
            "MainActivity" to "the launcher entry point",
            "PlaybackService" to "bound by the system's media controls, Android Auto and headset buttons",
            "PlaybackTileService" to "bound by the system for the Quick Settings tile, behind BIND_QUICK_SETTINGS_TILE",
            "MediaButtonReceiver" to "restarts playback from a media button after the process was reclaimed",
        )

        val PRIVATE_WIDGET_RECEIVERS = listOf(
            "PlaybackWidgetProvider",
            "ArtworkPlaybackWidgetProvider",
            "DeckPlaybackWidgetProvider",
            "PlaybackWidgetActionReceiver",
        )

        val CAPPED_STORAGE_PERMISSIONS = mapOf(
            "android.permission.WRITE_EXTERNAL_STORAGE" to "28",
            "android.permission.READ_EXTERNAL_STORAGE" to "32",
        )

        val FOREGROUND_SERVICE_TYPES = mapOf(
            "PlaybackService" to "mediaPlayback",
            "IndexingService" to "dataSync",
        )
    }
}
