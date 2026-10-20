/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.HabitListen
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.text.ArtistNeighbours
import io.github.nikitasud.latentjam.smart.text.ArtistPopularity
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's whole plan path against the Python serving reference (tools/research/listenbrainz_eval/serving/
 * reference.py on the research branch; fixtures copied by tools/research/ranker/export_serving_fixtures.py): 17
 * synthetic libraries — n = 0 / 1 / 2 / 3 / 20, versions only, one artist's majority, no tags, unresolved artists, tagger
 * imputation, the retrievers' quotas, the rule-only path, the ladder, an external seed, artist popularity, non-music
 * folders and UTF-8 ids. Each request runs on a new [SmartRanker] fed the fixture's resolved records (the golden seam),
 * as the reference plans it. Criterion (PLAN_FINAL §1.6): identical ids and counters; features and scores within 1e-9
 * relative or 1e-12 absolute.
 */
class ServingReferenceGoldenTest {
    private val tagger by lazy {
        AudioTagger.parse(GoldenFiles.ml("audio_tagger.json").decodeToString(), AssetBytes.of(GoldenFiles.ml("audio_tagger.bin")))!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixtures(resource: String): List<Map<String, Any?>> =
        GoldenFiles.gzipText(resource.removePrefix("/")).lines()
            .filter(String::isNotEmpty).map { MiniJson(it).parse() as Map<String, Any?> }

    @Test
    fun plansMatchTheServingReference() = plansMatch("/serving_fixtures.jsonl.gz", fixtures = 21, requests = 41)

    /**
     * The same 21 libraries and requests with the shipped models swapped in (model_core_l1.json 6c23bd6c — the PLAN_L1 v5
     * credit-run decoder, provisional parameters — for every request, model_full_final.json 54fd0a4b for the
     * personal ones; rule-only kept), re-planned by reference.py (3e659ff8… at b6e710fc).
     */
    @Test
    fun plansWithTheShippedModelsMatchTheServingReference() = plansMatch("/serving_fixtures_final.jsonl.gz", fixtures = 21, requests = 41)

    private fun plansMatch(resource: String, fixtures: Int, requests: Int) = runTest {
        val all = fixtures(resource)
        assertEquals(fixtures, all.size)
        val expectedRequests = requests
        val failures = ArrayList<String>()
        var requests = 0
        for (fx in all) {
            val name = fx["file"] as String
            @Suppress("UNCHECKED_CAST")
            val input = fx.getValue("input") as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val expected = fx.getValue("expected") as List<Map<String, Any?>>
            val reqs = input.list("requests").map { it as Map<*, *> }
            for ((i, req) in reqs.withIndex()) {
                requests++
                val got = run(input, req)
                val bad = ArrayList<String>()
                compare(got, withoutStops(expected[i], got, bad), "", bad)
                if (bad.isNotEmpty()) failures += "$name[$i]: " + bad.take(6).joinToString("; ")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(expectedRequests, requests)
    }

    /** One request on a fresh ranker; the result in the reference's golden shape. */
    private suspend fun run(input: Map<String, Any?>, req: Map<*, *>): Map<String, Any?> {
        val records = input.list("library").map { it as Map<*, *> }
        val external = (req["seed"] as? Map<*, *>)
        val tracks = LinkedHashMap<String, TrackDescriptor>()
        val resolved = HashMap<String, ResolvedTrack>()
        for (r in records + listOfNotNull(external)) {
            val id = r["id"] as String
            tracks[id] = descriptor(id, r["tags"] as Map<*, *>)
            resolved[id] = resolvedOf(r["resolved"] as Map<*, *>)
        }
        val library = records.map { tracks.getValue(it["id"] as String) }
        @Suppress("UNCHECKED_CAST")
        val colisten = (input["colisten"] as? Map<String, List<List<Number>>>).orEmpty()
            .entries.associate { (e, l) -> e.toInt() to l.map { it[0].toInt() to it[1].toInt() } }
        @Suppress("UNCHECKED_CAST")
        val audioVectors = (input["audio"] as? Map<String, String?>).orEmpty().mapValues { (_, v) -> v?.let(::floats) }
        @Suppress("UNCHECKED_CAST")
        val audioNeighbours = (input["audioNeighbours"] as? Map<String, List<String>>).orEmpty()
        val audio = object : AudioSource {
            override suspend fun vector(id: TrackId): FloatArray? = audioVectors[id.value]
            override suspend fun neighbours(id: TrackId, k: Int): List<TrackId> = audioNeighbours[id.value].orEmpty().take(k).map(::TrackId)
        }
        @Suppress("UNCHECKED_CAST")
        val pop = (input["pop"] as? Map<String, Number>)?.entries?.associate { (e, v) -> e.toInt() to v.toFloat() }
        val model = if (req["ruleOnly"] == true) RankerModel.RULE_SET else RankerModel.parse(req["modelJson"] as String)!!
        val ranker = SmartRanker(
            model = model,
            knowledge = null,
            neighbours = if (colisten.isEmpty()) null else ArtistNeighbours.parse(neighboursAsset(colisten)),
            tagger = if (input["tagger"] == true) tagger else null,
            audio = audio,
            popularity = pop?.let { ArtistPopularity.parse(popularityAsset(it)) },
        )
        ranker.resolver = { t -> resolved[t.id.value] }
        val trace = PlanTrace()
        ranker.tracer = trace
        val seed = tracks.getValue(external?.get("id") as? String ?: req["seed"] as String)
        // FULL inputs (reference.py): listens [{id, ms, next}] of the request or the fixture before seedMs, the
        // request's favourites (ids, or {id: heart ms}); without listens the ladder's order comes from `history` ids.
        val personal = req["personal"] == true
        val seedMs = (req["seedMs"] as Number?)?.toLong() ?: 0L
        val listens = (req["listens"] ?: input["listens"]) as List<*>?
        val engineArtist = { id: TrackId -> resolved[id.value]?.engineArtist.orEmpty() }
        val favourites: Map<TrackId, Long?> = when (val f = req["favourites"]) {
            is List<*> -> f.associate { TrackId(it as String) to null }
            is Map<*, *> -> f.entries.associate { (k, v) -> TrackId(k as String) to (v as Number?)?.toLong() }
            else -> emptyMap()
        }.takeIf { personal }.orEmpty()
        val personalHistory = if (listens != null) {
            val plays = listens.map { it as Map<*, *> }
                .map { HabitListen(TrackId(it["id"] as String), (it["ms"] as Number).toLong(), it["next"] as Boolean? ?: true) }
                .filter { it.startedAtMs < seedMs }
            PersonalHistory(plays, seedMs, favourites, engineArtist)
        } else {
            val plays = input.list("history").mapIndexed { i, id -> HabitListen(TrackId(id as String), 1_000_000L + i * 60_000L) }
            PersonalHistory(plays, if (personal) seedMs else 1_000_000L + plays.size * 60_000L, favourites, engineArtist)
        }
        val request = PlanRequest(
            seed = seed,
            library = library,
            candidates = (req["candidates"] as? List<*>)?.map { tracks.getValue(it as String) } ?: library,
            length = (req["length"] as? Number)?.toInt() ?: 20,
            exclude = (req["exclude"] as? List<*>).orEmpty().map { TrackId(it as String) }.toSet(),
            recentIds = (req["recentIds"] as? List<*>).orEmpty().map { TrackId(it as String) },
            press = (req["press"] as? Number)?.toLong() ?: 0L,
            history = personalHistory,
            personal = personal,
            ruleOnly = req["ruleOnly"] == true,
        )
        val report = ranker.plan(request)
        val rep = mapOf(
            "ids" to report.ids.map { it.value }, "model" to report.model, "poolSize" to report.poolSize,
            "fromModel" to report.fromModel, "fromLadder" to report.fromLadder, "imputed" to report.imputed,
        )
        val n = request.candidates.filter { it.id != seed.id && !SmartRanker.isNonMusic(it) }.distinctBy { it.id }.size
        val tr = LinkedHashMap<String, Any?>(mapOf("model" to report.model, "n" to n))
        if (n > 0 && request.length > 0) {
            val pool = trace.pool
            tr["query"] = trace.query
            tr["poolHow"] = trace.poolHow
            tr["window"] = trace.window
            tr["pool"] = pool.map { it.value }
            tr["seedFeatures"] = trace.seedFeatures.map { row -> row.toList() }
            tr["hops"] = trace.hops.mapIndexed { h, hop ->
                mapOf(
                    "s" to hop.s.toList(), "t" to hop.t.map { if (it.isNaN()) null else it }, "sd" to hop.sd,
                    "penalty" to hop.penalty, "runArtist" to hop.runArtist, "runLength" to hop.runLength,
                    "capped" to hop.capped, "best" to hop.best, "sdPenalised" to hop.sdPenalised, "floor" to hop.floor,
                    "nearBest" to hop.nearBest.map { pool[it].value }, "u" to trace.draws[h]?.toULong()?.toString(),
                    "pick" to pool[hop.pick].value,
                )
            }
            tr["ladder"] = trace.ladder.map { (id, rung) -> mapOf("pick" to id.value, "rung" to rung) }
            tr["imputedTracks"] = trace.imputedTracks.map { it.value }
        }
        return mapOf("report" to rep, "trace" to tr)
    }

    /**
     * The reference ends a decoder that found no allowed row with a {stop} hop; the app's decoder just returns. That
     * hop is dropped here, and the app's plan must then have stopped early too.
     */
    private fun withoutStops(expected: Map<String, Any?>, got: Map<String, Any?>, bad: MutableList<String>): Map<String, Any?> {
        val trace = expected["trace"] as Map<*, *>
        // The app's feature rows may run past the reference's (columns appended later): those must read 0 there.
        (got["trace"] as MutableMap<String, Any?>?)?.let { t ->
            val width = ((trace["seedFeatures"] as List<*>?)?.firstOrNull() as List<*>?)?.size
            val rows = t["seedFeatures"] as List<*>?
            if (width != null && rows != null) {
                val popKnown = RankerFeatures.NAMES.indexOf("pop_known")
                val logpop = RankerFeatures.NAMES.indexOf("logpop")
                t["seedFeatures"] = rows.map { row ->
                    val r = row as List<*>
                    for (j in width until r.size) {
                        // pop_known can be checked against the row itself: a known artist has logpop = ln(1 + listeners) > 0.
                        val want = if (j == popKnown) (if (r[logpop] != 0.0) 1.0 else 0.0) else 0.0
                        if (r[j] != want) bad += "trace.seedFeatures: ${RankerFeatures.NAMES[j]} = ${r[j]}, expected $want"
                    }
                    r.take(width)
                }
            }
        }
        val hops = trace["hops"] as List<*>? ?: return expected
        val kept = hops.filter { (it as Map<*, *>)["stop"] == null }
        if (kept.size == hops.size) return expected
        val report = got["report"] as Map<*, *>
        val length = minOf(20, report["poolSize"] as Int)
        if ((report["fromModel"] as Int) >= length) bad += "trace.hops: the reference stopped, the app did not"
        return expected + ("trace" to (trace + ("hops" to kept)))
    }

    private fun descriptor(id: String, t: Map<*, *>) = TrackDescriptor(
        id = TrackId(id),
        title = t["title"] as String?,
        artist = t["artist"] as String?,
        album = t["album"] as String?,
        genre = t["genre"] as String?,
        durationMs = (t["durationMs"] as Number?)?.toLong(),
        folderPath = t["folderPath"] as String?,
        sizeBytes = (t["sizeBytes"] as Number?)?.toLong(),
        fileName = t["fileName"] as String?,
        storageVolume = t["storageVolume"] as String?,
        artists = (t["artists"] as List<*>?).orEmpty().map { it as String },
    )

    private fun resolvedOf(r: Map<*, *>) = ResolvedTrack(
        engineArtist = r["engineArtist"] as String? ?: "",
        setArtist = (r["setArtist"] as String?)?.takeIf(String::isNotEmpty)?.toLong() ?: 0L,
        artists = (r["artistSet"] as List<*>?).orEmpty().map { (it as String).toLong() }.toLongArray(),
        family = r["family"] as String?,
        language = r["language"] as String?,
        decade = (r["decade"] as Number?)?.toInt(),
        entity = (r["entity"] as Number?)?.toInt() ?: -1,
        version = r["version"] as String,
        descriptor = (r["descriptor"] as String?)?.let(::floats),
        lfm = (r["lfm"] as String?)?.let(::floats),
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun floats(b64: String): FloatArray {
        val b = Base64.decode(b64)
        fun u8(i: Int) = b[i].toInt() and 0xff
        return FloatArray(b.size / 4) { Float.fromBits(u8(it * 4) or (u8(it * 4 + 1) shl 8) or (u8(it * 4 + 2) shl 16) or (u8(it * 4 + 3) shl 24)) }
    }

    private fun Map<String, Any?>.list(key: String): List<Any?> = (this[key] as List<*>?).orEmpty()

    /** reference.compare: dicts by key, lists by position, floats within 1e-9 relative or 1e-12 absolute. */
    private fun compare(a: Any?, b: Any?, path: String, bad: MutableList<String>) {
        when {
            a is Map<*, *> && b is Map<*, *> -> for (k in a.keys + b.keys) {
                if (k !in a || k !in b) { if (k != "edge") bad += "$path.$k: missing"; continue }
                compare(a[k], b[k], "$path.$k", bad)
            }
            a is List<*> && b is List<*> ->
                if (a.size != b.size) bad += "$path: length ${a.size} != ${b.size} ($a vs $b)".take(400)
                else for (i in a.indices) compare(a[i], b[i], "$path[$i]", bad)
            a is Double || (b is Double && a !is Int) -> {
                val x = (a as Number?)?.toDouble()
                val y = (b as Number?)?.toDouble()
                if (x == null || y == null || !(abs(x - y) <= 1e-12 || abs(x - y) <= 1e-9 * max(abs(x), abs(y)))) bad += "$path: $a != $b"
            }
            a is Int || a is Boolean || a is String || a == null ->
                if (normal(a) != normal(b)) bad += "$path: $a != $b"
            else -> bad += "$path: ${a::class} vs $b"
        }
    }

    private fun normal(v: Any?): Any? = if (v is Number) v.toDouble() else v

    /** The LJNBRS1 layout (pack_colisten.py) from the fixture's lists. */
    private fun neighboursAsset(lists: Map<Int, List<Pair<Int, Int>>>): ByteArray {
        val space = (lists.keys.maxOrNull() ?: 0) + 1
        val out = ByteSink()
        fun u8(v: Int) = out.write(v and 0xff)
        fun u32(v: Long) { for (b in 0 until 4) u8(((v ushr (8 * b)) and 0xff).toInt()) }
        out.write("LJNBRS1\u0000".encodeToByteArray())
        u32(1); u32(space.toLong()); u32(lists.size.toLong()); u8(4); u8(0)
        val bits = LongArray((space + 63) / 64)
        for (e in lists.keys) bits[e ushr 6] = bits[e ushr 6] or (1L shl (e and 63))
        for (w in bits) for (b in 0 until 8) u8(((w ushr (8 * b)) and 0xff).toInt())
        for (block in 0 until (space + 511) / 512 + 1) u32(lists.keys.count { it < block * 512 }.toLong())
        val sorted = lists.entries.sortedBy { it.key }
        var offset = 0L
        u32(0)
        for ((_, l) in sorted) { offset += l.size; u32(offset) }
        for ((_, l) in sorted) for ((n, s) in l) { u8(n); u8(n ushr 8); u8(n ushr 16); u8(s) }
        return out.toByteArray()
    }

    /** The LJPOPS1 layout (serving/artist_pop.py): LJVECS1 with dimension 1. */
    private fun popularityAsset(values: Map<Int, Float>): ByteArray {
        val space = (values.keys.maxOrNull() ?: 0) + 1
        val out = ByteSink()
        fun u8(v: Int) = out.write(v and 0xff)
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Long) { for (b in 0 until 4) u8(((v ushr (8 * b)) and 0xff).toInt()) }
        out.write("LJPOPS1\u0000".encodeToByteArray())
        u32(1); u32(space.toLong()); u32(values.size.toLong()); u16(1); u16(4)
        val bits = LongArray((space + 63) / 64)
        for (e in values.keys) bits[e ushr 6] = bits[e ushr 6] or (1L shl (e and 63))
        for (w in bits) for (b in 0 until 8) u8(((w ushr (8 * b)) and 0xff).toInt())
        for (block in 0 until (space + 511) / 512 + 1) u32(values.keys.count { it < block * 512 }.toLong())
        for ((_, v) in values.entries.sortedBy { it.key }) u32(v.toRawBits().toLong() and 0xffffffffL)
        return out.toByteArray()
    }
}

/** A growable byte buffer (common code has no ByteArrayOutputStream). */
private class ByteSink {
    private var bytes = ByteArray(1024)
    private var size = 0
    fun write(b: Int) {
        if (size == bytes.size) bytes = bytes.copyOf(size * 2)
        bytes[size++] = b.toByte()
    }
    fun write(b: ByteArray) = b.forEach { write(it.toInt()) }
    fun toByteArray(): ByteArray = bytes.copyOf(size)
}

/** The golden fixtures (gzipped, test resources) and the shipped ml/ assets, read per platform. */
internal expect object GoldenFiles {
    fun gzipText(name: String): String
    fun ml(name: String): ByteArray
}
