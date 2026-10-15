/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class AndroidEqualizerSettingsStoreTest {
    @Test fun failedMigrationKeepsLegacyCurveAndRetriesOnNextStart() {
        val preferences = MemoryPreferences("band_0" to 1500, "preset" to -1, "enabled" to true)
        var attempts = 0
        repeat(2) {
            val store = AndroidEqualizerSettingsStore(preferences.value) { attempts++; null }
            assertEquals(List(10) { 0 }, store.restore().levels)
            assertTrue(store.migrationPending)
            assertFalse(preferences.values.containsKey("software_version"))
            assertEquals(1500, preferences.values["band_0"])
        }
        assertEquals(2, attempts)
        assertEquals(0, preferences.writes)
    }

    @Test fun successfulMigrationPreservesFifteenDecibelsAndDoesNotProbeOrWriteAgain() {
        val preferences = MemoryPreferences("band_0" to 1500, "preset" to -1)
        val curve = List(10) { if (it % 2 == 0) 1500 else -1500 }
        val store = AndroidEqualizerSettingsStore(preferences.value) { curve }
        assertEquals(curve, store.restore().levels)
        assertFalse(store.migrationPending)
        assertEquals(1, preferences.values["software_version"])
        assertEquals(curve, AndroidEqualizerSettingsStore(preferences.value) { error("already migrated") }.restore().levels)
        assertEquals(1, preferences.writes)
    }

    @Test fun enableAndBassEditsDoNotCommitAFlatCurveAfterAFailedProbe() {
        val preferences = MemoryPreferences("band_0" to 900)
        val store = AndroidEqualizerSettingsStore(preferences.value) { null }
        val edited = store.restore().copy(enabled = true, bass = 700)
        store.persist(edited)
        assertTrue(store.migrationPending)
        assertFalse(preferences.values.containsKey("software_version"))
        val curve = List(10) { 900 }
        val retried = AndroidEqualizerSettingsStore(preferences.value) { curve }.restore()
        assertEquals(curve, retried.levels)
        assertTrue(retried.enabled)
        assertEquals(700, retried.bass)
    }

    @Test fun explicitBandPresetAndResetEditsWinOverPendingMigration() {
        for (choice in listOf(
            SoftwareEqualizerSettings(levels = List(10) { -500 }),
            SoftwareEqualizerSettings().withPreset(2),
            SoftwareEqualizerSettings(preset = 0),
        )) {
            val preferences = MemoryPreferences("band_0" to 900)
            val store = AndroidEqualizerSettingsStore(preferences.value) { null }
            store.restore()
            store.persist(choice, replaceCurve = true)
            assertFalse(store.migrationPending)
            val restored = AndroidEqualizerSettingsStore(preferences.value) { error("user already replaced the curve") }.restore()
            assertEquals(choice, restored)
            assertEquals(1, preferences.writes)
        }
    }

    @Test fun freshInstallDoesNotCreateAnAudioEffectAndInitializesOnlyOnce() {
        val preferences = MemoryPreferences()
        repeat(2) {
            assertEquals(SoftwareEqualizerSettings(),
                AndroidEqualizerSettingsStore(preferences.value) { error("no legacy settings") }.restore())
        }
        assertEquals(1, preferences.writes)
    }

    /** Implements only the preferences operations the real store uses; no Android runtime needed. */
    private class MemoryPreferences(vararg initial: Pair<String, Any>) {
        val values = initial.toMap().toMutableMap()
        var writes = 0
        val value = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0])
                "getInt", "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putInt", "putBoolean" -> { pending[args!![0] as String] = args[1]; proxy }
                    "apply" -> { values.putAll(pending); writes++; null }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
