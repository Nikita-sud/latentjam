/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.JournalRecord
import io.github.nikitasud.latentjam.library.tags.write.TagRecovery
import io.github.nikitasud.latentjam.library.tags.write.TargetFile
import io.github.nikitasud.latentjam.library.tags.write.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.TimeSource

internal enum class TagWriteStrategy { SYSTEM_WRITE_REQUEST, RECOVERABLE_CONSENT, WRITE_PERMISSION, NO_CONSENT }
internal enum class TagWriteKind { EDIT, RECOVER }
internal enum class TagWriteStage { READY, WRITING, OFFER_PERMISSION, OFFER_FILE, OFFER_BATCH, WAIT_PERMISSION, WAIT_FILE, WAIT_BATCH, COMPLETE }
internal enum class WriteAnswer { APPROVED, CANCELLED, FAILED }

internal sealed interface WriteOpen<out C> {
    class Opened(val file: TargetFile, val freeBytes: Long?) : WriteOpen<Nothing>

    /**
     * The file is not there. It may be deleted, or only on a volume that is not mounted, so an
     * interrupted save of it is never given up automatically: its record and backup stay.
     */
    data object Missing : WriteOpen<Nothing>
    data object Denied : WriteOpen<Nothing>
    data object ReadOnly : WriteOpen<Nothing>
    data object Failed : WriteOpen<Nothing>
    data class NeedsConsent<C>(val consent: C) : WriteOpen<C>
}

/** What a platform supplies: consent, file access, the durable writer over its store, and the media index. */
internal interface TagWriteBackend<C> {
    val strategy: TagWriteStrategy
    val writer: DurableWriter
    val recovery: TagRecovery
    fun hasWritePermission(): Boolean
    suspend fun batchConsent(keys: List<String>): C
    suspend fun open(key: String): WriteOpen<C>
    suspend fun rescan(keys: List<String>)

    /**
     * Keeps a replacement cover outside the saved state, which cannot hold image bytes. Like the key
     * files below, these do file I/O and are only ever called on the coordinator's io dispatcher.
     */
    fun stash(name: String, bytes: ByteArray)
    fun unstash(name: String): ByteArray?
    fun drop(name: String)
    fun stashNames(): List<String>

    /**
     * Keeps request [id]'s keys in app-private files, byte for byte (a key may hold any character).
     * The saved state must stay small: a Bundle holds 1 MB, and a request may name 10,000 files.
     */
    fun saveKeys(id: Long, keys: List<String>)
    fun loadKeys(id: Long): List<String>?
    fun dropKeys(id: Long)
    fun savedKeyIds(): List<Long>
}

internal data class TagWriteRequest(
    val id: Long,
    val kind: TagWriteKind,
    val keys: List<String>,
    val edits: TagEdits,
    val stage: TagWriteStage = TagWriteStage.READY,
    val consented: Boolean = false,
    val permissionRequested: Boolean = false,
    /** The files the current system write request covers (Android 11+). */
    val batch: List<String> = emptyList(),
    val results: List<FileWriteResult> = emptyList(),
    val stopRequested: Boolean = false,
    /**
     * Restored from a checkpoint taken mid-write. A file whose save landed before the process died,
     * but whose result did not, is now UNCHANGED and still needs the rescan.
     */
    val interrupted: Boolean = false,
    /**
     * Its keys (and cover) are in the backend's files. Until then no checkpoint names it and none of
     * its files is written: after a restart nothing could name them.
     */
    val persisted: Boolean = true,
    /** Computed once, when the keys are first named; a copy carries it. The checkpoint checks the key file against it. */
    val keysCrc: Long = tagWriteKeysCrc(keys),
) {
    val remaining: List<String>
        get() {
            val done = results.mapTo(HashSet()) { it.key }
            return keys.filter { it !in done }
        }
}

internal data class TagWritePrompt<C>(val requestId: Long, val consent: C? = null)
internal data class TagWriteProgress(val requestId: Long, val done: Int, val total: Int)

/**
 * Saves tag edits into many files with the consent each platform needs, three at a time, and
 * reports each file's fate exactly.
 *
 * Runs on the main thread, where every state change happens; file work (the writes, and the
 * backend's key and cover files) runs on [io]. Its owner outlives the Activity and checkpoints every
 * transition. A process that dies mid-request restores it, asks for consent again (a grant never
 * outlives its process) and finishes the file that was being written through [TagRecovery] before
 * saving it again.
 *
 * A restore reads the request's keys on [io], so the constructor never blocks the UI. Until it is
 * done nothing runs, answers and resumes that arrive meanwhile are replayed after it, and the saved
 * state is left as restored. It also deletes key and cover files no request names. That assumes one
 * live coordinator per store, which the platform owner keeps: a second one would delete the first
 * one's files.
 *
 * With [checkpoints] false (no platform uses it today) nothing could ever read a request's
 * key and cover files, so none are written: a request runs from memory at once.
 *
 * Request ids are random, never counted: an id is a key file's name and a listener's address, and
 * one reused by another coordinator or after a restart could hand a restored request someone
 * else's keys, or an editor someone else's report. The checkpoint also keeps a CRC of each
 * request's keys, and a key file that does not match drops the request instead of running it.
 *
 * It enforces [TagRecovery]'s preconditions. A file's interrupted save is recovered only by the
 * worker that is about to save that file, so never under a live save of it. The store is swept only
 * under [TagWriteStoreLock] with nothing written or recovered: once per request after all its files
 * are closed, and by [refreshRecovery] when no request is queued. A save's saved bytes exist before
 * its journal record does, and a sweep beside it would delete them as stale. An interrupted save is
 * never given up: a file that seems gone may only be on a volume that is not mounted.
 */
internal class TagWriteCoordinator<C>(
    private val backend: TagWriteBackend<C>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    restored: List<String>? = null,
    private val save: (List<String>) -> Unit = {},
    private val concurrency: Int = 3,
    private val newId: () -> Long = { Random.nextLong(1, Long.MAX_VALUE) },
    private val checkpoints: Boolean = true,
) {
    /** The restored checkpoint; its keys and covers are read on [io] before anything runs. */
    private val saved = parseTagWriteCheckpoint(restored)
    private var restoring = true
    private val restoredSignal = CompletableDeferred<Unit>()

    /** Calls that came in while restoring, replayed in order once it is done. */
    private val deferred = ArrayList<() -> Unit>()

    /** Orders the key and cover files: a restore's reads and prune, then saves, then deletes. */
    private val keyFiles = Mutex()

    private var requests: List<TagWriteRequest> = emptyList()
    private var worker: Job? = null
    private val listeners = HashMap<Long, (TagWriteReport) -> Unit>()
    private val encodedResults = EncodedResults()

    private val mutablePrompt = MutableStateFlow<TagWritePrompt<C>?>(null)
    val prompt = mutablePrompt.asStateFlow()
    private val mutableCompleted = MutableStateFlow<TagWriteRequest?>(null)
    val completed = mutableCompleted.asStateFlow()
    private val mutableProgress = MutableStateFlow<TagWriteProgress?>(null)
    val progress = mutableProgress.asStateFlow()
    private val mutablePending = MutableStateFlow<List<JournalRecord>>(emptyList())
    val pendingRecovery = mutablePending.asStateFlow()

    private val mutableUnclaimed = MutableStateFlow<List<TagWriteReport>>(emptyList())

    /**
     * Finished reports nobody was listening for: a recovery, an edit whose sheet was closed or
     * recreated, or an edit restored after process death. Kept until [acknowledge]; a report shown
     * nowhere would be a save that failed or succeeded in silence.
     */
    val unclaimed = mutableUnclaimed.asStateFlow()

    fun acknowledge(report: TagWriteReport) {
        mutableUnclaimed.value = mutableUnclaimed.value.filterNot { it.id == report.id }
    }

    /** True while any request is queued or running: an open journal record then may be a save in flight. */
    private val mutableActive = MutableStateFlow(saved.isNotEmpty())
    val active = mutableActive.asStateFlow()

    private val mutableCouldNotFinish = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Files whose interrupted save a recovery in this process tried and could not finish. A file
     * caught mid-replace can be forgotten only then: forgetting deletes the only copies it could be
     * repaired from, so "Finish" is tried first. Never saved; a new process tries again.
     */
    val couldNotFinish = mutableCouldNotFinish.asStateFlow()

    private val mutableMissingAtFinish = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Of [couldNotFinish], the files that were not there: Settings says so beside each. Their
     * records stay (see [WriteOpen.Missing]). Never saved; a new process tries again.
     */
    val missingAtFinish = mutableMissingAtFinish.asStateFlow()

    private var restoredWait: Long? = null

    init {
        // Undispatched, so the key-file lock is taken before any enqueue can queue a save behind it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { restore() }
    }

    /**
     * Queues [edits] for [keys]; the id to [listen] on, or null when no key is usable. A key already
     * queued by an earlier request (a recovery the user just accepted, say) waits its turn behind
     * it: one request runs at a time, so two saves of one file never overlap.
     */
    fun enqueue(keys: List<String>, edits: TagEdits): Long? {
        val distinct = keys.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return null
        val id = freshId()
        requests = requests + TagWriteRequest(id, TagWriteKind.EDIT, distinct, edits, persisted = !checkpoints)
        checkpoint()
        persist(id, distinct, edits.cover as? CoverEdit.Replace)
        return id
    }

    /** One request finishing every interrupted save in [pendingRecovery], with consent for exactly those files. */
    fun enqueueRecovery(): Long? {
        val queued = requests.flatMapTo(HashSet()) { it.keys }
        val targets = mutablePending.value.map { it.target }.distinct().filter { it !in queued }
        if (targets.isEmpty()) return null
        val id = freshId()
        requests = requests + TagWriteRequest(id, TagWriteKind.RECOVER, targets, TagEdits(), persisted = !checkpoints)
        checkpoint()
        persist(id, targets, cover = null)
        return id
    }

    /**
     * Reads the journal's open records into [pendingRecovery]. With no request queued it also sweeps
     * the store; a queued request's files may be written the moment it resumes, so then it does not.
     */
    suspend fun refreshRecovery() {
        // A restored request's interrupted file is its own to finish, not one to offer recovering.
        restoredSignal.await()
        val records = TagWriteStoreLock.withLock {
            val idle = requests.isEmpty()
            withContext(io) {
                if (idle) backend.recovery.sweep()
                try {
                    backend.recovery.pending()
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
        // A file some queued request is saving right now has an open record that is not an interruption.
        val busy = requests.flatMapTo(HashSet()) { it.keys }
        mutablePending.value = records.filter { it.target !in busy }
    }

    /** Stops between files: those written stay written, the rest untouched. */
    fun stop() {
        if (restoring) {
            deferred += ::stop
            return
        }
        val first = requests.firstOrNull() ?: return
        if (first.stage == TagWriteStage.COMPLETE || first.stopRequested) return
        update(first.copy(stopRequested = true))
        if (first.stage in OFFERING) {
            mutablePrompt.value = null
            finishRemaining(requests.first(), FileWriteStatus.STOPPED)
            resume()
        }
    }

    /**
     * Stops [id]'s request: between files when it is running, before its first file when it is
     * still queued behind another. An editor stops its own save, never the one in front of it.
     */
    fun stop(id: Long) {
        if (restoring) {
            deferred += { stop(id) }
            return
        }
        val index = requests.indexOfFirst { it.id == id }
        when {
            index < 0 -> return
            index == 0 -> stop()
            else -> {
                requests = requests.mapIndexed { i, request -> if (i == index) request.copy(stopRequested = true) else request }
                checkpoint()
            }
        }
    }

    /**
     * Gives up an interrupted save for good. Its record and saved bytes are deleted, and the file
     * stays exactly as it is now. Only for the user's explicit "Forget" in Settings, because it
     * deletes the only way back (see [TagRecovery.abandon]). Refused while any queued request names
     * the file: that request's save or recovery owns the record.
     */
    suspend fun forget(record: JournalRecord): Boolean {
        restoredSignal.await()
        val forgotten = TagWriteStoreLock.withLock {
            // Checked under the lock: a request enqueued meanwhile opens its files only under it too.
            if (requests.any { record.target in it.keys }) return@withLock false
            try {
                withContext(io) { backend.recovery.abandon(record) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
        refreshRecovery()
        return forgotten
    }

    /**
     * Whether [id] is still a request here: queued, running, or finished with its report unclaimed.
     * Answers only once the restore is done, since before that a restored id is not yet held. An
     * editor's saved id this says no to will never be reported: a checkpoint that could not be
     * restored, keys that could not be saved, or a report already taken by someone else.
     */
    suspend fun knows(id: Long): Boolean {
        restoredSignal.await()
        return requests.any { it.id == id } || mutableUnclaimed.value.any { it.id == id }
    }

    fun listen(id: Long, listener: (TagWriteReport) -> Unit) {
        listeners[id] = listener
    }

    fun unlisten(id: Long) {
        listeners.remove(id)
    }

    /** Hands a completed request's report to its listener, if one is still there, and forgets it. */
    fun deliver(id: Long) {
        val first = requests.firstOrNull() ?: return
        if (first.id != id || first.stage != TagWriteStage.COMPLETE) return
        val report = TagWriteReport(first.kind, first.results, first.id, first.edits.cover)
        requests = requests.drop(1)
        mutableCompleted.value = null
        checkpoint()
        // Once no checkpoint names it: a request the saved state still names must still find its files.
        val cover = first.edits.cover is CoverEdit.Replace
        if (checkpoints) scope.launch {
            keyFiles.withLock {
                withContext(io) {
                    if (cover) safely { backend.drop(coverName(id)) }
                    safely { backend.dropKeys(id) }
                }
            }
        }
        if (first.kind == TagWriteKind.RECOVER) {
            val tried = first.results.filter { it.status !in NOT_TRIED }
            mutableCouldNotFinish.value = mutableCouldNotFinish.value -
                tried.filter { it.status in FINISHED }.mapTo(HashSet()) { it.key } +
                tried.filter { it.status !in FINISHED }.map { it.key }
            mutableMissingAtFinish.value = mutableMissingAtFinish.value -
                tried.mapTo(HashSet()) { it.key } +
                tried.filter { it.status == FileWriteStatus.MISSING }.map { it.key }
        }
        val listener = listeners.remove(id)
        if (listener != null) listener(report) else mutableUnclaimed.value = mutableUnclaimed.value + report
        resume()
    }

    /** Activity results arrive before the host resumes; none after a recreation means the dialog is gone. */
    fun onHostResumed() {
        if (restoring) {
            deferred += ::onHostResumed
            return
        }
        val first = requests.firstOrNull() ?: return
        if (first.id != restoredWait) return
        restoredWait = null
        if (first.stage !in WAITING) return
        update(first.copy(
            stage = TagWriteStage.READY,
            consented = false,
            batch = emptyList(),
            permissionRequested = first.permissionRequested && first.stage != TagWriteStage.WAIT_PERMISSION,
        ))
        resume()
    }

    /** Called immediately before a prompt is launched, with no suspension between saving and launching. */
    fun promptLaunched(id: Long): Boolean {
        val first = requests.firstOrNull() ?: return false
        if (first.id != id || mutablePrompt.value?.requestId != id) return false
        val waiting = when (first.stage) {
            TagWriteStage.OFFER_PERMISSION -> TagWriteStage.WAIT_PERMISSION
            TagWriteStage.OFFER_FILE -> TagWriteStage.WAIT_FILE
            TagWriteStage.OFFER_BATCH -> TagWriteStage.WAIT_BATCH
            else -> return false
        }
        update(first.copy(stage = waiting, permissionRequested = first.permissionRequested || waiting == TagWriteStage.WAIT_PERMISSION))
        mutablePrompt.value = null
        return true
    }

    fun answer(answer: WriteAnswer) {
        if (restoring) {
            deferred += { answer(answer) }
            return
        }
        val first = requests.firstOrNull() ?: return
        if (first.stage !in WAITING) return
        restoredWait = null
        when {
            answer == WriteAnswer.FAILED -> finishRemaining(first, FileWriteStatus.FAILED)
            answer == WriteAnswer.CANCELLED && first.stage == TagWriteStage.WAIT_PERMISSION -> finishRemaining(first, FileWriteStatus.DENIED)
            answer == WriteAnswer.CANCELLED -> finishRemaining(first, FileWriteStatus.CANCELLED)
            first.stopRequested -> finishRemaining(first, FileWriteStatus.STOPPED)
            else -> update(first.copy(stage = TagWriteStage.READY, consented = true))
        }
        resume()
    }

    /**
     * Reads the restored requests' keys and covers, deletes the files no request names, and only then
     * lets anything run.
     *
     * A restore that fails (a checkpoint that cannot be written) restores nothing rather than
     * crashing the owner, which would crash again at every relaunch from the same saved state. The
     * journals still protect every interrupted file, and the recovery offer finds it. Whatever
     * happens, it ends: nothing is left waiting for it.
     */
    private suspend fun restore() {
        var loadedIds: Set<Long> = emptySet()
        try {
            keyFiles.withLock {
                val loaded = withContext(io) {
                    saved.mapNotNull { entry ->
                        val cover = if (entry.replacesCover) unstash(coverName(entry.id)) else null
                        entry.toRequest(loadKeys(entry.id), cover)
                    }
                }
                loadedIds = loaded.mapTo(HashSet()) { it.id }
                // Requests enqueued meanwhile go after the restored ones, and their files are kept.
                requests = loaded + requests
                val ids = requests.mapTo(HashSet()) { it.id }
                val covers = requests.filter { it.edits.cover is CoverEdit.Replace }.mapTo(HashSet()) { coverName(it.id) }
                withContext(io) {
                    for (id in storedKeyIds()) if (id !in ids) safely { backend.dropKeys(id) }
                    for (name in stashNames()) if (name !in covers) safely { backend.drop(name) }
                }
            }
            restoring = false
            val first = requests.firstOrNull()
            when {
                first == null || !first.persisted -> Unit
                first.stage in OFFERING -> update(first.copy(stage = TagWriteStage.READY))
                // Consent died with the process; the interrupted file has an open journal record.
                first.stage == TagWriteStage.WRITING -> update(
                    first.copy(stage = TagWriteStage.READY, consented = false, batch = emptyList(), interrupted = true),
                )
            }
            restoredWait = requests.firstOrNull()?.takeIf { it.stage in WAITING }?.id
            checkpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            requests = requests.filter { it.id !in loadedIds }
            restoredWait = null
            restoring = false
            safely { checkpoint() }
        } finally {
            restoring = false
            restoredSignal.complete(Unit)
        }
        val replay = deferred.toList()
        deferred.clear()
        // A replayed call whose checkpoint fails has still changed the state in memory; it must not end the owner.
        replay.forEach { call -> safely { call() } }
        resume()
    }

    /** A random id no request in memory or in the restored checkpoint holds. */
    private fun freshId(): Long {
        val taken = requests.mapTo(HashSet()) { it.id }.apply { saved.mapTo(this) { it.id } }
        while (true) {
            val id = newId()
            if (id > 0 && id !in taken) return id
        }
    }

    /**
     * Saves the keys (and cover) on [io] before any checkpoint names the request; then it may run.
     * Without checkpoints there is nothing to save, and it may run at once.
     */
    private fun persist(id: Long, keys: List<String>, cover: CoverEdit.Replace?) {
        if (!checkpoints) {
            resume()
            return
        }
        scope.launch {
            keyFiles.withLock {
                withContext(io) {
                    // If this fails the request still runs, but does not survive a restart.
                    safely { backend.saveKeys(id, keys) }
                    cover?.let { safely { backend.stash(coverName(id), it.bytes) } }
                }
            }
            val index = requests.indexOfFirst { it.id == id }
            if (index < 0) return@launch
            requests = requests.toMutableList().also { it[index] = it[index].copy(persisted = true) }
            checkpoint()
            resume()
        }
    }

    private fun checkpoint() {
        mutableActive.value = requests.isNotEmpty() || (restoring && saved.isNotEmpty())
        // While restoring, the saved state still holds the restored requests; writing now would drop them.
        if (restoring) return
        save(encodeTagWriteRequests(requests.filter { it.persisted }, encodedResults))
    }

    private fun storedKeyIds(): List<Long> = try {
        backend.savedKeyIds()
    } catch (_: Exception) {
        emptyList()
    }

    private fun stashNames(): List<String> = try {
        backend.stashNames()
    } catch (_: Exception) {
        emptyList()
    }

    private fun unstash(name: String): ByteArray? = try {
        backend.unstash(name)
    } catch (_: Exception) {
        null
    }

    private fun loadKeys(id: Long): List<String>? = try {
        backend.loadKeys(id)
    } catch (_: Exception) {
        null
    }

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            // Best effort: at worst a request does not survive a restart, or a file waits for the next prune.
        }
    }

    private fun update(request: TagWriteRequest) {
        requests = listOf(request) + requests.drop(1)
        checkpoint()
    }

    private fun finishRemaining(first: TagWriteRequest, status: FileWriteStatus) {
        update(first.copy(
            stage = TagWriteStage.READY,
            consented = false,
            batch = emptyList(),
            results = first.results + first.remaining.map { FileWriteResult(it, status) },
        ))
    }

    private fun offer(request: TagWriteRequest, stage: TagWriteStage, consent: C? = null) {
        update(request.copy(stage = stage))
        mutablePrompt.value = TagWritePrompt(request.id, consent)
    }

    /** In memory first, then checkpointed: a checkpoint that fails still leaves the result recorded. */
    private fun record(result: FileWriteResult) {
        val first = requests.first()
        if (first.results.any { it.key == result.key }) return
        val updated = first.copy(results = first.results + result)
        update(updated)
        mutableProgress.value = TagWriteProgress(updated.id, updated.results.size, updated.keys.size)
    }

    private fun resume() {
        if (restoring) return
        val first = requests.firstOrNull() ?: return
        if (first.stage == TagWriteStage.COMPLETE) {
            mutableCompleted.value = first
            return
        }
        if (!first.persisted || worker?.isActive == true || first.stage !in RUNNABLE) return
        val job = scope.launch { run() }
        worker = job
        job.invokeOnCompletion { failure ->
            if (worker === job) worker = null
            if (failure == null && requests.firstOrNull()?.stage in RUNNABLE) resume()
        }
    }

    private suspend fun run() {
        while (true) {
            val current = requests.firstOrNull() ?: return
            if (current.stage == TagWriteStage.COMPLETE) {
                mutableCompleted.value = current
                return
            }
            if (!current.persisted || current.stage !in RUNNABLE) return
            try {
                step(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A backend that throws must not leave the request stuck: what is left fails, and it
                // completes. The files written so far stay written.
                val now = requests.first()
                if (now.remaining.isEmpty()) {
                    update(now.copy(stage = TagWriteStage.COMPLETE))
                } else {
                    finishRemaining(now, FileWriteStatus.FAILED)
                }
            }
        }
    }

    private suspend fun step(current: TagWriteRequest) {
        val remaining = current.remaining
        when {
            remaining.isEmpty() -> complete(current)
            current.stopRequested -> finishRemaining(current, FileWriteStatus.STOPPED)
            else -> when (backend.strategy) {
                TagWriteStrategy.NO_CONSENT -> writeAll(current, remaining)
                TagWriteStrategy.WRITE_PERMISSION -> when {
                    backend.hasWritePermission() -> writeAll(current, remaining)
                    current.permissionRequested -> finishRemaining(current, FileWriteStatus.DENIED)
                    else -> offer(current, TagWriteStage.OFFER_PERMISSION)
                }
                TagWriteStrategy.SYSTEM_WRITE_REQUEST -> if (current.consented && current.batch.isNotEmpty()) {
                    writeAll(current, current.batch.filter { it in remaining })
                    update(requests.first().copy(consented = false, batch = emptyList()))
                } else {
                    // Android 16 caps one request at 2,000 items (spec §5.5).
                    val batch = remaining.take(CONSENT_LIMIT)
                    val consent = try {
                        backend.batchConsent(batch)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        finishRemaining(requests.first(), FileWriteStatus.FAILED)
                        return
                    }
                    // Re-read after the suspension: a stop() meanwhile must not be written over, nor prompted past.
                    val now = requests.first()
                    if (!now.stopRequested) offer(now.copy(batch = batch), TagWriteStage.OFFER_BATCH, consent)
                }
                TagWriteStrategy.RECOVERABLE_CONSENT -> writeWithFileConsent(current, remaining.first())
            }
        }
    }

    private suspend fun writeAll(current: TagWriteRequest, keys: List<String>) {
        update(current.copy(stage = TagWriteStage.WRITING))
        val started = TimeSource.Monotonic.markNow()
        val permits = Semaphore(concurrency)
        TagWriteStoreLock.withLock {
            // One file's failure must not cancel the others: their saves may have landed, and each is
            // reported as it is.
            supervisorScope {
                for (key in keys) {
                    permits.acquire()
                    if (requests.first().stopRequested) {
                        permits.release()
                        break
                    }
                    launch {
                        try {
                            val step = attempt(current, key)
                            record(if (step is Attempt.Done) step.result else FileWriteResult(key, FileWriteStatus.DENIED))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A result already recorded in memory stands; otherwise this file failed.
                            safely { record(FileWriteResult(key, FileWriteStatus.FAILED)) }
                        } finally {
                            permits.release()
                        }
                    }
                }
            }
        }
        val after = requests.first()
        // Consent dialogs are outside this span: it times the writing itself, for device benchmarks.
        println("TAGS: wrote ${keys.size} file(s) in ${started.elapsedNow().inWholeMilliseconds} ms")
        if (after.stage == TagWriteStage.WRITING) update(after.copy(stage = TagWriteStage.READY))
    }

    private suspend fun writeWithFileConsent(current: TagWriteRequest, key: String) {
        update(current.copy(stage = TagWriteStage.WRITING))
        val started = TimeSource.Monotonic.markNow()
        when (val step = TagWriteStoreLock.withLock { attempt(current, key) }) {
            is Attempt.Done -> {
                println("TAGS: wrote 1 file(s) in ${started.elapsedNow().inWholeMilliseconds} ms")
                record(step.result)
                update(requests.first().copy(stage = TagWriteStage.READY, consented = false))
            }
            // A stop() while the file was opened: no prompt, and the loop finishes the rest as stopped.
            is Attempt.Consent -> if (requests.first().stopRequested) {
                update(requests.first().copy(stage = TagWriteStage.READY, consented = false))
            } else if (!current.consented) {
                offer(requests.first(), TagWriteStage.OFFER_FILE, step.consent)
            } else {
                record(FileWriteResult(key, FileWriteStatus.DENIED))
                update(requests.first().copy(stage = TagWriteStage.READY, consented = false))
            }
        }
    }

    private suspend fun complete(current: TagWriteRequest) {
        // Every file of this request is closed and no other request runs: the one safe moment to
        // delete what finished or abandoned saves left in the store.
        TagWriteStoreLock.withLock { withContext(io) { backend.recovery.sweep() } }
        // A restored file changed too: the index may have read the interrupted save's tags.
        val changed = current.results
            .filter { it.status in CHANGED || (current.interrupted && it.status == FileWriteStatus.UNCHANGED) }
            .map { it.key }
        if (changed.isNotEmpty()) {
            try {
                backend.rescan(changed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The files are saved; the index catches up at its next scan.
            }
        }
        update(requests.first().copy(stage = TagWriteStage.COMPLETE, batch = emptyList()))
        mutableProgress.value = null
        mutableCompleted.value = requests.first()
    }

    private sealed interface Attempt<out C> {
        data class Done(val result: FileWriteResult) : Attempt<Nothing>
        data class Consent<C>(val consent: C) : Attempt<C>
    }

    private suspend fun attempt(request: TagWriteRequest, key: String): Attempt<C> {
        // A file that is missing is reported MISSING, and its record stays open (see the class notes).
        if (request.kind == TagWriteKind.RECOVER) return withOpened(key) { file, _ -> recoverKey(key, file) }
        val first = withOpened(key) { file, free -> saved(key, backend.writer.write(key, file, request.edits, free)) }
        if (first !is Attempt.Done || first.result.status != FileWriteStatus.RECOVERY_PENDING) return first
        // An earlier save of this file was interrupted: finish or undo it, then save again on a fresh handle
        // (after an atomic replace the old handle points at the replaced file).
        val recovered = withOpened(key) { file, _ -> recoverKey(key, file) }
        if (recovered !is Attempt.Done || recovered.result.status == FileWriteStatus.RECOVERY_PENDING) return first
        return withOpened(key) { file, free -> saved(key, backend.writer.write(key, file, request.edits, free)) }
    }

    private suspend fun withOpened(key: String, action: (TargetFile, Long?) -> FileWriteResult): Attempt<C> {
        val opened = try {
            backend.open(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WriteOpen.Failed
        }
        return when (opened) {
            is WriteOpen.Opened -> Attempt.Done(withContext(io) {
                try {
                    opened.file.use { action(it, opened.freeBytes) }
                } catch (_: Exception) {
                    FileWriteResult(key, FileWriteStatus.FAILED)
                }
            })
            is WriteOpen.NeedsConsent -> Attempt.Consent(opened.consent)
            WriteOpen.Missing -> Attempt.Done(FileWriteResult(key, FileWriteStatus.MISSING))
            WriteOpen.Denied -> Attempt.Done(FileWriteResult(key, FileWriteStatus.DENIED))
            WriteOpen.ReadOnly -> Attempt.Done(FileWriteResult(key, FileWriteStatus.READ_ONLY))
            WriteOpen.Failed -> Attempt.Done(FileWriteResult(key, FileWriteStatus.FAILED))
        }
    }

    private fun saved(key: String, result: WriteResult): FileWriteResult = when (result) {
        is WriteResult.Saved -> FileWriteResult(key, FileWriteStatus.SAVED, newLength = result.newLength)
        WriteResult.NoChange -> FileWriteResult(key, FileWriteStatus.UNCHANGED)
        is WriteResult.Refused -> FileWriteResult(key, FileWriteStatus.REFUSED, refusal = result.reason)
        WriteResult.NotEnoughSpace -> FileWriteResult(key, FileWriteStatus.NO_SPACE)
        is WriteResult.Failed -> FileWriteResult(key, FileWriteStatus.FAILED)
        is WriteResult.RecoveryPending -> FileWriteResult(key, FileWriteStatus.RECOVERY_PENDING)
    }

    /**
     * Finishes [key]'s interrupted saves, and only [key]'s: other files of the batch may be mid-save,
     * so nothing here sweeps (see the class notes).
     */
    private fun recoverKey(key: String, file: TargetFile): FileWriteResult {
        val records = backend.recovery.pending().filter { it.target == key }
        if (records.isEmpty()) return FileWriteResult(key, FileWriteStatus.UNCHANGED)
        val outcomes = records.map { backend.recovery.recover(it, file) }
        val status = when {
            TagRecovery.Outcome.STUCK in outcomes -> FileWriteStatus.RECOVERY_PENDING
            TagRecovery.Outcome.FOREIGN in outcomes -> FileWriteStatus.FOREIGN
            TagRecovery.Outcome.COMPLETED in outcomes -> FileWriteStatus.RECOVERED
            else -> FileWriteStatus.RESTORED
        }
        return FileWriteResult(key, status)
    }

    private companion object {
        const val CONSENT_LIMIT = 2_000
        val WAITING = setOf(TagWriteStage.WAIT_PERMISSION, TagWriteStage.WAIT_FILE, TagWriteStage.WAIT_BATCH)
        val OFFERING = setOf(TagWriteStage.OFFER_PERMISSION, TagWriteStage.OFFER_FILE, TagWriteStage.OFFER_BATCH)
        val RUNNABLE = setOf(TagWriteStage.READY, TagWriteStage.WRITING)
        val CHANGED = setOf(FileWriteStatus.SAVED, FileWriteStatus.RECOVERED, FileWriteStatus.RESTORED)

        /** A recovery that ended so never tried the file: the user declined or stopped it. */
        val NOT_TRIED = setOf(FileWriteStatus.CANCELLED, FileWriteStatus.DENIED, FileWriteStatus.STOPPED)

        /** A recovery that ended so left no interrupted save behind. */
        val FINISHED = setOf(
            FileWriteStatus.RECOVERED, FileWriteStatus.RESTORED, FileWriteStatus.SAVED, FileWriteStatus.UNCHANGED,
        )
    }
}

internal fun coverName(id: Long): String = "cover-$id"

/**
 * One lock for every coordinator in the process, held while files are written or recovered and by
 * every sweep. Per instance it would not do: a coordinator left over from a recreated owner may
 * still be writing when a new one sweeps the same store.
 */
internal val TagWriteStoreLock = Mutex()

/**
 * Each request's results as the one checkpoint string that holds them, appended to as files finish
 * rather than re-encoded, so a 10,000-file batch does not redo its whole report per file.
 */
internal class EncodedResults {
    private class Entry(val keys: List<String>) {
        val index = HashMap<String, Int>(keys.size * 2).also { map -> keys.forEachIndexed { i, key -> map[key] = i } }
        val text = StringBuilder()
        var count = 0
        var last: FileWriteResult? = null
    }

    private val entries = HashMap<Long, Entry>()

    fun retain(ids: Set<Long>) {
        entries.keys.retainAll(ids)
    }

    fun of(request: TagWriteRequest): String {
        val results = request.results
        var entry = entries[request.id]
        // Results only ever grow at the end; anything else is encoded afresh.
        if (entry == null || entry.keys !== request.keys || entry.count > results.size ||
            (entry.count > 0 && results[entry.count - 1] !== entry.last)
        ) {
            entry = Entry(request.keys).also { entries[request.id] = it }
        }
        for (i in entry.count until results.size) {
            if (entry.text.isNotEmpty()) entry.text.append(',')
            encodeResult(entry.text, entry.index.getValue(results[i].key), results[i])
        }
        entry.count = results.size
        entry.last = results.lastOrNull()
        return entry.text.toString()
    }
}

/** `<index><status code>[<new length>][!<refusal>]`, e.g. `12S17436` or `3R!TRUNCATED`. */
private fun encodeResult(into: StringBuilder, index: Int, result: FileWriteResult) {
    into.append(index).append(result.status.code)
    result.newLength?.let { into.append(it) }
    result.refusal?.let { into.append('!').append(it.name) }
}

private fun decodeResult(entry: String, keys: List<String>): FileWriteResult {
    val codeAt = entry.indexOfFirst { !it.isDigit() }
    check(codeAt > 0) { "no index in $entry" }
    val status = STATUS_BY_CODE.getValue(entry[codeAt])
    val rest = entry.substring(codeAt + 1)
    val bang = rest.indexOf('!')
    val length = if (bang < 0) rest else rest.substring(0, bang)
    return FileWriteResult(
        key = keys[entry.substring(0, codeAt).toInt()],
        status = status,
        refusal = if (bang < 0) null else TagRefusal.valueOf(rest.substring(bang + 1)),
        newLength = length.takeIf { it.isNotEmpty() }?.toLong(),
    )
}

/** One letter per status, fixed here rather than by declaration order, so a reorder cannot misread a checkpoint. */
private val FileWriteStatus.code: Char
    get() = when (this) {
        FileWriteStatus.SAVED -> 'S'
        FileWriteStatus.UNCHANGED -> 'U'
        FileWriteStatus.REFUSED -> 'R'
        FileWriteStatus.NO_SPACE -> 'N'
        FileWriteStatus.FAILED -> 'F'
        FileWriteStatus.RECOVERY_PENDING -> 'P'
        FileWriteStatus.DENIED -> 'D'
        FileWriteStatus.CANCELLED -> 'C'
        FileWriteStatus.MISSING -> 'M'
        FileWriteStatus.READ_ONLY -> 'O'
        FileWriteStatus.STOPPED -> 'T'
        FileWriteStatus.RECOVERED -> 'V'
        FileWriteStatus.RESTORED -> 'E'
        FileWriteStatus.FOREIGN -> 'G'
    }

private val STATUS_BY_CODE: Map<Char, FileWriteStatus> = FileWriteStatus.entries.associateBy { it.code }

/**
 * String-only saved state (SavedStateHandle holds no Context, IntentSender or image), kept small
 * for a Bundle:
 * - the keys live in the backend's files ([TagWriteBackend.saveKeys]);
 * - results are one string per request that names each key by its index;
 * - the batch is stored as its size, since it is always the first files still to do;
 * - a replaced cover is stashed under [coverName], and only its mime is written here;
 * - [TagEdits.originalFollowsYear] is "1"/"0" after the cover (version 5 on).
 */
internal fun encodeTagWriteRequests(
    requests: List<TagWriteRequest>,
    results: EncodedResults = EncodedResults(),
): List<String> = buildList {
    results.retain(requests.mapTo(HashSet()) { it.id })
    add(CHECKPOINT_VERSION)
    add(requests.size.toString())
    for (r in requests) {
        addAll(listOf(r.id.toString(), r.kind.name, r.stage.name, r.consented.toString(),
            r.permissionRequested.toString(), r.stopRequested.toString(), r.interrupted.toString()))
        addAll(listOf(r.edits.title, r.edits.artist, r.edits.album, r.edits.genre, r.edits.year, r.edits.albumArtist,
            r.edits.trackNumber, r.edits.trackTotal, r.edits.discNumber, r.edits.discTotal, r.edits.lyrics)
            .map { if (it == null) "0" else "1$it" })
        add(when (val cover = r.edits.cover) {
            CoverEdit.Keep -> "k"
            CoverEdit.Remove -> "r"
            is CoverEdit.Replace -> "c${cover.mime}"
        })
        add(if (r.edits.originalFollowsYear) "1" else "0")
        add(r.keys.size.toString())
        add(r.keysCrc.toString())
        add(r.batch.size.toString())
        add(results.of(r))
    }
}

/** One request as the checkpoint holds it: everything but its keys and cover bytes, which are files. */
internal class SavedTagWrite(
    val id: Long,
    private val kind: TagWriteKind,
    private val stage: TagWriteStage,
    private val consented: Boolean,
    private val permissionRequested: Boolean,
    private val stopRequested: Boolean,
    private val interrupted: Boolean,
    private val fields: List<String?>,
    private val coverCode: String,
    private val originalFollowsYear: Boolean,
    private val keyCount: Int,
    private val keysCrc: Long,
    private val batchSize: Int,
    private val results: String,
) {
    val replacesCover: Boolean get() = coverCode.startsWith("c")

    /**
     * The request, or null when its keys were lost or are not the ones saved: its files cannot be
     * named, and running it against another request's files would save the edit into the wrong ones.
     */
    fun toRequest(keys: List<String>?, coverBytes: ByteArray?): TagWriteRequest? {
        if (keys == null || keys.size != keyCount || tagWriteKeysCrc(keys) != keysCrc) return null
        return try {
            val results = if (results.isEmpty()) emptyList() else results.split(',').map { decodeResult(it, keys) }
            check(results.mapTo(HashSet()) { it.key }.size == results.size)
            val cover = when {
                coverCode == "k" -> CoverEdit.Keep
                coverCode == "r" -> CoverEdit.Remove
                coverBytes != null -> CoverEdit.Replace(coverBytes, coverCode.removePrefix("c"))
                else -> null
            }
            val edits = TagEdits(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6],
                fields[7], fields[8], fields[9], fields[10], cover ?: CoverEdit.Keep, originalFollowsYear)
            val request = TagWriteRequest(id, kind, keys, edits, stage, consented, permissionRequested,
                batch = emptyList(), results = results, stopRequested = stopRequested, interrupted = interrupted, keysCrc = keysCrc)
                .let { it.copy(batch = it.remaining.take(batchSize)) }
            // A cover whose bytes were lost must not be saved as "keep": finish the rest as failed.
            if (cover == null) {
                request.copy(stage = TagWriteStage.READY, batch = emptyList(),
                    results = results + request.remaining.map { FileWriteResult(it, FileWriteStatus.FAILED) })
            } else request
        } catch (_: Exception) {
            null
        }
    }
}

/** Parses without touching a file, so a coordinator can read it in its constructor; empty when malformed. */
internal fun parseTagWriteCheckpoint(saved: List<String>?): List<SavedTagWrite> {
    if (saved == null) return emptyList()
    return try {
        val values = saved.iterator()
        val version = values.next()
        check(version == CHECKPOINT_VERSION || version == CHECKPOINT_VERSION_WITHOUT_ORIGINALS)
        List(values.next().toInt()) {
            SavedTagWrite(
                id = values.next().toLong(),
                kind = TagWriteKind.valueOf(values.next()),
                stage = TagWriteStage.valueOf(values.next()),
                consented = values.next().toBooleanStrict(),
                permissionRequested = values.next().toBooleanStrict(),
                stopRequested = values.next().toBooleanStrict(),
                interrupted = values.next().toBooleanStrict(),
                fields = List(11) { values.next().let { v -> if (v == "0") null else v.removePrefix("1") } },
                coverCode = values.next(),
                // Unknown before version 5: a restored request moves no original date rather than
                // risk overwriting real ones (a bulk year over files whose years differed).
                originalFollowsYear = if (version == CHECKPOINT_VERSION) values.next().toBooleanFlag() else false,
                keyCount = values.next().toInt(),
                keysCrc = values.next().toLong(),
                batchSize = values.next().toInt(),
                results = values.next(),
            )
        }.also { check(!values.hasNext()) }
    } catch (_: Exception) {
        emptyList()
    }
}

/** The whole restore in one go, for tests and tools: [loadKeys] and [unstash] do file I/O. */
internal fun decodeTagWriteRequests(
    saved: List<String>?,
    unstash: (String) -> ByteArray?,
    loadKeys: (Long) -> List<String>?,
): List<TagWriteRequest> = parseTagWriteCheckpoint(saved).mapNotNull {
    it.toRequest(loadKeys(it.id), if (it.replacesCover) unstash(coverName(it.id)) else null)
}

private const val CHECKPOINT_VERSION = "5"

/** The checkpoint before [TagEdits.originalFollowsYear] was saved; still read. */
private const val CHECKPOINT_VERSION_WITHOUT_ORIGINALS = "4"

private fun String.toBooleanFlag(): Boolean = when (this) {
    "1" -> true
    "0" -> false
    else -> throw IllegalArgumentException("not a flag: $this")
}
