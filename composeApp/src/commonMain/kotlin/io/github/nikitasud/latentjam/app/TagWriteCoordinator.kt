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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class TagWriteStrategy { SYSTEM_WRITE_REQUEST, RECOVERABLE_CONSENT, WRITE_PERMISSION, NO_CONSENT }
internal enum class TagWriteKind { EDIT, RECOVER }
internal enum class TagWriteStage { READY, WRITING, OFFER_PERMISSION, OFFER_FILE, OFFER_BATCH, WAIT_PERMISSION, WAIT_FILE, WAIT_BATCH, COMPLETE }
internal enum class WriteAnswer { APPROVED, CANCELLED, FAILED }

internal sealed interface WriteOpen<out C> {
    class Opened(val file: TargetFile, val freeBytes: Long?) : WriteOpen<Nothing>
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

    /** Keeps a replacement cover outside the saved state, which cannot hold image bytes. */
    fun stash(name: String, bytes: ByteArray)
    fun unstash(name: String): ByteArray?
    fun drop(name: String)
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
 * Runs on the main thread, where every state change happens; the writes themselves run on [io].
 * Its owner outlives the Activity and checkpoints every transition. A process that dies mid-request
 * restores it, asks for consent again (a grant never outlives its process) and finishes the file
 * that was being written through [TagRecovery] before saving it again.
 *
 * It enforces [TagRecovery]'s preconditions. A file's interrupted save is recovered only by the
 * worker that is about to save that file, so never under a live save of it. The store is swept only
 * under [store] with nothing written or recovered: once per request after all its files are closed,
 * and by [refreshRecovery] when no request is queued. A save's saved bytes exist before its journal
 * record does, and a sweep beside it would delete them as stale.
 */
internal class TagWriteCoordinator<C>(
    private val backend: TagWriteBackend<C>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    restored: List<String>? = null,
    private val save: (List<String>) -> Unit = {},
    private val concurrency: Int = 3,
) {
    private var requests: List<TagWriteRequest> = decodeTagWriteRequests(restored, backend::unstash)
    private var nextId = (requests.maxOfOrNull { it.id } ?: 0L) + 1L
    private var worker: Job? = null
    private val listeners = HashMap<Long, (TagWriteReport) -> Unit>()

    /** Held by the worker while it writes or recovers files, and by every sweep. */
    private val store = Mutex()

    private val mutablePrompt = MutableStateFlow<TagWritePrompt<C>?>(null)
    val prompt = mutablePrompt.asStateFlow()
    private val mutableCompleted = MutableStateFlow<TagWriteRequest?>(null)
    val completed = mutableCompleted.asStateFlow()
    private val mutableProgress = MutableStateFlow<TagWriteProgress?>(null)
    val progress = mutableProgress.asStateFlow()
    private val mutablePending = MutableStateFlow<List<JournalRecord>>(emptyList())
    val pendingRecovery = mutablePending.asStateFlow()

    /** True while any request is queued or running: an open journal record then may be a save in flight. */
    private val mutableActive = MutableStateFlow(requests.isNotEmpty())
    val active = mutableActive.asStateFlow()

    private var restoredWait = requests.firstOrNull()?.takeIf { it.stage in WAITING }?.id

    init {
        val first = requests.firstOrNull()
        when {
            first == null -> Unit
            first.stage in OFFERING -> update(first.copy(stage = TagWriteStage.READY))
            // Consent died with the process; the interrupted file has an open journal record.
            first.stage == TagWriteStage.WRITING ->
                update(first.copy(stage = TagWriteStage.READY, consented = false, batch = emptyList()))
        }
        resume()
    }

    /** Queues [edits] for [keys]; the id to [listen] on, or null when every key is already queued. */
    fun enqueue(keys: List<String>, edits: TagEdits): Long? {
        val queued = requests.flatMapTo(HashSet()) { it.keys }
        val distinct = keys.filter { it.isNotBlank() && it !in queued }.distinct()
        if (distinct.isEmpty()) return null
        val id = nextId++
        (edits.cover as? CoverEdit.Replace)?.let { backend.stash(coverName(id), it.bytes) }
        requests = requests + TagWriteRequest(id, TagWriteKind.EDIT, distinct, edits)
        checkpoint()
        resume()
        return id
    }

    /** One request finishing every interrupted save in [pendingRecovery], with consent for exactly those files. */
    fun enqueueRecovery(): Long? {
        val queued = requests.flatMapTo(HashSet()) { it.keys }
        val targets = mutablePending.value.map { it.target }.distinct().filter { it !in queued }
        if (targets.isEmpty()) return null
        val id = nextId++
        requests = requests + TagWriteRequest(id, TagWriteKind.RECOVER, targets, TagEdits())
        checkpoint()
        resume()
        return id
    }

    /**
     * Reads the journal's open records into [pendingRecovery]. With no request queued it also sweeps
     * the store; a queued request's files may be written the moment it resumes, so then it does not.
     */
    suspend fun refreshRecovery() {
        val records = store.withLock {
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
        val first = requests.firstOrNull() ?: return
        if (first.stage == TagWriteStage.COMPLETE || first.stopRequested) return
        update(first.copy(stopRequested = true))
        if (first.stage in OFFERING) {
            mutablePrompt.value = null
            finishRemaining(requests.first(), FileWriteStatus.STOPPED)
            resume()
        }
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
        val report = TagWriteReport(first.kind, first.results)
        if (first.edits.cover is CoverEdit.Replace) backend.drop(coverName(id))
        requests = requests.drop(1)
        mutableCompleted.value = null
        checkpoint()
        listeners.remove(id)?.invoke(report)
        resume()
    }

    /** Activity results arrive before the host resumes; none after a recreation means the dialog is gone. */
    fun onHostResumed() {
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

    private fun checkpoint() {
        mutableActive.value = requests.isNotEmpty()
        save(encodeTagWriteRequests(requests))
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

    private fun record(result: FileWriteResult) {
        val first = requests.first()
        if (first.results.any { it.key == result.key }) return
        val updated = first.copy(results = first.results + result)
        update(updated)
        mutableProgress.value = TagWriteProgress(updated.id, updated.results.size, updated.keys.size)
    }

    private fun resume() {
        val first = requests.firstOrNull() ?: return
        if (first.stage == TagWriteStage.COMPLETE) {
            mutableCompleted.value = first
            return
        }
        if (worker?.isActive == true || first.stage !in RUNNABLE) return
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
            if (current.stage !in RUNNABLE) return
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
                        try {
                            offer(current.copy(batch = batch), TagWriteStage.OFFER_BATCH, backend.batchConsent(batch))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            finishRemaining(current, FileWriteStatus.FAILED)
                        }
                    }
                    TagWriteStrategy.RECOVERABLE_CONSENT -> writeWithFileConsent(current, remaining.first())
                }
            }
        }
    }

    private suspend fun writeAll(current: TagWriteRequest, keys: List<String>) {
        update(current.copy(stage = TagWriteStage.WRITING))
        val permits = Semaphore(concurrency)
        store.withLock {
            coroutineScope {
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
                        } finally {
                            permits.release()
                        }
                    }
                }
            }
        }
        val after = requests.first()
        if (after.stage == TagWriteStage.WRITING) update(after.copy(stage = TagWriteStage.READY))
    }

    private suspend fun writeWithFileConsent(current: TagWriteRequest, key: String) {
        update(current.copy(stage = TagWriteStage.WRITING))
        when (val step = store.withLock { attempt(current, key) }) {
            is Attempt.Done -> {
                record(step.result)
                update(requests.first().copy(stage = TagWriteStage.READY, consented = false))
            }
            is Attempt.Consent -> if (!current.consented) {
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
        store.withLock { withContext(io) { backend.recovery.sweep() } }
        // A restored file changed too: the index may have read the interrupted save's tags.
        val changed = current.results.filter { it.status in CHANGED }.map { it.key }
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
    }
}

internal fun coverName(id: Long): String = "cover-$id"

/**
 * String-only saved state (SavedStateHandle holds no Context, IntentSender or image). A replaced
 * cover is stashed by the backend under [coverName] and only its mime is written here.
 */
internal fun encodeTagWriteRequests(requests: List<TagWriteRequest>): List<String> = buildList {
    add("1")
    add(requests.size.toString())
    for (r in requests) {
        addAll(listOf(r.id.toString(), r.kind.name, r.stage.name, r.consented.toString(),
            r.permissionRequested.toString(), r.stopRequested.toString()))
        addAll(listOf(r.edits.title, r.edits.artist, r.edits.album, r.edits.genre, r.edits.year, r.edits.albumArtist,
            r.edits.trackNumber, r.edits.trackTotal, r.edits.discNumber, r.edits.discTotal, r.edits.lyrics)
            .map { if (it == null) "0" else "1$it" })
        add(when (val cover = r.edits.cover) {
            CoverEdit.Keep -> "k"
            CoverEdit.Remove -> "r"
            is CoverEdit.Replace -> "c${cover.mime}"
        })
        add(r.keys.size.toString())
        addAll(r.keys)
        add(r.batch.size.toString())
        addAll(r.batch)
        add(r.results.size.toString())
        for (result in r.results) {
            addAll(listOf(result.key, result.status.name, result.refusal?.name.orEmpty(), result.newLength?.toString().orEmpty()))
        }
    }
}

internal fun decodeTagWriteRequests(saved: List<String>?, unstash: (String) -> ByteArray?): List<TagWriteRequest> {
    if (saved == null) return emptyList()
    return try {
        val values = saved.iterator()
        check(values.next() == "1")
        List(values.next().toInt()) {
            val id = values.next().toLong()
            val kind = TagWriteKind.valueOf(values.next())
            val stage = TagWriteStage.valueOf(values.next())
            val consented = values.next().toBooleanStrict()
            val permissionRequested = values.next().toBooleanStrict()
            val stopRequested = values.next().toBooleanStrict()
            val fields = List(11) { values.next().let { v -> if (v == "0") null else v.removePrefix("1") } }
            val coverCode = values.next()
            val keys = List(values.next().toInt()) { values.next() }
            val batch = List(values.next().toInt()) { values.next() }
            val results = List(values.next().toInt()) {
                FileWriteResult(
                    key = values.next(),
                    status = FileWriteStatus.valueOf(values.next()),
                    refusal = values.next().takeIf { it.isNotEmpty() }?.let(TagRefusal::valueOf),
                    newLength = values.next().takeIf { it.isNotEmpty() }?.toLong(),
                )
            }
            val coverBytes = if (coverCode.startsWith("c")) unstash(coverName(id)) else null
            val cover = when {
                coverCode == "k" -> CoverEdit.Keep
                coverCode == "r" -> CoverEdit.Remove
                coverBytes != null -> CoverEdit.Replace(coverBytes, coverCode.removePrefix("c"))
                else -> null
            }
            val edits = TagEdits(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6],
                fields[7], fields[8], fields[9], fields[10], cover ?: CoverEdit.Keep)
            val request = TagWriteRequest(id, kind, keys, edits, stage, consented, permissionRequested, batch, results, stopRequested)
            // A cover whose bytes were lost must not be saved as "keep": finish the rest as failed.
            if (cover == null) {
                request.copy(stage = TagWriteStage.READY, results = results + request.remaining.map { FileWriteResult(it, FileWriteStatus.FAILED) })
            } else request
        }.also { check(!values.hasNext()) }
    } catch (_: Exception) {
        emptyList()
    }
}
