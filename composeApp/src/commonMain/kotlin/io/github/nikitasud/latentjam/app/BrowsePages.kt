/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.settings_stats
import io.github.nikitasud.latentjam.app.generated.resources.tab_albums
import io.github.nikitasud.latentjam.app.generated.resources.tab_artists
import io.github.nikitasud.latentjam.app.generated.resources.tab_folders
import io.github.nikitasud.latentjam.app.generated.resources.tab_for_you
import io.github.nikitasud.latentjam.app.generated.resources.tab_genres
import io.github.nikitasud.latentjam.app.generated.resources.tab_map
import io.github.nikitasud.latentjam.app.generated.resources.tab_playlists
import io.github.nikitasud.latentjam.app.generated.resources.tab_tracks
import org.jetbrains.compose.resources.StringResource

/** A saved page is an identity, never an index into the listener's changing page order. */
internal fun resolveActiveBrowsePage(
    visiblePages: List<StartPage>,
    savedPageName: String?,
    preferredStartPage: StartPage,
): StartPage = visiblePages.firstOrNull { it.name == savedPageName }
    ?: preferredStartPage.takeIf { it in visiblePages }
    ?: visiblePages.first()

internal fun StartPage.titleResource(): StringResource = when (this) {
    StartPage.FOR_YOU -> Res.string.tab_for_you
    StartPage.MAP -> Res.string.tab_map
    StartPage.PLAYLISTS -> Res.string.tab_playlists
    StartPage.TRACKS -> Res.string.tab_tracks
    StartPage.ALBUMS -> Res.string.tab_albums
    StartPage.ARTISTS -> Res.string.tab_artists
    StartPage.GENRES -> Res.string.tab_genres
    StartPage.FOLDERS -> Res.string.tab_folders
    StartPage.STATISTICS -> Res.string.settings_stats
}
