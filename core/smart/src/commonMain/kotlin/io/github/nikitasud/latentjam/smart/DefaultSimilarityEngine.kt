/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.ChainTuning
import io.github.nikitasud.latentjam.smart.chain.Rerank
import io.github.nikitasud.latentjam.smart.chain.ChainWalk
import io.github.nikitasud.latentjam.smart.chain.JourneySequencer
import io.github.nikitasud.latentjam.smart.chain.MetadataFallbackQueue
import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import io.github.nikitasud.latentjam.smart.chain.SmartChain
import io.github.nikitasud.latentjam.smart.chain.SmartSnapshot
import io.github.nikitasud.latentjam.smart.chain.SmartTrack
import io.github.nikitasud.latentjam.smart.chain.TrackMeta
import io.github.nikitasud.latentjam.smart.chain.applyLibraryEnergy
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorCoverage
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorFusion
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSpace
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile
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
    /** Teacher knowledge about artists for the chain's descriptor space; absent, the space stays empty. */
    private val artistKnowledge: ArtistKnowledge? = null,
    private val textIndex: VectorIndex? = null,
    private val textStore: IndexStore? = null,
    private val clock: SmartClock = SmartClock.Unknown,
) : SimilarityEngine {

    private val mutex = Mutex()

    @Volatile
    private var artistRunPenalty = config.artistRunPenalty.coerceAtLeast(0f)

    override fun setArtistRunPenalty(penalty: Float) {
        artistRunPenalty = penalty.coerceAtLeast(0f)
    }
    private val mutableState = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    private val knownTracks = LinkedHashMap<TrackId, TrackDescriptor>()
    /** Identity of the descriptor used to create each in-memory/persisted audio vector. */
    private val audioVectorIdentities = LinkedHashMap<TrackId, String>()
    /** Track-local audio failures that should not wake the model again for unchanged media. */
    private val audioFailureIdentities = LinkedHashMap<TrackId, String>()
    /** Identity of the descriptor used to create each in-memory/persisted metadata vector. */
    private val textVectorIdentities = LinkedHashMap<TrackId, String>()
    /**
     * In-memory mutations remain dirty until the corresponding snapshot save returns normally.
     * This matters after an I/O failure: the next operation may find every vector already present,
     * but it must still retry the snapshot rather than treating the batch as fully persisted.
     */
    private var audioIndexDirty = false
    private var textIndexDirty = false
    private var indexRevision = 0L
    private var snapshotCache: SnapshotCache? = null
    private var mixCoverageCache: MixCoverageCache? = null
    private var predictorLoaded = false
    private var textEncoderLoaded = false
    private var audioModelLoaded = false
    private var semanticModelLoaded = false
    private val semanticCache = LinkedHashMap<TrackId, TrackSemantics>()

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()

    override suspend fun initialize(): Result<Unit> = withContext(dispatcher) {
        mutex.withLock {
            // Idempotent: a Ready engine stays Ready; nothing is reloaded.
            if (mutableState.value is EngineState.Ready) return@withLock Result.success(Unit)
            mutableState.value = EngineState.Initializing
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
                runCatching { textStore?.loadSnapshot(TEXT_INDEX_VERSION) }.getOrNull()
                    ?.let { persisted ->
                        for ((id, vector) in persisted.entries) {
                            runCatching { textIndex.upsert(id, vector) }
                                .onSuccess {
                                    persisted.identities[id]?.let { identity ->
                                        textVectorIdentities[id] = identity
                                    }
                                }
                        }
                    }
            }
            mutableState.value = EngineState.Ready(indexedCount = index.size)
            println(
                "SMART: models audio=lazy, " +
                    "scorer=${if (predictorLoaded) "ready" else "unavailable"}, " +
                    "text=${if (textEncoderLoaded) "ready" else "unavailable"}",
            )
            Result.success(Unit)
        }
    }

    /**
     * Loads the audio encoder on first need instead of at [initialize]: a fully indexed library
     * answers every query from stored vectors, so launches must not pay tens of MB of ONNX
     * session for a model they may never run. Failures are returned to the operation that needed
     * the model, never latched into [state] — the next operation retries the load, and the
     * persisted index keeps serving queries in the meantime.
     *
     * Only ever called with [mutex] held, which is what makes the unguarded flag safe.
     */
    private suspend fun ensureAudioModel(): Result<Unit> {
        if (audioModelLoaded) return Result.success(Unit)
        return backend.loadModel().onSuccess {
            audioModelLoaded = true
            println("SMART: audio model loaded on first use")
        }
    }

    /** Loads the small semantic head without paying for the audio encoder on restored libraries. */
    private suspend fun ensureSemanticModel(): Result<Unit> {
        if (semanticModelLoaded) return Result.success(Unit)
        return backend.loadSemanticModel().onSuccess {
            semanticModelLoaded = true
            println("SMART: semantic model loaded for mix classification")
        }
    }

    override suspend fun indexLibrary(tracks: List<TrackDescriptor>): IndexReport =
        indexLibrary(tracks, persistAfterBatch = true)

    override suspend fun stageLibraryIndex(tracks: List<TrackDescriptor>): IndexReport =
        indexLibrary(tracks, persistAfterBatch = false)

    private suspend fun indexLibrary(
        tracks: List<TrackDescriptor>,
        persistAfterBatch: Boolean,
    ): IndexReport = withContext(dispatcher) {
        mutex.withLock {
            rememberTracks(tracks)
            if (mutableState.value !is EngineState.Ready) {
                return@withLock IndexReport(
                    indexed = 0,
                    failed = tracks.size,
                    errors = tracks.associate { it.id to EngineError.ModelUnavailable },
                )
            }
            // Resumability: tracks already in the index keep their embedding. A file that the
            // decoder could not open is also skipped while its exact content identity is
            // unchanged. This partition happens before ensureAudioModel(), which is the key to
            // keeping one permanently bad file from waking the lazy model on every launch.
            val alreadyIndexed = tracks.filter { it.id in index }
            val rememberedFailures = tracks.filter { track ->
                track.id !in index && hasRememberedAudioFailure(track)
            }
            val rememberedFailureIds = rememberedFailures.mapTo(HashSet()) { it.id }
            // A descriptor with no playable URI is a deterministic track-local miss that can be
            // remembered without loading the model even once (notably protected Music.app rows).
            val missingAudio = tracks.filter { track ->
                track.id !in index &&
                    track.id !in rememberedFailureIds &&
                    track.audioUri == null
            }
            val missingAudioIds = missingAudio.mapTo(HashSet()) { it.id }
            val toEmbed = tracks.filter {
                it.id !in index && it.id !in rememberedFailureIds && it.id !in missingAudioIds
            }
            var indexed = 0
            val errors = LinkedHashMap<TrackId, EngineError>()
            rememberedFailures.forEach { track ->
                errors[track.id] = EngineError.InvalidAudio()
            }
            missingAudio.forEach { track ->
                errors[track.id] = EngineError.InvalidAudio("No audio URI")
                rememberAudioFailure(track)
            }
            val modelFailure = if (toEmbed.isEmpty()) {
                null // Nothing to embed — the audio model stays unloaded.
            } else {
                ensureAudioModel().exceptionOrNull()?.toEngineError()
            }
            if (modelFailure != null) {
                for (track in toEmbed) errors[track.id] = modelFailure
            } else {
                backend.embedEach(toEmbed) { track, result ->
                    result.fold(
                        onSuccess = { vector ->
                            val rejection = validateAndUpsert(track, vector)
                            if (rejection == null) indexed++ else errors[track.id] = rejection
                        },
                        onFailure = { throwable ->
                            val error = throwable.toEngineError()
                            errors[track.id] = error
                            if (error is EngineError.InvalidAudio) {
                                rememberAudioFailure(track)
                            } else {
                                forgetAudioFailure(track.id)
                            }
                        },
                    )
                    // Let foreground dispatchers run between independent tracks. Native inference
                    // is still serialized, but indexing no longer behaves like one unbroken CPU
                    // task.
                    yield()
                }
            }
            // Text is encoded here rather than at query time: the encoder costs milliseconds per track,
            // but a whole library of it would stall the first SMART press for seconds.
            //
            // Over ALL tracks, not just the ones needing audio: the two indexes fill independently,
            // so a library already embedded before text encoding existed still gets its vectors.
            for (track in tracks) {
                indexTextVector(track)
                yield()
            }
            // Persist dirty work even when this invocation added nothing. A previous save may have
            // failed after the vectors were installed in memory, so `indexed == 0` is not proof
            // that the durable snapshot is current.
            if (persistAfterBatch) {
                persistAudioIndex()
                persistTextIndex()
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

    override suspend fun retryFailedTracks(ids: List<TrackId>): Int = withContext(dispatcher) {
        mutex.withLock {
            if (mutableState.value !is EngineState.Ready) return@withLock 0
            var cleared = 0
            for (id in ids.toSet()) {
                if (audioFailureIdentities.remove(id) != null) {
                    audioIndexDirty = true
                    cleared++
                }
            }
            // Persist before returning so cancellation of the replacement indexing job cannot
            // resurrect the old marker on process restart.
            persistAudioIndex()
            cleared
        }
    }

    override suspend fun carryOverAudio(
        library: List<TrackDescriptor>,
        carryOvers: List<AudioCarryOver>,
    ): AudioCarryOverResult = withContext(dispatcher) {
        mutex.withLock {
            val none = AudioCarryOverResult(emptySet(), emptySet())
            if (carryOvers.isEmpty() || mutableState.value !is EngineState.Ready) return@withLock none
            val byId = library.associateBy { it.id }
            val applied = HashSet<TrackId>()
            val settled = HashSet<TrackId>()
            for (carry in carryOvers) {
                val track = byId[carry.trackId] ?: continue
                if (track.sourceRevision == carry.oldRevision) continue
                settled += carry.trackId
                // The identity the vector was made under, had the file kept its old revision.
                val before = track.copy(sourceRevision = carry.oldRevision).audioVectorIdentity()
                val unchanged = carry.trackId in index && audioVectorIdentities[carry.trackId] == before
                if (unchanged && track.sizeBytes == carry.newLength) {
                    audioVectorIdentities[carry.trackId] = track.audioVectorIdentity()
                    applied += carry.trackId
                }
            }
            if (applied.isNotEmpty()) {
                audioIndexDirty = true
                persistAudioIndex()
            }
            AudioCarryOverResult(applied, settled)
        }
    }

    override suspend fun synchronizeLibrary(
        library: List<TrackDescriptor>,
        pruneMissing: Boolean,
    ): Int =
        withContext(dispatcher) {
            mutex.withLock {
                if (mutableState.value !is EngineState.Ready) return@withLock 0
                val liveIds = library.mapTo(HashSet(library.size)) { it.id }
                val staleAudio = if (pruneMissing) {
                    index.entries().keys.filterNot(liveIds::contains)
                } else {
                    emptyList()
                }
                val staleText = if (pruneMissing) {
                    textIndex?.entries()?.keys?.filterNot(liveIds::contains).orEmpty()
                } else {
                    emptyList()
                }
                val staleAudioFailures = if (pruneMissing) {
                    audioFailureIdentities.keys.filterNot(liveIds::contains)
                } else {
                    emptyList()
                }

                // Compare against the identity persisted WITH each vector, not against
                // knownTracks: that map is intentionally empty after process death. Missing
                // identity means a legacy vector-only snapshot and is a conservative cache miss.
                val changedText = library.mapNotNull { track ->
                    track.id.takeIf { id ->
                        textIndex?.contains(id) == true &&
                            textVectorIdentities[id] != track.textVectorIdentity()
                    }
                }
                val changedAudio = library.mapNotNull { track ->
                    track.id.takeIf { id ->
                        id in index && audioVectorIdentities[id] != track.audioVectorIdentity()
                    }
                }
                val changedAudioFailures = library.mapNotNull { track ->
                    track.id.takeIf { id ->
                        audioFailureIdentities[id]?.let { it != track.audioFailureIdentity() } == true
                    }
                }

                staleAudio.forEach(index::remove)
                staleText.forEach { textIndex?.remove(it) }
                changedAudio.forEach(index::remove)
                changedText.forEach { textIndex?.remove(it) }
                (staleAudio + changedAudio).forEach(audioVectorIdentities::remove)
                (staleAudioFailures + changedAudioFailures).forEach(audioFailureIdentities::remove)
                (staleText + changedText).forEach(textVectorIdentities::remove)
                (staleAudio + changedAudio).forEach(semanticCache::remove)
                if (pruneMissing) knownTracks.keys.retainAll(liveIds)

                if (
                    staleAudio.isNotEmpty() || changedAudio.isNotEmpty() ||
                    staleAudioFailures.isNotEmpty() || changedAudioFailures.isNotEmpty()
                ) {
                    audioIndexDirty = true
                }
                if (staleText.isNotEmpty() || changedText.isNotEmpty()) textIndexDirty = true
                if (
                    staleAudio.isNotEmpty() ||
                    staleText.isNotEmpty() ||
                    changedAudio.isNotEmpty() ||
                    changedText.isNotEmpty()
                ) {
                    indexRevision++
                    snapshotCache = null
                }
                // Also retries an earlier failed save when reconciliation itself made no change.
                // Cache invalidation above precedes I/O because a failed save must not make the
                // already-mutated in-memory indexes appear to have their previous revision.
                persistAudioIndex()
                persistTextIndex()
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
            // Prefer the stored vector — no inference cost (and no model load) for indexed seeds.
            val seedVector = index.vector(seed.id)
                ?: run {
                    if (hasRememberedAudioFailure(seed)) {
                        return@withLock NextTrackResult.Failure(EngineError.InvalidAudio())
                    }
                    if (seed.audioUri.isNullOrBlank()) {
                        rememberAudioFailure(seed)
                        persistAudioIndex()
                        return@withLock NextTrackResult.Failure(
                            EngineError.InvalidAudio("No audio URI"),
                        )
                    }
                    ensureAudioModel().onFailure { throwable ->
                        return@withLock NextTrackResult.Failure(throwable.toEngineError())
                    }
                    backend.embed(seed).getOrElse { throwable ->
                        val error = throwable.toEngineError()
                        if (error is EngineError.InvalidAudio) {
                            rememberAudioFailure(seed)
                            persistAudioIndex()
                        }
                        return@withLock NextTrackResult.Failure(error)
                    }
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

    override suspend fun missingFromIndex(ids: List<TrackId>): Int = withContext(dispatcher) {
        mutex.withLock {
            ids.count { id ->
                id !in index && knownTracks[id]?.let { !hasRememberedAudioFailure(it) } != false
            }
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
                ).also { rememberMixCoverage(ids, it) }
            }
        }

    override suspend fun libraryMixCoverage(ids: List<TrackId>): LibraryVectorCoverage? =
        withContext(dispatcher) {
            mutex.withLock {
                if (mutableState.value !is EngineState.Ready) return@withLock null
                mixCoverageCache?.takeIf { it.revision == indexRevision && it.requestedIds == ids }
                    ?.let { return@withLock it.coverage }
                LibraryVectorFusion.coverageFromIndexes(
                    ids = ids,
                    audio = index,
                    metadata = textIndex,
                    audioDim = config.embeddingDim,
                    metadataDim = TextEncoder.TEXT_DIM,
                ).also { coverage ->
                    mixCoverageCache = MixCoverageCache(indexRevision, ids.toList(), coverage)
                }
            }
        }

    override suspend fun libraryMixFeatures(
        ids: List<TrackId>,
        loadMissingSemantics: Boolean,
    ): LibraryMixFeatures? =
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
                rememberMixCoverage(ids, vectorSpace)
                val requested = ids.distinct()
                val missing = requested.mapNotNull { id ->
                    if (id in semanticCache) return@mapNotNull null
                    index.vector(id)?.let { id to it }
                }
                // Semantic routing is optional decoration for library worlds. Never wake the
                // heavyweight audio model solely to rebuild a background Map/For You page: a
                // fully restored library (or one containing only remembered bad files) must keep
                // lazy loading intact. If real embedding/query work already loaded the model,
                // opportunistically fill the process cache on this or a later call.
                val shouldLoadSemantics = missing.isNotEmpty() &&
                    (loadMissingSemantics || audioModelLoaded)
                val semanticsAvailable = semanticModelLoaded ||
                    (shouldLoadSemantics && ensureSemanticModel().isSuccess)
                if (semanticsAvailable) cacheSemantics(missing)
                LibraryMixFeatures(
                    vectorSpace = vectorSpace,
                    semantics = requested.mapNotNull { id ->
                        semanticCache[id]?.let { id to it }
                    }.toMap(LinkedHashMap()),
                )
            }
        }

    override suspend fun ensureMetadataVectors(library: List<TrackDescriptor>): Int =
        ensureMetadataVectors(library, persistAfterBatch = true)

    override suspend fun stageMetadataVectors(library: List<TrackDescriptor>): Int =
        ensureMetadataVectors(library, persistAfterBatch = false)

    private suspend fun ensureMetadataVectors(
        library: List<TrackDescriptor>,
        persistAfterBatch: Boolean,
    ): Int = withContext(dispatcher) {
        mutex.withLock {
            if (mutableState.value !is EngineState.Ready) return@withLock 0
            rememberTracks(library)
            var added = 0
            for (track in library) {
                if (indexTextVector(track)) added++
                yield()
            }
            // Also retries metadata that was installed before an earlier save failure when
            // this is the ordinary durable operation. A staged scheduler checkpoints it with
            // persistPendingAnalysis instead.
            if (persistAfterBatch) persistTextIndex()
            added
        }
    }

    override suspend fun persistPendingAnalysis(): Unit = withContext(dispatcher) {
        mutex.withLock {
            // Dirty flags are cleared only after their store returns normally. If either save
            // fails, this (or any later durable operation) retries exactly the outstanding work.
            persistAudioIndex()
            persistTextIndex()
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
        companionGroups: List<Set<TrackId>>,
    ): List<TrackId> = withContext(dispatcher) {
        mutex.withLock {
            if (mutableState.value !is EngineState.Ready) return@withLock emptyList()
            rememberTracks(library)
            rememberTracks(listOf(seed))
            // Metadata is cheap enough to create on demand and gives first launch an honest local
            // result while the acoustic index is still cold.
            indexTextVector(seed)
            // In-memory vectors can serve playback even if their durable checkpoint fails.
            // Keep dirty flags intact so indexing or the next query retries the write.
            persistForPlayback("metadata", ::persistTextIndex)

            // First-launch indexing is progressive. Whichever track the listener actually picked
            // must still be a valid anchor even when its background batch has not reached it yet.
            if (index.vector(seed.id) == null) {
                if (hasRememberedAudioFailure(seed)) {
                    return@withLock metadataFallback(
                        seed, library, length, history, companionGroups,
                    )
                }
                if (seed.audioUri.isNullOrBlank()) {
                    rememberAudioFailure(seed)
                    persistForPlayback("audio", ::persistAudioIndex)
                    return@withLock metadataFallback(
                        seed, library, length, history, companionGroups,
                    )
                }
                val vector = ensureAudioModel().getOrNull()?.let {
                    backend.embed(seed).fold(
                        onSuccess = { it },
                        onFailure = { throwable ->
                            if (throwable.toEngineError() is EngineError.InvalidAudio) {
                                rememberAudioFailure(seed)
                                persistForPlayback("audio", ::persistAudioIndex)
                            }
                            null
                        },
                    )
                } ?: return@withLock metadataFallback(
                    seed, library, length, history, companionGroups,
                )
                if (validateAndUpsert(seed, vector) != null) {
                    return@withLock metadataFallback(
                        seed, library, length, history, companionGroups,
                    )
                }
                persistForPlayback("audio", ::persistAudioIndex)
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
                return@withLock metadataFallback(
                    seed, library, length, history, companionGroups,
                )
            }

            // The seed anchors every distance the walk measures, so it must be in the snapshot even
            // when the caller left it out — and callers reasonably do, since it is the one track
            // the queue must not repeat. The chain excludes it from its own output regardless.
            val snapshot = snapshotFor(history)
                ?: return@withLock metadataFallback(
                    seed, library, length, history, companionGroups,
                )
            val eligibleRows = BooleanArray(snapshot.size) { row ->
                snapshot.tracks[row].id in eligibleIds
            }
            val livePredictor = predictor.takeIf { predictorLoaded }
            println(
                "SMART: queue=${if (livePredictor != null) "audio-scorer" else "audio-geometry"} " +
                    "indexed=$indexedEligible/${eligibleIds.size}, required=$requiredAudio, " +
                    "text=${if (textEncoderLoaded) "ready" else "unavailable"}",
            )
            // A request seeded with the last track of a plan this engine answered continues that
            // walk (the app tops the queue up this way); any other seed starts a new one.
            val resume = if (config.continueAfterExhaustion && companionGroups.isEmpty()) {
                walks[seed.id]
            } else {
                null
            }
            // The judged scoring was measured on queues without marked playlists; theirs keep the shipped chain.
            val judged = config.judgedScoring && config.continueAfterExhaustion && companionGroups.isEmpty()
            val runPenalty = if (judged) artistRunPenalty else 0f
            val tuning = if (judged) {
                ChainTuning(
                    continueAfterExhaustion = true,
                    neighbourhoodBonus = 0f,
                    rerankWeights = Rerank.JUDGED_WEIGHTS,
                    soundFloor = Rerank.SOUND_FLOOR,
                    artistRunPenalty = runPenalty,
                )
            } else {
                ChainTuning(continueAfterExhaustion = config.continueAfterExhaustion)
            }
            val chain = SmartChain(
                snapshot,
                livePredictor,
                eligibleRows,
                typicalityWeight = config.typicalityWeight,
                companionGroups = companionGroups,
                tuning = tuning,
            ).build(
                seedId = seed.id,
                length = length,
                timeFeatures = clock.timeFeatures(),
                historyEvents = history,
                resume = resume,
            )
            // Marked playlists have positional quota turns; retain their planned order.
            // Otherwise bridge the selected tracks locally before handing the plan to playback; a
            // continued walk is bridged from the queue's last track, which plays right before it.
            val continuedFrom = if (resume != null) snapshot.rowOf(seed.id).takeIf { it >= 0 } else null
            val rows = if (companionGroups.isEmpty()) {
                JourneySequencer.order(snapshot, chain.rows, from = continuedFrom, sameArtistCost = runPenalty)
            } else {
                chain.rows
            }
            val ids = rows.map { snapshot.tracks[it].id }
            val walk = chain.walk
            if (walk != null && ids.isNotEmpty()) rememberWalk(ids.last(), walk)
            ids.ifEmpty {
                metadataFallback(seed, library, length, history, companionGroups)
            }
        }
    }

    /**
     * Continuation-mode walks by the last track of the plan that ended them, newest last. A few
     * entries, so an unrelated request in between (a diagnostic seed, a second queue) does not
     * break the walk a playing queue is following. In memory only: after a restart the next plan
     * starts a new walk.
     */
    private val walks = LinkedHashMap<TrackId, ChainWalk>()

    private fun rememberWalk(lastTrack: TrackId, walk: ChainWalk) {
        walks.remove(lastTrack)
        walks[lastTrack] = walk
        while (walks.size > WALKS_KEPT) walks.remove(walks.keys.first())
    }

    override suspend fun clearAnalysis() {
        withContext(dispatcher) {
            mutex.withLock {
                val audioSnapshot = currentAudioSnapshot()
                val metadataSnapshot = currentTextSnapshot()
                val failures = mutableListOf<Throwable>()

                // Both deletions are attempted even if the first fails. Memory stays live until
                // every durable snapshot has been deleted, so a caller never observes a cleared
                // engine while an old snapshot is still known to be recoverable on restart.
                runCatching { store.clear() }.onFailure(failures::add)
                textStore?.let { target ->
                    runCatching { target.clear() }.onFailure(failures::add)
                }
                if (failures.isNotEmpty()) {
                    // A successful deletion paired with a failed/ambiguous one would leave the two
                    // indexes at different logical generations. Restore both current snapshots on
                    // a best-effort basis before surfacing the failure; dirty flags retain any
                    // repair that still needs another save attempt in this process.
                    audioIndexDirty = true
                    runCatching {
                        store.saveSnapshot(config.modelVersion, audioSnapshot)
                    }.onSuccess {
                        audioIndexDirty = false
                    }.onFailure(failures::add)
                    if (textStore != null) {
                        textIndexDirty = true
                        runCatching {
                            textStore.saveSnapshot(TEXT_INDEX_VERSION, metadataSnapshot)
                        }.onSuccess {
                            textIndexDirty = false
                        }.onFailure(failures::add)
                    }
                    throwAnalysisClearFailure(failures)
                }

                index.clear()
                textIndex?.clear()
                knownTracks.clear()
                audioVectorIdentities.clear()
                audioFailureIdentities.clear()
                textVectorIdentities.clear()
                audioIndexDirty = false
                textIndexDirty = false
                semanticCache.clear()
                mixCoverageCache = null
                snapshotCache = null
                indexRevision++
                walks.clear()
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
                audioVectorIdentities.clear()
                audioFailureIdentities.clear()
                textVectorIdentities.clear()
                audioIndexDirty = false
                textIndexDirty = false
                semanticCache.clear()
                mixCoverageCache = null
                snapshotCache = null
                indexRevision++
                walks.clear()
                predictorLoaded = false
                textEncoderLoaded = false
                audioModelLoaded = false
                semanticModelLoaded = false
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
        val persisted = runCatching { store.loadSnapshot(config.modelVersion) }.getOrNull() ?: return
        for ((id, vector) in persisted.entries) {
            runCatching { index.upsert(id, vector) }
                .onSuccess {
                    persisted.identities[id]?.let { identity ->
                        audioVectorIdentities[id] = identity
                    }
                }
        }
        for ((id, identity) in persisted.failedIdentities) {
            if (id !in index && identity.isNotBlank()) {
                audioFailureIdentities[id] = identity
            }
        }
        if (persisted.entries.isNotEmpty()) indexRevision++
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
        val metadata = track.textString()
        if (metadata.isBlank()) return false
        val vector = runCatching { encoder.encode(metadata) }.getOrNull() ?: return false
        if (vector.size != TextEncoder.TEXT_DIM) return false
        return runCatching { target.upsert(track.id, vector) }
            .onSuccess {
                textVectorIdentities[track.id] = track.textVectorIdentity()
                textIndexDirty = true
                indexRevision++
                snapshotCache = null
            }
            .isSuccess
    }

    /** Returns `null` on success, or the typed rejection reason. */
    private fun validateAndUpsert(track: TrackDescriptor, vector: FloatArray): EngineError? {
        val id = track.id
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
        audioVectorIdentities[id] = track.audioVectorIdentity()
        audioFailureIdentities.remove(id)
        audioIndexDirty = true
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

    /**
     * Collision-safe canonical identity. Values are length-prefixed, so an opaque URI/title may
     * contain any delimiter without making two different descriptors serialize alike.
     */
    private fun vectorIdentity(version: String, vararg fields: String?): String = buildString {
        append(version)
        for (field in fields) {
            append('|')
            if (field == null) {
                append("-1:")
            } else {
                append(field.length)
                append(':')
                append(field)
            }
        }
    }

    private fun TrackDescriptor.audioVectorIdentity(): String = vectorIdentity(
        AUDIO_IDENTITY_VERSION,
        audioUri,
        durationMs?.toString(),
        sourceRevision,
    )

    private fun TrackDescriptor.audioFailureIdentity(): String = vectorIdentity(
        AUDIO_FAILURE_IDENTITY_VERSION,
        audioUri,
        durationMs?.toString(),
        sourceRevision,
    )

    /** The trusted string the text encoder embeds for this track, with the pack's facts about its artist. */
    private fun TrackDescriptor.textString(): String {
        val facts = artistKnowledge?.facts(artist, artists.firstOrNull())
        return TextEncoder.metadataString(
            genre = genre,
            artist = artist,
            title = title,
            year = year,
            originalYear = originalYear,
            language = language,
            artistLanguage = facts?.language,
            artistDecade = facts?.decade,
        )
    }

    /** The string itself: a vector is current exactly when it encodes what the track would encode now. */
    private fun TrackDescriptor.textVectorIdentity(): String = vectorIdentity(TEXT_IDENTITY_VERSION, textString())

    private fun hasRememberedAudioFailure(track: TrackDescriptor): Boolean =
        audioFailureIdentities[track.id] == track.audioFailureIdentity()

    private fun rememberAudioFailure(track: TrackDescriptor) {
        val identity = track.audioFailureIdentity()
        if (audioFailureIdentities.put(track.id, identity) != identity) {
            audioIndexDirty = true
        }
    }

    private fun forgetAudioFailure(id: TrackId) {
        if (audioFailureIdentities.remove(id) != null) audioIndexDirty = true
    }

    private suspend fun persistAudioIndex() {
        if (!audioIndexDirty) return
        store.saveSnapshot(config.modelVersion, currentAudioSnapshot())
        audioIndexDirty = false
    }

    private suspend fun persistTextIndex() {
        if (!textIndexDirty) return
        val targetStore = textStore
        if (targetStore == null) {
            // This engine was intentionally configured without metadata persistence.
            textIndexDirty = false
            return
        }
        targetStore.saveSnapshot(TEXT_INDEX_VERSION, currentTextSnapshot())
        textIndexDirty = false
    }

    /** Query checkpoint failures must not turn a usable in-memory index into silence. */
    private suspend fun persistForPlayback(kind: String, save: suspend () -> Unit) {
        try {
            save()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            println("SMART: $kind checkpoint pending retry (${failure::class.simpleName})")
        }
    }

    private fun currentAudioSnapshot(): StoredIndexSnapshot {
        val entries = index.entries()
        return StoredIndexSnapshot(
            entries = entries,
            identities = audioVectorIdentities.filterKeys(entries::containsKey),
            failedIdentities = audioFailureIdentities.filterKeys { it !in entries },
        )
    }

    private fun currentTextSnapshot(): StoredIndexSnapshot {
        val entries = textIndex?.entries().orEmpty()
        return StoredIndexSnapshot(
            entries = entries,
            identities = textVectorIdentities.filterKeys(entries::containsKey),
        )
    }

    private fun throwAnalysisClearFailure(failures: List<Throwable>): Nothing {
        val failure = IllegalStateException(
            "Could not clear every SMART snapshot; in-memory analysis was retained",
            failures.first(),
        )
        failures.drop(1).forEach(failure::addSuppressed)
        throw failure
    }

    private suspend fun snapshotFor(history: List<SmartHistoryEvent>): SmartSnapshot? {
        snapshotCache?.takeIf { it.revision == indexRevision && it.semanticCount == semanticCache.size }
            ?.let { return it.snapshot }

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
        val snapshot = SmartSnapshot.build(withEnergy(rows)) ?: return null
        snapshotCache = SnapshotCache(indexRevision, semanticCache.size, snapshot)
        return snapshot
    }

    /**
     * Fills the chain's energy-smoothness input, which nothing else supplies: the semantic head's
     * energy score, ranked within these rows (see [applyLibraryEnergy]). Rows the head has not seen
     * are classified only when it is already loaded, because a queue must never wait on loading a
     * model for a sequencing hint. A row without semantics keeps NaN, which the term ignores.
     */
    private suspend fun withEnergy(rows: List<SmartTrack>): List<SmartTrack> {
        if (semanticModelLoaded) {
            cacheSemantics(rows.mapNotNull { row -> if (row.id in semanticCache) null else row.id to row.audio })
        }
        return applyLibraryEnergy(rows, semanticCache)
    }

    /** Classifies [missing] audio vectors into [semanticCache]; rows the head rejects stay absent. */
    private suspend fun cacheSemantics(missing: List<Pair<TrackId, FloatArray>>) {
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
    }

    private fun TrackDescriptor.toSmartTrack(audio: FloatArray): SmartTrack = SmartTrack(
        id = id,
        audio = audio,
        text = textIndex?.vector(id),
        descriptor = artistKnowledge?.descriptor(artist, artists.firstOrNull(), textIndex?.vector(id)),
        energy = energy ?: Float.NaN,
        meta = TrackMeta(
            title, artist, album, genre, year, durationMs,
            primaryArtist = artists.firstOrNull(),
            originalYear = originalYear,
        ),
    )

    private fun metadataFallback(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        length: Int,
        history: List<SmartHistoryEvent>,
        companionGroups: List<Set<TrackId>>,
    ): List<TrackId> = MetadataFallbackQueue.build(
        seed,
        library,
        length,
        textIndex,
        history,
        companionGroups,
    )

    /** [semanticCount] rebuilds the snapshot once more tracks have semantics, and so energy. */
    private data class SnapshotCache(
        val revision: Long,
        val semanticCount: Int,
        val snapshot: SmartSnapshot,
    )

    /** Keep the small coverage identity, never the one-shot matrix consumed by clustering. */
    private fun rememberMixCoverage(ids: List<TrackId>, space: LibraryVectorSpace?) {
        mixCoverageCache = MixCoverageCache(
            revision = indexRevision,
            requestedIds = ids.toList(),
            coverage = space?.let {
                LibraryVectorCoverage(it.trackIds, it.source, it.fingerprint)
            },
        )
    }

    /** Warm Map/For You visits compare IDs instead of copying and hashing every embedding. */
    private data class MixCoverageCache(
        val revision: Long,
        val requestedIds: List<TrackId>,
        val coverage: LibraryVectorCoverage?,
    )

    private fun Throwable.toEngineError(): EngineError =
        (this as? SmartEngineException)?.error
            ?: EngineError.BackendFailure(message ?: "Unknown backend failure", this)

    private companion object {
        /** Maximum warm-up requirement for large libraries. */
        const val MIN_AUDIO_CORPUS = 64

        /** Continuation walks kept for requests that continue a plan; see [walks]. */
        const val WALKS_KEPT = 4

        /** Below this, the metadata-only full-library path is more honest than a tiny audio pool. */
        const val MIN_AUDIO_CORPUS_FLOOR = 24

        const val SEMANTIC_BATCH_SIZE = 128

        const val AUDIO_IDENTITY_VERSION = "audio-v1"
        // v2 deliberately invalidates markers written before transient and deterministic audio
        // failures were separated. Existing vectors keep AUDIO_IDENTITY_VERSION and remain warm.
        const val AUDIO_FAILURE_IDENTITY_VERSION = "audio-failure-v2"
        /**
         * v2: the trusted string gained the original year and a language word. v3: the identity is the
         * encoded string, which can now carry the knowledge pack's language and decade, so a pack update
         * re-encodes exactly the tracks whose string it changes.
         */
        const val TEXT_IDENTITY_VERSION = "text-v3"

        /** The warm-up floor cannot exceed the remaining library, including its seed. */
        fun requiredAudioCorpus(librarySize: Int): Int {
            val coverageTarget = (librarySize * 4 + 4) / 5
            return maxOf(MIN_AUDIO_CORPUS_FLOOR, coverageTarget)
                .coerceAtMost(minOf(MIN_AUDIO_CORPUS, librarySize))
        }
    }
}
