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
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a picked image becomes a stored cover. */
internal class CoverImportRule(val maxEdge: Int, val jpegQuality: Int, val keepSmallPng: Boolean)

internal val PLAYLIST_COVER_RULE = CoverImportRule(PLAYLIST_COVER_MAX_EDGE, jpegQuality = 88, keepSmallPng = false)
internal val TAG_COVER_RULE = CoverImportRule(TAG_COVER_MAX_EDGE, TAG_COVER_JPEG_QUALITY, keepSmallPng = true)

/**
 * The system photo picker, with its decode held by a [CoverPickViewModel] under [key], so a
 * configuration change replaces only the launcher, never the running decode.
 */
@Composable
internal fun rememberSystemCoverPicker(
    key: String,
    import: (ContentResolver, Uri) -> String?,
    isReference: (String) -> Boolean,
    delete: suspend (String) -> Unit,
    onResult: (CoverPickOutcome) -> Unit,
): () -> Unit {
    val activity = LocalActivity.current as? ComponentActivity
        ?: return { onResult(CoverPickOutcome.Failed) }
    val model = remember(activity, key) {
        val factory = viewModelFactory {
            initializer {
                CoverPickViewModel(activity.applicationContext, createSavedStateHandle(), import, isReference, delete)
            }
        }
        ViewModelProvider(activity, factory)[key, CoverPickViewModel::class.java]
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
private class CoverPickViewModel(
    private val context: Context,
    private val handle: SavedStateHandle,
    private val import: (ContentResolver, Uri) -> String?,
    private val isReference: (String) -> Boolean,
    private val delete: suspend (String) -> Unit,
) : ViewModel() {
    private var phase: String? = handle["phase"]
    val completed = MutableStateFlow<CoverPickOutcome?>(
        when (phase) {
            "decoding" -> CoverPickOutcome.Cancelled // interrupted by process death
            "ready" -> when (handle.get<String>("kind")) {
                "selected" -> handle.get<String>("reference")?.takeIf(isReference)
                    ?.let(CoverPickOutcome::Picked) ?: CoverPickOutcome.Failed
                "cancelled" -> CoverPickOutcome.Cancelled
                else -> CoverPickOutcome.Failed
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

    fun failedToLaunch() = complete(CoverPickOutcome.Failed)

    fun picked(uri: Uri?) {
        if (phase == "decoding" || phase == "ready") return
        if (uri == null) {
            complete(CoverPickOutcome.Cancelled)
            return
        }
        phase = "decoding"
        handle["phase"] = phase
        viewModelScope.launch {
            var unclaimed: String? = null
            val result = try {
                withContext(Dispatchers.IO) {
                    import(context.contentResolver, uri).also { unclaimed = it }
                }?.let(CoverPickOutcome::Picked) ?: CoverPickOutcome.Failed
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    unclaimed?.let { delete(it) }
                }
                throw cancelled
            } catch (_: Exception) {
                CoverPickOutcome.Failed
            } catch (_: OutOfMemoryError) {
                CoverPickOutcome.Failed
            }
            complete(result)
        }
    }

    private fun complete(result: CoverPickOutcome) {
        phase = "ready"
        handle["phase"] = phase
        handle["kind"] = when (result) {
            is CoverPickOutcome.Picked -> "selected"
            CoverPickOutcome.Cancelled -> "cancelled"
            CoverPickOutcome.Failed -> "failed"
        }
        handle["reference"] = (result as? CoverPickOutcome.Picked)?.reference
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
        val unclaimed = (completed.value as? CoverPickOutcome.Picked)?.reference
        if (unclaimed != null) {
            AppGraph.appScope.launch(Dispatchers.IO) { delete(unclaimed) }
        }
        super.onCleared()
    }
}

/** Imports [uri] into [directory] under [rule]; the new file's name, or null. */
internal fun importCoverImage(resolver: ContentResolver, uri: Uri, directory: File, rule: CoverImportRule): String? {
    val encodedSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    if (encodedSize != null && (encodedSize == 0L || encodedSize > 64L * 1024L * 1024L)) return null
    if (rule.keepSmallPng && encodedSize != null && encodedSize > 0) {
        val size = resolver.openInputStream(uri)?.use { pngSize(it.readAtMost(PNG_HEAD_BYTES)) }
        if (size != null && keepsPickedPng(size.first, size.second, encodedSize)) {
            val png = resolver.openInputStream(uri)?.use { it.readAtMost(TAG_COVER_PNG_KEEP_BYTES.toInt()) }
            if (png != null && keepsPickedPngBytes(png, encodedSize)) {
                return storeCover(directory, "png") { output ->
                    output.write(png)
                    true
                }
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = resolver.openInputStream(uri) ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = coverDecodeSample(bounds.outWidth, bounds.outHeight, rule.maxEdge) ?: return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return null
    var normalized: Bitmap? = null
    var flattened: Bitmap? = null
    try {
        // The decoder is sampled before allocating pixels; reject an unexpected codec result.
        if (maxOf(decoded.width, decoded.height) > rule.maxEdge * 2) return null
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
            val scale = (rule.maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
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
        return storeCover(directory, "jpg") { rendered.compress(Bitmap.CompressFormat.JPEG, rule.jpegQuality, it) }
    } finally {
        flattened?.recycle()
        if (normalized !== decoded) normalized?.recycle()
        decoded.recycle()
    }
}

/** Written through a temp file and a rename, so a torn write never sits under a cover's name. */
private inline fun storeCover(directory: File, extension: String, write: (OutputStream) -> Boolean): String? {
    if (!directory.isDirectory && !directory.mkdirs()) return null
    val reference = "${UUID.randomUUID()}.$extension"
    val pending = File.createTempFile("cover-", ".tmp", directory)
    try {
        if (!pending.outputStream().use(write)) return null
        return if (pending.renameTo(File(directory, reference))) reference else null
    } finally {
        pending.delete()
    }
}

private fun InputStream.readAtMost(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var done = 0
    while (done < count) {
        val read = read(buffer, done, count - done)
        if (read <= 0) break
        done += read
    }
    return buffer.copyOf(done)
}
