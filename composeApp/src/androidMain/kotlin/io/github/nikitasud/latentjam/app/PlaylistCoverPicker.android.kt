/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberPlaylistCoverPicker(
    onResult: (PlaylistCoverPickResult) -> Unit,
): () -> Unit {
    val activity = LocalActivity.current as? ComponentActivity
        ?: return { onResult(PlaylistCoverPickResult.Failed) }
    val model = remember(activity) {
        val factory = viewModelFactory {
            initializer { PlaylistCoverViewModel(activity.applicationContext, createSavedStateHandle()) }
        }
        ViewModelProvider(activity, factory)[PlaylistCoverViewModel::class.java]
    }
    val currentOnResult by rememberUpdatedState(onResult)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        model.picked(uri)
    }
    var resumed by remember(activity) {
        mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    val completed by model.completed.collectAsState()
    LaunchedEffect(completed, resumed) {
        val result = completed
        if (result != null && resumed) {
            model.acknowledge()
            currentOnResult(result)
        }
    }
    return remember(picker, model) {
        {
            if (model.begin()) {
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    model.failedToLaunch()
                }
            }
        }
    }
}

/** A configuration change replaces only the launcher/callback, never the running image decode. */
private class PlaylistCoverViewModel(
    private val context: Context,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private var phase: String? = handle["phase"]
    val completed = MutableStateFlow<PlaylistCoverPickResult?>(
        when (phase) {
            "decoding" -> PlaylistCoverPickResult.Cancelled // interrupted by process death
            "ready" -> when (handle.get<String>("kind")) {
                "selected" -> handle.get<String>("reference")?.takeIf(::isPlaylistCoverReference)
                    ?.let(PlaylistCoverPickResult::Selected) ?: PlaylistCoverPickResult.Failed
                "cancelled" -> PlaylistCoverPickResult.Cancelled
                else -> PlaylistCoverPickResult.Failed
            }
            else -> null
        },
    )

    fun begin(): Boolean {
        if (phase != null || completed.value != null) return false
        phase = "picking"
        handle["phase"] = phase
        return true
    }

    fun failedToLaunch() = complete(PlaylistCoverPickResult.Failed)

    fun picked(uri: Uri?) {
        if (phase == "decoding" || phase == "ready") return
        if (uri == null) {
            complete(PlaylistCoverPickResult.Cancelled)
            return
        }
        phase = "decoding"
        handle["phase"] = phase
        viewModelScope.launch {
            var unclaimed: String? = null
            val result = try {
                withContext(Dispatchers.IO) {
                    importPlaylistCover(context.contentResolver, uri).also { unclaimed = it }
                }?.let(PlaylistCoverPickResult::Selected) ?: PlaylistCoverPickResult.Failed
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    unclaimed?.let { deletePlaylistCover(it) }
                }
                throw cancelled
            } catch (_: Exception) {
                PlaylistCoverPickResult.Failed
            } catch (_: OutOfMemoryError) {
                PlaylistCoverPickResult.Failed
            }
            complete(result)
        }
    }

    private fun complete(result: PlaylistCoverPickResult) {
        phase = "ready"
        handle["phase"] = phase
        handle["kind"] = when (result) {
            is PlaylistCoverPickResult.Selected -> "selected"
            PlaylistCoverPickResult.Cancelled -> "cancelled"
            PlaylistCoverPickResult.Failed -> "failed"
        }
        handle["reference"] = (result as? PlaylistCoverPickResult.Selected)?.reference
        completed.value = result
    }

    fun acknowledge() {
        completed.value = null
        phase = null
        handle.remove<String>("phase")
        handle.remove<String>("kind")
        handle.remove<String>("reference")
    }

    override fun onCleared() {
        // A finished decode can wait for the Activity to resume. If that Activity is instead
        // permanently closed, no composition can claim its result; rotation keeps this model.
        val unclaimed = (completed.value as? PlaylistCoverPickResult.Selected)?.reference
        if (unclaimed != null) {
            AppGraph.appScope.launch(Dispatchers.IO) { deletePlaylistCover(unclaimed) }
        }
        super.onCleared()
    }
}

internal actual fun playlistCoverUri(reference: String?): String? =
    reference?.takeIf(::isPlaylistCoverReference)?.let { Uri.fromFile(coverFile(it)).toString() }

internal actual suspend fun deletePlaylistCover(reference: String): Boolean = withContext(Dispatchers.IO) {
    if (!isPlaylistCoverReference(reference)) return@withContext false
    val file = coverFile(reference)
    !file.exists() || (file.isFile && file.delete())
}

private fun coverFile(reference: String): File =
    File(File(AndroidAppContext.value.filesDir, PLAYLIST_COVER_DIRECTORY), reference)

private fun importPlaylistCover(resolver: ContentResolver, uri: Uri): String? {
    val encodedSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    if (encodedSize != null && (encodedSize == 0L || encodedSize > 64L * 1024L * 1024L)) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = resolver.openInputStream(uri) ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = playlistCoverDecodeSample(bounds.outWidth, bounds.outHeight) ?: return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return null
    var normalized: Bitmap? = null
    var flattened: Bitmap? = null
    var pending: File? = null
    try {
        // The decoder is sampled before allocating pixels; reject an unexpected codec result.
        if (maxOf(decoded.width, decoded.height) > PLAYLIST_COVER_MAX_EDGE * 2) return null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
            val scale = (PLAYLIST_COVER_MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
                .coerceAtMost(1f)
            postScale(scale, scale)
        }
        val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
        normalized = oriented
        val rendered = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
        flattened = rendered
        Canvas(rendered).apply {
            drawColor(Color.WHITE)
            drawBitmap(oriented, 0f, 0f, null)
        }
        val reference = "${UUID.randomUUID()}.jpg"
        val output = coverFile(reference)
        val directory = output.parentFile ?: return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        pending = File.createTempFile("cover-", ".tmp", directory)
        val encoded = pending.outputStream().use { rendered.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!encoded || !pending.renameTo(output)) return null
        return reference
    } finally {
        pending?.delete()
        flattened?.recycle()
        if (normalized !== decoded) normalized?.recycle()
        decoded.recycle()
    }
}
