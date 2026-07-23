/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_back
import io.github.nikitasud.latentjam.app.generated.resources.action_cancel
import io.github.nikitasud.latentjam.app.generated.resources.action_clear
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_from_latentjam
import io.github.nikitasud.latentjam.app.generated.resources.action_retry
import io.github.nikitasud.latentjam.app.generated.resources.backup_export
import io.github.nikitasud.latentjam.app.generated.resources.backup_export_success
import io.github.nikitasud.latentjam.app.generated.resources.backup_destination_warning
import io.github.nikitasud.latentjam.app.generated.resources.backup_failed
import io.github.nikitasud.latentjam.app.generated.resources.backup_import
import io.github.nikitasud.latentjam.app.generated.resources.backup_import_body
import io.github.nikitasud.latentjam.app.generated.resources.backup_import_partial
import io.github.nikitasud.latentjam.app.generated.resources.backup_import_success
import io.github.nikitasud.latentjam.app.generated.resources.backup_import_success_unmatched
import io.github.nikitasud.latentjam.app.generated.resources.backup_import_title
import io.github.nikitasud.latentjam.app.generated.resources.backup_invalid
import io.github.nikitasud.latentjam.app.generated.resources.backup_merge
import io.github.nikitasud.latentjam.app.generated.resources.backup_replace
import io.github.nikitasud.latentjam.app.generated.resources.backup_replace_warning
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.engine_error_backend
import io.github.nikitasud.latentjam.app.generated.resources.engine_error_audio_unavailable
import io.github.nikitasud.latentjam.app.generated.resources.engine_error_model_unavailable
import io.github.nikitasud.latentjam.app.generated.resources.engine_error_not_indexed
import io.github.nikitasud.latentjam.app.generated.resources.engine_state_not_started
import io.github.nikitasud.latentjam.app.generated.resources.engine_state_ready
import io.github.nikitasud.latentjam.app.generated.resources.engine_state_starting
import io.github.nikitasud.latentjam.app.generated.resources.engine_state_unavailable
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_bands
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_bass_boost
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_bass_value
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_preset_bass
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_preset_electronic
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_preset_flat
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_preset_treble
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_preset_vocal
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_presets
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_reset
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_unavailable_body
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_analysis_failed
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_analysis_progress
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_engine
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_failures_more
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_fingerprints
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_how_it_works
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_how_title
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_indexed_of
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_include_novelty_mixes
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_include_novelty_mixes_body
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_library
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_manage_exclusions
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_manage_exclusions_body
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_excluded_artists
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_excluded_tracks
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_exclusions_clear
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_exclusions_empty
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_no_problems
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_notifications
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_notifications_body
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_problems
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_problems_body
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_queue_length
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_queue_length_body
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_rebuild
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_rebuild_body
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_rebuild_confirm
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_retry
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_section_recommendations
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_section_status
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_syncing
import io.github.nikitasud.latentjam.app.generated.resources.permission_audio_settings_rationale
import io.github.nikitasud.latentjam.app.generated.resources.permission_open_settings
import io.github.nikitasud.latentjam.app.generated.resources.privacy_clear_history
import io.github.nikitasud.latentjam.app.generated.resources.privacy_clear_history_body
import io.github.nikitasud.latentjam.app.generated.resources.privacy_clear_history_confirm
import io.github.nikitasud.latentjam.app.generated.resources.privacy_clear_searches
import io.github.nikitasud.latentjam.app.generated.resources.privacy_clear_searches_body
import io.github.nikitasud.latentjam.app.generated.resources.privacy_clear_searches_confirm
import io.github.nikitasud.latentjam.app.generated.resources.privacy_data_clear_failed
import io.github.nikitasud.latentjam.app.generated.resources.privacy_delete_existing
import io.github.nikitasud.latentjam.app.generated.resources.privacy_disable_history_body
import io.github.nikitasud.latentjam.app.generated.resources.privacy_disable_searches_body
import io.github.nikitasud.latentjam.app.generated.resources.privacy_history_empty
import io.github.nikitasud.latentjam.app.generated.resources.privacy_history_listens
import io.github.nikitasud.latentjam.app.generated.resources.privacy_keep_existing
import io.github.nikitasud.latentjam.app.generated.resources.privacy_listening_history
import io.github.nikitasud.latentjam.app.generated.resources.privacy_local_body
import io.github.nikitasud.latentjam.app.generated.resources.privacy_recent_searches
import io.github.nikitasud.latentjam.app.generated.resources.privacy_remember_searches_body
import io.github.nikitasud.latentjam.app.generated.resources.privacy_save_history_body
import io.github.nikitasud.latentjam.app.generated.resources.privacy_setting_save_failed
import io.github.nikitasud.latentjam.app.generated.resources.settings_about
import io.github.nikitasud.latentjam.app.generated.resources.settings_about_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_about_subtitle
import io.github.nikitasud.latentjam.app.generated.resources.settings_appearance
import io.github.nikitasud.latentjam.app.generated.resources.settings_appearance_subtitle
import io.github.nikitasud.latentjam.app.generated.resources.settings_backup
import io.github.nikitasud.latentjam.app.generated.resources.settings_backup_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_color_dynamic
import io.github.nikitasud.latentjam.app.generated.resources.settings_color_dynamic_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_color_smart
import io.github.nikitasud.latentjam.app.generated.resources.settings_color_smart_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_color_theme
import io.github.nikitasud.latentjam.app.generated.resources.settings_color_theme_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_equalizer
import io.github.nikitasud.latentjam.app.generated.resources.settings_equalizer_subtitle
import io.github.nikitasud.latentjam.app.generated.resources.settings_equalizer_unavailable
import io.github.nikitasud.latentjam.app.generated.resources.settings_intelligence
import io.github.nikitasud.latentjam.app.generated.resources.settings_library
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_count
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_import
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_import_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_loading
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_refresh
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_refresh_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_refreshing
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_restore
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_restore_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_subtitle
import io.github.nikitasud.latentjam.app.generated.resources.settings_language
import io.github.nikitasud.latentjam.app.generated.resources.settings_language_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_license
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_aliases
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_aliases_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_models
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_models_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_libraries
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_libraries_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_runtime
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_runtime_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_text_model
import io.github.nikitasud.latentjam.app.generated.resources.settings_license_text_model_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_licenses
import io.github.nikitasud.latentjam.app.generated.resources.settings_licenses_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_audio_access
import io.github.nikitasud.latentjam.app.generated.resources.settings_audio_access_allowed
import io.github.nikitasud.latentjam.app.generated.resources.settings_audio_access_blocked
import io.github.nikitasud.latentjam.app.generated.resources.settings_audio_access_not_required
import io.github.nikitasud.latentjam.app.generated.resources.settings_audio_access_not_requested
import io.github.nikitasud.latentjam.app.generated.resources.settings_hidden_tracks
import io.github.nikitasud.latentjam.app.generated.resources.settings_hidden_tracks_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_hidden_tracks_empty
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_manage_failed
import io.github.nikitasud.latentjam.app.generated.resources.settings_restore
import io.github.nikitasud.latentjam.app.generated.resources.settings_restore_all
import io.github.nikitasud.latentjam.app.generated.resources.settings_source_unnamed
import io.github.nikitasud.latentjam.app.generated.resources.settings_source_music_library
import io.github.nikitasud.latentjam.app.generated.resources.settings_sources
import io.github.nikitasud.latentjam.app.generated.resources.settings_sources_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_sources_empty
import io.github.nikitasud.latentjam.app.generated.resources.settings_open_source
import io.github.nikitasud.latentjam.app.generated.resources.settings_open_source_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_privacy
import io.github.nikitasud.latentjam.app.generated.resources.settings_privacy_subtitle
import io.github.nikitasud.latentjam.app.generated.resources.settings_report_issue
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_appearance
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_data_support
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_library
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_music
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_navigation
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_preferences
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_privacy
import io.github.nikitasud.latentjam.app.generated.resources.settings_smart_engine
import io.github.nikitasud.latentjam.app.generated.resources.settings_smart_engine_subtitle
import io.github.nikitasud.latentjam.app.generated.resources.settings_start_page
import io.github.nikitasud.latentjam.app.generated.resources.settings_start_page_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_theme
import io.github.nikitasud.latentjam.app.generated.resources.settings_title
import io.github.nikitasud.latentjam.app.generated.resources.settings_track_colors
import io.github.nikitasud.latentjam.app.generated.resources.settings_version
import io.github.nikitasud.latentjam.app.generated.resources.state_off
import io.github.nikitasud.latentjam.app.generated.resources.state_on
import io.github.nikitasud.latentjam.app.generated.resources.snack_removed_from_latentjam
import io.github.nikitasud.latentjam.app.generated.resources.snack_smart_exclusion_failed
import io.github.nikitasud.latentjam.app.generated.resources.tab_albums
import io.github.nikitasud.latentjam.app.generated.resources.tab_artists
import io.github.nikitasud.latentjam.app.generated.resources.tab_for_you
import io.github.nikitasud.latentjam.app.generated.resources.tab_folders
import io.github.nikitasud.latentjam.app.generated.resources.tab_genres
import io.github.nikitasud.latentjam.app.generated.resources.tab_playlists
import io.github.nikitasud.latentjam.app.generated.resources.tab_tracks
import io.github.nikitasud.latentjam.app.generated.resources.theme_dark
import io.github.nikitasud.latentjam.app.generated.resources.theme_light
import io.github.nikitasud.latentjam.app.generated.resources.theme_system
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.RecentSearches
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.library.LibrarySource
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.playback.EqualizerController
import io.github.nikitasud.latentjam.playback.EqualizerPreset
import io.github.nikitasud.latentjam.playback.EqualizerPresetKind
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class SettingsPage {
    ROOT,
    APPEARANCE,
    LIBRARY,
    SOURCES,
    HIDDEN_TRACKS,
    EQUALIZER,
    INTELLIGENCE,
    INTELLIGENCE_PROBLEMS,
    SMART_EXCLUSIONS,
    PRIVACY,
    BACKUP,
    ABOUT,
    LICENSES,
}

/** Every settings action is backed by a real app capability; there are no placeholder toggles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    equalizer: EqualizerController,
    engine: SimilarityEngine,
    history: ListeningHistory,
    recentSearches: RecentSearches,
    tracks: List<TrackDescriptor>,
    libraryLoading: Boolean,
    libraryRefreshing: Boolean,
    hasHiddenTracks: Boolean,
    canImportAudio: Boolean,
    onRefreshLibrary: () -> Unit,
    onImportAudio: () -> Unit,
    onRetryIndexing: () -> Unit,
    onRebuildAnalysis: suspend () -> Unit,
    onHideTrack: suspend (TrackDescriptor) -> Unit,
    onBackupRestored: suspend () -> Unit,
    onClearListeningHistory: suspend () -> Unit,
    onClearRecentSearches: suspend () -> Unit,
    snackbarHostState: SnackbarHostState,
    onClose: () -> Unit,
) {
    val library = remember { AppGraph.library }
    val permissions = remember { AppGraph.permissions }
    var savedStack by rememberSaveable { mutableStateOf(SettingsPage.ROOT.name) }
    val stack = savedStack.split(ROUTE_SEPARATOR)
        .mapNotNull { name -> SettingsPage.entries.firstOrNull { it.name == name } }
        .ifEmpty { listOf(SettingsPage.ROOT) }
    val page = stack.last()
    val rootListState = rememberLazyListState()

    fun open(next: SettingsPage) {
        savedStack = (stack + next).joinToString(ROUTE_SEPARATOR)
    }

    fun goBack() {
        if (stack.size == 1) {
            onClose()
        } else {
            savedStack = stack.dropLast(1).joinToString(ROUTE_SEPARATOR)
        }
    }

    PlatformBackHandler(enabled = true, onBack = ::goBack)

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(page.titleResource())) },
                    navigationIcon = {
                        IconButton(onClick = ::goBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(Res.string.action_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (page) {
                    SettingsPage.ROOT -> SettingsRoot(
                        listState = rootListState,
                        equalizer = equalizer,
                        onOpen = ::open,
                    )
                    SettingsPage.APPEARANCE -> AppearanceSettings(
                        settings = settings,
                        onOpenSystemSettings = permissions::openAppSettings,
                    )
                    SettingsPage.LIBRARY -> LibrarySettings(
                        trackCount = tracks.size,
                        loading = libraryLoading,
                        refreshing = libraryRefreshing,
                        hasHiddenTracks = hasHiddenTracks,
                        canImportAudio = canImportAudio,
                        permissions = permissions,
                        onRefreshLibrary = onRefreshLibrary,
                        onImportAudio = onImportAudio,
                        onOpen = ::open,
                    )
                    SettingsPage.SOURCES -> SourcesSettings(
                        library = library,
                        onRefreshLibrary = onRefreshLibrary,
                        snackbarHostState = snackbarHostState,
                    )
                    SettingsPage.HIDDEN_TRACKS -> HiddenTracksSettings(
                        library = library,
                        onRefreshLibrary = onRefreshLibrary,
                        snackbarHostState = snackbarHostState,
                    )
                    SettingsPage.EQUALIZER -> EqualizerSettings(equalizer)
                    SettingsPage.INTELLIGENCE -> IntelligenceSettings(
                        settings = settings,
                        engine = engine,
                        tracks = tracks,
                        libraryLoading = libraryLoading,
                        onRetryIndexing = onRetryIndexing,
                        onRebuildAnalysis = onRebuildAnalysis,
                        permissions = permissions,
                        snackbarHostState = snackbarHostState,
                        onOpen = ::open,
                    )
                    SettingsPage.INTELLIGENCE_PROBLEMS -> IntelligenceProblemsSettings(
                        tracks = tracks,
                        libraryLoading = libraryLoading,
                        onRetryIndexing = onRetryIndexing,
                        onHideTrack = onHideTrack,
                        snackbarHostState = snackbarHostState,
                    )
                    SettingsPage.SMART_EXCLUSIONS -> SmartExclusionsSettings(
                        library = library,
                        visibleTracks = tracks,
                        snackbarHostState = snackbarHostState,
                    )
                    SettingsPage.PRIVACY -> PrivacySettings(
                        settings = settings,
                        history = history,
                        recentSearches = recentSearches,
                        onClearListeningHistory = onClearListeningHistory,
                        onClearRecentSearches = onClearRecentSearches,
                        snackbarHostState = snackbarHostState,
                    )
                    SettingsPage.BACKUP -> BackupSettings(
                        settings = settings,
                        library = library,
                        onBackupRestored = onBackupRestored,
                        snackbarHostState = snackbarHostState,
                    )
                    SettingsPage.ABOUT -> AboutSettings(onOpenLicenses = {
                        open(SettingsPage.LICENSES)
                    })
                    SettingsPage.LICENSES -> LicensesSettings()
                }
            }
        }
    }
}

private fun SettingsPage.titleResource(): StringResource = when (this) {
    SettingsPage.ROOT -> Res.string.settings_title
    SettingsPage.APPEARANCE -> Res.string.settings_appearance
    SettingsPage.LIBRARY -> Res.string.settings_library
    SettingsPage.SOURCES -> Res.string.settings_sources
    SettingsPage.HIDDEN_TRACKS -> Res.string.settings_hidden_tracks
    SettingsPage.EQUALIZER -> Res.string.settings_equalizer
    SettingsPage.INTELLIGENCE -> Res.string.settings_intelligence
    SettingsPage.INTELLIGENCE_PROBLEMS -> Res.string.intelligence_problems
    SettingsPage.SMART_EXCLUSIONS -> Res.string.intelligence_manage_exclusions
    SettingsPage.PRIVACY -> Res.string.settings_privacy
    SettingsPage.BACKUP -> Res.string.settings_backup
    SettingsPage.ABOUT -> Res.string.settings_about
    SettingsPage.LICENSES -> Res.string.settings_licenses
}

@Composable
private fun SettingsRoot(
    listState: LazyListState,
    equalizer: EqualizerController,
    onOpen: (SettingsPage) -> Unit,
) {
    val equalizerState by equalizer.state.collectAsState()
    val equalizerSubtitle = when {
        !equalizerState.available -> stringResource(Res.string.settings_equalizer_unavailable)
        equalizerState.enabled -> stringResource(Res.string.state_on)
        else -> stringResource(Res.string.state_off)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.settings_section_preferences)) {
                SettingsRow(
                    title = stringResource(Res.string.settings_appearance),
                    subtitle = stringResource(Res.string.settings_appearance_subtitle),
                    onClick = { onOpen(SettingsPage.APPEARANCE) },
                )
                SettingsRow(
                    title = stringResource(Res.string.settings_equalizer),
                    subtitle = equalizerSubtitle,
                    onClick = { onOpen(SettingsPage.EQUALIZER) },
                )
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_section_music)) {
                SettingsRow(
                    title = stringResource(Res.string.settings_library),
                    subtitle = stringResource(Res.string.settings_library_subtitle),
                    onClick = { onOpen(SettingsPage.LIBRARY) },
                )
                SettingsRow(
                    title = stringResource(Res.string.settings_smart_engine),
                    subtitle = stringResource(Res.string.settings_smart_engine_subtitle),
                    onClick = { onOpen(SettingsPage.INTELLIGENCE) },
                )
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_section_data_support)) {
                SettingsRow(
                    title = stringResource(Res.string.settings_privacy),
                    subtitle = stringResource(Res.string.settings_privacy_subtitle),
                    onClick = { onOpen(SettingsPage.PRIVACY) },
                )
                SettingsRow(
                    title = stringResource(Res.string.settings_backup),
                    subtitle = stringResource(Res.string.settings_backup_body),
                    onClick = { onOpen(SettingsPage.BACKUP) },
                )
                SettingsRow(
                    title = stringResource(Res.string.settings_about),
                    subtitle = stringResource(Res.string.settings_about_subtitle),
                    onClick = { onOpen(SettingsPage.ABOUT) },
                )
            }
        }
    }
}

@Composable
private fun AppearanceSettings(
    settings: AppSettings,
    onOpenSystemSettings: () -> Unit,
) {
    val theme by settings.themeMode.collectAsState()
    val colorMode by settings.trackColorMode.collectAsState()
    val startPage by settings.startPage.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.settings_section_appearance)) {
                SettingsLabel(stringResource(Res.string.settings_theme))
                ChoiceChips(
                    values = ThemeMode.entries,
                    selected = theme,
                    label = { mode ->
                        stringResource(when (mode) {
                            ThemeMode.SYSTEM -> Res.string.theme_system
                            ThemeMode.LIGHT -> Res.string.theme_light
                            ThemeMode.DARK -> Res.string.theme_dark
                        })
                    },
                    onSelected = settings::setThemeMode,
                )
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_track_colors)) {
                ChoiceChips(
                    values = TrackColorMode.entries,
                    selected = colorMode,
                    label = { mode ->
                        stringResource(when (mode) {
                            TrackColorMode.DYNAMIC -> Res.string.settings_color_dynamic
                            TrackColorMode.SMART -> Res.string.settings_color_smart
                            TrackColorMode.THEME -> Res.string.settings_color_theme
                        })
                    },
                    onSelected = settings::setTrackColorMode,
                )
                SettingsBody(
                    stringResource(
                        when (colorMode) {
                            TrackColorMode.DYNAMIC -> Res.string.settings_color_dynamic_body
                            TrackColorMode.SMART -> Res.string.settings_color_smart_body
                            TrackColorMode.THEME -> Res.string.settings_color_theme_body
                        },
                    ),
                )
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_section_navigation)) {
                SettingsLabel(stringResource(Res.string.settings_start_page))
                SingleChoiceRows(
                    values = StartPage.entries,
                    selected = startPage,
                    label = { page ->
                        stringResource(when (page) {
                            StartPage.FOR_YOU -> Res.string.tab_for_you
                            StartPage.PLAYLISTS -> Res.string.tab_playlists
                            StartPage.TRACKS -> Res.string.tab_tracks
                            StartPage.ALBUMS -> Res.string.tab_albums
                            StartPage.ARTISTS -> Res.string.tab_artists
                            StartPage.GENRES -> Res.string.tab_genres
                            StartPage.FOLDERS -> Res.string.tab_folders
                        })
                    },
                    onSelected = settings::setStartPage,
                )
                SettingsBody(stringResource(Res.string.settings_start_page_body))
                SettingsRow(
                    title = stringResource(Res.string.settings_language),
                    subtitle = stringResource(Res.string.settings_language_body),
                    onClick = onOpenSystemSettings,
                )
            }
        }
    }
}

@Composable
private fun <T> SingleChoiceRows(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        values.forEach { value ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == value,
                        role = Role.RadioButton,
                        onClick = { onSelected(value) },
                    )
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = null,
                )
                Text(
                    text = label(value),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceChips(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        items(values) { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun LibrarySettings(
    trackCount: Int,
    loading: Boolean,
    refreshing: Boolean,
    hasHiddenTracks: Boolean,
    canImportAudio: Boolean,
    permissions: AppPermissions,
    onRefreshLibrary: () -> Unit,
    onImportAudio: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    val audioAccess by permissions.audioLibraryStatus.collectAsState()
    LaunchedEffect(permissions) { permissions.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.intelligence_section_status)) {
                StatRow(
                    label = stringResource(Res.string.settings_library_count),
                    value = if (loading) {
                        stringResource(Res.string.settings_library_loading)
                    } else {
                        pluralStringResource(Res.plurals.count_tracks, trackCount, trackCount)
                    },
                )
                StatRow(
                    label = stringResource(Res.string.settings_audio_access),
                    value = audioAccessLabel(audioAccess),
                )
                if (
                    audioAccess == AppPermissionStatus.DENIED ||
                    audioAccess == AppPermissionStatus.NOT_DETERMINED
                ) {
                    SettingsActionRow(
                        title = stringResource(Res.string.permission_open_settings),
                        subtitle = stringResource(Res.string.permission_audio_settings_rationale),
                        onClick = permissions::openAppSettings,
                    )
                }
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_section_library)) {
                SettingsActionRow(
                    title = stringResource(Res.string.settings_library_refresh),
                    subtitle = stringResource(
                        if (refreshing) {
                            Res.string.settings_library_refreshing
                        } else {
                            Res.string.settings_library_refresh_body
                        },
                    ),
                    enabled = !loading && !refreshing,
                    onClick = onRefreshLibrary,
                )
                if (canImportAudio) {
                    SettingsActionRow(
                        title = stringResource(Res.string.settings_library_import),
                        subtitle = stringResource(Res.string.settings_library_import_body),
                        enabled = !loading,
                        onClick = onImportAudio,
                    )
                }
                SettingsRow(
                    title = stringResource(Res.string.settings_sources),
                    subtitle = stringResource(Res.string.settings_sources_body),
                    onClick = { onOpen(SettingsPage.SOURCES) },
                )
                SettingsRow(
                    title = stringResource(Res.string.settings_hidden_tracks),
                    subtitle = stringResource(
                        if (hasHiddenTracks) {
                            Res.string.settings_hidden_tracks_body
                        } else {
                            Res.string.settings_hidden_tracks_empty
                        },
                    ),
                    onClick = { onOpen(SettingsPage.HIDDEN_TRACKS) },
                )
            }
        }
    }
}

@Composable
private fun audioAccessLabel(status: AppPermissionStatus): String = stringResource(
    when (status) {
        AppPermissionStatus.GRANTED -> Res.string.settings_audio_access_allowed
        AppPermissionStatus.DENIED -> Res.string.settings_audio_access_blocked
        AppPermissionStatus.NOT_DETERMINED -> Res.string.settings_audio_access_not_requested
        AppPermissionStatus.NOT_REQUIRED -> Res.string.settings_audio_access_not_required
    },
)

@Composable
private fun SourcesSettings(
    library: MusicLibrary,
    onRefreshLibrary: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<LibrarySource>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var changingSourceId by remember { mutableStateOf<String?>(null) }
    val failureMessage = stringResource(Res.string.settings_library_manage_failed)

    suspend fun loadSources() {
        try {
            sources = library.sources()
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            loadFailed = true
        }
    }

    LaunchedEffect(library) { loadSources() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.settings_sources)) {
                SettingsBody(stringResource(Res.string.settings_sources_body))
                when {
                    sources == null && !loadFailed -> LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    loadFailed -> SettingsActionRow(
                        title = stringResource(Res.string.action_retry),
                        subtitle = failureMessage,
                        onClick = {
                            sources = null
                            loadFailed = false
                            scope.launch { loadSources() }
                        },
                    )
                    sources.orEmpty().isEmpty() -> SettingsBody(
                        stringResource(Res.string.settings_sources_empty),
                    )
                }
            }
        }
        items(
            items = sources.orEmpty(),
            key = LibrarySource::id,
        ) { source ->
            SourceSwitchRow(
                source = source,
                enabled = changingSourceId == null && source.canToggle,
                onCheckedChange = { enabled ->
                    scope.launch {
                        changingSourceId = source.id
                        try {
                            library.setSourceEnabled(source.id, enabled)
                            sources = library.sources()
                            onRefreshLibrary()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            snackbarHostState.showSnackbar(failureMessage)
                        } finally {
                            changingSourceId = null
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SourceSwitchRow(
    source: LibrarySource,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = source.enabled,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = when {
                    !source.name.isNullOrBlank() -> source.name.orEmpty()
                    source.id == IOS_MUSIC_LIBRARY_SOURCE_ID -> stringResource(
                        Res.string.settings_source_music_library,
                    )
                    else -> stringResource(Res.string.settings_source_unnamed)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = pluralStringResource(
                    Res.plurals.count_tracks,
                    source.trackCount,
                    source.trackCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = source.enabled,
            enabled = enabled,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun HiddenTracksSettings(
    library: MusicLibrary,
    onRefreshLibrary: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var hiddenTracks by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var restoringTrackId by remember { mutableStateOf<String?>(null) }
    val failureMessage = stringResource(Res.string.settings_library_manage_failed)

    suspend fun loadHiddenTracks() {
        try {
            hiddenTracks = library.hiddenTracks()
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            loadFailed = true
        }
    }

    LaunchedEffect(library) { loadHiddenTracks() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.settings_hidden_tracks)) {
                SettingsBody(stringResource(Res.string.settings_hidden_tracks_body))
                when {
                    hiddenTracks == null && !loadFailed -> LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    loadFailed -> SettingsActionRow(
                        title = stringResource(Res.string.action_retry),
                        subtitle = failureMessage,
                        onClick = {
                            hiddenTracks = null
                            loadFailed = false
                            scope.launch { loadHiddenTracks() }
                        },
                    )
                    hiddenTracks.orEmpty().isEmpty() -> SettingsBody(
                        stringResource(Res.string.settings_hidden_tracks_empty),
                    )
                    else -> SettingsActionRow(
                        title = stringResource(Res.string.settings_restore_all),
                        subtitle = stringResource(Res.string.settings_library_restore_body),
                        enabled = restoringTrackId == null,
                        onClick = {
                            scope.launch {
                                restoringTrackId = RESTORE_ALL_OPERATION_ID
                                try {
                                    library.unhideAll()
                                    hiddenTracks = emptyList()
                                    onRefreshLibrary()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Throwable) {
                                    snackbarHostState.showSnackbar(failureMessage)
                                } finally {
                                    restoringTrackId = null
                                }
                            }
                        },
                    )
                }
            }
        }
        items(
            items = hiddenTracks.orEmpty(),
            key = { it.id.value },
        ) { track ->
            HiddenTrackRow(
                track = track,
                enabled = restoringTrackId == null,
                onRestore = {
                    scope.launch {
                        restoringTrackId = track.id.value
                        try {
                            library.unhide(track.id)
                            hiddenTracks = hiddenTracks.orEmpty().filterNot { it.id == track.id }
                            onRefreshLibrary()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            snackbarHostState.showSnackbar(failureMessage)
                        } finally {
                            restoringTrackId = null
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun HiddenTrackRow(
    track: TrackDescriptor,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = track.title ?: stringResource(Res.string.track_untitled),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = track.artist ?: stringResource(Res.string.track_unknown_artist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRestore, enabled = enabled) {
            Text(stringResource(Res.string.settings_restore))
        }
    }
}

// ------------------------------------------------------------------ equalizer

@Composable
private fun EqualizerSettings(equalizer: EqualizerController) {
    val state by equalizer.state.collectAsState()
    val scope = rememberCoroutineScope()

    if (!state.available) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(Res.string.equalizer_unavailable_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = state.enabled,
                        role = Role.Switch,
                        onValueChange = { scope.launch { equalizer.setEnabled(it) } },
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.settings_equalizer),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(if (state.enabled) Res.string.state_on else Res.string.state_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = null)
            }
        }

        if (state.presets.isNotEmpty()) {
            item {
                SettingsSection(stringResource(Res.string.equalizer_presets)) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                    ) {
                        items(state.presets) { preset ->
                            FilterChip(
                                selected = state.activePreset == preset.index,
                                onClick = { scope.launch { equalizer.applyPreset(preset.index) } },
                                label = { Text(equalizerPresetLabel(preset)) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SettingsSection(stringResource(Res.string.equalizer_bands)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.bands.forEach { band ->
                        BandSlider(
                            label = formatFrequency(band.centreFrequencyHz),
                            value = band.levelMillibels.toFloat(),
                            range = state.minLevelMillibels.toFloat()..state.maxLevelMillibels.toFloat(),
                            enabled = state.enabled,
                            onChangeFinished = {
                                scope.launch { equalizer.setBandLevel(band.index, it.toInt()) }
                            },
                        )
                    }
                }
            }
        }

        if (state.bassBoostSupported) {
            item {
                var bassValue by remember(state.bassBoostStrength) {
                    mutableFloatStateOf(state.bassBoostStrength.toFloat())
                }
                val bassValueDescription = stringResource(
                    Res.string.equalizer_bass_value,
                    bassValue.toInt() / 10,
                )
                SettingsSection(stringResource(Res.string.equalizer_bass_boost)) {
                    StatRow(
                        label = stringResource(Res.string.equalizer_bass_boost),
                        value = bassValueDescription,
                    )
                    Slider(
                        value = bassValue,
                        onValueChange = { bassValue = it },
                        onValueChangeFinished = {
                            scope.launch { equalizer.setBassBoost(bassValue.toInt()) }
                        },
                        valueRange = 0f..1000f,
                        enabled = state.enabled,
                        modifier = Modifier
                            .semantics { stateDescription = bassValueDescription }
                            .padding(horizontal = 20.dp),
                    )
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                OutlinedButton(onClick = { scope.launch { equalizer.reset() } }) {
                    Text(stringResource(Res.string.equalizer_reset))
                }
            }
        }
    }
}

/** A bipolar vertical fader with a roomy touch target and adjustable accessibility semantics. */
@Composable
private fun BandSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChangeFinished: (Float) -> Unit,
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val fillColor = if (enabled) activeColor else activeColor.copy(alpha = 0.25f)
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val thumbColor = if (enabled) activeColor else activeColor.copy(alpha = 0.35f)
    var displayValue by remember(label) { mutableFloatStateOf(value) }
    var dragging by remember(label) { mutableStateOf(false) }
    LaunchedEffect(value, dragging) {
        if (!dragging) displayValue = value
    }
    val coercedValue = displayValue.coerceIn(range.start, range.endInclusive)
    val valueDescription = formatDecibels(coercedValue)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxHeight()
            .width(72.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = valueDescription
                progressBarRangeInfo = ProgressBarRangeInfo(coercedValue, range)
                if (enabled) {
                    setProgress { requested ->
                        displayValue = requested.coerceIn(range.start, range.endInclusive)
                        onChangeFinished(displayValue)
                        true
                    }
                } else {
                    disabled()
                }
            },
    ) {
        Text(
            text = valueDescription,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (coercedValue != 0f) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled && coercedValue != 0f) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            softWrap = false,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .fillMaxWidth()
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        var latest = bandValueForY(down.position.y, size.height.toFloat(), range)
                        displayValue = latest
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            latest = bandValueForY(change.position.y, size.height.toFloat(), range)
                            displayValue = latest
                            change.consume()
                        }
                        dragging = false
                        onChangeFinished(latest)
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centreX = size.width / 2f
                val trackWidth = 6.dp.toPx()
                val radius = trackWidth / 2f
                fun yFor(level: Float): Float {
                    val fraction = ((level - range.start) / span).coerceIn(0f, 1f)
                    return size.height * (1f - fraction)
                }
                val zeroY = yFor(0f)
                val thumbY = yFor(coercedValue)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(centreX - radius, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
                val fillTop = minOf(zeroY, thumbY)
                val fillHeight = (maxOf(zeroY, thumbY) - fillTop).coerceAtLeast(radius)
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(centreX - radius, fillTop),
                    size = Size(trackWidth, fillHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )
                drawLine(
                    color = zeroLineColor,
                    start = Offset(centreX - 8.dp.toPx(), zeroY),
                    end = Offset(centreX + 8.dp.toPx(), zeroY),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawCircle(color = thumbColor, radius = 9.dp.toPx(), center = Offset(centreX, thumbY))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun bandValueForY(y: Float, height: Float, range: ClosedFloatingPointRange<Float>): Float {
    if (height <= 0f) return range.start
    val fraction = (1f - (y / height)).coerceIn(0f, 1f)
    return range.start + fraction * (range.endInclusive - range.start)
}

private fun formatFrequency(hz: Int): String =
    if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"

@Composable
private fun equalizerPresetLabel(preset: EqualizerPreset): String = when (preset.kind) {
    EqualizerPresetKind.FLAT -> stringResource(Res.string.equalizer_preset_flat)
    EqualizerPresetKind.BASS -> stringResource(Res.string.equalizer_preset_bass)
    EqualizerPresetKind.TREBLE -> stringResource(Res.string.equalizer_preset_treble)
    EqualizerPresetKind.VOCAL -> stringResource(Res.string.equalizer_preset_vocal)
    EqualizerPresetKind.ELECTRONIC -> stringResource(Res.string.equalizer_preset_electronic)
    null -> preset.name
}

private fun formatDecibels(millibels: Float): String {
    val rounded = kotlin.math.round(millibels / 100f).toInt()
    return (if (rounded > 0) "+$rounded" else "$rounded") + " dB"
}

// --------------------------------------------------------------- intelligence

@Composable
private fun IntelligenceSettings(
    settings: AppSettings,
    engine: SimilarityEngine,
    tracks: List<TrackDescriptor>,
    libraryLoading: Boolean,
    onRetryIndexing: () -> Unit,
    onRebuildAnalysis: suspend () -> Unit,
    permissions: AppPermissions,
    snackbarHostState: SnackbarHostState,
    onOpen: (SettingsPage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by engine.state.collectAsState()
    val indexing by AppGraph.automaticIndexing.collectAsState()
    val queueLength by settings.smartQueueLength.collectAsState()
    val includeNoveltyMixes by settings.includeNoveltyMixes.collectAsState()
    val notificationStatus by permissions.notificationStatus.collectAsState()
    val ids = remember(tracks) { tracks.map(TrackDescriptor::id) }
    val indexingMatchesLibrary = indexing.trackIds == ids
    val synchronized = !libraryLoading &&
        (tracks.isEmpty() || (indexingMatchesLibrary && indexing.metadataReady))
    val fingerprintTarget = remember(tracks) { tracks.count { it.audioUri != null } }
    val indexed = if (synchronized) {
        ((state as? EngineState.Ready)?.indexedCount ?: 0).coerceIn(0, fingerprintTarget)
    } else {
        0
    }
    val failures = if (!libraryLoading && indexingMatchesLibrary) {
        actionableIndexingFailures(indexing.failures, tracks)
    } else {
        emptyMap()
    }
    val done = if (indexingMatchesLibrary) indexing.done.coerceIn(0, tracks.size) else 0
    var rebuilding by rememberSaveable { mutableStateOf(false) }
    var confirmRebuild by rememberSaveable { mutableStateOf(false) }
    val operationFailed = stringResource(Res.string.settings_library_manage_failed)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.intelligence_section_status)) {
                StatRow(
                    stringResource(Res.string.intelligence_engine),
                    if (libraryLoading) {
                        stringResource(Res.string.settings_library_loading)
                    } else if (!synchronized && indexing.running) {
                        stringResource(Res.string.intelligence_syncing)
                    } else {
                        describeEngineState(state)
                    },
                )
                StatRow(
                    label = stringResource(Res.string.intelligence_library),
                    value = if (libraryLoading) {
                        stringResource(Res.string.settings_library_loading)
                    } else {
                        pluralStringResource(Res.plurals.count_tracks, tracks.size, tracks.size)
                    },
                )
                StatRow(
                    label = stringResource(Res.string.intelligence_fingerprints),
                    value = if (libraryLoading) {
                        stringResource(Res.string.settings_library_loading)
                    } else if (fingerprintTarget == 0) {
                        "—"
                    } else if (!synchronized) {
                        stringResource(Res.string.intelligence_syncing)
                    } else {
                        stringResource(Res.string.intelligence_indexed_of, indexed, fingerprintTarget)
                    },
                )
                if (!libraryLoading && indexing.running && tracks.isNotEmpty()) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / tracks.size },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    Text(
                        text = stringResource(
                            Res.string.intelligence_analysis_progress,
                            done,
                            tracks.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                val needsRecovery = !libraryLoading && (
                    state is EngineState.Uninitialized ||
                        state is EngineState.Failed ||
                        (
                            tracks.isNotEmpty() &&
                                !indexing.running &&
                                (!indexing.complete || failures.isNotEmpty())
                            )
                    )
                if (needsRecovery) {
                    OutlinedButton(
                        onClick = onRetryIndexing,
                        enabled = !indexing.running && state !is EngineState.Initializing,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(stringResource(Res.string.intelligence_retry))
                    }
                }
            }
        }
        item {
            SettingsSection(stringResource(Res.string.intelligence_section_recommendations)) {
                SettingsLabel(stringResource(Res.string.intelligence_queue_length))
                ChoiceChips(
                    values = SMART_QUEUE_LENGTH_OPTIONS,
                    selected = queueLength,
                    label = { length ->
                        pluralStringResource(Res.plurals.count_tracks, length, length)
                    },
                    onSelected = settings::setSmartQueueLength,
                )
                SettingsBody(stringResource(Res.string.intelligence_queue_length_body))
                SettingsSwitchRow(
                    title = stringResource(Res.string.intelligence_include_novelty_mixes),
                    subtitle = stringResource(Res.string.intelligence_include_novelty_mixes_body),
                    checked = includeNoveltyMixes,
                    enabled = true,
                    onCheckedChange = settings::setIncludeNoveltyMixes,
                )
                SettingsRow(
                    title = stringResource(Res.string.intelligence_manage_exclusions),
                    subtitle = stringResource(Res.string.intelligence_manage_exclusions_body),
                    onClick = { onOpen(SettingsPage.SMART_EXCLUSIONS) },
                )
            }
        }
        item {
            SettingsSection(title = null) {
                SettingsRow(
                    title = stringResource(Res.string.intelligence_problems),
                    subtitle = if (failures.isEmpty()) {
                        stringResource(Res.string.intelligence_no_problems)
                    } else {
                        stringResource(Res.string.intelligence_analysis_failed, failures.size)
                    },
                    onClick = { onOpen(SettingsPage.INTELLIGENCE_PROBLEMS) },
                )
                if (notificationStatus != AppPermissionStatus.NOT_REQUIRED) {
                    SettingsActionRow(
                        title = stringResource(Res.string.intelligence_notifications),
                        subtitle = stringResource(Res.string.intelligence_notifications_body) +
                            " · " + audioAccessLabel(notificationStatus),
                        onClick = permissions::openNotificationSettings,
                    )
                }
                SettingsActionRow(
                    title = stringResource(Res.string.intelligence_rebuild),
                    subtitle = stringResource(Res.string.intelligence_rebuild_body),
                    enabled = !indexing.running && !rebuilding,
                    destructive = true,
                    onClick = { confirmRebuild = true },
                )
            }
        }
        item {
            SettingsSection(stringResource(Res.string.intelligence_how_title)) {
                SettingsBody(stringResource(Res.string.intelligence_how_it_works))
            }
        }
    }

    if (confirmRebuild) {
        ConfirmSettingsAction(
            title = stringResource(Res.string.intelligence_rebuild),
            body = stringResource(Res.string.intelligence_rebuild_confirm),
            confirmLabel = stringResource(Res.string.intelligence_rebuild),
            onConfirm = {
                confirmRebuild = false
                scope.launch {
                    rebuilding = true
                    try {
                        onRebuildAnalysis()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        snackbarHostState.showSnackbar(operationFailed)
                    } finally {
                        rebuilding = false
                    }
                }
            },
            onDismiss = { confirmRebuild = false },
        )
    }
}

private fun actionableIndexingFailures(
    failures: Map<TrackId, EngineError>,
    tracks: List<TrackDescriptor>,
): Map<TrackId, EngineError> {
    val byId = tracks.associateBy(TrackDescriptor::id)
    return failures.filterNot { (id, error) ->
        // Music-library providers can expose playable protected items without a file URL. Their
        // metadata still participates in search, mixes and SMART ranking, so repeatedly presenting
        // them as a broken analysis is both unactionable and misleading.
        error is EngineError.AudioUnavailable && byId[id]?.audioUri == null
    }
}

@Composable
private fun IntelligenceProblemsSettings(
    tracks: List<TrackDescriptor>,
    libraryLoading: Boolean,
    onRetryIndexing: () -> Unit,
    onHideTrack: suspend (TrackDescriptor) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val indexing by AppGraph.automaticIndexing.collectAsState()
    val ids = remember(tracks) { tracks.map(TrackDescriptor::id) }
    val failures = if (!libraryLoading && indexing.trackIds == ids) {
        actionableIndexingFailures(indexing.failures, tracks)
    } else {
        emptyMap()
    }
    val byId = remember(tracks) { tracks.associateBy(TrackDescriptor::id) }
    var removing by remember { mutableStateOf<TrackId?>(null) }
    val manageFailed = stringResource(Res.string.settings_library_manage_failed)
    val removedMessage = stringResource(Res.string.snack_removed_from_latentjam)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.intelligence_problems)) {
                SettingsBody(stringResource(Res.string.intelligence_problems_body))
                if (libraryLoading || (indexing.running && failures.isEmpty())) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                } else if (failures.isEmpty()) {
                    SettingsBody(stringResource(Res.string.intelligence_no_problems))
                } else {
                    SettingsActionRow(
                        title = stringResource(Res.string.intelligence_retry),
                        subtitle = stringResource(Res.string.intelligence_analysis_failed, failures.size),
                        enabled = !indexing.running && removing == null,
                        onClick = onRetryIndexing,
                    )
                }
            }
        }
        items(
            items = failures.entries.toList(),
            key = { it.key.value },
        ) { (id, error) ->
            val track = byId[id]
            IndexingProblemRow(
                title = track?.title ?: stringResource(Res.string.track_untitled),
                artist = track?.artist ?: stringResource(Res.string.track_unknown_artist),
                error = error.toUserMessage(),
                canRemove = track != null && removing == null,
                onRemove = {
                    val target = track ?: return@IndexingProblemRow
                    scope.launch {
                        removing = id
                        try {
                            onHideTrack(target)
                            snackbarHostState.showSnackbar(removedMessage)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            snackbarHostState.showSnackbar(manageFailed)
                        } finally {
                            removing = null
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun IndexingProblemRow(
    title: String,
    artist: String,
    error: String,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "$artist · $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove, enabled = canRemove) {
            Text(stringResource(Res.string.action_remove_from_latentjam))
        }
    }
}

@Composable
private fun SmartExclusionsSettings(
    library: MusicLibrary,
    visibleTracks: List<TrackDescriptor>,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val exclusions = remember { AppGraph.smartExclusions }
    val state by exclusions.state.collectAsState()
    var allTracks by remember(visibleTracks) { mutableStateOf(visibleTracks) }
    var changingKey by remember { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val updateFailed = stringResource(Res.string.snack_smart_exclusion_failed)

    LaunchedEffect(library, visibleTracks) {
        exclusions.load()
        allTracks = (visibleTracks + library.hiddenTracks()).distinctBy(TrackDescriptor::id)
    }
    val byId = remember(allTracks) { allTracks.associateBy(TrackDescriptor::id) }

    fun update(key: String, action: suspend () -> Unit) {
        scope.launch {
            changingKey = key
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                snackbarHostState.showSnackbar(updateFailed)
            } finally {
                changingKey = null
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.intelligence_manage_exclusions)) {
                SettingsBody(stringResource(Res.string.intelligence_manage_exclusions_body))
                if (state.trackIds.isEmpty() && state.artists.isEmpty()) {
                    SettingsBody(stringResource(Res.string.intelligence_exclusions_empty))
                } else {
                    SettingsActionRow(
                        title = stringResource(Res.string.intelligence_exclusions_clear),
                        subtitle = null,
                        enabled = changingKey == null,
                        destructive = true,
                        onClick = { confirmClear = true },
                    )
                }
            }
        }
        if (state.trackIds.isNotEmpty()) {
            item {
                SettingsSection(stringResource(Res.string.intelligence_excluded_tracks)) { }
            }
            items(state.trackIds.sortedBy { byId[it]?.title.orEmpty() }, key = { it.value }) { id ->
                val track = byId[id]
                ExclusionRow(
                    title = track?.title ?: stringResource(Res.string.track_untitled),
                    subtitle = track?.artist ?: stringResource(Res.string.track_unknown_artist),
                    enabled = changingKey == null,
                    onRestore = { update("track:${id.value}") { exclusions.includeTrack(id) } },
                )
            }
        }
        if (state.artists.isNotEmpty()) {
            item {
                SettingsSection(stringResource(Res.string.intelligence_excluded_artists)) { }
            }
            items(state.artists.sortedBy(String::lowercase), key = { it }) { artist ->
                ExclusionRow(
                    title = artist,
                    subtitle = null,
                    enabled = changingKey == null,
                    onRestore = { update("artist:$artist") { exclusions.includeArtist(artist) } },
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmSettingsAction(
            title = stringResource(Res.string.intelligence_exclusions_clear),
            body = stringResource(Res.string.intelligence_manage_exclusions_body),
            onConfirm = {
                confirmClear = false
                update("all") { exclusions.clear() }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ExclusionRow(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onRestore, enabled = enabled) {
            Text(stringResource(Res.string.settings_restore))
        }
    }
}

// ------------------------------------------------------------------- backup

@Composable
private fun BackupSettings(
    settings: AppSettings,
    library: MusicLibrary,
    onBackupRestored: suspend () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val service = remember(settings, library) {
        LocalBackupService(
            settings = settings,
            playlists = AppGraph.playlists,
            history = AppGraph.history,
            recentSearches = AppGraph.recentSearches,
            library = library,
            smartExclusions = AppGraph.smartExclusions,
        )
    }
    var busy by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var selectedSections by remember { mutableStateOf(LocalBackupSections()) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    val exportSuccess = stringResource(Res.string.backup_export_success)
    val importSuccess = stringResource(Res.string.backup_import_success)
    val failedMessage = stringResource(Res.string.backup_failed)
    val invalidMessage = stringResource(Res.string.backup_invalid)
    val partialMessage = stringResource(Res.string.backup_import_partial)

    fun show(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val exchange = rememberLocalBackupFileExchange(
        onExportResult = { result ->
            busy = false
            when (result) {
                is LocalBackupFileResult.Success -> show(exportSuccess)
                LocalBackupFileResult.Cancelled -> Unit
                is LocalBackupFileResult.Failure -> show(failedMessage)
            }
        },
        onImportResult = { result ->
            busy = false
            when (result) {
                is LocalBackupFileResult.Success -> {
                    selectedSections = LocalBackupSections()
                    pendingImport = result.value
                }
                LocalBackupFileResult.Cancelled -> Unit
                is LocalBackupFileResult.Failure -> show(failedMessage)
            }
        },
    )

    fun restore(mode: LocalBackupRestoreMode) {
        val encoded = pendingImport ?: return
        pendingImport = null
        confirmReplace = false
        busy = true
        scope.launch {
            try {
                val report = service.importEncoded(encoded, mode, selectedSections)
                onBackupRestored()
                val message = if (report.unresolvedTrackReferences == 0) {
                    importSuccess
                } else {
                    org.jetbrains.compose.resources.getString(
                        Res.string.backup_import_success_unmatched,
                        report.unresolvedTrackReferences,
                    )
                }
                snackbarHostState.showSnackbar(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LocalBackupFormatException) {
                snackbarHostState.showSnackbar(invalidMessage)
            } catch (failure: LocalBackupRestoreException) {
                snackbarHostState.showSnackbar(
                    if (failure.completedSections.isEmpty()) failedMessage else partialMessage,
                )
            } catch (_: Throwable) {
                snackbarHostState.showSnackbar(failedMessage)
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.settings_backup)) {
                SettingsBody(stringResource(Res.string.settings_backup_body))
                SettingsBody(stringResource(Res.string.backup_destination_warning))
                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                SettingsActionRow(
                    title = stringResource(Res.string.backup_export),
                    subtitle = null,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val encoded = service.exportEncoded()
                                exchange.export(encoded, "latentjam-backup-${epochMillis()}")
                            } catch (cancelled: CancellationException) {
                                busy = false
                                throw cancelled
                            } catch (_: Throwable) {
                                busy = false
                                snackbarHostState.showSnackbar(failedMessage)
                            }
                        }
                    },
                )
                SettingsActionRow(
                    title = stringResource(Res.string.backup_import),
                    subtitle = null,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        exchange.import()
                    },
                )
            }
        }
    }

    if (pendingImport != null && !confirmReplace) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(Res.string.backup_import_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(Res.string.backup_import_body))
                    BackupSectionChoice(
                        label = stringResource(Res.string.settings_title),
                        checked = selectedSections.settings,
                        onCheckedChange = { selectedSections = selectedSections.copy(settings = it) },
                    )
                    BackupSectionChoice(
                        label = stringResource(Res.string.tab_playlists),
                        checked = selectedSections.playlists,
                        onCheckedChange = { selectedSections = selectedSections.copy(playlists = it) },
                    )
                    BackupSectionChoice(
                        label = stringResource(Res.string.privacy_listening_history),
                        checked = selectedSections.listeningHistory,
                        onCheckedChange = {
                            selectedSections = selectedSections.copy(listeningHistory = it)
                        },
                    )
                    BackupSectionChoice(
                        label = stringResource(Res.string.privacy_recent_searches),
                        checked = selectedSections.recentSearches,
                        onCheckedChange = {
                            selectedSections = selectedSections.copy(recentSearches = it)
                        },
                    )
                    BackupSectionChoice(
                        label = stringResource(Res.string.settings_hidden_tracks),
                        checked = selectedSections.hiddenTracks,
                        onCheckedChange = {
                            selectedSections = selectedSections.copy(hiddenTracks = it)
                        },
                    )
                    BackupSectionChoice(
                        label = stringResource(Res.string.intelligence_manage_exclusions),
                        checked = selectedSections.smartExclusions,
                        onCheckedChange = {
                            selectedSections = selectedSections.copy(smartExclusions = it)
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { restore(LocalBackupRestoreMode.MERGE) },
                    enabled = selectedSections.hasAnySelection(),
                ) {
                    Text(stringResource(Res.string.backup_merge))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImport = null }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    TextButton(
                        onClick = { confirmReplace = true },
                        enabled = selectedSections.hasAnySelection(),
                    ) {
                        Text(
                            text = stringResource(Res.string.backup_replace),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
    if (pendingImport != null && confirmReplace) {
        ConfirmSettingsAction(
            title = stringResource(Res.string.backup_replace),
            body = stringResource(Res.string.backup_replace_warning),
            confirmLabel = stringResource(Res.string.backup_replace),
            onConfirm = { restore(LocalBackupRestoreMode.REPLACE) },
            onDismiss = { confirmReplace = false },
        )
    }
}

@Composable
private fun BackupSectionChoice(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun LocalBackupSections.hasAnySelection(): Boolean =
    settings || playlists || listeningHistory || recentSearches || hiddenTracks || smartExclusions

// --------------------------------------------------------------- privacy

private enum class PrivacyRecording { LISTENING, SEARCHES }

@Composable
private fun PrivacySettings(
    settings: AppSettings,
    history: ListeningHistory,
    recentSearches: RecentSearches,
    onClearListeningHistory: suspend () -> Unit,
    onClearRecentSearches: suspend () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val saveListeningHistory by settings.saveListeningHistory.collectAsState()
    val rememberSearches by settings.rememberSearches.collectAsState()
    var listens by remember { mutableStateOf<Int?>(null) }
    var searches by remember { mutableStateOf<Int?>(null) }
    var changingHistory by remember { mutableStateOf(false) }
    var changingSearches by remember { mutableStateOf(false) }
    var clearingHistory by remember { mutableStateOf(false) }
    var clearingSearches by remember { mutableStateOf(false) }
    var confirmHistory by rememberSaveable { mutableStateOf(false) }
    var confirmSearches by rememberSaveable { mutableStateOf(false) }
    var confirmDisable by rememberSaveable { mutableStateOf<PrivacyRecording?>(null) }
    val settingSaveFailed = stringResource(Res.string.privacy_setting_save_failed)
    val dataClearFailed = stringResource(Res.string.privacy_data_clear_failed)

    LaunchedEffect(history, recentSearches) {
        listens = history.stats().values.sumOf { it.plays }
        searches = recentSearches.recent(Int.MAX_VALUE).size
    }

    suspend fun persist(block: suspend () -> Result<Unit>): Boolean {
        val result = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        if (result.isFailure) snackbarHostState.showSnackbar(settingSaveFailed)
        return result.isSuccess
    }

    suspend fun clear(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        snackbarHostState.showSnackbar(dataClearFailed)
        false
    }

    fun setHistoryRecording(enabled: Boolean, deleteExisting: Boolean = false) {
        scope.launch {
            changingHistory = true
            try {
                if (persist { settings.setSaveListeningHistory(enabled) } && deleteExisting) {
                    if (clear(onClearListeningHistory)) listens = 0
                }
            } finally {
                changingHistory = false
            }
        }
    }

    fun setSearchRecording(enabled: Boolean, deleteExisting: Boolean = false) {
        scope.launch {
            changingSearches = true
            try {
                if (persist { settings.setRememberSearches(enabled) } && deleteExisting) {
                    if (clear(onClearRecentSearches)) searches = 0
                }
            } finally {
                changingSearches = false
            }
        }
    }

    fun clearHistory() {
        scope.launch {
            clearingHistory = true
            try {
                if (clear(onClearListeningHistory)) listens = 0
            } finally {
                clearingHistory = false
            }
        }
    }

    fun clearSearches() {
        scope.launch {
            clearingSearches = true
            try {
                if (clear(onClearRecentSearches)) searches = 0
            } finally {
                clearingSearches = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SettingsSection(stringResource(Res.string.settings_section_privacy)) {
                    SettingsBody(stringResource(Res.string.privacy_local_body))
                }
            }
            item {
                SettingsSection(stringResource(Res.string.privacy_listening_history)) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.privacy_listening_history),
                        subtitle = stringResource(Res.string.privacy_save_history_body),
                        checked = saveListeningHistory,
                        enabled = listens != null && !changingHistory && !clearingHistory,
                        onCheckedChange = { enabled ->
                            if (!enabled && (listens ?: 0) > 0) {
                                confirmDisable = PrivacyRecording.LISTENING
                            } else {
                                setHistoryRecording(enabled)
                            }
                        },
                    )
                    StatRow(
                        label = stringResource(Res.string.privacy_listening_history),
                        value = listens?.let { count ->
                            if (count == 0) {
                                stringResource(Res.string.privacy_history_empty)
                            } else {
                                pluralStringResource(Res.plurals.privacy_history_listens, count, count)
                            }
                        } ?: "…",
                    )
                    SettingsActionRow(
                        title = stringResource(Res.string.privacy_clear_history),
                        subtitle = stringResource(Res.string.privacy_clear_history_body),
                        enabled = (listens ?: 0) > 0 && !clearingHistory,
                        destructive = true,
                        onClick = { confirmHistory = true },
                    )
                }
            }
            item {
                SettingsSection(stringResource(Res.string.privacy_recent_searches)) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.privacy_recent_searches),
                        subtitle = stringResource(Res.string.privacy_remember_searches_body),
                        checked = rememberSearches,
                        enabled = searches != null && !changingSearches && !clearingSearches,
                        onCheckedChange = { enabled ->
                            if (!enabled && (searches ?: 0) > 0) {
                                confirmDisable = PrivacyRecording.SEARCHES
                            } else {
                                setSearchRecording(enabled)
                            }
                        },
                    )
                    StatRow(
                        label = stringResource(Res.string.privacy_recent_searches),
                        value = searches?.toString() ?: "…",
                    )
                    SettingsActionRow(
                        title = stringResource(Res.string.privacy_clear_searches),
                        subtitle = stringResource(Res.string.privacy_clear_searches_body),
                        enabled = (searches ?: 0) > 0 && !clearingSearches,
                        destructive = true,
                        onClick = { confirmSearches = true },
                    )
                }
            }
        }

        if (confirmHistory) {
            ConfirmSettingsAction(
                title = stringResource(Res.string.privacy_clear_history),
                body = stringResource(Res.string.privacy_clear_history_confirm),
                onConfirm = {
                    confirmHistory = false
                    clearHistory()
                },
                onDismiss = { confirmHistory = false },
            )
        }
        if (confirmSearches) {
            ConfirmSettingsAction(
                title = stringResource(Res.string.privacy_clear_searches),
                body = stringResource(Res.string.privacy_clear_searches_confirm),
                onConfirm = {
                    confirmSearches = false
                    clearSearches()
                },
                onDismiss = { confirmSearches = false },
            )
        }
        confirmDisable?.let { target ->
            ConfirmDisableRecording(
                title = stringResource(
                    if (target == PrivacyRecording.LISTENING) {
                        Res.string.privacy_listening_history
                    } else {
                        Res.string.privacy_recent_searches
                    },
                ),
                body = stringResource(
                    if (target == PrivacyRecording.LISTENING) {
                        Res.string.privacy_disable_history_body
                    } else {
                        Res.string.privacy_disable_searches_body
                    },
                ),
                onKeepExisting = {
                    confirmDisable = null
                    if (target == PrivacyRecording.LISTENING) {
                        setHistoryRecording(enabled = false)
                    } else {
                        setSearchRecording(enabled = false)
                    }
                },
                onDeleteExisting = {
                    confirmDisable = null
                    if (target == PrivacyRecording.LISTENING) {
                        setHistoryRecording(enabled = false, deleteExisting = true)
                    } else {
                        setSearchRecording(enabled = false, deleteExisting = true)
                    }
                },
                onDismiss = { confirmDisable = null },
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.38f,
                ),
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun ConfirmDisableRecording(
    title: String,
    body: String,
    onKeepExisting: () -> Unit,
    onDeleteExisting: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onDeleteExisting) {
                Text(
                    text = stringResource(Res.string.privacy_delete_existing),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                TextButton(onClick = onKeepExisting) {
                    Text(stringResource(Res.string.privacy_keep_existing))
                }
            }
        },
    )
}

@Composable
private fun ConfirmSettingsAction(
    title: String,
    body: String,
    confirmLabel: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel ?: stringResource(Res.string.action_clear))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

// --------------------------------------------------------------- about

@Composable
private fun AboutSettings(onOpenLicenses: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val version = rememberAppVersion()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(title = null) {
                SettingsBody(stringResource(Res.string.settings_about_body))
                version?.let {
                    StatRow(stringResource(Res.string.settings_version), it)
                }
            }
        }
        item {
            SettingsSection(title = null) {
                SettingsRow(
                    title = stringResource(Res.string.settings_licenses),
                    subtitle = stringResource(Res.string.settings_licenses_body),
                    onClick = onOpenLicenses,
                )
                SettingsActionRow(
                    title = stringResource(Res.string.settings_open_source),
                    subtitle = stringResource(Res.string.settings_open_source_body),
                    onClick = { uriHandler.openUri(SOURCE_URL) },
                )
                SettingsActionRow(
                    title = stringResource(Res.string.settings_report_issue),
                    subtitle = null,
                    onClick = { uriHandler.openUri(ISSUES_URL) },
                )
            }
        }
    }
}

@Composable
private fun LicensesSettings() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingsSection(stringResource(Res.string.settings_license)) {
                SettingsBody(stringResource(Res.string.settings_license_body))
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_license_models)) {
                SettingsBody(stringResource(Res.string.settings_license_models_body))
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_license_text_model)) {
                SettingsBody(stringResource(Res.string.settings_license_text_model_body))
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_license_runtime)) {
                SettingsBody(stringResource(Res.string.settings_license_runtime_body))
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_license_aliases)) {
                SettingsBody(stringResource(Res.string.settings_license_aliases_body))
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_license_libraries)) {
                SettingsBody(stringResource(Res.string.settings_license_libraries_body))
            }
        }
    }
}

// -------------------------------------------------------------------- pieces

@Composable
private fun SettingsSection(title: String?, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (title == null) 12.dp else 0.dp, bottom = 12.dp),
    ) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        content()
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun SettingsBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingsRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val titleColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.5f,
                    ),
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
private fun describeEngineState(state: EngineState): String = when (state) {
    is EngineState.Uninitialized -> stringResource(Res.string.engine_state_not_started)
    is EngineState.Initializing -> stringResource(Res.string.engine_state_starting)
    is EngineState.Ready -> stringResource(Res.string.engine_state_ready)
    is EngineState.Failed -> stringResource(
        Res.string.engine_state_unavailable,
        state.error.toUserMessage(),
    )
}

@Composable
private fun EngineError.toUserMessage(): String = when (this) {
    EngineError.ModelUnavailable -> stringResource(Res.string.engine_error_model_unavailable)
    EngineError.NotIndexed -> stringResource(Res.string.engine_error_not_indexed)
    is EngineError.AudioUnavailable -> stringResource(Res.string.engine_error_audio_unavailable)
    is EngineError.BackendFailure -> stringResource(Res.string.engine_error_backend)
}

private const val MAX_VISIBLE_FAILURES = 5
private const val ROUTE_SEPARATOR = "|"
private const val IOS_MUSIC_LIBRARY_SOURCE_ID = "ios:music"
private const val RESTORE_ALL_OPERATION_ID = "restore-all"
private const val SOURCE_URL = "https://github.com/Nikita-sud/latentjam"
private const val ISSUES_URL = "$SOURCE_URL/issues"
