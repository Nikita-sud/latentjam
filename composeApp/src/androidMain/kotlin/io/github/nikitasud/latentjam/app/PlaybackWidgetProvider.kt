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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.RemoteViews
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
private suspend fun PlaybackController.ensureWidgetQueue(): Boolean {
    synchronizeWithPlatformSession()
    if (state.value.queue.isNotEmpty()) return true
    val resume = MediaBrowseRegistry.resumption?.invoke() ?: return false
    setShuffleMode(resume.shuffleMode)
    restoreQueue(
        tracks = resume.tracks,
        startIndex = resume.startIndex,
        positionMs = resume.positionMs,
        sourceTracks = resume.sourceTracks,
    )
    return state.value.queue.isNotEmpty()
}

private fun kotlinx.coroutines.CoroutineScope.launchWidgetAction(
    pending: BroadcastReceiver.PendingResult,
    block: suspend () -> Unit,
) {
    launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

internal enum class WidgetStyle(
    val layout: Int,
    val provider: Class<out AppWidgetProvider>,
) {
    PULSE(R.layout.widget_playback, PlaybackWidgetProvider::class.java),
    PORTAL(R.layout.widget_playback_artwork, ArtworkPlaybackWidgetProvider::class.java),
    DECK(R.layout.widget_playback_deck, DeckPlaybackWidgetProvider::class.java),
}

private object WidgetUpdateCoordinator {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LatentJam-widget-art").apply { isDaemon = true }
    }
    private val artworkCache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_ARTWORK_CACHE
    }

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
        executor.execute {
            try {
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
                        artwork = renderLatentArtwork(current)
                    }
                }
                val manager = AppWidgetManager.getInstance(context)
                val accent = accentFor(current, artwork)
                targets.forEach { (style, ids) ->
                    ids.forEach { id ->
                        runCatching {
                            manager.updateAppWidget(
                                id,
                                buildViews(context, style, id, current, artwork, accent),
                            )
                        }.onFailure { failure ->
                            Log.w(WIDGET_LOG_TAG, "Unable to update ${style.name} widget $id", failure)
                        }
                    }
                }
            } finally {
                onComplete()
            }
        }
    }

    private val accentCache = object : LinkedHashMap<String, Int?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int?>?): Boolean =
            size > MAX_ARTWORK_CACHE * 2
    }

    /** The player's own accent, sampled from the exact bitmap this widget displays. */
    private fun accentFor(snapshot: PlaybackWidgetSnapshot, artwork: Bitmap): Int? {
        val key = "${snapshot.mediaId}\u0000${snapshot.artworkUri.orEmpty()}"
        synchronized(accentCache) { if (accentCache.containsKey(key)) return accentCache[key] }
        val sampled = runCatching { sampleArtworkAccentArgb(artwork) }.getOrNull()
        synchronized(accentCache) { accentCache[key] = sampled }
        return sampled
    }

    private fun artworkFor(context: Context, snapshot: PlaybackWidgetSnapshot): Bitmap {
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
    val views = RemoteViews(context.packageName, style.layout)
    views.setOnClickPendingIntent(R.id.widget_root, appLaunch(context))
    views.setImageViewBitmap(R.id.widget_artwork, artwork)
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
    if (style == WidgetStyle.DECK) {
        // A listener can stretch the deck well past its 4x2 design height. Let the artwork
        // absorb the extra rows instead of leaving hollow bands; pre-S launchers keep 96dp.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val minHeightDp = AppWidgetManager.getInstance(context)
                .getAppWidgetOptions(appWidgetId)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            if (minHeightDp > 0) {
                val artDp = (minHeightDp - 92).coerceIn(96, 168).toFloat()
                views.setViewLayoutHeight(
                    R.id.widget_artwork, artDp, android.util.TypedValue.COMPLEX_UNIT_DIP,
                )
                views.setViewLayoutWidth(
                    R.id.widget_artwork, artDp, android.util.TypedValue.COMPLEX_UNIT_DIP,
                )
            }
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
        views.setInt(R.id.widget_repeat, "setImageAlpha", snapshot.repeatMode.activeAlpha())
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
        views.setInt(R.id.widget_shuffle, "setImageAlpha", snapshot.shuffleMode.activeAlpha())
    }
    return views
}


/**
 * Dresses the widget in the playing track's colour, exactly as the in-app player does: the raw
 * sampled seed becomes a dark container (`lerp(seed, black, 0.45)`), the glyph flips black/white
 * on the container's luminance. Without a seed the XML system-accent defaults stay — the same
 * fallback order the app's theme uses.
 */
private fun applyTrackAccent(views: RemoteViews, style: WidgetStyle, accentArgb: Int?) {
    val seed = accentArgb ?: return
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        // The card itself carries the track, exactly like the player's background does; the
        // portal's card IS the artwork, so only the strip and deck take the surface tint.
        if (style != WidgetStyle.PORTAL) {
            views.setColorStateList(
                R.id.widget_root,
                "setBackgroundTintList",
                android.content.res.ColorStateList.valueOf(
                    blendToward(seed, 0xFF000000.toInt(), 0.62f),
                ),
            )
        }
        val pill = blendToward(seed, 0xFF000000.toInt(), 0.45f)
        val onPill = if (relativeLuminance(pill) > 0.45f) 0xDE000000.toInt() else 0xFFFFFFFF.toInt()
        views.setColorStateList(
            R.id.widget_play_pause,
            "setBackgroundTintList",
            android.content.res.ColorStateList.valueOf(pill),
        )
        views.setInt(R.id.widget_play_pause, "setColorFilter", onPill)
    }
    if (style == WidgetStyle.DECK) {
        views.setTextColor(R.id.widget_elapsed, blendToward(seed, 0xFFFFFFFF.toInt(), 0.35f))
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
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
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
    centerCrop(decoded, ARTWORK_SIZE_PX)
}.getOrNull()

private fun centerCrop(source: Bitmap, size: Int): Bitmap {
    val crop = minOf(source.width, source.height)
    val left = (source.width - crop) / 2
    val top = (source.height - crop) / 2
    val square = Bitmap.createBitmap(source, left, top, crop, crop)
    val scaled = Bitmap.createScaledBitmap(square, size, size, true)
    if (square !== source && !source.isRecycled) source.recycle()
    if (scaled !== square && !square.isRecycled) square.recycle()
    return scaled
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
    ShuffleMode.OFF -> context.getString(R.string.widget_mode_off)
    ShuffleMode.ON -> context.getString(R.string.widget_mode_shuffle)
    ShuffleMode.SMART -> "SMART ✦"
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
