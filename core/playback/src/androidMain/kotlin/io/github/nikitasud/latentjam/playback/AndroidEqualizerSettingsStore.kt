/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import android.content.SharedPreferences

/** Keeps an unsuccessful legacy curve migration retryable, without overwriting later user edits. */
internal class AndroidEqualizerSettingsStore(
    private val preferences: SharedPreferences,
    private val migrateNativeCurve: () -> List<Int>?,
) {
    var migrationPending: Boolean = false
        private set

    fun restore(): SoftwareEqualizerSettings {
        val software = preferences.getInt("software_version", 0) == 1
        val hasLegacyCurve = preferences.contains("preset") || preferences.contains("band_0")
        val migrated = if (!software && hasLegacyCurve) migrateNativeCurve() else null
        migrationPending = !software && hasLegacyCurve && migrated == null
        val restored = SoftwareEqualizerSettings(
            enabled = preferences.getBoolean("enabled", false),
            levels = if (software) List(10) { preferences.getInt("software_band_$it", 0) }
                else migrated ?: List(10) { 0 },
            bass = preferences.getInt("bass_boost", 0),
            preset = if (software) preferences.getInt("software_preset", -1).takeIf { it >= 0 } else null,
        ).sanitized()
        // A failed probe must never publish a successful migration marker or a flat replacement.
        // Already migrated settings need no write on startup.
        if (!software && !migrationPending) persist(restored)
        return restored
    }

    fun persist(value: SoftwareEqualizerSettings, replaceCurve: Boolean = false) {
        val commitCurve = !migrationPending || replaceCurve
        preferences.edit().apply {
            putBoolean("enabled", value.enabled)
            putInt("bass_boost", value.bass)
            if (commitCurve) {
                putInt("software_version", 1)
                putInt("software_preset", value.preset ?: -1)
                value.levels.forEachIndexed { index, level -> putInt("software_band_$index", level) }
            }
        }.apply()
        // Enable/bass changes retain the legacy bands for a later retry. An explicit band edit,
        // preset or reset intentionally replaces that curve and must win over future migration.
        if (commitCurve) migrationPending = false
    }
}
