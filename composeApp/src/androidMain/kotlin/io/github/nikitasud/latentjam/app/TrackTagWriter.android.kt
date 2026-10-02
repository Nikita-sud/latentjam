/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nikitasud.latentjam.library.tags.write.ChannelTargetFile
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.FileRecoveryDirectory
import io.github.nikitasud.latentjam.library.tags.write.TagRecovery
import io.github.nikitasud.latentjam.library.tags.write.TargetFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

internal fun tagWriteStrategy(sdkInt: Int): TagWriteStrategy = when {
    sdkInt >= Build.VERSION_CODES.R -> TagWriteStrategy.SYSTEM_WRITE_REQUEST
    sdkInt >= Build.VERSION_CODES.Q -> TagWriteStrategy.RECOVERABLE_CONSENT
    else -> TagWriteStrategy.WRITE_PERMISSION
}

/**
 * The one coordinator over the store, for the life of the process. An Activity that finishes no
 * longer stops a batch. A second MainActivity (started into another task) shares this coordinator
 * instead of starting one whose restore would delete this one's key and cover files. Checkpoints
 * still go to every live Activity's saved state, so process death restores a batch as before.
 * Main thread only, like every coordinator call.
 */
internal object AndroidTagWrites {
    private const val CHECKPOINT = "tag-writes"
    private val checkpoints = CheckpointFanOut()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var coordinator: TagWriteCoordinator<IntentSender>? = null

    /** [handle]'s checkpoint restores only the coordinator it creates; a later handle just receives checkpoints. */
    fun attach(context: Context, handle: SavedStateHandle, owner: Any): TagWriteCoordinator<IntentSender> {
        checkpoints.attach(owner) { handle[CHECKPOINT] = ArrayList(it) }
        coordinator?.let { return it }
        val backend = AndroidTagWriteBackend(context.applicationContext)
        val created = TagWriteCoordinator(
            backend = backend,
            scope = scope,
            io = Dispatchers.IO,
            restored = handle.get<ArrayList<String>>(CHECKPOINT),
            save = checkpoints::save,
        )
        coordinator = created
        // Eagerly but off the main thread. A save that comes first prepares the store itself.
        scope.launch(Dispatchers.IO) {
            try {
                backend.prepareStore()
            } catch (_: Exception) {
                // Retried by the first save, which fails if it fails again.
            }
        }
        return created
    }

    fun detach(owner: Any) = checkpoints.detach(owner)
}

/** Connects an Activity's saved state to [AndroidTagWrites]; it owns nothing itself. */
internal class TagWriteViewModel(context: Context, handle: SavedStateHandle) : ViewModel() {
    val coordinator = AndroidTagWrites.attach(context, handle, this)

    override fun onCleared() {
        AndroidTagWrites.detach(this)
    }
}

@Composable
private fun tagWriteCoordinator(): TagWriteCoordinator<IntentSender>? {
    val activity = LocalActivity.current as? ComponentActivity ?: return null
    return remember(activity) {
        val factory = viewModelFactory {
            initializer { TagWriteViewModel(activity.applicationContext, createSavedStateHandle()) }
        }
        ViewModelProvider(activity, factory)[TagWriteViewModel::class.java]
    }.coordinator
}

@Composable
internal actual fun rememberTagWriteAccess(): TagWriteAccess? {
    val coordinator = tagWriteCoordinator() ?: return null
    return remember(coordinator) {
        TagWriteAccess(coordinator, readOnlyIsMusicLibrary = false) { track ->
            track.audioUri?.takeIf(String::isNotBlank)
        }
    }
}

@Composable
actual fun TagWriteHost() {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val coordinator = tagWriteCoordinator() ?: return
    val scope = rememberCoroutineScope()
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        coordinator.answer(when {
            result.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true -> WriteAnswer.FAILED
            result.resultCode == Activity.RESULT_OK -> WriteAnswer.APPROVED
            else -> WriteAnswer.CANCELLED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        coordinator.answer(if (granted) WriteAnswer.APPROVED else WriteAnswer.CANCELLED)
    }
    var resumed by remember(activity) { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(coordinator, resumed) {
        if (resumed) {
            coordinator.onHostResumed()
            coordinator.refreshRecovery()
        }
    }
    val prompt by coordinator.prompt.collectAsState()
    LaunchedEffect(prompt, resumed) {
        val pending = prompt
        if (resumed && pending != null && coordinator.promptLaunched(pending.requestId)) {
            try {
                val sender = pending.consent
                if (sender == null) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coordinator.answer(WriteAnswer.FAILED)
            }
        }
    }
    val completed by coordinator.completed.collectAsState()
    LaunchedEffect(completed, resumed) {
        val request = completed
        if (resumed && request != null) {
            // Delivered synchronously so recomposition cannot hand the same report out twice.
            coordinator.deliver(request.id)
            // Outside this effect: delivering clears `completed`, which restarts it and would cancel the refresh.
            scope.launch { coordinator.refreshRecovery() }
        }
    }
    TagRecoveryPrompt(coordinator)
}

/**
 * Tag saves through MediaStore. The recovery store is `noBackupFilesDir/tag-write`. Each request's
 * keys and replacement cover live beside it in `tag-write-requests`, never inside the store, whose
 * sweep deletes every name it does not know.
 */
private class AndroidTagWriteBackend(private val context: Context) : TagWriteBackend<IntentSender> {
    override val strategy = tagWriteStrategy(Build.VERSION.SDK_INT)
    private val root = File(context.noBackupFilesDir, "tag-write")
    private val store = FileRecoveryDirectory(root, ::syncDirectory)
    private val requestFiles = File(context.noBackupFilesDir, "tag-write-requests")
    private val keyDirectory = File(requestFiles, "keys")
    private val coverDirectory = File(requestFiles, "covers")
    override val writer = DurableWriter(store, { UUID.randomUUID().toString() })
    override val recovery = TagRecovery(store)

    /**
     * The store's directory, synced into its parent: a journal record inside it is only as durable
     * as the directory's own entry. Not cached when it throws, so the next call tries again.
     */
    private val storeReady = lazy {
        if (!root.mkdirs() && !root.isDirectory) throw IOException("cannot create $root")
        syncDirectory(context.noBackupFilesDir)
    }

    fun prepareStore() {
        storeReady.value
    }

    override fun hasWritePermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    override suspend fun batchConsent(keys: List<String>): IntentSender = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        MediaStore.createWriteRequest(context.contentResolver, keys.map(Uri::parse)).intentSender
    }

    override suspend fun absent(keys: List<String>, paths: Map<String, String>): Map<String, WriteOpen<Nothing>> =
        withContext(Dispatchers.IO) {
            val rows = rowPaths(keys)
            val byPath = seesFilesByPath()
            val absent = HashMap<String, WriteOpen<Nothing>>()
            for (key in keys) {
                val row = rows[key]
                // A file the app sees by path is proven there by a stat; any other is opened read-only.
                if (byPath && row != null && File(row).exists()) continue
                val there = try {
                    context.contentResolver.openFileDescriptor(Uri.parse(key), "r")?.close()
                    true
                } catch (_: FileNotFoundException) {
                    false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A refusal or a provider error says nothing about the file: it stays in the request.
                    true
                }
                if (!there) absent[key] = if (isGone(row ?: paths[key])) WriteOpen.Missing else WriteOpen.Failed
            }
            absent
        }

    /**
     * Each key's DATA path, for the keys MediaStore still has a row for: one query per [ROW_CHUNK]
     * ids, not one per file. A key that is no audio row's URI has none.
     */
    @Suppress("DEPRECATION")
    private fun rowPaths(keys: List<String>): Map<String, String> {
        val prefix = "${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/"
        val keyById = HashMap<Long, String>()
        for (key in keys) {
            val id = key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull() ?: continue
            keyById[id] = key
        }
        val paths = HashMap<String, String>()
        for (chunk in keyById.keys.chunked(ROW_CHUNK)) {
            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
                    "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map(Long::toString).toTypedArray(),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val key = keyById[cursor.getLong(0)] ?: continue
                        cursor.getString(1)?.takeIf(String::isNotBlank)?.let { paths[key] = it }
                    }
                }
            }
        }
        return paths
    }

    /** Where an interrupted save's record says [key]'s file was; only read when MediaStore has lost it. */
    private fun storedPath(key: String): String? = try {
        recovery.pending().firstNotNullOfOrNull { record -> record.path?.takeIf { record.target == key } }
    } catch (_: Exception) {
        null
    }

    override suspend fun open(key: String): WriteOpen<IntentSender> = withContext(Dispatchers.IO) {
        try {
            // Every save and every recovery opens its file first, so the store is ready before either.
            prepareStore()
            val file = MediaStoreFile(key)
            openExistingForWrite(file, file.path?.let(::freeBytesAt), file.path) { failure ->
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && failure is RecoverableSecurityException) {
                    WriteOpen.NeedsConsent(failure.userAction.actionIntent.intentSender)
                } else WriteOpen.Denied
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WriteOpen.Failed
        }
    }

    /** One audio row's file. Reading never needs more consent than the library already has. */
    private inner class MediaStoreFile(private val key: String) : MediaFileAccess {
        private val uri: Uri = Uri.parse(key)

        /** Read before any open: the row may go with the file, and this still says where it was. */
        val path: String? = filePathOf(context, uri)

        override fun probe(): Long {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("no descriptor for $uri")
            return descriptor.use { it.statSize }
        }

        override fun openReadWrite(): OpenedForWrite {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("no descriptor for $uri")
            val size = descriptor.statSize
            // Neither stream is closed: on Android a stream over a descriptor it did not open leaves the
            // descriptor open, and closing it is the ParcelFileDescriptor's job, done once on close.
            val file = ChannelTargetFile(
                FileInputStream(descriptor.fileDescriptor).channel,
                FileOutputStream(descriptor.fileDescriptor).channel,
            ) { descriptor.close() }
            return OpenedForWrite(file, size)
        }

        // A row MediaStore has dropped names no path; an interrupted save's record may still.
        override fun isGone(): Boolean = isGone(filePathOf(context, uri) ?: path ?: storedPath(key))
    }

    /**
     * True only when the file is certainly not there, so its save may be reported MISSING. An
     * unmounted volume, a path the app cannot see files by, or a file still at its path proves
     * nothing: those are FAILED.
     */
    private fun isGone(path: String?): Boolean {
        if (path == null) return false
        val file = File(path)
        if (Environment.getExternalStorageState(file) != Environment.MEDIA_MOUNTED) return false
        if (!seesFilesByPath()) return false
        return !file.exists()
    }

    /** Whether `File` sees other apps' audio: not under Android 10's scoped storage, nor without read access. */
    private fun seesFilesByPath(): Boolean {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) return Environment.isExternalStorageLegacy()
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun freeBytesAt(path: String): Long? = try {
        File(path).parent?.let { StatFs(it).availableBytes }
    } catch (_: Exception) {
        // Unknown is allowed: the writer then relies on the disk-full error itself.
        null
    }

    /** One scan for the whole batch, waited for so a library refresh after it reads the new tags. */
    override suspend fun rescan(keys: List<String>) {
        val paths = withContext(Dispatchers.IO) { keys.mapNotNull { filePathOf(context, Uri.parse(it)) } }
        if (paths.isEmpty()) return
        withTimeoutOrNull(maxOf(SCAN_TIMEOUT_MS, paths.size * 50L)) {
            suspendCancellableCoroutine { continuation ->
                // The callbacks arrive on the scanner's thread, not this one.
                val remaining = AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    if (remaining.decrementAndGet() == 0 && continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    override fun stash(name: String, bytes: ByteArray) {
        coverDirectory.mkdirs()
        val partial = File(coverDirectory, name + PARTIAL)
        partial.writeBytes(bytes)
        replace(partial, File(coverDirectory, name))
    }

    override fun unstash(name: String): ByteArray? = File(coverDirectory, name).takeIf { it.isFile }?.readBytes()

    override fun drop(name: String) {
        File(coverDirectory, name).delete()
    }

    /** Leftover partial files too: no request names them, so the restore's prune deletes them. */
    override fun stashNames(): List<String> = coverDirectory.list()?.toList().orEmpty()

    override fun saveKeys(id: Long, keys: List<String>) {
        keyDirectory.mkdirs()
        val partial = File(keyDirectory, "$id$KEYS$PARTIAL")
        FileOutputStream(partial).use { writeTagWriteKeys(it, keys) }
        replace(partial, File(keyDirectory, "$id$KEYS"))
    }

    override fun loadKeys(id: Long): List<String>? {
        val file = File(keyDirectory, "$id$KEYS").takeIf { it.isFile } ?: return null
        return FileInputStream(file).use { readTagWriteKeys(it, file.length()) }
    }

    override fun dropKeys(id: Long) {
        File(keyDirectory, "$id$KEYS").delete()
        File(keyDirectory, "$id$KEYS$PARTIAL").delete()
    }

    /** Ids with a partial file count too, so the restore's prune deletes those as well. */
    override fun savedKeyIds(): List<Long> = keyDirectory.list().orEmpty()
        .mapNotNull { KEY_FILE.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        .distinct()

    private companion object {
        const val ROW_CHUNK = 500
        const val KEYS = ".keys"
        const val PARTIAL = ".partial"
        val KEY_FILE = Regex("""(\d+)\.keys(?:\.partial)?""")
    }
}

/**
 * One file as [openExistingForWrite] opens it: MediaStore on a device, a fake in host tests.
 * [probe] opens read-only, which never creates a file, and answers its size (-1 when unknown); it
 * throws FileNotFoundException when the file cannot be opened. [openReadWrite] creates a file that
 * is not there, as every MediaStore write mode does ("w", "rw" and "rwt" all carry MODE_CREATE).
 * [isGone] is true only when the file is certainly not there.
 */
internal interface MediaFileAccess {
    fun probe(): Long
    fun openReadWrite(): OpenedForWrite
    fun isGone(): Boolean
}

internal class OpenedForWrite(val file: TargetFile, val size: Long)

/**
 * Opens a file to save tags into, never creating one. A save or a Finish of a file deleted
 * meanwhile would otherwise make an empty file at its path, and with it a ghost track, and never
 * say the file is gone. So the file is proven there by a read-only open first, and one that is
 * certainly gone is MISSING: an interrupted save of it keeps its record and backup.
 *
 * A file deleted between the probe and the write open is recreated empty by the write open; it is
 * then closed unwritten and reported MISSING too. [refused] maps a SecurityException to the
 * platform's consent or Denied. [path], where the file is, goes with the opened file into the journal.
 */
internal fun <C> openExistingForWrite(
    access: MediaFileAccess,
    freeBytes: Long?,
    path: String? = null,
    refused: (SecurityException) -> WriteOpen<C>,
): WriteOpen<C> {
    return try {
        val size = try {
            access.probe()
        } catch (failure: FileNotFoundException) {
            return if (access.isGone()) WriteOpen.Missing else WriteOpen.Failed
        }
        val opened = access.openReadWrite()
        // Missing or emptied meanwhile: the write open may have just made this empty file.
        if (size > 0 && opened.size == 0L) {
            try {
                opened.file.close()
            } catch (_: Exception) {
                // Nothing was written through it.
            }
            WriteOpen.Missing
        } else {
            WriteOpen.Opened(opened.file, freeBytes, path)
        }
    } catch (failure: FileNotFoundException) {
        when {
            failure.isReadOnly() -> WriteOpen.ReadOnly
            // The row may have gone with the file; the path it named a moment ago still says where.
            access.isGone() -> WriteOpen.Missing
            else -> WriteOpen.Failed
        }
    } catch (failure: SecurityException) {
        refused(failure)
    }
}

/**
 * A request's keys as a count, then each key's length and UTF-16 code units as they are, so a key
 * survives exactly, lone surrogates included.
 */
internal fun writeTagWriteKeys(stream: OutputStream, keys: List<String>) {
    val out = DataOutputStream(BufferedOutputStream(stream))
    out.writeInt(keys.size)
    for (key in keys) {
        out.writeInt(key.length)
        out.writeChars(key)
    }
    out.flush()
}

/** The keys [writeTagWriteKeys] wrote to a file of [size] bytes; throws when they are damaged. */
internal fun readTagWriteKeys(stream: InputStream, size: Long): List<String> {
    val input = DataInputStream(BufferedInputStream(stream))
    val count = input.readInt()
    // Bounded by the file, so a damaged count cannot ask for more memory than the file could fill.
    check(count >= 0 && count <= size / Int.SIZE_BYTES)
    val keys = List(count) {
        val length = input.readInt()
        check(length >= 0 && length <= size / Char.SIZE_BYTES)
        String(CharArray(length) { input.readChar() })
    }
    check(input.read() == -1)
    return keys
}

/** A rename replaces atomically, so a process killed mid-write never leaves a torn file under [target]'s name. */
private fun replace(partial: File, target: File) {
    if (!partial.renameTo(target)) {
        partial.delete()
        throw IOException("cannot replace $target")
    }
}

/** A read-only volume refuses the open outright, and its message is the only place that says so. */
private fun FileNotFoundException.isReadOnly(): Boolean =
    message?.let { "EROFS" in it || "Read-only file system" in it } == true

/** fsync of a directory, so the entries created and deleted in it survive a power cut (API 21+). */
private fun syncDirectory(directory: File) {
    val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

/**
 * DATA is deprecated and still the only way to name a file to the scanner — and the only way to
 * find the `.lrc` beside it.
 */
@Suppress("DEPRECATION")
internal fun filePathOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

private const val SCAN_TIMEOUT_MS = 10_000L
