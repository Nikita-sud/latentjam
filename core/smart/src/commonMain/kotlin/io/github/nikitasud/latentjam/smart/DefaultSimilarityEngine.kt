/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.MetadataFallbackQueue
import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import io.github.nikitasud.latentjam.smart.chain.SmartChain
import io.github.nikitasud.latentjam.smart.chain.SmartSnapshot
import io.github.nikitasud.latentjam.smart.chain.SmartTrack
import io.github.nikitasud.latentjam.smart.chain.TrackMeta
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorFusion
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSpace
import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * The one production [SimilarityEngine]: orchestrates a platform
 * [EmbeddingBackend] and a [VectorIndex], nothing more.
 *
 * ### Concurrency model
 * Two layers, both required:
 * 1. [dispatcher] — a background dispatcher (single-parallelism in the DI
 *    graph) that keeps model loading, inference, and index scans off the
 *    caller's thread. This is the "never blocks the Compose UI" guarantee.
 * 2. [mutex] — serializes whole OPERATIONS. A single-parallelism dispatcher
 *    alone only serializes between suspension points; without the mutex, a
 *    `nextTrack` could interleave into the middle of an `indexLibrary` batch
 *    while it awaits an `embed`. The mutex makes each public call atomic.
 *
 * Construct via the Koin module ([io.github.nikitasud.latentjam.smart.di.smartEngineModule])
 * or directly in tests; `internal` because the type is an implementation
 * detail — callers depend on [SimilarityEngine] only.
 */
internal class DefaultSimilarityEngine(
    private val backend: EmbeddingBackend,
    private val index: VectorIndex,
    private val store: IndexStore,
    private val config: SmartEngineConfig,
    private val dispatcher: CoroutineDispatcher,
    // The chain's inputs. All optional: without them SMART degrades to single
    // nearest-neighbour picks rather than failing, which is how iOS behaves today.
    private val predictor: PredictorRuntime? = null,
    private val textEncoder: TextEncoder? = null,
    private val textIndex: VectorIndex? = null,
    private val textStore: IndexStore? = null,
    private val clock: SmartClock = SmartClock.Unknown,
) : SimilarityEngine {

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    private val knownTracks = LinkedHashMap<TrackId, TrackDescriptor>()
    private var indexRevision = 0L
    private var snapshotCache: SnapshotCache? = null
    private var predictorLoaded = false
    private var textEncoderLoaded = false
    private val semanticCache = LinkedHashMap<TrackId, TrackSemantics>()

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()

    override suspend fun initialize(): Result<Unit> = withContext(dispatcher) {
        mutex.withLock {
            // Idempotent: a Ready engine stays Ready; the model is not reloaded.
            if (mutableState.value is EngineState.Ready) return@withLock Result.success(Unit)
            mutableState.value = EngineState.Initializing
            backend.loadModel().fold(
                onSuccess = {
                    restorePersistedIndex()
                    // The chain's models are best-effort: a missing predictor or text encoder costs
                    // queue quality, not the ability to shuffle, so none of this can fail startup.
                    predictorLoaded = predictor?.let {
                        runCatching { it.load().getOrThrow() }.isSuccess
                    } ?: false
                    textEncoderLoaded = textEncoder?.let {
                        runCatching { it.load().getOrThrow() }.isSuccess
                    } ?: false
                    if (textIndex != null && textIndex.size == 0) {
                        runCatching { textStore?.load(TEXT_INDEX_VERSION) }.getOrNull()
                            ?.forEach { (id, vector) -> runCatching { textIndex.upsert(id, vector) } }
                    }
                    mutableState.value = EngineState.Ready(indexedCount = index.size)
                    println(
                        "SMART: models audio=ready, " +
                            "scorer=${if (predictorLoaded) "ready" else "unavailable"}, " +
                            "text=${if (textEncoderLoaded) "ready" else "unavailable"}",
                    )
                    Result.success(Unit)
                },
                onFailure = { throwable ->
                    val error = throwable.toEngineError()
                    mutableState.value = EngineState.Failed(error)
                    Result.failure(SmartEngineException(error))
                },
            )
        }
    }

    override suspend fun indexLibrary(tracks: List<TrackDescriptor>): IndexReport = withContext(dispatcher) {
        mutex.withLock {
            rememberTracks(tracks)
            if (mutableState.value !is EngineState.Ready) {
                return@withLock IndexReport(
                    indexed = 0,
                    failed = tracks.size,
                    errors = tracks.associate { it.id to EngineError.ModelUnavailable },
                )
            }
            // Resumability: tracks already in the index keep their embedding.
            val (alreadyIndexed, toEmbed) = tracks.partition { it.id in index }
            var indexed = 0
            val errors = LinkedHashMap<TrackId, EngineError>()
            for (track in toEmbed) {
                backend.embed(track).fold(
                    onSuccess = { vector ->
                        val rejection = validateAndUpsert(track.id, vector)
                        if (rejection == null) indexed++ else errors[track.id] = rejection
                    },
                    onFailure = { throwable -> errors[track.id] = throwable.toEngineError() },
                )
                // Let foreground dispatchers run between independent tracks. Native inference is
                // still serialized, but indexing no longer behaves like one unbroken CPU task.
                yield()
            }
            // Text is encoded here rather than at query time: MiniLM costs milliseconds per track,
            // but a whole library of it would stall the first SMART press for seconds.
            //
            // Over ALL tracks, not just the ones needing audio: the two indexes fill independently,
            // so a library already embedded before text encoding existed still gets its vectors.
            var textIndexed = 0
            for (track in tracks) {
                if (indexTextVector(track)) textIndexed++
                yield()
            }
            if (indexed > 0) {
                runCatching { store.save(config.modelVersion, index.entries()) }
            }
            if (textIndexed > 0 && textIndex != null) {
                runCatching { textStore?.save(TEXT_INDEX_VERSION, textIndex.entries()) }
            }
            mutableState.value = EngineState.Ready(indexedCount = index.size)
            IndexReport(
                indexed = indexed,
                failed = errors.size,
                skipped = alreadyIndexed.size,
                errors = errors,
            )
        }
    }

    override suspend fun synchronizeLibrary(library: List<TrackDescriptor>): Int =
        withContext(dispatcher) {
            mutex.withLock {
                if (mutableState.value !is EngineState.Ready) return@withLock 0
                val liveIds = library.mapTo(HashSet(library.size)) { it.id }
                val staleAudio = index.entries().keys.filterNot(liveIds::contains)
                val staleText = textIndex?.entries()?.keys?.filterNot(liveIds::contains).orEmpty()

                // Library rows keep the same id when their tags are edited. Preserve the expensive
                // audio vector, but invalidate the cheap text vector so semantic search and SMART
                // conditioning cannot keep using an old title, artist, genre, or year. A changed
                // audio locator/duration is treated as replaced content and embedded again.
                val changedText = library.mapNotNull { track ->
                    knownTracks[track.id]
                        ?.takeIf { previous -> !previous.hasSameTextIdentity(track) }
                        ?.let { track.id }
                }
                val changedAudio = library.mapNotNull { track ->
                    knownTracks[track.id]
                        ?.takeIf { previous -> !previous.hasSameAudioIdentity(track) }
                        ?.let { track.id }
                }

                staleAudio.forEach(index::remove)
                staleText.forEach { textIndex?.remove(it) }
                changedAudio.forEach(index::remove)
                changedText.forEach { textIndex?.remove(it) }
                (staleAudio + changedAudio).forEach(semanticCache::remove)
                knownTracks.keys.retainAll(liveIds)

                if (staleAudio.isNotEmpty() || changedAudio.isNotEmpty()) {
                    runCatching { store.save(config.modelVersion, index.entries()) }
                }
                if ((staleText.isNotEmpty() || changedText.isNotEmpty()) && textIndex != null) {
                    runCatching { textStore?.save(TEXT_INDEX_VERSION, textIndex.entries()) }
                }
                if (
                    staleAudio.isNotEmpty() ||
                    staleText.isNotEmpty() ||
                    changedAudio.isNotEmpty() ||
                    changedText.isNotEmpty()
                ) {
                    indexRevision++
                    snapshotCache = null
                }
                rememberTracks(library)
                mutableState.value = EngineState.Ready(indexedCount = index.size)
                (staleAudio + changedAudio).distinct().size
            }
        }

    override suspend fun nextTrack(context: ListeningContext): NextTrackResult = withContext(dispatcher) {
        mutex.withLock {
            if (mutableState.value !is EngineState.Ready) {
                return@withLock NextTrackResult.Failure(EngineError.ModelUnavailable)
            }
            if (index.size == 0) {
                return@withLock NextTrackResult.Failure(EngineError.NotIndexed)
            }

            val seed = context.seed
            // Prefer the stored vector — no inference cost for indexed seeds.
            val seedVector = index.vector(seed.id)
                ?: backend.embed(seed).getOrElse { throwable ->
                    return@withLock NextTrackResult.Failure(throwable.toEngineError())
                }
            if (seedVector.size != config.embeddingDim) {
                return@withLock NextTrackResult.Failure(
                    EngineError.BackendFailure(
                        "Backend produced a ${seedVector.size}-dim vector for ${seed.id.value}, " +
                            "expected ${config.embeddingDim}",
                    ),
                )
            }

            val excluded = buildSet {
                add(seed.id)
                addAll(context.recentTrackIds)
                addAll(context.excludedTrackIds)
            }
            val best = index.nearest(query = seedVector, k = 1, exclude = excluded).firstOrNull()
                ?: return@withLock NextTrackResult.NoCandidates
            NextTrackResult.Match(trackId = best.trackId, similarity = best.score)
        }
    }

    override suspend fun embedding(trackId: TrackId): FloatArray? = withContext(dispatcher) {
        mutex.withLock { index.vector(trackId) }
    }

    override suspend fun libraryMixVectors(ids: List<TrackId>): LibraryVectorSpace? =
        withContext(dispatcher) {
            mutex.withLock {
                if (mutableState.value !is EngineState.Ready) return@withLock null
                LibraryVectorFusion.buildFromIndexes(
                    ids = ids,
                    audio = index,
                    metadata = textIndex,
                    audioDim = config.embeddingDim,
                    metadataDim = TextEncoder.TEXT_DIM,
                )
            }
        }

    override suspend fun libraryMixFeatures(ids: List<TrackId>): LibraryMixFeatures? =
        withContext(dispatcher) {
            mutex.withLock {
                if (mutableState.value !is EngineState.Ready) return@withLock null
                val vectorSpace = LibraryVectorFusion.buildFromIndexes(
                    ids = ids,
                    audio = index,
                    metadata = textIndex,
                    audioDim = config.embeddingDim,
                    metadataDim = TextEncoder.TEXT_DIM,
                ) ?: return@withLock null
                val requested = ids.distinct()
                val missing = requested.mapNotNull { id ->
                    if (id in semanticCache) return@mapNotNull null
                    index.vector(id)?.let { id to it }
                }
                for (batch in missing.chunked(SEMANTIC_BATCH_SIZE)) {
                    val outputs = backend.classify(batch.map { it.second }).getOrNull()
                        ?.takeIf { it.size == batch.size }
                        ?: continue
                    for (row in batch.indices) {
                        TrackSemantics.fromModelOutput(outputs[row])?.let { prediction ->
                            semanticCache[batch[row].first] = prediction
                        }
                    }
                    yield()
                }
                LibraryMixFeatures(
                    vectorSpace = vectorSpace,
                    semantics = requested.mapNotNull { id ->
                        semanticCache[id]?.let { id to it }
                    }.toMap(LinkedHashMap()),
                )
            }
        }

    override suspend fun ensureMetadataVectors(library: List<TrackDescriptor>): Int =
        withContext(dispatcher) {
            mutex.withLock {
                if (mutableState.value !is EngineState.Ready) return@withLock 0
                rememberTracks(library)
                var added = 0
                for (track in library) {
                    if (indexTextVector(track)) added++
                    yield()
                }
                if (added > 0 && textIndex != null) {
                    runCatching { textStore?.save(TEXT_INDEX_VERSION, textIndex.entries()) }
                }
                added
            }
        }

    override suspend fun metadataVectors(): Map<TrackId, FloatArray> = withContext(dispatcher) {
        mutex.withLock { textIndex?.entries().orEmpty() }
    }

    override suspend fun semanticSearch(query: String, limit: Int): List<ScoredTrack> =
        withContext(dispatcher) {
            mutex.withLock {
                if (limit <= 0 || query.isBlank() || mutableState.value !is EngineState.Ready) {
                    return@withLock emptyList()
                }
                val encoder = textEncoder ?: return@withLock emptyList()
                val target = textIndex ?: return@withLock emptyList()
                val vector = runCatching { encoder.encode(query.trim()) }.getOrNull()
                    ?.takeIf { it.size == TextEncoder.TEXT_DIM && it.all(Float::isFinite) }
                    ?: return@withLock emptyList()
                target.nearest(vector, limit)
            }
        }

    override suspend fun smartQueue(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        length: Int,
        history: List<SmartHistoryEvent>,
    ): List<TrackId> = withContext(dispatcher) {
        mutex.withLock {
            if (mutableState.value !is EngineState.Ready) return@withLock emptyList()
            rememberTracks(library)
            rememberTracks(listOf(seed))
            // Metadata is cheap enough to create on demand and gives first launch an honest local
            // result while the acoustic index is still cold.
            indexTextVector(seed)

            // First-launch indexing is progressive. Whichever track the listener actually picked
            // must still be a valid anchor even when its background batch has not reached it yet.
            if (index.vector(seed.id) == null) {
                val vector = backend.embed(seed).getOrNull()
                    ?: return@withLock metadataFallback(seed, library, length, history)
                if (validateAndUpsert(seed.id, vector) != null) {
                    return@withLock metadataFallback(seed, library, length, history)
                }
                runCatching { store.save(config.modelVersion, index.entries()) }
                mutableState.value = EngineState.Ready(indexedCount = index.size)
            }

            // The scorer graph has 100 slots but deliberately supports a shorter, zero-padded
            // pool. Promote once there is both a useful minimum and good coverage of this library;
            // a fixed 64-track gate incorrectly kept a 57-track phone on metadata forever.
            val eligibleIds = library.mapTo(LinkedHashSet()) { it.id }.apply { add(seed.id) }
            val indexedEligible = eligibleIds.count { it in index }
            val requiredAudio = requiredAudioCorpus(eligibleIds.size)
            if (indexedEligible < requiredAudio) {
                println(
                    "SMART: queue=metadata indexed=$indexedEligible/${eligibleIds.size}, " +
                        "required=$requiredAudio",
                )
                return@withLock metadataFallback(seed, library, length, history)
            }

            // The seed anchors every distance the walk measures, so it must be in the snapshot even
            // when the caller left it out — and callers reasonably do, since it is the one track
            // the queue must not repeat. The chain excludes it from its own output regardless.
            val snapshot = snapshotFor(history)
                ?: return@withLock metadataFallback(seed, library, length, history)
            val eligibleRows = BooleanArray(snapshot.size) { row ->
                snapshot.tracks[row].id in eligibleIds
            }
            val livePredictor = predictor.takeIf { predictorLoaded }
            println(
                "SMART: queue=${if (livePredictor != null) "audio-scorer" else "audio-geometry"} " +
                    "indexed=$indexedEligible/${eligibleIds.size}, required=$requiredAudio, " +
                    "text=${if (textEncoderLoaded) "ready" else "unavailable"}",
            )
            val chain = SmartChain(snapshot, livePredictor, eligibleRows).build(
                seedId = seed.id,
                length = length,
                timeFeatures = clock.timeFeatures(),
                historyEvents = history,
            )
            chain.rows.map { snapshot.tracks[it].id }
                .ifEmpty { metadataFallback(seed, library, length, history) }
        }
    }

    override suspend fun clearAnalysis() {
        withContext(dispatcher) {
            mutex.withLock {
                index.clear()
                textIndex?.clear()
                store.clear()
                textStore?.clear()
                knownTracks.clear()
                semanticCache.clear()
                snapshotCache = null
                indexRevision++
                if (mutableState.value is EngineState.Ready) {
                    mutableState.value = EngineState.Ready(indexedCount = 0)
                }
            }
        }
    }

    override suspend fun release() {
        withContext(dispatcher) {
            mutex.withLock {
                backend.close()
                predictor?.close()
                textEncoder?.close()
                index.clear()
                textIndex?.clear()
                knownTracks.clear()
                semanticCache.clear()
                snapshotCache = null
                indexRevision++
                predictorLoaded = false
                textEncoderLoaded = false
                mutableState.value = EngineState.Uninitialized
            }
        }
    }

    /**
     * Loads the persisted snapshot into an EMPTY index (a live index is never
     * clobbered on re-initialize). Individually invalid entries are skipped;
     * a missing/mismatched snapshot just means starting empty.
     */
    private suspend fun restorePersistedIndex() {
        if (index.size > 0) return
        val persisted = runCatching { store.load(config.modelVersion) }.getOrNull() ?: return
        for ((id, vector) in persisted) {
            runCatching { index.upsert(id, vector) }
        }
        if (persisted.isNotEmpty()) indexRevision++
    }

    /**
     * Encodes and stores this track's metadata text vector.
     *
     * @return true when a new vector was added, so the caller knows whether to persist.
     */
    private fun indexTextVector(track: TrackDescriptor): Boolean {
        val encoder = textEncoder ?: return false
        val target = textIndex ?: return false
        if (track.id in target) return false
        val metadata = TextEncoder.metadataString(
            genre = track.genre, artist = track.artist, title = track.title, year = track.year,
        )
        if (metadata.isBlank()) return false
        val vector = runCatching { encoder.encode(metadata) }.getOrNull() ?: return false
        if (vector.size != TextEncoder.TEXT_DIM) return false
        return runCatching { target.upsert(track.id, vector) }
            .onSuccess {
                indexRevision++
                snapshotCache = null
            }
            .isSuccess
    }

    /** Returns `null` on success, or the typed rejection reason. */
    private fun validateAndUpsert(id: TrackId, vector: FloatArray): EngineError? {
        if (vector.size != config.embeddingDim) {
            return EngineError.BackendFailure(
                "Backend produced a ${vector.size}-dim vector for ${id.value}, " +
                    "expected ${config.embeddingDim}",
            )
        }
        var normSquared = 0.0
        for (component in vector) {
            if (!component.isFinite()) {
                return EngineError.BackendFailure(
                    "Backend produced a non-finite embedding for ${id.value}",
                )
            }
            normSquared += component.toDouble() * component
        }
        if (!normSquared.isFinite() || normSquared <= 1e-12) {
            return EngineError.BackendFailure(
                "Backend produced a zero-norm embedding for ${id.value}",
            )
        }
        index.upsert(id, vector)
        semanticCache.remove(id)
        indexRevision++
        snapshotCache = null
        return null
    }

    private fun rememberTracks(tracks: List<TrackDescriptor>) {
        for (track in tracks) {
            if (knownTracks[track.id] != track) {
                knownTracks[track.id] = track
                indexRevision++
                snapshotCache = null
            }
        }
    }

    private fun TrackDescriptor.hasSameTextIdentity(other: TrackDescriptor): Boolean =
        title == other.title && artist == other.artist && genre == other.genre && year == other.year

    private fun TrackDescriptor.hasSameAudioIdentity(other: TrackDescriptor): Boolean =
        audioUri == other.audioUri && durationMs == other.durationMs

    private fun snapshotFor(history: List<SmartHistoryEvent>): SmartSnapshot? {
        snapshotCache?.takeIf { it.revision == indexRevision }?.let { return it.snapshot }

        val rows = knownTracks.values.mapNotNull { track ->
            val audio = index.vector(track.id) ?: return@mapNotNull null
            track.toSmartTrack(audio)
        }.toMutableList()
        val knownIds = rows.mapTo(HashSet()) { it.id }
        for (id in history.asSequence().map { it.trackId }.distinct()) {
            if (id in knownIds) continue
            val audio = index.vector(id) ?: continue
            rows += SmartTrack(
                id = id,
                audio = audio,
                text = textIndex?.vector(id),
                meta = TrackMeta(null, null, null, null, null),
            )
        }
        val snapshot = SmartSnapshot.build(rows) ?: return null
        snapshotCache = SnapshotCache(indexRevision, snapshot)
        return snapshot
    }

    private fun TrackDescriptor.toSmartTrack(audio: FloatArray): SmartTrack = SmartTrack(
        id = id,
        audio = audio,
        text = textIndex?.vector(id),
        energy = energy ?: Float.NaN,
        meta = TrackMeta(title, artist, album, genre, year),
    )

    private fun metadataFallback(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        length: Int,
        history: List<SmartHistoryEvent>,
    ): List<TrackId> = MetadataFallbackQueue.build(seed, library, length, textIndex, history)

    private data class SnapshotCache(
        val revision: Long,
        val snapshot: SmartSnapshot,
    )

    private fun Throwable.toEngineError(): EngineError =
        (this as? SmartEngineException)?.error
            ?: EngineError.BackendFailure(message ?: "Unknown backend failure", this)

    private companion object {
        /** Maximum warm-up requirement for large libraries. */
        const val MIN_AUDIO_CORPUS = 64

        /** Below this, the metadata-only full-library path is more honest than a tiny audio pool. */
        const val MIN_AUDIO_CORPUS_FLOOR = 24

        const val SEMANTIC_BATCH_SIZE = 128

        /** Small libraries promote only when at least 80% of their tracks have usable audio. */
        fun requiredAudioCorpus(librarySize: Int): Int {
            val coverageTarget = (librarySize * 4 + 4) / 5
            return maxOf(MIN_AUDIO_CORPUS_FLOOR, coverageTarget).coerceAtMost(MIN_AUDIO_CORPUS)
        }
    }
}
