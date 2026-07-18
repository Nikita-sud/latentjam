/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import io.github.nikitasud.latentjam.smart.chain.SemanticDescriptorStore
import io.github.nikitasud.latentjam.smart.chain.SmartChain
import io.github.nikitasud.latentjam.smart.chain.SmartSnapshot
import io.github.nikitasud.latentjam.smart.chain.SmartTrack
import io.github.nikitasud.latentjam.smart.chain.TrackMeta
import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val descriptorSource: DescriptorSource? = null,
    private val clock: SmartClock = SmartClock.Unknown,
) : SimilarityEngine {

    private var descriptors: SemanticDescriptorStore? = null

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<EngineState>(EngineState.Uninitialized)

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()

    override suspend fun initialize(): Result<Unit> = withContext(dispatcher) {
        mutex.withLock {
            // Idempotent: a Ready engine stays Ready; the model is not reloaded.
            if (mutableState.value is EngineState.Ready) return@withLock Result.success(Unit)
            mutableState.value = EngineState.Initializing
            backend.loadModel().fold(
                onSuccess = {
                    restorePersistedIndex()
                    // The chain's models are best-effort: a missing predictor or descriptor asset
                    // costs queue quality, not the ability to shuffle, so none of this can fail
                    // initialization.
                    predictor?.let { runCatching { it.load() } }
                    textEncoder?.let { runCatching { it.load() } }
                    if (textIndex != null && textIndex.size == 0) {
                        runCatching { textStore?.load(TEXT_INDEX_VERSION) }.getOrNull()
                            ?.forEach { (id, vector) -> runCatching { textIndex.upsert(id, vector) } }
                    }
                    if (descriptors == null) {
                        descriptors = runCatching { descriptorSource?.read() }.getOrNull()
                            ?.let(SemanticDescriptorStore::parse)
                    }
                    mutableState.value = EngineState.Ready(indexedCount = index.size)
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
            }
            // Text is encoded here rather than at query time: MiniLM costs milliseconds per track,
            // but a whole library of it would stall the first SMART press for seconds.
            //
            // Over ALL tracks, not just the ones needing audio: the two indexes fill independently,
            // so a library already embedded before text encoding existed still gets its vectors.
            var textIndexed = 0
            for (track in tracks) {
                if (indexTextVector(track)) textIndexed++
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

    override suspend fun ensureMetadataVectors(library: List<TrackDescriptor>): Int =
        withContext(dispatcher) {
            mutex.withLock {
                if (mutableState.value !is EngineState.Ready) return@withLock 0
                var added = 0
                for (track in library) if (indexTextVector(track)) added++
                if (added > 0 && textIndex != null) {
                    runCatching { textStore?.save(TEXT_INDEX_VERSION, textIndex.entries()) }
                }
                added
            }
        }

    override suspend fun metadataVectors(): Map<TrackId, FloatArray> = withContext(dispatcher) {
        mutex.withLock { textIndex?.entries().orEmpty() }
    }

    override suspend fun smartQueue(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        length: Int,
    ): List<TrackId> = withContext(dispatcher) {
        mutex.withLock {
            if (mutableState.value !is EngineState.Ready || index.size == 0) return@withLock emptyList()

            // The seed anchors every distance the walk measures, so it must be in the snapshot even
            // when the caller left it out — and callers reasonably do, since it is the one track
            // the queue must not repeat. The chain excludes it from its own output regardless.
            val corpus = if (library.any { it.id == seed.id }) library else library + seed
            val tracks = corpus.mapNotNull { track ->
                val audio = index.vector(track.id) ?: return@mapNotNull null
                SmartTrack(
                    id = track.id,
                    audio = audio,
                    text = textIndex?.vector(track.id),
                    descriptor = descriptors?.lookup(track.artist, track.title),
                    energy = track.energy ?: Float.NaN,
                    meta = TrackMeta(
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        genre = track.genre,
                        year = track.year,
                    ),
                )
            }
            val snapshot = SmartSnapshot.build(tracks) ?: return@withLock emptyList()
            val chain = SmartChain(snapshot, predictor).build(
                seedId = seed.id,
                length = length,
                timeFeatures = clock.timeFeatures(),
            )
            chain.rows.map { snapshot.tracks[it].id }
        }
    }

    override suspend fun release() {
        withContext(dispatcher) {
            mutex.withLock {
                backend.close()
                index.clear()
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
        return runCatching { target.upsert(track.id, vector) }.isSuccess
    }

    /** Returns `null` on success, or the typed rejection reason. */
    private fun validateAndUpsert(id: TrackId, vector: FloatArray): EngineError? {
        if (vector.size != config.embeddingDim) {
            return EngineError.BackendFailure(
                "Backend produced a ${vector.size}-dim vector for ${id.value}, " +
                    "expected ${config.embeddingDim}",
            )
        }
        index.upsert(id, vector)
        return null
    }

    private fun Throwable.toEngineError(): EngineError =
        (this as? SmartEngineException)?.error
            ?: EngineError.BackendFailure(message ?: "Unknown backend failure", this)
}
