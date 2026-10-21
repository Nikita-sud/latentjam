/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.HistoryStore
import io.github.nikitasud.latentjam.history.SmartImpressionLog
import io.github.nikitasud.latentjam.history.SmartLogStore
import io.github.nikitasud.latentjam.history.SmartPressStore
import io.github.nikitasud.latentjam.history.SmartPresses
import io.github.nikitasud.latentjam.library.DefaultPlaylists
import io.github.nikitasud.latentjam.library.PlaylistStore
import io.github.nikitasud.latentjam.smart.InMemoryVectorIndex
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.AUDIO_MODEL_VERSION
import io.github.nikitasud.latentjam.smart.readIndexSnapshotFile
import io.github.nikitasud.latentjam.smart.ranker.AudioSource
import io.github.nikitasud.latentjam.smart.ranker.SmartRankerAssets
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import io.github.nikitasud.latentjam.smart.text.MusicEntityResolver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * The offline PRODUCT-CHECK stand (tools/diagnostics/smart_stand.py drives it): the phone's own chooser and ranker,
 * with the assets this build ships, planning queues from a copy of the owner's phone state. L1 product checks only —
 * what the owner would hear; never input to fitting, choosing or gates. Runs only when LJ_STAND_DIR is set (the owner's
 * copy stays on this machine; nothing of it is in the repository).
 *
 * LJ_STAND_DIR holds library.tsv, seeds.tsv and files/ (the copied private files); LJ_STAND_OUT the output directory.
 * Each seed: CORE and FULL, three presses ten minutes apart (the re-press chain within the freshness window), each a
 * [HOPS]-row queue topped up as the playback controllers do (the last row seeds, the rows before it are the recent
 * window). Every seed starts from an empty impression log at the same simulated time, one minute after the copied
 * history's last listen, so the seeds do not see each other.
 */
class SmartStandRunner {
    @Test
    fun plansTheStandQueues() {
        val dir = System.getenv("LJ_STAND_DIR")?.let(::File) ?: return
        val out = File(checkNotNull(System.getenv("LJ_STAND_OUT")) { "LJ_STAND_OUT" }).also { it.mkdirs() }
        runBlocking { run(dir, out) }
    }

    private suspend fun run(dir: File, out: File) {
        val ml = File("../androidApp/src/main/assets/ml")
        fun read(name: String) = File(ml, name).takeIf { it.isFile }?.readBytes()?.let(AssetBytes::of)
        // The library as the app hands it to SMART: the MediaStore scan, upgraded by GenreEnrichment's stored tag
        // facts (full genre list, credited artists, original year, language, album artist).
        val payload = File(dir, "track_genres.txt").takeIf { it.isFile }?.readText()
        val settings = java.lang.reflect.Proxy.newProxyInstance(
            AppSettings::class.java.classLoader, arrayOf(AppSettings::class.java),
        ) { _, method, _ ->
            if (method.name == "readTrackGenresPayload") payload else error("stand: unexpected settings call ${method.name}")
        } as AppSettings
        val library = GenreEnrichment(settings) { null }.apply(File(dir, "library.tsv").readLines().filter(String::isNotEmpty).map(::track))
        val byId = library.associateBy { it.id }
        val seeds = File(dir, "seeds.tsv").readLines().map(String::trim).filter(String::isNotEmpty).mapNotNull { byId[TrackId(it)] }
            // LJ_STAND_SEEDS=n: the first n of the fixed seed set (spot checks).
            .let { all -> System.getenv("LJ_STAND_SEEDS")?.toInt()?.let(all::take) ?: all }
        val files = File(dir, "files")

        // The phone's audio index, e cache and their order, as the engine restores them.
        val vectors = checkNotNull(readIndexSnapshotFile(File(files, "smart_index.bin"), AUDIO_MODEL_VERSION)) { "smart_index.bin" }
        val index = InMemoryVectorIndex(vectors.entries.values.first().size)
        for ((id, v) in vectors.entries) index.adopt(id, v.copyOf())
        val energies = readIndexSnapshotFile(File(files, "smart_energy.bin"), "semantic-energy-v1-$AUDIO_MODEL_VERSION")
            ?.entries?.mapValues { it.value[0].toDouble() }.orEmpty()
        val audio = object : AudioSource {
            override suspend fun vector(id: TrackId): FloatArray? = index.vector(id)
            override suspend fun neighbours(id: TrackId, k: Int): List<TrackId> =
                index.vector(id)?.let { v -> index.nearest(v, k, exclude = setOf(id)).map { it.trackId } }.orEmpty()
            override suspend fun energy(id: TrackId): Double? = energies[id]
        }
        val knowledge = ArtistKnowledge(
            MusicEntityResolver { read("music_entities_250k.bin") },
            loadAdapter = { File(ml, "artist_adapter.bin").takeIf { it.isFile }?.readBytes() },
        ) { read("artist_knowledge.bin") }
        // LJ_STAND_AS2=1: artist_sets.py's as2 rules (audit 07 #4 / #7 / #10), to preview them before the FIT rebuild.
        val assets = SmartRankerAssets(knowledge, ::read, audio = audio, artistSetsAs2 = System.getenv("LJ_STAND_AS2") == "1")
        val ranker = assets.ranker
        check(assets.problem == null) { "ranker assets: ${assets.problem}" }
        ranker.backfillImputations(library)
        // One warm plan resolves every library track (family, imputation): the facts the seed set is stratified by.
        ranker.plan(io.github.nikitasud.latentjam.smart.ranker.PlanRequest(library[0], library, library, 1))
        File(out, "facts.tsv").writeText(buildString {
            append("id\tfamily\te\thasVector\n")
            for (t in library) {
                append(t.id.value).append('\t').append(ranker.familyOf(t.id).orEmpty()).append('\t')
                    .append(energies[t.id]?.toString().orEmpty()).append('\t').append(index.contains(t.id)).append('\n')
            }
        })

        val historyLines = File(files, "listening_history.log").readLines().filter(String::isNotEmpty)
        val history = DefaultListeningHistory(object : HistoryStore {
            override suspend fun append(line: String) = Unit
            override suspend fun readAll(): List<String> = historyLines
            override suspend fun clear() = Unit
        })
        val lastListen = history.recentEvents(1).firstOrNull()?.startedAtMs ?: 0L
        val favourites = File(files, "favorites.txt").takeIf { it.isFile }?.readLines()
            ?.map(String::trim)?.filter(String::isNotEmpty)?.associate { TrackId(it) to null as Long? }.orEmpty()
        // Fast 3.3: the copy's own playlists reach FULL plans as on the phone (LJ_STAND_PLAYLISTS=0 leaves them out).
        val playlistLines = File(files, "playlists.txt").takeIf { it.isFile && System.getenv("LJ_STAND_PLAYLISTS") != "0" }?.readLines().orEmpty()
        val userPlaylists = DefaultPlaylists(object : PlaylistStore {
            override suspend fun read(): List<String> = playlistLines
            override suspend fun write(lines: List<String>) = Unit
        }).all().smartUserPlaylists()
        println("SMART stand: ${userPlaylists.size} playlists")
        // The artist-variety setting as the ranker sees it (LJ_STAND_VARIETY, default 1 = the tuned values).
        val varietyScale = System.getenv("LJ_STAND_VARIETY")?.toDouble() ?: 1.0

        System.getenv("LJ_STAND_PROBE")?.let { spec ->
            probe(spec, byId, library, ranker, historyLines, favourites, File(out, "probe.jsonl"))
            return
        }
        System.getenv("LJ_STAND_REPLAY")?.let { specs ->
            for ((i, spec) in specs.split(',').withIndex()) {
                replay(spec, byId, library, ranker, assets, historyLines, favourites, userPlaylists, files, File(out, "replay-$i.txt"))
            }
            return
        }
        // The v0.9.1-equivalent engine: the learned ranker off, the judged chain of the phone's engine (LJ_STAND_ENGINE=chain).
        val chain = System.getenv("LJ_STAND_ENGINE") == "chain"
        val chainEngine = if (chain) chainEngine(dir, files, knowledge, library) else null
        val queues = StringBuilder()
        for (seed in seeds) {
            for (full in listOf(false, true)) {
                var now = lastListen + 60_000
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val plans = HashMap<TrackId, Pair<String, String>>()
                var queueModel = ""
                val recorder = SmartLogRecorder(SmartImpressionLog(MemoryLog()), scope, clock = { now })
                val log = object : SmartPlanLog by recorder {
                    override suspend fun planned(queue: String, plan: Int, rankOffset: Int, model: String, rows: List<SmartPlanRow>) {
                        if (plan == 0) queueModel = model
                        for (r in rows) plans[r.trackId] = r.source to "$plan"
                        recorder.planned(queue, plan, rankOffset, model, rows)
                    }
                }
                if (chain && full) continue
                val chooser = EngineNextTrackChooser(
                    engine = chainEngine ?: NoEngine.instance, history = history, library = { library },
                    useRanker = { !chain }, ranker = { ranker }, rankerHealthy = { assets.healthy },
                    learnFromHabits = { full && !chain }, personalRanker = { full },
                    presses = SmartPresses(MemoryPresses()), smartLog = log,
                    favourites = { favourites }, useFavourites = { true }, clock = { now }, userPlaylists = { userPlaylists },
                    varietyScale = { varietyScale },
                )
                for (press in 0 until PRESSES) {
                    val queue = mutableListOf(seed)
                    val picks = ArrayList<String>()
                    repeat(HOPS) {
                        val queued = queue.mapTo(HashSet()) { it.id }
                        val choice = chooser.choose(
                            queue.last(), queue.dropLast(1).takeLast(10).map { it.id }, library.filter { it.id !in queued },
                        ) ?: return@repeat
                        queue += choice.track
                        val (source, plan) = plans[choice.track.id] ?: ("?" to "?")
                        picks += "{\"id\":${q(choice.track.id.value)},\"pos\":${choice.planPosition ?: 0},\"source\":${q(source)},\"plan\":$plan}"
                    }
                    queues.append("{\"seed\":${q(seed.id.value)},\"mode\":\"${if (full) "FULL" else "CORE"}\",\"press\":$press,")
                        .append("\"model\":${q(queueModel)},\"picks\":[").append(picks.joinToString(",")).append("]}\n")
                    now += PRESS_GAP_MS
                }
                scope.cancel()
            }
        }
        File(out, "queues.jsonl").writeText(queues.toString())
        File(out, "build.txt").writeText(
            "model ${ranker.modelName}\nfull ${ranker.fullModelName}\nencoder $AUDIO_MODEL_VERSION\n" +
                "library ${library.size}\nvectors ${vectors.entries.size}\nenergies ${energies.size}\nseeds ${seeds.size}\n",
        )
        println("stand: ${seeds.size} seeds -> $out")
    }

    /**
     * L1 mechanism probe ("seedId:full"): one press planned at the stand's clock with every pool row's terms at every
     * hop (PlanDiagnostics), each row's source, pack language and family. Product check only.
     */
    private suspend fun probe(
        spec: String, byId: Map<TrackId, TrackDescriptor>, library: List<TrackDescriptor>,
        ranker: io.github.nikitasud.latentjam.smart.ranker.SmartRanker,
        historyLines: List<String>, favourites: Map<TrackId, Long?>, target: File,
    ) {
        val (seedId, fullText) = spec.split(':')
        val seed = checkNotNull(byId[TrackId(seedId)]) { "probe seed $seedId" }
        val full = fullText == "1"
        val history = DefaultListeningHistory(object : HistoryStore {
            override suspend fun append(line: String) = Unit
            override suspend fun readAll(): List<String> = historyLines
            override suspend fun clear() = Unit
        })
        val events = history.recentEvents(10_000)
        val start = (events.firstOrNull()?.startedAtMs ?: 0L) + 60_000
        val ids = library.mapTo(HashSet()) { it.id }
        val listens = habitListens(events.asReversed(), ids, start)
        val artistKeys = library.associate { it.id to io.github.nikitasud.latentjam.smart.HabitSlots.artistKey(it) }
        val personal = io.github.nikitasud.latentjam.smart.ranker.PersonalHistory(listens, start, if (full) favourites else emptyMap()) { artistKeys[it].orEmpty() }
        val d = io.github.nikitasud.latentjam.smart.ranker.PlanDiagnostics(topK = 100_000)
        val report = ranker.plan(
            io.github.nikitasud.latentjam.smart.ranker.PlanRequest(
                seed = seed, library = library, candidates = library.filter { it.id != seed.id }, length = 20,
                exclude = io.github.nikitasud.latentjam.smart.HabitSlots.novelty(listens, start), press = 0, history = personal,
                personal = full, seedMs = start,
            ),
            d,
        )
        fun q(s: String?) = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun n(x: Double) = if (x.isFinite()) x.toString() else "null"
        val sb = StringBuilder()
        sb.append("{\"seed\":${q(seed.id.value)},\"model\":${q(d.model)},\"features\":[${d.features.joinToString(",") { q(it) }}],")
            .append("\"rowNames\":[${io.github.nikitasud.latentjam.smart.ranker.RankerFeatures.NAMES.joinToString(",") { q(it) }}],")
            .append("\"seedLanguage\":${q(ranker.languageOf(seed.id))},\"seedFamily\":${q(ranker.familyOf(seed.id))},")
            .append("\"picks\":[${report.ids.joinToString(",") { q(it.value) }}],\"sources\":{${d.sourceCounts.entries.joinToString(",") { (k, v) -> "${q(k)}:$v" }}}}\n")
        for (h in d.hops.take(5)) for (e in h.entries) {
            sb.append("{\"hop\":${h.hop},\"pickOfHop\":${e.id == h.pick},\"id\":${q(e.id.value)},\"s\":${n(e.s)},\"t\":${n(e.t)},")
                .append("\"source\":${q(e.source)},\"lang\":${q(ranker.languageOf(e.id))},\"family\":${q(ranker.familyOf(e.id))},")
                .append("\"penalty\":${n(e.penalty)},\"reentry\":${n(e.reentry)},")
                .append("\"terms\":${e.terms?.joinToString(",", "[", "]") { n(it) } ?: "null"},")
                .append("\"contrib\":[${e.contributions.joinToString(",") { n(it) }}],\"row\":[${e.seedRow.joinToString(",") { n(it) }}]}\n")
        }
        target.writeText(sb.toString())
    }

    /**
     * Fidelity check: one logged phone press ("seedId:startMs:press:full") re-planned with the phone's impression log,
     * history and press count as they were at that moment; the picks go to replay.txt for comparison with the log.
     */
    private suspend fun replay(
        spec: String, byId: Map<TrackId, TrackDescriptor>, library: List<TrackDescriptor>,
        ranker: io.github.nikitasud.latentjam.smart.ranker.SmartRanker, assets: SmartRankerAssets,
        historyLines: List<String>, favourites: Map<TrackId, Long?>, userPlaylists: List<List<TrackId>>, files: File, target: File,
    ) {
        val (seedId, startText, pressText, fullText) = spec.split(':')
        val start = startText.toLong()
        val seed = checkNotNull(byId[TrackId(seedId)]) { "replay seed $seedId" }
        val earlier = historyLines.filter { line -> line.split('|').getOrNull(2)?.toLongOrNull()?.let { it < start } ?: true }
        val history = DefaultListeningHistory(object : HistoryStore {
            override suspend fun append(line: String) = Unit
            override suspend fun readAll(): List<String> = earlier
            override suspend fun clear() = Unit
        })
        val logLines = File(files, "smart_impressions.log").readLines().filter { line ->
            line.split('|').getOrNull(1)?.toLongOrNull()?.let { it < start } ?: false
        }
        val store = MemoryLog().also { m -> m.append(logLines.joinToString("\n", postfix = "\n")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = SmartLogRecorder(SmartImpressionLog(store), scope, clock = { start })
        val key = io.github.nikitasud.latentjam.smart.ranker.SaltedKey.contentKey(seed)
        val presses = MemoryPresses().also {
            it.write(listOf("p1|" + key.encodeToByteArray().joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') } + "|" + pressText))
        }
        val full = fullText == "1"
        val chooser = EngineNextTrackChooser(
            engine = NoEngine.instance, history = history, library = { library },
            useRanker = { true }, ranker = { ranker }, rankerHealthy = { assets.healthy },
            learnFromHabits = { full }, personalRanker = { full },
            presses = SmartPresses(presses), smartLog = recorder,
            favourites = { favourites }, useFavourites = { true }, clock = { start }, userPlaylists = { userPlaylists },
        )
        val queue = mutableListOf(seed)
        repeat(20) {
            val queued = queue.mapTo(HashSet()) { it.id }
            val choice = chooser.choose(queue.last(), queue.dropLast(1).takeLast(10).map { it.id }, library.filter { it.id !in queued })
                ?: return@repeat
            queue += choice.track
        }
        scope.cancel()
        target.writeText(queue.drop(1).joinToString("\n") { it.id.value } + "\n")
    }

    /**
     * The phone's DefaultSimilarityEngine with the app's chain config (judged scoring, default artist variety), restored
     * from the copied audio / text indexes and e cache. The audio encoder never runs (every library track is indexed);
     * the semantic head's outputs come from semantics.tsv, computed from the same vectors by tools/diagnostics.
     */
    private suspend fun chainEngine(
        dir: File, files: File, knowledge: ArtistKnowledge, library: List<TrackDescriptor>,
    ): io.github.nikitasud.latentjam.smart.SimilarityEngine {
        fun store(name: String) = object : io.github.nikitasud.latentjam.smart.IndexStore {
            override suspend fun load(modelVersion: String) = loadSnapshot(modelVersion)?.entries
            override suspend fun loadSnapshot(modelVersion: String) = readIndexSnapshotFile(File(files, name), modelVersion)
            override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) = Unit
            override suspend fun saveSnapshot(modelVersion: String, snapshot: io.github.nikitasud.latentjam.smart.StoredIndexSnapshot) = Unit
            override suspend fun clear() = Unit
        }
        val semantics = File(dir, "semantics.tsv").readLines().filter(String::isNotEmpty).associate { l ->
            val f = l.split('\t')
            f[0] to FloatArray(f.size - 1) { f[it + 1].toFloat() }
        }
        val vectors = checkNotNull(readIndexSnapshotFile(File(files, "smart_index.bin"), AUDIO_MODEL_VERSION))
        // The engine hands the head its stored (unit) vectors: matched back to the track by the vector's bits.
        val byVector = HashMap<List<Int>, String>()
        val probe = InMemoryVectorIndex(vectors.entries.values.first().size)
        for ((id, v) in vectors.entries) { probe.adopt(id, v.copyOf()); byVector[probe.vector(id)!!.map { it.toRawBits() }] = id.value }
        val backend = object : io.github.nikitasud.latentjam.smart.EmbeddingBackend {
            override suspend fun loadModel(): Result<Unit> = Result.success(Unit)
            override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> = Result.failure(IllegalStateException("stand: no encoder"))
            override suspend fun classify(embeddings: List<FloatArray>): Result<List<FloatArray>> = runCatching {
                embeddings.map { e -> checkNotNull(semantics[byVector[e.map { it.toRawBits() }]]) { "stand: no semantics for a vector" } }
            }
            override fun close() = Unit
        }
        val config = io.github.nikitasud.latentjam.smart.SmartEngineConfig(
            embeddingDim = 960, modelLocator = "ml/mnv4_audio.onnx", modelVersion = AUDIO_MODEL_VERSION, typicalityWeight = 0f,
            continueAfterExhaustion = true, judgedScoring = true,
            artistRunPenalty = ARTIST_VARIETY_PENALTIES[DEFAULT_ARTIST_VARIETY],
        )
        val engine = io.github.nikitasud.latentjam.smart.createHostSimilarityEngine(
            config, backend, store("smart_index.bin"), store("smart_text_index.bin"), store("smart_energy.bin"), knowledge,
        )
        engine.initialize().getOrThrow()
        engine.synchronizeLibrary(library = library, pruneMissing = false)
        return engine
    }

    private fun q(s: String?) = if (s == null) "null" else buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun track(line: String): TrackDescriptor {
        val f = line.split('\t').map { it.ifEmpty { null } }
        return TrackDescriptor(
            id = TrackId(f[0]!!), title = f[1], artist = f[2], album = f[3], albumArtist = f[4], genre = f[5],
            durationMs = f[6]?.toLongOrNull(), year = f[7]?.toIntOrNull(), sizeBytes = f[8]?.toLongOrNull(),
            folderPath = f[9], fileName = f[10], storageVolume = f[11], sourceRevision = f.getOrNull(12),
            audioUri = "content://media/external/audio/media/${f[0]}",
        )
    }

    /** The ranker path never asks the engine (the chain path would): every call answers empty. */
    private object NoEngine {
        val instance: io.github.nikitasud.latentjam.smart.SimilarityEngine = java.lang.reflect.Proxy.newProxyInstance(
            io.github.nikitasud.latentjam.smart.SimilarityEngine::class.java.classLoader,
            arrayOf(io.github.nikitasud.latentjam.smart.SimilarityEngine::class.java),
        ) { _, method, _ -> error("stand: unexpected engine call ${method.name}") } as io.github.nikitasud.latentjam.smart.SimilarityEngine
    }

    private class MemoryLog : SmartLogStore {
        private val text = StringBuilder()
        override suspend fun append(text: String) { this.text.append(text) }
        override suspend fun size(): Long = text.length.toLong()
        override suspend fun keepTail(maxBytes: Long) = Unit
        override suspend fun forEachLine(line: (String) -> Unit) = text.lines().filter(String::isNotEmpty).forEach(line)
        override suspend fun clear() { text.clear() }
    }

    private class MemoryPresses : SmartPressStore {
        private var lines = emptyList<String>()
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) { this.lines = lines }
    }

    private companion object {
        const val HOPS = 40
        const val PRESSES = 3
        const val PRESS_GAP_MS = 10L * 60_000
    }
}
