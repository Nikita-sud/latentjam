/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.library.defaultDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module
import kotlin.math.abs

/** How the app picks between its light and dark palettes. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The browse destination shown when LatentJam is opened. */
enum class StartPage(internal val persistedValue: String) {
    FOR_YOU("for_you"),
    MAP("map"),
    PLAYLISTS("playlists"),
    TRACKS("tracks"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    GENRES("genres"),
    FOLDERS("folders"),
    STATISTICS("statistics"),
}

/** User-owned browse order. Hidden pages retain their position so enabling them is predictable. */
data class PageLayout(
    val order: List<StartPage> = StartPage.entries.toList(),
    val hiddenPages: Set<StartPage> = setOf(StartPage.MAP, StartPage.STATISTICS),
) {
    val visiblePages: List<StartPage>
        get() = normalized().let { layout -> layout.order.filterNot { it in layout.hiddenPages } }

    /** Repairs duplicates, older partial orders, and an invalid all-hidden configuration. */
    fun normalized(): PageLayout {
        val completeOrder = (order + StartPage.entries).distinct()
        val safeHidden = if (completeOrder.all { it in hiddenPages }) {
            hiddenPages - StartPage.TRACKS
        } else {
            hiddenPages.toSet()
        }
        return if (completeOrder == order && safeHidden == hiddenPages) this else {
            PageLayout(completeOrder, safeHidden)
        }
    }

    /** There must always be at least one browse page available. */
    fun withPageEnabled(page: StartPage, enabled: Boolean): PageLayout {
        val layout = normalized()
        if (!enabled && layout.visiblePages == listOf(page)) return layout
        return layout.copy(hiddenPages = if (enabled) layout.hiddenPages - page else layout.hiddenPages + page)
    }

    fun movePage(page: StartPage, offset: Int): PageLayout {
        val layout = normalized()
        val from = layout.order.indexOf(page)
        val to = (from.toLong() + offset.toLong()).coerceIn(0L, layout.order.lastIndex.toLong()).toInt()
        if (from == to) return layout
        return layout.copy(order = layout.order.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun resolveStartPage(preferred: StartPage): StartPage =
        visiblePages.let { pages -> preferred.takeIf { it in pages } ?: pages.first() }
}

/** Order and visibility share one value so interrupted writes cannot pair different layouts. */
internal fun encodePageLayout(layout: PageLayout): String = layout.normalized().let { normalized ->
    "$PAGE_LAYOUT_PREFIX${normalized.order.joinToString(",") { it.persistedValue }}|" +
        normalized.order.filter { it in normalized.hiddenPages }.joinToString(",") { it.persistedValue }
}

/** Unknown page names can survive an app downgrade without breaking the remaining layout. */
internal fun decodePageLayout(value: String): PageLayout? {
    if (value.length > MAX_PAGE_LAYOUT_CHARS || !value.startsWith(PAGE_LAYOUT_PREFIX)) return null
    val fields = value.removePrefix(PAGE_LAYOUT_PREFIX).split('|')
    if (fields.size != 2) return null
    fun pages(field: String): List<StartPage> = field.split(',').mapNotNull { persisted ->
        StartPage.entries.firstOrNull { it.persistedValue == persisted }
    }
    val order = pages(fields[0]).distinct().takeIf { it.isNotEmpty() } ?: return null
    val hidden = pages(fields[1]).toSet() + (PageLayout().hiddenPages - order.toSet())
    return PageLayout(order, hidden).normalized()
}

internal fun pageLayoutFromPersisted(value: String?): PageLayout =
    value?.let(::decodePageLayout) ?: PageLayout()

private const val PAGE_LAYOUT_PREFIX = "LJPL1|"
private const val MAX_PAGE_LAYOUT_CHARS = 4_096

/** Where the player surface gets its accent colour. */
enum class TrackColorMode(internal val persistedValue: String) {
    /** Prefer artwork and fall back to the SMART embedding when artwork has no useful colour. */
    DYNAMIC("dynamic"),

    /** Derive the colour from the track's local SMART embedding. */
    SMART("smart"),

    /** Keep the current app theme colour for every track. */
    THEME("theme"),

    /** The listener's own colour from [CUSTOM_ACCENT_PALETTE], whatever is playing. */
    CUSTOM("custom"),
}

/** How the browse header shows the pages around the current one. */
enum class PageHeaderStyle(internal val persistedValue: String) {
    /** One centred title and a dot per page: reads the same in every language. */
    DOTS("dots"),

    /** The neighbouring titles beside the current one, shrunk to fit the strip. */
    NEIGHBOURS("neighbours"),

    /** Every page's name in a row that scrolls to keep the current one centred over a bar. */
    TABS("tabs"),

    /** The same row as outlined pills, the current one filled. */
    PILLS("pills"),

    /** One title with a chevron; tapping it lists every page. */
    MENU("menu"),
}

internal fun pageHeaderStyleFromPersisted(value: String?): PageHeaderStyle =
    PageHeaderStyle.entries.firstOrNull { it.persistedValue == value } ?: PageHeaderStyle.DOTS

const val DEFAULT_SMART_QUEUE_LENGTH: Int = 20
val SMART_QUEUE_LENGTH_OPTIONS: List<Int> = listOf(10, DEFAULT_SMART_QUEUE_LENGTH, 40)

/**
 * The artist-variety setting's levels, as SMART's artist-run penalty (SmartEngineConfig.artistRunPenalty):
 * from runs of one artist allowed freely to the most variety. Every level was measured; the middle one was
 * accepted as the default (docs/model-diet-fixes-2026-10-06.md).
 */
val ARTIST_VARIETY_PENALTIES: List<Float> = listOf(0f, 0.25f, 0.5f, 1f, 2f)
const val DEFAULT_ARTIST_VARIETY: Int = 2

/**
 * The same levels for the learned ranker: a multiplier on its tuned artist terms (run, re-entry, third in a row).
 * Free = none, the default = the tuned values; the hard rule terms (duration, titles, holidays) never move.
 */
val ARTIST_VARIETY_RANKER_SCALES: List<Double> = listOf(0.0, 0.5, 1.0, 1.5, 2.0)

internal fun sanitizeArtistVariety(level: Int): Int = level.coerceIn(0, ARTIST_VARIETY_PENALTIES.lastIndex)

internal fun artistVarietyFromPersisted(value: Int?): Int = value?.let(::sanitizeArtistVariety) ?: DEFAULT_ARTIST_VARIETY

internal fun startPageFromPersisted(value: String?): StartPage =
    StartPage.entries.firstOrNull { it.persistedValue == value } ?: StartPage.TRACKS

internal fun trackColorModeFromPersisted(value: String?): TrackColorMode =
    TrackColorMode.entries.firstOrNull { it.persistedValue == value } ?: TrackColorMode.DYNAMIC

/**
 * Keeps callers and migrated persisted values on one of the supported queue sizes.
 *
 * A value between two options prefers the default, then the smaller option. Using [Long] for the
 * distance keeps even [Int.MIN_VALUE] and [Int.MAX_VALUE] safe.
 */
internal fun sanitizeSmartQueueLength(value: Int): Int =
    SMART_QUEUE_LENGTH_OPTIONS.minWithOrNull(
        compareBy<Int> { option -> abs(value.toLong() - option.toLong()) }
            .thenBy { option -> abs(option - DEFAULT_SMART_QUEUE_LENGTH) }
            .thenBy { it },
    ) ?: DEFAULT_SMART_QUEUE_LENGTH

internal fun smartQueueLengthFromPersisted(value: Int?): Int =
    value?.let(::sanitizeSmartQueueLength) ?: DEFAULT_SMART_QUEUE_LENGTH

/** A list's chosen order: the field it sorts by and which way that runs. */
data class SortChoice<S : Enum<S>>(val sort: S, val direction: SongSortDirection)

/** The Tracks tab opens A–Z by title. */
internal val DEFAULT_SONG_SORT: SortChoice<SongSort> = SortChoice(SongSort.TITLE, SongSortDirection.ASCENDING)

/** The Albums tab opens A–Z by title, the order it has always had. */
internal val DEFAULT_ALBUM_SORT: SortChoice<AlbumSort> = SortChoice(AlbumSort.TITLE, SongSortDirection.ASCENDING)

/** An artist's page opens as a discography: newest release first. */
internal val DEFAULT_ARTIST_ALBUM_SORT: SortChoice<AlbumSort> =
    SortChoice(AlbumSort.YEAR, SongSortDirection.DESCENDING)

/** What an artist's page can sort its albums by. Every album there shares the artist. */
internal val ARTIST_ALBUM_SORTS: List<AlbumSort> = listOf(AlbumSort.YEAR, AlbumSort.TITLE)

/** `field:direction`, both lowercase names, one value so a write can never pair two choices. */
internal fun encodeSortChoice(choice: SortChoice<*>): String =
    "${choice.sort.name.lowercase()}:${choice.direction.name.lowercase()}"

/**
 * An unknown field — garbage, or an option a later build renamed or dropped — falls back to
 * [default] whole; a known field with an unreadable direction keeps the field and takes its own
 * natural direction. A sort order is never worth failing a launch over.
 */
internal fun <S : Enum<S>> decodeSortChoice(
    value: String?,
    options: List<S>,
    default: SortChoice<S>,
    naturalDirection: (S) -> SongSortDirection,
): SortChoice<S> {
    val fields = value?.split(':')?.takeIf { it.size <= 2 } ?: return default
    val sort = options.firstOrNull { it.name.lowercase() == fields[0] } ?: return default
    val direction = SongSortDirection.entries.firstOrNull { it.name.lowercase() == fields.getOrNull(1) }
        ?: naturalDirection(sort)
    return SortChoice(sort, direction)
}

internal fun songSortFromPersisted(value: String?): SortChoice<SongSort> =
    decodeSortChoice(value, SongSort.entries, DEFAULT_SONG_SORT) { it.defaultDirection }

internal fun albumSortFromPersisted(value: String?): SortChoice<AlbumSort> =
    decodeSortChoice(value, AlbumSort.entries, DEFAULT_ALBUM_SORT) { it.defaultDirection }

internal fun artistAlbumSortFromPersisted(value: String?): SortChoice<AlbumSort> =
    decodeSortChoice(value, ARTIST_ALBUM_SORTS, DEFAULT_ARTIST_ALBUM_SORT) { it.defaultDirection }

/** Missing or type-corrupt privacy preferences preserve the historical opt-in default. */
internal fun recordingPreferenceFromPersisted(value: Boolean?): Boolean = value ?: true

/** Novelty/effects mixes are an explicit opt-in; a missing or corrupt value stays off. */
internal fun noveltyMixPreferenceFromPersisted(value: Boolean?): Boolean = value ?: false

/** Volume normalization audibly changes playback, so it too is an explicit opt-in. */
internal fun normalizeVolumePreferenceFromPersisted(value: Boolean?): Boolean = value ?: false

/** Exported playlists keep the absolute paths they always had unless the listener asks. */
internal fun relativePlaylistPathsPreferenceFromPersisted(value: Boolean?): Boolean = value ?: false

/** Live SMART queue plus its independent canonical source, persisted as one atomic value. */
internal data class ResumeQueueState(
    val queueTrackIds: List<String>,
    val sourceQueueTrackIds: List<String>,
    val queueIndex: Int,
    val sourceQueuePersisted: Boolean = true,
    val smartContinuationIds: Set<String> = emptySet(),
    val smartPlanPositions: Map<String, Int> = emptyMap(),
)

/**
 * Collision-safe queue codec.
 *
 * Track ids are opaque (imported file identities can legally contain commas), so each id is
 * length-prefixed instead of delimiter-escaped. Both lists and the current index share one encoded
 * value, preventing a crash between separate writes from pairing a new SMART queue with an old
 * source playlist.
 */
internal fun encodeResumeQueueState(state: ResumeQueueState): String = buildString {
    val sourceIds = state.sourceQueueTrackIds.takeIf { state.sourceQueuePersisted }.orEmpty()
    append(RESUME_QUEUE_STATE_PREFIX)
    append(state.queueIndex)
    append('|')
    append(state.queueTrackIds.size)
    append('|')
    state.queueTrackIds.forEach { id ->
        append(id.length)
        append(':')
        append(id)
    }
    append('|')
    append(if (state.sourceQueuePersisted) 1 else 0)
    append('|')
    append(sourceIds.size)
    append('|')
    sourceIds.forEach { id ->
        append(id.length)
        append(':')
        append(id)
    }
    val continuationIds = state.smartContinuationIds.intersect(state.queueTrackIds.toSet()).sorted()
    append('|')
    append(continuationIds.size)
    append('|')
    continuationIds.forEach { id ->
        append(id.length)
        append(':')
        append(id)
    }
    // SMART's plan positions survive a restart like the continuation labels do; otherwise every
    // saved recommendation would reach the listening log as if SMART had not chosen it.
    val queued = state.queueTrackIds.toSet()
    val planPositions = state.smartPlanPositions.entries
        .filter { (id, position) -> id in queued && position >= 1 }
        .sortedBy { it.key }
    append('|')
    append(planPositions.size)
    append('|')
    planPositions.forEach { (id, position) ->
        append(id.length)
        append(':')
        append(id)
        append('=')
        append(position)
        append(';')
    }
}

/**
 * Reads current, v3 and v2 queues. v2 queues have no saved continuation labels, and v2/v3 queues no
 * SMART plan positions.
 */
internal fun decodeResumeQueueState(value: String): ResumeQueueState? {
    val prefix = when {
        value.startsWith(RESUME_QUEUE_STATE_PREFIX) -> RESUME_QUEUE_STATE_PREFIX
        value.startsWith(V3_RESUME_QUEUE_STATE_PREFIX) -> V3_RESUME_QUEUE_STATE_PREFIX
        value.startsWith(LEGACY_RESUME_QUEUE_STATE_PREFIX) -> LEGACY_RESUME_QUEUE_STATE_PREFIX
        else -> return null
    }
    var offset = prefix.length

    fun readIntToken(): Int? {
        val delimiter = value.indexOf('|', startIndex = offset)
        if (delimiter < 0) return null
        val parsed = value.substring(offset, delimiter).toIntOrNull()
        offset = delimiter + 1
        return parsed
    }

    fun readIds(count: Int): List<String>? {
        if (count !in 0..MAX_DECODED_RESUME_QUEUE_IDS) return null
        val ids = ArrayList<String>(count)
        repeat(count) {
            val colon = value.indexOf(':', startIndex = offset)
            if (colon < 0) return null
            val length = value.substring(offset, colon).toIntOrNull()
                ?.takeIf { it >= 0 }
                ?: return null
            val start = colon + 1
            if (length > value.length - start) return null
            val end = start + length
            ids += value.substring(start, end)
            offset = end
        }
        return ids
    }

    /** `len:id=position;` entries, the id length-prefixed like every other id here. */
    fun readPlanPositions(count: Int): Map<String, Int>? {
        if (count !in 0..MAX_DECODED_RESUME_QUEUE_IDS) return null
        val positions = LinkedHashMap<String, Int>(count)
        repeat(count) {
            val id = readIds(1)?.single() ?: return null
            if (value.getOrNull(offset) != '=') return null
            val end = value.indexOf(';', startIndex = offset + 1)
            if (end < 0) return null
            positions[id] = value.substring(offset + 1, end).toIntOrNull()
                ?.takeIf { it >= 1 }
                ?: return null
            offset = end + 1
        }
        return positions
    }

    val queueIndex = readIntToken() ?: return null
    val queueCount = readIntToken() ?: return null
    val queueIds = readIds(queueCount) ?: return null
    if (value.getOrNull(offset) != '|') return null
    offset++
    val sourcePersisted = when (readIntToken()) {
        0 -> false
        1 -> true
        else -> return null
    }
    val sourceCount = readIntToken() ?: return null
    val sourceIds = readIds(sourceCount) ?: return null
    if (!sourcePersisted && sourceIds.isNotEmpty()) return null
    val continuationIds = if (prefix != LEGACY_RESUME_QUEUE_STATE_PREFIX) {
        if (value.getOrNull(offset) != '|') return null
        offset++
        val count = readIntToken() ?: return null
        readIds(count)?.toSet() ?: return null
    } else {
        emptySet()
    }
    val planPositions = if (prefix == RESUME_QUEUE_STATE_PREFIX) {
        if (value.getOrNull(offset) != '|') return null
        offset++
        val count = readIntToken() ?: return null
        readPlanPositions(count) ?: return null
    } else {
        emptyMap()
    }
    if (offset != value.length) return null
    val queued = queueIds.toSet()
    return ResumeQueueState(
        queueTrackIds = queueIds,
        sourceQueueTrackIds = sourceIds,
        queueIndex = queueIndex,
        sourceQueuePersisted = sourcePersisted,
        smartContinuationIds = continuationIds.intersect(queued),
        smartPlanPositions = planPositions.filterKeys { it in queued },
    )
}

private const val RESUME_QUEUE_STATE_PREFIX = "LJQ4|"
private const val V3_RESUME_QUEUE_STATE_PREFIX = "LJQ3|"
private const val LEGACY_RESUME_QUEUE_STATE_PREFIX = "LJQ2|"
private const val MAX_DECODED_RESUME_QUEUE_IDS = 10_000

/**
 * User preferences that belong to the app shell rather than to any one feature.
 *
 * Deliberately small. Anything a feature owns (equalizer curves, the SMART index) is stored by that
 * feature, so a preference screen never becomes the place where unrelated state accumulates.
 */
private val DEFAULT_APPEARANCE: StateFlow<Appearance> = kotlinx.coroutines.flow.MutableStateFlow(Appearance())

/**
 * "Learn from my habits" (the FULL ranker) is off until the listener turns it on. The preference moved to a new key so
 * a value stored by earlier test builds does not carry over.
 */
internal const val DEFAULT_LEARN_FROM_HABITS = false
private val LEARN_FROM_HABITS_DEFAULT_FLOW: StateFlow<Boolean> = MutableStateFlow(DEFAULT_LEARN_FROM_HABITS)

/** "Use favourites" (under "Learn from my habits") is on until the listener turns it off. */
internal const val DEFAULT_USE_FAVOURITES = true
private val USE_FAVOURITES_DEFAULT_FLOW: StateFlow<Boolean> = MutableStateFlow(DEFAULT_USE_FAVOURITES)

/** The learned SMART ranker is on until the listener turns it off. */
internal const val DEFAULT_SMART_RANKER = true
private val SMART_RANKER_FALLBACK_FLOW: StateFlow<Boolean> = MutableStateFlow(false)

interface AppSettings {
    val themeMode: StateFlow<ThemeMode>
    fun setThemeMode(mode: ThemeMode)

    val startPage: StateFlow<StartPage>
    fun setStartPage(page: StartPage)

    val pageLayout: StateFlow<PageLayout>
    /** Persists a complete valid layout and repairs a start page that has been hidden. */
    fun setPageLayout(layout: PageLayout)

    val trackColorMode: StateFlow<TrackColorMode>
    fun setTrackColorMode(mode: TrackColorMode)

    val pageHeaderStyle: StateFlow<PageHeaderStyle>
    fun setPageHeaderStyle(style: PageHeaderStyle)

    val smartQueueLength: StateFlow<Int>
    fun setSmartQueueLength(length: Int)

    /** How strongly SMART avoids several tracks in a row by one artist: an index into [ARTIST_VARIETY_PENALTIES]. */
    val artistVariety: StateFlow<Int>
    fun setArtistVariety(level: Int)

    /**
     * Whether For You may surface separately routed meme/viral and sound-effect clusters.
     *
     * These tracks are never folded into music mixes. This preference only controls whether their
     * own clearly named mixes are visible.
     */
    val includeNoveltyMixes: StateFlow<Boolean>
    fun setIncludeNoveltyMixes(enabled: Boolean)

    /**
     * "Learn from my habits": whether the learned ranker plans with the FULL model, which reads the
     * listener's own history, rather than the objective one. Off by default
     * ([DEFAULT_LEARN_FROM_HABITS]); implementations without the preference keep that default.
     */
    val learnFromHabits: StateFlow<Boolean> get() = LEARN_FROM_HABITS_DEFAULT_FLOW
    fun setLearnFromHabits(enabled: Boolean) {}

    /**
     * "Use favourites", nested under [learnFromHabits]: whether the FULL ranker sees the listener's hearts
     * (is_favourite, favourited_days_ago). Ignored while habits are off; hearts are still logged locally either way.
     */
    val useFavourites: StateFlow<Boolean> get() = USE_FAVOURITES_DEFAULT_FLOW
    fun setUseFavourites(enabled: Boolean) {}

    /**
     * Whether SMART plans its queues with the learned ranker (docs/smart-ranker.md) instead of its
     * chain. On by default ([DEFAULT_SMART_RANKER]); implementations without the preference keep it off.
     */
    val smartRanker: StateFlow<Boolean> get() = SMART_RANKER_FALLBACK_FLOW
    fun setSmartRanker(enabled: Boolean) {}

    /** Whether playback attenuates loud tracks toward a common level. Off by default. */
    val normalizeVolume: StateFlow<Boolean>
    fun setNormalizeVolume(enabled: Boolean)

    /** Seconds of amplitude fade at each track boundary; 0 (the default) keeps hard cuts. */
    val crossfadeSeconds: StateFlow<Int>
    fun setCrossfadeSeconds(seconds: Int)

    /** Whether SMART sizes each crossfade to the two tracks it joins (issue #25). Off by default. */
    val adaptiveCrossfade: StateFlow<Boolean> get() = AdaptiveCrossfadeOff
    fun setAdaptiveCrossfade(enabled: Boolean) {}

    /**
     * Whether an M3U export writes each path from the playlist's own folder (`../Music/…`), for
     * players on other devices, instead of this device's absolute path. Off by default; only
     * offered where [relativePlaylistPathsAvailable].
     */
    val relativePlaylistPaths: StateFlow<Boolean>
    fun setRelativePlaylistPaths(enabled: Boolean)

    /** Android notification layout; device-local like playlist export and per-playlist sorting. */
    val notificationFavorite: StateFlow<Boolean> get() = DEFAULT_NOTIFICATION_FAVORITE
    fun setNotificationFavorite(enabled: Boolean) {}

    /** Android only: the full player opens above the lock screen when the screen turns off. */
    val lockScreenPlayer: StateFlow<Boolean> get() = DEFAULT_LOCK_SCREEN_PLAYER
    fun setLockScreenPlayer(enabled: Boolean) {}

    /** The Tracks tab's order. */
    val songSort: StateFlow<SortChoice<SongSort>>
    fun setSongSort(choice: SortChoice<SongSort>)

    /** The Albums tab's order. */
    val albumSort: StateFlow<SortChoice<AlbumSort>>
    fun setAlbumSort(choice: SortChoice<AlbumSort>)

    /** How an artist's page orders their albums; one choice for every artist. */
    val groupSortOrders: StateFlow<GroupSortOrders>
    fun setGroupSortOrders(orders: GroupSortOrders)

    val artistAlbumSort: StateFlow<SortChoice<AlbumSort>>

    /** Ignores a field outside [ARTIST_ALBUM_SORTS]. */
    fun setArtistAlbumSort(choice: SortChoice<AlbumSort>)

    /**
     * How the Folders tab shows the library: the folder tree (the default) or every folder in one
     * list. The platforms persist it; the defaults here only serve settings that never store it.
     */
    val folderView: StateFlow<FolderView> get() = DEFAULT_FOLDER_VIEW
    fun setFolderView(view: FolderView) {}

    /** Accent, black floor, cloud, corners, mini player, hand and album columns; see [Appearance]. */
    val appearance: StateFlow<Appearance> get() = DEFAULT_APPEARANCE
    fun setAppearance(appearance: Appearance) {}

    /** How each playlist's page and Favorites order their tracks; device-local, see [PlaylistSortOrders]. */
    val playlistSortOrders: StateFlow<PlaylistSortOrders>
    fun setPlaylistSortOrders(orders: PlaylistSortOrders)

    /** Raw persisted loudness measurements (see [encodeTrackLoudness]); null when none exist. */
    fun readTrackLoudnessPayload(): String?
    fun writeTrackLoudnessPayload(payload: String)

    /** Raw persisted per-track embedded genres (see [GenreEnrichment]); null when none exist. */
    fun readTrackGenresPayload(): String?
    fun writeTrackGenresPayload(payload: String)

    /**
     * The same genre payload a line at a time, for a cache with a line per song: a platform that
     * keeps it in a file streams it instead of holding one string of the whole library. Null
     * when none exists; otherwise what [read] returns, after it has seen every line.
     */
    fun <T> readTrackGenreLines(read: (Sequence<String>) -> T): T? =
        readTrackGenresPayload()?.let { read(it.lineSequence()) }

    /** Replaces the genre payload with the lines [write] emits through its argument, in order. */
    fun writeTrackGenreLines(write: ((String) -> Unit) -> Unit) {
        val payload = StringBuilder()
        var first = true
        write { line ->
            if (!first) payload.append('\n')
            first = false
            payload.append(line)
        }
        writeTrackGenresPayload(payload.toString())
    }

    /** SMART carry-overs from tag edits (see [AudioCarryOverStore]); null when none. */
    fun readAudioCarryOversPayload(): String?
    fun writeAudioCarryOversPayload(payload: String)

    /** Pairs the listener marked as different recordings; the duplicate finder keeps them apart. */
    fun readDuplicateDismissalsPayload(): String?
    fun writeDuplicateDismissalsPayload(payload: String)

    /**
     * When a deferred Map rebuild was first asked for and has not run since, in epoch ms (0: none waiting). Kept on
     * disk so the in-app fallback's day survives process deaths; the defaults keep fakes and tests storage-free.
     */
    fun readMapRebuildRequestedAtMs(): Long = 0L
    fun writeMapRebuildRequestedAtMs(epochMs: Long) = Unit

    /** Whether new playback sessions are written to the private on-device listening log. */
    val saveListeningHistory: StateFlow<Boolean>

    /**
     * Persists [enabled] before changing the observable value.
     *
     * Unlike purely visual preferences, privacy controls must not claim to be off when the durable
     * write failed and the next launch would silently turn them back on.
     */
    suspend fun setSaveListeningHistory(enabled: Boolean): Result<Unit>

    /** Whether searches submitted from the library are added to the private recent-query list. */
    val rememberSearches: StateFlow<Boolean>

    /** See [setSaveListeningHistory]; the same persistence guarantee applies to search history. */
    suspend fun setRememberSearches(enabled: Boolean): Result<Unit>

    /** The playback session to restore at launch, or null when nothing was saved. */
    val resumePlayback: StateFlow<ResumePlayback?>

    /** Fire-and-forget: written on every track/mode change, so best-effort like the visual prefs. */
    fun setResumePlayback(state: ResumePlayback?)
}

/**
 * Where listening stood when the app last ran: enough to put the same track back in the player,
 * paused, with the same shuffle mode — so SMART stays on across launches without being re-armed.
 *
 * [shuffleMode] is the mode's name rather than the enum so a persisted value from a build with
 * different modes degrades to "no restore" instead of crashing launch.
 */
data class ResumePlayback(
    val trackId: String,
    val shuffleMode: String,
    val positionMs: Long,
    /**
     * [QueueSourceKind] name of what the queue was started from, or null when unknown — including
     * every session saved before sources existed. Stored as a name for the same reason as
     * [shuffleMode].
     */
    val sourceKind: String? = null,
    /** Display name for name-bearing sources (collection title, search query). */
    val sourceName: String? = null,
    /** Stable source identity used to reconstruct an omitted oversized queue when available. */
    val sourceReference: String? = null,
    /**
     * The queue's track ids in play order, so a restart restores the queue that was actually
     * playing — a playlist stays that playlist — instead of wrapping the track in the whole
     * library. Empty for sessions saved before this existed, or when the queue was too large
     * to be worth persisting; restore falls back to the whole-library wrap then.
     */
    val queueTrackIds: List<String> = emptyList(),
    /** Index of the saved current row in [queueTrackIds], or -1 for legacy sessions. */
    val queueIndex: Int = -1,
    /**
     * Canonical natural-order source retained independently from a generated SMART queue. Empty
     * for legacy sessions or when an oversized source is reconstructed from [sourceReference].
     */
    val sourceQueueTrackIds: List<String> = emptyList(),
    /** Whether [sourceQueueTrackIds] was explicitly saved; distinguishes known empty from absent. */
    val sourceQueuePersisted: Boolean = false,
    /** Saved provenance for rows in [queueTrackIds]; absent from older sessions. */
    val smartContinuationIds: Set<String> = emptySet(),
    /** SMART's plan position for rows in [queueTrackIds] it recommended; absent from older sessions. */
    val smartPlanPositions: Map<String, Int> = emptyMap(),
)

/** True when persistence can update only the small position field and retain queue metadata. */
internal fun ResumePlayback.sameSessionExceptPosition(other: ResumePlayback): Boolean =
    trackId == other.trackId &&
        shuffleMode == other.shuffleMode &&
        sourceKind == other.sourceKind &&
        sourceName == other.sourceName &&
        sourceReference == other.sourceReference &&
        queueTrackIds == other.queueTrackIds &&
        queueIndex == other.queueIndex &&
        sourceQueueTrackIds == other.sourceQueueTrackIds &&
        sourceQueuePersisted == other.sourceQueuePersisted &&
        smartContinuationIds == other.smartContinuationIds &&
        smartPlanPositions == other.smartPlanPositions

private val DEFAULT_NOTIFICATION_FAVORITE: StateFlow<Boolean> =
    kotlinx.coroutines.flow.MutableStateFlow(false)

/** Whether this platform offers configurable media notification actions. */
internal expect val notificationFavoriteAvailable: Boolean

private val DEFAULT_LOCK_SCREEN_PLAYER: StateFlow<Boolean> =
    kotlinx.coroutines.flow.MutableStateFlow(false)

/** Whether this platform can draw the app's own player above the lock screen (iOS cannot). */
internal expect val lockScreenPlayerAvailable: Boolean

expect fun appSettingsModule(): Module

/** [AppSettings.adaptiveCrossfade] for settings stores that do not keep it. */
private val AdaptiveCrossfadeOff: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
