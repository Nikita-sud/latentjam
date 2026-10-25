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
        val dirs = System.getenv("LJ_STAND_DIR")?.split(':')?.filter(String::isNotEmpty)?.map(::File) ?: return
        val outBase = File(checkNotNull(System.getenv("LJ_STAND_OUT")) { "LJ_STAND_OUT" })
        // General stand: several input dirs (colon-separated) -> LJ_STAND_OUT/<dir name> each.
        for (dir in dirs) {
            val out = (if (dirs.size > 1) File(outBase, dir.name) else outBase).also { it.mkdirs() }
            runBlocking { run(dir, out) }
        }
    }

    private suspend fun run(dir: File, out: File) {
        val ml = File("../androidApp/src/main/assets/ml")
        fun read(name: String) = File(ml, name).takeIf { it.isFile }?.readBytes()?.let(AssetBytes::of)
        // The library as the app hands it to SMART: the MediaStore scan, upgraded by GenreEnrichment's stored tag
        // facts (full genre list, credited artists, original year, language, album artist).
        val payload = File(dir, "track_genres.txt").takeIf { it.isFile }?.readText()
        val settings = java.lang.reflect.Proxy.newProxyInstance(
            AppSettings::class.java.classLoader, arrayOf(AppSettings::class.java),
        ) { _, method, args ->
            @Suppress("UNCHECKED_CAST")
            when (method.name) {
                "readTrackGenresPayload" -> payload
                // The line-at-a-time read (the genre cache on storage): the same payload, its lines.
                "readTrackGenreLines" -> payload?.let { (args[0] as (Sequence<String>) -> Any?)(it.lineSequence()) }
                else -> error("stand: unexpected settings call ${method.name}")
            }
        } as AppSettings
        val library = GenreEnrichment(settings) { null }.apply(File(dir, "library.tsv").readLines().filter(String::isNotEmpty).map(::track))
        val byId = library.associateBy { it.id }
        // LJ_STAND_SEEDS_DIR: the seeds come from <that dir>/<library folder name>.tsv instead of the library's seeds.tsv.
        val seedFile = System.getenv("LJ_STAND_SEEDS_DIR")?.let { File(it, dir.name + ".tsv") } ?: File(dir, "seeds.tsv")
        val seeds = seedFile.readLines().map(String::trim).filter(String::isNotEmpty).mapNotNull { byId[TrackId(it)] }
            // LJ_STAND_SEEDS=n: the first n of the fixed seed set (spot checks).
            .let { all -> System.getenv("LJ_STAND_SEEDS")?.toInt()?.let(all::take) ?: all }
        val files = File(dir, "files")

        // The phone's audio index, e cache and their order, as the engine restores them.
        val vectors = checkNotNull(readIndexSnapshotFile(versioned(files, "smart_index", AUDIO_MODEL_VERSION), AUDIO_MODEL_VERSION)) { "smart_index" }
        val index = InMemoryVectorIndex(vectors.entries.values.first().size)
        for ((id, v) in vectors.entries) index.adopt(id, v.copyOf())
        val energies = readIndexSnapshotFile(versioned(files, "smart_energy", "semantic-energy-v1-$AUDIO_MODEL_VERSION"), "semantic-energy-v1-$AUDIO_MODEL_VERSION")
            ?.entries?.mapValues { it.value[0].toDouble() }.orEmpty()
        // The stored metadata-text vectors (the engine's text index), for the cold fix's semChain.
        val texts = readIndexSnapshotFile(File(files, "smart_text_index.bin"), "student-s256x3m-emb4-int8-trusted-v1")?.entries.orEmpty()
        println("SMART stand: ${texts.size} text vectors")
        val audio = object : AudioSource {
            override suspend fun textVectors(ids: Collection<TrackId>): Map<TrackId, FloatArray> = ids.mapNotNull { id -> texts[id]?.let { id to it } }.toMap()
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
        // No wall-clock budgets on the bench (a busy host must not turn a plan into a fallback); LJ_STAND_BUDGETS=on keeps them.
        ranker.ignoreBudgets = System.getenv("LJ_STAND_BUDGETS") != "on"
        ranker.backfillImputations(library)
        // One warm plan resolves every library track (family, imputation): the facts the seed set is stratified by.
        ranker.plan(io.github.nikitasud.latentjam.smart.ranker.PlanRequest(library[0], library, library, 1))
        File(out, "facts.tsv").writeText(buildString {
            append("id\tfamily\te\thasVector\tlanguage\tlfmKnown\n")
            for (t in library) {
                append(t.id.value).append('\t').append(ranker.familyOf(t.id).orEmpty()).append('\t')
                    .append(energies[t.id]?.toString().orEmpty()).append('\t').append(index.contains(t.id))
                    .append('\t').append(ranker.languageOf(t.id).orEmpty()).append('\t').append(ranker.lfmKnownOf(t.id)).append('\n')
            }
        })
        if (System.getenv("LJ_STAND_FACTS_ONLY") == "1") return

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

        if (System.getenv("LJ_STAND_ENTITIES") == "1") {
            // Which seeds the pack resolves (cold seeds = entity -1): one plan fills the ranker's per-track knowledge.
            ranker.plan(io.github.nikitasud.latentjam.smart.ranker.PlanRequest(seeds.first(), library, library, length = 1))
            File(out, "seed_entities.tsv").writeText(seeds.joinToString("") { s ->
                "${s.id.value}\t${ranker.entityOf(s.id)}\t${ranker.languageOf(s.id)}\t${ranker.familyOf(s.id)}\n"
            })
            return
        }
        if (System.getenv("LJ_STAND_ALBUM") == "1") {
            albumCheck(seeds, library, ranker, historyLines, favourites, File(out, "album_check.tsv"))
            return
        }
        if (System.getenv("LJ_STAND_HABIT") == "1") {
            habit(byId, library, ranker, historyLines, favourites, userPlaylists, File(out, "habit.tsv"))
            return
        }
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
        // "Keep together" stand check: LJ_STAND_MARKS names a file in the library dir of "group<TAB>trackId" lines (the
        // marked playlists), given to the chooser as the app does — both the ranker and the chain read them.
        val marks = System.getenv("LJ_STAND_MARKS")?.let { name ->
            File(dir, name).takeIf { it.exists() }?.readLines()?.mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 } }
                ?.groupBy({ it[0] }, { TrackId(it[1]) })?.values?.map { it.toSet() }
        }.orEmpty()
        val queues = StringBuilder()
        for (seed in seeds) {
            for (full in listOf(false, true)) {
                // PLAN_TRANSGRAPH: LJ_STAND_NOW (epoch ms) fixes the plan clock (a years-old import must age from it).
                // PLAN_NOVELTY session mode (LJ_STAND_SESSION=1): the clock is 20 min after this library's last play, the
                // history is the copy's own afresh for every seed and mode, and each press's first 3 picks are played
                // (completed, autoplay) before the next press — what a listener re-pressing SMART has just heard.
                val session = System.getenv("LJ_STAND_SESSION") == "1"
                var now = if (session) lastListen + SESSION_CLOCK_MS else System.getenv("LJ_STAND_NOW")?.toLong() ?: (lastListen + 60_000)
                val seedHistory = if (!session) history else DefaultListeningHistory(object : HistoryStore {
                    override suspend fun append(line: String) = Unit
                    override suspend fun readAll(): List<String> = historyLines
                    override suspend fun clear() = Unit
                })
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
                // LJ_STAND_CHAIN_FULL=1: the chain's FULL as the phone runs it ("Learn from my habits": the habit slots on top).
                val chainFull = System.getenv("LJ_STAND_CHAIN_FULL") == "1"
                if (chain && full && !chainFull) continue
                // General stand (synthetic FMA libraries): LJ_STAND_CORE_ONLY=1 skips FULL (no history to personalise on).
                if (full && System.getenv("LJ_STAND_CORE_ONLY") == "1") continue
                val chooser = EngineNextTrackChooser(
                    engine = chainEngine ?: NoEngine.instance, history = seedHistory, library = { library },
                    useRanker = { !chain }, ranker = { ranker }, rankerHealthy = { assets.healthy },
                    learnFromHabits = { full && (!chain || chainFull) }, personalRanker = { full },
                    presses = SmartPresses(MemoryPresses()), smartLog = log,
                    favourites = { favourites }, useFavourites = { true }, clock = { now }, userPlaylists = { userPlaylists },
                    varietyScale = { varietyScale }, companionGroups = { marks },
                )
                suspend fun played(track: TrackDescriptor, at: Long, start: io.github.nikitasud.latentjam.history.ListenStart): Long {
                    val heard = track.durationMs ?: 180_000L
                    seedHistory.record(
                        io.github.nikitasud.latentjam.history.ListenEvent(
                            track.id, at, heard, track.durationMs, completed = true, skipped = false, listenedMs = heard,
                            origin = io.github.nikitasud.latentjam.history.ListenOrigin(start),
                        ),
                    )
                    return at + heard + SESSION_STEP_MS
                }
                for (press in 0 until (System.getenv("LJ_STAND_PRESSES")?.toInt() ?: PRESSES)) {
                    // Session mode: the press happens on the seed, which has just played to its end.
                    if (session) played(seed, now - (seed.durationMs ?: 180_000L) - SESSION_STEP_MS, io.github.nikitasud.latentjam.history.ListenStart.USER_PICK)
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
                    if (session) {
                        // Back to back, as the phone plays them: after the press, the first picks in order.
                        var at = now
                        for (track in queue.drop(1).take(SESSION_PLAYED)) at = played(track, at, io.github.nikitasud.latentjam.history.ListenStart.AUTO_ADVANCE)
                        now = maxOf(now + PRESS_GAP_MS, at + SESSION_AFTER_MS) - PRESS_GAP_MS   // the loop adds PRESS_GAP_MS
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
     * §19 AS check: each fixed seed on an album with at least three tracks here, pressed right after two other tracks of
     * that album (recentPlays 400 s and 200 s before) and with no such plays; how many of the first 10 picks stay on the
     * album, CORE and FULL. Product check only.
     */
    private suspend fun albumCheck(
        seeds: List<TrackDescriptor>, library: List<TrackDescriptor>, ranker: io.github.nikitasud.latentjam.smart.ranker.SmartRanker,
        historyLines: List<String>, favourites: Map<TrackId, Long?>, target: File,
    ) {
        val history = DefaultListeningHistory(object : HistoryStore {
            override suspend fun append(line: String) = Unit
            override suspend fun readAll(): List<String> = historyLines
            override suspend fun clear() = Unit
        })
        val events = history.recentEvents(10_000)
        val start = (events.firstOrNull()?.startedAtMs ?: 0L) + 3_600_000
        val ids = library.mapTo(HashSet()) { it.id }
        val listens = habitListens(events.asReversed(), ids, start)
        val artistKeys = library.associate { it.id to io.github.nikitasud.latentjam.smart.HabitSlots.artistKey(it) }
        fun key(t: TrackDescriptor) = io.github.nikitasud.latentjam.smart.ranker.AlbumKeys.albumKey(t.album, t.albumArtist, t.artist)
        val byAlbum = library.groupBy(::key).filterKeys { it.isNotEmpty() }
        val sb = StringBuilder("seed\tmode\talbumTracks\twithPrefix\twithout\n")
        for (seed in seeds) {
            val album = byAlbum[key(seed)]?.filter { it.id != seed.id } ?: continue
            if (album.size < 2) continue
            val recent = listOf(album[0].id to start - 400_000, album[1].id to start - 200_000)
            for (full in listOf(false, true)) {
                val personal = io.github.nikitasud.latentjam.smart.ranker.PersonalHistory(listens, start, if (full) favourites else emptyMap()) { artistKeys[it].orEmpty() }
                fun request(plays: List<Pair<TrackId, Long>>) = io.github.nikitasud.latentjam.smart.ranker.PlanRequest(
                    seed = seed, library = library, candidates = library.filter { it.id != seed.id && it.id !in recent.map { r -> r.first } },
                    length = 10, exclude = io.github.nikitasud.latentjam.smart.HabitSlots.novelty(listens, start), press = 0, history = personal,
                    personal = full, seedMs = start, recentPlays = plays,
                )
                val albumIds = album.mapTo(HashSet()) { it.id }
                val on = ranker.plan(request(recent)).ids.count { it in albumIds }
                val off = ranker.plan(request(emptyList())).ids.count { it in albumIds }
                sb.append("${seed.id.value}\t${if (full) "FULL" else "CORE"}\t${album.size + 1}\t$on\t$off\n")
            }
        }
        target.writeText(sb.toString())
    }

    /**
     * L1 mechanism probe ("seedId:full"): one press planned at the stand's clock with every pool row's terms at every
     * hop (PlanDiagnostics), each row's source, pack language and family. Product check only.
     */
    /** Night 2026-10-21 habit gate: every A with a follower B played ≥ 2 times right after it; B's rank in FULL / CORE press 0. */
    private suspend fun habit(
        byId: Map<TrackId, TrackDescriptor>, library: List<TrackDescriptor>,
        ranker: io.github.nikitasud.latentjam.smart.ranker.SmartRanker,
        historyLines: List<String>, favourites: Map<TrackId, Long?>, playlists: List<List<TrackId>>, target: File,
    ) {
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
        // The pairs listed are the same for every arm (normal pairs + vc21 pairs into an unskipped B: a pair into a B the
        // listener skipped is not a habit); each arm ranks with its own personal history (PLAN_NB: the FULL model's
        // skipPairs setting, as HabitSlotsPlanner applies it).
        fun personalWith(extra: List<Pair<TrackId, TrackId>>) = io.github.nikitasud.latentjam.smart.ranker.PersonalHistory(
            listens, start, favourites, extraTransitions = extra,
        ) { artistKeys[it].orEmpty() }
        val listing = personalWith(habitSkipTransitions(events.asReversed(), ids, start, unskippedOnly = true))
        val normalOnly = personalWith(emptyList())
        val personal = personalHistoryOf(
            events.asReversed(), ids, start, favourites, skipPairs = ranker.skipPairsFor(true),
            unskippedOnly = ranker.skipPairsUnskippedOnlyFor(true), lw = ranker.listenWeightFor(true),
        ) { artistKeys[it].orEmpty() }
        val exclude = io.github.nikitasud.latentjam.smart.HabitSlots.novelty(listens, start)
        val pairs = ArrayList<Triple<TrackId, TrackId, Int>>()
        for (a in listens.map { it.trackId }.distinct()) {
            if (a !in byId) continue
            for (b in listing.followers(a)) {
                val c = listing.followCount(a, b)
                if (c >= (System.getenv("LJ_STAND_HABIT_MIN")?.toInt() ?: 2) && b in byId) pairs += Triple(a, b, c)
            }
        }
        // PLAN_LISTENW: how many of each pair's plays of B were full-credit listens (completed or past the ListenBrainz
        // threshold) — the owner's pairs held up only by short plays are expected to drop under listen weighting
        val fullOnly = personalHistoryOf(
            events.asReversed().map { e -> if (listenCredit(e, 30_000L) >= 1.0) e else e.copy(skipped = true) }, ids, start, favourites,
            skipPairs = true, unskippedOnly = true, lw = null,
        ) { artistKeys[it].orEmpty() }
        val sb = StringBuilder("seed\tfollower\tcount\texcluded\tfullRank\tcoreRank\tnormalCount\tfullCount\n")
        for ((a, b, c) in pairs.sortedByDescending { it.third }.take(60)) {
            val seed = byId.getValue(a)
            suspend fun rank(full: Boolean): Int {
                val r = ranker.plan(
                    io.github.nikitasud.latentjam.smart.ranker.PlanRequest(
                        seed = seed, library = library, candidates = library.filter { it.id != seed.id }, length = 20,
                        exclude = exclude, press = 0, history = personal, personal = full, seedMs = start,
                        playlists = if (full) playlists else emptyList(),
                    ),
                )
                return r.ids.indexOf(b).let { if (it < 0) -1 else it + 1 }
            }
            sb.append("${a.value}\t${b.value}\t$c\t${b in exclude}\t${rank(true)}\t${rank(false)}\t${normalOnly.followCount(a, b)}\t${fullOnly.followCount(a, b)}\n")
        }
        target.writeText(sb.toString())
        // PLAN_NB ear test: vc21 pairs into a B the listener skipped (what r12aNB and r12aNBs both stop counting), from
        // the history only, with how often each occurs and its normal-pair count.
        val all = habitSkipTransitions(events.asReversed(), ids, start).groupingBy { it }.eachCount()
        val kept = habitSkipTransitions(events.asReversed(), ids, start, unskippedOnly = true).groupingBy { it }.eachCount()
        val skippedInto = all.mapValues { (k, v) -> v - (kept[k] ?: 0) }.filterValues { it > 0 }
        File(target.parentFile, "skip_pairs.tsv").writeText(
            "seed\tfollower\tcount\tnormalCount\n" + skippedInto.entries.sortedByDescending { it.value }
                .joinToString("") { (k, v) -> "${k.first.value}\t${k.second.value}\t$v\t${normalOnly.followCount(k.first, k.second)}\n" },
        )
    }

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
        val vectors = checkNotNull(readIndexSnapshotFile(versioned(files, "smart_index", AUDIO_MODEL_VERSION), AUDIO_MODEL_VERSION))
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
            config, backend, store(versioned(files, "smart_index", AUDIO_MODEL_VERSION).name), store("smart_text_index.bin"),
            store(versioned(files, "smart_energy", "semantic-energy-v1-$AUDIO_MODEL_VERSION").name), knowledge,
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

    /** The per-encoder snapshot file (VersionedIndexStore) when the copy has one, else the legacy single file. */
    private fun versioned(files: File, base: String, version: String): File =
        File(files, io.github.nikitasud.latentjam.smart.VersionedIndexStore.fileNameFor(base, version)).takeIf { it.isFile } ?: File(files, "$base.bin")

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
        /** PLAN_NOVELTY session mode: the press clock this long after the library's last play. */
        const val SESSION_CLOCK_MS: Long = 20L * 60L * 1_000L
        /** PLAN_NOVELTY session mode: rows of a press played (back to back) before the next press. */
        const val SESSION_PLAYED: Int = 3
        /** PLAN_NOVELTY session mode: the gap between two plays, and from the last play's end to the next press. */
        const val SESSION_STEP_MS: Long = 5_000L
        const val SESSION_AFTER_MS: Long = 60_000L
    }
}
