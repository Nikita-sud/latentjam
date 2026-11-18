/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class TrackDeleteStrategy { SYSTEM_DELETE_REQUEST, RECOVERABLE_CONSENT, WRITE_PERMISSION }
internal enum class DeleteStage { READY, DELETING, OFFER_PERMISSION, OFFER_FILE, OFFER_BATCH,
    WAIT_PERMISSION, WAIT_FILE, WAIT_BATCH, RECOVER_BATCH, COMPLETE }
internal enum class DeleteAnswer { APPROVED, CANCELLED, FAILED }
internal enum class DeletePresence { PRESENT, MISSING, DENIED, FAILED }

internal sealed interface DeleteAttempt<out Consent> {
    data object Deleted : DeleteAttempt<Nothing>
    data object Missing : DeleteAttempt<Nothing>
    data object Denied : DeleteAttempt<Nothing>
    data object Failed : DeleteAttempt<Nothing>
    data class NeedsConsent<C>(val consent: C) : DeleteAttempt<C>
}

internal interface TrackDeleteBackend<C> {
    val strategy: TrackDeleteStrategy
    fun hasWritePermission(): Boolean
    suspend fun presence(uri: String): DeletePresence
    suspend fun delete(uri: String): DeleteAttempt<C>
    suspend fun batchConsent(uris: List<String>): C
}

internal data class DeleteRequest(
    val id: Long,
    val uris: List<String>,
    val position: Int = 0,
    val stage: DeleteStage = DeleteStage.READY,
    val consented: Boolean = false,
    val permissionRequested: Boolean = false,
    val batchSize: Int = 0,
    val report: TrackDeleteReport = TrackDeleteReport(),
) {
    val remaining: Int get() = uris.size - position
}

internal data class DeletePrompt<C>(val requestId: Long, val consent: C? = null)

/** Main-thread coordinator. Its owner outlives the Activity and checkpoints every transition. */
internal class TrackDeleteCoordinator<C>(
    private val backend: TrackDeleteBackend<C>,
    private val scope: CoroutineScope,
    restored: List<String>? = null,
    private val save: (List<String>) -> Unit = {},
) {
    // A per-file consent from the platform does not survive the process: the next run has to ask
    // again. Carrying it over made the restored request report the file as denied without a
    // prompt when the platform answered NeedsConsent a second time (Android 10 per-file consent).
    private var requests = decodeDeleteRequests(restored).map { request ->
        if (request.consented) request.copy(consented = false) else request
    }
    private var nextId = (requests.maxOfOrNull { it.id } ?: 0L) + 1L
    private var worker: Job? = null
    private val mutablePrompt = MutableStateFlow<DeletePrompt<C>?>(null)
    val prompt = mutablePrompt.asStateFlow()
    private val mutableCompleted = MutableStateFlow<DeleteRequest?>(null)
    val completed = mutableCompleted.asStateFlow()
    // A process can die after the provider deletes a file but before our checkpoint. Only that
    // interrupted write may count an already absent item as completed when it is retried.
    private var interruptedWrite = requests.firstOrNull()?.stage == DeleteStage.DELETING
    private var restoredWait = requests.firstOrNull()?.takeIf {
        it.stage in setOf(DeleteStage.WAIT_PERMISSION, DeleteStage.WAIT_FILE, DeleteStage.WAIT_BATCH)
    }?.id

    init {
        val first = requests.firstOrNull()
        if (first != null && first.stage in setOf(DeleteStage.OFFER_PERMISSION, DeleteStage.OFFER_FILE, DeleteStage.OFFER_BATCH)) {
            update(first.copy(stage = DeleteStage.READY))
        }
        resume()
    }

    /**
     * Activity results are delivered before the host resumes. If none was redelivered after
     * process recreation, the old dialog is gone; do not leave its request blocking the queue.
     * Normal live dialogs and already delivered answers never enter this recovery path.
     */
    fun onHostResumed() {
        val first = requests.firstOrNull() ?: return
        if (first.id != restoredWait) return
        restoredWait = null
        when (first.stage) {
            // Nothing says what became of a batch restored without its URIs: none of it is claimed deleted.
            DeleteStage.WAIT_BATCH -> if (first.uris.isEmpty()) finishUnnamedBatch(first, cancelled = first.batchSize)
                else update(first.copy(stage = DeleteStage.RECOVER_BATCH))
            DeleteStage.WAIT_FILE -> {
                interruptedWrite = true
                update(first.copy(stage = DeleteStage.DELETING, consented = false))
            }
            DeleteStage.WAIT_PERMISSION -> update(first.copy(stage = DeleteStage.READY, permissionRequested = false))
            else -> return
        }
        resume()
    }

    fun enqueue(uris: List<String>) {
        val pending = requests.flatMapTo(HashSet()) { it.uris }
        val distinct = uris.filter { it.isNotBlank() && it !in pending }.distinct()
        if (distinct.isEmpty()) return
        requests = requests + DeleteRequest(nextId++, distinct)
        checkpoint()
        resume()
    }

    /** Called immediately before launch, with no suspension between saving and launching. */
    fun promptLaunched(id: Long): Boolean {
        val first = requests.firstOrNull() ?: return false
        if (first.id != id || mutablePrompt.value?.requestId != id) return false
        val waiting = when (first.stage) {
            DeleteStage.OFFER_PERMISSION -> DeleteStage.WAIT_PERMISSION
            DeleteStage.OFFER_FILE -> DeleteStage.WAIT_FILE
            DeleteStage.OFFER_BATCH -> DeleteStage.WAIT_BATCH
            else -> return false
        }
        update(first.copy(stage = waiting, permissionRequested =
            first.permissionRequested || waiting == DeleteStage.WAIT_PERMISSION))
        mutablePrompt.value = null
        return true
    }

    fun answer(answer: DeleteAnswer) {
        val first = requests.firstOrNull() ?: return
        if (first.stage !in setOf(DeleteStage.WAIT_PERMISSION, DeleteStage.WAIT_FILE, DeleteStage.WAIT_BATCH)) return
        restoredWait = null
        when {
            first.stage == DeleteStage.WAIT_BATCH && first.uris.isEmpty() -> when (answer) {
                DeleteAnswer.APPROVED -> finishUnnamedBatch(first, deleted = first.batchSize)
                DeleteAnswer.FAILED -> finishUnnamedBatch(first, failed = first.batchSize)
                DeleteAnswer.CANCELLED -> finishUnnamedBatch(first, cancelled = first.batchSize)
            }
            answer == DeleteAnswer.FAILED -> finishRemaining(first, failed = first.remaining)
            answer == DeleteAnswer.CANCELLED && first.stage == DeleteStage.WAIT_PERMISSION ->
                finishRemaining(first, denied = first.remaining)
            answer == DeleteAnswer.CANCELLED -> finishRemaining(first, cancelled = first.remaining)
            first.stage == DeleteStage.WAIT_BATCH -> advance(first, deleted = first.batchSize)
            else -> update(first.copy(stage = DeleteStage.READY,
                consented = first.stage == DeleteStage.WAIT_FILE))
        }
        resume()
    }

    fun acknowledge(id: Long) {
        if (requests.firstOrNull()?.let { it.id == id && it.stage == DeleteStage.COMPLETE } != true) return
        requests = requests.drop(1)
        mutableCompleted.value = null
        checkpoint()
        resume()
    }

    private fun checkpoint() = save(encodeDeleteRequests(requests))
    private fun update(request: DeleteRequest) {
        requests = listOf(request) + requests.drop(1)
        checkpoint()
    }

    private fun advance(first: DeleteRequest, deleted: Int = 0, denied: Int = 0, failed: Int = 0) {
        val position = first.position + deleted + denied + failed
        update(first.copy(position = position, consented = false, batchSize = 0,
            stage = if (position == first.uris.size) DeleteStage.COMPLETE else DeleteStage.READY,
            report = first.report.copy(deleted = first.report.deleted + deleted,
                denied = first.report.denied + denied, failed = first.report.failed + failed)))
    }

    private fun finishRemaining(first: DeleteRequest, denied: Int = 0, failed: Int = 0, cancelled: Int = 0) {
        update(first.copy(position = first.uris.size, stage = DeleteStage.COMPLETE, batchSize = 0,
            report = first.report.copy(denied = first.report.denied + denied,
                failed = first.report.failed + failed, cancelled = first.report.cancelled + cancelled)))
    }

    /**
     * Ends a request restored with a system batch out whose URIs were too long to save (see [encodeDeleteRequests]):
     * only the system's answer says what became of those [DeleteRequest.batchSize] files.
     */
    private fun finishUnnamedBatch(first: DeleteRequest, deleted: Int = 0, failed: Int = 0, cancelled: Int = 0) {
        update(first.copy(stage = DeleteStage.COMPLETE, batchSize = 0,
            report = first.report.copy(deleted = first.report.deleted + deleted,
                failed = first.report.failed + failed, cancelled = first.report.cancelled + cancelled)))
    }

    private fun offer(first: DeleteRequest, stage: DeleteStage, consent: C? = null, batchSize: Int = 0) {
        update(first.copy(stage = stage, batchSize = batchSize))
        mutablePrompt.value = DeletePrompt(first.id, consent)
    }

    /** Reconcile only the submitted batch; files beyond it were never covered by that consent. */
    private suspend fun recoverBatch(first: DeleteRequest) {
        val batch = first.uris.drop(first.position).take(first.batchSize)
        val outcomes = batch.map { uri ->
            val presence = try {
                backend.presence(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DeletePresence.FAILED
            }
            uri to presence
        }
        val finished = outcomes.filter { it.second != DeletePresence.PRESENT }
        val remaining = outcomes.filter { it.second == DeletePresence.PRESENT }
        val position = first.position + finished.size
        // Keep the counted items before the cursor, then request fresh consent for survivors.
        update(first.copy(
            uris = first.uris.take(first.position) + finished.map { it.first } + remaining.map { it.first } +
                first.uris.drop(first.position + first.batchSize),
            position = position,
            stage = if (position == first.uris.size) DeleteStage.COMPLETE else DeleteStage.READY,
            batchSize = 0,
            report = first.report.copy(
                deleted = first.report.deleted + finished.count { it.second == DeletePresence.MISSING },
                denied = first.report.denied + finished.count { it.second == DeletePresence.DENIED },
                failed = first.report.failed + finished.count { it.second == DeletePresence.FAILED },
            ),
        ))
    }

    private fun resume() {
        val first = requests.firstOrNull() ?: return
        if (first.stage == DeleteStage.COMPLETE) {
            mutableCompleted.value = first
            return
        }
        if (worker?.isActive == true || first.stage !in setOf(DeleteStage.READY, DeleteStage.DELETING, DeleteStage.RECOVER_BATCH)) return
        val job = scope.launch {
            while (true) {
                val current = requests.firstOrNull() ?: break
                if (current.stage == DeleteStage.COMPLETE) {
                    mutableCompleted.value = current
                    break
                }
                if (current.stage !in setOf(DeleteStage.READY, DeleteStage.DELETING, DeleteStage.RECOVER_BATCH)) break
                if (current.stage == DeleteStage.RECOVER_BATCH) {
                    recoverBatch(current)
                    continue
                }
                if (backend.strategy == TrackDeleteStrategy.WRITE_PERMISSION && !backend.hasWritePermission()) {
                    if (current.permissionRequested) finishRemaining(current, denied = current.remaining)
                    else offer(current, DeleteStage.OFFER_PERMISSION)
                    continue
                }
                if (backend.strategy == TrackDeleteStrategy.SYSTEM_DELETE_REQUEST) {
                    // Android 16 limits each system request to 2,000 media items.
                    val batch = current.uris.drop(current.position).take(2_000)
                    try {
                        offer(current, DeleteStage.OFFER_BATCH, backend.batchConsent(batch), batch.size)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        finishRemaining(current, failed = current.remaining)
                    }
                    continue
                }
                update(current.copy(stage = DeleteStage.DELETING))
                val attempt = try {
                    backend.delete(current.uris[current.position])
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    DeleteAttempt.Failed
                }
                when (attempt) {
                    DeleteAttempt.Deleted -> advance(current, deleted = 1)
                    DeleteAttempt.Missing -> if (interruptedWrite) advance(current, deleted = 1)
                        else advance(current, failed = 1)
                    DeleteAttempt.Denied -> advance(current, denied = 1)
                    DeleteAttempt.Failed -> advance(current, failed = 1)
                    is DeleteAttempt.NeedsConsent -> if (!current.consented &&
                        backend.strategy == TrackDeleteStrategy.RECOVERABLE_CONSENT) {
                        offer(current, DeleteStage.OFFER_FILE, attempt.consent)
                    } else advance(current, denied = 1)
                }
                interruptedWrite = false
            }
        }
        worker = job
        job.invokeOnCompletion { failure ->
            if (worker === job) worker = null
            if (failure == null && requests.firstOrNull()?.stage in setOf(DeleteStage.READY, DeleteStage.DELETING, DeleteStage.RECOVER_BATCH)) {
                resume()
            }
        }
    }
}

/**
 * The most characters of URIs that the queued deletions put into saved state. Whole requests: about a thousand
 * MediaStore URIs. A larger request saves only the batch out at the system dialog, at most 2,000 URIs with their
 * shared beginning written once: for MediaStore URIs that is the row numbers, 40,000 characters at their longest.
 */
internal const val MAX_SAVED_DELETE_URI_CHARS = 50_000

/**
 * Whether [requests] are saved with their URIs. Saved state travels in one system transaction of about a megabyte for
 * the whole Activity, and a state above it ends the process when the app leaves the screen: a library-wide deletion
 * (10,000 URIs are about that megabyte) is not written into it.
 */
private fun deleteRequestsFitSavedState(requests: List<DeleteRequest>): Boolean {
    // A request restored from the short form no longer names all its files (its counts are ahead of its cursor, or
    // it has no URIs left): it stays in the short form.
    if (requests.any { it.uris.isEmpty() || it.report.total != it.position }) return false
    // No URI is empty, so more of them than the bound cannot fit: a long list is not summed at every checkpoint.
    if (requests.sumOf { it.uris.size } > MAX_SAVED_DELETE_URI_CHARS) return false
    return requests.sumOf { request -> request.uris.sumOf { it.length } } <= MAX_SAVED_DELETE_URI_CHARS
}

/**
 * String-only saved state works with SavedStateHandle without retaining a Context or IntentSender.
 *
 * Deletions too large for saved state (see [deleteRequestsFitSavedState]) are saved in a short form: their counts,
 * and the batch that is out at the system dialog. The owner keeps every URI across a recreated Activity. After a
 * restarted process such a request cannot go on: what was done stays counted, the batch that was out is settled by
 * the dialog's answer or checked against what still exists (as a whole request's batch is), and the rest is
 * restored as cancelled. A batch too long even with its shared beginning written once is saved as its size alone:
 * the dialog's answer still counts it, and without an answer it is counted as cancelled.
 */
internal fun encodeDeleteRequests(requests: List<DeleteRequest>): List<String> =
    if (deleteRequestsFitSavedState(requests)) encodeWholeDeleteRequests(requests) else buildList {
        add("2")
        add(requests.size.toString())
        for (request in requests) {
            val out = request.stage == DeleteStage.WAIT_BATCH || request.stage == DeleteStage.RECOVER_BATCH
            val batchSize = if (out) request.batchSize else 0
            val batch = if (!out || request.uris.isEmpty()) emptyList() else {
                request.uris.subList(request.position, minOf(request.position + batchSize, request.uris.size))
            }
            val shared = sharedBeginning(batch)
            val named = batch.isNotEmpty() &&
                shared.length + batch.sumOf { it.length - shared.length } <= MAX_SAVED_DELETE_URI_CHARS
            val stage = when {
                batchSize == 0 -> DeleteStage.COMPLETE
                named -> request.stage
                else -> DeleteStage.WAIT_BATCH
            }
            // What lies beyond the batch is never asked for again; a request restored without URIs has none left.
            val beyond = (request.remaining - batchSize).coerceAtLeast(0)
            addAll(listOf(request.id.toString(), request.report.deleted.toString(), request.report.denied.toString(),
                request.report.failed.toString(), (request.report.cancelled + beyond).toString(),
                stage.name, batchSize.toString(), (if (named) batch.size else 0).toString()))
            if (named) {
                add(shared)
                batch.forEach { add(it.substring(shared.length)) }
            }
        }
    }

/** What every one of [values] begins with: MediaStore URIs differ only in the row number at their end. */
private fun sharedBeginning(values: List<String>): String {
    var shared = values.firstOrNull() ?: return ""
    for (value in values) {
        shared = shared.commonPrefixWith(value)
        if (shared.isEmpty()) break
    }
    return shared
}

private fun encodeWholeDeleteRequests(requests: List<DeleteRequest>): List<String> = buildList {
    add("1")
    add(requests.size.toString())
    for (request in requests) {
        addAll(listOf(request.id.toString(), request.position.toString(), request.stage.name,
            request.consented.toString(), request.permissionRequested.toString(), request.batchSize.toString(),
            request.report.deleted.toString(), request.report.denied.toString(), request.report.failed.toString(),
            request.report.cancelled.toString(), request.uris.size.toString()))
        addAll(request.uris)
    }
}

internal fun decodeDeleteRequests(saved: List<String>?): List<DeleteRequest> {
    if (saved == null) return emptyList()
    return runCatching {
        val values = saved.iterator()
        val format = values.next()
        if (format == "2") return@runCatching decodeFinishedDeleteRequests(values)
        check(format == "1")
        List(values.next().toInt()) {
            val id = values.next().toLong()
            val position = values.next().toInt()
            val stage = DeleteStage.valueOf(values.next())
            val consented = values.next().toBooleanStrict()
            val permissionRequested = values.next().toBooleanStrict()
            val batchSize = values.next().toInt()
            val report = TrackDeleteReport(values.next().toInt(), values.next().toInt(),
                values.next().toInt(), values.next().toInt())
            val uris = List(values.next().toInt()) { values.next() }
            check(uris.isNotEmpty() && position in 0..uris.size && batchSize in 0..(uris.size - position))
            check(listOf(report.deleted, report.denied, report.failed, report.cancelled).all { it >= 0 })
            check(report.total == position)
            DeleteRequest(id, uris, position, stage, consented, permissionRequested, batchSize, report)
        }.also { check(!values.hasNext()) }
    }.getOrDefault(emptyList())
}

/**
 * The short form of [encodeDeleteRequests]: each request with its report as it stood and the files beyond its batch
 * counted as cancelled. A request with no batch out is finished; one with a batch out waits for the system's answer,
 * with that batch's URIs as all it still names, or with none when they were too long to save.
 */
private fun decodeFinishedDeleteRequests(values: Iterator<String>): List<DeleteRequest> =
    List(values.next().toInt()) {
        val id = values.next().toLong()
        val report = TrackDeleteReport(values.next().toInt(), values.next().toInt(),
            values.next().toInt(), values.next().toInt())
        check(listOf(report.deleted, report.denied, report.failed, report.cancelled).all { it >= 0 })
        val stage = DeleteStage.valueOf(values.next())
        val batchSize = values.next().toInt()
        val named = values.next().toInt()
        check(batchSize >= 0 && (named == 0 || named == batchSize))
        val uris = if (named == 0) emptyList() else values.next().let { shared -> List(named) { shared + values.next() } }
        if (batchSize == 0) {
            DeleteRequest(id, uris = emptyList(), stage = DeleteStage.COMPLETE, report = report)
        } else {
            check(stage == DeleteStage.WAIT_BATCH || (stage == DeleteStage.RECOVER_BATCH && named > 0))
            DeleteRequest(id, uris, stage = stage, batchSize = batchSize, report = report)
        }
    }.also { check(!values.hasNext()) }
