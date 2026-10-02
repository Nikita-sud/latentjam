/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.nikitasud.latentjam.library.IosPrivateFiles
import io.github.nikitasud.latentjam.library.IosTagFiles
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.RecoveryDirectory
import io.github.nikitasud.latentjam.library.tags.write.TagRecovery
import io.github.nikitasud.latentjam.library.tags.write.TargetFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * iOS writes only files it owns, imported into Documents: no consent, and a rename replaces a file
 * atomically. Music-library items are read-only to every app. The recovery store is
 * `Application Support/tag-write`; each request's keys and replacement cover live beside it in
 * `tag-write-requests`, never inside the store, whose sweep deletes every name it does not know.
 */
private class IosTagWriteBackend(
    store: RecoveryDirectory,
    private val keyFiles: IosPrivateFiles,
    private val coverFiles: IosPrivateFiles,
) : TagWriteBackend<Nothing> {
    override val strategy = TagWriteStrategy.NO_CONSENT
    private val replacer = IosTagFiles.replacer()
    override val writer = DurableWriter(store, { NSUUID().UUIDString }, replacer)
    override val recovery = TagRecovery(store, replacer)

    override fun hasWritePermission() = true

    override suspend fun batchConsent(keys: List<String>): Nothing = error("iOS asks for no consent")

    override suspend fun open(key: String): WriteOpen<Nothing> {
        if (IosTagFiles.isMusicLibraryTrack(key)) return WriteOpen.ReadOnly
        var opened: TargetFile? = null
        return try {
            withContext(Dispatchers.IO) {
                // Every save and every recovery opens its file first, so the store is ready before either.
                IosTagFiles.prepareStore()
                val file = IosTagFiles.open(key)
                opened = file
                when {
                    file != null -> WriteOpen.Opened(file, IosTagFiles.freeBytes())
                    // Missing only when the file is certainly gone; any doubt is a failure.
                    IosTagFiles.isGone(key) -> WriteOpen.Missing
                    else -> WriteOpen.Failed
                }
            }
        } catch (cancelled: CancellationException) {
            // A result dropped on the way back from the IO dispatcher must not leave its descriptor open.
            opened?.close()
            throw cancelled
        } catch (_: Exception) {
            opened?.close()
            WriteOpen.Failed
        }
    }

    // The library's next scan re-reads a file whose size or date changed; there is no system index to tell.
    override suspend fun rescan(keys: List<String>) = Unit

    override fun stash(name: String, bytes: ByteArray) = coverFiles.write(name, bytes)

    override fun unstash(name: String): ByteArray? = coverFiles.read(name)

    override fun drop(name: String) = coverFiles.delete(name)

    /** Leftover partial files too: no request names them, so the restore's prune deletes them. */
    override fun stashNames(): List<String> = coverFiles.names()

    override fun saveKeys(id: Long, keys: List<String>) = keyFiles.write("$id$KEYS", encodeTagWriteKeys(keys))

    override fun loadKeys(id: Long): List<String>? = keyFiles.read("$id$KEYS")?.let(::decodeTagWriteKeys)

    override fun dropKeys(id: Long) = keyFiles.delete("$id$KEYS")

    /** Ids with a partial file count too, so the restore's prune deletes those as well. */
    override fun savedKeyIds(): List<Long> = keyFiles.names()
        .mapNotNull { KEY_FILE.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        .distinct()

    private companion object {
        const val KEYS = ".keys"
        val KEY_FILE = Regex("""(\d+)\.keys(?:\.partial)?""")
    }
}

/**
 * The one live coordinator over the store, for the life of the process. A sheet leaving
 * composition cannot cancel a save or lose its report, and a second coordinator would delete this
 * one's key and cover files when it starts. Its checkpoint file lets a batch that iOS suspended
 * or ended resume at the next launch, where it finishes the interrupted file before going on.
 */
private object IosTagWrites {
    val coordinator: TagWriteCoordinator<Nothing>? by lazy {
        val store = IosTagFiles.store() ?: return@lazy null
        val keys = IosTagFiles.requestFiles("keys") ?: return@lazy null
        val covers = IosTagFiles.requestFiles("covers") ?: return@lazy null
        val state = IosTagFiles.requestFiles("state") ?: return@lazy null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Eagerly but off the main thread. A save that comes first prepares the store itself.
        scope.launch(Dispatchers.IO) {
            try {
                IosTagFiles.prepareStore()
            } catch (_: Exception) {
                // Retried by the first save, which fails if it fails again.
            }
        }
        TagWriteCoordinator(
            IosTagWriteBackend(store, keys, covers),
            scope,
            Dispatchers.IO,
            restored = readCheckpoint(state),
            save = { saved -> writeCheckpoint(state, saved) },
        ).also { keepRunningInBackground(it, scope) }
    }
}

private const val CHECKPOINT = "checkpoint"

/** A checkpoint that cannot be read restores nothing: the journals still protect every interrupted file. */
private fun readCheckpoint(files: IosPrivateFiles): List<String>? = try {
    files.read(CHECKPOINT)?.let(::decodeTagWriteKeys)
} catch (_: Exception) {
    null
}

/**
 * Best effort: a checkpoint that cannot be written (a full disk) costs only a resume at the next
 * launch, since the journals still protect every file, while a throw here would end the app
 * mid-batch. A failed write also drops the old checkpoint, which must not restore a batch that has
 * moved on.
 */
private fun writeCheckpoint(files: IosPrivateFiles, saved: List<String>) {
    try {
        if (saved.isEmpty()) files.delete(CHECKPOINT) else files.write(CHECKPOINT, encodeTagWriteKeys(saved))
    } catch (_: Exception) {
        try {
            files.delete(CHECKPOINT)
        } catch (_: Exception) {
            // Nothing more can be done; the next checkpoint tries again.
        }
    }
}

/**
 * Keeps the app running while a save is queued or in flight, so leaving the app mid-batch does not
 * suspend it between files. When iOS runs out of background time the task just ends. The journal
 * protects the file being written, and the checkpoint resumes the rest at the next launch.
 */
@OptIn(ExperimentalForeignApi::class)
private fun keepRunningInBackground(coordinator: TagWriteCoordinator<Nothing>, scope: CoroutineScope) {
    var task = UIBackgroundTaskInvalid
    fun end() {
        if (task != UIBackgroundTaskInvalid) {
            UIApplication.sharedApplication.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
    }
    scope.launch {
        coordinator.active.collect { active ->
            if (active && task == UIBackgroundTaskInvalid) {
                task = UIApplication.sharedApplication.beginBackgroundTaskWithName("tag-save") { end() }
            } else if (!active) {
                end()
            }
        }
    }
}

@Composable
internal actual fun rememberTagWriteAccess(): TagWriteAccess? {
    val coordinator = IosTagWrites.coordinator ?: return null
    return remember(coordinator) {
        // Imported tracks are written by their path under Documents; a Music-library item has no file to write.
        TagWriteAccess(coordinator, readOnlyIsMusicLibrary = true) { track ->
            track.id.value.takeUnless(IosTagFiles::isMusicLibraryTrack)
        }
    }
}

/** Hands out finished saves and finishes interrupted ones at once: imported files need no consent (spec §5.4). */
@Composable
actual fun TagWriteHost() {
    val coordinator = IosTagWrites.coordinator ?: return
    val scope = rememberCoroutineScope()
    LaunchedEffect(coordinator) { coordinator.refreshRecovery() }
    val completed by coordinator.completed.collectAsState()
    LaunchedEffect(completed) {
        val request = completed
        if (request != null) {
            // Delivered synchronously so recomposition cannot hand the same report out twice.
            coordinator.deliver(request.id)
            // Outside this effect: delivering clears `completed`, which restarts it and would cancel the refresh.
            scope.launch { coordinator.refreshRecovery() }
        }
    }
    // No TagRecoveryPrompt here: there is nothing to ask the user.
    val pending by coordinator.pendingRecovery.collectAsState()
    LaunchedEffect(pending) {
        if (pending.isNotEmpty()) coordinator.enqueueRecovery()
    }
}
