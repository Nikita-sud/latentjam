/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * MlDiagnosticsFragment.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.settings.categories

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import io.github.nikitasud.latentjam.R
import io.github.nikitasud.latentjam.ml.MlSettings
import io.github.nikitasud.latentjam.ml.audio.NativeDecoderStats
import io.github.nikitasud.latentjam.ml.data.ListeningEventDao
import io.github.nikitasud.latentjam.ml.data.TrackEmbeddingDao
import io.github.nikitasud.latentjam.ml.encoder.EncoderRuntime
import io.github.nikitasud.latentjam.ml.predictor.PredictorRuntime
import io.github.nikitasud.latentjam.settings.BasePreferenceFragment
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only diagnostics surface for the smart-shuffle ML stack. Surfaces the runtime state that
 * `EncoderRuntime`, `NativeDecoderStats`, `PredictorRuntime`, `MlSettings`, and the listening-event
 * DAO accumulate but otherwise don't expose anywhere — answering questions like:
 * * Which encoder backend actually loaded? (QNN HTP vs CPU). If it's CPU on a Snapdragon device,
 *   why did QNN init fail?
 * * Of all the audio decode attempts so far, what fraction hit the fast native path? Of the
 *   fallbacks, are they "no .so on this device" (ABI strip) or "this format is genuinely
 *   unsupported"?
 * * Is the user running on the bundled predictor or has on-device fine-tuning written a
 *   personalized model? What versions are loaded?
 * * How many listening events are in the DB right now? (Lets us cross-check the retain worker.)
 *
 * Intentionally PreferenceFragment-based rather than a custom layout so it inherits the same
 * toolbar / scrolling / inset handling as every other settings screen — `BasePreferenceFragment`
 * already wires those up. Each diagnostic value lives in a non-selectable [Preference]'s summary,
 * refreshed in [onResume] so navigating away and back picks up new event counts and decoder
 * outcomes without an app restart. The two action entries (copy, reset) are clickable — handled in
 * [onPreferenceTreeClick] off their preference keys.
 */
@AndroidEntryPoint
class MlDiagnosticsFragment : BasePreferenceFragment(R.xml.preferences_ml_diagnostics) {

    @Inject lateinit var encoderRuntime: EncoderRuntime
    @Inject lateinit var predictorRuntime: PredictorRuntime
    @Inject lateinit var mlSettings: MlSettings
    @Inject lateinit var listeningEventDao: ListeningEventDao
    @Inject lateinit var trackEmbeddingDao: TrackEmbeddingDao

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Re-read every dynamic value and push it into the relevant [Preference] summary. Encoder,
     * native-decoder, and predictor sections are synchronous reads (the runtimes hold their state
     * in volatile fields and the stats are atomic counters). The events + embeddings counts hit
     * Room, so they go through `lifecycleScope` on `Dispatchers.IO`.
     */
    private fun refresh() {
        renderEncoder()
        renderNativeDecoder()
        renderPredictor()
        renderEventsAsync()
    }

    private fun renderEncoder() {
        val pref = findPreference<Preference>(getString(R.string.set_key_ml_diag_encoder)) ?: return
        val backend = encoderRuntime.backend
        val errors = encoderRuntime.lastBackendErrors()
        if (backend == EncoderRuntime.Backend.UNINITIALIZED) {
            pref.summary = getString(R.string.set_ml_diag_encoder_uninit)
            return
        }
        val sb = StringBuilder()
        sb.append("Backend: ").append(backend.name)
        if (errors.isNotEmpty()) {
            sb.append("\nCascade errors before this backend won:")
            errors.forEach { (b, msg) ->
                sb.append("\n  • ").append(b.name).append(": ").append(msg)
            }
        }
        pref.summary = sb.toString()
    }

    private fun renderNativeDecoder() {
        val pref =
            findPreference<Preference>(getString(R.string.set_key_ml_diag_native_decoder)) ?: return
        val s = NativeDecoderStats.snapshot()
        if (s.total == 0L) {
            pref.summary = getString(R.string.set_ml_diag_native_decoder_idle)
            return
        }
        val pct = if (s.total > 0L) (100 * s.success / s.total).toInt() else 0
        // Only enumerate non-zero fallback buckets so the summary stays compact for the common case
        // where most failures land in one or two reasons.
        val fallbacks =
            buildList<Pair<String, Long>> {
                if (s.fallbackNoLib > 0L) add("no native lib" to s.fallbackNoLib)
                if (s.fallbackAfdOpenFailed > 0L) add("AFD open failed" to s.fallbackAfdOpenFailed)
                if (s.fallbackNonzeroAfdOffset > 0L)
                    add("AFD nonzero offset" to s.fallbackNonzeroAfdOffset)
                if (s.fallbackEmptyLength > 0L) add("empty AFD length" to s.fallbackEmptyLength)
                if (s.fallbackFdFetchFailed > 0L) add("fd fetch failed" to s.fallbackFdFetchFailed)
                if (s.fallbackNativeThrew > 0L) add("native threw" to s.fallbackNativeThrew)
                if (s.fallbackNativeReturnedNull > 0L)
                    add("native returned null" to s.fallbackNativeReturnedNull)
            }
        val sb = StringBuilder()
        sb.append(s.success)
            .append("/")
            .append(s.total)
            .append(" decode attempts hit the fast path (")
            .append(pct)
            .append("%)")
        if (fallbacks.isNotEmpty()) {
            sb.append("\nFallbacks:")
            fallbacks.forEach { (label, n) ->
                sb.append("\n  • ").append(label).append(": ").append(n)
            }
        }
        pref.summary = sb.toString()
    }

    private fun renderPredictor() {
        val pref =
            findPreference<Preference>(getString(R.string.set_key_ml_diag_predictor)) ?: return
        val sb = StringBuilder()
        sb.append("Encoder version: ").append(mlSettings.embeddingVersion)
        sb.append("\nPredictor version: ").append(mlSettings.predictorVersion)
        sb.append("\nState model source: ")
            .append(
                if (predictorRuntime.isUsingUserModel()) "user (fine-tuned)" else "bundled asset"
            )
        pref.summary = sb.toString()
    }

    private fun renderEventsAsync() {
        val pref = findPreference<Preference>(getString(R.string.set_key_ml_diag_events)) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val embeddingVersion = mlSettings.embeddingVersion
            val (eventCount, embeddingCount) =
                withContext(Dispatchers.IO) {
                    listeningEventDao.count() to trackEmbeddingDao.count(embeddingVersion)
                }
            val retentionDays = mlSettings.eventRetentionDays
            val retentionLabel = if (retentionDays <= 0) "disabled" else "$retentionDays days"
            // findPreference can return null if the user navigated away while the IO query
            // was in flight — the lifecycleScope cancellation would also cancel us, but a
            // null-guard is cheaper than a try/catch on a contractual race.
            findPreference<Preference>(getString(R.string.set_key_ml_diag_events))?.summary =
                buildString {
                    append("Stored events: ").append(eventCount)
                    append("\nEmbedded tracks: ").append(embeddingCount)
                    append(" (for current encoder version)")
                    append("\nRetention window: ").append(retentionLabel)
                }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            getString(R.string.set_key_ml_diag_copy) -> {
                copyDiagnosticsToClipboard()
                true
            }
            getString(R.string.set_key_ml_diag_reset) -> {
                resetUserModel()
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }

    /**
     * Build a markdown-ish dump of every diagnostic value and put it on the clipboard. Triaging "ML
     * feels broken" reports gets to skip the entire back-and-forth of "OK can you tell me which
     * device, which Android version..." — a one-tap copy gets the issue filer everything.
     */
    private fun copyDiagnosticsToClipboard() {
        val ctx = requireContext()
        val backend = encoderRuntime.backend
        val backendErrors = encoderRuntime.lastBackendErrors()
        val decoder = NativeDecoderStats.snapshot()
        val text = buildString {
            append("# LatentJam ML diagnostics\n")
            append("\n## Encoder\n")
            append("backend: ").append(backend.name).append("\n")
            if (backendErrors.isNotEmpty()) {
                append("backend_errors:\n")
                backendErrors.forEach { (b, msg) ->
                    append("  - ").append(b.name).append(": ").append(msg).append("\n")
                }
            }
            append("\n## Native decoder\n")
            append("success: ").append(decoder.success).append("\n")
            append("fallback_no_lib: ").append(decoder.fallbackNoLib).append("\n")
            append("fallback_afd_open_failed: ").append(decoder.fallbackAfdOpenFailed).append("\n")
            append("fallback_nonzero_afd_offset: ")
                .append(decoder.fallbackNonzeroAfdOffset)
                .append("\n")
            append("fallback_empty_length: ").append(decoder.fallbackEmptyLength).append("\n")
            append("fallback_fd_fetch_failed: ").append(decoder.fallbackFdFetchFailed).append("\n")
            append("fallback_native_threw: ").append(decoder.fallbackNativeThrew).append("\n")
            append("fallback_native_returned_null: ")
                .append(decoder.fallbackNativeReturnedNull)
                .append("\n")
            append("\n## Predictor\n")
            append("encoder_version: ").append(mlSettings.embeddingVersion).append("\n")
            append("predictor_version: ").append(mlSettings.predictorVersion).append("\n")
            append("user_model_loaded: ").append(predictorRuntime.isUsingUserModel()).append("\n")
            append("\n## Settings\n")
            append("event_logging_enabled: ").append(mlSettings.enableEventLogging).append("\n")
            append("retrain_threshold: ").append(mlSettings.retrainThreshold).append("\n")
            append("event_retention_days: ").append(mlSettings.eventRetentionDays).append("\n")
        }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.set_ml_diag_copy_label), text))
        Toast.makeText(ctx, R.string.set_ml_diag_copy_done, Toast.LENGTH_SHORT).show()
    }

    /**
     * Wipe any user-trained predictor state model and reload from the bundled asset on next
     * playback. We probe the file existence first so we can show a "nothing to reset" toast instead
     * of pretending we did something — `predictorRuntime.resetToBaseline()` is a no-op on its own
     * when no user model is present.
     */
    private fun resetUserModel() {
        val ctx = requireContext()
        val hadUserModel = PredictorRuntime.userStateModelFile(ctx).exists()
        predictorRuntime.resetToBaseline()
        val msgRes =
            if (hadUserModel) R.string.set_ml_diag_reset_done else R.string.set_ml_diag_reset_none
        Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
        refresh()
    }
}
