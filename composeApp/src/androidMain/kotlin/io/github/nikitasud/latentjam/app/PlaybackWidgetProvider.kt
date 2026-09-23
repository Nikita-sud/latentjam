/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import io.github.nikitasud.latentjam.app.shared.R
import io.github.nikitasud.latentjam.playback.MediaBrowseRegistry
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.PlaybackWidgetSnapshot
import io.github.nikitasud.latentjam.playback.PlaybackWidgetStateStore
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Compact 4x1 "Pulse Strip"; keeps the original class so installed widgets upgrade in place. */
public class PlaybackWidgetProvider : LivePlaybackWidgetProvider(WidgetStyle.PULSE)

/** Square 2x2 "Cover Portal" with full-bleed artwork. */
public class ArtworkPlaybackWidgetProvider : LivePlaybackWidgetProvider(WidgetStyle.PORTAL)

/** Detailed 4x2 "SMART Deck" with time, next-up, repeat and three-state shuffle. */
public class DeckPlaybackWidgetProvider : LivePlaybackWidgetProvider(WidgetStyle.DECK)

/**
 * All three picker entries share one honest state/render path.
 *
 * The playback service publishes small metadata snapshots only on discrete player events. Artwork
 * is decoded off the broadcast thread, bounded before it enters RemoteViews, and revision-checked
 * so rapidly skipped track A can never paint over newer track B.
 */
public abstract class LivePlaybackWidgetProvider internal constructor(
    private val style: WidgetStyle,
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdateCoordinator.request(context, style, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        WidgetUpdateCoordinator.request(context, style, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PlaybackWidgetStateStore.stateChangedAction(context) -> {
                val pending = goAsync()
                WidgetUpdateCoordinator.requestAll(context, pending::finish)
            }
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: manager.getAppWidgetIds(ComponentName(context, style.provider))
                val pending = goAsync()
                WidgetUpdateCoordinator.request(context, style, ids, pending::finish)
            }
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> {
                val id = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
                    super.onReceive(context, intent)
                } else {
                    val pending = goAsync()
                    WidgetUpdateCoordinator.request(
                        context,
                        style,
                        intArrayOf(id),
                        pending::finish,
                    )
                }
            }
            else -> super.onReceive(context, intent)
        }
    }
}

/** All widget controls stay behind an explicit, non-exported app receiver. */
public class PlaybackWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        AppGraph.appScope.launchWidgetAction(pending) {
            val playback = AppGraph.playback
            if (intent.action in QUEUE_ACTIONS && !playback.ensureWidgetQueue()) return@launchWidgetAction
            when (intent.action) {
                ACTION_PREVIOUS -> playback.previous()
                ACTION_PLAY_PAUSE -> playback.togglePlayPause()
                ACTION_NEXT -> playback.next()
                ACTION_CYCLE_REPEAT -> playback.cycleRepeatMode()
                ACTION_CYCLE_SHUFFLE -> playback.cycleShuffleMode()
            }
        }
    }

    public companion object {
        public const val ACTION_PREVIOUS: String =
            "io.github.nikitasud.latentjam.widget.PREVIOUS"
        public const val ACTION_PLAY_PAUSE: String =
            "io.github.nikitasud.latentjam.widget.PLAY_PAUSE"
        public const val ACTION_NEXT: String =
            "io.github.nikitasud.latentjam.widget.NEXT"
        public const val ACTION_CYCLE_REPEAT: String =
            "io.github.nikitasud.latentjam.widget.CYCLE_REPEAT"
        public const val ACTION_CYCLE_SHUFFLE: String =
            "io.github.nikitasud.latentjam.widget.CYCLE_SHUFFLE"

        private val QUEUE_ACTIONS: Set<String> = setOf(
            ACTION_PREVIOUS,
            ACTION_PLAY_PAUSE,
            ACTION_NEXT,
            ACTION_CYCLE_REPEAT,
            ACTION_CYCLE_SHUFFLE,
        )
    }
}

/** Restores the last durable queue before a widget command reaches a cold, empty media session. */
private suspend fun PlaybackController.ensureWidgetQueue(): Boolean = widgetRestoreMutex.withLock {
    // Restoration suspends while reading the saved queue. A second cold-start tap must wait
    // for that restore, while transport commands remain free to interrupt a pending SMART Next.
    synchronizeWithPlatformSession()
    if (state.value.queue.isNotEmpty()) return@withLock true
    val resume = MediaBrowseRegistry.resumption?.invoke() ?: return@withLock false
    setShuffleMode(resume.shuffleMode)
    restoreQueue(
        tracks = resume.tracks,
        startIndex = resume.startIndex,
        positionMs = resume.positionMs,
        sourceTracks = resume.sourceTracks,
        smartContinuationIds = resume.smartContinuationIds,
    )
    state.value.queue.isNotEmpty()
}

private fun kotlinx.coroutines.CoroutineScope.launchWidgetAction(
    pending: BroadcastReceiver.PendingResult,
    block: suspend () -> Unit,
) {
    launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(WIDGET_LOG_TAG, "Unable to perform widget action", failure)
        } finally {
            pending.finish()
        }
    }
}

private val widgetRestoreMutex = Mutex()

internal enum class WidgetStyle(
    val layout: Int,
    val provider: Class<out AppWidgetProvider>,
) {
    PULSE(R.layout.widget_playback, PlaybackWidgetProvider::class.java),
    // A new layout identity makes installed hosts reinflate the corrected artwork/scrim stack.
    PORTAL(R.layout.widget_playback_portal, ArtworkPlaybackWidgetProvider::class.java),
    DECK(R.layout.widget_playback_smart_deck, DeckPlaybackWidgetProvider::class.java),
}

/** Rebuild size/type decisions instead of letting a host reapply stale RemoteViews actions. */
internal fun refreshPlaybackWidgets(context: Context) {
    WidgetUpdateCoordinator.requestAll(context)
}

private object WidgetUpdateCoordinator {
    // Empty widgets use the same XML placeholder as the picker; no cover-sized bitmap is needed.
    private val emptyArtwork = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LatentJam-widget-art").apply { isDaemon = true }
    }
    private val artworkCache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_ARTWORK_CACHE
    }
    private data class Target(val style: WidgetStyle, val id: Int)
    private var refreshQueue: WidgetRefreshQueue<Target>? = null

    fun requestAll(context: Context, onComplete: () -> Unit = {}) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val targets = WidgetStyle.entries.mapNotNull { style ->
            manager.getAppWidgetIds(ComponentName(appContext, style.provider))
                .takeIf(IntArray::isNotEmpty)
                ?.let { ids -> style to ids }
        }
        if (targets.isEmpty()) {
            onComplete()
            return
        }
        requestTargets(appContext, targets, onComplete)
    }

    fun request(
        context: Context,
        style: WidgetStyle,
        ids: IntArray,
        onComplete: () -> Unit = {},
    ) {
        if (ids.isEmpty()) {
            onComplete()
            return
        }
        requestTargets(
            context.applicationContext,
            listOf(style to ids.copyOf()),
            onComplete,
        )
    }

    private fun requestTargets(
        context: Context,
        targets: List<Pair<WidgetStyle, IntArray>>,
        onComplete: () -> Unit,
    ) {
        val queue = synchronized(this) {
            refreshQueue ?: WidgetRefreshQueue<Target>(
                execute = { executor.execute(it) },
                render = { render(context, it) },
                onFailure = { Log.w(WIDGET_LOG_TAG, "Unable to refresh widgets", it) },
            ).also { refreshQueue = it }
        }
        queue.request(targets.flatMap { (style, ids) -> ids.map { Target(style, it) } }, onComplete)
    }

    private fun render(context: Context, targets: Set<Target>) {
        var current = PlaybackWidgetStateStore.read(context)
        var artwork = artworkFor(context, current)
        // A cover decode can overlap a rapid skip. Re-read and decode the newer snapshot;
        // after a second race prefer its deterministic local fallback over stale artwork.
        val afterDecode = PlaybackWidgetStateStore.read(context)
        if (afterDecode.revision != current.revision) {
            current = afterDecode
            artwork = artworkFor(context, current)
            val newest = PlaybackWidgetStateStore.read(context)
            if (newest.revision != current.revision) {
                current = newest
                artwork = if (current.mediaId.isBlank()) emptyArtwork else renderLatentArtwork(current)
            }
        }
        val manager = AppWidgetManager.getInstance(context)
        val accent = accentFor(current, artwork)
        targets.forEach { (style, id) ->
            runCatching {
                manager.updateAppWidget(id, buildViews(context, style, id, current, artwork, accent))
            }.onFailure { failure ->
                Log.w(WIDGET_LOG_TAG, "Unable to update ${style.name} widget $id", failure)
            }
        }
    }

    private val accentCache = object : LinkedHashMap<String, Int?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int?>?): Boolean =
            size > MAX_ARTWORK_CACHE * 2
    }

    /** The player's own accent, sampled from the exact bitmap this widget displays. */
    private fun accentFor(snapshot: PlaybackWidgetSnapshot, artwork: Bitmap): Int? {
        if (snapshot.mediaId.isBlank()) return null
        val key = "${snapshot.mediaId}\u0000${snapshot.artworkUri.orEmpty()}"
        synchronized(accentCache) { if (accentCache.containsKey(key)) return accentCache[key] }
        val sampled = runCatching { sampleArtworkAccentArgb(artwork) }.getOrNull()
        synchronized(accentCache) { accentCache[key] = sampled }
        return sampled
    }

    private fun artworkFor(context: Context, snapshot: PlaybackWidgetSnapshot): Bitmap {
        if (snapshot.mediaId.isBlank()) return emptyArtwork
        val key = "${snapshot.mediaId}\u0000${snapshot.artworkUri.orEmpty()}\u0000${snapshot.title}"
        synchronized(artworkCache) { artworkCache[key]?.let { return it } }
        val decoded = snapshot.artworkUri
            ?.let { uri -> decodeBoundedArtwork(context, uri) }
            ?: renderLatentArtwork(snapshot)
        synchronized(artworkCache) { artworkCache[key] = decoded }
        return decoded
    }
}

private fun buildViews(
    context: Context,
    style: WidgetStyle,
    appWidgetId: Int,
    snapshot: PlaybackWidgetSnapshot,
    artwork: Bitmap,
    accentArgb: Int?,
): RemoteViews {
    val hasTrack = snapshot.mediaId.isNotBlank()
    val bootCount = currentBootCount(context)
    val now = SystemClock.elapsedRealtime()
    val livePlaying = snapshot.isLivePlaying(now, bootCount)
    val position = snapshot.projectedPositionMs(now, bootCount)
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    // Legacy options bound both orientations: the smallest height belongs to landscape,
    // while portrait uses the narrow width and tall height. Pair the active dimensions.
    val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val widthKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
        else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
    val heightKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
        else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
    val widthFallbackKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
        else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
    val heightFallbackKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
    val widthDp = options.getInt(widthKey).takeIf { it > 0 }
        ?: options.getInt(widthFallbackKey).takeIf { it > 0 }
        ?: if (style == WidgetStyle.PORTAL) 168 else 280
    val heightDp = options.getInt(heightKey).takeIf { it > 0 }
        ?: options.getInt(heightFallbackKey).takeIf { it > 0 }
        ?: when (style) {
            WidgetStyle.PULSE -> 72
            WidgetStyle.PORTAL -> 156
            WidgetStyle.DECK -> 170
        }
    val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    val views = RemoteViews(context.packageName, style.layout)
    views.setOnClickPendingIntent(R.id.widget_root, appLaunch(context))
    if (hasTrack) {
        views.setImageViewBitmap(R.id.widget_artwork, artwork)
    } else {
        views.setImageViewResource(
            R.id.widget_artwork,
            if (style == WidgetStyle.PORTAL) 0 else R.drawable.widget_artwork_placeholder,
        )
    }
    if (style == WidgetStyle.PORTAL) {
        views.setViewVisibility(R.id.widget_empty_artwork, if (hasTrack) View.GONE else View.VISIBLE)
        // Keep the decorative empty-state mark above metadata even with larger system type.
        val metadataDp = maxOf(112, kotlin.math.ceil(72 + 40 * fontScale).toInt())
        val metadataPx = kotlin.math.ceil(metadataDp * context.resources.displayMetrics.density).toInt()
        views.setViewPadding(R.id.widget_empty_artwork, 0, 0, 0, metadataPx)
        views.setViewVisibility(
            R.id.widget_empty_mark,
            if (heightDp - metadataDp >= 40) View.VISIBLE else View.GONE,
        )
    }
    views.setTextViewText(
        R.id.widget_title,
        snapshot.title.takeIf(String::isNotBlank) ?: context.getString(R.string.widget_app_label),
    )
    views.setTextViewText(
        R.id.widget_artist,
        snapshot.artist.takeIf(String::isNotBlank)
            ?: context.getString(if (hasTrack) R.string.widget_app_label else R.string.widget_ready),
    )
    bindTransport(context, views, style, appWidgetId, snapshot, hasTrack, livePlaying)
    applyTrackAccent(views, style, accentArgb)
    if (style == WidgetStyle.PULSE) {
        // Reserve at least 80dp for metadata while keeping play/next at 48dp. Explicitly
        // restore views after growing a widget; RemoteViews can reuse the compact view.
        val extraMetadataDp = 80 * (fontScale - 1)
        views.setViewVisibility(
            R.id.widget_previous,
            if (widthDp >= 312 + extraMetadataDp) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(
            R.id.widget_artwork,
            if (widthDp >= 264 + extraMetadataDp) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(
            R.id.widget_artist,
            if (heightDp - 16 >= 41 * fontScale + 2) View.VISIBLE else View.GONE,
        )
    }
    if (style == WidgetStyle.DECK) {
        // The top row has the remaining height after 20dp padding and 52dp transport.
        // Preserve title/artist first, then elapsed time, then next-up as room permits.
        val metadataHeightDp = heightDp - 72
        val metadataTopDp = minOf(17f, (metadataHeightDp - 40 * fontScale - 1).coerceAtLeast(0f))
        val showTime = metadataHeightDp >= 55 * fontScale + metadataTopDp + 6
        val showNext = showTime && metadataHeightDp >= 70 * fontScale + metadataTopDp + 8
        val metadataTopPx = kotlin.math.ceil(metadataTopDp * context.resources.displayMetrics.density).toInt()
        views.setViewPadding(R.id.widget_metadata, 0, metadataTopPx, 0, 0)
        views.setViewVisibility(R.id.widget_time_row, if (showTime) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_next_title, if (showNext) View.VISIBLE else View.GONE)
        val shownLinesDp = (if (showNext) 70 else if (showTime) 55 else 40) * fontScale
        val shownGapsDp = if (showNext) 8 else if (showTime) 6 else 1
        val titleTopDp = 11 + metadataTopDp +
            ((metadataHeightDp - shownLinesDp - shownGapsDp - metadataTopDp) / 2).coerceAtLeast(0f)
        // The normal-size pill keeps its designed position. At enlarged type it yields to the
        // title until extra height provides a separate area; shuffle remains an accessible control.
        val showMode = fontScale <= 1f || titleTopDp >= 18 + 15 * fontScale
        views.setViewVisibility(R.id.widget_mode, if (showMode) View.VISIBLE else View.GONE)
        // A listener can stretch the deck well past its 4x2 design height. Let the artwork
        // absorb the extra rows without taking the metadata's width; pre-S launchers keep 84dp.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // 28dp outer padding + 14dp artwork gap. Font scaling needs more room for time
            // and titles, so a tall-but-narrow card should retain a smaller square cover.
            val widthArtLimit = (widthDp - 42 - 112 * fontScale).coerceIn(84f, 168f)
            val artDp = minOf((heightDp - 86).coerceIn(84, 168).toFloat(), widthArtLimit)
            views.setViewLayoutHeight(
                R.id.widget_artwork, artDp, android.util.TypedValue.COMPLEX_UNIT_DIP,
            )
            views.setViewLayoutWidth(
                R.id.widget_artwork, artDp, android.util.TypedValue.COMPLEX_UNIT_DIP,
            )
        }
        views.setTextViewText(R.id.widget_mode, snapshot.shuffleMode.deckLabel(context))
        views.setTextViewText(R.id.widget_duration, formatDuration(snapshot.durationMs))
        views.setTextViewText(
            R.id.widget_next_title,
            snapshot.nextTitle?.takeIf(String::isNotBlank)?.let { title ->
                "${context.getString(R.string.widget_next_up)} · $title"
            } ?: context.getString(R.string.widget_next_up),
        )
        views.setChronometer(
            R.id.widget_elapsed,
            now - position,
            null,
            livePlaying,
        )
        // A stopped Chronometer still recomputes text from its old base when the host reapplies
        // RemoteViews. A plain TextView preserves both paused text and its spoken value.
        views.setViewVisibility(R.id.widget_elapsed, if (livePlaying) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_elapsed_paused, if (livePlaying) View.GONE else View.VISIBLE)
        views.setTextViewText(
            R.id.widget_elapsed_paused,
            android.text.format.DateUtils.formatElapsedTime(position.coerceAtLeast(0) / 1_000),
        )
        views.setOnClickPendingIntent(
            R.id.widget_repeat,
            widgetAction(
                context,
                style,
                appWidgetId,
                PlaybackWidgetActionReceiver.ACTION_CYCLE_REPEAT,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.widget_shuffle,
            widgetAction(
                context,
                style,
                appWidgetId,
                PlaybackWidgetActionReceiver.ACTION_CYCLE_SHUFFLE,
            ),
        )
        views.setContentDescription(
            R.id.widget_repeat,
            "${context.getString(R.string.widget_repeat)} · ${snapshot.repeatMode.label(context)}",
        )
        views.setContentDescription(
            R.id.widget_shuffle,
            "${context.getString(R.string.widget_shuffle)} · ${snapshot.shuffleMode.deckLabel(context)}",
        )
        views.setBoolean(R.id.widget_repeat, "setEnabled", hasTrack)
        views.setBoolean(R.id.widget_shuffle, "setEnabled", hasTrack)
        views.setInt(R.id.widget_repeat, "setImageAlpha", if (hasTrack) snapshot.repeatMode.activeAlpha() else 76)
        // The player's own glyphs: Rounded.Shuffle for off/on, the LatentJam mark for SMART —
        // the same icon the media notification's shuffle slot shows.
        views.setImageViewResource(
            R.id.widget_shuffle,
            if (snapshot.shuffleMode == ShuffleMode.SMART) {
                R.drawable.ic_widget_smart_mark
            } else {
                R.drawable.ic_widget_shuffle
            },
        )
        views.setInt(R.id.widget_shuffle, "setImageAlpha", if (hasTrack) snapshot.shuffleMode.activeAlpha() else 76)
    }
    return views
}


/**
 * A cached song-coloured cloud sits on the neutral card, matching the browse/player language.
 * RemoteViews receives a small static bitmap, so this works before API 31 without an update timer.
 */
private fun applyTrackAccent(views: RemoteViews, style: WidgetStyle, accentArgb: Int?) {
    // RemoteViews may reapply onto an existing view; clear a previous track's tint for empty state.
    views.setInt(R.id.widget_play_pause, "setColorFilter", 0xFF0B0B0B.toInt())
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        views.setColorStateList(R.id.widget_root, "setBackgroundTintList", null)
        views.setColorStateList(R.id.widget_play_pause, "setBackgroundTintList", null)
    }
    if (style != WidgetStyle.PORTAL) {
        views.setImageViewBitmap(R.id.widget_cloud, WidgetCloudCache.bitmap(style, accentArgb))
    }
    if (style == WidgetStyle.DECK) {
        val elapsedColor = accentArgb?.let { blendToward(it, 0xFFFFFFFF.toInt(), 0.7f) }
            ?: 0xFFCBC3F2.toInt()
        views.setTextColor(R.id.widget_elapsed, elapsedColor)
        views.setTextColor(R.id.widget_elapsed_paused, elapsedColor)
    }
    val seed = accentArgb ?: return
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        val pill = blendToward(seed, 0xFFFFFFFF.toInt(), 0.7f)
        val onPill = if (relativeLuminance(pill) > 0.45f) 0xDE000000.toInt() else 0xFFFFFFFF.toInt()
        views.setColorStateList(
            R.id.widget_play_pause,
            "setBackgroundTintList",
            android.content.res.ColorStateList.valueOf(pill),
        )
        views.setInt(R.id.widget_play_pause, "setColorFilter", onPill)
    }
}

/** Accessed only from the existing single-thread widget renderer; capped at eight small images. */
private object WidgetCloudCache {
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val cache = object : LinkedHashMap<Pair<WidgetStyle, Int>, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<WidgetStyle, Int>, Bitmap>?): Boolean =
            size > 8
    }

    fun bitmap(style: WidgetStyle, seed: Int?): Bitmap {
        if (seed == null) return empty
        val key = style to seed
        return cache.getOrPut(key) {
            val width = 320
            val height = if (style == WidgetStyle.DECK) 170 else 72
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val color = liftForDarkSurface(ComposeColor(seed)).copy(alpha = 0.25f).toArgb()
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    width * 0.14f, height * 0.35f, width * 0.78f,
                    intArrayOf(color, Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            bitmap
        }
    }
}

private fun blendToward(from: Int, to: Int, fraction: Float): Int {
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * fraction)).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun relativeLuminance(argb: Int): Float {
    fun linear(value: Int): Float {
        val c = value / 255f
        return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    val r = linear((argb shr 16) and 0xFF)
    val g = linear((argb shr 8) and 0xFF)
    val b = linear(argb and 0xFF)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun bindTransport(
    context: Context,
    views: RemoteViews,
    style: WidgetStyle,
    appWidgetId: Int,
    snapshot: PlaybackWidgetSnapshot,
    hasTrack: Boolean,
    isPlaying: Boolean,
) {
    views.setOnClickPendingIntent(
        R.id.widget_previous,
        widgetAction(context, style, appWidgetId, PlaybackWidgetActionReceiver.ACTION_PREVIOUS),
    )
    views.setOnClickPendingIntent(
        R.id.widget_play_pause,
        widgetAction(context, style, appWidgetId, PlaybackWidgetActionReceiver.ACTION_PLAY_PAUSE),
    )
    views.setOnClickPendingIntent(
        R.id.widget_next,
        widgetAction(context, style, appWidgetId, PlaybackWidgetActionReceiver.ACTION_NEXT),
    )
    views.setImageViewResource(
        R.id.widget_play_pause,
        if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
    )
    views.setInt(
        R.id.widget_play_pause,
        "setBackgroundResource",
        if (isPlaying) R.drawable.widget_play_button_playing else R.drawable.widget_play_button,
    )
    views.setContentDescription(
        R.id.widget_play_pause,
        context.getString(if (isPlaying) R.string.widget_pause else R.string.widget_play),
    )
    setControlEnabled(views, R.id.widget_previous, hasTrack && snapshot.hasPrevious)
    setControlEnabled(views, R.id.widget_play_pause, hasTrack)
    setControlEnabled(views, R.id.widget_next, hasTrack && snapshot.hasNext)
}

private fun setControlEnabled(views: RemoteViews, id: Int, enabled: Boolean) {
    views.setBoolean(id, "setEnabled", enabled)
    views.setInt(id, "setImageAlpha", if (enabled) 255 else 76)
}

private fun appLaunch(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).addFlags(
        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
    ),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

private fun widgetAction(
    context: Context,
    style: WidgetStyle,
    appWidgetId: Int,
    action: String,
): PendingIntent {
    val actionCode = when (action) {
        PlaybackWidgetActionReceiver.ACTION_PREVIOUS -> 1
        PlaybackWidgetActionReceiver.ACTION_PLAY_PAUSE -> 2
        PlaybackWidgetActionReceiver.ACTION_NEXT -> 3
        PlaybackWidgetActionReceiver.ACTION_CYCLE_REPEAT -> 4
        else -> 5
    }
    return PendingIntent.getBroadcast(
        context,
        (style.ordinal + 1) * 1_000_000 + (appWidgetId % 100_000) * 10 + actionCode,
        Intent(context, PlaybackWidgetActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun decodeBoundedArtwork(context: Context, value: String): Bitmap? = runCatching {
    val uri = Uri.parse(value)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth !in 1..100_000 || bounds.outHeight !in 1..100_000) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > ARTWORK_SIZE_PX * 2 ||
        bounds.outHeight / sample > ARTWORK_SIZE_PX * 2
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: return@runCatching null
    if (maxOf(decoded.width, decoded.height) > ARTWORK_SIZE_PX * 2) {
        decoded.recycle()
        return@runCatching null
    }
    centerCrop(decoded, ARTWORK_SIZE_PX)
}.getOrNull()

private fun centerCrop(source: Bitmap, size: Int): Bitmap {
    var output: Bitmap? = null
    try {
        val crop = minOf(source.width, source.height)
        val left = (source.width - crop) / 2
        val top = (source.height - crop) / 2
        val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        output = scaled
        Canvas(scaled).drawBitmap(
            source, Rect(left, top, left + crop, top + crop), Rect(0, 0, size, size),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        output = null // ownership passes to the bounded artwork cache / RemoteViews
        return scaled
    } finally {
        output?.recycle()
        source.recycle()
    }
}

private fun renderLatentArtwork(snapshot: PlaybackWidgetSnapshot): Bitmap {
    val seed = (snapshot.mediaId.ifBlank { snapshot.title.ifBlank { "LatentJam" } }).hashCode()
    val hue = Math.floorMod(seed, 360).toFloat()
    val bitmap = Bitmap.createBitmap(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val first = Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.74f))
    val second = Color.HSVToColor(floatArrayOf((hue + 118f) % 360f, 0.76f, 0.48f))
    paint.shader = LinearGradient(
        0f,
        0f,
        ARTWORK_SIZE_PX.toFloat(),
        ARTWORK_SIZE_PX.toFloat(),
        first,
        second,
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, ARTWORK_SIZE_PX.toFloat(), ARTWORK_SIZE_PX.toFloat(), paint)
    paint.shader = null
    paint.color = 0x30FFFFFF
    canvas.drawCircle(ARTWORK_SIZE_PX * 0.78f, ARTWORK_SIZE_PX * 0.18f, ARTWORK_SIZE_PX * 0.34f, paint)
    paint.color = 0x22000000
    canvas.drawCircle(ARTWORK_SIZE_PX * 0.14f, ARTWORK_SIZE_PX * 0.86f, ARTWORK_SIZE_PX * 0.46f, paint)
    val mark = snapshot.title.trim().take(1).uppercase().ifBlank { "LJ" }
    paint.color = Color.WHITE
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = if (mark.length == 1) ARTWORK_SIZE_PX * 0.45f else ARTWORK_SIZE_PX * 0.28f
    val baseline = ARTWORK_SIZE_PX / 2f - (paint.ascent() + paint.descent()) / 2f
    paint.setShadowLayer(12f, 0f, 5f, 0x66000000)
    canvas.drawText(mark, ARTWORK_SIZE_PX / 2f, baseline, paint)
    return bitmap
}

private fun currentBootCount(context: Context): Int = runCatching {
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
}.getOrDefault(PlaybackWidgetSnapshot.UNKNOWN_BOOT_COUNT)

private fun formatDuration(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun ShuffleMode.deckLabel(context: Context): String = when (this) {
    ShuffleMode.OFF -> context.getString(R.string.widget_mode_in_order)
    ShuffleMode.ON -> context.getString(R.string.widget_mode_shuffle)
    ShuffleMode.SMART -> "SMART"
}

private fun RepeatMode.label(context: Context): String = when (this) {
    RepeatMode.OFF -> context.getString(R.string.widget_mode_off)
    RepeatMode.ALL -> context.getString(R.string.widget_repeat_all)
    RepeatMode.ONE -> context.getString(R.string.widget_repeat_one)
}

// One shared OFF level: two different dimming strengths on one row read as breakage, not state.
private fun RepeatMode.activeAlpha(): Int = if (this == RepeatMode.OFF) 150 else 255
private fun ShuffleMode.activeAlpha(): Int = if (this == ShuffleMode.OFF) 150 else 255

private const val ARTWORK_SIZE_PX = 384
private const val MAX_ARTWORK_CACHE = 4
private const val WIDGET_LOG_TAG = "LatentJamWidget"
